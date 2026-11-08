package com.example.motonav

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

data class EmergencyConfig(val name: String, val phone: String, val prompt: String, val seconds: Int,
    val subscriptionId: Int, val realSms: Boolean = false)

/** Explicit, single-use brake TEST. The main looper serializes cancellation and SMS claim. */
object EmergencyAlertController {
    const val DEFAULT_PROMPT = "检测到您急刹了，若您平安无事，长按圆屏或手机弹窗确认取消报警"
    private const val CHANNEL = "motonav_brake_test_v1"
    private const val NOTIFICATION = 7081
    private val main = Handler(Looper.getMainLooper())
    private val detector = BrakeTestDetector()
    private lateinit var context: Context
    private lateinit var directory: File
    private var initialized = false
    private var storageReady = false
    private var storageError = "报警存储尚未就绪"
    private var saved = EmergencyConfig("", "", DEFAULT_PROMPT, 30, -1)
    private var sequence = 0L
    private var model: EmergencyAlertState? = null
    private var event: JSONObject? = null
    private var eventConfig = saved
    private var foreground = WeakReference<Activity>(null)
    private var navigation = false
    private var simulation = false
    private var wantedArm = false
    private var lastFix: Fix? = null
    private var lastBoardBoot = -1L
    private var lastBoardNow = -1L
    private var boardClock = 0L
    private var minimumOffset = Long.MAX_VALUE
    private var lastSampleSeq = -1L
    private var liveSince = 0L
    private var linkReady = false
    private var lastWireAt = 0L
    private var sentResults = IntArray(0)
    private var deliveryResults = IntArray(0)
    private var activeSince = 0L
    private var gpsGateAt = -1L
    private var imuGateAt = -1L
    private data class TraceState(var value: String, var observedAt: Long, var fields: Map<String, String> = emptyMap(), var emittedAt: Long = -1,
        var changes: Int = 1, var emitted: Boolean = false)
    private val traceStates = linkedMapOf<String, TraceState>() // Four fixed state keys, never per-sample history.
    private data class Fix(val lat: Double, val lng: Double, val accuracy: Double, val fixAt: Long, val wall: Long)

    @Volatile var status = "急刹测试未开启"; private set
    @Volatile var eventToken = 0L; private set
    @Volatile var stateName = "IDLE"; private set
    @Volatile var remainingSeconds = 0; private set
    @Volatile var alertPrompt = DEFAULT_PROMPT; private set
    val armed: Boolean get() = wantedArm || detector.isArmed()

    fun init(application: Application) {
        if (initialized) return
        initialized = true; context = application.applicationContext
        directory = File(context.noBackupFilesDir, "emergency_test")
        if (runCatching { check(directory.isDirectory || directory.mkdirs()) { "无法创建报警记录目录" } }
                .onFailure { storageError = "报警存储不可用：${it.message}；已关闭短信发送"; status = storageError }.isFailure) return
        storageReady = true
        runCatching {
            val config = read("config.json")
            sequence = config?.optLong("sequence", 0) ?: 0
            if (config != null) saved = decodeConfig(config)
            val old = read("latest.json")
            if (old != null) {
                event = old; eventToken = old.getLong("token"); alertPrompt = old.optString("prompt", DEFAULT_PROMPT)
                eventConfig = decodeConfig(old.getJSONObject("config"))
                model = EmergencyAlertState.restore(hex(eventToken), old.getLong("startMonotonicMs"), old.getLong("deadlineMonotonicMs"),
                    old.getInt("seconds"), EmergencyAlertState.Phase.valueOf(old.getString("phase")))
                stateName = if (model!!.phase == EmergencyAlertState.Phase.PROCESS_INTERRUPTED) "INTERRUPTED" else old.optString("state", "UNKNOWN")
                sentResults = decodeResults(old.optJSONArray("sentResults")); deliveryResults = decodeResults(old.optJSONArray("deliveryResults"))
                if (stateName == "INTERRUPTED") {
                    status = "上次报警流程因进程/手机重启中断，未自动补发短信"
                    persist()
                    audit("alert_interrupted", eventToken, "reason" to "process_restart", "state" to stateName)
                } else status = stateText(stateName)
            }
        }.onFailure { storageReady = false; storageError = "报警设置/记录读取失败：${it.message}；已关闭短信发送"; status = storageError }
        createChannel()
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { foreground = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (foreground.get() === a) foreground.clear() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        ContinuousImuRecorder.liveBatchObserver = { batch, received -> onBatch(batch, received) }
        runCatching { ContinuousImuRecorder.recordDetectorConfiguration(mapOf(
            "dynamic_threshold_mps2" to BrakeTestDetector.DYNAMIC_THRESHOLD_MPS2.toString(),
            "dynamic_duration_ms" to BrakeTestDetector.DYNAMIC_DURATION_MS.toString(),
            "max_gps_window_ms" to BrakeTestDetector.MAX_GPS_WINDOW_MS.toString(),
            "max_imu_age_ms" to BrakeTestDetector.MAX_IMU_AGE_MS.toString(),
            "max_gps_age_ms" to BrakeTestDetector.MAX_GPS_AGE_MS.toString(),
            "evidence_window_ms" to BrakeTestDetector.EVIDENCE_WINDOW_MS.toString())) }
        detectorTrace(force = true)
        main.post(tick)
    }

    fun config(): EmergencyConfig = saved
    fun save(value: EmergencyConfig): String? {
        if (!initialized || !storageReady) return storageError
        if (busy()) return "倒计时或短信发送中不能更改设置"
        val name = value.name.trim(); val phone = value.phone.replace(Regex("[\\s()-]"), "")
        if (name.isEmpty() || name.length > 30 || name.any { it.isISOControl() }) return "请输入 1–30 字的骑行人姓名"
        if (!Regex("\\+?[0-9]{7,15}").matches(phone)) return "请输入有效的紧急联系人手机号（7–15 位，可带 +）"
        if (value.prompt.isBlank() || value.prompt.length > 100) return "提示文字需要 1–100 字"
        if (value.seconds !in 0..60) return "倒计时需要在 0–60 秒之间"
        val next = value.copy(name = name, phone = phone, prompt = value.prompt.trim())
        return runCatching {
            write("config.json", configJson(next).put("sequence", sequence))
            saved = next; null
        }.getOrElse { "保存设置失败：${it.message}" }
    }

    fun readiness(realSms: Boolean): String? {
        if (!initialized) return "报警模块尚未初始化"
        if (!storageReady) return storageError
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "请先开启 MotoNav 通知"
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return "请开启“急刹测试倒计时”通知渠道"
        if (realSms) return runCatching { EmergencySmsSender.manager(context, saved.subscriptionId); null }
            .getOrElse { it.message ?: "短信条件未就绪" }
        return null
    }

    fun arm(): String {
        audit("arm", "state" to "requested", "navigation" to navigation, "simulation" to simulation)
        if (busy()) return "报警流程正在进行"
        readiness(saved.realSms)?.let { return it }
        if (saved.name.isBlank() || saved.phone.isBlank()) return "请先保存姓名和紧急联系人"
        if (!BleLink.isConnected || !BleLink.verified || !BleLink.supportsEmergencyAlarm || !BleLink.supportsContinuousImu) return "请连接支持急刹测试的 nav.14 圆屏固件"
        wantedArm = true
        if (navigation && !simulation) detector.arm(now(), true, false)
        audit("arm", "state" to "enabled", "navigation" to navigation, "simulation" to simulation)
        detectorTrace(force = true)
        status = if (navigation && !simulation) "急刹测试已开启，等待新鲜 IMU 和定位" else "已开启单次急刹测试，开始真实导航后生效"
        return status
    }

    fun disarm(): String {
        audit("disarm", "armed" to armed, "state" to stateName)
        wantedArm = false; detector.disarm()
        detectorTrace(force = true)
        if (stateName == "COUNTDOWN") return cancel(eventToken)
        if (eventToken != 0L) return "已停止后续触发；当前事件结果请查看，已进入短信处理阶段的消息不能撤回"
        status = "急刹测试已关闭"; return status
    }

    fun startDrill(): String {
        if (busy()) return "请先处理当前报警"
        readiness(saved.realSms)?.let { return it }
        if (saved.name.isBlank() || saved.phone.isBlank()) return "请先保存姓名和联系人"
        val previousToken = eventToken
        return runCatching {
            NavService.start(context)
            startAlert("manual_drill", null)
            "演练已启动${if (eventConfig.realSms) "，超时会真实发送短信" else "，本次不发短信"}"
        }.getOrElse { abortNoSend("演练启动失败：${it.message}", previousToken) }
    }

    fun navigationStarted(mode: String, simulated: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { navigationStarted(mode, simulated) }; return }
        navigation = mode == "导航" || mode == "网页摩托车"; simulation = simulated
        gpsGateAt = -1
        resetLive()
        if (wantedArm && navigation && !simulation) detector.arm(now(), true, false)
        else detector.disarm()
        audit("navigation_started", "mode" to mode, "navigation" to navigation, "simulation" to simulation, "armed" to armed)
        gateState("gps_gate", "no_fix", force = true)
        gateState("imu_gate", "waiting_for_live_data", force = true)
        detectorTrace(force = true)
    }
    fun navigationStopped() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { navigationStopped() }; return }
        navigation = false; detector.disarm()
        audit("navigation_stopped", "state" to stateName, "armed" to armed)
        gateState("gps_gate", "navigation_inactive", force = true)
        gateState("imu_gate", "navigation_inactive", force = true)
        detectorTrace(force = true)
        // Ending navigation is not the rider's explicit safety confirmation.
    }
    fun serviceStopped() {
        wantedArm = false; detector.disarm()
        detectorTrace(force = true)
        if (stateName == "COUNTDOWN") {
            model = model?.restoreInterrupted(); stateName = "INTERRUPTED"; status = "后台服务关闭，报警流程中断，未发短信"
            runCatching { persist() }; publish()
            audit("alert_interrupted", eventToken, "reason" to "service_stopped", "state" to stateName)
        }
    }
    fun onLinkDisconnected() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onLinkDisconnected() }; return }
        resetLive(); detector.onLinkInterrupted()
        gateState("link_state", "disconnected", force = true)
        gateState("imu_gate", "link_disconnected", force = true)
        detectorTrace(force = true)
    }
    private fun resetLive() { lastBoardBoot = -1; lastBoardNow = -1; minimumOffset = Long.MAX_VALUE; lastSampleSeq = -1; liveSince = now(); imuGateAt = -1 }

    fun onFix(lat: Double, lng: Double, fixWall: Long, receivedWall: Long, accuracy: Double, speedKmh: Double) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onFix(lat, lng, fixWall, receivedWall, accuracy, speedKmh) }; return }
        val received = now(); val age = receivedWall - fixWall
        val fixTrace = mapOf("fix_wall_ms" to fixWall.toString(), "received_wall_ms" to receivedWall.toString(),
            "fix_monotonic_ms" to (received - age).toString(), "received_monotonic_ms" to received.toString(),
            "age_ms" to age.toString(), "accuracy_m" to accuracy.toString(), "speed_kmh" to speedKmh.toString())
        if (age !in 0..1500 || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0 ||
            lat == 0.0 && lng == 0.0 || !accuracy.isFinite() || accuracy <= 0 || accuracy > 100) {
            gpsGateAt = -1; gateState("gps_gate", "invalid_or_stale", fields = fixTrace)
            detector.invalidateGps(); detectorTrace(); return
        }
        val acquired = received - age
        gpsGateAt = acquired
        gateState("gps_gate", when {
            !navigation -> "navigation_inactive"
            simulation -> "simulation"
            !detector.isArmed() -> "not_armed"
            !speedKmh.isFinite() || speedKmh !in 0.0..250.0 || accuracy > 30 -> "invalid_speed_or_accuracy"
            else -> "fresh_real_fix"
        }, fields = fixTrace)
        if (navigation && !simulation) lastFix = Fix(lat, lng, accuracy, acquired, fixWall)
        if (navigation && !simulation && detector.isArmed()) {
            detector.onGps(acquired, received, speedKmh, accuracy, true)?.let { trigger(it) }
        }
        detectorTrace()
    }

    private fun onBatch(batch: ContinuousImuProtocol.Batch, received: Long) {
        if (!navigation || simulation || !detector.isArmed()) {
            gateState("imu_gate", if (!navigation) "navigation_inactive" else if (simulation) "simulation" else "not_armed")
            return
        }
        if (batch.boot != lastBoardBoot) {
            resetLive(); lastBoardBoot = batch.boot; boardClock = batch.boardNow; lastBoardNow = batch.boardNow
            detector.onLinkInterrupted()
        } else {
            val delta = (batch.boardNow - lastBoardNow) and 0xffffffffL
            if (delta > 0x7fffffffL) { gateState("imu_gate", "board_time_regression"); return }
            boardClock += delta; lastBoardNow = batch.boardNow
        }
        minimumOffset = minOf(minimumOffset, received - boardClock)
        val queueAge = boardClock + minimumOffset
        val live = received - queueAge <= 500 && received - liveSince >= 2000 &&
            batch.nextSeq == batch.firstSeq + batch.samples.size && batch.flags and 13 == 1
        imuGateAt = received
        gateState("imu_gate", when {
            batch.flags and 12 != 0 -> "sensor_or_buffer_unavailable"
            batch.flags and 1 == 0 -> "board_not_sampling"
            received - liveSince < 2000 -> "reconnect_warmup"
            batch.nextSeq != batch.firstSeq + batch.samples.size -> "buffered_backlog"
            received - queueAge > 500 -> "transport_delayed"
            batch.samples.isEmpty() -> "no_new_sample"
            batch.samples.all { ((batch.boardNow - it.timestamp) and 0xffffffffL) > 500 } -> "sample_stale"
            else -> "fresh_live_samples"
        })
        batch.samples.forEachIndexed { index, sample ->
            val seq = batch.firstSeq + index
            if (seq <= lastSampleSeq) return@forEachIndexed
            lastSampleSeq = seq
            val age = (batch.boardNow - sample.timestamp) and 0xffffffffL
            val acquired = boardClock + minimumOffset - age
            detector.onImu(acquired, received, sample.ax.toDouble(), sample.ay.toDouble(), sample.az.toDouble(),
                sample.gx.toDouble(), sample.gy.toDouble(), sample.gz.toDouble(), live && age <= 500, batch.flags and 12 == 0)?.let { trigger(it) }
            detectorTrace()
        }
    }
    private fun trigger(trigger: BrakeTestDetector.Trigger) {
        if (!wantedArm || !navigation || simulation || busy()) return
        val previousToken = eventToken
        runCatching { startAlert("imu_gps_brake", trigger) }.onFailure {
            abortNoSend("报警启动失败：${it.message}", previousToken)
        }
    }

    private fun startAlert(source: String, trigger: BrakeTestDetector.Trigger?) {
        readiness(saved.realSms)?.let { error(it) }
        wantedArm = false; detector.disarm()
        detectorTrace(force = true)
        val epoch = (System.currentTimeMillis() / 1000) and 0xffffffffL
        val token = maxOf((sequence + 1) and 0xffffffffL, epoch).coerceAtLeast(1)
        write("config.json", configJson(saved).put("sequence", token)); sequence = token
        val at = now(); val initial = EmergencyAlertState.start(hex(token), at, saved.seconds)
        eventToken = token; model = initial; eventConfig = saved; alertPrompt = saved.prompt
        stateName = "COUNTDOWN"; remainingSeconds = saved.seconds; activeSince = at
        sentResults = IntArray(0); deliveryResults = IntArray(0)
        event = JSONObject().put("token", token).put("source", source).put("startWallMs", System.currentTimeMillis())
            .put("prompt", alertPrompt).put("config", configJson(eventConfig))
        trigger?.let { event!!.put("dynamicMps2", it.dynamicAccelerationMps2).put("speedDropKmh", it.speedDropKmh)
            .put("decelerationMps2", it.decelerationMps2) }
        persist() // A failure here never reaches SMS submission.
        audit("alert_started", token, "source" to source, "state" to stateName, "seconds" to saved.seconds,
            "real_sms" to eventConfig.realSms, "start_monotonic_ms" to initial.startMonotonicMs,
            "deadline_monotonic_ms" to initial.deadlineMonotonicMs)
        trigger?.let {
            audit("detector_triggered", token, "state" to "TRIGGERED", "reason" to "triggered_manual_rearm_required",
                "dynamic_mps2" to it.dynamicAccelerationMps2, "speed_drop_kmh" to it.speedDropKmh,
                "deceleration_mps2" to it.decelerationMps2, "imu_at_ms" to it.imuAtMs, "gps_at_ms" to it.gpsAtMs)
        }
        status = "急刹测试倒计时；长按确认平安可取消"
        wire()
        sendPrompt()
        publish(open = true)
        // Zero seconds intentionally has no usable cancellation window.
        if (saved.seconds == 0) main.post { guardedExpire(token) }
    }

    private fun abortNoSend(message: String, previousToken: Long? = null): String {
        wantedArm = false; detector.disarm()
        // A failure before allocating a new event must not rewrite the outcome
        // of an older SMS that has already been submitted or delivered.
        if (previousToken != null && eventToken == previousToken) return "$message；未启动新报警，原事件结果已保留"
        model = model?.restoreInterrupted()
        stateName = "INTERRUPTED"; status = "$message；未继续发短信"
        runCatching { persist() }; publish(); wire()
        audit("alert_interrupted", eventToken, "reason" to "start_or_persist_failed", "state" to stateName)
        return status
    }

    private fun guardedExpire(token: Long) {
        if (token != eventToken) return
        runCatching { expire() }.onFailure {
            abortNoSend("报警记录保存失败：${it.message}")
        }
    }

    fun cancel(token: Long): String {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancel(token) }; return "正在确认取消" }
        if (token == 0L || token != eventToken) return "该报警已不是当前事件"
        if (stateName == "CANCELLED") { wire(); return "报警已取消" }
        if (stateName != "COUNTDOWN") { wire(); return "短信已进入处理阶段，无法撤回；请查看发送结果" }
        val next = model!!.cancel(hex(token))
        return try {
            model = next; stateName = "CANCELLED"; remainingSeconds = 0; status = "已确认平安，报警已取消，未发短信"
            audit("alert_cancelled", token, "state" to stateName)
            persist(); publish(); wire(); status
        } catch (e: Exception) {
            // Cancellation remains in memory even if durable storage failed.
            status = "本次已取消，但取消记录保存失败：${e.message}"; publish(); wire(); status
        }
    }
    fun onBoardCancel(token: Long) { cancel(token) }

    private fun expire() {
        if (stateName != "COUNTDOWN") return
        val next = model!!.claimSend(hex(eventToken), now(), false)
        if (next.phase != EmergencyAlertState.Phase.SEND_CLAIMED) return
        model = next
        audit("alert_expired", eventToken, "real_sms" to eventConfig.realSms, "state" to "SEND_CLAIMED")
        if (!eventConfig.realSms) {
            stateName = "DRY_RUN"; status = "演练完成：倒计时已结束，本次未发送短信"
            model = next.sent(hex(eventToken)); persist(); publish(); wire()
            audit("alert_dry_run", eventToken, "state" to stateName); return
        }
        try {
            readiness(true)?.let { error(it) }
            val body = smsBody(); val parts = EmergencySmsSender.parts(context, eventConfig.subscriptionId, body)
            sentResults = IntArray(parts.size) { Int.MIN_VALUE }; deliveryResults = IntArray(parts.size) { Int.MIN_VALUE }
            stateName = "SENDING"; status = "正在提交 ${parts.size} 段短信，不能撤回"
            event!!.put("sms", body).put("submitWallMs", System.currentTimeMillis())
            activeSince = now()
            persist() // Irreversible claim is durable BEFORE invoking the modem API.
            audit("sms_submit_claimed", eventToken, "state" to stateName, "total" to parts.size)
            publish(); wire()
            EmergencySmsSender.send(context, eventToken, eventConfig.subscriptionId, eventConfig.phone, parts)
            audit("sms_api_submitted", eventToken, "state" to stateName, "total" to parts.size)
        } catch (e: Exception) {
            stateName = "FAILED"; model = model!!.failed(hex(eventToken)); status = "短信发送失败：${e.message}；未自动重试"
            event?.put("error", e.message); runCatching { persist() }; publish(); wire()
            audit("alert_failed", eventToken, "reason" to "sms_submission_failed", "state" to stateName)
        }
    }

    fun onSmsResult(token: Long, part: Int, total: Int, delivery: Boolean, result: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onSmsResult(token, part, total, delivery, result) }; return }
        if (token == 0L || total !in 1..16 || part !in 0 until total) return
        val current = token == eventToken
        val record = if (current) event else runCatching { read("event-${hex(token)}.json") }.getOrNull()
        if (record == null || record.optLong("token") != token) return
        val previousState = if (current) stateName else record.optString("state")
        if (previousState in listOf("COUNTDOWN", "CANCELLED", "DRY_RUN")) return
        val sent = if (current) sentResults else decodeResults(record.optJSONArray("sentResults"))
        val delivered = if (current) deliveryResults else decodeResults(record.optJSONArray("deliveryResults"))
        if (sent.size != total || delivered.size != total) return
        val repeatedUnverified = delivery && result == Int.MAX_VALUE && record.has("unverifiedDeliveryReportWallMs")
        if (delivery && result == Int.MAX_VALUE) {
            record.put("unverifiedDeliveryReportWallMs", System.currentTimeMillis())
        } else {
            val results = if (delivery) delivered else sent
            if (results[part] != Int.MIN_VALUE) return // Duplicate callbacks cannot wash out a failure.
            results[part] = result
        }
        var nextState = previousState
        var message = if (current) status else record.optString("status")
        when {
            sent.any { it != Int.MIN_VALUE && it != Activity.RESULT_OK } -> {
                nextState = "FAILED"; message = "至少一段短信发送失败；可能部分已提交，未自动重试"
            }
            delivered.any { it != Int.MIN_VALUE && it != Activity.RESULT_OK } -> {
                nextState = "FAILED"; message = "短信送达报告失败，不能确认联系人收到"
            }
            delivered.all { it == Activity.RESULT_OK } -> {
                nextState = "DELIVERED"; message = "所有短信段有成功送达报告；不代表联系人已读"
            }
            sent.all { it == Activity.RESULT_OK } -> {
                nextState = "SUBMITTED"; message = "所有短信段已提交，尚无完整成功送达报告"
            }
        }
        record.put("sentResults", JSONArray(sent.toList())).put("deliveryResults", JSONArray(delivered.toList()))
            .put("state", nextState).put("status", message).put("updatedWallMs", System.currentTimeMillis())
        if (!repeatedUnverified) audit("sms_result", token, "part" to part, "total" to total, "delivery" to delivery,
            "result" to result, "previous_state" to previousState, "state" to nextState)
        if (current) {
            stateName = nextState; status = message
            if (stateName == "SUBMITTED" || stateName == "DELIVERED") model = model?.sent(hex(token))
            else if (stateName == "FAILED") model = model?.failed(hex(token))
            runCatching { persist() }.onFailure { status += "；结果保存失败" }
            publish(); wire()
        } else {
            // Late reports remain attached to their original event after a new drill.
            runCatching { write("event-${hex(token)}.json", record) }
        }
    }
    private fun smsBody(): String {
        val fix = lastFix; val age = fix?.let { now() - it.fixAt }
        val place = if (fix == null) "位置暂不可用，请直接联系本人" else {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(fix.wall))
            val prefix = if (age != null && age in 0..10000) "当前定位" else "最后定位（$stamp，非实时）"
            "$prefix：https://uri.amap.com/marker?position=${fix.lng},${fix.lat} （GCJ-02，精度约${fix.accuracy.toInt()}米）"
        }
        return "【MotoNav急刹测试，未确认事故】您的亲人/好友${eventConfig.name}触发急刹报警且未确认平安，疑似发生事故。地点：$place。请先联系本人核实；确认需要救援时请报警。"
    }

    private val tick = object : Runnable {
        override fun run() {
            val ready = BleLink.isConnected && BleLink.verified
            if (!ready && linkReady) onLinkDisconnected()
            if (ready && !linkReady) {
                gateState("link_state", "connected", force = true)
                if (eventToken != 0L) { wire(); sendPrompt() }
            }
            linkReady = ready
            if (navigation && !simulation && detector.isArmed()) {
                if (gpsGateAt >= 0 && now() - gpsGateAt > BrakeTestDetector.MAX_GPS_AGE_MS) gateState("gps_gate", "stale")
                if (imuGateAt >= 0 && now() - imuGateAt > BrakeTestDetector.MAX_IMU_AGE_MS) gateState("imu_gate", "stale")
            }
            flushTraceStates()
            if (stateName == "COUNTDOWN") {
                remainingSeconds = ceil(model!!.remainingMs(now()) / 1000.0).toInt()
                if (model!!.isExpired(now())) guardedExpire(eventToken)
                else if (now() - lastWireAt >= 1000) { wire(); publish() }
            } else if (stateName == "SENDING" && now() - activeSince > 60000) {
                stateName = "UNKNOWN"; status = "未收到完整发送结果，短信状态未知；未自动重试"
                runCatching { persist() }; publish(); wire()
                audit("sms_result_timeout", eventToken, "state" to stateName)
            }
            main.postDelayed(this, 100)
        }
    }

    /** Privacy-safe journal mirroring is best effort and cannot alter cancellation/SMS behavior. */
    private fun audit(kind: String, vararg fields: Pair<String, Any>) = audit(kind, 0L, *fields)
    private fun audit(kind: String, token: Long, vararg fields: Pair<String, Any>) {
        auditFields(kind, token, fields.associate { it.first to it.second.toString() })
    }
    private fun auditFields(kind: String, token: Long, fields: Map<String, String>) {
        runCatching { ContinuousImuRecorder.recordEmergencyEvent(kind, token, fields) }
    }
    private fun detectorTrace(force: Boolean = false) = gateState("detector_state", "${detector.state().name}:${detector.status()}", force)
    private fun gateState(kind: String, value: String, force: Boolean = false, fields: Map<String, String> = emptyMap()) {
        val at = now()
        val state = traceStates[kind]
        if (state == null) traceStates[kind] = TraceState(value, at, fields)
        else if (state.value != value) { state.value = value; state.observedAt = at; state.fields = fields; state.changes++ }
        val current = traceStates.getValue(kind)
        if (current.changes > 0 && (force || !current.emitted || at - current.emittedAt >= 1000)) emitTraceState(kind, current, at)
    }
    private fun emitTraceState(kind: String, state: TraceState, at: Long) {
        auditFields(kind, 0L, state.fields + mapOf("state" to state.value, "observed_monotonic_ms" to state.observedAt.toString(),
            "coalesced_changes" to state.changes.toString()))
        state.changes = 0; state.emitted = true; state.emittedAt = at
    }
    private fun flushTraceStates() {
        val at = now()
        traceStates.forEach { (kind, state) ->
            if (state.changes > 0 && at - state.emittedAt >= 1000) emitTraceState(kind, state, at)
        }
    }

    private fun wire() {
        if (!BleLink.isConnected || !BleLink.verified || !BleLink.supportsEmergencyAlarm || eventToken == 0L) return
        val state = when (stateName) { "COUNTDOWN" -> 1; "CANCELLED" -> 2; "SENDING" -> 3; "SUBMITTED", "DELIVERED" -> 4; "FAILED" -> 5; "DRY_RUN" -> 7; "INTERRUPTED" -> 8; else -> 6 }
        BleLink.sendEvent("#EA,${hex(eventToken)},$state,$remainingSeconds")
        lastWireAt = now()
    }
    private fun sendPrompt() {
        if (!BleLink.supportsEmergencyAlarm || stateName != "COUNTDOWN") return
        val images = TextImage.renderEmergency(alertPrompt)
        images.forEachIndexed { index, image -> BleLink.sendImage(14 + index, image.w, image.h, image.data) }
        // Clear unused retained slots so reconnect cannot restore a previous,
        // longer prompt underneath the current one.
        for (index in images.size until 2) BleLink.sendImage(14 + index, 0, 0, byteArrayOf())
    }
    private fun publish(open: Boolean = false) {
        if (eventToken == 0L) return
        val activityIntent = Intent(context, EmergencyAlertActivity::class.java).apply {
            putExtra("event_token", eventToken); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(context, eventToken.toInt(), activityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = Intent(context, EmergencyCancelReceiver::class.java).apply { action = "com.example.motonav.EMERGENCY_CANCEL"; putExtra("token", eventToken) }
        val cancelPending = PendingIntent.getBroadcast(context, eventToken.toInt(), cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (stateName == "COUNTDOWN") "急刹测试：$remainingSeconds 秒后${if (eventConfig.realSms) "发送短信" else "完成演练"}" else "MotoNav 急刹测试结果")
            .setContentText(status).setStyle(NotificationCompat.BigTextStyle().bigText(if (stateName == "COUNTDOWN") alertPrompt else status))
            .setContentIntent(pending).setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(stateName == "COUNTDOWN")
        if (stateName == "COUNTDOWN") builder.addAction(0, "我平安，取消报警", cancelPending)
        if (open && (Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())) builder.setFullScreenIntent(pending, true)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION, builder.build()) }
        if (open) foreground.get()?.takeIf { it !is EmergencyAlertActivity && !it.isFinishing }?.let { a -> runCatching { a.startActivity(activityIntent) } }
    }
    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "急刹测试倒计时", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "用户开启的急刹报警测试、取消与短信状态"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    private fun persist() {
        val m = model ?: return; val data = event ?: return
        data.put("phase", m.phase.name).put("state", stateName).put("status", status)
            .put("startMonotonicMs", m.startMonotonicMs).put("deadlineMonotonicMs", m.deadlineMonotonicMs).put("seconds", m.countdownSeconds)
            .put("sentResults", JSONArray(sentResults.toList())).put("deliveryResults", JSONArray(deliveryResults.toList()))
            .put("updatedWallMs", System.currentTimeMillis())
        write("event-${hex(eventToken)}.json", data)
        write("latest.json", data)
    }
    fun latestReport(): String = event?.toString(2) ?: "尚无报警测试记录"
    private fun configJson(c: EmergencyConfig) = JSONObject().put("name", c.name).put("phone", c.phone).put("prompt", c.prompt)
        .put("seconds", c.seconds).put("subscriptionId", c.subscriptionId).put("realSms", c.realSms)
    private fun decodeConfig(o: JSONObject) = EmergencyConfig(o.optString("name"), o.optString("phone"), o.optString("prompt", DEFAULT_PROMPT),
        o.optInt("seconds", 30).coerceIn(0, 60), o.optInt("subscriptionId", -1), o.optBoolean("realSms", false))
    private fun decodeResults(a: JSONArray?) = IntArray(a?.length()?.coerceAtMost(16) ?: 0) { a!!.getInt(it) }
    private fun write(name: String, o: JSONObject) {
        val atomic = AtomicFile(File(directory, name)); val stream = atomic.startWrite()
        try { stream.write(o.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun read(name: String): JSONObject? {
        val file = AtomicFile(File(directory, name))
        return try { file.openRead().bufferedReader().use { JSONObject(it.readText()) } }
        catch (_: java.io.FileNotFoundException) { null }
    }
    private fun busy() = stateName == "COUNTDOWN" || stateName == "SENDING"
    private fun now() = SystemClock.elapsedRealtime()
    private fun hex(token: Long) = String.format(Locale.US, "%08X", token)
    private fun stateText(state: String) = when (state) {
        "CANCELLED" -> "上次报警已取消"; "SUBMITTED" -> "上次短信已提交，是否送达请查看记录"
        "DELIVERED" -> "上次短信有运营商送达报告"; "FAILED" -> "上次短信失败或部分失败，未自动重试"
        "DRY_RUN" -> "上次演练完成，未发短信"; else -> "上次短信状态未知，未自动补发"
    }
}
