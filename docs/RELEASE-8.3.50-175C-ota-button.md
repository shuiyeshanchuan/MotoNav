# MotoNav v8.3.50 测试版 · 圆屏 OTA 按钮修复

## 本次修复

- Android `8.3.50`，`versionCode 152`。
- 修复检查到圆屏固件更新后，“更新至 …”按钮继续沿用检查过程中的禁用状态，显示灰色且无法点击的问题。
- 发现可用固件时会恢复更新按钮的可点击状态和正常透明度；下载、校验和 BLE OTA 流程不变。

## 手机更新

在 MotoNav 中打开“设置 → 关于 MotoNav → 检查 App 更新”，安装 `v8.3.50`。也可以从本 Release 下载 `app-release.apk` 后按 Android 系统提示安装。

## 圆屏更新

安装 App 后连接并完成圆屏配对，打开“设置 → 小圆屏 → 固件升级”。检查到 `v7.12.9-nav.8-imu-test` 时，“更新至 …”按钮应为可点击状态。升级前请停车、接入稳定电源并保持手机靠近圆屏。

本次 App 修复不改变圆屏固件、OTA 签名校验或事故安全诊断边界。`v7.12.9-nav.8-imu-test` 仍是 `diagnostic=true`、`deviceTested=false` 的测试固件，不启动事故判定或紧急联系人通知。

## 校验

- Android APK：发布前使用 `android/verify-release.ps1` 检查包名、版本和个人签名。
- APK 应覆盖安装在已有个人签名的 MotoNav 上；若系统提示签名不一致，请不要继续安装。
