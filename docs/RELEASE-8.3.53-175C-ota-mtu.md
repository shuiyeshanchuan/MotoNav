# MotoNav v8.3.53 测试版 · 圆屏 OTA MTU 修复

## 本次内容

- Android `8.3.53`，`versionCode 155`。
- 修复部分手机在 BLE 配对完成前只协商到 23 字节 MTU，导致圆屏 OTA 的 RSA 签名 BEGIN 包无法发送。
- 认证完成后限次重新请求 MTU 517；手机端控制包上限与圆屏固件 `RXQ_MAX=512` 对齐。
- 圆屏固件继续使用 `v7.12.9-nav.9-ride-info-imu-test`，没有改变 OTA 签名、分区或信任根。

## 使用和限制

请在停车、稳定供电、手机和圆屏靠近的条件下更新。若手机最终只能协商到 23、185 或 247 字节，旧固件的完整签名 BEGIN 包无法拆分，App 会提示当前/所需包大小；先断开系统蓝牙占用后重连，再重新点击更新。仍无法协商大 MTU 时使用既有 USB 救援镜像。

本 Release 仍是 `diagnostic=true`、`deviceTested=false` 的测试版；行驶信息和 IMU 仅用于诊断显示，不启用事故判定或紧急联系人通知。
