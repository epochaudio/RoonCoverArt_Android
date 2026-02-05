# WebSocket 日志止血验证清单（2026-02-05）

## 目标
- 删除逐帧高频日志，避免 `logcat` 洪泛。
- 保留连接生命周期日志，保证排障链路可见。

## 代码策略
- 文件：`app/src/main/java/com/example/roonplayer/network/SimpleWebSocketClient.kt`
- 保留 `DEBUG_ENABLED=true` 以持续输出关键生命周期日志。
- 新增 `FRAME_VERBOSE_LOG=false`，逐帧解析日志统一走 `logFrameVerbose()`（默认关闭）。
- 生命周期日志保留并统一事件名：
  - `[WS][CONNECT_START]`
  - `[WS][CONNECT_OK]`
  - `[WS][CONNECT_FAIL]`
  - `[WS][LOOP_START]`
  - `[WS][LOOP_END]`
  - `[WS][DISCONNECT]`
  - `[WS][REMOTE_EOF]`
  - `[WS][REMOTE_CLOSE_FRAME]`

## 验证步骤
1. 清空设备日志：
   - `adb -s <device> logcat -c`
2. 启动应用并保持运行 60 秒。
3. 检查逐帧日志是否被抑制：
   - `adb -s <device> logcat -d | rg "Reading WebSocket frame|Received WebSocket continuation frame|WebSocket payload read|Continuing fragmented message reassembly"`
   - 预期：无输出或极低（仅当临时打开 `FRAME_VERBOSE_LOG` 时出现）。
4. 检查生命周期日志是否存在：
   - `adb -s <device> logcat -d | rg "\[WS\]\[(CONNECT_START|CONNECT_OK|CONNECT_FAIL|LOOP_START|LOOP_END|DISCONNECT|REMOTE_EOF|REMOTE_CLOSE_FRAME)\]"`
   - 预期：能看到连接建立、循环启动/结束、断开等关键节点。
5. 检查崩溃关键字：
   - `adb -s <device> logcat -d | rg "FATAL EXCEPTION|AndroidRuntime"`
   - 预期：无匹配。

## 回滚策略
- 若需要恢复旧行为，仅需回退 `SimpleWebSocketClient.kt` 本次提交。
