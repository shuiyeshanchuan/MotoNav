package com.example.motonav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** User-armed, one-input experiment. It never changes the production BLE answer path. */
object WechatGestureProbe {
    private const val WECHAT = "com.tencent.mm"
    private const val ARM_MS = 60_000L
    private const val MAX_REPORT = 100_000
    private const val MAX_NODES = 180
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var service: AccessibilityService? = null
    private var report = ""
    private var state = "尚未开始专项验证"
    private var armed = ""
    private var armGeneration = 0L
    private var forcedGesture = false
    private var busy = false
    private var asyncGeneration = 0L
    private var waitReason = ""
    private var captureCallToken = 0L
    private var captureAt = 0L
    private var captureFingerprint = ""
    private var target: Target? = null

    @Volatile var screenshot: Bitmap? = null
        private set
    @Volatile var captureId = 0L
        private set
    @Volatile var captureWidth = 0
        private set
    @Volatile var captureHeight = 0
        private set
    @Volatile var captureRotation = 0
        private set
    @Volatile var captureCallType = "未知"
        private set

    private data class Target(val x: Float, val y: Float, val id: Long)
    private data class Screen(val width: Int, val height: Int, val rotation: Int)
    private data class Node(val ref: AccessibilityNodeInfo, val depth: Int, val bounds: Rect,
                            val text: String, val description: String, val id: String) {
        val labels get() = listOf(text, description).map { it.trim().lowercase(Locale.ROOT) }
    }
    private class Page(val pkg: String, val bounds: Rect, val screen: Screen,
                       val nodes: List<Node>, val windows: String, val complete: Boolean) {
        val labels get() = nodes.flatMap { it.labels }
        val callType get() = when {
            labels.any { "视频通话" in it || "视频来电" in it || "video call" in it } -> "视频"
            labels.any { "语音通话" in it || "语音来电" in it || "voice call" in it } -> "语音"
            else -> "未知"
        }
        val fingerprint: String get() {
            // Boundaries, IDs and control labels distinguish the incoming layout from other pages.
            // Caller names and transient animation text do not become layout identifiers.
            val raw = nodes.joinToString("|") {
                "${it.depth}:${it.id}:${it.ref.className}:${it.bounds.toShortString()}:" +
                    it.labels.filter { label -> label in answerLabels || label in declineLabels }.joinToString()
            }
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        fun close() { nodes.forEach { @Suppress("DEPRECATION") it.ref.recycle() } }
    }

    private val answerLabels = setOf("接听", "接听语音通话", "接听视频通话", "接听微信电话", "接听电话",
        "answer", "answer call", "answer voice call", "answer video call", "accept call")
    private val declineLabels = setOf("拒绝", "拒接", "挂断", "decline", "decline call", "reject", "reject call")

    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        report = try { reportFile()?.takeIf { it.isFile }?.readText()?.takeLast(MAX_REPORT).orEmpty() }
        catch (_: Exception) { "" }
    }

    fun connected(value: AccessibilityService) {
        init(value)
        service = value
        state = "无障碍已连接；尚未安排点击"
    }

    fun disconnected(value: AccessibilityService) {
        if (service !== value) return
        service = null
        cancel()
        asyncGeneration++
        busy = false
        captureCallToken = 0L
        target = null
        state = "无障碍已断开，专项任务已取消"
    }

    fun statusText(): String = "${if (service != null) "无障碍已连接" else "无障碍未连接"} · $state" +
        if (target != null) "\n已确认本次截图接听点；每次测试最多一个输入" else "\n尚未确认截图接听点"

    fun reportText(): String = report.ifBlank { "（尚无专项记录）" }

    fun armCapture(): String = arm("采集", false)

    fun armOneShot(forceGesture: Boolean = false): String {
        if (forceGesture && (!validCapture() || target == null)) return "请先采集当前这通来电的完整页面并在截图上确认接听点"
        return arm("点击", forceGesture)
    }

    private fun arm(kind: String, force: Boolean): String {
        if (busy) return "上次采集或点击尚未完成，请稍候"
        val s = service ?: return "无障碍未连接，请关闭再重新开启 MotoNav 无障碍服务"
        if (kind == "采集" && Build.VERSION.SDK_INT < 30) return "此手机低于 Android 11，专项截图不可用；请保留手动接听"
        if (kind == "点击" && s.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0)
            return "服务尚未加载手势能力，请关闭再重新开启 MotoNav 无障碍服务"
        armed = kind
        waitReason = ""
        forcedGesture = force
        asyncGeneration++
        val generation = ++armGeneration
        append("服务能力：capabilities=${s.serviceInfo.capabilities}，" +
            "读窗口=${s.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0}，" +
            "手势=${s.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0}，" +
            "截图=${Build.VERSION.SDK_INT >= 30 && s.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0}。")
        append("安排$kind：60 秒内手动打开微信完整来电页，手机保持亮屏解锁；" +
            if (kind == "点击") "路径=${if (force) "仅模拟点击" else "优先节点"}，最多一次输入，不自动重试。" else "仅采集，不发送点击。")
        state = "已安排$kind，等待微信完整来电页（60 秒）"
        main.postDelayed({
            if (generation == armGeneration && armed.isNotEmpty()) {
                armed = ""
                append("等待超时：未执行任何输入。")
                state = "等待超时；未执行点击"
            }
        }, ARM_MS)
        onWindowChanged()
        fun pollConditions() {
            if (generation != armGeneration || armed.isEmpty()) return
            onWindowChanged()
            if (generation == armGeneration && armed.isNotEmpty()) main.postDelayed({ pollConditions() }, 500)
        }
        main.postDelayed({ pollConditions() }, 500) // Readiness polling never repeats an input.
        return "已安排$kind，请在 60 秒内手动打开微信完整来电页"
    }

    fun cancel(): String {
        armed = ""
        armGeneration++
        asyncGeneration++
        busy = false
        state = "等待任务已取消；已提交的输入不能撤回"
        return "已取消等待任务"
    }

    /** Called by the existing service only; ordinary WeChat events do nothing unless armed. */
    fun onWindowChanged() {
        if (armed.isEmpty() || busy) return
        val s = service ?: return
        try {
            if (!unlocked(s)) { waiting("等待手机亮屏并解锁；未执行输入"); return }
            if (!CallWatcher.hasFreshWechatCall()) { waiting("等待有效微信来电；未执行输入"); return }
            val page = readPage(s) ?: run { waiting("当前窗口不可读；等待微信完整来电页"); return }
            try {
                val rejection = pageRejection(page)
                if (rejection != null) { waiting("$rejection；未执行输入"); return }
                val kind = armed
                armed = "" // Consume before any asynchronous screenshot, ACTION_CLICK or gesture submission.
                armGeneration++
                dumpPage(page, "输入前/采集页")
                if (kind == "采集") capture(s, page) else clickOnce(s, page, forcedGesture)
            } finally { page.close() }
        } catch (e: Exception) {
            armed = ""
            busy = false
            state = "专项失败：${e.javaClass.simpleName}；未自动重试"
            append("$state：${e.message}")
        }
    }

    fun configureTarget(xRatio: Float, yRatio: Float, width: Int, height: Int, rotation: Int,
                        callType: String, expectedCaptureId: Long = captureId): String {
        if (!xRatio.isFinite() || !yRatio.isFinite() || xRatio !in 0f..1f || yRatio !in 0f..1f)
            return "接听点超出原图范围"
        if (!validCapture() || expectedCaptureId != captureId || width != captureWidth || height != captureHeight ||
            rotation != captureRotation || callType != captureCallType) return "截图已过期或尺寸/方向/来电类型不匹配，请重新采集"
        target = Target(xRatio, yRatio, captureId)
        append("用户确认本通来电截图接听点：x=%.4f，y=%.4f，原图=%dx%d，方向=%d，类型=%s。".format(
            Locale.ROOT, xRatio, yRatio, width, height, rotation, callType))
        state = "已确认接听点；尚未执行点击"
        return "已确认本次截图上的接听点"
    }

    fun recordOutcome(value: String): String {
        append("用户人工核实：${value.take(300)}（以双方实际通话为准）。")
        state = "已保存人工结果"
        return "人工结果已保存"
    }

    fun copyReport(ctx: Context): String {
        init(ctx)
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("微信接听专项诊断", reportText()))
        return "已复制专项记录；发送前请检查联系人等隐私信息"
    }

    fun clear(): String {
        if (busy) return "采集或点击尚未完成，请稍候再清除"
        cancel()
        report = ""
        screenshot = null
        target = null
        captureCallToken = 0L
        captureAt = 0L
        captureWidth = 0
        captureHeight = 0
        captureId++
        asyncGeneration++
        try { reportFile()?.delete(); screenshotFile()?.delete() } catch (_: Exception) {}
        state = "本机专项记录与截图已清除"
        return state
    }

    private fun validCapture(): Boolean = screenshot != null && captureCallToken != 0L &&
        captureCallToken == CallWatcher.wechatCallSessionToken() &&
        SystemClock.elapsedRealtime() - captureAt in 0L..120_000L

    private fun waiting(reason: String) {
        state = reason
        if (reason != waitReason) { waitReason = reason; append("等待条件：$reason") }
    }

    private fun unlocked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
            !(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    @Suppress("DEPRECATION")
    private fun screen(s: AccessibilityService): Screen {
        val display = (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val size = Point().also { display.getRealSize(it) }
        return Screen(size.x, size.y, display.rotation)
    }

    @Suppress("DEPRECATION")
    private fun readPage(s: AccessibilityService): Page? {
        val windowInfo = ArrayList<String>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        val initialRoot = s.rootInActiveWindow
        var chosenRoot: AccessibilityNodeInfo? = null
        var ownershipTransferred = false
        if (initialRoot != null) roots += initialRoot
        val wins = try { s.windows.orEmpty() } catch (_: Exception) { emptyList() }
        try {
            val candidates = ArrayList<Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>>()
            for (window in wins) {
                val bounds = Rect().also(window::getBoundsInScreen)
                val windowRoot = window.root
                windowInfo += "window id=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused} " +
                    "pkg=${windowRoot?.packageName ?: "null"} bounds=${bounds.toShortString()}"
                if (windowRoot != null) {
                    roots += windowRoot
                    candidates += windowRoot to window
                }
            }
            // Some devices keep MotoNav's root active while the full WeChat window is
            // present in the interactive-window list. Prefer an active WeChat
            // application window, then fall back to the current root for a safe
            // foreground-package rejection. Never select an inactive WeChat window.
            val activeWechat = candidates.firstOrNull { (root, window) ->
                root.packageName?.toString() == WECHAT &&
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive
            }?.first
            chosenRoot = activeWechat
                ?: initialRoot
                ?: candidates.firstOrNull { it.second.isActive }?.first
            val chosen = chosenRoot ?: return null
            val nodes = ArrayList<Node>()
            var truncated = false
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                val bounds = Rect().also(node::getBoundsInScreen)
                nodes += Node(node, depth, bounds, node.text?.toString().orEmpty().take(160),
                    node.contentDescription?.toString().orEmpty().take(160), node.viewIdResourceName.orEmpty().take(160))
                if (depth >= 24) { if (node.childCount > 0) truncated = true; return }
                for (i in 0 until node.childCount) {
                    if (nodes.size >= MAX_NODES) { truncated = true; break }
                    node.getChild(i)?.let { walk(it, depth + 1) }
                }
            }
            try {
                if (chosen.packageName?.toString() == WECHAT) walk(chosen, 0)
                else nodes += Node(chosen, 0, Rect().also(chosen::getBoundsInScreen), "", "", "")
                val bounds = Rect().also(chosen::getBoundsInScreen)
                val page = Page(chosen.packageName?.toString().orEmpty(), bounds, screen(s), nodes,
                    windowInfo.joinToString("\n"), !truncated)
                ownershipTransferred = true
                return page
            }
            catch (e: Exception) {
                nodes.forEach { if (it.ref !== chosen) it.ref.recycle() }
                throw e
            }
        } finally {
            val retained = if (ownershipTransferred) chosenRoot else null
            val released = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            for (root in roots) if (root !== retained && released.add(root)) root.recycle()
            wins.forEach { it.recycle() }
        }
    }

    private fun pageRejection(page: Page): String? = when {
        page.pkg != WECHAT -> "微信来电页未在前台（当前 ${page.pkg.ifBlank { "未知" }}）"
        page.bounds.width() < page.screen.width * 0.8f || page.bounds.height() < page.screen.height * 0.65f ->
            "当前不是完整微信窗口（可能仅为横幅）"
        !page.complete -> "节点树超出限制，停止测试"
        page.labels.filter { it in setOf("微信", "通讯录", "发现", "我") }.distinct().size >= 2 -> "当前是微信聊天页"
        page.labels.any { label -> listOf("正在通话", "通话中", "已接通", "通话结束").any { it in label } } ->
            "当前页面包含通话中或结束状态，停止测试"
        else -> null
    }

    private fun dumpPage(page: Page, title: String) {
        val out = StringBuilder("$title：pkg=${page.pkg} window=${page.bounds.toShortString()} " +
            "display=${page.screen.width}x${page.screen.height} rotation=${page.screen.rotation} " +
            "type=${page.callType} nodes=${page.nodes.size} complete=${page.complete}\n${page.windows}\n")
        for ((i, node) in page.nodes.withIndex()) {
            fun safe(value: String) = value.replace("\n", " ").replace("\r", " ")
            out.append("[$i] depth=${node.depth} class=${node.ref.className} id=${node.id} " +
                "text=\"${safe(node.text)}\" desc=\"${safe(node.description)}\" " +
                "bounds=${node.bounds.toShortString()} visible=${node.ref.isVisibleToUser} " +
                "enabled=${node.ref.isEnabled} clickable=${node.ref.isClickable} " +
                "actions=${node.ref.actionList.joinToString { it.id.toString() }}\n")
            if (out.length > 45_000) { out.append("（本次节点输出已截断）\n"); break }
        }
        append(out.toString())
    }

    private fun capture(s: AccessibilityService, page: Page) {
        if (Build.VERSION.SDK_INT < 30) { state = "Android 11 以下不可专项截图"; append(state); return }
        if (s.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
            state = "服务未加载截图能力，请关闭再重新开启无障碍服务"
            append(state)
            return
        }
        busy = true
        val operation = ++asyncGeneration
        target = null
        screenshot = null
        captureCallToken = 0L
        val callToken = CallWatcher.wechatCallSessionToken()
        val fingerprint = page.fingerprint
        val callType = page.callType
        val geometry = page.screen
        state = "正在采集一次截图；未发送点击"
        main.postDelayed({
            if (operation == asyncGeneration && busy) {
                busy = false
                asyncGeneration++
                state = "截图 8 秒内未收到回调；未自动重试"
                append(state)
            }
        }, 8_000)
        val executor = Executor { main.post(it) }
        s.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    if (operation != asyncGeneration || service !== s) return
                    val current = readPage(s)
                    val stillSame = current != null && current.pkg == WECHAT && current.fingerprint == fingerprint &&
                        current.screen == geometry && unlocked(s) && callToken != 0L && callToken == CallWatcher.wechatCallSessionToken()
                    current?.close()
                    if (!stillSame) { state = "截图期间页面或来电已变化，截图已丢弃"; append(state); return }
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?: throw IllegalStateException("无法转换截图缓冲区")
                    val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) }
                    finally { hardware.recycle() }
                        ?: throw IllegalStateException("无法复制截图")
                    if (bitmap.width != geometry.width || bitmap.height != geometry.height) {
                        bitmap.recycle()
                        state = "截图尺寸与当前屏幕像素不同，停止标定"
                        append(state)
                        return
                    }
                    screenshotFile()?.outputStream()?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot = bitmap
                    captureId++
                    captureWidth = bitmap.width
                    captureHeight = bitmap.height
                    captureRotation = geometry.rotation
                    captureCallType = callType
                    captureFingerprint = fingerprint
                    captureCallToken = callToken
                    captureAt = SystemClock.elapsedRealtime()
                    state = "截图采集完成；请回专项页确认接听点，未发送任何点击"
                    append("$state；原图 ${bitmap.width}x${bitmap.height}，方向=$captureRotation，类型=$callType；仅保存在本机非备份私有目录。")
                } catch (e: Exception) { state = "截图处理失败：${e.javaClass.simpleName}"; append("$state：${e.message}") }
                finally { result.hardwareBuffer.close(); if (operation == asyncGeneration) busy = false }
            }
            override fun onFailure(errorCode: Int) {
                if (operation != asyncGeneration || service !== s) return
                busy = false
                val reason = when (errorCode) {
                    1 -> "系统内部错误"
                    2 -> "未声明截图能力或未重新加载服务"
                    3 -> "截图请求间隔过短"
                    4 -> "显示屏无效"
                    5 -> "窗口无效"
                    6 -> "安全窗口禁止截图"
                    else -> "系统返回未知错误"
                }
                state = "截图失败：$reason（代码 $errorCode）；未自动重试"
                append(state)
            }
        })
    }

    private fun answerNode(page: Page): AccessibilityNodeInfo? {
        fun button(node: Node): AccessibilityNodeInfo? {
            if (!node.ref.isVisibleToUser || !node.ref.isEnabled) return null
            val available = page.nodes.associateBy { it.ref }
            var current = node.ref
            repeat(3) {
                val bound = Rect().also(current::getBoundsInScreen)
                if (current.isVisibleToUser && current.isEnabled && current.isClickable && !bound.isEmpty &&
                    page.bounds.contains(bound) && bound.centerY() > page.bounds.top + page.bounds.height() * 3 / 5 &&
                    bound.width().toLong() * bound.height() < page.bounds.width().toLong() * page.bounds.height() / 3)
                    return current
                val parent = current.parent ?: return null
                val known = available[parent]?.ref
                @Suppress("DEPRECATION") parent.recycle()
                current = known ?: return null
            }
            return null
        }
        val answers = page.nodes.filter { it.labels.any(answerLabels::contains) }.mapNotNull(::button)
            .distinctBy { Rect().also(it::getBoundsInScreen).toShortString() }
        val declines = page.nodes.filter { it.labels.any(declineLabels::contains) }.mapNotNull(::button)
            .distinctBy { Rect().also(it::getBoundsInScreen).toShortString() }
        if (answers.size != 1 || declines.size != 1) return null
        val a = Rect().also(answers.single()::getBoundsInScreen)
        val d = Rect().also(declines.single()::getBoundsInScreen)
        if (kotlin.math.abs(a.centerX() - d.centerX()) < page.bounds.width() / 4 ||
            kotlin.math.abs(a.centerY() - d.centerY()) > page.bounds.height() / 8) return null
        return answers.single()
    }

    private fun clickOnce(s: AccessibilityService, page: Page, forceGesture: Boolean) {
        if (!unlocked(s) || !CallWatcher.hasFreshWechatCall()) { state = "输入前已锁屏或来电过期；未执行"; append(state); return }
        asyncGeneration++
        val before = page.fingerprint
        val callToken = CallWatcher.wechatCallSessionToken()
        val node = if (!forceGesture) answerNode(page) else null
        if (node != null) {
            val sent = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            state = "节点点击返回 $sent；尚未确认是否接通，不补点、不重试"
            append("ACTION_CLICK：$sent。$state")
            observeAfter(s, before, callToken)
            return
        }
        append(if (forceGesture) "用户选择仅模拟点击；不发送 ACTION_CLICK。" else "未找到唯一可点击的底部接听/拒绝控件，检查本通来电截图标定。")
        val selected = target
        val geometry = page.screen
        if (!validCapture() || selected == null || selected.id != captureId || captureWidth != geometry.width ||
            captureHeight != geometry.height || captureRotation != geometry.rotation || captureCallType != page.callType ||
            captureFingerprint != page.fingerprint) {
            state = "未执行模拟点击：本通电话的截图/确认点/布局/尺寸/方向/类型不匹配，请重新采集"
            append(state)
            return
        }
        val x = selected.x * captureWidth
        val y = selected.y * captureHeight
        if (!page.bounds.contains(x.toInt(), y.toInt())) { state = "确认点不在当前微信窗口内，未点击"; append(state); return }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 80)).build()
        busy = true
        val operation = ++asyncGeneration
        val id = captureId
        target = null // Each manual confirmation grants at most one gesture, including failed submissions.
        val submitted = try {
            s.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (operation != asyncGeneration || service !== s) return
                    busy = false
                    state = "手势回调完成；尚未确认接通，请人工核实"
                    append("GestureResultCallback.onCompleted：完成；capture=$id。手势完成不等于通话接通。")
                    observeAfter(s, before, callToken)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (operation != asyncGeneration || service !== s) return
                    busy = false
                    state = "手势回调取消；未自动重试"
                    append("GestureResultCallback.onCancelled：取消；capture=$id。")
                    observeAfter(s, before, callToken)
                }
            }, main)
        } catch (e: Exception) {
            busy = false
            state = "手势提交异常：${e.javaClass.simpleName}；未自动重试"
            append("$state：${e.message}")
            false
        }
        append("dispatchGesture：submitted=$submitted，x=%.1f，y=%.1f，display=%dx%d，rotation=%d；单次点击。".format(
            Locale.ROOT, x, y, geometry.width, geometry.height, geometry.rotation))
        if (!submitted) { busy = false; state = "系统未接收手势；未自动重试" }
        else {
            state = "手势已提交，等待回调；尚未确认接通"
            main.postDelayed({
                if (operation == asyncGeneration && busy) {
                    busy = false
                    asyncGeneration++ // A late callback must not alter a subsequent manual test.
                    state = "手势 5 秒内未收到回调；未自动重试"
                    append(state)
                }
            }, 5_000)
        }
    }

    private fun observeAfter(s: AccessibilityService, before: String, callToken: Long) {
        val observationGeneration = asyncGeneration
        for (delay in listOf(1_000L, 3_000L)) main.postDelayed({
            try {
                if (observationGeneration != asyncGeneration) return@postDelayed
                if (service !== s) { append("点击后 ${delay}ms：无障碍服务已断开。"); return@postDelayed }
                val page = readPage(s)
                if (page == null) append("点击后 ${delay}ms：窗口不可读取；接通状态未知。")
                else try {
                    append("点击后 ${delay}ms：当前包名=${page.pkg}，页面结构变化=${page.fingerprint != before}，" +
                        "原来电仍有效=${CallWatcher.wechatCallSessionToken() == callToken}。页面变化/通知消失不能单独证明接通。")
                    if (page.pkg == WECHAT) dumpPage(page, "点击后 ${delay}ms")
                    else append("非微信窗口仅记录包名与边界：${page.pkg} / ${page.bounds.toShortString()}；不采集其节点文本。")
                } finally { page.close() }
            } catch (e: Exception) { append("点击后观察失败：${e.javaClass.simpleName}；未追加输入。") }
        }, delay)
    }

    private fun directory(): File? = context?.let { File(it.noBackupFilesDir, "wechat-gesture-probe").apply { mkdirs() } }
    private fun reportFile() = directory()?.let { File(it, "report.txt") }
    private fun screenshotFile() = directory()?.let { File(it, "capture.png") }

    private fun append(value: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.CHINA).format(Date())
        if (report.isBlank()) report = "MotoNav $APP_VERSION 微信接听专项（仅本机手动触发，手势完成不代表接通）\n" +
            "设备 ${Build.MANUFACTURER} ${Build.MODEL}；Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "微信版本：${wechatVersion()}\n"
        report = (report + "\n[$time] $value\n").takeLast(MAX_REPORT)
        try { reportFile()?.writeText(report) }
        catch (_: Exception) { state = "本机报告保存失败；内存记录仍可复制" }
    }

    @Suppress("DEPRECATION")
    private fun wechatVersion(): String = try {
        context?.packageManager?.getPackageInfo(WECHAT, 0)?.let { "${it.versionName} (${it.versionCode})" } ?: "未知"
    } catch (_: Exception) { "不可读取" }
}
