# CoverArt for Android - Roon音乐封面显示器

CoverArt 是面向 Roon 生态的 Android 客户端，专注在大屏设备上实时展示当前播放的专辑封面，并在闲置时切换到艺术墙模式，打造沉浸式的客厅音乐体验。

## ✨ 核心特性
- **实时播放信息**：通过 WebSocket 与 Roon Core 保持连接，展示曲目标题、艺术家、专辑及高清封面。
- **艺术墙模式**：播放停止 5 秒自动进入 15 宫格轮播，封面每分钟轮换，随时可一键返回单封面模式。
- **物理按键控制**：支持键盘多击手势（单击播放 / 双击下一首 / 三击上一首）及静默音量调节，自动匹配当前显示区域。
- **动态视觉**：封面主色调驱动背景、文字对比度与阴影效果，适配横竖屏与不同分辨率。
- **智能缓存**：LRU 策略管理最多 900 张封面，具备去重、离线显示与轮播池动态补充。

## 📋 系统要求
- Android 8.0 (API 26) 及以上，建议使用 10" 以上横屏设备。
- 同一局域网内可访问的 Roon Core，已启用扩展 API。
- 稳定的 Wi-Fi 连接与约 500 MB 剩余存储空间。

## 🚀 快速上手
1. **构建应用**
   - 推荐使用 Android Studio Hedgehog (2023.1.1)+，直接 `File > Open` 打开仓库。
   - 首次同步会自动下载依赖；如需命令行，可执行 `./gradlew assembleDebug`。
2. **配置 Roon Core**
   - 在 Roon 桌面端开启 *设置 > 扩展*，确认 CoverArt 可见并已授权。
   - 记下 Core IP（默认端口 `9330`）。
3. **连接应用**
   - 启动 CoverArt 后输入 Core 地址或等待自动发现。
   - 应用会按 `info → register → subscribe` 流程完成注册，并缓存 token 以便下次免授权。

## 🎯 日常使用
- **区域选择**：默认自动选择“正在播放 → 有曲目信息 → 首个区域”的优先级，可在 Roon *扩展 > CoverArt_Android* 中手动指定。
- **播放控制**：物理播放键支持多击逻辑，传统媒体键（Play/Pause/Next/Prev）同样适用。
- **艺术墙管理**：在所有监控区域停止时自动切换，可从 UI/物理键随时退出；艺术墙素材来自缓存池。
- **状态提示**：底部状态栏展示连接、授权、区域选择等细节，出现告警（网络中断、区域失效）时便于定位。

## 🏗️ 架构速览
- `MainActivity.kt`：统一的通信与 UI 控制中心，处理 WebSocket/MOO 协议、UI 状态和媒体键响应。
- `network/`：
  - `SmartConnectionManager` 智能重试与网络检测。
  - `RoonConnectionValidator` 进行 Core 连通性校验。
  - `ConnectionHealthMonitor` 周期性健康检测与降级处理。
- `api/RoonApiSettings.kt`：对接 Roon Settings 服务，支撑区域配置 UI。
- `SimpleWebSocketClient`：精简的 Moo/WebSocket 客户端，支持消息顺序化和断线重连。

## 🧪 开发与测试
| 操作 | 命令 |
| --- | --- |
| 构建 Debug APK | `./gradlew assembleDebug` |
| 安装到设备 | `./gradlew installDebug` |
| 运行单元测试 | `./gradlew testDebugUnitTest` |
| 运行 Lint | `./gradlew lint` |

- 构建依赖由 Gradle 自动管理，JDK 17–21 均可。
- 首次命令行构建可设置 `GRADLE_USER_HOME=$PWD/.gradle`，避免修改全局环境。
- 设备端需授予网络、存储与多播权限以保证发现与缓存功能。

## 📦 版本信息
- **当前版本**：Android_FrameArt_2.15
- **扩展 ID**：`com.epochaudio.coverartandroid`
- **发布日期**：2025-08-20

### 更新摘要
- **2.15**：同步版本号，抽象注册报文构建逻辑，清理失效网络缓存与辅助函数，统一区域信息解析。
- **2.13**：通信栈合并，WebSocket 客户端与注册流程统一到单一实现；引入 core_id Token 管理与自动迁移。

## 🤝 支持
若有问题或建议：
1. 确认使用最新版本并重试连接。
2. 提供设备型号、系统版本、日志或截图描述现象。


欢迎提交 Issue/PR，为 Roon 爱好者共建更好的客厅视觉体验。
# CoverArt
