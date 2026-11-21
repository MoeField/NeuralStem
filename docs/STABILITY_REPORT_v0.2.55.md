# 状态稳定性测试报告（v0.2.55 候选）

> 状态：**测试执行中，门禁未全绿，未发 v0.2.55**。  
> 计划：[STABILITY_TEST_PLAN.md](STABILITY_TEST_PLAN.md)。  
> 设备：BF00A（Android 16，arm64），音量 0/15。

## 1. 构建

- 被测 debug：`0.2.55-test (255)`，`-PtestHooks=true`
- 正式基线：`0.2.54 (254)`
- 代码：`9e154e6` + 稳定性测试/修复

## 2. 结果总览

| 项 | 结果 |
|---|---|
| JVM 全量 | **507/0** |
| `MultiTrackEngineSmokeTest` | **1/0** |
| `Av3aGuardTest` | **1/0** |
| 5min soak | 319 ops / 3 restarts / 0 FATAL / 0 ANR / 0 player error |
| 2h soak | **7128 ops / 90 restarts / 0 FATAL / 0 ANR / 0 player error / 0 waveform error** |
| 关键序列脚本（5 组） | **5/5 PASS**：basic / multi / eq / av3a / restart |
| write 故障注入 | **PASS**：重建 AudioTrack → session 3913→3921 → EQ 重挂 → heartbeat 5015ms |
| Spleeter 2 轨真机 | **PASS**：39MB 模型下载 → edge complete=true → 自动 2 轨多轨 → 导出 2 文件成功 |
| Spleeter 4 轨真机 | **PASS**：79MB 模型下载 → 4 轨分离 17 blocks → 自动 4 轨多轨 → 导出 4 文件成功 |
| SAF grant | **PASS**：导入后 GRANTS=1；删除后 released file grant、GRANTS=0 |

2h 产物：`stability-out/soak-2h-c/`（本地，未入库）  
关键序列产物：`stability-out/key-20261122-015434/`

## 3. 本轮发现/修复

### Stopping 时迟到 StemsLoaded 清空 pendingStart

- 随机 reducer 测试 seed=4 step=35 复现
- 修复 `SeparationReducer.onStemsLoaded()`：Stopping 直接忽略迟到加载
- 回归测试：`SeparationReducerTest.stopping_stemsLoaded_must_not_drop_pendingStart`
- 随机模型：2000 seeds × 40 events 全绿

### 测试环境问题（非 App 缺陷，已排除）

- 第一次 2h soak 无效：`connectedDebugAndroidTest` 结束后 App 被卸载，`monkey` 拉起失败；已停止，不计入结果。
- 第二次重试：debug 重装 + READ_MEDIA_AUDIO 授权 + `file://` 曲目，App 进程/命令总线存活后才计入。

## 4. 已验证序列

| 序列 | 结果 |
|---|---|
| 基础播放：load/play/pause/seek/next/prev | PASS |
| 多轨：inmt + 全轨 volume=0 + mtpos + exitsmt | PASS |
| EQ：eqall -15 / +15 / 关闭 | PASS |
| AV3A m4a：公开版明确拒绝 | PASS |
| force-stop + 冷启动 | PASS |
| write 失败注入 + AudioTrack 重建 + EQ 重挂 | PASS |
| 2h 随机播放/重启 | PASS |
| edge 分离→attach→多轨 | PASS（coverage complete=true，2 轨） |
| 导出到 Music/separate | PASS（2 文件 spleeter-2stem；4 文件 spleeter-4stem） |
| SAF 导入→删除→grant | PASS（count 1→0） |

## 5. 未完成/仍需补测

- Demucs 分离压力（未下载 172MB 模型）
- 损坏 playlists.json 真机（JVM 已测）
- 损坏 playlists.json 真机（JVM 已测）
- 15 条序列中剩余的分离/歌单/导出人工序列
- **#2 完整 UI Stopping 仍不稳定性**；C SeparationService 启动竞态仍开放
- 未做 2h 以外的低内存/后台杀进程专项

## 6. 发布决定

**v0.2.55 暂不发布。**

门禁状态：

- [x] JVM 全绿
- [x] 2h soak 无 FATAL/ANR/播放错误
- [x] 核心多轨/AV3A/EQ/write 故障序列
- [ ] 15 条关键序列全部执行
- [ ] 分离/导出/SAF/损坏歌单真机序列
- [ ] #2 Stopping 完整验证
- [ ] 无未解释 P0/P1

下一版若包含 B1 架构改动，按计划跳 **v0.3.0**；否则先完成上述剩余测试再发 v0.2.55。
