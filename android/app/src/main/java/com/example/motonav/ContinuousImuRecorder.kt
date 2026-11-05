package com.example.motonav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Navigation-scoped raw recording. No incident decisions or contact actions. */
object ContinuousImuRecorder {
    /** Fast read-only observation before the disk queue; no storage ACK semantics change. */
    @Volatile var liveBatchObserver: ((ContinuousImuProtocol.Batch, Long) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val jobs = ArrayBlockingQueue<() -> Unit>(64)
    private val assembler = ContinuousImuProtocol.Assembler(3000)
    private val queueRejected = AtomicLong()
    private val badPackets = AtomicLong()
    @Volatile private var lifecycleQueueFailed = false
    private val random = SecureRandom()
    private lateinit var context: Context
    private lateinit var store: ContinuousImuStore
    private var initialized = false

    @Volatile var message = "导航开始后自动记录六轴数据"
        private set
    @Volatile var latestDirectory: File? = null
        private set
    @Volatile private var publishedSession = 0L
    @Volatile private var publishedBoot = 0L
    // Everything below is confined to the one bounded disk worker.
    private var session = 0L
    private var boot = 0L
    private var durableNext = 0L
    private var navigating = false
    private var stoppingAt = 0L
    private var firstRequestedAt = 0L
    private var lastReceivedAt = 0L
    private var lastControlAt = 0L
    private var lastIdleQueryAt = 0L
    private var linkWasReady = false
    private var annotatedQueueRejected = 0L
    private var annotatedBadPackets = 0L

    @Synchronized fun init(c: Context) {
        if (initialized) return
        initialized = true
        context = c.applicationContext
        Thread({
            try {
                val root = File(context.noBackupFilesDir, "imu_records")
                store = ContinuousImuStore(root)
                val recovered = store.recoverUnfinished()
                // Finished sessions are not returned by recovery, but must
                // remain exportable after a normal app restart as well.
                var latest: File? = null
                root.listFiles()?.filter { it.isDirectory }?.forEach { day ->
                    day.listFiles()?.filter { it.isDirectory }?.forEach { recording ->
                        if (latest == null || recording.lastModified() > latest!!.lastModified()) latest = recording
                    }
                }
                latestDirectory = latest
                if (recovered.isNotEmpty()) message = "上次连续记录已按中断恢复，可导出检查"
            } catch (e: Exception) {
                message = "连续记录初始化失败：${e.message}"
                return@Thread
            }
            var lastTick = 0L
            while (true) {
                try {
                    jobs.poll(200, TimeUnit.MILLISECONDS)?.invoke()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTick >= 1000) { lastTick = now; tick(now) }
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }, "MotoNav-imu-files").apply { isDaemon = true; start() }
    }

    private fun offer(critical: Boolean = false, action: () -> Unit) {
        if (!jobs.offer(action)) {
            queueRejected.incrementAndGet()
            if (critical) lifecycleQueueFailed = true
        }
    }

    fun navigationStarted(mode: String, title: String, simulation: Boolean) {
        offer(critical = true) {
            if (mode != "导航" && mode != "网页摩托车") {
                requestStop()
                return@offer
            }
            if (session != 0L) finish("new_navigation_tail_unknown")
            var id: Long
            do { id = random.nextInt().toLong() and 0xffffffffL } while (id == 0L)
            val now = SystemClock.elapsedRealtime()
            store.start(id, mode, title, simulation, System.currentTimeMillis(), now)
            session = id; boot = 0; durableNext = 0; navigating = true; stoppingAt = 0
            publishedSession = id; publishedBoot = 0
            firstRequestedAt = now; lastReceivedAt = 0; lastControlAt = 0
            annotatedQueueRejected = queueRejected.get(); annotatedBadPackets = badPackets.get()
            latestDirectory = store.snapshot().directory
            message = "连续记录已开启，等待圆屏确认${if (simulation) "（模拟导航）" else ""}"
            tick(now)
        }
    }

    fun navigationStopped() = offer(critical = true) { requestStop() }

    private fun requestStop() {
        if (session == 0L || !navigating) return
        navigating = false; stoppingAt = SystemClock.elapsedRealtime(); lastControlAt = 0
        message = "导航已结束，正在保存圆屏缓存"
        control(2)
    }

    /** The existing service is about to close BLE; a drain cannot be promised. */
    fun serviceStopped() {
        val id = publishedSession
        if (id != 0L) BleLink.sendImuRecordControl(ContinuousImuProtocol.encodeControl(2, id, publishedBoot, 0))
        offer(critical = true) { if (session != 0L) finish("service_stopped_tail_unknown") }
    }

    /** Called on BLE's main-thread dispatcher. Reassembly is at most 308 bytes. */
    fun onNotification(bytes: ByteArray, receivedAt: Long): Boolean {
        if (bytes.size < 2 || (bytes[0].toInt() and 255) != 0xd7 || (bytes[1].toInt() and 255) != 0x52) return false
        val batch = try { assembler.accept(bytes, receivedAt) }
        catch (_: IllegalArgumentException) { badPackets.incrementAndGet(); null }
        val wall = System.currentTimeMillis()
        if (batch != null) {
            liveBatchObserver?.invoke(batch, receivedAt)
            offer { receive(batch, receivedAt, wall) }
        }
        return true
    }

    private fun receive(batch: ContinuousImuProtocol.Batch, receivedAt: Long, wall: Long) {
        if (session == 0L || batch.session != session) {
            // A process/phone restart does not resume navigation. Stop an orphan
            // session on reconnect; its old file remains marked interrupted.
            if (batch.session != 0L && batch.flags and 1 != 0)
                BleLink.sendImuRecordControl(ContinuousImuProtocol.encodeControl(2, batch.session, batch.boot, 0))
            return
        }
        val first = lastReceivedAt == 0L
        durableNext = store.append(batch, wall, receivedAt) // includes fsync; never ACK queued-only bytes
        boot = batch.boot; publishedBoot = boot; lastReceivedAt = receivedAt
        if (first) {
            store.note("firmware", BleLink.screenFirmware)
            store.note("screen", BleLink.screenName)
        }
        control(3, durableNext)
        val stats = store.snapshot()
        latestDirectory = stats.directory
        message = "${if (navigating) "连续记录中" else "正在保存缓存"} · 已保存 ${stats.writtenSamples} 条\n序号缺口 ${stats.gapSamples} 条 · 漏采槽位 ${stats.missedSlots}"
        if (batch.flags and 12 != 0) {
            control(2)
            finish(if (batch.flags and 8 != 0) "board_buffer_allocation_failed" else "imu_unavailable")
            message = "连续记录失败：${if (batch.flags and 8 != 0) "圆屏缓存分配失败" else "IMU 不可用"}"
        } else if (batch.flags and 2 != 0 && durableNext >= batch.nextSeq) {
            val interrupted = navigating
            finish(if (interrupted) "board_stopped_unexpectedly" else "navigation_ended")
        }
    }

    private fun control(opcode: Int, argument: Long = 0) {
        if (session != 0L) BleLink.sendImuRecordControl(ContinuousImuProtocol.encodeControl(opcode, session, boot, argument))
    }

    private fun tick(now: Long) {
        if (lifecycleQueueFailed) {
            lifecycleQueueFailed = false
            control(2)
            if (session != 0L) finish("phone_lifecycle_queue_full_tail_unknown")
            message = "连续记录已中断：手机写入队列过载"
        }
        val ready = BleLink.isConnected && BleLink.verified && !BleLink.otaInProgress
        if (ready && !linkWasReady) lastControlAt = 0
        linkWasReady = ready
        if (session == 0L) {
            if (ready && now - lastIdleQueryAt >= 10000) {
                lastIdleQueryAt = now
                if (BleLink.supportsContinuousImu)
                    BleLink.sendImuRecordControl(ContinuousImuProtocol.encodeControl(4, 0, 0, 0))
            }
            return
        }
        annotateCounters()
        if (stoppingAt != 0L && now - stoppingAt > 180000) {
            finish("stop_timeout_tail_unknown")
            return
        }
        if (ready && now - lastControlAt >= 3000) {
            lastControlAt = now
            if (BleLink.supportsContinuousImu) control(if (navigating) 1 else 2, durableNext)
            else BleLink.send("#GB?")
        }
        if (!ready) message = if (BleLink.otaInProgress) "固件升级中，连续记录暂不可传输" else "圆屏连接中断，等待重连补传有限缓存"
        else if (lastReceivedAt == 0L && now - firstRequestedAt > 15000)
            message = "圆屏未确认连续记录，请检查测试固件和 IMU 状态"
        else if (lastReceivedAt != 0L && now - lastReceivedAt > 10000)
            message = "连续记录数据暂未更新，等待圆屏响应"
    }

    private fun annotateCounters() {
        val rejected = queueRejected.get()
        val invalid = badPackets.get()
        if (rejected != annotatedQueueRejected) { store.note("phone_queue_rejected", rejected.toString()); annotatedQueueRejected = rejected }
        if (invalid != annotatedBadPackets) { store.note("protocol_errors", invalid.toString()); annotatedBadPackets = invalid }
    }

    private fun finish(reason: String) {
        annotateCounters()
        store.stop(reason, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val stats = store.snapshot()
        latestDirectory = stats.directory
        message = if (reason == "navigation_ended") "连续记录已保存 · ${stats.writtenSamples} 条\n序号缺口 ${stats.gapSamples} 条 · 漏采槽位 ${stats.missedSlots}${if (stats.tailUnknown) " · 存在未确认片段" else ""}"
            else "连续记录已保存 · ${when (reason) {
                "new_navigation_tail_unknown" -> "已切换新导航，旧记录尾部未确认"
                "service_stopped_tail_unknown" -> "后台服务已关闭，尾部未确认"
                "stop_timeout_tail_unknown" -> "缓存保存超时，尾部未确认"
                "board_stopped_unexpectedly" -> "圆屏意外停止采样"
                "phone_lifecycle_queue_full_tail_unknown" -> "手机写入队列过载，尾部未确认"
                "imu_unavailable" -> "IMU 不可用"
                "board_buffer_allocation_failed" -> "圆屏缓存分配失败"
                else -> "记录异常结束，详情见导出文件"
            }}"
        session = 0; boot = 0; publishedSession = 0; publishedBoot = 0
        navigating = false; stoppingAt = 0
    }

    private fun fail(e: Exception) {
        control(2)
        runCatching { if (session != 0L) store.stop("storage_error_tail_unknown", System.currentTimeMillis(), SystemClock.elapsedRealtime()) }
        session = 0; boot = 0; publishedSession = 0; publishedBoot = 0; navigating = false; stoppingAt = 0
        message = "连续记录保存失败：${e.message}；处理存储问题后请重启 App"
        android.util.Log.e("MotoNavIMU", message, e)
    }

    /** Stream one session into a bounded temporary ZIP; never load a ride into RAM. */
    fun exportLatest(callback: (File?, String?) -> Unit) = offer {
        val result = runCatching {
            val directory = latestDirectory ?: error("还没有连续记录，请先开始一次导航")
            val output = File(context.cacheDir, "imu_exports").apply { check(isDirectory || mkdirs()) }
            output.listFiles()?.filter { it.extension == "zip" }?.forEach { check(it.delete()) }
            val archive = File(output, "MotoNav-IMU-${directory.name}.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip, 8192) }; zip.closeEntry()
                }
            }
            archive
        }
        main.post { callback(result.getOrNull(), result.exceptionOrNull()?.message) }
    }
}
