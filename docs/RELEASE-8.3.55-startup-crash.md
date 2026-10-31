# MotoNav v8.3.55 测试版 · 启动闪退修复

## 本次修复

- Android `8.3.55`，`versionCode 157`。
- 修复 Release 增量 dex 缓存不完整导致 `BootBrand`、`AmapCaptureHost` 和 AndroidX Startup 资源类未进入 APK，App 在创建主界面前直接闪退的问题。
- 本机发布脚本现在对 Release 执行 `clean assembleRelease`，并在签名校验后检查 APK 的启动必需类定义，避免构建成功但安装后立即闪退的包进入 Release。
- 保留 `v8.3.54` 的四姿态 IMU 静置基线诊断；不启用事故判定、联系人、短信、拨号或自动通知。

## 验证

- API 30 模拟器卸载旧包后安装 `v8.3.55`，冷启动成功，进程保持运行。
- `android/tests/run.ps1` 全部通过。
- APK 包名、版本号、个人签名 SHA-1 和启动类完整性检查通过。

## 圆屏固件

继续使用 `v7.12.9-nav.9-ride-info-imu-test`。固件仍标记 `diagnostic=true`、`deviceTested=false`，仅用于停车诊断和安全场地观察。
