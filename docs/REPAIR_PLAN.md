# 维修计划（REPAIR_PLAN）

> 来源：`PATH_AUDIT.md` 的 BUG-01..BUG-10。  
> 状态：**P0 已实施；P1-1/P1-2 已实施；P2 部分（临时清理）**。  
> 原则：先复现/加测试，再修 P0；每步单独提交；`main` 保持 v0.2.14 可回退。

---

## 0. 目标与非目标

### 目标

1. 消除“A 的分离结果串到 B”的所有已知竞态。
2. 让 Domain 的 job/attachment 与播放器实际状态一致。
3. 补 A→B、重叠任务、full 后台完成、手动开关的真机/单测回归。
4. 统一导出命名（至少明确是否要模型维度）。

### 非目标

- 不在本计划重写播放器/`SessionStateMachine`。
- 不新增分离算法/模型。
- 不处理 NCM/AVS3/R8/AGP。
- 不为了命名规范去改 contentHash 缓存身份（hash 仍是正确性 key）。

---

## 1. 维修总顺序

```text
P0-1 复现 A→B + 加日志/测试
P0-2 唯一临时源 + 启动 currentTrack 校验
P0-3 EdgeSession 与 job/source 绑定
P0-4 repo 终态带 source token
P0-5 Finished 按 currentTrack 过滤
P1-1 手动开关走 Domain
P1-2 ServiceLost/ProcessRestored 接线
P2-1 导出模型维度/命名决策
P2-2 临时文件/旧缓存清理
P2-3 移除 SpikeVM 双适配
```

P0 已实现并完成 A→B 真机回归；P1-1 开关走 Domain、P1-2 ServiceLost 已接；
P2 已完成旧临时文件清理 + 导出模型目录；直解 content URI/SpikeVM 适配仍未做。
是否合 main/发 v0.2.15 待最终验收。

---

## 2. P0：错曲结果

### P0-1 复现与诊断

**任务**

- 构造：A（who_am_i）完成普通/edge 分离 → 顺序播到 B（bullet）→ B 立即点「极快」。
- 采集：`SepDomain`、`SepRepo`、`loadMultiTrack`、`EdgeSession`、`PlayerVm` 日志；
  记录命中的 cache hash/file 路径。
- 增加临时诊断日志（可保留为 debug tag）：
  - Domain jobId / request.trackKey / currentTrackKey
  - `tickEdge` 的 edge.generation/sourceUri
  - repo 终态 source（见 P0-4）

**验收**

- 至少复现一次“A 文件出现在 B job”或证明当前无法复现；
- 若无法复现，仍按代码竞态修复并加确定性 reducer 测试。

### P0-2 唯一临时源 + 启动校验

**文件**

- `spike/CurrentTrackBridge.kt`
- `spike/state/SeparationDomain.kt`
- `spike/SeparationScreen.kt` / `SpikeViewModel.kt`

**改动**

1. 临时源文件名：`separate_input_<uriHash>_<jobId>.<ext>`（或直接解码 content URI，见 P2）。
2. `SeparationDomain.start()`：
   - request.trackKey 与 snapshot.currentTrackKey 不一致 → 拒绝 + `Notify("已切歌，请重试")`。
3. `SpikeViewModel.start*` 在 `session.beginSeparation` 前做同样的检测，避免状态机卡 Preparing。
4. 老 `startSeparation` 的空 TrackKey 自动入口改为带当前 uri/owner。

**测试**

- 单测：Domain start 拒绝错误 TrackKey。
- 真机：A→B 自动下一首窗口点极快，不应出现 A 文件。

### P0-3 EdgeSession 与 job/source 绑定

**文件**

- `spike/state/SeparationDomain.kt`
- `spike/EdgeSeparationSession.kt`（如需暴露 generation/source）

**改动**

1. `tickEdge`/`publishEdgeUi` 校验：
   - `edge.sourceUri`/generation/startFrame 与当前 job 的 request/track 匹配；
   - 不匹配 → 不发送 coverage/UI，等待新 session 初始化。
2. Domain 记录 `activeEdgeGeneration`/`activeSourceUri`，在 `SourceReady` 时绑定。
3. `onRepoState(EdgeDone)` 不再读全局 edge 的 total/start，改由 `EdgeDone` 自带/当前绑定 coverage；
   或至少在读取前做 source 校验。
4. 旧 EdgeSession 的 `abort` 与新 job 启动顺序：确保新 job 开始前旧 session 已失效，或 Domain 忽略旧 generation。

**测试**

- reducer/controller 交错：旧 edge generation 的 coverage 到达新 job → 丢弃。
- 真机：A edge 未完成时切 B，B 不吃 A 的进度/文件。

### P0-4 repo 终态带 source token

**文件**

- `spike/SeparationRepository.kt`
- `spike/state/SeparationDomain.kt`

**改动**

1. `State.Done/Done4/EdgeDone/Failed` 增加轻量字段：
   - `sourceKey`（contentHash 或 sourcePath）与可选 `engineTag`。
   - 不改 State 语义，只做归属校验。
2. Domain 在 `StartDecode` 保存 `activeSourceKey`/`activeEngine`；`onRepoState` 终态必须匹配，不匹配丢弃。
3. 如不想改 State，可增加 `repo.jobToken` 并在每次状态发射时带上；二选一，优先 sourceKey（可读、可测）。

**测试**

- 纯 reducer：Late A JobFinished(jobId=B) 若 source 不匹配应忽略。
- Domain adapter 集成测试（fake repo state）。

### P0-5 Finished 按 currentTrack 过滤

**文件**

- `spike/SpikeViewModel.kt`
- `ui/AppShell.kt`（owner 校验时序）

**改动**

1. `applyDomainSnapshot` 在 `Lifecycle.Finished` 分支：
   - 若 `job.trackKey != snapshot.currentTrackKey` → 只缓存/记录，不置 `Done2/Done4` 给当前 UI；
   - FULL 后台继续（D1）必须走这个分支。
2. `lastExportable`/`_lastEdgeDone` 也按 currentTrack 过滤。
3. AppShell 的 owner 校验保留为第二道闸。

**测试**

- FULL 后台完成于 B 播放时：UI 不出现 A 的完成/导出；
- A 的 Done 迟到时 B 的 separationTask 不变。

---

## 3. P1：状态一致

### P1-1 手动开关走 Domain

**文件**

- `ui/AppShell.kt`
- `player/PlayerScreen.kt`
- `spike/SeparationScreen.kt`
- `player/PlayerViewModel.kt`

**改动**

- 开关回调改为 Domain `attach/detach`；PlayerBridge 执行 load/detach。
- UI `checked` 用 Domain attachment/实际多轨联合判断；
- 手动关 → 设置 autoAttachSuppressed；手动开 → 清 suppression。

**测试**

- 手动 off 后 edge 自动 attach 不再抢开；
- 手动 on 后 domain attachment 与 isMultiTrack 一致。

### P1-2 ServiceLost/ProcessRestored 接线

**文件**

- `PlaybackService` / `PlayerViewModel` / `SeparationService`
- `SeparationDomain`

**改动**

- `PlaybackService` 终止/解绑时发 `onPlaybackServiceLost`；
- `SeparationService` 终止时发 `onSeparationServiceLost`；
- App 启动/VM 恢复时发 `onProcessRestored`（或明确删除死代码并按 cache/VM 对账）。

**测试**

- kill Service / 进程重建场景矩阵。

---

## 4. P2：命名与维护

### P2-1 导出命名决策

- 选项 A：`Music/separate/<song>/<model>/`（推荐，满足“每次分离按歌+模型区分”）。
- 选项 B：保持 `<song>/`，仅在 UI/日志标注模型，接受同名覆盖。
- 需要用户/产品确认；确认后改 `StemExporter`/调用点/README/测试。

### P2-2 临时文件/缓存清理

- 压缩源：直接 `MediaExtractor` 吃 content URI/fd，只保留 `sep-src-*.wav`；
- WAV：fd 可 seek 直接读，否则唯一副本；
- 清理旧的 `separate_input.*`/`sep-src-*`（启动/完成时按时间/任务）。

### P2-3 移除 SpikeVM 双适配

- Domain View 直接供 SeparationScreen；
- 去掉 `SeparationTask`/`lastExportable`/`lastEdgeDone` 手工镜像；
- 保留 adapter 作为过渡，最终单独提交。

---

## 5. 测试矩阵（必须真机过）

| 场景 | 期望 |
|---|---|
| A 未分离，顺序播到 B，B 极快 | B 的结果/cache，不能出现 A 文件 |
| A edge 未完成，切 B，B 极快 | 旧 A 事件被丢弃；B 正常 |
| A 分离已 attach，A 播完自动 B | B 不继承 A 多轨/stems |
| FULL 后台完成时当前 B | 不弹 A 的完成、不切 A stems、不导出 A |
| 手动 off/on | Domain attachment 与播放器一致 |
| 取消 A 后立刻 B | pendingStart/旧事件不串 |
| 进程重建 | 不恢复 active job，cache/finished 可重新命中 |
| 导出 2/4/Demucs | 目录/文件名符合决策，不覆盖错曲 |

JVM 侧补：

- reducer 交错（旧 source/job 终态）；
- Domain adapter fake repo 测试；
- CurrentTrackBridge 单测（唯一命名）；
- 导出命名测试。

---

## 6. 分支/发布策略

- 分支：`fix/path-audit`，基于 `main` / v0.2.14。
- 禁止直接改 `main`；每个 P0 一个提交，带测试。
- P0 全绿 + A/B 真机回归通过 → 合并；是否发 v0.2.15 再定。
- 失败/无法复现：保留分支，先报告复现日志，不硬改。

---

## 7. 风险

| 风险 | 对策 |
|---|---|
| 改 source token 触碰 repo 状态/缓存 | 只加字段，不改 cache key；保留旧缓存 |
| 唯一临时文件占用空间 | 按 job/时间清理；压缩源直解减少一层 |
| 手动开关改 Domain 引入 UI 闪烁 | 先加 capability，再切 UI |
| FULL 后台过滤导致旧完成不可见 | 保留缓存/通知，不推当前 UI |
| 路径修复破坏 fast path | 保留 fast path 回归（v0.2.13 场景） |

---

## 8. 待确认

1. 导出是否要模型子目录（选项 A/B）。
2. FULL 后台完成时是否需要在当前曲 UI 提示“旧曲已完成”。
3. P0 修复是否直接发 v0.2.15，还是攒 P1 一起发。
