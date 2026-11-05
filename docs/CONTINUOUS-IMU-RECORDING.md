# 导航期间连续 IMU 本地记录（第一阶段）

本轮仅增加原始数据采集、传输和保存，不改变事故模型、传感器零偏、安装参考或联系人通知。用户最终指定的触发条件优先：**开始导航才开始记录，结束导航停止采样并排空缓存**。打开 App、圆屏上电、规划路线或开始巡航都不启动记录。模拟导航可记录，但元数据明确标记 simulation，不能与真实骑行混用。

## 现有工程与最小修改

1. 1.75C 使用 `firmware/boards/175/MotoNav`。SensorLib 的 `ImuDrv.hpp` → QMI8658 驱动已有六轴读取；诊断配置传入 112.0 Hz，映射到硬件约 112.1 Hz。保留现有量程、ODR、滤波、自检、主循环 I²C 所有权与安装校准。没有外接存储，不能假设存在 TF 卡。
2. `ImuDiagnostics.h` 每次成功读取后向独立记录器提供真实新样本。新模块每 40 ms 调度接纳一条，目标 25 Hz；不重复旧快照凑采样率。地图刷新、主循环和同步切页动画仍可能延迟采样，记录时间与 missedSlots 揭示这一限制。这不是硬件 FIFO 全量采集，也不保证保留冲击峰值。
3. 新增有限缓冲与批协议；`CommandReceiver.h` 在更新导航活跃时间之前分流二进制控制，采集 ACK 不延长旧导航数据的有效期。新协议复用配对后的普通 BLE RX/TX，不使用 OTA 特征。
4. Android `RideRecorder.start/stop` 只增加生命周期转发。GPS 轨迹关闭或暂未定位仍可记录 IMU；原生 SDK、网页摩托车、分享路线使用现有开始/结束入口，重算不另开会话。旧 `AccidentRecordMonitor` 和四姿态采集保持独立。
5. `ContinuousImuRecorder.kt` 使用独立单线程和 64 项有界工作队列，`ContinuousImuProtocol.java` 负责有限分包解析，`ContinuousImuStore.java` 负责流式持久化。`BleLink.kt` 在 UTF-8 解析前分流新通知，不替换旧单 IMU listener。能力帧 `CAP,IMUR,1` 确认支持后才发送控制，旧固件不被当作支持。

## 采集与板端缓存

- 每样本 32 字节：32 位板端读取时间（ms）、三轴加速度（m/s²）、三轴角速度（°/s）、温度（°C），均为未做新零偏补偿的原始工程单位。
- PSRAM 分配固定 4096 条，共 131072 字节；按目标 25 Hz 约 163.84 秒。这是已经成功采到的数据的缓存，不会补回显示阻塞期间未读取的历史值。
- 手机持久化确认前保留；满时覆盖最旧未确认样本，增加 overflow。分配失败、传感器不可用明确报告，不无限申请 RAM。
- 每次启动使用新的非零 boot ID，每次导航使用新的非零 32 位 session ID。序号是本段实际记录数，不用“理论 25 Hz × 时长”冒充实际数量。
- 主循环错过的 40 ms 槽位、读错、缓存覆盖与手机收到的序号缺口分别统计，不能简单相加为单一丢包数。

## BLE 协议 v1

全部多字节整数和 IEEE754 float 为小端。控制包 18 字节，默认 MTU 23 也可承载：

| 偏移 | 内容 |
|---|---|
| 0..3 | `D7 43 01 opcode` |
| 4 | session uint32 |
| 8 | boot uint32 |
| 12 | argument uint32 |
| 16 | 前 16 字节的 CRC16-CCITT，初值 FFFF |

opcode：1 START/RESUME、2 STOP、3 ACK、4 QUERY。相同 session START 幂等，已停止会话不能被定时 START 再启；只有 boot 匹配时 START 的 argument 才可作为 durable-next 确认。STOP 需匹配 session，boot 可为 0 或实际 boot；ACK 必须同时匹配 session/boot，argument 为已持久化的下一序号。QUERY session 0 查询当前会话。控制不进入导航状态重放缓存。

一批最多 8 样本。通知分片头为 8 字节：`D7 52 01 frameId partIndex partCount totalLengthLE16`。片长按连接实际 MTU−3 限制，头后 payload 最大 MTU−11。完整 body 最多 308 字节；手机组装空间有限，坏序号、尺寸、超时或 CRC 拒绝，等板端重传。低 MTU 的吞吐可能不足，须查看缓存覆盖和有效速率，不能因支持分包就保证所有手机均可无损传输。

| body 偏移 | uint32，另注明除外 |
|---|---|
| 0 / 4 | session / boot |
| 8 / 12 / 16 | firstSeq / nextSeq（总采样数）/ oldestSeq |
| 20 / 24 / 28 | overflow / missedSlots / readErrors |
| 32 / 36 / 40 | boardNow / startMillis / stopMillis |
| 44 / 46 | flags uint16 / count uint16 |
| 48 起 | count × 32 字节样本 |
| 末 4 字节 | 前面完整 body 的 CRC32 IEEE |

flags 位：1 采集中、2 已停止、4 IMU 不可用、8 缓冲分配失败。count 可为 0，用于状态和最终结束计数。板端单个在途批次，限速分片、超时重发；手机文件同步后 ACK，重复样本去重。停止后继续发尾部，最终以已停止状态和实际 nextSeq 核对。180 秒排空超时、切入新导航或服务关闭均按未知尾部标记，不能显示为完整保存。

## 手机本地文件与中断

目录为 App 私有、排除系统备份的 `noBackupFilesDir/imu_records/yyyy-MM-dd/会话目录/`。CSV 持续追加，元数据保留会话开始/结束、真实/模拟标记、原始板端时间、手机接收墙钟/单调时钟锚点、boot 段、实际数量、缺口、异常与终止原因。板端时间是软件读取时刻，手机接收时间包含缓存和传输延迟，均不是同步过的硬件 UTC 测量时间。

总记录预算 512 MiB，手机剩余空间不足 64 MiB 时停止并报告；不静默删除历史。RAM 只有有限批次和文件缓冲，不把一小时或全部历史加载入内存。行驶信息页显示保存状态并可导出最近会话 ZIP，ZIP 也流式生成；清除 App 数据或卸载会删除私有记录，应先导出。

| 情况 | 行为与限制 |
|---|---|
| 息屏、正常后台 | 复用现有 location/connectedDevice 前台服务与 PARTIAL_WAKE_LOCK；仍须实机验证厂商省电策略 |
| BLE 短断线 | 同一导航会话重连、确认 durable-next、重发环形缓存；缓存范围内可补传 |
| 长断线 | 已采数据只保留固定环；超出容量的最旧数据丢失且可见 |
| 圆屏断电/重启 | RAM 缓存丢失；新 boot 另开段，旧段未确认尾部未知 |
| App 进程被杀、强停、手机重启 | 不能保证 onDestroy 或自动重建导航。下次启动修复尾部、标记旧文件 interrupted；不擅自重新开始导航。重新连接发现孤儿采样会话时停止它 |
| 服务关闭、写盘失败、空间不足 | 保留已持久化记录、停止确认、尽力发 STOP；未确认尾部明确未知 |

Android 系统限制依据：[BLE 后台通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background)、[后台启动前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。START_STICKY 不代表任意厂商/强停/重启情形均能自动恢复。

## 一小时验收方法

自动测试与硬件验收分别记录，不互相代替：

1. 自动构造 90000 条、25 Hz、跨 1 小时的数据，经过实际分片解析和流式 store。每批最多 8 条；核对 CSV 数量、真实时间戳、末尾 durable-next、元数据、CRC/重复/缺片/错误会话/溢出、尾行截断恢复和有限内存。测试运行耗时可短于一小时，仅代表一小时数据量。
2. 固件 native 测试覆盖环容量、ACK 不越界、不匹配 session/boot 不释放、覆盖后缺口、重复控制、停止、分包和时间回绕。
3. 实机开始一次导航并保持一小时；可在停车场静置或安全封闭环境进行，不需要制造事故。先记手机/圆屏版本，确认记录条数持续增长。保持正常地图、音乐和横滑功能工作。
4. 10–25 分钟息屏，25–35 分钟切后台；40 分钟时断开 BLE 30 秒后恢复，观察补传。另开一次测试断开超过 164 秒，确认缺口被如实报告，不能要求它无损。
5. 正常结束导航，等待保存完成后导出 ZIP。核对每个 boot 段序号、实际 board 时间跨度、实际采样率、最长间隔、sample 数量、板端 nextSeq、overflow、missedSlots、readErrors 与文件 gap，不能只看“90000”一个数字。
6. 分开测试手机杀进程/重启、圆屏重启、存储失败；应保留前面文件并标记 interrupted/未知尾部，不应伪装为正常完整结束。确认未启动事故判定、短信、拨号和联系人通知。

实现与测试结果见对应测试版发布说明；硬件一小时未执行前，不得写为已通过。
