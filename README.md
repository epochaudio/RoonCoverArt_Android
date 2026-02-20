# CoverArt for Android (v2.23)

[English](#english) | [中文](#chinese)

<a name="english"></a>
## English

CoverArt is an Android display client for the Roon ecosystem.
It is designed for always-on screens and TV-class devices, showing current album art in full screen and switching to an art wall when playback is idle.

### What It Does
- Shows now playing title, artist, album, and high-resolution cover art from Roon Core.
- Keeps a clean visual mode by hiding normal status text; only actionable warnings are shown as overlay.
- Automatically enters a 15-cover Art Wall when playback stops, then returns to single-cover mode when music resumes.
- Supports both physical media keys and touch gestures for playback control.
- Uses local cache and color-adaptive background/text styling for stable long-running display behavior.

### Playback Controls
- Touch gestures:
  - Swipe left: next track
  - Swipe right: previous track
  - Swipe up: pause
  - Swipe down: play
- Media keys:
  - Play/Pause key multi-click: single=toggle, double=next, triple=previous
  - Media Next / Previous / Play / Pause / Stop supported
  - Silent volume up/down and mute handling

### Requirements
- Android 8.0 (API 26) or newer
- Roon Core on the same LAN
- Extension API enabled in Roon
- Stable Wi-Fi network

### Build & Install
1. Open in Android Studio and run debug build, or use CLI: `./gradlew assembleDebug`
2. Current packaged APK (v2.23) is generated at project root: `CoverArtForAndroid-v2.23-debug.apk`
3. Install the APK to your Android device.
4. Ensure device and Roon Core are in the same LAN.

### Setup in Roon
1. Open Roon desktop app.
2. Go to `Settings > Extensions`.
3. Find `CoverArt_Android` and enable it.
4. Select or confirm playback zone if needed.

### How to Use
1. Launch CoverArt.
2. Wait for auto-discovery and auto-pairing.
3. During playback, the app shows single-cover now playing view.
4. When playback is idle/stopped, the app transitions to Art Wall automatically.
5. Use touch gestures or media keys to control playback directly from the display device.

### Version
- **2.23 (Latest)**
  - Added touch gesture playback controls (left/right/up/down swipes)
  - Added track transition animation feedback when changing tracks
  - Kept clean status overlay policy and existing keyboard/media-key control path
- **2.21**
  - Switched user-facing UI status text to English
  - Added alert-only status overlay visibility strategy
  - Improved first-pairing authorization hint behavior

---

<a name="chinese"></a>
## 中文

CoverArt 是面向 Roon 生态的 Android 展示端应用。
它面向常亮大屏/电视类设备，主界面专注展示当前播放专辑封面，播放停止时自动进入艺术墙模式。

### 功能简介
- 实时展示 Roon 当前播放信息：歌曲名、艺术家、专辑名、高清封面。
- 状态提示采用“告警优先”策略：正常播放时保持画面干净，仅在需要处理的问题场景显示提示层。
- 播放停止后自动进入 15 宫格艺术墙；恢复播放后自动回到单封面模式。
- 同时支持物理媒体键与触摸手势控制播放。
- 本地缓存与主色调自适应背景/文字，提升长期运行稳定性与观感一致性。

### 播放控制
- 触摸手势：
  - 左滑：下一曲
  - 右滑：上一曲
  - 上滑：暂停
  - 下滑：开始播放
- 物理媒体键：
  - 播放键多击：单击=播放/暂停，双击=下一曲，三击=上一曲
  - 支持 Next / Previous / Play / Pause / Stop
  - 支持静默音量增减与静音

### 使用条件
- Android 8.0（API 26）及以上
- 与 Roon Core 在同一局域网
- 已在 Roon 中启用 Extension API
- 网络连接稳定

### 构建与安装
1. 使用 Android Studio 打开工程并运行，或命令行执行：`./gradlew assembleDebug`
2. 当前已打包的 v2.23 APK 位于项目根目录：`CoverArtForAndroid-v2.23-debug.apk`
3. 安装生成的 APK 到 Android 设备。
4. 确保设备与 Roon Core 在同一局域网。

### Roon 侧配置
1. 打开 Roon 桌面端。
2. 进入 `设置 > 扩展`。
3. 找到 `CoverArt_Android` 并启用。
4. 如有需要，在扩展设置中确认播放区域（Zone）。

### 使用指南
1. 启动 CoverArt。
2. 等待自动发现/自动配对。
3. 播放中显示单封面 Now Playing。
4. 停播后自动切换为艺术墙。
5. 可直接在屏幕上滑动控制播放，或使用媒体键操作。

### 版本信息
- **2.23（最新）**
  - 新增触摸手势播放控制（左/右/上/下滑）
  - 新增切歌动画反馈
  - 保留并兼容原有媒体键控制与状态提示策略
- **2.21**
  - UI 状态文案统一英文
  - 增加仅告警可见的状态提示层策略
  - 优化首次配对授权提示逻辑

### 支持
- Issue / PR: [GitHub](https://github.com/epochaudio/CoverArtForAndroid/issues)
