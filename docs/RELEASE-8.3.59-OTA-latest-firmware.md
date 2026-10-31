# MotoNav v8.3.59 测试版 · OTA 最新固件选择修复

本版修复手机 OTA 检查偶尔继续显示 `v7.12.9-nav.10-ride-info-imu-test` 的问题。GitHub Releases API 不保证新建 Release 排在响应第一条，旧 App 只取第一条可用 OTA 索引，因此可能忽略已经发布的 nav.11。现在 App 遍历可用 Release，并按固件通道与数字版本选择最新候选。

## 版本

- Android App：`v8.3.59`，`versionCode 161`
- 1.75C 圆屏：`v7.12.9-nav.11-ride-info-ui-fix`
- Release：`motonav-v8.3.59`
- 固件 manifest：`diagnostic=true`、`deviceTested=false`

## 更新方法

1. 先把手机 App 更新到 `v8.3.59`。
2. 连接圆屏，在“小圆屏 → 固件更新”重新检查。
3. 应显示 `v7.12.9-nav.11-ride-info-ui-fix`，确认后在停车状态执行 OTA。

本版沿用上一版四页手势顺序、底部圆点和中文行驶信息修复。诊断固件仍未开启事故判定、联系人通知、短信或自动拨号。

## 校验

- App release APK 已使用个人发布证书签名并通过启动类完整性检查。
- OTA 应用镜像、完整 USB 救援镜像和 manifest 沿用 nav.11 的 SHA-256 与 RSA-2048 签名。
