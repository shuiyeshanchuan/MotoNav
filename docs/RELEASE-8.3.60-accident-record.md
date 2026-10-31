# MotoNav v8.3.60 测试版

## 本版内容

- 以用户当前圆屏稳定固件 `v7.12.9-nav.10-ride-info-imu-test` 为基线。
- 高级设置新增“事故监测（P1 只记录）”：用户明确开始后，后台服务接收现有 nav10 IMU 状态、健康摘要、IMU 样本，以及导航/巡航定位和速度。
- 记录采用有界内存环，定期保存到 App 私有目录的 `accident-record/latest.csv`，可在高级设置复制到剪贴板，用于停车或封闭场地回放。
- 记录会明确保留 GPS 未知、IMU 健康失败、样本无效和断流状态，不猜测位置或事故结论。

## 安全边界

- 本版 `canNotify=false`：不判定事故，不发送短信，不拨号，不联系紧急联系人。
- 不需要、也不要求用户把圆屏升级到 nav11；本版没有改变固件 BLE 协议。
- 低频 nav10 诊断流不能代表高采样率碰撞数据。请只在安全停车或封闭场地测试，不能把 CSV 或页面提示当作救援保证。

## 验证

- `android/tests/run.ps1`：纯 JVM 事故模型、IMU 解析、P1 记录会话和协调器测试全部通过。
- `MotoNav-Local.ps1 -Task Android -Offline`：Release APK 构建、签名和 `android/verify-release.ps1` 检查通过。

