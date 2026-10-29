package com.example.motonav

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.os.SystemClock
import java.util.Locale

/**
 * 版本：跟随 App 版本，见 MainActivity.kt 里的 APP_VERSION（v5.0 新增）
 *
 * 来电显示：手机响铃时把来电人的名字（通讯录里查）和号码发给圆屏，圆屏整屏显示；
 * 接听或挂断后发 "#i,0"，圆屏回到地图。
 * - 名字渲染成文字图片放在图片栏 2（圆屏没有中文字库），号码直接发文字："#i,1,13800138000"
 * - 需要权限：电话状态（知道在响铃）、通话记录（安卓 9 起拿号码要它）、通讯录（查名字）；
 *   少了后两个也能用，只是显示号码或“UNKNOWN”
 * - 微信来电/消息通过用户授权的通知监听显示；通知内容和来电控制取决于微信实际提供的系统通知
 * - v5.3 圆屏触屏接听：来电画面上按住圆屏 0.8 秒（固件 v6.9 起 0.2 秒），圆屏发来 "T,A"，这里替你接起电话
 *   （需要“接听电话”权限；接起后声音照常走蓝牙耳机）。圆屏按下时还会报坐标 "T,P,x,y"，调试信息里能看到
 */
object CallWatcher {
    private const val PREFS = "motonav"
    private var ctx: Context? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile var enabled = true
        private set
    @Volatile var privacyMode = false
        private set
    @Volatile var wechatEnabled = false
        private set
    @Volatile var wechatMessageDetails = true
        private set
    @Volatile var lastInfo = "（还没有来电）"
    @Volatile var wechatAnswerDiagnostic = "尚未收到微信来电"
    @Volatile var lastTouch = "（还没按过）"           // v5.3 圆屏最后一次按下的位置
    private val main = Handler(Looper.getMainLooper())
    private var ringing = false
    private var lastNum = ""
    private var wechatCallKey: String? = null
    private var wechatCallLabel = ""
    private var wechatAnswerIntent: PendingIntent? = null
    private var wechatOpenIntent: PendingIntent? = null
    private var wechatAnswerAttemptPending = false
    private var wechatLastCanAnswer = false
    private var wechatAnswerRequestedAt = 0L
    private var wechatCallStartedAt = 0L
    private var wechatCallPostTime = 0L
    private var wechatCallWhen = 0L
    private var wechatCallPublishedAt = 0L
    private var lastWechatMessage = ""
    private var lastWechatMessageAt = 0L
    private val twoLineFirmware = Regex("^v7\\.12\\.9-nav\\.(\\d+)$")
    private val clearWechatMessage = Runnable {
        if (wechatCallKey == null) BleLink.sendImage(13, 0, 0, ByteArray(0))
    }
    private val noticeRefresh = object : Runnable {
        override fun run() {
            when {
                ringing -> {
                    BleLink.send("#i,1,${if (privacyMode) "" else lastNum}")
                    BleLink.send("#I,1,30000")
                }
                wechatCallKey != null && wechatEnabled && wechatCallFresh() ->
                    sendWechatCallState()
                wechatCallKey != null -> {
                    wechatCallKey = null
                    wechatCallLabel = ""
                    wechatAnswerIntent = null
                    wechatOpenIntent = null
                    wechatAnswerAttemptPending = false
                    wechatAnswerRequestedAt = 0L
                    wechatCallPostTime = 0L
                    clearNotice()
                    return
                }
                else -> return
            }
            main.postDelayed(this, 20_000)
        }
    }

    val PERMS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS)                           // v5.3 圆屏触屏接听

    fun load(context: Context) {
        val c = context.applicationContext
        ctx = c
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("call_show", true)
        privacyMode = prefs.getBoolean("call_privacy", false)
        wechatEnabled = prefs.getBoolean("wechat_notice", false)
        wechatMessageDetails = prefs.getBoolean("wechat_message_details", true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("call_show", on).apply()
        if (!on) hide()
    }

    fun setPrivacyMode(context: Context, on: Boolean) {
        privacyMode = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("call_privacy", on).apply()
        if (on) {
            lastNum = ""
            main.removeCallbacks(clearWechatMessage)
            BleLink.sendImage(13, 0, 0, ByteArray(0))
        }
        if (ringing) publishPhone()
        else if (wechatCallKey != null && wechatEnabled && wechatCallFresh()) publishWechatCall()
    }

    fun requiredPermissions(): Array<String> = if (privacyMode)
        arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS) else PERMS

    fun setWechatEnabled(context: Context, on: Boolean) {
        wechatEnabled = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("wechat_notice", on).apply()
        if (!on) {
            main.removeCallbacks(clearWechatMessage)
            wechatCallKey = null
            wechatAnswerIntent = null
            wechatOpenIntent = null
            wechatAnswerAttemptPending = false
            wechatAnswerRequestedAt = 0L
            wechatCallStartedAt = 0L
            wechatCallPostTime = 0L
            if (!ringing) clearNotice()
        }
    }

    fun setWechatMessageDetails(context: Context, on: Boolean) {
        wechatMessageDetails = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("wechat_message_details", on).apply()
        if (!on && wechatCallKey == null) {
            main.removeCallbacks(clearWechatMessage)
            BleLink.sendImage(13, 0, 0, ByteArray(0))
        }
    }

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun permsText(context: Context): String {
        val c = context.applicationContext
        return when {
            !granted(c, Manifest.permission.READ_PHONE_STATE) -> "没有电话权限，来电显示不能用"
            !privacyMode && !granted(c, Manifest.permission.READ_CALL_LOG) -> "没有通话记录权限：只显示“来电”，看不到号码"
            !privacyMode && !granted(c, Manifest.permission.READ_CONTACTS) -> "没有通讯录权限：只显示号码，不显示名字"
            !granted(c, Manifest.permission.ANSWER_PHONE_CALLS) -> "没有接听电话权限：圆屏上按住接不了电话（把来电显示关掉再打开就会弹授权）"
            else -> if (privacyMode) "隐私模式：只显示来电" else "权限齐全"
        }
    }

    /** 后台服务启动时调用；授权后再调用一次也没关系 */
    fun start(context: Context) {
        val c = context.applicationContext
        ctx = c
        load(c)
        if (receiver != null) return
        if (!granted(c, Manifest.permission.READ_PHONE_STATE)) { lastInfo = "没有电话权限"; return }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                @Suppress("DEPRECATION")
                val num = if (privacyMode) null else intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                when (st) {
                    TelephonyManager.EXTRA_STATE_RINGING -> if (enabled) show(num)
                    else -> hide()                                      // 接听（OFFHOOK）或挂断（IDLE）
                }
            }
        }
        try {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else c.registerReceiver(r, f)
            receiver = r
            lastInfo = "来电显示已开启（${permsText(c)}）"
        } catch (e: Exception) {
            lastInfo = "来电监听没能开启：${e.message}"
        }
    }

    fun stop() {
        val c = ctx ?: return
        receiver?.let { try { c.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        main.removeCallbacks(noticeRefresh)
        main.removeCallbacks(clearWechatMessage)
        if (wechatCallKey == null) BleLink.sendImage(13, 0, 0, ByteArray(0))
    }

    private fun show(raw: String?) {
        val digits = raw?.filter { it.isDigit() || it == '+' } ?: ""
        // 安卓会发两次响铃广播：第一次可能没有号码，第二次才有；号码没变就不重复发
        if (ringing && (digits == lastNum || digits.isEmpty())) return
        ringing = true
        lastNum = digits
        publishPhone()
    }

    private fun sendLabel(slot: Int, label: String) {
        val img = TextImage.render(label, 260, 36f, 20f)
        if (img != null) BleLink.sendImage(slot, img.w, img.h, img.data)
        else BleLink.sendImage(slot, 0, 0, ByteArray(0))
    }

    private fun supportsTwoLineNotice(): Boolean =
        BleLink.screenBoard == "175C" &&
            (twoLineFirmware.matchEntire(BleLink.screenFirmware)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 4

    private fun publishPhone() {
        val name = if (!privacyMode && lastNum.isNotEmpty()) lookupName(lastNum) else null
        sendLabel(2, if (privacyMode) "来电" else name.orEmpty())
        BleLink.send("#i,1,${if (privacyMode) "" else lastNum}")
        BleLink.send("#I,1,30000")
        lastInfo = if (privacyMode) "来电（隐私模式）"
            else "来电：${name ?: "（通讯录里没有）"} ${lastNum.ifEmpty { "（没拿到号码）" }}"
        scheduleRefresh()
    }

    private fun hide() {
        if (!ringing) return
        ringing = false
        lastNum = ""
        BleLink.send("#i,0")
        if (wechatCallKey != null && wechatEnabled && wechatCallFresh()) publishWechatCall()
        else clearNotice()
    }

    private fun scheduleRefresh() {
        main.removeCallbacks(noticeRefresh)
        main.postDelayed(noticeRefresh, 20_000)
    }

    private fun wechatCallFresh(): Boolean = wechatCallPostTime > 0L &&
        System.currentTimeMillis() - wechatCallPostTime in 0L..120_000L &&
        SystemClock.elapsedRealtime() - wechatCallStartedAt < 120_000L

    fun hasFreshWechatCall(): Boolean = wechatEnabled && !ringing &&
        wechatCallKey != null && wechatCallFresh()

    private fun clearNotice() {
        main.removeCallbacks(noticeRefresh)
        main.removeCallbacks(clearWechatMessage)
        BleLink.send("#I,0")
        BleLink.sendImage(2, 0, 0, ByteArray(0))
        BleLink.sendImage(13, 0, 0, ByteArray(0))
    }

    @Suppress("DEPRECATION")
    private fun wechatAnswerAction(n: Notification): PendingIntent? {
        if (Build.VERSION.SDK_INT >= 31) {
            val extras = n.extras
            val answer = try {
                if (Build.VERSION.SDK_INT >= 33)
                    extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                else extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT) as? PendingIntent
            } catch (_: RuntimeException) { null }
            if (answer?.creatorPackage == "com.tencent.mm") return answer
        }
        val answerLabels = setOf("接听", "接通", "接受", "接听语音通话", "接听视频通话", "接听电话",
            "接通语音通话", "接通视频通话", "接受语音通话", "接受视频通话",
            "answer", "answer call", "answer voice call", "answer video call", "accept", "accept call")
        val candidates = ArrayList<PendingIntent>()
        for (action in n.actions.orEmpty()) {
            val title = action.title?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (title !in answerLabels) continue
            if (action.remoteInputs?.isNotEmpty() == true) continue
            if (Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired) continue
            val intent = action.actionIntent
            if (intent?.creatorPackage == "com.tencent.mm") candidates.add(intent)
        }
        return candidates.singleOrNull()
    }

    private fun wechatOpenAction(n: Notification): PendingIntent? =
        listOfNotNull(n.fullScreenIntent, n.contentIntent).firstOrNull {
            it.creatorPackage == "com.tencent.mm" && it.isActivity
        }

    private fun wechatCanAnswer(): Boolean {
        if (wechatAnswerAttemptPending ||
            wechatAnswerRequestedAt != 0L && SystemClock.elapsedRealtime() - wechatAnswerRequestedAt < 10_000L) return false
        return wechatAnswerIntent != null ||
            AmapScreenReader.isConnected() &&
                (wechatOpenIntent != null || AmapScreenReader.hasWechatAnswerControl())
    }

    private fun sendWechatCallState() {
        wechatLastCanAnswer = wechatCanAnswer()
        BleLink.send("#I,2,30000,${if (wechatLastCanAnswer) 1 else 0}")
    }

    private fun wechatAnswerFailed(message: String) {
        lastInfo = message
        sendLabel(13, "微信接听失败")
        sendWechatCallState()
        val key = wechatCallKey
        val requestedAt = wechatAnswerRequestedAt
        val remaining = (10_050L - (SystemClock.elapsedRealtime() - requestedAt)).coerceAtLeast(0L)
        main.postDelayed({
            if (wechatCallKey == key && wechatAnswerRequestedAt == requestedAt &&
                !wechatAnswerAttemptPending && wechatEnabled && wechatCallFresh() && !ringing) {
                publishWechatCall()
            }
        }, remaining)
    }

    /** Accessibility events may expose the answer button after the call notification arrives. */
    fun onWechatUiChanged() {
        if (!wechatEnabled || wechatCallKey == null || !wechatCallFresh()) return
        if (wechatAnswerAttemptPending && AmapScreenReader.clickWechatAnswer()) {
            wechatAnswerAttemptPending = false
            lastInfo = "已点击微信接听，等待来电状态更新"
        }
        if (ringing) return
        val canAnswer = wechatCanAnswer()
        if (canAnswer != wechatLastCanAnswer) sendWechatCallState()
    }

    /** 通知使用权授予后读取微信通知；正文只发给已配对的圆屏，不写入诊断日志。 */
    fun onWechatPosted(context: Context, sbn: StatusBarNotification) {
        if (ctx == null) load(context)
        if (!wechatEnabled || sbn.packageName != "com.tencent.mm") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!sbn.isOngoing && System.currentTimeMillis() - sbn.postTime > 60_000) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val callHint = "$title $body"
        val callType = if (Build.VERSION.SDK_INT >= 31) n.extras?.getInt(
            Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_UNKNOWN) else null
        val currentCallNotice = n.category == Notification.CATEGORY_CALL || sbn.key == wechatCallKey
        if (callType == Notification.CallStyle.CALL_TYPE_ONGOING ||
            currentCallNotice && listOf("正在通话", "通话中", "通话已接通", "通话结束").any { it in callHint }) {
            if (wechatCallKey == sbn.key) {
                wechatCallKey = null
                wechatCallLabel = ""
                wechatAnswerIntent = null
                wechatOpenIntent = null
                wechatAnswerAttemptPending = false
                wechatAnswerRequestedAt = 0L
                wechatCallPostTime = 0L
                if (!ringing) clearNotice()
            }
            return
        }
        val isCall = n.category == Notification.CATEGORY_CALL ||
            callHint.contains("邀请你进行语音通话") || callHint.contains("邀请你进行视频通话") ||
            (sbn.isOngoing && (callHint.contains("语音通话") || callHint.contains("视频通话")))
        if (isCall) {
            main.removeCallbacks(clearWechatMessage)
            val now = SystemClock.elapsedRealtime()
            // A new call can reuse WeChat's notification key. Preserve updates with the
            // same notification when-time, and keep an in-flight answer through its cooldown.
            val sameCall = wechatCallKey == sbn.key &&
                (wechatCallPostTime == sbn.postTime ||
                    n.`when` != 0L && n.`when` == wechatCallWhen ||
                    wechatAnswerRequestedAt != 0L && now - wechatAnswerRequestedAt < 10_000L)
            if (!sameCall) {
                wechatCallStartedAt = now
                wechatCallPostTime = sbn.postTime
                wechatCallWhen = n.`when`
                wechatAnswerAttemptPending = false
                wechatAnswerRequestedAt = 0L
            } else if (sbn.postTime != wechatCallPostTime) wechatCallPostTime = sbn.postTime
            if (!wechatCallFresh()) {
                if (sameCall) {
                    wechatCallKey = null
                    wechatCallLabel = ""
                    wechatAnswerIntent = null
                    wechatOpenIntent = null
                    wechatAnswerAttemptPending = false
                    wechatAnswerRequestedAt = 0L
                    wechatCallStartedAt = 0L
                    wechatCallPostTime = 0L
                    wechatCallPublishedAt = 0L
                    if (!ringing) clearNotice()
                }
                return
            }
            val label = if (privacyMode || n.visibility == Notification.VISIBILITY_SECRET) "来电"
                else title.lineSequence().firstOrNull().orEmpty().take(32).ifBlank { "微信来电" }
            val notificationAnswer = wechatAnswerAction(n)
            val answer = if (wechatAnswerRequestedAt != 0L && now - wechatAnswerRequestedAt < 10_000)
                null else notificationAnswer
            val open = wechatOpenAction(n)
            // Record only action availability; caller and message content stay private.
            wechatAnswerDiagnostic = "通知接听动作=${if (notificationAnswer != null) "有" else "无"}，" +
                "微信动作数=${n.actions.orEmpty().count { it.actionIntent?.creatorPackage == "com.tencent.mm" }}，" +
                "系统接听字段=${if (Build.VERSION.SDK_INT >= 31 && n.extras?.containsKey(Notification.EXTRA_ANSWER_INTENT) == true) "有" else "无"}，" +
                "无障碍=${if (AmapScreenReader.isConnected()) "已开启" else "未开启"}"
            if (sameCall && label == wechatCallLabel && answer == wechatAnswerIntent && open == wechatOpenIntent &&
                now - wechatCallPublishedAt < 15_000) return
            wechatCallKey = sbn.key
            wechatCallLabel = label
            wechatAnswerIntent = answer
            wechatOpenIntent = open
            if (!ringing) publishWechatCall()
            return
        }
        if (ringing || wechatCallKey != null || sbn.isOngoing || (title.isBlank() && body.isBlank())) return
        val now = SystemClock.elapsedRealtime()
        val signature = "${sbn.key}:${n.`when`}:${body.hashCode()}"
        if (signature == lastWechatMessage && now - lastWechatMessageAt < 3000) return
        lastWechatMessage = signature
        lastWechatMessageAt = now
        main.removeCallbacks(clearWechatMessage)
        val hidden = privacyMode || n.visibility == Notification.VISIBILITY_SECRET
        val label = if (hidden) "微信消息"
            else title.lineSequence().firstOrNull().orEmpty().take(32).ifBlank { "微信消息" }
        if (wechatMessageDetails && !hidden && body.isNotBlank()) {
            val img = if (supportsTwoLineNotice()) TextImage.renderNotice(label, body)
                else TextImage.render("$label：${body.replace(Regex("\\s+"), " ").trim()}", 260, 30f, 18f)
            if (img != null) BleLink.sendImage(13, img.w, img.h, img.data)
            else sendLabel(13, label)
        } else sendLabel(13, label)
        BleLink.sendEvent("#I,3,5000")
        main.postDelayed(clearWechatMessage, 5_500)
        lastInfo = "微信消息提示已发送"
    }

    fun onWechatRemoved(sbn: StatusBarNotification) {
        if (sbn.key != wechatCallKey) return
        wechatCallKey = null
        wechatCallLabel = ""
        wechatAnswerIntent = null
        wechatOpenIntent = null
        wechatAnswerAttemptPending = false
        wechatAnswerRequestedAt = 0L
        wechatCallStartedAt = 0L
        wechatCallPostTime = 0L
        wechatCallPublishedAt = 0L
        if (!ringing) clearNotice()
    }

    fun onWechatListenerDisconnected() {
        wechatCallKey = null
        wechatCallLabel = ""
        wechatAnswerIntent = null
        wechatOpenIntent = null
        wechatAnswerAttemptPending = false
        wechatAnswerRequestedAt = 0L
        wechatCallStartedAt = 0L
        wechatCallPostTime = 0L
        wechatCallPublishedAt = 0L
        if (!ringing) clearNotice()
    }

    private fun publishWechatCall() {
        sendLabel(13, if (privacyMode) "来电" else wechatCallLabel)
        sendWechatCallState()
        wechatCallPublishedAt = SystemClock.elapsedRealtime()
        lastInfo = if (wechatLastCanAnswer) "微信来电：圆屏可按住尝试接听"
            else "微信来电：当前没有可用的接听路径，请在手机接听"
        scheduleRefresh()
    }

    /** 圆屏长按发 T,A；普通电话走 Telecom，微信来电先用通知动作，再尝试可见的微信来电页。 */
    fun onScreenTouch(p: List<String>) {
        when (p.getOrNull(1)) {
            "P" -> lastTouch = "x=${p.getOrNull(2)} y=${p.getOrNull(3)}"
            "A" -> main.post { answer() }
            "W" -> Weather.request()                                  // v5.4 圆屏滑到了天气页
        }
    }

    private fun answer() {
        val c = ctx ?: return
        if (ringing) {
            if (!granted(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
                lastInfo = "圆屏要求接听，但没有“接听电话”权限（把来电显示关掉再打开授权）"
                return
            }
            lastInfo = try {
                val tm = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                @Suppress("DEPRECATION")
                tm.acceptRingingCall()
                "已从圆屏接听普通电话"
            } catch (e: Exception) {
                "圆屏接听普通电话失败：${e.message}"
            }
            return
        }
        if (!wechatEnabled || wechatCallKey == null || !wechatCallFresh()) {
            lastInfo = "圆屏要求接听，但现在没有有效的微信来电"
            return
        }
        val key = wechatCallKey ?: return
        val requestedAt = SystemClock.elapsedRealtime()
        if (wechatAnswerRequestedAt != 0L && requestedAt - wechatAnswerRequestedAt < 10_000L) {
            lastInfo = "微信接听请求刚发送，请稍候或在手机接听"
            return
        }
        val action = wechatAnswerIntent
        if (action?.creatorPackage == "com.tencent.mm") {
            wechatAnswerIntent = null
            wechatAnswerRequestedAt = requestedAt
            sendWechatCallState()
            lastInfo = try {
                action.send()
                "已向微信发送接听请求，等待来电状态更新"
            } catch (e: Exception) {
                wechatAnswerFailed("微信接听动作已失效：${e.message}，请在手机接听")
                return
            }
            return
        }
        if (!AmapScreenReader.isConnected()) {
            lastInfo = "微信通知无接听动作；请启用 MotoNav 无障碍服务后重试"
            return
        }
        wechatAnswerRequestedAt = requestedAt
        sendWechatCallState()
        if (AmapScreenReader.clickWechatAnswer()) {
            lastInfo = "已点击微信接听，等待来电状态更新"
            return
        }
        val open = wechatOpenIntent
        if (open == null) {
            wechatAnswerFailed("微信来电页未显示明确接听控件，通知也没有可打开的来电入口")
            return
        }
        wechatAnswerAttemptPending = true
        try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle() else null
            open.send(c, 0, null, null, null, null, options)
            lastInfo = "已请求打开微信来电页，正在寻找接听控件"
        } catch (e: Exception) {
            wechatAnswerAttemptPending = false
            wechatAnswerFailed("微信来电页无法打开：${e.message}，请在手机接听")
            return
        }
        for (delay in listOf(300L, 800L, 1_500L, 2_500L, 4_000L)) {
            main.postDelayed({
                if (!wechatAnswerAttemptPending || wechatCallKey != key ||
                    wechatAnswerRequestedAt != requestedAt || !wechatCallFresh()) return@postDelayed
                if (AmapScreenReader.clickWechatAnswer()) {
                    wechatAnswerAttemptPending = false
                    lastInfo = "已点击微信接听，等待来电状态更新"
                } else if (delay == 4_000L) {
                    wechatAnswerAttemptPending = false
                    wechatAnswerFailed("微信来电页未提供明确可点击的接听控件，请在手机接听")
                }
            }, delay)
        }
    }

    /** 在通讯录里按号码查名字；没权限或查不到返回 null */
    private fun lookupName(num: String): String? {
        val c = ctx ?: return null
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
