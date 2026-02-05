# Clean Architecture 审查与最小风险改造清单（2026-02-05）

## 1. 审查背景
- 审查目标：识别逻辑重复、冗余和高风险错误，给出先止血后重构的最小风险改造路径。
- 审查范围：
  - `app/src/main/java/com/example/roonplayer/MainActivity.kt`
  - `app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt`
  - `app/src/main/java/com/example/roonplayer/network/*`
- 审查方式：静态代码审查 + Kotlin 编译验证（`./gradlew :app:compileDebugKotlin` 通过）。

## 2. 关键问题（按严重级别）

### P0 / 高风险
1. Zone 配置失效后没有回退路径，导致有可用 Zone 仍可能无法显示内容。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:3953`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:4066`
   - 影响：用户看到“未找到合适播放区域”，但实际上可恢复为自动选择。

2. 健康监控生命周期不闭合，断开后监控可能继续盯旧连接。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:3326`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3495`、`app/src/main/java/com/example/roonplayer/network/ConnectionHealthMonitor.kt:33`
   - 影响：状态提示与真实连接错位，重连后监控可能失效。

3. 重连入口并发，`connect()` 防重不足（仅判断 isConnected）。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:520`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:531`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:6264`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3257`
   - 影响：重复连接、重复注册、偶发状态竞争。

4. token 持久化策略分叉，core_id token 读取链路与配对加载模型不一致。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:3805`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:2327`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:2337`
   - 影响：启动流程可能误判“无已配对 Core”，降低自动恢复成功率。

### P1 / 中风险
1. 后台线程直接写 UI 仍然存在。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:2368`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:2379`
   - 影响：可能触发线程安全问题和偶发崩溃。

2. Zone 配置/映射逻辑在 `MainActivity` 与 `RoonApiSettings` 双份实现。
   - 证据：
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4499`
     - `app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt:302`
     - `app/src/main/java/com/example/roonplayer/MainActivity.kt:4928`
     - `app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt:269`
   - 影响：后续修复容易“改一处漏一处”。

3. 状态模型并行（`TrackState`、`UIState`、直接控件写入）破坏单一事实源。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:94`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:248`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:5136`
   - 影响：旋转恢复、实时更新、缓存恢复可能互相覆盖。

### P2 / 低风险
1. 冗余/死路径较多，增加维护成本。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:3038`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3904`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:5715`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:5897`

2. 无效空值保护写法掩盖分支意图。
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:4156`
   - 说明：`sendMoo()` 返回 `Unit`，`?: run {}` 不会按预期触发。

## 3. 架构洞察（Clean Architecture 视角）
1. `MainActivity` 承担了展示、连接、发现、注册、Zone 规则、持久化和恢复，边界完全耦合。
2. 业务规则没有独立 UseCase，导致“流程正确性”依赖 UI 生命周期时序。
3. 持久化键规则未集中管理，历史迁移逻辑分散，演进成本高且回归风险大。
4. 线程模型混杂（`GlobalScope`、`activityScope`、裸 `CoroutineScope`），生命周期不可控。

## 4. 最小风险改造清单（先止血后重构，按提交粒度）

> 原则：每个提交只做一件事、可单独回滚、可独立验收。

### 阶段 A：先止血（优先上线）

#### Commit A1：修复 Zone 失效回退
- 提交标题建议：`fix(zone): fallback to auto-selection when stored zone is unavailable`
- 目标：当存储 Zone 不可用时，自动降级到可用 Zone，而不是停留在无效配置。
- 触达范围：Zone 选择分支与状态提示路径。
- 验收：
  - 存储无效 `zoneId` 时，应用能自动恢复到可用 Zone。
  - 不再出现“有 Zone 但显示未找到合适区域”。

#### Commit A2：修复健康监控生命周期
- 提交标题建议：`fix(connection): bind health monitor to active websocket lifecycle`
- 目标：断开时停止监控，重连时明确重启监控并绑定新连接。
- 触达范围：`disconnect()`、连接成功路径、监控启动/停止入口。
- 验收：
  - 切换连接后监控目标与当前连接一致。
  - 不出现“旧连接仍在报警”的状态漂移。

#### Commit A3：连接去重与并发防护
- 提交标题建议：`fix(connection): deduplicate reconnect triggers with in-flight guard`
- 目标：保证任意时刻只有一个连接流程在执行。
- 触达范围：启动、自动重连、网络变化、恢复路径。
- 验收：
  - 高频网络波动下不重复触发注册。
  - 日志中同一时间窗口仅出现一条主连接流程。

#### Commit A4：UI 主线程写入收口
- 提交标题建议：`fix(ui): route status and text updates through main-thread dispatcher`
- 目标：清理后台线程直接写 `TextView` 的路径。
- 触达范围：发现流程、诊断流程、重连流程的 UI 更新点。
- 验收：
  - 所有 UI 更新统一经过主线程封装。
  - 压测下无 `CalledFromWrongThreadException`。

#### Commit A5：配对数据读取一致化
- 提交标题建议：`fix(pairing): normalize loading for host-based and core-id-based tokens`
- 目标：统一 token 读取策略，避免 core_id 方案被 host 校验误伤。
- 触达范围：配对加载、自动重连候选构建逻辑。
- 验收：
  - 冷启动可正确识别已配对 Core。
  - 不再出现“明明有 token 却走全网发现”。

### 阶段 B：后重构（稳定后推进）

#### Commit B1：提取 ZoneConfigRepository
- 提交标题建议：`refactor(zone): extract zone config repository and key policy`
- 目标：消除 `MainActivity` 与 `RoonApiSettings` 的重复配置逻辑。
- 触达范围：配置加载、迁移、输出映射、键策略。
- 验收：
  - Zone 配置读写只有一个实现。
  - 迁移逻辑可单测覆盖。

#### Commit B2：提取 ZoneSelectionUseCase
- 提交标题建议：`refactor(zone): move selection policy to use case`
- 目标：把“存储优先/自动回退/手动覆盖”规则从 UI 层抽离。
- 触达范围：`handleZoneUpdate`、手动选择、设置回调。
- 验收：
  - 策略变更不需要改 UI 代码。
  - 选择行为可通过输入输出测试验证。

#### Commit B3：提取 ConnectionCoordinator UseCase
- 提交标题建议：`refactor(connection): centralize connect-discover-retry orchestration`
- 目标：收敛发现、连接、重连、注册流程为单入口状态机。
- 触达范围：`connect()`、自动发现、网络回调、启动恢复。
- 验收：
  - 连接状态机状态明确（Idle/Connecting/Connected/Reconnecting/Failed）。
  - 重连策略可配置、可观测。

#### Commit B4：统一状态模型
- 提交标题建议：`refactor(state): converge ui and playback state to single source of truth`
- 目标：收口 `TrackState/UIState/控件直写` 为单状态驱动渲染。
- 触达范围：更新入口与渲染函数。
- 验收：
  - 状态变化仅通过一个 reducer/更新入口。
  - 旋转与恢复行为一致。

#### Commit B5：清理死代码与历史分支
- 提交标题建议：`chore(cleanup): remove unused flows and obsolete helpers`
- 目标：删除未使用函数、注释掉的历史分支、无效保护代码。
- 触达范围：发现备份流程、未调用对话框、废弃健康检查分支等。
- 验收：
  - 函数引用图清晰，无孤儿流程。
  - 编译与核心回归通过。

#### Commit B6：补齐回归测试
- 提交标题建议：`test(core): add regression coverage for reconnect and zone selection`
- 目标：为止血点和关键策略加回归保障。
- 覆盖重点：
  - Zone 失效回退
  - 连接去重
  - token 迁移与加载
  - 健康监控生命周期

## 5. 交付与推进建议
- 第一批上线建议仅包含 A1-A5（止血提交），每个提交后做一次小范围验证。
- 第二批重构建议按 B1-B6 顺序推进，避免同时移动“规则 + 生命周期 + UI”三类风险。
- 每个提交建议附带：
  - 风险说明
  - 回滚方式
  - 最小验证步骤（连接、播放、切 Zone、重启、断网恢复）

## 6. 实施进度（当前分支）
- [x] A1 `fix(zone): fallback to auto-selection when stored zone is unavailable`
- [x] A2 `fix(connection): bind health monitor to active websocket lifecycle`
- [x] A3 `fix(connection): deduplicate reconnect triggers with in-flight guard`
- [x] A4 `fix(ui): route status and text updates through main-thread dispatcher`
- [x] A5 `fix(pairing): normalize loading for host-based and core-id-based tokens`
- [x] B1 `refactor(zone): extract zone config repository and key policy`
- [x] B2 `refactor(zone): move selection policy to use case`
- [x] B3 `refactor(connection): centralize connect entry helper`
- [x] B4 `refactor(state): converge ui and playback state to single source of truth (minimal-risk step)`
- [x] B5 `chore(cleanup): remove unused flows and obsolete helpers`
- [x] B6 `test(core): add regression coverage for reconnect and zone selection`
- B5 当前进展：已清理未接线私有流程（当前分支 diff 中删除 45 个 `private fun` 入口），含旧授权弹窗、旧健康检查链路、旧推荐/诊断/多区弹窗分支。
- B6 当前进展：已新增 `ZoneSelectionUseCase`、`ZoneConfigRepository`、`AutoReconnectPolicy`、`InFlightOperationGuard` 的 JVM 单测并通过 `:app:testDebugUnitTest`。

### B4 本轮落地说明
- 移除 `UIState` 并收口到 `TrackState(currentState)` 单状态源。
- `saveUIState/restoreUIState` 改为对 `currentState` 快照与渲染，不再维护第二份状态对象。
- `displayZoneContent`、`resetDisplay`、封面加载失败分支统一走 `updateTrackInfo/updateAlbumImage`。
- Zone 更新中的“是否变更”判断改为基于 `currentState` 快照，而非直接读取控件文本。
