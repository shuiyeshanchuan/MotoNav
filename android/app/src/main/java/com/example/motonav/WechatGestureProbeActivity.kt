package com.example.motonav

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** An explicit local test. Preparing an action never opens WeChat or changes BLE state. */
class WechatGestureProbeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var lastReport = ""
    private var exporting: Bitmap? = null
    private val exportScreenshot = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = exporting
        exporting = null
        if (uri != null && bitmap != null) {
            val result = runCatching {
                val stream = contentResolver.openOutputStream(uri) ?: error("无法打开保存位置")
                stream.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
            }
            toast(if (result.isSuccess) "截图已保存；发送前请遮住联系人信息" else "保存失败：${result.exceptionOrNull()?.message}")
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            status.text = WechatGestureProbe.statusText()
            val report = WechatGestureProbe.reportText()
            if (report != lastReport) { lastReport = report; log.text = report }
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WechatGestureProbe.init(this)
        window.statusBarColor = NavStyle.BG
        window.navigationBarColor = NavStyle.BG
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(NavStyle.BG); addView(content) }
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars()).top else ins.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars()).bottom else ins.systemWindowInsetBottom
            v.setPadding(0, top, 0, bottom); ins
        }
        content.addView(button("返回") { finish() })
        content.addView(label("微信接听专项验证", 24f, NavStyle.TEXT))
        content.addView(label("$APP_VERSION · 仅解锁、亮屏和微信完整来电页", 13f, NavStyle.SUB))
        content.addView(label("仅在停车时与熟人测试。先采集页面、确认接听位置，再准备一次点击。准备后有 60 秒，请通过最近任务切回微信，并点开顶部来电横幅，露出完整接听页面。顶部横幅本身不能作为测试页面。", 14f, NavStyle.TEXT))
        content.addView(label("截图和节点记录会包含联系人信息，仅保存在手机本地。只有手动复制或保存后才导出；用完可清除。", 13f, NavStyle.SUB))
        content.addView(button("打开系统无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(label("首次安装本测试版后，请关闭再重新开启 MotoNav 无障碍服务，以加载手势与截图能力。无需升级圆屏固件。", 13f, NavStyle.SUB))
        status = label("", 14f, NavStyle.PINK).apply { setTextIsSelectable(true) }
        content.addView(status)
        content.addView(button("1. 准备采集来电页（60 秒）") {
            confirm("采集微信来电页", "准备后切回微信完整来电页。此次只读取窗口、节点和一张截图，不点击接听。") {
                toast(WechatGestureProbe.armCapture())
            }
        })
        content.addView(button("2. 查看截图并确认接听位置") { selectTarget() })
        content.addView(button("3. 接听测试一次（优先控件）") {
            confirm("准备一次接听测试", "准备后切回本通微信来电的完整页面。优先点击明确的接听控件；找不到控件时，只有已确认本通来电截图位置才尝试模拟点击。全程最多一次点击，不自动重试。") {
                toast(WechatGestureProbe.armOneShot())
            }
        })
        content.addView(button("仅测试模拟点击一次（需已确认位置）") {
            confirm("准备一次模拟点击", "只在已确认截图位置的同一通来电中测试。准备后切回微信完整来电页，跳过控件点击，只发送一个模拟点击。手势完成不等于通话接通。") {
                toast(WechatGestureProbe.armOneShot(forceGesture = true))
            }
        })
        content.addView(button("取消等待 / 测试") { toast(WechatGestureProbe.cancel()) })
        content.addView(button("记录实际测试结果") {
            val choices = arrayOf("已真正接通并能通话", "仍停留在来电页，未接通", "出现误触或跳转到其他页面", "已手动接听（对照）", "本次无法确认")
            AlertDialog.Builder(this).setTitle("以双方实际通话为准")
                .setItems(choices) { _, which -> toast(WechatGestureProbe.recordOutcome(choices[which])) }
                .setNegativeButton("取消", null).show()
        })
        content.addView(button("复制诊断记录") {
            confirm("复制本地诊断", "节点文本可能含有联系人姓名。复制后请检查并遮去个人信息再发送。") {
                toast(WechatGestureProbe.copyReport(this))
            }
        })
        content.addView(button("保存采集截图") {
            val screenshot = WechatGestureProbe.screenshot
            if (screenshot == null) toast("还没有采集截图") else {
                exporting = screenshot
                exportScreenshot.launch("MotoNav-wechat-probe-${WechatGestureProbe.captureId}.png")
            }
        })
        content.addView(button("清除本次截图与记录") {
            confirm("清除本地诊断", "取消等待，并清除本地节点记录、截图和接听位置。已手动导出的副本请自行删除。") {
                toast(WechatGestureProbe.clear())
            }
        })
        log = label("", 12f, NavStyle.SUB).apply { setTextIsSelectable(true) }
        content.addView(log)
        setContentView(scroll)
    }

    private fun selectTarget() {
        val screenshot = WechatGestureProbe.screenshot ?: run { toast("先采集本通微信的完整来电页"); return }
        val captureId = WechatGestureProbe.captureId
        val captureWidth = WechatGestureProbe.captureWidth
        val captureHeight = WechatGestureProbe.captureHeight
        val rotation = WechatGestureProbe.captureRotation
        val callType = WechatGestureProbe.captureCallType
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        val position = label("在下图中轻点绿色接听按钮的中心，再确认。", 13f, NavStyle.TEXT)
        box.addView(position)
        val picker = ScreenshotTargetView(this, screenshot)
        box.addView(picker, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.58f).toInt()))
        val dialog = AlertDialog.Builder(this).setTitle("确认本通来电的接听位置")
            .setView(box).setPositiveButton("确认此位置", null).setNegativeButton("取消", null).create()
        picker.onSelected = { x, y ->
            position.text = "选择位置：${(x * captureWidth).toInt()}, ${(y * captureHeight).toInt()} · ${captureWidth}×$captureHeight · $callType"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    val point = picker.selected ?: return@setOnClickListener
                    val result = WechatGestureProbe.configureTarget(point.first, point.second,
                        captureWidth, captureHeight, rotation, callType, captureId)
                    toast(result)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun dp(n: Int) = NavStyle.dpi(this, n)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("准备") { _, _ -> action() }.setNegativeButton("取消", null).show()
    }
    private fun label(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(s: String, action: () -> Unit) = label(s, 15f, NavStyle.PINK).apply {
        minHeight = dp(50); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(NavStyle.CARD2),
            NavStyle.round(this@WechatGestureProbeActivity, NavStyle.CARD, 12f, NavStyle.STROKE), null)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
}

/** Touch coordinates are mapped through the displayed bitmap rectangle, excluding letterboxing. */
private class ScreenshotTargetView(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageRect = RectF()
    var selected: Pair<Float, Float>? = null
        private set
    var onSelected: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap, null, imageRect, paint)
        selected?.let { (x, y) ->
            val cx = imageRect.left + x * imageRect.width(); val cy = imageRect.top + y * imageRect.height()
            paint.color = Color.YELLOW; paint.strokeWidth = 3f; paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, 13f, paint)
            canvas.drawLine(cx - 20, cy, cx + 20, cy, paint)
            canvas.drawLine(cx, cy - 20, cx, cy + 20, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                downX = event.x; downY = event.y; parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (imageRect.contains(event.x, event.y) &&
                    kotlin.math.abs(event.x - downX) < 24 && kotlin.math.abs(event.y - downY) < 24) {
                    val x = (event.x - imageRect.left) / imageRect.width()
                    val y = (event.y - imageRect.top) / imageRect.height()
                    selected = x to y; invalidate(); onSelected?.invoke(x, y); performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
