package pub.miku.neuralstem.spike.state

/**
 * 分离领域纯状态模型（M1）。
 *
 * 约束：
 * - 本文件不依赖 Android，可被 JVM 单测直接驱动。
 * - Snapshot 由 reducer 单写；UI/Service/协程只能发 Event。
 * - `job == null` 表示 Idle，不再用独立 Idle lifecycle 冗余表达。
 * - 旧 `SeparationRepository`/`EdgeSeparationSession`/`PlayerState` 在 M3 前只做 adapter。
 */

/** 稳定曲目标识：原始 uri + 时长；temp/cache 文件名不参与身份。 */
data class TrackKey(
    val uri: String,
    val durationMs: Long = 0L,
    val contentHash: String? = null,
)

enum class SeparationKind {
    EDGE,
    FULL_2,
    FULL_4,
}

/** 任务生命周期。Idle 由 `job == null` 表达。 */
sealed interface Lifecycle {
    data object Preparing : Lifecycle
    data object Running : Lifecycle
    data object Stopping : Lifecycle
    data object Finished : Lifecycle
    data class Failed(val message: String) : Lifecycle
}

/** 进行中的显式操作；中间态不再用布尔拼。 */
sealed interface Operation {
    data class Decoding(val jobId: Long) : Operation
    data class Separating(val jobId: Long) : Operation
    data class Backfilling(val jobId: Long) : Operation
    data class LoadingStems(val jobId: Long) : Operation
    data class Cancelling(
        val jobId: Long,
        val pendingStart: StartRequest? = null,
        /** native 取消超时后仍存活；此时禁止启动第二个 worker。 */
        val workerAlive: Boolean = false,
    ) : Operation
}

data class StartRequest(
    val kind: SeparationKind,
    val trackKey: TrackKey,
    /** 分离起点（帧）；edge 从当前位置开始，full 一般为 0。 */
    val startFrame: Long = 0L,
    val fromPositionMs: Long = 0L,
    val trackTitle: String? = null,
)

/** 已覆盖区间/水位线。五阶段文案由 source + coverage + operation 派生。 */
data class Coverage(
    val totalFrames: Long = 0L,
    val startFrame: Long = 0L,
    val coveredRanges: List<LongRange> = emptyList(),
    val watermarkFrame: Long = 0L,
    val complete: Boolean = false,
) {
    init {
        require(totalFrames >= 0L) { "totalFrames < 0" }
        require(startFrame >= 0L) { "startFrame < 0" }
        require(watermarkFrame >= 0L) { "watermarkFrame < 0" }
        require(coveredRanges.all { it.first >= 0L && it.last >= it.first }) {
            "invalid covered range"
        }
    }

    val hasPreview: Boolean get() = coveredRanges.isNotEmpty()

    /**
     * 从 0 开始第一个未覆盖帧；用于“补前段”的缺口。
     * 注意 startFrame 本身可能被前面的 backfill 覆盖，因此不能直接用 startFrame。
     */
    val frontGapFrames: Long
        get() {
            if (startFrame <= 0L) return 0L
            if (coveredRanges.isEmpty()) return startFrame
            // 只关心 [0, startFrame) 的前段是否被补过；第一个覆盖区从 0 开始才算补上。
            val firstCovered = coveredRanges.minOf { it.first }.coerceAtLeast(0L)
            return minOf(startFrame, firstCovered)
        }

    /** 已完成覆盖的帧数（不要求连续；用于进度）。 */
    val coveredFrames: Long
        get() = coveredRanges.sumOf { it.last - it.first + 1L }
}

data class StemFiles(
    /** 顺序由 kind 决定：2-stem = vocals/accompaniment；4-stem = vocals/drums/bass/other。 */
    val paths: List<String>,
) {
    init {
        require(paths.isNotEmpty()) { "empty stem files" }
    }
}

data class JobState(
    val jobId: Long,
    val trackKey: TrackKey,
    val kind: SeparationKind,
    val lifecycle: Lifecycle,
    val operation: Operation? = null,
    val coverage: Coverage = Coverage(startFrame = 0L),
    val files: StemFiles? = null,
    val trackTitle: String? = null,
) {
    val isTerminal: Boolean
        get() = lifecycle is Lifecycle.Finished || lifecycle is Lifecycle.Failed
}

sealed interface AttachmentState {
    data object None : AttachmentState
    data class Loaded(
        val jobId: Long,
        val trackKey: TrackKey,
        val files: StemFiles,
    ) : AttachmentState
}

data class FinishedJob(
    val jobId: Long,
    val trackKey: TrackKey,
    val kind: SeparationKind,
    val files: StemFiles,
    val coverage: Coverage,
)

data class SeparationSnapshot(
    /** 播放器当前曲；null = 未知/未加载。 */
    val currentTrackKey: TrackKey? = null,
    /** 当前任务；Finished/Failed 仍保留，便于手动 attach/导出。 */
    val job: JobState? = null,
    val attachment: AttachmentState = AttachmentState.None,
    val lastFinished: FinishedJob? = null,
    val lastError: String? = null,
    /** 用户显式关开关后，本 job 不再自动 attach。 */
    val autoAttachSuppressedFor: Long? = null,
    /** 本 job 已自动尝试过 attach；失败后不每个 coverage tick 重试。 */
    val autoAttachAttemptedFor: Long? = null,
    /** 下一个 jobId；由 reducer 分配，保证可重放/可测。 */
    val nextJobId: Long = 1L,
) {
    /** 派生：当前播放器是否真的加载了当前曲的 stems。 */
    val separationOn: Boolean
        get() = attachment is AttachmentState.Loaded &&
            attachment.trackKey == currentTrackKey

    val activeJob: JobState?
        get() = job?.takeIf { !it.isTerminal }
}

enum class ServiceKind { PLAYBACK, SEPARATION }

enum class OperationKind { DECODING, SEPARATING, BACKFILLING, LOADING_STEMS, CANCELLING }

enum class DetachReason { USER, TRACK_CHANGED, PLAYBACK_LOST }

// ==================== Events ====================

sealed interface SeparationEvent {
    sealed interface User : SeparationEvent {
        data class Start(val request: StartRequest) : User
        data class Cancel(val jobId: Long? = null) : User
        data class Attach(val jobId: Long? = null) : User
        data object Detach : User
        data object BackfillNow : User
        data object Export : User
        data object ClearCache : User
    }

    sealed interface Domain : SeparationEvent {
        data class SourceReady(
            val jobId: Long,
            val coverage: Coverage,
            val files: StemFiles? = null,
        ) : Domain

        data class CoverageUpdated(
            val jobId: Long,
            val coverage: Coverage,
            /** 由播放侧 Gate 计算后传入；M1 不复制 Gate 生命周期。 */
            val gateReady: Boolean = false,
        ) : Domain

        data class JobFinished(
            val jobId: Long,
            val files: StemFiles,
            val coverage: Coverage,
            val complete: Boolean,
        ) : Domain

        data class JobFailed(val jobId: Long, val message: String) : Domain
        data class JobStopped(val jobId: Long) : Domain
        data class StemsLoaded(val jobId: Long, val files: StemFiles) : Domain
        data class StemsLoadFailed(val jobId: Long, val message: String) : Domain
    }

    sealed interface External : SeparationEvent {
        data class TrackChanged(val trackKey: TrackKey) : External
        data object PlaybackEnded : External
        data class ServiceLost(val service: ServiceKind) : External
        data object ProcessRestored : External
        data object ConfigChanged : External
    }

    sealed interface Timeout : SeparationEvent {
        data class OperationTimeout(
            val jobId: Long,
            val operation: OperationKind,
        ) : Timeout
    }
}

// ==================== Effects ====================

sealed interface SeparationEffect {
    data class StartDecode(val jobId: Long, val request: StartRequest) : SeparationEffect
    data class StartSeparation(val jobId: Long) : SeparationEffect
    data class CancelJob(val jobId: Long) : SeparationEffect
    data class LoadStems(val jobId: Long, val files: StemFiles) : SeparationEffect
    data class DetachStems(val reason: DetachReason) : SeparationEffect
    data class Export(val jobId: Long) : SeparationEffect
    data object ClearCache : SeparationEffect
    data class StopForeground(val jobId: Long) : SeparationEffect
    data class Notify(val message: String) : SeparationEffect
}

data class ReduceResult(
    val snapshot: SeparationSnapshot,
    val effects: List<SeparationEffect> = emptyList(),
)

object SeparationMessages {
    const val BUSY = "分离进行中，暂不能操作"
    const val NO_RESULT = "无分离结果，需先分离"
    const val RESULT_OTHER_TRACK = "已有结果属于另一首曲"
    const val LOAD_FAILED = "分离结果加载失败"
    const val LOAD_TIMEOUT = "分离结果加载超时"
    const val DECODE_TIMEOUT = "解码超时"
    const val STALLED = "分离长时间无进度"
    const val CANCEL_SLOW = "正在停止分离，请稍候"
    const val CLEAR_BUSY = "请先取消分离并关闭分离开关"
    const val EXPORT_MISSING = "没有可导出的完整分离结果"
}
