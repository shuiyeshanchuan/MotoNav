# MotoNav v8.3.56 测试版 · 圆屏 OTA 检查大小限制修复

## 本次修复

- Android `8.3.56`，`versionCode 158`。
- 修复“检查圆屏固件更新失败：下载文件超过允许大小”。原因为 GitHub 最近 30 个 Release 的 API 列表误用了单个 OTA manifest 的 256 KB 上限；当前列表约 349 KB，尚未开始下载固件就被错误拦截。
- GitHub Release 列表现在使用独立的 2 MB 上限；单个 OTA manifest 仍限制为 256 KB，应用镜像仍限制为 5 MB。
- 圆屏固件 `v7.12.9-nav.9-ride-info-imu-test`、OTA RSA 签名、SHA-256 和 BLE 协议均不变。

## 验证

- 实测 GitHub `/releases?per_page=30` 响应约 349 KB，可在新的 2 MB 上限内正常解析。
- OTA manifest 为 1,267 字节，应用镜像为 1,376,048 字节，均在各自限制内。
- `android/tests/run.ps1`、APK 签名和启动类完整性检查继续通过。

## 安全边界

继续停车、稳定供电并保持手机靠近圆屏后再升级。固件仍标记 `diagnostic=true`、`deviceTested=false`，仅用于停车诊断和安全场地观察。
