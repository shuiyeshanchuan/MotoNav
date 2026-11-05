package com.example.motonav

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Presentation only. Controller owns the deadline, event identity and SMS dispatch. */
class EmergencyAlertActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var prompt: TextView
    private lateinit var status: TextView
    private lateinit var cancel: TextView
    private lateinit var close: TextView
    private var token = 0L
    private var holding = false
    private var holdStarted = 0L
    private val holdComplete = Runnable {
        if (holding && SystemClock.elapsedRealtime() - holdStarted >= 500) {
            holding = false; cancel.isPressed = false; cancelEvent()
        }
    }
    private val refresh = object : Runnable {
        override fun run() { paint(); handler.postDelayed(this, 200) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = savedInstanceState?.getLong("event_token") ?: intent.getLongExtra("event_token", EmergencyAlertController.eventToken)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.statusBarColor = NavStyle.BG; window.navigationBarColor = NavStyle.BG
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(NavStyle.BG); isFillViewport = true; addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            view.setPadding(0, top, 0, bottom); insets
        }
        content.addView(label("急刹专项测试提醒", 23f, NavStyle.TEXT).apply { typeface = NavStyle.BOLD })
        content.addView(label("测试功能 · 未确认事故", 15f, NavStyle.AMBER))
        countdown = label("--", 82f, NavStyle.RED).apply {
            typeface = NavStyle.NUM; minHeight = dp(118)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        prompt = label("", 23f, NavStyle.TEXT).apply { typeface = NavStyle.BOLD }
        content.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        status = label("", 16f, NavStyle.SUB)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        cancel = label("按住 0.5 秒取消本次提醒", 19f, NavStyle.TEXT).apply {
            minHeight = dp(84); isClickable = true; isFocusable = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = NavStyle.round(this@EmergencyAlertActivity, NavStyle.CARD2, 18f, NavStyle.STROKE)
            contentDescription = "取消本次提醒。触摸时按住半秒；使用屏幕阅读器时激活按钮即可取消。"
            // Accessibility ACTION_CLICK/keyboard activation has an equivalent
            // cancel action; physical short touch never calls performClick().
            setOnClickListener { cancelEvent() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!isEnabled) return@setOnTouchListener false
                        holding = true; holdStarted = SystemClock.elapsedRealtime(); isPressed = true
                        text = "继续按住，即将取消"
                        handler.postDelayed(holdComplete, 500); true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.x < 0 || event.y < 0 || event.x > view.width || event.y > view.height) stopHolding()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopHolding(); true }
                    else -> true
                }
            }
        }
        content.addView(cancel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        close = label("返回", 16f, NavStyle.PINK).apply {
            minHeight = dp(52); isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(label("返回或切换应用不会自动取消倒计时。短信一旦提交就无法撤回；只有明确取消成功才表示本次未发送。", 13f, NavStyle.SUB))
        setContentView(scroll); paint()
    }

    private fun cancelEvent() {
        if (token == 0L || token != EmergencyAlertController.eventToken || EmergencyAlertController.stateName != "COUNTDOWN") {
            Toast.makeText(this, "当前已不在可取消的倒计时阶段", Toast.LENGTH_LONG).show(); paint(); return
        }
        val result = EmergencyAlertController.cancel(token)
        Toast.makeText(this, result, Toast.LENGTH_LONG).show(); paint()
    }

    private fun stopHolding() {
        holding = false; handler.removeCallbacks(holdComplete)
        if (::cancel.isInitialized) { cancel.isPressed = false; cancel.text = "按住 0.5 秒取消本次提醒" }
    }

    private fun paint() {
        if (!::countdown.isInitialized) return
        val current = token != 0L && token == EmergencyAlertController.eventToken
        val state = if (current) EmergencyAlertController.stateName else "STALE"
        val counting = state == "COUNTDOWN"
        countdown.text = if (counting) EmergencyAlertController.remainingSeconds.coerceAtLeast(0).toString() else when (state) {
            "CANCELLED" -> "已取消"
            "DRY_RUN" -> "演练结束"
            "DELIVERED" -> "有送达回执"
            "SUBMITTED" -> "已提交"
            "SENDING" -> "发送中"
            else -> "提醒结束"
        }
        countdown.textSize = if (counting) 82f else 36f
        countdown.contentDescription = if (counting) "剩余 ${EmergencyAlertController.remainingSeconds.coerceAtLeast(0)} 秒" else countdown.text
        prompt.text = if (current) EmergencyAlertController.alertPrompt else "此提醒事件已结束"
        status.text = if (current) EmergencyAlertController.status else "当前页面对应旧事件，不会操作其他提醒。"
        cancel.isEnabled = counting; cancel.alpha = if (counting) 1f else 0.5f
        if (!counting) stopHolding()
        close.text = if (counting || state == "SENDING") "返回（不会取消提醒）" else "关闭提醒页面"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); stopHolding()
        token = intent.getLongExtra("event_token", EmergencyAlertController.eventToken); paint()
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { stopHolding(); handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("event_token", token); super.onSaveInstanceState(outState) }
    private fun dp(value: Int) = NavStyle.dpi(this, value)
    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(0, dp(7), 0, dp(7))
    }
}
