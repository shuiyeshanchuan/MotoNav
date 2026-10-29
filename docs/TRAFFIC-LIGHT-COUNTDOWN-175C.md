# 1.75C 红绿灯读秒接入方案（2026-10-29）

## 现状与目标

正式 Android App 使用高德普通导航 SDK `10.1.600`，从路线中取得红绿灯位置、数量和距离；公开的导航监听器没有灯色与剩余秒数回调。固件已有 `#TLA` 路口有效期、`#TL` / `#TLS` 相位数据的接收和失效清除能力。目标是在真实导航中让**同一路口、同一行驶方向**的权威灯态和剩余秒数经手机 BLE 显示到圆屏；没有可靠数据时显示“无数据”。

旧独立实验版依赖特定高德车机补丁 APK，当前没有该输入包，也不能作为正式 App 的现成数据源。仅升级固件或普通导航 SDK 的内置气泡显示开关，不会生成可转发的秒数。

## 优先接入路径：高德 AIoT Link

高德 [AmapLinkClient 文档](https://lbs.amap.com/api/aiot-sdk/guide/strong-ties/amaplinkclient-api)说明第三方 Android App 可与高德地图 App 建联，通过 `onDataReceived(JSONObject data, int transportType)` 接收数据；导航回调外层的 `datas` 是 JSON 数组字符串，每项含 `type` 与 `data`。高德 [PB 类型说明](https://lbs.amap.com/api/aiot-sdk/faq-old)定义 `type=12` 为实时红绿灯倒计时、`type=13` 为隐藏。这为结构化数据提供了明确的官方调查方向。

公开文档**没有给出 `type=12` 内层 `data` 的字段定义**，也未保证该事件在摩托车路线、MotoNav 自有 SDK 导航或每个路口都产生。Link 连接的是高德地图 App；若事件只随高德地图 App 自己的导航输出，须以该路线为圆屏导航依据，不能把另一条 MotoNav 路线的路口和它直接拼接。高德 [RTOS 屏显组件](https://lbs.amap.com/api/aiot-sdk/guide/screen-display/ptos-user-manual)虽有 `show_traffic_signal` 回调，但导航数据需先由宿主以 protobuf 输入，组件本身不是独立的实时灯源。

### 当前缺口

1. 高德 [智能硬件 Key](https://lbs.amap.com/api/aiot-sdk/guide/create/get-key)及 Link 授权审核。Link 文档要求 Key 注册并通过审核，错误码列有 `API_KEY_INVALID(204)`、`PERMISSION_DENIED(205)`；普通地图 Key 不等于读秒权限。
2. 可供项目集成的 Android Link SDK 产物、版本兼容说明及 `type=12` 数据字段。高德 [SDK 指南](https://lbs.amap.com/api/aiot-sdk/skills)要求联系高德取得对应 SDK 产物。本机项目与 Gradle 缓存只找到普通导航 SDK，未找到 Link / Agent SDK 包。
3. 官方确认摩托车／两轮车场景的授权、覆盖范围、道路方向、路口标识、数据时间与有效期。高德 [两轮车 SDK 更新日志](https://lbs.amap.com/api/twowheeler-navi-sdk/changelog)表明产品有倒计时能力，但没有证明现有 MotoNav SDK / Key 可取得结构化秒数。
4. 真机验证。当前仅有 Android 模拟器，未安装高德地图 App；无法证明建联、事件触发或实路覆盖。

## 获得授权后的实现与验收

1. 在独立适配层接入 Link，记录连接结果、错误码和**脱敏**事件类型；先在高德地图 App 的真实导航中证实 `type=12/13` 到达。不要把 Key、原始位置或联系人写入日志。
2. 依据高德确认的字段解析灯色、剩余时长、路口 ID、行驶方向、数据时间与有效期。拒绝未知状态、缺字段、时间倒退、路口或方向不匹配的数据；重算、断联及 `type=13` 时立即清除。
3. 只有同一导航来源的路线与灯数据完成匹配后，才发送现有 `#TLA` 和 `#TL` / `#TLS`；在 BLE 实际写入时重算剩余时间和有效期，不在手机队列中排队过期秒数。
4. 在封闭道路或停车场、模拟回放及真实允许通行的路口分别验证事件时效、方向匹配、断联消失和手机／圆屏一致性。模拟样本只能证明解析与显示，不能证明实路覆盖或信号正确。

### 向高德确认的问题（工单草稿）

MotoNav 是 Android 手机 App + 1.75C BLE 圆屏的摩托车导航项目。请确认：① AIoT Link `AMapNaviDataTypeShowTrafficSignal(12)` 是否对摩托车导航及圆屏外设开放，能否与现有普通导航 SDK 同时使用；② 如何申请和审核智能硬件 Key、Link SDK 及必要许可；③ 提供 `type=12/13` 的完整字段定义、样例、时间戳/有效期与路口/方向标识；④ 若事件仅来自高德地图 App 导航，是否允许以该导航作为圆屏主数据源，以及后台/锁屏时的输出条件；⑤ 读秒覆盖范围、测试环境与使用限制。拿到上述确认和真机事件前，不能承诺正式读秒已实现。
