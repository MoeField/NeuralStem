# 稳定性测试报告 v0.3.1（2026-11-26）

> 触发：v0.3.1 拆 `PlaybackService`（896 → 489 行）后，验证「只改结构、不改行为」。
> 设备：BF00A（Android 16 / arm64），`0.3.1-test`（TEST_HOOKS）。
> 脚本：`tools/stability_multitrack_fixed.sh`，`eq=-15` / `vol=1`。

## 结果（2h 多轨固定基线 soak）

| 指标 | 值 |
|---|---|
| 时长 | **7200s**（20:22 → 22:22） |
| ops | **6652** |
| 强制重启 | **27** |
| FATAL EXCEPTION | **0** |
| ANR | **0** |
| 播放错误（`播放失败`/`ERROR_CODE_`） | **0** |
| `ConcurrentModificationException` | **0** |
| heartbeat | 698 |
| multi load | 28 |
| EQ 失败 | **0** |
| `max_unmuted_started` | **1** |

产出：`stability-out/mt-fixed-20261126-202229/`。

## 覆盖的操作

- 每 240 ops `force-stop` → 重启 → 重新 `inmt` → 重置 EQ/音量（27 轮）
- 每轮随机：play/pause/seek/repeat one/all/speed 0.8–1.2/pitch −6..+6
- 10% 概率短暂切 `eq=+15` / `vol=0` 后恢复（覆盖切换路径，基线不变）

## 结论

- v0.3.1 的 5 步拆分（EqHost / SnapshotPublisher / MultiTrackCoordinator / PlayerCommandHandler）
  在 2h 混合压力下**零回归**：无崩溃、无 ANR、无竞态、无播放错误、单播放真源保持。
- 与 v0.2.65 基线（6666 ops / 0 错误）同一量级，说明拆分未改变行为。
- v0.3.1 可作为当前稳定基线；`v0.3.2`（SessionCallback / 单轨生命周期进一步拆分）另行排期。
