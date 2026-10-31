package com.example.motonav

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * Android bridge for the P1 accident record-only session.
 *
 * The monitor is explicitly started by the user, consumes the existing nav10
 * diagnostic IMU stream and navigation/cruise fixes, and writes a bounded CSV
 * snapshot to the app-private files directory. It never decides that an
 * accident happened and canNotify() is permanently false in this phase.
 */
object AccidentRecordMonitor : BleLink.ImuListener {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var attached = false
    private var pendingStart = false
    private var ownsImuStream = false
    private var coordinator: AccidentRecordCoordinator? = null
    private var latestStatus: AccidentImuSampleParser.Status? = null
    private var latestGps: AccidentRecordSession.GpsFix? = null
    private var latestHealth: AccidentImuSampleParser.Health? = null
    private var lastPersistedFrameCount = -1

    @Volatile var message: String = "未开启"
        private set
    @Volatile var lastFilePath: String = ""
        private set

    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                coordinator?.let {
                    it.tick(SystemClock.elapsedRealtime())
                    if (it.state() != AccidentRecordSession.State.DISABLED &&
                        it.state() != AccidentRecordSession.State.STOPPED) persistLocked(it)
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    /** Attach once from NavService and MainActivity; the service keeps it alive in background. */
    fun attach(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (!attached) {
                attached = true
                BleLink.setImuListener(this)
                NaviBridge.accidentLocationObserver = { fix -> onGps(fix) }
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }
    }

    /** Start an explicitly user-requested P1 record session. */
    fun start(): String {
        synchronized(lock) {
            if (!attached) return "后台监测尚未初始化"
            val existing = coordinator
            if (existing != null && existing.state() != AccidentRecordSession.State.DISABLED &&
                existing.state() != AccidentRecordSession.State.STOPPED) {
                return "只记录已经在运行"
            }
            if (!BleLink.isConnected) {
                message = "无法开始：圆屏未连接"
                return message
            }
            var status = latestStatus ?: parseCurrentStatusLocked()
            if (status == null) {
                pendingStart = true
                BleLink.setImuDiagnosticsStream(true)
                BleLink.requestImuDiagnosticsStatus()
                message = "已请求 IMU 状态，收到 nav10 状态后开始"
                return message
            }
            if (!status.present || !status.selfTestKnown || !status.selfTestOk || !status.configured) {
                message = "无法开始：IMU 未通过自检或未配置"
                return message
            }
            if (!status.streamEnabled) {
                pendingStart = true
                ownsImuStream = true
                BleLink.setImuDiagnosticsStream(true)
                BleLink.requestImuDiagnosticsStatus()
                message = "正在开启 IMU 只记录流，收到状态后开始"
                return message
            }
            beginLocked(status)
            return message
        }
    }

    fun stop(): String {
        synchronized(lock) {
            pendingStart = false
            val c = coordinator ?: run {
                message = "只记录尚未开始"
                return message
            }
            c.stop()
            persistLocked(c)
            if (ownsImuStream) {
                BleLink.setImuDiagnosticsStream(false)
                ownsImuStream = false
            }
            message = "只记录已停止，已保存 ${c.snapshotSummary().frameCount} 帧"
            return message
        }
    }

    fun clear(): String {
        synchronized(lock) {
            pendingStart = false
            coordinator?.disable()
            coordinator = null
            if (ownsImuStream) {
                BleLink.setImuDiagnosticsStream(false)
                ownsImuStream = false
            }
            latestHealth = null
            lastPersistedFrameCount = -1
            lastFilePath.takeIf { it.isNotEmpty() }?.let { runCatching { File(it).delete() } }
            lastFilePath = ""
            message = "只记录数据已清除"
            return message
        }
    }

    fun canNotify(): Boolean = false

    fun statusText(): String {
        synchronized(lock) {
            val c = coordinator ?: return message
            val s = c.snapshotSummary()
            return "P1 只记录：${s.state} · ${s.frameCount} 帧 · 丢弃 ${s.droppedFrames} · 无效 ${s.invalidSamples}" +
                "\nGPS ${if (s.gpsKnown) "有效" else "未知"} · IMU 健康摘要 ${if (s.imuHealthKnown) "已收到" else "未收到"}" +
                "\n自动通知：关闭 · ${if (lastFilePath.isEmpty()) "尚未保存" else "已保存到 App 私有目录"}"
        }
    }

    /** Copy the latest bounded snapshot as text; no contacts or continuous audio are included. */
    fun copyLatest(context: Context): String {
        synchronized(lock) {
            val c = coordinator
            val text = if (c != null && c.snapshot().isNotEmpty()) csvLocked(c)
            else lastFilePath.takeIf { it.isNotEmpty() }?.let { path ->
                runCatching { File(path).readText() }.getOrNull()
            }.orEmpty()
            if (text.isEmpty()) return "没有可导出的只记录数据"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MotoNav 事故只记录", text))
            return "已复制 ${text.lineSequence().drop(1).count()} 行记录"
        }
    }

    override fun onImuStatus(status: AccidentImuSampleParser.Status, receivedAtMonotonicMs: Long) {
        synchronized(lock) {
            latestStatus = status
            val c = coordinator
            if (pendingStart && status.streamEnabled) {
                pendingStart = false
                beginLocked(status)
            } else if (c != null) {
                c.onStatus(status, BleLink.isConnected, 0L, 0L, true, receivedAtMonotonicMs)
            }
        }
    }

    override fun onImuHealth(health: AccidentImuSampleParser.Health) {
        synchronized(lock) {
            latestHealth = health
            coordinator?.onHealth(health, health.receivedAtMonotonicMs)
        }
    }

    override fun onImuSample(sample: AccidentImuSampleParser.Sample) {
        synchronized(lock) {
            val c = coordinator ?: return
            c.onImu(sample)
            val count = c.snapshotSummary().frameCount
            if (count > 0 && count % 25 == 0 && count != lastPersistedFrameCount) persistLocked(c)
        }
    }

    private fun onGps(fix: AccidentRecordSession.GpsFix) {
        synchronized(lock) {
            latestGps = fix
            coordinator?.onGps(fix)
        }
    }

    private fun beginLocked(status: AccidentImuSampleParser.Status) {
        val c = AccidentRecordCoordinator()
        coordinator = c
        latestHealth = null
        val state = c.begin(
            SystemClock.elapsedRealtime(), status, BleLink.isConnected,
            0L, 0L, true, latestGps)
        message = "只记录已开始：$state（P1，不判定事故、不通知联系人）"
        lastPersistedFrameCount = -1
    }

    private fun parseCurrentStatusLocked(): AccidentImuSampleParser.Status? {
        val raw = BleLink.screenImuStatus
        return (AccidentImuSampleParser.parse(raw, SystemClock.elapsedRealtime())
            as? AccidentImuSampleParser.Status)
    }

    private fun persistLocked(c: AccidentRecordCoordinator) {
        val context = appContext ?: return
        val frames = c.snapshotSummary().frameCount
        if (frames == 0 && lastFilePath.isNotEmpty()) return
        val dir = File(context.filesDir, "accident-record").apply { mkdirs() }
        val tmp = File(dir, "latest.csv.tmp")
        val dst = File(dir, "latest.csv")
        runCatching {
            tmp.writeText(csvLocked(c), Charsets.UTF_8)
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) throw IllegalStateException("无法保存记录")
            lastFilePath = dst.absolutePath
            lastPersistedFrameCount = frames
        }.onFailure { message = "只记录保存失败：${it.message ?: "未知错误"}" }
    }

    private fun csvLocked(c: AccidentRecordCoordinator): String {
        val out = StringBuilder()
        val firmware = BleLink.screenFirmware.ifBlank { "未知固件" }
        out.append("# MotoNav P1 只记录；圆屏 $firmware；不代表事故判定\n")
        out.append("sequence,received_monotonic_ms,board_ms,ax_mps2,ay_mps2,az_mps2,gx_dps,gy_dps,gz_dps,temp_c,gps_lat,gps_lng,speed_kmh,gps_accuracy_m,gps_age_ms\n")
        for (frame in c.snapshot()) {
            val imu = frame.imu
            val gps = frame.gps
            out.append(String.format(Locale.US,
                "%d,%d,%d,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.2f,",
                frame.sequence, frame.receivedAtMonotonicMs, imu.boardMillis,
                imu.accelerationXMps2, imu.accelerationYMps2, imu.accelerationZMps2,
                imu.gyroXDegPerSec, imu.gyroYDegPerSec, imu.gyroZDegPerSec, imu.temperatureC))
            if (gps == null) out.append(",,,,\n")
            else out.append(String.format(Locale.US, "%.7f,%.7f,%.2f,%.2f,%d\n",
                gps.latitude, gps.longitude, gps.speedKmh, gps.accuracyM, frame.gpsAgeMs))
        }
        return out.toString()
    }
}
