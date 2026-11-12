# 分离状态模型重构：需求与设计稿

> 状态：**M3 已完成（Domain 接管分离状态/UI/自动切轨），待 M4 验收合并**
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

## 0.1 本次目标（2026-11-13 定稿）

### 目标级别：Level 2（领域收口，不重写播放器）

本次做到：

1. 分离领域有**唯一 Snapshot + 唯一写者**（Controller/reducer）。
2. 中间态（Decoding/Cancelling/LoadingStems/Backfilling）成为正式 Operation。
3. 换曲、播放结束、Service 丢失、进程恢复等外部事件进入迁移表。
4. UI 只读派生 View；`PlayerState`/`SpikeViewModel`/`SeparationScreen` 不再各自保存
   `edgePercent`/`backfillPercent`/`pendingBackfillMs`/`separationOn`/`lastStems` 等副本。
5. 自动切轨改由 Effect 触发，fast path/cancel/next 等场景可重复验证。
6. 保留现有音频、分离算法、R8、播放器 UI 行为；只实现已确认的 D1（FULL 后台继续、
   EDGE 取消）这一项行为变化。

**明确不做（Level 3）**：

- 不重写 `PlaybackService`/`SessionStateMachine` 的播放状态机。
- 不做分离独立进程/可强杀 native worker。
- 不做进程死亡的 active job 恢复；只做 Idle + cache fast path。
- 不重做 UI 视觉；不新增 NCM/AVS3/AGP/R8 工作。
- 不追求“删除所有旧类”；`EdgeSeparationSession` 可以保留为引擎/闸门内部实现。

### 里程碑

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| M0 | 本文档 | 决策/边界/迁移表定稿 |
| M1 | 纯模型 + reducer + 测试 | ✅ 已完成：`spike/state/` + 50 条 reducer 测试；全量 424/0 |
| M2 | Controller + adapter | ✅ 已完成：Channel actor + fake effects；全量 431/0 |
| M3 | UI/VM/PlayerState 迁移 | ✅ 已完成：Domain 接管；433/0；真机 edge/4-stem 通过 |
| M4 | 验收 + 合并 | JVM/R8/真机场景全过；合 main 后发 v0.2.14 |

### M1 实现记录（2026-11-13）

- `app/src/main/java/pub/miku/neuralstem/spike/state/SeparationState.kt`
  - `TrackKey`、`SeparationKind`、`Lifecycle`、`Operation`、`Coverage`、
    `JobState`、`AttachmentState`、`SeparationSnapshot`、Event/Effect 定义。
- `app/src/main/java/pub/miku/neuralstem/spike/state/SeparationReducer.kt`
  - 纯函数 `reduce(Snapshot, Event) -> ReduceResult`；不依赖 Android。
  - 覆盖启动/取消/让位、EDGE 自动 attach、FULL 手动、换曲/播放结束、
    Service 丢失/进程恢复、加载竞态、超时、缓存/导出、终态栅栏。
- `app/src/test/java/pub/miku/neuralstem/spike/state/SeparationReducerTest.kt`
  - 50 条迁移/交错/不变量测试；全量 JVM 424/0。
- 本阶段**未接** UI、Repository、PlaybackService；旧状态仍是线上真源。

### M2 实现记录（2026-11-13）

- `app/src/main/java/pub/miku/neuralstem/spike/state/SeparationController.kt`
  - `Channel<SeparationEvent>`（UNLIMITED）+ 单 actor；
  - 只有 actor 写 `StateFlow<SeparationSnapshot>`；
  - `onEffect` 执行 Effect，结果必须再以 Event 回来；Effect 失败不杀状态机。
- `app/src/test/java/pub/miku/neuralstem/spike/state/SeparationControllerTest.kt`
  - 7 条：事件串行、Effect 顺序、取消/JobStopped、旧 job 事件、
    换曲、100 条事件不丢、close 后 send 安全。
- 本阶段仍未接 Repository/UI；M3 把真实 Effect executor 接进来。

### M3a 实现记录（2026-11-13）

- `app/src/main/java/pub/miku/neuralstem/spike/state/SeparationDomain.kt`
  - 进程级单例；持有 Controller/repo/EdgeSession；
  - StartRequest → 旧 Repository（EDGE/SPHERA/SPLEETER4/DEMUCS）；
  - repo/EdgeSession 回调 → Domain SourceReady/Coverage/Finished/Failed/Stopped；
  - PlayerBridge 接口（M3b 接播放侧）；
  - 当前曲通过 AppShell `TrackChanged` 同步。
- `SpikeViewModel` 的 start/startOption/startEdge/cancel 已改走 Domain；
  旧 UI/自动切轨仍由旧 repo 状态驱动，行为保持不变（真机 edge 启动验证）。
- 新增 M3a 后全量 JVM 仍 431/0（集成层由真机验证）。

### M3 实现记录（2026-11-13）

- `SeparationDomain`
  - 进程级 Controller/repo/EdgeSession 适配；Effect → 旧 repo；
  - Domain 自动切轨 Effect 经 `PlayerBridge` 落到 `PlayerViewModel.loadMultiTrack`；
  - `SeparationEdgeUi` 派生：edgePercent/fraction/starved/gray/backfill/pending；
  - 当前曲 `TrackChanged`、播放结束、取消/失败 detach 已接。
- `SpikeViewModel`
  - 启动/取消走 Domain；Domain snapshot → SeparationTask/State/export/lastEdgeDone；
  - 删除旧 repo→UI 订阅；VM 重建从 Domain snapshot 恢复。
- `PlayerViewModel` / `PlayerScreen` / `SeparationScreen`
  - 删除 PlayerState 的 edgePercent/edgeStarved/edgeBeforeStart/edgeStartMs/
    edgeSeparatedFraction/backfillPercent/pendingBackfillMs/separationOn/stemsAvailable；
  - 统一读 `separationEdge` + `isMultiTrack`；旧自动切轨 effect disabled。
- 真机：edge 缓存命中自动切多轨；4-stem 启动/缓存命中；取消/换曲路径待 M4 再全量验收。
- JVM 433/0（新增 coverage frontGap、JobMetrics 传播测试）。

### 本次“完成”的判据### 本次“完成”的判据

- `SeparationController` 是分离状态的唯一写者；grep 不再出现多份分离 UI 字段。
- reducer 覆盖 `State × Event` 迁移表；交错测试覆盖：
  - fast path 一帧完成
  - Cancel → TrackChanged → JobStopped → Start
  - PlaybackEnded → 自动下一首
  - LoadStems 中切歌/关开关
  - FULL 后台继续
  - Stopping 超时/native 卡死
- 现有 JVM 374 + 新增测试全过；R8 release 构建/安装/2/4 轨/edge/导出 smoke 通过。
- 真机验收通过后才合 `main`；不通过则分支保留，`main` 继续 v0.2.13。

### 不追求的目标

- 不追求“状态数量最少”或引入通用状态图框架。
- 不为了抽象而抽象；每个 Operation/字段必须对应一个真实的外部事件或用户可见行为。

### 停止/回退条件

- 如果 M2 的 adapter 无法在两次迭代内做到行为对齐，停止并保留分支，不硬合。
- 如果 M3 发现需要改播放主状态机才能继续，缩小到 M2 + 局部字段派生，
  不强行完成全量迁移。
- 任何一步导致 fast path/cancel/next 场景回归且 24h 内无法定位，回退该步。

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
| 开始 full | 从 0 或指定位置开始；track 改变时继续后台（D1） |
| 取消 | 任意阶段可请求；不可中断 native 返回后进入 Idle；可排队下一次 Start |
| 切分离开关 | 只有当前 track 有可用 finished job 才成功；否则提示“无分离结果” |
| 分离进行中开开关 | 无 attachment 时拒绝并提示“分离中”；有 attachment 时可以关；不允许把旧 finished 结果挂到正在跑的新 job 上 |
| 补齐前段 | 重新发起 edge（from=0），复用 manifest 只补缺口 |
| 导出 | 只有 finished + complete 才可用；partial 不可导出 |
| 清缓存 | active job 或已 attach stems 时**拒绝**并提示先取消/先关开关；其余情况清 manifest/WAV 并清结果 |
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
attachment is Loaded
&& attachment.loadedTrackKey == currentTrackKey
```

注意：**attachment 可以比 active job 更旧**。例如用户正在播旧的 edge stems，又发起
“补齐前段”的新 EDGE job；在新 job 达到 gate 之前，旧 attachment 仍应继续播，
不能因为 `jobId` 变了就把开关误判成“原曲”。`loadedJobId` 只用于 provenance/自动切轨去重，
不参与“当前是否多轨”的判定。

这解决“上一首分离完成、下一首正在播”“完成但未切多轨”“新 job 覆盖旧 attachment”的竞态。

### 2.6 完成后的行为

- **仅 EDGE**：当前 track 匹配 + gate 达到 Playable/Finished 时，发一次 `LoadStems(jobId)` effect；成功后 `SwitchToStems` 一次；同一 `jobId` 不重复。
- **FULL 不自动 attach**：即使当前 track 匹配，也只发 `NotifyStemsAvailable`，等用户点开关。
- 当前 track 不匹配：只保留 finished/cache，不自动切。
- 用户返回原曲（`TrackChanged` 回到原 track）且 job 仍 finished：允许手动 attach；不自动。
- 自动切轨失败：保留原曲、提示、记录错误，不把 `lastStems` 留成半初始化。
- 用户在同一 job 内显式关掉开关 → 之后该 job 不再自动 attach；手动打开才恢复。

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

`Loaded.jobId` 是**加载来源**，不是当前 active job 的强绑定；同一 track 的新 job 可以替换它。

Snapshot 里额外保存两个“用户意图”标记（不是另一份分离状态）：

```kotlin
data class SeparationSnapshot(
    // ...
    /** 用户显式关掉开关后，本 job 不再自动 attach。 */
    val autoAttachSuppressedFor: JobId? = null,
    /** 本 job 已自动尝试过 attach；失败后不每个 coverage tick 重试。 */
    val autoAttachAttemptedFor: JobId? = null,
)
```

规则：

- 用户 `Detach` → `autoAttachSuppressedFor = 当前 jobId`。
- 用户手动 `Attach` → 清空 suppression 和 attempted。
- 新 job → 两个标记都清空。
- `FULL` 本来就不自动 attach，这两个标记主要用于 `EDGE`。

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
| Running(EDGE) | CoverageUpdated | Running | 更新 coverage；达到阈值发一次 LoadStems |
| Running(FULL) | CoverageUpdated | Running | 只更新 coverage；不自动 attach |
| Running | Cancel | Stopping | RequestStop |
| Running(EDGE) | TrackChanged / PlaybackEnded | Stopping | 取消 |
| Running(FULL) | TrackChanged / PlaybackEnded | 保持 Running；attachment 解除 | 任务后台继续；不自动切（D1 已确认） |
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
- `CoverageUpdated` 在 controller 入口做节流/合并（例如最多 10Hz 或进度变化 ≥1% 才入队），
  用户命令、取消、外部事件、终态事件绝不丢弃/合并；避免进度事件淹没切歌事件。
- **终态栅栏**：一旦某 jobId 进入 `Finished/Stopping/Failed`，同一 jobId 的
  `CoverageUpdated/Progress` 全部忽略；终态后到达的迟到结果不得把状态拉回 Running。
- 事件队列的优先级：External/TrackChanged > User Intent > Operation Result > Progress。
  同优先级保持入队顺序；不阻塞 actor。

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
- `SessionStateMachine` 继续拥有**播放**状态；其现有 `separation` 任务字段在阶段 4 改为 adapter，
  阶段 6 删除对 UI 的暴露。不能让 Controller 和它同时成为分离真源。

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
  - `Loaded.trackKey == currentTrackKey` 才允许 `separationOn`（loadedJobId 可以旧于 active job）。
- Backfill/新 job 进行中，旧 attachment 仍可播放；新 job 达到 gate 才替换。
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
| FULL 后台继续造成 CPU/热/音频 underrun | 单 worker；不因新曲再起 job；FGS 通知明确旧曲；用户可取消 |
| 分支长期偏离 | 定期 rebase main；合并前统一验收 |

---

## 10. 已确认决策（2026-11-13）

以下为定稿行为，代码按此实现，不再保留“待确认”。

### D1：EDGE 取消，FULL 后台继续

- `EDGE` 与当前播放位置强绑定；`TrackChanged` / `PlaybackEnded` → 取消。
- `FULL`（2/4 轨完整分离）在换曲/自然结束时**继续跑**，只解除 UI attachment。
- 完成后按 `trackKey` 缓存；不自动切到旧歌；用户回到原曲后可手动开开关。
- 换曲后 `FULL` 的完成态不进入当前 UI 的“最近完成”文案；通知/进度按 `trackKey` 过滤，避免误导。
- 用户对**新曲**发起 Start 时，旧 FULL 任务被取消（单 worker，latest wins）；旧缓存保留，可重新从 manifest 续算。
- FULL 后台继续意味着 FGS 通知保留旧曲标题；只允许一个分离 worker，避免 CPU/热/内存叠加。

### D2：自动下一首总是取消 EDGE

- `PlaybackEnded` 与随后的 `TrackChanged` 都发取消；重复取消幂等。
- 不自动为新曲开始分离；只清除 attachment，等用户明确点。

### D3：进程恢复不恢复 active job

- active job 不持久化恢复；进程死亡后按 Idle 处理。
- 有完整 manifest/WAV → 后续 Start 命中 fast path，恢复为 Finished。
- `Attachment` 由 PlaybackService 实际载入的 stems 决定；Service 重建后重新对账，不假装已加载。

### D4：Gate 留在播放侧

- `Gate` 仍表示“播放能不能走”，由 Controller 读 `coverage + playFrame + startFrame` 派生。
- 不把 `edgePercent`/`backfillPercent`/`separationOn` 复制到 `PlayerState`。
- 播放位置变化只发 `PositionChanged`（或由 Gate 计算读取），不直接改 job lifecycle。

### D5：Stopping 期间不并发启动

- `Stopping` 中收到 Start：写入 `pendingStart`（latest wins），不启动第二个 worker。
- 收到 `JobStopped` 后再按 `pendingStart` 进入 `Preparing`。
- 收到 `TrackChanged`：若 `pendingStart` 的 trackKey 是旧曲，丢弃；不自动为新曲启动。

### 其他确认

- **EDGE 自动切多轨；FULL 不自动切**。FULL 完成后由用户点开关（避免长任务完成时突然切走）。
- **TrackKey 用原始曲 uri + duration**，不用 edge 临时文件名；temp/cache 文件只作为实现细节。
- **BackfillNow 视为一次新的 EDGE job（from=0）**，复用 manifest 续算；旧 finished/attachment 保留到新 job 达到 gate 再切换。
- **ClearCache 在 active job 时拒绝**（提示先取消），避免删正在写的 WAV。
- **Export 只对当前曲、Finished+complete 的 attachment/job 可用**；partial 一律禁用。

---

## 11. 边界情况与处理

### 11.1 换曲与播放结束

| 场景 | 处理 |
|---|---|
| EDGE Preparing 时切歌 | 取消解码；JobStopped → Idle；不发 Switch |
| EDGE Running 时切歌 | 取消；旧 JobFinished 即使到达也按 jobId 丢弃；attachment 解除 |
| EDGE Running 时播放自然结束 | 等同切歌；取消；不等自动下一首 |
| FULL Running 时切歌 | 继续 job；attachment 解除；Service 通知保留“正在分离 <旧曲名>” |
| FULL Running 时播放自然结束 | 继续；不自动切；无下一首也不取消 |
| FULL 完成时当前已是别的歌 | Finished 保留在 snapshot；不自动切；不弹当前歌的完成提示 |
| 回到原曲时 FULL 已 Finished | 不自动 attach；提示“已有分离结果”；用户点开关 → LoadStems |
| 回到原曲时 FULL 仍在 Running | 不自动 attach；等完成或用户取消；Gate 不参与当前播放 |
| TrackChanged 但 trackKey 相同（reload/seek/重播） | 视为无变化；不取消 job、不清 attachment、不重新 auto attach |

### 11.2 加载/切轨竞态

| 场景 | 处理 |
|---|---|
| LoadStems 未完成时切歌 | `LoadingStems` 结果按 jobId/trackKey 丢弃；attachment=None |
| LoadStems 失败 | attachment=None；lastError；原曲继续；用户可再点开关重试 |
| 自动切轨 effect 发出后切歌 | effect 结果按 trackKey 丢弃；不出现“新歌播旧 stems” |
| 用户快速连点开关 | Attach/Detach 串行；同一 job 幂等；不重复 LoadStems |
| 关闭开关时 edge 还在跑 | 只 Detach；不取消分离任务（任务仍可继续；这是当前语义） |
| 关闭开关时 FULL 还在跑 | 只 Detach；任务继续 |
| EDGE 自动 LoadStems 中用户关开关 | 记 `autoAttachSuppressedFor=jobId`；LoadStems 结果回来后不 attach；若已 attach 则 Detach |
| 自动 attach 失败后 coverage 继续更新 | `autoAttachAttemptedFor` 阻止每个 tick 重试；用户手动开关可重试 |
| 手动 Attach 时 LoadStems 中切歌 | 结果按 trackKey 丢弃；attachment 保持 None |
| LoadStems 中再次点开关 | 串行化；关闭则抑制后续自动 attach，打开则等结果 |

### 11.3 取消与启动交错

| 场景 | 处理 |
|---|---|
| 解码中取消 | Operation.Decoding → Cancelling；解码协程可取消 → JobStopped → Idle |
| native 分离中取消 | Operation.Separating → Cancelling；等待 native 返回；不并发新 worker |
| 取消中再点开始 | pendingStart=最新请求；旧 job 真实停止后才 Preparing |
| 取消中切歌 | 旧 pendingStart 丢弃；取消完成后 Idle，不自动为新曲开始 |
| 取消中播放结束 | 同切歌；取消幂等 |
| 取消超时 | 保持 Stopping + `workerAlive=true`；UI 显示“正在停止”，禁止 Start；不伪造 Idle |
| 两次 Cancel | 幂等；只发一次 RequestStop |

### 11.4 完成与缓存

| 场景 | 处理 |
|---|---|
| edge fast path（缓存完整） | Preparing → Finished 可在一个调度周期内完成；reducer 仍按事件顺序走；自动切轨只靠 `SwitchToStems(jobId)` |
| fast path 后用户点开关 | attachment 已 Loaded 则 Detach/Attach；未 Loaded 时按“无结果”处理，不静默 |
| FULL 命中完整缓存 | Finished；不自动切；用户点开关后 LoadStems |
| 部分 coverage + 成本门控 | Finished(complete=false)；Export 禁用；显示 pendingBackfill；BackfillNow 新 job |
| 清缓存时有 active job | 拒绝并提示；不删除正在写的文件 |
| 清缓存时只有 finished 结果 | 允许；先 Detach，再清 manifest/WAV；结果置 None |
| 清缓存时已 attach stems | 拒绝并提示“先关闭分离开关”；避免删掉正在播放的 WAV |
| 导出 partial/无权 | 禁用；不产生静默 WAV |

### 11.5 服务/进程

| 场景 | 处理 |
|---|---|
| PlaybackService 丢失 | 只影响播放/attachment：归一 None；分离 job 继续（FULL）或按 D1 取消（EDGE） |
| SeparationService 丢失 | active job → Failed；缓存保留；播放侧原曲/现有 stems 按实际状态对账 |
| 进程死亡后恢复 | Idle；不恢复 active job；finished 结果由 cache fast path 重新发现 |
| 旋转/配置变化 | 只重建 UI；不重跑 job；attachment 从 Service 对账 |
| 通知/FGS 被系统杀 | 与 ServiceLost 相同；不假设后台任务还活着 |

### 11.6 用户操作

| 场景 | 处理 |
|---|---|
| 无结果点开关 | 返回 false / 提示“先分离”；不自动开始 |
| 有结果但 track 不匹配 | 不 attach；提示结果属于另一首 |
| 分离中导出 | 禁用 |
| 分离中再次点同一档 | 视为重复 Start；若 operation 非空则 pendingStart 覆盖，不并发 |
| 另一档 Start | 取消旧 job，pendingStart 新档（latest wins） |
| 空曲/无 currentUri | Start 返回 invalid；不创建 job |
| 分离中无 attachment 时开开关 | 拒绝，提示“分离中”；不创建 pending attach |
| 分离中有 attachment 时开/关 | 关闭允许；打开已加载则幂等 |
| 有 active FULL + 当前新曲点开关 | 拒绝（结果属于旧曲）；不把旧 finished 挂到新曲 |
| 清缓存时有 active job（哪怕属于旧曲） | 拒绝；先取消 |
| 清缓存时已 attach | 拒绝；先关开关 |

---

## 12. 超时、失败与不可中断 native

### 12.1 超时策略

| Operation | 阈值 | 超时动作 |
|---|---|---|
| LoadingStems | 5s | Failed；attachment=None；原曲继续；可手动重试 |
| Decoding | 可取消；按心跳 | 取消/失败；JobStopped → Idle |
| Separating 心跳 | 15s 无进度 | 标记 stalled；不立即杀死 native；UI 提示 |
| Cancelling | 2s | 保持 Stopping，标记 `workerAlive`；禁止新 Start；继续等 |
| Export | 10s | Failed；MediaStore 回滚；不残留 pending |

### 12.2 native 卡死

- 不做“超时后假装 Idle”：那会启动第二个 writer，破坏音频。
- `Stopping(workerAlive=true)` 是合法长时间状态；UI 显示“正在停止分离”，Start 禁用。
- 若用户强杀 App，进程结束即回收；下次启动不恢复 active job。
- 需要彻底解决时可把分离放独立进程/可杀死 worker，列为后续增强，不在本次范围。
- FULL 后台继续时也要保持单 worker；播放新歌不会新开分离，避免 CPU/热/内存叠加。
  FGS 通知必须带 track 标题，用户能看到“正在分离旧曲”。

### 12.3 失败语义

- `Failed` 必须带 `jobId/trackKey/message`；旧 job 的失败不能覆盖新 job。
- 失败不自动重试；用户点 Start 生成新 jobId。
- UI 只显示当前 track 的失败；旧曲失败只写日志/缓存状态。

---

## 13. 测试补充（在上述矩阵之外）

- FULL 后台继续：切歌后 job 仍 Running，切回原曲不自动 attach。
- FULL 完成在别的 track：不 Switch、不弹当前歌完成提示。
- EDGE 取消 + 自动下一首的重复事件幂等。
- pendingStart 在 track change 后被丢弃。
- native cancel 超时：状态保持 Stopping，Start 被拒绝，无第二个 worker。
- LoadStems 与 TrackChanged 交错：旧结果丢弃。
- 清缓存/导出在 active/partial/finished 三种状态的门控。
- 事件风暴/重复播放事件不产生重复 Switch。
- 用户关闭开关后 EDGE 不再自动 attach；手动打开可恢复。
- LoadStems 中 Detach/TrackChanged/再次 Toggle 的结果处理。
- 清缓存时 attachment/active 的门控。
- FULL 后台继续时通知/进度只属于旧 track，新曲 UI 不显示。
- 同一 trackKey 的 TrackChanged 不取消 job。
- 终态后迟到的 CoverageUpdated 不会把状态拉回 Running。
- 分离中无 attachment 开开关被拒绝；旧 finished 不挂到新 job。

---

## 14. 本分支边界

- 只改分离领域与其 UI 接线；不改播放算法、不改 native、不改 R8。
- 不直接删旧字段，先 adapter；删除放在阶段 6。
- 不在本分支发 release；合入 `main` 后再走正常发布流程。
- 旧 private 仓库、NCM/AVS3、AGP 不在本分支范围。
- 本文档为定稿；实现中发现需要改行为，先改文档再改代码。
