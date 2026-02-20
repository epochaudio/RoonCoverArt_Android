# CoverArt 网络与协议层改造行动文档

- 文档日期：2026-02-20
- 适用项目：`/Users/yiwen/work/CoverArtForAndroid`
- 对照来源：
  - 官方 `node-roon-api`：`/Users/yiwen/work/node-roon-api-master`
  - 参考实现 `roon-controller`：`/Users/yiwen/work/roon/roon-controller`

## 1. 改造目标

本次改造目标不是“继续堆发现策略”，而是把当前实现升级为“协议正确 + 状态机稳定 + 可测试 + 可演进”的网络核心。

核心范围：

1. 网络发现（SOOD）
2. 请求与响应路由（MOO over WebSocket）
3. 配对与注册语义（registry + pairing）
4. 订阅生命周期（zones/queue/settings）
5. token 持久化与迁移
6. zone 状态管理与选择

## 2. 现状问题（归纳）

1. `MainActivity` 承担过多协议职责，耦合 UI 与网络状态，难测且易回归。
2. SOOD codec 与官方格式存在偏差（value length 处理），影响发现可靠性。
3. MOO 路由主要依赖字符串分支，不是 Request-Id 驱动，重连后易出现订阅错配。
4. zone 增量事件处理不完整（added/removed/seek/state 语义不统一）。
5. 缺少官方 pairing service 语义，多 Core 场景行为不可预测。
6. 订阅生命周期缺少统一 registry，断连清理和重订阅策略分散。

## 3. 目标架构

### 3.1 分层

1. `transport` 层：纯连接与帧收发（WebSocket、ping/pong、断连信号）
2. `protocol` 层：SOOD/MOO 编解码（纯 Kotlin，可单测）
3. `session` 层：请求-响应路由、订阅注册表、连接状态机
4. `domain` 层：pairing 决策、zone reducer、queue reducer、重连策略
5. `app` 层：UI 只消费状态与事件，不直接拼协议包

### 3.2 状态机（单一来源）

`Disconnected -> Discovering -> Connecting -> Registering -> WaitingApproval -> Connected -> Reconnecting -> Failed`

要求：

1. 任一时刻只有一个活动会话
2. 断连时统一清理 pending requests/subscriptions
3. 重连后自动恢复必要订阅（zones、当前 zone queue、settings）

## 4. 分阶段改造计划

## Phase 0：基线与防回归（0.5 天）

目标：在动核心逻辑前先建立可观测基线。

任务：

1. 增加结构化日志字段：`connection_id`、`request_id`、`subscription_key`、`zone_id`
2. 记录关键指标：发现耗时、首次注册耗时、订阅恢复耗时、重连次数
3. 固定一套本地回归流程（单测 + 手测脚本）

产出：

1. `docs/network-refactor-baseline.md`
2. 关键日志规范注释（不改业务逻辑）

验收：

1. 可复现实测流程并采集指标
2. 不改变当前功能行为

## Phase 1：SOOD 协议正确性（1 天）

目标：对齐官方 `sood.js` 的关键协议细节，修复发现链路准确性。

任务：

1. 修正 SOOD property 编解码：
   - `key_len: 1 byte`
   - `value_len: 2 bytes big-endian`
2. 支持/保留 `_replyaddr` 与 `_replyport` 语义
3. query 报文加入 `_tid`，并保留 `query_service_id`
4. 发现策略统一为：
   - multicast
   - 各网卡 broadcast
   - unicast socket 兜底

代码范围：

1. `app/src/main/java/com/example/roonplayer/network/SoodProtocolCodec.kt`
2. `app/src/main/java/com/example/roonplayer/network/SoodDiscoveryClient.kt`
3. `app/src/main/java/com/example/roonplayer/MainActivity.kt`（仅发现调用点）

测试：

1. 新增 SOOD round-trip 单测（包含 2-byte length）
2. 新增 `_replyaddr/_replyport` 解析单测

验收：

1. 与官方格式兼容的 SOOD 报文可正确解析
2. 发现成功率提升且无误报增多

## Phase 2：MOO 路由与请求管理（1.5 天）

目标：把“按字符串分支”升级为“按 request-id/verb/endpoint 路由”。

任务：

1. 新建 `MooSession`（或同等命名）：
   - requestId 生成器
   - pending request map
   - response timeout 管理
2. 引入 `RequestRouter`：
   - `REQUEST`（Core -> Extension）
   - `CONTINUE/COMPLETE`（Core -> pending/subscriptions）
3. 严格化 MOO 解析规则：
   - 缺失 `Request-Id` 判无效
   - `Content-Length/Content-Type` 一致性校验
4. 统一错误行为：
   - 未知服务 -> `InvalidRequest`
   - 未知 Request-Id response -> 记录并断开（可配置）

代码范围（新增为主）：

1. `app/src/main/java/com/example/roonplayer/network/moo/MooSession.kt`（新增）
2. `app/src/main/java/com/example/roonplayer/network/moo/MooRouter.kt`（新增）
3. `app/src/main/java/com/example/roonplayer/network/MooProtocol.kt`（收敛为纯 parser）
4. `app/src/main/java/com/example/roonplayer/MainActivity.kt`（移除协议分支）

测试：

1. request/continue/complete 路由单测
2. unknown request-id、malformed header 单测

验收：

1. 所有请求都可关联响应
2. 并发请求无串包

## Phase 3：注册与配对语义（1 天）

目标：对齐官方注册流程，并补齐 pairing 能力。

任务：

1. 规范注册流程：
   - `registry/info` -> 解析 core/service names
   - `registry/register` -> 等待 `Registered`/待授权状态
2. 新增 pairing service（建议 `com.roonlabs.pairing:1`）：
   - `get_pairing`
   - `pair`
   - `subscribe_pairing` / `unsubscribe_pairing`
3. 持久化 `paired_core_id`，将“控制目标 core”显式化
4. 支持可选 one-time token 注册路径（后续扩展）

代码范围：

1. `app/src/main/java/com/example/roonplayer/api/PairedCoreRepository.kt`
2. `app/src/main/java/com/example/roonplayer/network/registration/*`（新增）
3. `app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt`（仅配合状态）

测试：

1. 注册状态迁移单测（首次授权/已授权/断连重连）
2. pairing service 行为单测

验收：

1. 多 Core 场景下仅控制 paired core
2. 授权与配对状态可恢复

## Phase 4：订阅注册表与生命周期（1 天）

目标：为 zones/queue/settings 建立统一订阅管理，防错配、防泄漏。

任务：

1. `SubscriptionRegistry`：
   - `requestId -> subscription metadata`
   - `subscription_key -> subscription metadata`
2. queue 订阅绑定到 zone：
   - 切 zone 自动取消旧订阅并订阅新 zone
3. 断连清理：
   - 清空 registry
   - 重连后按策略恢复订阅
4. 统一 `Unsubscribed` 处理

代码范围：

1. `app/src/main/java/com/example/roonplayer/network/subscription/*`（新增）
2. `app/src/main/java/com/example/roonplayer/MainActivity.kt`（删除局部订阅状态字段）

测试：

1. 重连恢复订阅单测
2. zone 切换 queue 串流回归单测

验收：

1. queue 更新不会污染非当前 zone
2. 无重复订阅风暴

## Phase 5：Zone/Queue 状态管理重构（1.5 天）

目标：对齐 transport 事件语义，改为 reducer 驱动状态更新。

任务：

1. 新建 `ZoneStateStore` + `ZoneReducer`
2. 完整处理事件：
   - `zones`
   - `zones_added`
   - `zones_removed`
   - `zones_changed`
   - `zones_state_changed`
   - `zones_now_playing_changed`
   - `zones_seek_changed`
3. queue 使用独立 `QueueStore`，仅接受当前 zone 订阅流
4. zone 选择策略保留当前用例，但输入改为 store 快照

代码范围：

1. `app/src/main/java/com/example/roonplayer/domain/ZoneSelectionUseCase.kt`（适配 store）
2. `app/src/main/java/com/example/roonplayer/state/zone/*`（新增）
3. `app/src/main/java/com/example/roonplayer/state/queue/*`（新增）

测试：

1. reducer 事件序列单测
2. seek-only 事件不污染 now-playing 回归单测

验收：

1. zone 列表与当前 zone 状态一致
2. 切歌、切 zone、暂停/播放 UI 行为稳定

## Phase 6：token 与配置持久化强化（0.5 天）

目标：保留现有迁移优势，补安全性与原子性。

任务：

1. token 存储改为加密（Android Keystore + EncryptedSharedPreferences）
2. 保存流程原子化：
   - `core_id/token/last_connected` 一次事务提交
3. 迁移逻辑幂等化，支持重复执行

代码范围：

1. `app/src/main/java/com/example/roonplayer/api/PairedCoreRepository.kt`
2. `app/src/main/java/com/example/roonplayer/api/SecurePrefsProvider.kt`（新增）

测试：

1. migration idempotent 单测
2. 旧数据升级兼容单测

验收：

1. 升级后 token 不丢失
2. 新增安全存储不影响自动重连

## Phase 7：MainActivity 瘦身与编排收敛（1 天）

目标：让 `MainActivity` 仅保留 UI 绑定和用户交互。

任务：

1. 新建 `RoonConnectionOrchestrator`（应用层）
2. `MainActivity` 删除协议拼包、消息分发、订阅管理逻辑
3. 通过 `StateFlow`/回调只消费：
   - connection state
   - zone snapshot
   - now playing snapshot
   - queue preview snapshot

验收：

1. `MainActivity` 代码显著缩减
2. 网络协议改动不再直接触发 UI 回归

## Phase 8：发布与回滚（0.5 天）

目标：可控上线。

任务：

1. 功能开关：
   - `new_sood_codec`
   - `new_moo_router`
   - `new_subscription_registry`
   - `new_zone_store`
2. 灰度顺序：
   - internal -> 小范围设备 -> 全量
3. 回滚策略：
   - 开关可退回旧链路
   - 保留旧 token/zone 读取兼容

验收：

1. 灰度期间无致命连接故障
2. 可一键降级核心网络链路

## 5. 文件级改造清单（建议）

建议新增目录：

1. `app/src/main/java/com/example/roonplayer/network/sood/`
2. `app/src/main/java/com/example/roonplayer/network/moo/`
3. `app/src/main/java/com/example/roonplayer/network/session/`
4. `app/src/main/java/com/example/roonplayer/network/subscription/`
5. `app/src/main/java/com/example/roonplayer/state/zone/`
6. `app/src/main/java/com/example/roonplayer/state/queue/`
7. `app/src/main/java/com/example/roonplayer/application/connection/`

建议收敛/迁移文件：

1. `app/src/main/java/com/example/roonplayer/MainActivity.kt`
2. `app/src/main/java/com/example/roonplayer/network/SoodProtocolCodec.kt`
3. `app/src/main/java/com/example/roonplayer/network/SoodDiscoveryClient.kt`
4. `app/src/main/java/com/example/roonplayer/network/MooProtocol.kt`
5. `app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt`
6. `app/src/main/java/com/example/roonplayer/api/PairedCoreRepository.kt`
7. `app/src/main/java/com/example/roonplayer/api/ZoneConfigRepository.kt`

## 6. 测试与验收矩阵

### 6.1 单元测试（必须）

1. SOOD 编解码 round-trip（含 2-byte value length）
2. MOO parser 严格性测试（header/body consistency）
3. requestId/subscription registry 测试
4. zone reducer 事件序列测试
5. token 迁移与加密存储测试

### 6.2 集成测试（建议）

1. Discovery -> Connect -> Register -> Subscribe -> Zone update 全链路
2. 授权前等待 + 授权后自动完成配对
3. 断网重连 + 订阅恢复 + 队列恢复

### 6.3 手动回归（必须）

1. 首次安装首次配对
2. 升级安装（保留旧数据）
3. 多 Core 环境配对切换
4. 切 zone/切歌/暂停播放/下一曲
5. 异常网络（短断网、弱网）

## 7. 里程碑与工期建议

1. M1（2 天）：Phase 0-2 完成，协议正确性与路由落地
2. M2（2 天）：Phase 3-5 完成，配对/订阅/zone 状态稳定
3. M3（1 天）：Phase 6-8 完成，安全存储 + 灰度发布

总计建议：5 个工作日（含测试与灰度）

## 8. 风险与应对

1. 风险：协议收紧导致兼容老 Core 行为变化  
   应对：严格模式可开关，默认逐步灰度
2. 风险：MainActivity 拆分过程引入 UI 回归  
   应对：先引入新编排层，再逐段迁移旧逻辑
3. 风险：token 加密迁移失败导致重配对  
   应对：保留旧键回退读取，迁移失败不删除原值

## 9. 本周可立即执行的任务（建议）

1. 完成 Phase 0（日志与基线）
2. 完成 Phase 1（SOOD 修正 + 单测）
3. 完成 Phase 2 第一部分（MOO parser 严格化 + request map）
4. 提交一次小范围内部验证包

---

该文档用于执行，不是设计草案。后续每个阶段完成后，在文档末尾追加：

1. 变更摘要
2. 实测结果
3. 风险复盘
4. 是否进入下一阶段

## 10. 执行进展（2026-02-20）

### 10.1 已落地改造

1. `Phase 0`：新增基线行动文档 `docs/network-refactor-baseline.md`，补结构化日志字段约定与回归清单。
2. `Phase 1`：SOOD codec 升级为官方兼容（`value_len=2-byte BE`），加入 `_tid/_replyaddr/_replyport`；发现客户端补网卡 broadcast 目标采集。
3. `Phase 2`：MOO parser 严格化（header/body 一致性、Request-Id 必填校验、非 JSON 内容类型分流）；新增 `MooSession/MooRouter`（request-id pending/timeout/未知 request-id 策略）。
4. `Phase 3`：新增 `network/registration` 状态机与 pairing 请求构造模块；`PairedCoreRepository` 增加 `paired_core_id` 语义。
5. `Phase 4`：新增 `SubscriptionRegistry`，接入 queue/zones 订阅发送与生命周期日志。
6. `Phase 5`：新增 `ZoneReducer/ZoneStateStore/QueueStore`，并在 `MainActivity` 通过 `new_zone_store` 开关进行接入。
7. `Phase 6`：新增 `SecurePrefsProvider`（优先 EncryptedSharedPreferences，失败自动回退）；`PairedCoreRepository` 改为幂等迁移 + 原子提交。
8. `Phase 7`：新增 `RoonConnectionOrchestrator` 并接入关键连接状态迁移。
9. `Phase 8`：新增 runtime feature flags：
   - `feature_flags.new_sood_codec`
   - `feature_flags.new_moo_router`
   - `feature_flags.new_subscription_registry`
   - `feature_flags.new_zone_store`
   - `feature_flags.strict_moo_unknown_request_id_disconnect`

### 10.2 风险控制策略

1. 高风险逻辑默认由 feature flag 关闭（MOO router/subscription registry/zone store）。
2. SOOD 协议升级默认开启，但保留 legacy 解析兼容路径。
3. Token 安全迁移失败自动回退到普通偏好存储，不中断主功能。

### 10.3 待验收项

1. 全量单测通过。
2. 手测回归：封面显示、封面墙、切歌、切 zone、重连恢复。
3. 小规模灰度后再开启 `new_moo_router/new_subscription_registry/new_zone_store`。
