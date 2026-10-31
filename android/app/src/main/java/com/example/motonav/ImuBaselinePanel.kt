package com.example.motonav

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicit foreground capture. Stored reports never change sensor offsets or arm safety monitoring. */
class ImuBaselinePanel(context: Context) : LinearLayout(context) {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("imu_static_baselines", Context.MODE_PRIVATE)
    private val poses = ImuStaticBaseline.Pose.values()
    private var capture: ImuStaticBaseline? = null
    private var streamOwned = false
    private var nextStatusQueryAt = 0L
    private var captureFirmware = ""
    private var captureDevice = ""
    private var captureStartedAt = -1L
    private var firstHealth: AccidentImuSampleParser.Health? = null
    private var lastHealth: AccidentImuSampleParser.Health? = null
    private var lastStatus: AccidentImuSampleParser.Status? = null
    private var expanded = false
    private val posePicker = Spinner(context)
    private val status = text("选择姿态后开始；每个姿态单独记录 60 秒。")
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val report = text("").apply { setTextIsSelectable(true); visibility = GONE }
    private val start = button("开始 60 秒静置采集") { begin() }
    private val cancel = button("结束并保存当前记录") { cancelCapture("用户提前结束") }
    private val showReports = button("查看已保存结果") {
        expanded = !expanded
        paintReports()
    }

    init {
        orientation = VERTICAL
        addView(text("静置基线 · 仅诊断", 16f, true), row(16))
        addView(text("停车并停止导航后，将圆屏用支架固定，稳定至少 5 秒再开始。按实物朝向选择姿态，采集时不要手持、触碰或转动设备；手机保持本页前台。"), row(8))
        addView(text("本次屏幕朝向（与圆屏 UI 旋转无关）", 14f, true), row(12))
        posePicker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            poses.map { it.label })
        posePicker.contentDescription = "选择本次静置姿态"
        posePicker.minimumHeight = dp(48)
        addView(posePicker, row(4))
        addView(start, row(8))
        cancel.visibility = GONE
        addView(cancel, row(8))
        progress.max = ImuStaticBaseline.REQUIRED_DURATION_MS.toInt()
        progress.progressTintList = ColorStateList.valueOf(NavStyle.PINK)
        addView(progress, row(12))
        addView(status, row(8))
        addView(text("保存三轴均值、标准差、范围和温度，不自动校准。采集完成只代表满足本次记录条件，不能用于确认事故检测可靠。"), row(8))
        addView(showReports, row(12))
        addView(report, row(8))
        addView(button("复制已保存结果") { copyReports() }, row(8))
        paintReports()
    }

    private fun begin() {
        if (capture != null) return
        if (!BleLink.isConnected || !BleLink.verified) {
            message("请先连接圆屏并完成配对")
            return
        }
        val model = ImuStaticBaseline()
        captureStartedAt = SystemClock.elapsedRealtime()
        model.start(poses[posePicker.selectedItemPosition], captureStartedAt)
        capture = model
        captureFirmware = BleLink.screenFirmware
        captureDevice = BleLink.screenInfo
        firstHealth = null
        lastHealth = null
        lastStatus = null
        streamOwned = !BleLink.screenImuStreamEnabled
        nextStatusQueryAt = 0L
        keepScreenOn = true
        start.isEnabled = false
        start.alpha = 0.5f
        posePicker.isEnabled = false
        cancel.visibility = VISIBLE
        // BleLink stamps frames at the GATT callback, before its main-thread
        // dispatch. Old queued callbacks cannot feed a later capture.
        BleLink.setImuListener(object : BleLink.ImuListener {
            override fun onImuStatus(value: AccidentImuSampleParser.Status, received: Long) {
                ui.post {
                    if (capture === model && received >= captureStartedAt) {
                        lastStatus = value
                        model.updateStatus(value, received)
                        checkFinished()
                    }
                }
            }
            override fun onImuSample(value: AccidentImuSampleParser.Sample) {
                ui.post {
                    if (capture === model && value.receivedAtMonotonicMs >= captureStartedAt) {
                        model.onSample(value); checkFinished()
                    }
                }
            }
            override fun onImuHealth(value: AccidentImuSampleParser.Health) {
                ui.post {
                    if (capture === model && value.receivedAtMonotonicMs >= captureStartedAt) {
                        if (firstHealth == null && value.usableForRecording()) firstHealth = value
                        lastHealth = value
                        model.updateHealth(value)
                        checkFinished()
                    }
                }
            }
        })
        if (streamOwned) BleLink.setImuDiagnosticsStream(true)
        ui.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            val model = capture ?: return
            val firmwareChanged = captureFirmware.isNotBlank() && BleLink.screenFirmware.isNotBlank() &&
                BleLink.screenFirmware != captureFirmware
            val deviceChanged = captureDevice.isNotBlank() && BleLink.screenInfo.isNotBlank() &&
                BleLink.screenInfo != captureDevice
            if (!BleLink.isConnected || !BleLink.verified || firmwareChanged || deviceChanged) {
                cancelCapture("连接或设备状态已改变，请重连后重新采集")
                return
            }
            val now = SystemClock.elapsedRealtime()
            model.tick(now)
            if (checkFinished()) return
            if (now >= nextStatusQueryAt) {
                // #IMU? returns both STATUS and HEALTH on the existing nav.9
                // diagnostic firmware. Never reuse cached pre-session health.
                BleLink.requestImuDiagnosticsStatus()
                nextStatusQueryAt = now + 1000L
            }
            val result = model.result()
            val duration = durationMs(result)
            progress.progress = duration.coerceAtMost(ImuStaticBaseline.REQUIRED_DURATION_MS).toInt()
            status.text = "${result.pose.label} · ${duration / 1000}/60 秒 · ${result.acceptedSamples} 个样本\n${model.reason()}"
            ui.postDelayed(this, 250L)
        }
    }

    private fun checkFinished(): Boolean {
        val model = capture ?: return true
        if (model.state() == ImuStaticBaseline.State.COLLECTING) return false
        finish(model.result(), model.reason())
        return true
    }

    fun cancelCapture(reason: String) {
        val model = capture ?: return
        model.cancel()
        finish(model.result(), reason)
    }

    private fun finish(result: ImuStaticBaseline.Result, reason: String) {
        capture = null
        ui.removeCallbacksAndMessages(null)
        BleLink.setImuListener(null)
        if (streamOwned) BleLink.setImuDiagnosticsStream(false)
        streamOwned = false
        keepScreenOn = false
        start.isEnabled = true
        start.alpha = 1f
        posePicker.isEnabled = true
        cancel.visibility = GONE
        val complete = result.completeForReview()
        val outcome = if (complete) "采集完成 · 待复核" else "本次无效"
        status.text = "${result.pose.label} · $outcome\n$reason"
        progress.progress = durationMs(result).coerceAtMost(ImuStaticBaseline.REQUIRED_DURATION_MS).toInt()
        // Keep the most recent attempt per pose, including its explicit failure
        // state, rather than accidentally showing a previous success as current.
        prefs.edit().putString(result.pose.name, formatReport(result, outcome, reason)).apply()
        paintReports()
    }

    private fun durationMs(result: ImuStaticBaseline.Result): Long =
        minOf(result.lastBoardAt - result.firstBoardAt,
            result.lastReceivedAt - result.firstReceivedAt).coerceAtLeast(0L)

    private fun formatReport(r: ImuStaticBaseline.Result, outcome: String, reason: String): String {
        fun stats(name: String, unit: String, s: ImuStaticBaseline.AxisStats): String =
            if (s.count == 0) "$name：无样本" else String.format(Locale.US,
                "%s：均值 %.3f，标准差 %.3f，范围 %.3f ~ %.3f %s",
                name, s.mean, s.stdDev, s.min, s.max, unit)
        return buildString {
            appendLine("${r.pose.label} · $outcome")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
            appendLine("App $APP_VERSION · 圆屏 $captureFirmware")
            appendLine("设备 $captureDevice")
            appendLine("原因：$reason")
            appendLine("有效样本 ${r.acceptedSamples} / 拒绝 ${r.rejectedSamples}")
            appendLine("板端跨度 ${(r.lastBoardAt - r.firstBoardAt).coerceAtLeast(0L)} ms；接收跨度 ${(r.lastReceivedAt - r.firstReceivedAt).coerceAtLeast(0L)} ms")
            appendLine("板端间隔 ${r.boardIntervalMin} ~ ${r.boardIntervalMax} ms；接收间隔 ${r.receivedIntervalMin} ~ ${r.receivedIntervalMax} ms")
            lastStatus?.let {
                appendLine("末次状态：自检 ${it.selfTestKnown && it.selfTestOk}，配置 ${it.configured}，采样 ${it.streamEnabled}，累计样本 ${it.samples}，读错 ${it.readErrors}，实际采样时间 ${it.lastBoardMillis} ms")
            }
            fun healthLine(title: String, h: AccidentImuSampleParser.Health) =
                "$title：样本 ${h.samples}，读错 ${h.readErrors}，缺口 ${h.sampleGaps}，重力越界 ${h.gravityOutOfRange}，加速度/陀螺饱和 ${h.accelSaturated}/${h.gyroSaturated}，轮询间隔 ${h.intervalMinMs} ~ ${h.intervalMaxMs} ms"
            firstHealth?.let { appendLine(healthLine("初次健康累计值", it)) }
            lastHealth?.let { appendLine(healthLine("末次健康累计值", it)) }
            appendLine(stats("加速度 X", "m/s²", r.accelerationX))
            appendLine(stats("加速度 Y", "m/s²", r.accelerationY))
            appendLine(stats("加速度 Z", "m/s²", r.accelerationZ))
            appendLine(stats("加速度模长", "m/s²", r.accelerationMagnitude))
            appendLine(stats("角速度 X（未补偿）", "°/s", r.gyroX))
            appendLine(stats("角速度 Y（未补偿）", "°/s", r.gyroY))
            appendLine(stats("角速度 Z（未补偿）", "°/s", r.gyroZ))
            appendLine(stats("角速度模长", "°/s", r.gyroMagnitude))
            appendLine(stats("温度", "°C", r.temperature))
            append("仅 BLE 低频静置抽样，不代表全速冲击采集。未写入校准，未开启事故判定或联系人通知。")
        }
    }

    private fun savedReports(): String = poses.mapNotNull { prefs.getString(it.name, null) }
        .joinToString("\n\n")

    private fun paintReports() {
        val saved = poses.count { prefs.contains(it.name) }
        showReports.text = if (expanded) "收起已保存结果（$saved/4）" else "查看已保存结果（$saved/4）"
        report.text = savedReports().ifEmpty { "暂无结果；完成或取消采集后会保存本次记录。" }
        report.visibility = if (expanded) VISIBLE else GONE
    }

    private fun copyReports() {
        val content = savedReports()
        if (content.isEmpty()) { message("请先采集一个姿态"); return }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MotoNav IMU 静置基线", content))
        message("已复制本机保存的静置结果")
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) cancelCapture("诊断页面已关闭，采集取消")
    }

    override fun onDetachedFromWindow() {
        cancelCapture("诊断页面已退出，采集取消")
        ui.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun text(value: String, size: Float = 14f, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(NavStyle.TEXT)
        if (bold) typeface = NavStyle.BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        setTextColor(NavStyle.TEXT)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(NavStyle.STROKE),
            NavStyle.round(context, NavStyle.CARD2, 12f, NavStyle.STROKE), null)
        setOnClickListener { action() }
    }

    private fun row(top: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }
    private fun dp(value: Int) = NavStyle.dpi(context, value)
    private fun message(value: String) = Toast.makeText(context, value, Toast.LENGTH_LONG).show()
}
