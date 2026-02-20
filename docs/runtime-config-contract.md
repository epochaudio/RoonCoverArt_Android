# Runtime 配置契约（阶段 C 收尾）

## 1. 目标
- 把连接、发现、UI、缓存参数统一配置化。
- 明确默认值、取值范围、风险边界，避免隐式策略漂移。
- 支持运行时覆盖（调试/灰度）且可追踪来源。

## 2. 生效机制
1. 默认配置来自 `AppRuntimeConfig.defaults()`。
2. 运行时覆盖从 `SharedPreferences` 读取，键前缀为 `runtime_config.`。
3. 覆盖值统一通过 `RuntimeConfigResolver` 做类型解析和边界校验。
4. 非法值回退默认；越界值夹紧到边界；并输出告警日志。
5. 启动日志输出配置快照、覆盖项、告警，便于复盘。

## 3. 覆盖来源
- 来源实现：`RuntimeConfigOverrideRepository`
- 前缀：`runtime_config.`
- 值类型：`String/Number/Boolean`（最终按字符串解析）

## 4. 配置项契约

### 4.1 Connection
| 覆盖键 | 默认值 | 取值范围 | 用途 | 风险说明 |
|---|---:|---:|---|---|
| `connection.web_socket_port` | `9330` | `1..65535` | WebSocket 目标端口默认值 | 错配会导致全链路连接失败 |
| `connection.tcp_connect_timeout_ms` | `1000` | `100..120000` | TCP 探测/验证超时 | 过小易误判离线，过大阻塞发现 |
| `connection.health_check_connect_timeout_ms` | `3000` | `100..120000` | 健康检查 TCP 建连超时 | 过小易抖动误判，过大延后异常反馈 |
| `connection.ws_connect_timeout_ms` | `5000` | `100..120000` | WebSocket TCP 建连超时 | 过大会拖慢失败切换 |
| `connection.ws_handshake_timeout_ms` | `5000` | `100..120000` | WebSocket 握手超时 | 过小易在弱网握手失败 |
| `connection.ws_read_timeout_ms` | `15000` | `100..300000` | WebSocket 读超时 | 过小会造成误断连 |
| `connection.smart_retry_max_attempts` | `5` | `1..20` | 智能重试次数 | 过大会产生重试风暴 |
| `connection.smart_retry_initial_delay_ms` | `1000` | `100..300000` | 智能重试初始延迟 | 过小会高频重试 |
| `connection.smart_retry_max_delay_ms` | `15000` | `100..300000` | 智能重试最大延迟 | 过大恢复太慢 |
| `connection.health_check_interval_ms` | `15000` | `1000..600000` | 健康检查正常周期 | 过小增加网络负载 |
| `connection.health_quick_check_interval_ms` | `5000` | `1000..600000` | 健康检查降级周期 | 过小增加抖动 |
| `connection.network_ready_timeout_ms` | `30000` | `1000..600000` | 等待网络就绪总时限 | 过大影响故障反馈 |
| `connection.network_ready_poll_interval_ms` | `1000` | `100..60000` | 网络就绪轮询间隔 | 过小高 CPU/高日志 |
| `connection.network_check_timeout_ms` | `3000` | `100..120000` | 网络连通性探测超时 | 过小弱网误判 |
| `connection.network_test_host` | `8.8.8.8` | 非空，长度 `<=255` | 网络探测目标主机 | 不可达会影响就绪判断 |
| `connection.network_test_port` | `53` | `1..65535` | 网络探测目标端口 | 错配影响就绪判断 |
| `connection.auto_connect_delay_ms` | `1000` | `0..300000` | 自动连接延迟 | 过大会延后恢复 |
| `connection.auto_discovery_delay_ms` | `2000` | `0..300000` | 自动发现启动延迟 | 过大会延后发现 |
| `connection.history_retention_ms` | `2592000000` | `3600000..31536000000` | 连接历史保留窗口 | 过小损失历史命中率 |
| `connection.long_pause_reconnect_threshold_ms` | `30000` | `0..1800000` | 长暂停重连阈值 | 过小会频繁重连 |
| `connection.long_stop_reconnect_threshold_ms` | `60000` | `0..1800000` | 长停止重连阈值 | 过小会误触重连 |
| `connection.smart_reconnect_max_backoff_ms` | `30000` | `100..300000` | 智能重连退避上限 | 过小会过于激进 |

### 4.2 Discovery Network
| 覆盖键 | 默认值 | 取值范围 | 用途 | 风险说明 |
|---|---:|---:|---|---|
| `discovery.network.port` | `9003` | `1..65535` | 发现端口 | 错配会收不到发现报文 |
| `discovery.network.multicast_group` | `239.255.90.90` | IPv4 字面量 | SOOD 多播组 | 错配会错过公告 |
| `discovery.network.service_id` | `00720724-5143-4a9b-abac-0e50cba674bb` | 非空，长度 `<=64` | 服务识别 ID | 错配会过滤掉合法报文 |
| `discovery.network.broadcast_address` | `255.255.255.255` | IPv4 字面量 | 广播地址 | 错配降低主动发现命中 |

### 4.3 Discovery Timing
| 覆盖键 | 默认值 | 取值范围 | 用途 | 风险说明 |
|---|---:|---:|---|---|
| `discovery.timing.scan_interval_ms` | `100` | `0..10000` | 网段扫描节流 | 过小易造成扫描突发 |
| `discovery.timing.direct_detection_wait_ms` | `3000` | `0..180000` | 直连探测后等待 | 过小可能漏收后续结果 |
| `discovery.timing.announcement_socket_timeout_ms` | `2000` | `100..120000` | 公告 socket 超时 | 过小易频繁超时 |
| `discovery.timing.announcement_listen_window_ms` | `20000` | `100..300000` | 公告监听窗口 | 过小漏发现，过大耗时 |
| `discovery.timing.active_sood_socket_timeout_ms` | `8000` | `100..120000` | 主动 SOOD socket 超时 | 过小易失败 |
| `discovery.timing.active_sood_listen_window_ms` | `8000` | `100..300000` | 主动 SOOD 监听窗口 | 过小漏响应 |

### 4.4 UI Timing
| 覆盖键 | 默认值 | 取值范围 | 用途 | 风险说明 |
|---|---:|---:|---|---|
| `ui.timing.multi_click_delta_ms` | `400` | `50..5000` | 多击识别窗口 | 过小难触发，过大易误判 |
| `ui.timing.single_click_delay_ms` | `600` | `50..5000` | 单击延迟确认 | 过大会降低响应 |
| `ui.timing.art_wall_update_interval_ms` | `60000` | `1000..3600000` | 艺术墙轮换周期 | 过小影响性能 |
| `ui.timing.art_wall_stats_log_delay_ms` | `3000` | `0..300000` | 艺术墙统计日志延迟 | 过小增加日志噪声 |
| `ui.timing.delayed_art_wall_switch_delay_ms` | `5000` | `0..300000` | 延迟切艺术墙 | 过小导致频繁切换 |
| `ui.timing.reset_display_art_wall_delay_ms` | `2000` | `0..300000` | 重置封面墙延迟 | 过大导致 UI 滞后 |
| `ui.timing.startup_ui_settle_delay_ms` | `2000` | `0..300000` | 启动稳定等待 | 过小可能触发早期竞态 |

### 4.5 Cache
| 覆盖键 | 默认值 | 取值范围 | 用途 | 风险说明 |
|---|---:|---:|---|---|
| `cache.max_cached_images` | `900` | `10..10000` | 图片缓存上限 | 过大增加磁盘和内存压力 |
| `cache.max_display_cache` | `15` | `1..500` | 显示缓存上限 | 过小会频繁解码 |
| `cache.max_preload_cache` | `5` | `1..500` | 预加载缓存上限 | 过大增加内存占用 |
| `cache.memory_threshold_bytes` | `52428800` | `1048576..1073741824` | 内存阈值 | 过高可能触发 OOM 风险 |

### 4.6 Feature Flags（网络改造灰度）
| 覆盖键 | 默认值 | 取值 | 用途 | 风险说明 |
|---|---:|---|---|---|
| `feature_flags.new_sood_codec` | `true` | `true/false` | 启用新版 SOOD 编解码与发现报文字段 | 关闭后回退旧 SOOD 编解码行为 |
| `feature_flags.new_moo_router` | `false` | `true/false` | 启用 request-id 驱动的 MOO 路由 | 早期开启可能暴露历史非标准报文问题 |
| `feature_flags.new_subscription_registry` | `false` | `true/false` | 启用统一订阅注册表（zones/queue） | 早期开启可能与旧订阅状态并存 |
| `feature_flags.new_zone_store` | `false` | `true/false` | 启用 reducer 驱动的 zone 状态管理 | 早期开启可能改变 zone 事件合并顺序 |
| `feature_flags.strict_moo_unknown_request_id_disconnect` | `false` | `true/false` | 未知 Request-Id 响应时断连（严格模式） | 可能对兼容性差的 Core 过于严格 |

## 5. 当前未开放覆盖项
- 发现策略列表（网段、IP 后缀、端口池列表）仍在默认策略中维护。
- 若要开放覆盖，需额外补充“列表解析 + 安全上限 + 审计日志”。

## 6. 示例
```
runtime_config.connection.web_socket_port=9331
runtime_config.connection.ws_connect_timeout_ms=8000
runtime_config.discovery.timing.announcement_listen_window_ms=30000
runtime_config.cache.max_cached_images=1200
runtime_config.feature_flags.new_moo_router=true
```
