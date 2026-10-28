package com.example.motonav

/**
 * Small UI-facing snapshots.  The transport and navigation singletons remain
 * the owners of their data; screens consume these stable, presentation-ready
 * values instead of each reimplementing connection and navigation rules.
 */
data class DeviceUiState(
    val connected: Boolean,
    val verified: Boolean,
    val searching: Boolean,
    val title: String,
    val detail: String,
    val batteryPercent: Int,
    val batteryCharging: Boolean,
    val needsSystemUnpair: Boolean,
    val board: String,
    val firmware: String
)

data class NavigationUiState(
    val navigating: Boolean,
    val cruising: Boolean,
    val title: String,
    val detail: String
)

data class OtaUiState(
    val busy: Boolean = false,
    val message: String = "连接圆屏后可检查更新",
    val availableVersion: String? = null
)

object AppStatus {
    @Volatile private var otaState = OtaUiState()

    fun device(): DeviceUiState {
        val connected = BleLink.isConnected
        val verified = BleLink.verified
        val searching = BleLink.status.contains("搜索") ||
            BleLink.status.contains("连接…") || BleLink.status.contains("正在")
        val title = when {
            connected && verified -> "${BleLink.screenName.ifBlank { "圆屏" }} 已连接"
            connected -> "正在确认圆屏配对…"
            searching -> BleLink.status
            else -> "圆屏未连接"
        }
        val detail = when {
            BleLink.pairHint.isNotEmpty() && !verified -> BleLink.pairHint
            connected && verified -> "已配对验证"
            connected -> "正在验证配对…"
            searching -> "请确认圆屏已通电并在手机附近"
            BleLink.status == title -> "圆屏通电后会自动连接"
            else -> BleLink.status
        }
        return DeviceUiState(
            connected = connected,
            verified = verified,
            searching = searching,
            title = title,
            detail = detail,
            batteryPercent = BleLink.screenBatteryPct,
            batteryCharging = BleLink.screenBatteryCharging,
            needsSystemUnpair = BleLink.needUnbond,
            board = BleLink.screenBoard,
            firmware = BleLink.screenFirmware
        )
    }

    fun navigation(): NavigationUiState {
        val navigating = NaviBridge.lastInfo.startsWith("图标")
        val cruising = NaviBridge.cruising
        return NavigationUiState(
            navigating = navigating,
            cruising = cruising,
            title = if (navigating) "导航中" else if (cruising) "巡航中" else "未开始导航",
            detail = when {
                navigating -> NaviBridge.extraInfo.lineSequence().lastOrNull()?.removePrefix("底部：").orEmpty()
                cruising -> "提醒测速、电子眼和危险路段"
                else -> "规划路线后，导航提示会同步到圆屏"
            }
        )
    }

    fun ota(): OtaUiState = otaState

    fun updateOta(busy: Boolean, message: String, availableVersion: String?) {
        otaState = OtaUiState(busy, message, availableVersion)
    }
}
