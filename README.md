# CoverArt for Android - Roon Music Display & Art Wall

[English](#english) | [中文](#chinese)

<a name="english"></a>
## 🇬🇧 English

**CoverArt** is an Android client designed for the Roon ecosystem, focusing on displaying real-time album art on large-screen devices. It seamlessly switches to an immersive "Art Wall" mode when idle, creating a stunning visual experience for your living room.

### ✨ Core Features
- **Real-time Now Playing**: Connects to Roon Core via WebSocket to display track title, artist, album, and high-resolution cover art.
- **Art Wall Mode**: Automatically enters a 15-grid cover art carousel after 5 seconds of inactivity. Covers rotate every minute. One-click return to Now Playing.
- **Physical Controls**: Supports keyboard multi-tap gestures (Single: Play/Pause, Double: Next, Triple: Prev) and silent volume adjustments.
- **Dynamic Visuals**: Adaptive background colors, text contrast, and shadow effects based on the dominant color of the current cover art.
- **Smart Caching**: LRU strategy manages up to 900 covers with deduplication, offline support, and dynamic pool replenishment.

### 📋 System Requirements
- Android 8.0 (API 26) or higher. Recommended for 10"+ landscape devices.
- Roon Core accessible on the same LAN (Extension API enabled).
- Stable Wi-Fi connection and ~500 MB free storage.

### 🚀 Quick Start
1. **Build**
   - Use Android Studio Hedgehog (2023.1.1)+. Open the project and run.
   - Or use command line: `./gradlew assembleDebug`.
2. **Configure Roon Core**
   - Enable the extension in Roon via *Settings > Extensions*.
   - Note the Core IP (default port `9330`).
3. **Connect**
   - Launch CoverArt. Enter Core IP or wait for auto-discovery.
   - The app follows `info → register → subscribe` flow and caches the token for future auto-login.

### ⚙️ Runtime Config Overrides
- Override keys are read from Android `SharedPreferences` with prefix `runtime_config.`.
- Values are validated and clamped to safe ranges by `RuntimeConfigResolver`.
- Startup logs include a config snapshot, applied overrides, and validation warnings.
- Contract doc: `docs/runtime-config-contract.md`.

### 📦 Version History
- **2.20 (Latest)**:
  - **Settings Fix**: Fixed settings service request routing so Zone selector is correctly rendered in Roon extension settings.
  - **Settings Compatibility**: Improved `get_settings/save_settings` payload compatibility to prevent empty settings dialogs.
  - **Settings Protocol Alignment**: Aligned `subscribe_settings/unsubscribe_settings` handshake with Roon behavior (`CONTINUE Subscribed`, `COMPLETE Unsubscribed`) for stable settings rendering.
  - **Zone Save Effectiveness**: Use `zone` as the canonical settings key so selected zone is applied to the app immediately after saving.
  - **Settings UI Persistence**: Persist selected output name and mirror `name/display_name` to improve settings dialog value restore after restart.
  - **Protocol Hardening**: Integrated `SimpleWebSocketClient` with synchronous handshake logic for more reliable connections.
  - **Moo Protocol**: Implemented `MooParser` and `MooMessage` for robust message parsing and handling.
  - **Stability**: Fixed race conditions during Roon Core discovery and registration phases.
  - **Reconnect Reliability**: Added in-flight guard and auto-reconnect policy to reduce duplicate connect/register flows.
  - **Zone Selection**: Added `ZoneSelectionUseCase` and `ZoneConfigRepository` for safer fallback when stored zone is invalid.
  - **Logging Hygiene**: Reduced WebSocket per-frame log noise and kept lifecycle logs (`CONNECT_START/OK/FAIL`, `LOOP_START/END`, `DISCONNECT`) for troubleshooting.
  - **Art Wall**: Optimized for server-side random image API, improving performance and variety.
  - **Architecture**: Unified WebSocket client and registration flow; added `core_id` token management and auto-migration.
- **2.13**:
  - Initial connection stack unification.

---

<a name="chinese"></a>
## 🇨🇳 中文

**CoverArt** 是面向 Roon 生态的 Android 客户端，专注在大屏设备上实时展示当前播放的专辑封面，并在闲置时切换到艺术墙模式，打造沉浸式的客厅音乐体验。

### ✨ 核心特性
- **实时播放信息**：通过 WebSocket 与 Roon Core 保持连接，展示曲目标题、艺术家、专辑及高清封面。
- **艺术墙模式**：播放停止 5 秒自动进入 15 宫格轮播，封面每分钟轮换，随时可一键返回单封面模式。
- **物理按键控制**：支持键盘多击手势（单击播放 / 双击下一首 / 三击上一首）及静默音量调节，自动匹配当前显示区域。
- **动态视觉**：封面主色调驱动背景、文字对比度与阴影效果，适配横竖屏与不同分辨率。
- **智能缓存**：LRU 策略管理最多 900 张封面，具备去重、离线显示与轮播池动态补充。

### 📋 系统要求
- Android 8.0 (API 26) 及以上，建议使用 10" 以上横屏设备。
- 同一局域网内可访问的 Roon Core，已启用扩展 API。
- 稳定的 Wi-Fi 连接与约 500 MB 剩余存储空间。

### 🚀 快速上手
1. **构建应用**
   - 推荐使用 Android Studio Hedgehog (2023.1.1)+，直接 `File > Open` 打开仓库。
   - 首次同步会自动下载依赖；如需命令行，可执行 `./gradlew assembleDebug`。
2. **配置 Roon Core**
   - 在 Roon 桌面端开启 *设置 > 扩展*，确认 CoverArt 可见并已授权。
   - 记下 Core IP（默认端口 `9330`）。
3. **连接应用**
   - 启动 CoverArt 后输入 Core 地址或等待自动发现。
   - 应用会按 `info → register → subscribe` 流程完成注册，并缓存 token 以便下次免授权。

### ⚙️ 运行时配置覆盖
- 应用会从 Android `SharedPreferences` 读取 `runtime_config.` 前缀键作为覆盖配置。
- 覆盖值会经 `RuntimeConfigResolver` 做校验和边界夹紧，非法值自动回退默认值。
- 启动日志会输出配置快照、已应用覆盖项和校验告警。
- 配置契约见：`docs/runtime-config-contract.md`。

### 🎯 日常使用
- **区域选择**：默认自动选择“正在播放 → 有曲目信息 → 首个区域”的优先级，可在 Roon *扩展 > CoverArt_Android* 中手动指定。
- **播放控制**：物理播放键支持多击逻辑，传统媒体键（Play/Pause/Next/Prev）同样适用。
- **艺术墙管理**：在所有监控区域停止时自动切换，可从 UI/物理键随时退出；艺术墙素材来自缓存池。
- **状态提示**：底部状态栏展示连接、授权、区域选择等细节，出现告警（网络中断、区域失效）时便于定位。

### 📦 版本信息
- **2.20 (Latest)**:
  - **Settings 修复**: 修复 settings 服务请求路由，Roon 扩展设置页可正确渲染 Zone 选择器。
  - **Settings 兼容性**: 增强 `get_settings/save_settings` 载荷兼容，避免设置弹窗出现空白配置。
  - **Settings 协议对齐**: 将 `subscribe_settings/unsubscribe_settings` 对齐到 Roon 期望握手（`CONTINUE Subscribed`、`COMPLETE Unsubscribed`），设置页渲染更稳定。
  - **Zone 保存生效**: 以 `zone` 作为设置主键，保存后可立即在 App 端应用所选 Zone。
  - **设置回显持久化**: 持久化所选 output 名称，并镜像补齐 `name/display_name`，提升重启后设置弹窗的回显稳定性。
  - **协议强化**: 引入 `SimpleWebSocketClient` 配合同步握手逻辑，连接更稳定。
  - **Moo 协议**: 实现 `MooParser` 和 `MooMessage`，提升消息解析的安全性和准确性。
  - **稳定性修复**: 修复了 Roon Core 发现与注册阶段的竞态条件问题。
  - **重连可靠性**: 增加连接防重与自动重连策略，降低重复连接/重复注册概率。
  - **Zone 选择治理**: 增加 `ZoneSelectionUseCase` 与 `ZoneConfigRepository`，存量 Zone 失效时可安全回退。
  - **日志止血**: 大幅降低 WebSocket 逐帧日志噪声，保留关键生命周期日志（连接开始/成功/失败、循环开始/结束、断开）。
  - **艺术墙优化**: 适配服务端随机图片 API，提升加载效率与内容多样性。
  - **架构统一**: 统一 WebSocket 客户端与注册流程；引入 core_id Token 管理与自动迁移。

 
### 🤝 支持
若有问题或建议：
1. 确认使用最新版本并重试连接。
2. Check [Issues](https://github.com/epochaudio/CoverArtForAndroid/issues) or submit a PR.
