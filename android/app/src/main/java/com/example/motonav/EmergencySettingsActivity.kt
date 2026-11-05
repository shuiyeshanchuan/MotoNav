package com.example.motonav

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Explicit, single-use test setup. Opening or editing this page never sends SMS. */
class EmergencySettingsActivity : AppCompatActivity() {
    private lateinit var riderName: EditText
    private lateinit var phone: EditText
    private lateinit var prompt: EditText
    private lateinit var seconds: EditText
    private lateinit var sim: Spinner
    private lateinit var realSms: SwitchCompat
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var readiness: TextView
    private val simIds = ArrayList<Int>()
    private var changingSwitch = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { paint(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = EmergencyAlertController.config()
        window.statusBarColor = NavStyle.BG
        window.navigationBarColor = NavStyle.BG
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(NavStyle.BG)
            isFillViewport = true
            addView(content)
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            view.setPadding(0, top, 0, bottom)
            insets
        }
        content.addView(button("返回") { finish() })
        content.addView(label("急刹提醒专项测试", 25f, NavStyle.TEXT).apply { typeface = NavStyle.BOLD })
        content.addView(label("先验证提醒、取消与短信链路；急刹不能证明发生事故。每次开启仅允许触发一次，需要再次手动开启。", 14f))

        val settings = card()
        riderName = field(settings, "骑行人姓名（短信中显示）", savedInstanceState?.getString("name") ?: config.name, 30).apply { setSingleLine(true) }
        phone = field(settings, "紧急联系人手机号", savedInstanceState?.getString("phone") ?: config.phone, 32).apply { inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true) }
        prompt = field(settings, "倒计时提示文字（最多 100 字）", savedInstanceState?.getString("prompt") ?: config.prompt, 100).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START
        }
        seconds = field(settings, "倒计时秒数（0–60）", savedInstanceState?.getString("seconds") ?: config.seconds.toString(), 2).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
        }
        settings.addView(label("0 秒表示触发后立即处理，没有长按取消的时间。建议先用较长倒计时进行演练。", 13f, NavStyle.AMBER))
        settings.addView(label("发送短信的 SIM 卡", 15f, NavStyle.TEXT))
        sim = Spinner(this).apply { minimumHeight = dp(52); contentDescription = "选择发送短信的 SIM 卡" }
        settings.addView(sim, LinearLayout.LayoutParams(-1, -2))
        settings.addView(label("只显示 SIM 名称和卡槽，不读取本机号码。系统未设置默认短信卡时，请明确选择一张卡。", 13f))
        realSms = SwitchCompat(this).apply {
            text = "本次测试允许发送真实短信"
            textSize = 16f; setTextColor(NavStyle.TEXT); minHeight = dp(56)
            isChecked = savedInstanceState?.getBoolean("real_sms") ?: config.realSms
            setOnCheckedChangeListener { _, checked ->
                if (checked && !changingSwitch) {
                    AlertDialog.Builder(this@EmergencySettingsActivity)
                        .setTitle("允许本次测试发短信？")
                        .setMessage("倒计时结束且未取消时，会向你填写的联系人发送真实测试短信，运营商可能计费。短信会明确说明测试、未确认事故。请先告知联系人。此开关本身不会触发发送。")
                        .setPositiveButton("允许测试") { _, _ -> paint() }
                        .setNegativeButton("保持不发送") { _, _ -> setRealSms(false) }
                        .setOnCancelListener { setRealSms(false) }.show()
                }
                updatePreview()
            }
        }
        settings.addView(realSms)
        settings.addView(label("关闭时仅演练提醒与取消，不发送短信。真实短信需要单独授权，并在保存后开启一次测试。", 13f))
        content.addView(settings, margins())

        val actions = card()
        readiness = label("", 14f, NavStyle.AMBER); actions.addView(readiness)
        actions.addView(button("授予本次测试所需权限") { requestTestPermissions() })
        actions.addView(button("保存设置") { if (saveFields()) toast("已保存；尚未开启测试") })
        actions.addView(button("保存并开启一次急刹测试") {
            if (saveFields()) {
                val issue = EmergencyAlertController.readiness(EmergencyAlertController.config().realSms)
                if (issue != null) toast(issue) else toast(EmergencyAlertController.arm())
                paint()
            }
        })
        actions.addView(button("解除本次测试") { toast(EmergencyAlertController.disarm()); paint() })
        actions.addView(button("保存并立即演练倒计时") {
            if (saveFields()) {
                toast(EmergencyAlertController.startDrill())
                if (EmergencyAlertController.eventToken != 0L) {
                    startActivity(Intent(this, EmergencyAlertActivity::class.java)
                        .putExtra("event_token", EmergencyAlertController.eventToken))
                }
                paint()
            }
        })
        actions.addView(label("自动触发仅用于真实导航且传感器、定位数据有效的本次测试；立即演练用于停车时检查提醒链路。请勿为触发测试而在公共道路上急刹。", 13f))
        content.addView(actions, margins())

        val feedback = card()
        feedback.addView(label("提醒与发送状态", 18f, NavStyle.TEXT).apply { typeface = NavStyle.BOLD })
        status = label("", 15f, NavStyle.TEXT).apply { setTextIsSelectable(true) }; feedback.addView(status)
        feedback.addView(button("打开当前提醒") {
            if (EmergencyAlertController.eventToken == 0L) toast("当前没有提醒事件")
            else startActivity(Intent(this, EmergencyAlertActivity::class.java).putExtra("event_token", EmergencyAlertController.eventToken))
        })
        feedback.addView(button("查看最近测试记录") {
            AlertDialog.Builder(this).setTitle("最近测试记录")
                .setMessage(EmergencyAlertController.latestReport().ifBlank { "暂无测试记录" })
                .setPositiveButton("关闭", null).show()
        })
        feedback.addView(button("打开通知设置") {
            openSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        if (Build.VERSION.SDK_INT >= 34) feedback.addView(button("锁屏全屏提醒设置") {
            openSetting(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        })
        feedback.addView(label("锁屏是否显示完整提醒由系统权限、通知渠道和手机省电设置决定；全屏不可用时可能只显示通知。短信已提交不能通过取消按钮撤回，送达回执也不代表联系人已阅读。", 13f))
        content.addView(feedback, margins())

        val sample = card()
        sample.addView(label("短信示意", 18f, NavStyle.TEXT).apply { typeface = NavStyle.BOLD })
        preview = label("", 14f, NavStyle.TEXT).apply { setTextIsSelectable(true) }; sample.addView(preview)
        sample.addView(label("实际位置、定位时间与发送结果以触发时记录为准。提示文字用于倒计时界面；短信保留固定测试标记。", 13f))
        content.addView(sample, margins())
        setContentView(scroll)
        loadSims(savedInstanceState?.getInt("sim", config.subscriptionId) ?: config.subscriptionId)
        riderName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
            override fun afterTextChanged(s: Editable?) {}
        })
        updatePreview(); paint()
    }

    private fun selectedSim(): Int = simIds.getOrNull(sim.selectedItemPosition) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private fun loadSims(preferred: Int) {
        val names = ArrayList<String>(); simIds.clear()
        simIds.add(SubscriptionManager.INVALID_SUBSCRIPTION_ID); names.add("请选择短信 SIM 卡")
        val default = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(default)) { simIds.add(default); names.add("系统默认短信卡（订阅 $default）") }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val subscriptions = getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                for (info in subscriptions) {
                    if (!SubscriptionManager.isValidSubscriptionId(info.subscriptionId)) continue
                    simIds.add(info.subscriptionId)
                    names.add("卡槽 ${info.simSlotIndex + 1} · ${info.displayName}（订阅 ${info.subscriptionId}）")
                }
            }.onFailure { toast("读取 SIM 信息失败，请检查电话权限") }
        }
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val index = simIds.indexOf(preferred).takeIf { it >= 0 }
            ?: simIds.indexOf(default).takeIf { it >= 0 } ?: 0
        sim.setSelection(index)
    }

    private fun saveFields(): Boolean {
        val countdown = seconds.text.toString().toIntOrNull()
        if (countdown == null || countdown !in 0..60) { seconds.error = "请输入 0–60 的整数"; seconds.requestFocus(); return false }
        val value = EmergencyConfig(riderName.text.toString().trim(), phone.text.toString().trim(),
            prompt.text.toString().trim(), countdown, selectedSim(), realSms.isChecked)
        val issue = EmergencyAlertController.save(value)
        if (issue != null) { toast(issue); return false }
        paint(); return true
    }

    private fun requestTestPermissions() {
        val permissions = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (realSms.isChecked) permissions.add(Manifest.permission.SEND_SMS)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) toast("所需运行时权限已授予，请检查通知和全屏提醒设置")
        else requestPermissions(missing.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) { loadSims(selectedSim()); paint() }
    }

    private fun setRealSms(value: Boolean) { changingSwitch = true; realSms.isChecked = value; changingSwitch = false; updatePreview(); paint() }
    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.text = "【MotoNav急刹测试，未确认事故】${riderName.text.toString().trim().ifBlank { "骑行人姓名" }}触发了疑似异常测试，倒计时结束未取消。请先联系本人核实。位置和定位时间将在发送时补充。\n\n${if (realSms.isChecked) "当前界面选择：允许真实短信；保存后才生效。" else "当前界面选择：仅演练，不发短信。"}"
    }

    private fun paint() {
        if (!::status.isInitialized) return
        status.text = "${if (EmergencyAlertController.armed) "本次急刹测试已开启" else "本次急刹测试未开启"}\n${EmergencyAlertController.status}"
        val issue = EmergencyAlertController.readiness(realSms.isChecked)
        val fullScreen = if (Build.VERSION.SDK_INT >= 34) {
            if (getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) "全屏提醒已允许" else "全屏提醒未允许"
        } else "全屏提醒由系统控制"
        readiness.text = (issue ?: "本次测试所需条件已满足") + "\n" + fullScreen
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", riderName.text.toString()); outState.putString("phone", phone.text.toString())
        outState.putString("prompt", prompt.text.toString()); outState.putString("seconds", seconds.text.toString())
        outState.putInt("sim", selectedSim()); outState.putBoolean("real_sms", realSms.isChecked)
        super.onSaveInstanceState(outState)
    }
    private fun openSetting(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("此手机未提供该设置入口，请在系统应用设置中查看") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = NavStyle.dpi(this, value)
    private fun margins() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    private fun label(value: String, size: Float = 14f, color: Int = NavStyle.SUB) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(7), 0, dp(7))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16))
        background = NavStyle.round(this@EmergencySettingsActivity, NavStyle.CARD, 18f, NavStyle.STROKE)
    }
    private fun field(parent: LinearLayout, title: String, value: String, maxLength: Int): EditText {
        val heading = label(title, 15f, NavStyle.TEXT); parent.addView(heading)
        return EditText(this).apply {
            id = View.generateViewId(); heading.labelFor = id
            setText(value); textSize = 16f; setTextColor(NavStyle.TEXT); setHintTextColor(NavStyle.SUB)
            minHeight = dp(52); filters = arrayOf(InputFilter.LengthFilter(maxLength))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = NavStyle.round(this@EmergencySettingsActivity, NavStyle.CARD2, 10f, NavStyle.STROKE)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun button(value: String, action: () -> Unit) = label(value, 16f, NavStyle.PINK).apply {
        minHeight = dp(52); gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = NavStyle.round(this@EmergencySettingsActivity, NavStyle.CARD2, 12f, NavStyle.STROKE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { action() }
    }
}
