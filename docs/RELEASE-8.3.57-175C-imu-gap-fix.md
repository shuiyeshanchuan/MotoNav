# MotoNav v8.3.57 测试版 · 1.75C IMU 静置基线与 OTA 顺序修复

## 本次发布

- 圆屏测试固件：`v7.12.9-nav.10-ride-info-imu-test`。
- 修复 `nav.9` 四姿态静置采集全部显示“IMU 健康摘要过期”的问题。设备实测主循环间隔为 14–47 ms，而旧版 30 ms 诊断阈值把正常调度抖动记成 `sampleGaps`。
- 将静置诊断的缺口阈值调整为 75 ms。超过 75 ms 的停顿仍会记录为缺口；该阈值只用于低频静置诊断，不代表全速冲击采集能力。
- Android App：`v8.3.57`，`versionCode 159`。修复 OTA 版本比较对 `nav.10` 的字典序误判，确保从 `nav.9` 能显示并安装本次圆屏更新。圆屏固件和 App 一起放在本 Release，手机无需手动切换 Release。

## OTA 文件

- `motonav-ota-stable.json`：App 检查入口使用的索引。
- `*_imu-diagnostic_app_0x10000.bin`：通过 MotoNav BLE OTA 更新。
- `*_imu-diagnostic_full_0x0.bin`：USB 救援镜像，不由 App OTA 下载。
- `*_imu-diagnostic_manifest.json`：包含 SHA-256 和 RSA-2048 签名。

## 验证

- Arduino CLI 1.75C、32 MB Flash、`app5M_fat24M_32MB` 诊断构建通过。
- OTA manifest RSA-SHA256 签名、公钥 ID、应用镜像 SHA-256、应用偏移和大小校验通过。
- Android IMU 协议/静置基线回放测试：22 个场景全部通过（8 个样本解析、14 个基线门控）。14–47 ms 间隔不会记为缺口，76 ms 才会触发 75 ms 阈值。
- 编译后的 `FirmwareUpdateClient` 版本顺序回归通过：`nav.10 > nav.9`、`power.15 > power.14`，旧版本不会被接受为更新。
- `diagnostic=true`、`deviceTested=false` 保持不变。

## 重新采集步骤

1. 在 MotoNav 中保持 App `v8.3.56`，打开“设置 → 高级设置 → 诊断”。
2. 在“小圆屏 → 固件升级”检查并安装 `v7.12.9-nav.10-ride-info-imu-test`，停车并保持稳定供电。
3. 停止导航后，分别完成屏幕朝上、朝下、朝左、朝右四次静置采集。
4. 把新的四段结果发回；在四姿态有效、链路连续且完成人工复核前，不启用事故判定、联系人、短信、拨号或自动通知。

> 诊断固件只用于停车和封闭场地观察。它不提供事故检测保证，也不替代交通信号、人工报警或专业安全设备。
