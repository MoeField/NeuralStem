# 稳定性测试报告 v0.2.65（2026-11-26）

> 触发：v0.2.60→v0.2.64 连续 5 个版本改动后，做一次发布级回归，并关闭 REVIEW 的 NEED-CONFIRM-3/5。
> 设备：BF00A（Android 16 / arm64），`0.2.64-test`（TEST_HOOKS）+ `0.2.65` 修复验证。
> 结果：**测试通过，并发现/修复 1 个真实 bug（SeparationService 常驻前台）**。

## 1. 2h 多轨固定基线 soak

脚本：`tools/stability_multitrack_fixed.sh`（本次新增）

参数：

- `DURATION_SEC=7200`
- **`EQ_DB=-15`**（EQ 全段 −15 dB）
- **`VOL=1`**（每轨满音量）
- 每 240 ops force-stop 一次并重新进入多轨、重置 EQ/音量
- 10% 概率短暂切 `eq=+15` / `vol=0` 后恢复，覆盖切换路径

结果：

| 指标 | 值 |
|---|---|
| ops | **6666** |
| 强制重启 | **27** |
| FATAL EXCEPTION | **0** |
| ANR | **0** |
| 播放错误（`播放失败`/`ERROR_CODE_`） | **0** |
| heartbeat | 707 |
| multi load | 28 |
| EQ 失败 | **0** |
| `max_unmuted_started` | **1**（单播放真源） |

产出：`stability-out/mt-fixed-20261126-002128/`（logcat + state + summary）。

## 2. 长分离 + 前台服务生命周期（NEED-CONFIRM-3）

素材：`bullet.flac`（102 MB），全曲 edge 分离，清缓存后真推理。

结果：

- 分离完成：`edge done(file): vFrames=6527690 total=6528375 complete=true`，约 30s
- `SeparationService`（`dataSync` 前台）与 `PlaybackService`（`mediaPlayback`）并存，分离期间服务未中断
- 内存：TOTAL PSS **215 MB**，Native 22 MB，Java 88 MB

### 发现并修复：服务分离完成后常驻前台

**现象**：分离完成 4 分钟后，`dumpsys activity services` 仍显示
`SeparationService isForeground=true foregroundId=24224 startRequested=true`，且无 `stopSelf` 日志。

**根因**：终态 `_state.value = Done/EdgeDone` 在 worker job **内部**赋值，服务收到终态时
`repo.isRunning` 仍为 true（job 正在收尾）。旧实现直接 `return@collectLatest`，job 随后结束但 state 不再变化
→ 服务永远不 `stopSelf`，`dataSync` 前台持续占用。

**旁证**（同一设备、其他 app）：`android.app.ForegroundServiceStartNotAllowedException:
Time limit already exhausted for foreground service type dataSync` —— 证明 Android 15+ 确实会累计 dataSync 时长。

**修复**（v0.2.65）：终态 + `isRunning=true` 时安排 `scheduleTerminalStopRetry()`，每 500ms 重查、最多 5s，
repo idle 立即 `stopSelfCleanly()`。

**验证**：

```text
02:26:47.086  SepRepo : edge done(file): ... complete=true
02:26:47.599  SepService: terminal stop retry → repo idle, stopSelf
02:26:47.599  SepService: separation terminal → stopSelf
```

`dumpsys` 随后仅剩 `PlaybackService`，`SeparationService` 已销毁。

## 3. PCM 缓存租约（NEED-CONFIRM-5）

场景：播放当前曲（触发 PCM 缓存）→ 设置 → PCM 缓存 → 清理未使用缓存。

结果：

| 指标 | 清理前 | 清理后 |
|---|---|---|
| 占用 | 299.1 MB | 29 MB |
| 文件数 | 5 | 1 |

- 保留的正是当前播放曲的 PCM（29 MB）
- 播放继续（`state=PLAYING`）
- 与 `PcmStoreTest` 的 12 条 JVM 单测（lease/pin/clear/tmp/孤儿）一致

## 4. 结论

- v0.2.64 基线在 2h 混合压力下零错误
- NEED-CONFIRM-3 从“未验证”变为 **发现真实 bug 并修复**（v0.2.65）
- NEED-CONFIRM-5 真机验证通过
- 建议：v0.2.65 作为当前稳定基线；结构重构（v0.3.0）可按计划后延
