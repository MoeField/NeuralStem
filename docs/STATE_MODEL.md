# 分离状态模型重构：需求与设计稿

> 状态：**设计稿，未实施**  
> 基线：`v0.2.13`（`7d9f2ed`）  
> 分支：`refactor/separation-state-model`  
> 目标：先把需求和边界定清楚，再写代码；不在 `main` 上直接改。

---

## 0. 结论摘要

- **新开分支**，不直接改 `main`；`main` 保持 `v0.2.13` 可发布状态。
- 分离状态收敛为：**一个不可变 Snapshot + 一个单写者 actor + 显式 Operation + 任务/挂载分离**。
- 外部事件（播放结束、下一首、Service 丢失、进程恢复）不再由 UI 的 `LaunchedEffect` 临时处理，统一进入 reducer。
- 中间态（解码、取消中、加载 stems）是正式状态，不再用 `isBusy`/`isCancelling` 之类布尔拼出来。
- 先做“纯模型 + 迁移测试”，再接 UI；每阶段可回滚，最后才删旧状态字段。

---

## 1. 背景

### 1.1 现状盘点

分离相关状态目前至少分布在 6 个地方：

| 位置 | 现有状态/字段 |
|---|---|
| `SeparationRepository.State` | Idle、Preparing(PrepPhase)、Running、Done、Done4、Failed、Cancelled、EdgeDone |
| `EdgeSeparationSession` | State（Idle/Preparing/FirstSegmentReady/Running/Done/Failed/Cancelled）、Gate（Idle/Buffering/Playable/Starved/Finished）、`inBackfill`、`backfillPercent`、`frontCovered` |
| `SpikeViewModel` | State（Idle/Ready/Separating/Done/Error）、`lastEdgeDone`、`lastExportable` |
| `PlayerState` | `separationOn`、`stemsAvailable`、`edgePercent`、`edgeStarved`、`edgeBeforeStart`、`edgeStartMs`、`edgeSeparatedFraction`、`backfillPercent`、`pendingBackfillMs`、`lastStems` |
| `SeparationScreen` | 接收 `separationOn`、`edgePercent`、`backfillPercent`、`pendingBackfillMs`、`autoStartToken` 等参数 |
| `AppShell` | 多个 `LaunchedEffect` 组合 `edgeState/edgeGate/repoState` 决定自动切轨、提示、owner 校验 |

### 1.2 具体问题

1. **同一事实多份副本**：进度、覆盖、可播性、是否已切多轨，在 repo/VM/Service/UI 各有一份，需要手工同步和清零。
2. **派生量被当成状态**：`edgeBeforeStart`、`backfillPercent`、`pendingBackfillMs` 本可由覆盖区间和播放位置算出。
3. **五阶段不是互斥状态**：“解码 / 预览缓存 / 实时分离 / 补前段 / 完成”会重叠；当成五个 enum 后，再乘用户操作就组合爆炸。
4. **一次性效果混进状态观察**：自动切轨靠 `LaunchedEffect(edgeState, edgeGate)`，fast path 终态与上次相同会漏触发（v0.2.13 已修，但属于模型问题）。
5. **外部事件无正式位置**：播放结束、自动下一首、Service 丢失、进程恢复，只在 UI 或零散回调里处理。
6. **中间态没有名字**：取消等待、加载 stems 等待、非可中断 native 返回等待，只能靠布尔和 `generation` 兜。

### 1.3 目标

- 单一真源：分离领域只有一个 Snapshot 被写。
- 任意事件顺序下行为确定：切歌、结束播放、取消、启动交错时不出现“旧 job 覆盖新状态”。
- 中间态显式、可测试、有超时。
- 自动切轨、提示、Service 停止等副作用由 state machine 发 Effect，不由 UI 推断。
- 保留现有用户体验；不改音频算法、不改 R8、不改播放器主状态机。

### 1.4 非目标

- 不重写 `PlaybackService`/`SessionStateMachine` 的播放逻辑。
- 不新增分离模型/格式/导出功能。
- 不改变现有 UI 视觉设计。
- 不在本分支处理 NCM/AVS3、AGP、R8 规则。
- 不承诺首版就删除所有旧类；允许 adapter 过渡。

---

## 2. 功能需求

### 2.1 会话生命周期

用户可发起：

- **2-stem edge**（边分离边播，绑定当前播放位置）
- **2-stem full**（普通分离，完成后可切多轨/导出）
- **4-stem full**

每个任务必须有：

- `jobId`：单调递增，所有异步事件携带；旧 job 事件一律丢弃。
- `trackKey`：稳定标识当前曲（见 3.1）；换曲即不匹配。
- `kind`：EDGE / FULL_2 / FULL_4。
- `coverage`：总帧、起点、已覆盖区间、水位线、前段缺口。
- `files`：完成后的落盘文件（可为空；edge fast path 也应有完整文件）。

### 2.2 阶段与操作

阶段不是互斥 enum，而是以下维度的组合：

| 维度 | 取值 |
|---|---|
| 源准备 | Decoding / Ready |
| 任务生命周期 | Idle / Preparing / Running / Stopping / Finished / Failed |
| 进行中操作 | Decoding / Separating / Backfilling / LoadingStems / Cancelling |
| 覆盖 | 无 / 有预览段 / 水位推进 / 前段缺口 / 全覆盖 |
| 播放挂载 | 未加载 / 已加载当前 job 的 stems |

UI 文案可由这些维度派生，例如：

- “解码中” = 源未 Ready
- “已可播 · 正在补前段” = gate 可播 + frontGap > 0
- “分离完成” = coverage 全覆盖且无 active operation
- “无分离结果” = attachment 未加载且没有当前 track 的 finished job

### 2.3 用户操作

| 操作 | 期望 |
|---|---|
| 开始 edge | 从当前位置开始；换曲/结束播放时取消 |
| 开始 full | 从 0 或指定位置开始；track 改变时按策略处理 |
| 取消 | 任意阶段可请求；不可中断 native 返回后进入 Idle；可排队下一次 Start |
| 切分离开关 | 只有当前 track 有可用 finished job 才成功；否则提示“无分离结果” |
| 补齐前段 | 重新发起 edge（from=0），复用 manifest 只补缺口 |
| 导出 | 只有 finished + complete 才可用；partial 不可导出 |
| 清缓存 | 不改变 active job 的语义；应明确是否取消当前 job |
| seek | 由播放侧处理；分离只通过 gate 影响可播性，不新增 `edgeSeekMs` 类状态 |

### 2.4 外部事件（必须正式建模）

- `TrackChanged(newTrackKey)`：换曲/自动下一首。
- `PlaybackEnded`：当前曲自然结束，可能随后 `TrackChanged`。
- `ServiceLost` / `ProcessRestored`：服务/进程恢复。
- `FocusLost`（可选，影响暂停但不一定影响分离）。
- `ConfigChanged`/旋转：只影响 UI，不应重跑任务。
- 播放位置变化：作为输入，用于派生 gate；不直接改 job lifecycle。

### 2.5 任务与挂载分离

必须区分：

- **JobState**：分离任务本身（可能完成，但用户当前在听别的歌）。
- **AttachmentState**：当前播放器实际加载了哪个 job 的 stems。

`separationOn` 不再单独存，派生为：

```text
attachment.loadedJobId == currentJob.jobId
&& attachment.loadedTrackKey == currentTrackKey
```

这解决“上一首分离完成、下一首正在播”以及“完成但未切多轨”的竞态。

### 2.6 完成后的行为

- 当前 track 匹配 + gate 达到 Playable/Finished：发一次 `SwitchToStems` effect；同一 `jobId` 不重复。
- 当前 track 不匹配：只保留 finished/cache，不自动切。
- 用户返回原曲（`TrackChanged` 回到原 track）且 job 仍 finished：允许手动/按策略重新 attach。
- 自动切轨失败：保留原曲、提示、记录错误，不把 `lastStems` 留成半初始化。

---

## 3. 领域模型

### 3.1 标识

```kotlin
data class TrackKey(
    val uri: String,          // 原始 uri（不是临时文件名）
    val contentHash: String?, // 可选；缓存/身份稳定
    val durationMs: Long,
)

typealias JobId = Long
```

所有事件、Effect、结果都携带 `jobId` 和 `trackKey`。

### 3.2 Snapshot

```kotlin
data class SeparationSnapshot(
    val job: JobState? = null,
    val attachment: AttachmentState = AttachmentState.None,
    val lastFinished: FinishedJob? = null,
    val lastError: String? = null,
)

data class JobState(
    val jobId: JobId,
    val trackKey: TrackKey,
    val kind: SeparationKind,
    val lifecycle: Lifecycle,
    val operation: Operation? = null,
    val coverage: Coverage,
    val files: StemFiles? = null,
)

sealed interface Lifecycle {
    data object Idle : Lifecycle
    data object Preparing : Lifecycle
    data object Running : Lifecycle
    data object Stopping : Lifecycle   // 取消/让位中，等待 worker 真正退出
    data object Finished : Lifecycle
    data class Failed(val message: String) : Lifecycle
}
```

`Finished` 只表示“任务跑完”，不代表已挂载；`Stopping` 是正式中间态。

### 3.3 Operation（中间态）

```kotlin
sealed interface Operation {
    data class Decoding(val jobId: JobId) : Operation
    data class Separating(val jobId: JobId) : Operation
    data class Backfilling(val jobId: JobId) : Operation
    data class LoadingStems(val jobId: JobId) : Operation
    data class Cancelling(
        val jobId: JobId,
        val pendingStart: StartRequest? = null,
    ) : Operation
}
```

规则：

- 同一时刻最多一个 native worker。
- `Cancelling` 期间新的 Start 只更新 `pendingStart`（latest wins），不并发启动。
- `JobStopped` 到达后再根据 `pendingStart` 进入下一任务。
- 非可中断 native 调用：状态保持 `Stopping`，超时后 Failed/回收，不假装 Idle。

### 3.4 Coverage

```kotlin
data class Coverage(
    val totalFrames: Long,
    val startFrame: Long,
    val coveredRanges: List<LongRange>,
    val watermarkFrame: Long,
    val frontGapFrames: Long,
    val complete: Boolean,
)
```

派生：

- `hasPreview = coveredRanges.isNotEmpty()`
- `frontCovered = frontGapFrames == 0L`
- `pendingBackfillMs = frontGapFrames * 1000 / sampleRate`
- `edgeBeforeStart = playFrame < startFrame`
- `phaseLabel` 由 Coverage + source + operation 计算，不入库

### 3.5 Attachment

```kotlin
sealed interface AttachmentState {
    data object None : AttachmentState
    data class Loaded(
        val jobId: JobId,
        val trackKey: TrackKey,
        val files: StemFiles,
    ) : AttachmentState
}
```

### 3.6 派生 View

```kotlin
data class SeparationView(
    val phase: Phase,                 // UI 文案
    val canToggle: Boolean,
    val canExport: Boolean,
    val canBackfill: Boolean,
    val progressPercent: Int?,
    val backfillPercent: Int?,
    val pendingBackfillMs: Long,
    val separationOn: Boolean,
)
```

UI 只接收 View/Snapshot；不再接收 7 个独立进度参数。

---

## 4. 事件与迁移

### 4.1 事件分类

```kotlin
sealed interface SeparationEvent {
    data class User(val intent: SeparationIntent) : SeparationEvent
    data class Domain(val event: DomainEvent) : SeparationEvent
    data class External(val event: PlaybackExternalEvent) : SeparationEvent
    data class Timeout(val jobId: JobId, val operation: OperationKind) : SeparationEvent
}
```

- **User**：Start、Cancel、Attach、Detach、BackfillNow、Export、ClearCache。
- **Domain**：SourceReady、CoverageUpdated、JobFinished、JobFailed、JobStopped、StemsLoaded、StemsLoadFailed、SnapshotRestored。
- **External**：TrackChanged、PlaybackEnded、ServiceLost、ProcessRestored。
- **Timeout**：每个 Operation 有看门狗。

### 4.2 迁移表（核心）

| 当前 | 事件 | 下一状态 | 效果 |
|---|---|---|---|
| Idle | Start | Preparing(Decoding) | StartDecode(jobId) |
| Preparing | SourceReady | Running(Separating) | StartSeparation |
| Preparing | Cancel | Stopping(Cancelling) | RequestStop |
| Preparing | TrackChanged / PlaybackEnded | Stopping(Cancelling) | 丢弃旧 job |
| Running | CoverageUpdated | Running | 更新 coverage；达到阈值发一次 SwitchToStems |
| Running | Cancel | Stopping | RequestStop |
| Running(EDGE) | TrackChanged / PlaybackEnded | Stopping | 取消 |
| Running(FULL) | TrackChanged / PlaybackEnded | 保持 Running；attachment 解除 | 任务可后台继续（待确认 D1） |
| Running | JobFinished | Finished | 停止前台；保留结果/缓存 |
| Running | BackfillNow | Running(Backfilling) | 重发 edge from=0 |
| Stopping | JobStopped | Idle 或 Preparing(pendingStart) | 启动/清理 |
| Stopping | Start | Stopping(pendingStart=新请求) | 不并发启动 |
| Finished | Attach(currentTrack) | Finished + Loaded | LoadStems(jobId) |
| Finished | TrackChanged | 保持 Finished；attachment=None | 不自动切旧 results |
| Finished | StemsLoaded | Finished + Loaded | 发 SwitchToStems 一次 |
| Failed | Start/Retry | Preparing | 新 jobId |
| any | ServiceLost | Failed/Stopping | 按是否可恢复处理 |
| any | ProcessRestored | Restoring→Idle/Failed | 查缓存/服务后对齐 |

### 4.3 优先级与合并

- `TrackChanged` 优先于尚未执行的 Start；旧 job 的 effect 结果按 `jobId` 丢弃。
- 同一 `jobId` 的重复 Cancel 幂等。
- `pendingStart` 只保留最新 Start；不排队多个。
- 进度事件不进队列，只更新 coverage；避免 UI 高频事件阻塞。
- 自动切轨 effect 每个 `(jobId, trackKey)` 最多一次；失败不自动重试，用户可再点开关。

### 4.4 超时

- `Cancelling`：2s 未返回 → 记 Failed/回收，不进入 Idle 假象。
- `LoadingStems`：5s 未返回 → Failed，恢复原曲。
- `Decoding/Separating`：心跳超时（例如 10s 无进度）→ 标记 stalled；不要误杀正常长推理，需按 operation 配不同阈值。

---

## 5. Effect

```kotlin
sealed interface SeparationEffect {
    data class StartDecode(val request: StartRequest) : SeparationEffect
    data class CancelJob(val jobId: JobId) : SeparationEffect
    data class LoadStems(val jobId: JobId, val files: StemFiles) : SeparationEffect
    data class DetachStems(val reason: DetachReason) : SeparationEffect
    data class Export(val jobId: JobId) : SeparationEffect
    data class Persist(val snapshot: SeparationSnapshot) : SeparationEffect
    data class Notify(val message: Message) : SeparationEffect
    data class StopForeground(val jobId: JobId) : SeparationEffect
}
```

- Effect 只能发事件回来，不能直接改 Snapshot。
- `LoadStems` 结果必须带 `jobId/trackKey`；track 不匹配则丢弃。
- 自动切轨 = `LoadStems` 成功后的 effect；不再由 UI 推导。

---

## 6. 所有权与集成

### 6.1 新增 `SeparationController`

- 持有 `StateFlow<SeparationSnapshot>`。
- 单一 `Channel<SeparationEvent>` + actor 协程。
- `reduce()` 纯函数；所有 Effect 分发到子协程。
- 现有 `SeparationRepository` 改为执行器/引擎适配，不再对外暴露多份 State。
- `EdgeSeparationSession` 保留为播放闸门/引擎会话，但状态由 Controller 读取/派生，避免两份 lifecycle。

### 6.2 播放侧

- `PlaybackService` 发 `TrackChanged`、`PlaybackEnded`、`ServiceLost`。
- `PlayerViewModel` 保留播放状态与 `Attachment` 的加载动作，不再存 `edgePercent`/`backfillPercent`/`separationOn` 的独立副本。
- `AppShell` 只发事件、渲染 Snapshot；不负责自动切轨判断。

### 6.3 UI

`SeparationScreen` 参数收敛为一个 `SeparationView`（或 Snapshot）：

- 不再传 `edgePercent`、`backfillPercent`、`pendingBackfillMs`、`separationOn`。
- 按钮 enabled 由 `canToggle/canExport/canBackfill` 派生。
- 文案由 `phase` 派生。

---

## 7. 测试

### 7.1 纯 reducer

- 每个 `(state, event)` 迁移表测试。
- 不变量：
  - 同时最多一个 active job / native worker。
  - 旧 `jobId` 事件不改变当前状态。
  - 同一 job 自动切轨最多一次。
  - `Loaded.trackKey == currentTrackKey` 才允许 `separationOn`。
  - Cancel 最终到达 Idle/Failed（有界事件内）。
- 交错场景：
  1. Cancel → TrackChanged → JobStopped → Start
  2. TrackChanged 在 LoadStems 未完成时到达
  3. PlaybackEnded → 自动下一首 → 旧 edge JobFinished
  4. fast path 在一个调度周期内 Finished
  5. ServiceLost/ProcessRestored
  6. 取消解码期间再点开始（pendingStart latest wins）

### 7.2 Controller 集成

- 用 fake decoder/separator/stem loader，验证 effect/事件顺序。
- 确保不并发启动 native。

### 7.3 真机验收

- v0.2.13 已修 fast path 场景必须继续通过。
- 2-stem edge：开始→切多轨→取消→切歌→再开始。
- 4-stem：长任务中下一首（按 D1 策略）、返回原曲、导出。
- 播放自然结束/自动下一首。
- 旋转/进程重建/Service 断连。
- R8 release 同样的 smoke。

---

## 8. 迁移计划

分支 `refactor/separation-state-model`，按提交粒度推进：

1. **文档**（本文件）：状态/事件/迁移表定稿。
2. **纯模型**：`SeparationSnapshot`、`SeparationEvent`、`reduce()`、不变量测试；不接 UI。
3. **Controller**：Channel/actor + fake effects 集成测试。
4. **适配**：`SeparationRepository` 内部改为 effect 执行器；旧 `State` 先保留 adapter，行为不变。
5. **UI 迁移**：`AppShell`/`PlayerViewModel`/`SpikeViewModel`/`SeparationScreen` 改用 Snapshot/View；删重复字段。
6. **清理**：移除旧 `EdgeSession.State`/`Repository.State` 重复部分；更新文档/测试。
7. **验收**：JVM + R8 release + 真机场景；通过后合 `main`，再决定版本号（建议 v0.2.14）。

**合并门槛**：

- 所有 reducer/交错测试通过。
- 现有 374 JVM + 新增测试通过。
- R8 release 构建、安装、2/4 轨/edge/导出 smoke 通过。
- fast path、cancel→next、playback end 三条场景真机通过。
- 任一失败：不合 main；在分支修正或回退。

---

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| 重构面大，回归难 | 纯模型先行；adapter 过渡；每阶段可回滚 |
| native 不可中断 | Stopping + 超时；不并发 worker；pendingStart 排队 |
| 播放/分离耦合 | 用事件桥；只传 trackKey/jobId；播放状态机不动 |
| 生命周期/进程恢复 | 只持久化最小信息；恢复时查 cache/service，不假装 active |
| UI 改动引发视觉回归 | View 派生；保持现有文案/阈值；真机截图对比 |
| 分支长期偏离 | 定期 rebase main；合并前统一验收 |

---

## 10. 待确认决策

- **D1**：`FULL` 任务在换曲/自然结束时是否继续后台跑？
  - 建议：`EDGE` 取消；`FULL` 继续并只解除 attachment（完成结果按 `trackKey` 缓存）。这样不会浪费已跑的长任务，也不会自动切到旧歌。
- **D2**：自动下一首是否总是取消 edge？
  - 建议：是；edge 与当前播放位置绑定，换曲后无意义。
- **D3**：进程重建时 active job 如何处理？
  - 建议：不恢复 active job；有完整缓存则恢复为 Finished，否则 Idle/Failed，由用户重试。
- **D4**：`Gate` 是否保留？
  - 建议：保留为播放侧闸门；由 Controller 读 coverage/播放位置派生，不复制到 `PlayerState`。
- **D5**：Stopping 期间用户点开关？
  - 建议：不进入新 job；记录 pending intent，Worker 释放后按最新 intent 执行。

---

## 11. 本分支边界

- 只改分离领域与其 UI 接线；不改播放算法、不改 native、不改 R8。
- 不直接删旧字段，先 adapter；删除放在阶段 6。
- 不在本分支发 release；合入 `main` 后再走正常发布流程。
- 旧 private 仓库、NCM/AVS3、AGP 不在本分支范围。
