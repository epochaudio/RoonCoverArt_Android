# 架构与代码审查记录

## 范围与目标
- 范围：`app/src/main/java/com/example/roonplayer/MainActivity.kt`、`app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt`、`app/src/main/java/com/example/roonplayer/network/*`
- 目标：识别逻辑重复、冗余和错误；从 Clean Architecture 视角提出改造方案和步骤；暂不实施代码修改

## 关键结论（按风险优先级）
1) 连接信息持久化端口错误，导致自动重连极易失败或连错服务  
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:4090`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:4110`
   - 影响：没有显式端口时保存为 9332/9100，与实际 9330 不一致，后续自动重连/历史选择错误

2) 非主线程操作 UI，存在稳定性风险  
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:6597`
   - 影响：在 IO 协程中直接 `ipInput.setText`、`connect()` 可能触发崩溃或状态异常

3) Settings 响应 Content-Length 计算错误  
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:4648`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:4676`
   - 影响：使用字符长度而非字节长度，遇到非 ASCII（如中文区域名）可能被 Roon 端解析失败

4) Settings 初始化早于 IP 加载，导致配置读取错误  
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:597`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:600`、`app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt:215`
   - 影响：初始化时 host 为空，读取不到已保存的 zone 配置

5) Zone 配置持久化键体系不一致  
   - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:4706`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:4912`、`app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt:304`
   - 影响：一处保存为 `configured_zone_{coreId}`，另一处读取 `roon_zone_id_{host}`，可能覆盖或忽略用户配置

## 结构性与重复/冗余问题
- 握手与 info 请求重复与分叉  
  - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:3560`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3729`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3812`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:3588`
  - 表现：握手处理分布在多个入口，且 `sendWebSocketHandshake` 未调用，容易引发重复注册与状态分裂

- 状态模型并行且不一致  
  - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:92`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:312`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:492`
  - 表现：TrackState/UIState/legacy 状态并存，既有锁又有 AtomicReference，易产生漂移与重复更新

- 连接持久化路径重复且历史结构未落地  
  - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:5693`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:6584`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:147`
  - 表现：`saveSuccessfulConnection` 与 `saveLastSuccessfulConnection` 并存，`ConnectionHistory` 仅定义未使用

- Zone 选择策略重复与分散  
  - 证据：`app/src/main/java/com/example/roonplayer/MainActivity.kt:4206`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:4741`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:5409`、`app/src/main/java/com/example/roonplayer/MainActivity.kt:5631`
  - 表现：自动选择、推荐选择、手动选择逻辑在多处叠加，缺少统一优先级与入口

- 领域逻辑与 UI 强耦合，跨层依赖明显  
  - 证据：`app/src/main/java/com/example/roonplayer/api/RoonApiSettings.kt:15`（持有 EditText）
  - 表现：Settings 组件依赖 UI 控件，违反 Clean Architecture 的依赖方向

- 未使用/半迁移的组件与变量增加维护负担  
  - 证据：`app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt:195`
  - 表现：`connectToExistingCore` 未被调用（已删除）；`errorHistory` 未发现；`NetworkReadinessDetector` 与 `networkCallback` 仍在使用

## 方案步骤（分阶段）
### 阶段 0：风险止血（高价值低改动）
1) 统一端口策略与持久化规则（默认 9330，含输入端口时原样保存）  
2) UI 更新全部归一到主线程（封装 UI 更新入口）  
3) Settings 响应 Content-Length 统一按字节数计算（UTF-8）  
4) RoonApiSettings 初始化时机调整，确保已加载 host/IP  
5) Zone 配置改为单一全局键，移除 core_id/host 多套键  
6) 删除隐藏手动输入控件与相关逻辑，减少 UI 噪音

### 阶段 1：职责拆分与边界收敛
1) 提取 ConnectionUseCase（连接/重连/健康检查）与 ZoneUseCase（选择/推荐/过滤）  
2) 将 Settings 组件改为纯数据/协议层，不依赖 UI 控件  
3) 收敛为单一状态模型（PlaybackState + ConnectionState）

### 阶段 2：模块化与 Clean Architecture 对齐
1) data 层：WebSocket/Discovery/Cache Repository  
2) domain 层：UseCase 与业务规则  
3) presentation 层：UI 渲染与输入事件  
4) 强制依赖方向：presentation -> domain -> data

### 阶段 3：测试与回归
1) 协议层解析测试（MOO、Settings、Content-Length）  
2) 连接与重连流程测试（含端口/历史策略）  
3) Zone 选择策略测试（用户配置优先级）

## 建议
- 对外可见行为明确化：连接、授权、Zone 选择的优先级与状态机  
- 强制单一入口：握手/注册/订阅只允许在一条流程中执行  
- 持久化键命名统一并集中管理，避免散落在 UI 与 service 中  
- 删除未使用/未完成的分支功能，降低心智负担  
- 逐步替换 GlobalScope，绑定生命周期避免泄漏

## 任务清单（低风险重复优先）
### A. 未使用/半迁移组件与变量清理
- [x] 用 `rg` 列出 `NetworkReadinessDetector`、`networkCallback`、`errorHistory`、`connectToExistingCore` 的全部引用点  
- [x] 确认是否存在注册/回调/副作用（如 `registerNetworkCallback`、延迟任务）  
- [x] 删除未使用函数：`connectToExistingCore`  
- [x] 更新结论：`errorHistory` 未发现；`NetworkReadinessDetector`/`networkCallback` 在用，保留  

### B. 合并连接持久化入口
- [x] 盘点 `saveSuccessfulConnection` 与 `saveLastSuccessfulConnection` 的调用点与入参差异  
- [x] 选定保留函数并梳理职责边界（持久化 + 最近连接更新）  
- [x] 抽取共享逻辑为单一实现，更新调用点  
- [x] 删除重复函数与旧路径，统一命名与注释  

### C. 合并 Zone 选择入口
- [x] 盘点自动/推荐/手动选择的调用点与触发条件  
- [x] 明确优先级规则（存储配置优先；无配置时自动选择；推荐/手动为显式覆盖）  
- [x] 提取单一入口函数，所有路径统一走该入口（`applyZoneSelection`）  
- [x] 收敛选择分支的副作用（保存/计数/UI 更新统一入口控制）  

### D. 统一握手与 info 请求入口
- [x] 盘点握手与 info 发送的所有入口与触发条件  
- [x] 选定唯一入口函数并明确参数与时机（`sendInfoRequestOnce`）  
- [x] 迁移所有调用点到唯一入口  
- [x] 结论更新：`sendWebSocketHandshake` 未发现（已无冗余实现）  

### E. 收敛状态模型更新入口
- [x] 盘点 `TrackState`、`UIState` 与 legacy 状态的更新点  
- [x] 明确主状态源为 `TrackState`（`currentState`），`UIState`保留用于配置变更  
- [x] 移除 legacy 状态变量与未使用的保存/恢复函数  
- [x] 收敛更新入口：`updateTrackInfo`/`updateAlbumImage`/`updateStatus` 不再写入 legacy 路径  

## 已确认约束
- 允许 Roon Core 使用非 9330 端口：解析与持久化必须保留显式端口，默认回退统一为 9330  
- 不需要多 Core 分别保存 Zone 配置：Zone 配置使用单一全局键，移除按 Core/Host 的分歧  
- Settings 响应 Content-Length 必须按字节长度计算（UTF-8）  
- 不采用 kiosk 模式：删除隐藏的手动输入控件逻辑以降噪

## 建议的验收标准
- 首次连接与自动重连均可稳定恢复  
- Zone 配置变更后重启仍能正确恢复  
- Settings 服务在中文区域名下可正确返回并被 Roon 端接受  
- 同一事件流不产生重复注册或重复订阅
