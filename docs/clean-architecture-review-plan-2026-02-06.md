# CoverArtForAndroid 代码审查与重构计划（Clean Architecture）

## 1. 文档目标
本计划用于把当前审查结论落地为可执行任务，原则如下：

- 不做“补丁堆叠”，先修高风险，再重构边界。
- 禁止新增魔法数字和硬编码。
- 每个阶段都要有可验证的验收标准。

## 2. 审查范围与基线

- 审查范围：
  - `app/src/main/java/com/example/roonplayer/MainActivity.kt`
  - `app/src/main/java/com/example/roonplayer/network/*`
  - `app/src/main/java/com/example/roonplayer/api/*`
  - `app/src/main/java/com/example/roonplayer/domain/*`
  - `app/src/main/AndroidManifest.xml`
- 基线验证：
  - 已执行：`./gradlew :app:testDebugUnitTest`
  - 结果：通过（BUILD SUCCESSFUL）

## 3. 关键问题清单（按优先级）

## `P0` 必须先修（会导致运行期故障）

1. WebSocket 控制帧处理缺陷（`ping/pong`）
   - 位置：
     - `app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt:394`
     - `app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt:428`
     - `app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt:491`
   - 风险：连接可能被服务端判定异常并断开；帧解析可能错位。

## `P1` 高优先（会引入隐性不稳定）

1. `requestId` 并发自增非线程安全
   - 位置：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:376`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:3515`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4082`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4929`
   - 风险：请求与响应错配。

2. 大量 `GlobalScope`/临时 `CoroutineScope` 脱离生命周期
   - 位置：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:532`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:2366`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4110`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4949`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4781`
   - 风险：Activity 销毁后后台任务继续执行，导致重复重连、状态覆盖、资源泄漏。

3. 发现结果 Key 拼接错误（端口常量被字面量化）
   - 位置：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:2567`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:2570`
   - 风险：缓存与后续匹配异常，排障成本高。

4. 架构职责失衡（`MainActivity` 过载）
   - 位置：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:61`
   - 事实：该文件约 5336 行，承担 UI/网络/协议/发现/持久化/重连等多职责。
   - 风险：变更耦合极高，回归风险不可控。

## `P2` 中优先（长期维护风险）

1. 魔法数字与硬编码分散
   - 位置示例：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:2540`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:2963`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:3073`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:3088`
   - 风险：策略不可配置、行为不可预期、迁移困难。

2. Domain 层依赖 `JSONObject`
   - 位置：
     - `app/src/main/java/com/example/roonplayer/domain/ZoneSelectionUseCase.kt:3`
     - `app/src/main/java/com/example/roonplayer/domain/ZoneSelectionUseCase.kt:15`
   - 风险：核心业务依赖传输模型，违反 Clean Architecture。

3. 测试覆盖不完整（网络协议与状态机缺失）
   - 现状：仅有 `domain/api` 单测，缺少网络与编排层回归用例。

## 4. 重构决策
结论：不建议继续在现有 `MainActivity` 上做局部补丁。应采用“先止血、再分层、后清理”的渐进重构策略。

## 5. 分阶段执行计划

## 阶段 A：止血（先保证稳定性）
目标：消除协议和并发高风险，避免线上随机故障。

任务：
1. 修复 WebSocket 控制帧处理（正确消费 `ping/pong` payload，必要时回 `pong`）。
2. 将 `requestId` 改为线程安全生成器（如 `AtomicInteger`）。
3. 将 `GlobalScope` 和匿名 `CoroutineScope` 收敛到生命周期作用域。
4. 修复发现结果 key 拼接错误，统一 host:port 标准化。

产出：
- 小步提交，单个提交仅解决一类风险。

验收标准：
1. 不出现重复/错乱 `Request-Id`。
2. 网络抖动下连接可持续，控制帧不破坏后续解析。
3. Activity 销毁后不再有残留网络任务。

## 阶段 B：分层收口（Clean Architecture）
目标：把“UI、编排、协议、数据访问”拆开，降低耦合。

任务：
1. 引入应用编排层（连接状态机、重连策略、发现流程）。
2. `MainActivity` 仅保留 View 渲染与用户事件分发。
3. Domain 使用纯业务模型（禁止 `JSONObject` 进入 Domain）。
4. Data 层负责 DTO/JSON 映射与持久化实现。

产出：
- 新增清晰目录结构（如 `presentation/`, `domain/`, `data/`, `network/`）。
- 关键流程从 Activity 下沉为可测试用例与服务。

验收标准：
1. `MainActivity` 代码规模显著下降，职责单一。
2. Domain 层不依赖 Android/JSON 具体实现。
3. 连接与发现流程可在 JVM 单测中验证。

## 阶段 C：配置治理（消除魔法数字和硬编码）
目标：把策略参数集中管理，支持环境与策略切换。

任务：
1. 建立统一配置对象（端口、超时、重试、扫描策略、缓存上限）。
2. 移除散落的 IP 段与端口硬编码，改为策略配置。
3. 区分“默认值”与“运行时可覆盖配置”。

产出：
- `ConnectionConfig`、`DiscoveryConfig`、`UiTimingConfig` 等配置模型。

验收标准：
1. 网络参数不再散落于业务代码。
2. 任意策略调整无需改动核心流程代码。

## 阶段 D：测试与回归保障
目标：补齐关键路径自动化回归。

任务：
1. 补齐 WebSocket 帧解析与控制帧处理测试。
2. 补齐连接状态机与自动重连测试。
3. 补齐 Zone 选择/回退/配置迁移端到端行为测试（至少服务级）。

产出：
- 新增 network/orchestrator 用例。

验收标准：
1. 高风险路径有自动化覆盖。
2. 重构后行为与现有预期一致。

## 6. 里程碑与交付顺序

1. 里程碑 M1（阶段 A 完成）：系统先稳定，再谈优化。
2. 里程碑 M2（阶段 B 完成）：架构边界明确，可持续演进。
3. 里程碑 M3（阶段 C+D 完成）：参数可治理，回归可验证。

## 7. 变更控制规则（执行期间强制）

1. 每个 PR 只做一类变更，避免“修复+重构+功能”混杂。
2. 禁止新增硬编码端口、IP、超时值；统一走配置中心。
3. 新增并发逻辑必须说明生命周期归属与取消机制。
4. 没有测试的重构不允许合并到主线。

## 8. 待确认决策

1. 是否保留跨网段“激进扫描”策略，或改为“仅局域网段 + 已知连接优先”。
2. 开机场景是否仍要求直接拉起 `Activity`，还是迁移为前台服务模式。
3. 是否需要把 Settings/Token 迁移逻辑下沉到独立仓储模块并提供版本化迁移。

## 9. 下一步（建议）

1. 先执行阶段 A，单独提交 4 个小改动（协议、并发、协程生命周期、key 修复）。
2. 阶段 A 通过后再进入阶段 B 的结构拆分。
3. 每完成一个阶段，更新本文件的“验收结果”与风险状态。

## 10. 执行进展（2026-02-06）

### 阶段 A（已完成）
- 已完成四项止血改动：
  - WebSocket `ping/pong` 控制帧修复（正确消费 payload 并回 `pong`）。
  - `requestId` 改为 `AtomicInteger` 生成，避免并发错配。
  - 协程收口到生命周期作用域，移除 `GlobalScope`/临时 `CoroutineScope`。
  - 修复发现结果 key 拼接错误（端口常量误字面量化）。
- 验收状态：通过 `./gradlew :app:testDebugUnitTest`。

### 阶段 B（已完成，可进入阶段 C）
- 已完成：
  - Domain 去 JSON 化：`ZoneSelectionUseCase` 使用 `ZoneSnapshot`。
  - 引入连接路由编排：`ConnectionRoutingUseCase`。
  - 连接历史持久化下沉：`ConnectionHistoryRepository`。
  - 配对 token/core_id 持久化与迁移下沉：`PairedCoreRepository`。
  - 发现候选策略下沉：`DiscoveryCandidateUseCase` + `DiscoveryPolicyConfig`。
  - 连接探测循环下沉：`ConnectionProbeUseCase`（统一“首个可达目标”探测流程）。
  - 自动发现执行编排下沉：`DiscoveryExecutionUseCase`（主扫描/回退/等待流程）。
  - 发现时序参数配置化：`DiscoveryTimingConfig`（替代散落等待/超时魔法值）。
  - SOOD 协议编解码与收发下沉：`SoodProtocolCodec` + `SoodDiscoveryClient`。
  - 对应 JVM 单测已补齐：`ZoneSelectionUseCaseTest`、`ConnectionRoutingUseCaseTest`、`ConnectionHistoryRepositoryTest`、`PairedCoreRepositoryTest`、`DiscoveryCandidateUseCaseTest`、`ConnectionProbeUseCaseTest`、`DiscoveryExecutionUseCaseTest`、`SoodProtocolCodecTest`、`DiscoveryOrchestratorTest`。
- 当前风险状态：
  - `MainActivity` 仍承担连接副作用调度与 UI 状态编排，后续可继续下沉为应用服务层。

### 阶段 C（已完成）
- 已完成：
  - 建立统一配置入口：`AppRuntimeConfig`（聚合 `ConnectionConfig`、`DiscoveryNetworkConfig`、`DiscoveryPolicyConfig`、`DiscoveryTimingConfig`、`UiTimingConfig`、`CacheConfig`）。
  - `MainActivity` 主路径参数已切换到配置注入：
    - 连接参数：WebSocket 端口、连接超时、自动重连延迟、历史清理窗口、恢复阈值、重连退避上限。
    - 发现参数：SOOD 服务 ID、多播地址、广播地址、发现端口、发现时序。
    - UI/缓存参数：艺术墙轮换/延迟、多击按键时序、缓存阈值。
  - 发现端口语义收敛：移除 `MainActivity` 中 `9100/9332` 业务分支，统一为“候选探测端口 + 标准化 `host:port` 记录”。
  - 公告判定收敛：公告解析改为“SOOD `service_id + http_port` 强约束优先”，文本端口仅作为兜底探测入口。
  - 网络层硬编码收敛：`RoonConnectionValidator`、`SimplifiedConnectionHelper`、`SmartConnectionManager`、`NetworkReadinessDetector`、`ConnectionHealthMonitor`、`SimpleWebSocketClient` 的端口/超时参数统一改为配置注入。
  - 应用编排层补位：新增 `DiscoveryOrchestrator`，自动发现主流程从 Activity 下沉到应用编排层。
  - 资源生命周期补强：公告监听 socket 回收改为 `finally` 统一释放，避免异常路径泄漏。
  - 运行时覆盖机制：新增 `RuntimeConfigOverrideRepository` + `RuntimeConfigResolver`，支持 `runtime_config.*` 前缀覆盖，区分默认值与覆盖来源。
  - 配置校验边界：覆盖值统一经过范围校验与夹紧（端口/超时/重试/缓存等），非法值回退默认并输出告警。
  - 配置可观测性：应用启动输出配置快照、覆盖项来源与校验告警，便于复盘配置驱动问题。
- 当前风险状态：
  - `DiscoveryPolicyConfig` 的列表类策略（如网段/IP 后缀池）仍未开放运行时覆盖，当前仅支持连接/发现时序/UI/缓存参数覆盖。
  - 已通过 `ArchitectureGuardTest` 对 `MainActivity/network` 层关键发现字面量做守卫，后续新增策略项需同步扩展守卫规则。

### 阶段 D（已完成）
- 已完成：
  - WebSocket 帧解析与控制帧回归：新增 `SimpleWebSocketClientFrameTest`，覆盖 `ping` 跳过、分片重组、masked 帧兼容、close 帧退出。
  - 连接状态机与自动重连回归：`ConnectionRoutingUseCaseTest`、`AutoReconnectPolicyTest` 覆盖连接路由与重连决策核心分支。
  - Zone 选择/回退/配置迁移服务级回归：`ZoneConfigRepositoryTest`、`ZoneSelectionUseCaseTest` 覆盖配置迁移与回退链路。
  - 编排层回归：`DiscoveryOrchestratorTest` 覆盖主扫描/回退与自动连接目标选择。
- 验收状态：
  - `./gradlew :app:testDebugUnitTest` 通过（BUILD SUCCESSFUL）。

## 11. 阶段 B 完成总结（2026-02-06）

结论：阶段 B 的“分层收口”主目标已达成，建议正式进入阶段 C（配置治理）。

完成摘要（按架构边界）：
1. Domain 层
   - 新增连接路由、发现候选、探测循环、执行编排等用例，业务决策从 `MainActivity` 抽离。
   - `ZoneSelectionUseCase` 不再依赖 `JSONObject`，改为纯业务模型 `ZoneSnapshot`。
2. Data 层
   - 新增 `ConnectionHistoryRepository`（成功连接历史与优先级）。
   - 新增 `PairedCoreRepository`（token/core_id 持久化与迁移）。
3. Network 层
   - 新增 `SoodProtocolCodec`（SOOD 报文编解码）。
   - 新增 `SoodDiscoveryClient`（SOOD UDP 收发）。
4. Presentation 层（`MainActivity`）
   - 从“策略实现者”收敛为“流程编排 + UI 副作用执行者”。
   - 删除重复 SOOD 旧流程，减少协议细节在 Activity 中的散落。

验收对照（阶段 B 标准）：
1. `MainActivity` 职责是否收敛：
   - 已显著收敛（数据/策略/协议细节已大幅下沉），但仍保留副作用调度与 UI 状态编排。
2. Domain 是否摆脱 Android/JSON 具体实现：
   - 已达成（核心用例不依赖 `JSONObject` 与 Android API）。
3. 关键流程是否可在 JVM 单测验证：
   - 已达成（新增 use case/repository/protocol 单测，`./gradlew :app:testDebugUnitTest` 通过）。

## 12. 阶段 C 完成清单（按优先级）

目标：彻底消除“散落参数 + 隐式策略”，形成统一配置治理（当前范围已完成）。

`P0` 必做（进入可维护状态的门槛）：
1. 建立统一配置入口
   - 状态：已完成。
   - 已落地 `AppRuntimeConfig`，并聚合连接/发现/UI/缓存配置。
2. 清理 `MainActivity` 中剩余时序魔法值
   - 状态：已完成。
   - 已收口启动等待、艺术墙切换、生命周期恢复阈值、多击按键时序等关键项，并完成非主路径参数扫描与替换。
3. 消除发现端口分支硬编码
   - 状态：已完成。
   - 已移除 `9100/9332` 端口特判分支，端口仅作为探测候选和标准化连接目标参与后续流程。

`P1` 高优先（减少策略漂移风险）：
1. 建立运行时覆盖机制
   - 状态：已完成。
   - 已支持默认值 + 覆盖值（`runtime_config.*`），并记录覆盖来源。
2. 配置校验与边界保护
   - 状态：已完成。
   - 已对端口、超时、重试次数、扫描窗口、缓存阈值做边界约束，越界值会夹紧并告警。
3. 配置可观测性
   - 状态：已完成。
   - 启动时输出配置快照、覆盖项与校验告警，支持问题复盘。

`P2` 收尾项（工程质量）：
1. 文档化配置契约
   - 状态：已完成。
   - 已新增 `docs/runtime-config-contract.md`，覆盖用途、默认值、取值范围、风险说明与覆盖示例。
2. 增加配置驱动单测
   - 状态：已完成。
   - 已新增 `RuntimeConfigResolverTest`，覆盖“默认配置”“极限配置夹紧”“非法配置回退”。
3. 代码守卫
   - 状态：已完成。
   - 已新增 `ArchitectureGuardTest`，禁止在 `MainActivity/network` 层引入关键发现字面量硬编码。

阶段 C 完成判定（DoD）：
1. 连接与发现参数不再散落在业务代码中。
2. 修改策略仅需改配置，不需改核心流程逻辑。
3. 配置变更有单测兜底，且 `./gradlew :app:testDebugUnitTest` 通过。
4. 阶段 C 范围内无剩余阻塞项。
