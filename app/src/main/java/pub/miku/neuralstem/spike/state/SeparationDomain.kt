package pub.miku.neuralstem.spike.state

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pub.miku.neuralstem.spike.EdgeSeparationSession
import pub.miku.neuralstem.spike.SeparationRepository
import pub.miku.neuralstem.spike.SherpaSourceSeparation
import java.io.File

/** 分离 UI 的派生视图；PlayerState/SeparationScreen 不再各自存这些字段。 */
data class SeparationEdgeUi(
    val separationOn: Boolean = false,
    val edgePercent: Int? = null,
    val edgeSeparatedFraction: Float? = null,
    val edgeStarved: Boolean = false,
    val edgeBeforeStart: Boolean = false,
    val edgeStartMs: Long = 0L,
    val backfillPercent: Int? = null,
    val pendingBackfillMs: Long = 0L,
)

/**
 * Android 侧的分离领域适配层（M3a）。
 *
 * - 持有 [SeparationController]；UI/VM 通过它发事件；
 * - Effect 转发到旧 [SeparationRepository]（引擎）与 [SeparationPlayerBridge]（播放侧）；
 * - 旧 repo/EdgeSession 的回调翻译成 Domain 事件；
 * - 旧 UI 尚未切换前，本类只做“影子真源”，不改现有行为。
 */
class SeparationDomain private constructor(private val app: Application) {

    interface PlayerBridge {
        /** 真正把 stems 载入播放器；返回 false = 立即失败。 */
        fun loadStems(jobId: Long, files: List<File>): Boolean

        /** 退出多轨/解除 attachment。 */
        fun detachStems()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = SeparationRepository.get(app)
    private val edge = EdgeSeparationSession.get()

    private val controller = SeparationController(
        scope = scope,
        initial = SeparationSnapshot(),
    ) { effect -> execute(effect) }

    val snapshot: StateFlow<SeparationSnapshot> = controller.snapshot

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices

    private val _edgeUi = MutableStateFlow(SeparationEdgeUi())
    val edgeUi: StateFlow<SeparationEdgeUi> = _edgeUi

    @Volatile
    private var playerBridge: PlayerBridge? = null

    /** 自动切轨是否由 Domain 接管；AppShell 的旧 effect 据此走 fallback。 */
    @Volatile
    var autoSwitchEnabled: Boolean = false
        private set

    private var activeJobId: Long? = null
    private var sourceReady = false

    init {
        scope.launch {
            repo.state.collect { onRepoState(it) }
        }
        scope.launch {
            while (isActive) {
                delay(200L)
                tickEdge()
                publishEdgeUi()
            }
        }
    }

    fun setPlayerBridge(bridge: PlayerBridge?) {
        playerBridge = bridge
        autoSwitchEnabled = bridge != null
    }

    // ==================== Public events ====================

    fun start(request: StartRequest) {
        sourceReady = false
        activeJobId = null
        controller.start(request)
    }

    fun cancel(jobId: Long? = null) {
        controller.cancel(jobId)
    }

    fun attach(jobId: Long? = null) {
        controller.attach(jobId)
    }

    fun detach() {
        controller.detach()
    }

    fun backfill() {
        controller.send(SeparationEvent.User.BackfillNow)
    }

    fun export() {
        controller.send(SeparationEvent.User.Export)
    }

    fun clearCache() {
        controller.send(SeparationEvent.User.ClearCache)
    }

    fun onTrackChanged(trackKey: TrackKey?) {
        if (trackKey == null) return
        controller.send(SeparationEvent.External.TrackChanged(trackKey))
    }

    fun onPlaybackEnded() {
        controller.send(SeparationEvent.External.PlaybackEnded)
    }

    fun onPlaybackServiceLost() {
        controller.send(SeparationEvent.External.ServiceLost(ServiceKind.PLAYBACK))
    }

    fun onSeparationServiceLost() {
        controller.send(SeparationEvent.External.ServiceLost(ServiceKind.SEPARATION))
    }

    fun onProcessRestored() {
        controller.send(SeparationEvent.External.ProcessRestored)
    }

    // ==================== Effects ====================

    private fun execute(effect: SeparationEffect) {
        when (effect) {
            is SeparationEffect.StartDecode -> startEngine(effect)
            is SeparationEffect.StartSeparation -> Unit // repo.start 已负责真正启动
            is SeparationEffect.CancelJob -> repo.cancel()
            is SeparationEffect.LoadStems -> loadStems(effect)
            is SeparationEffect.DetachStems -> playerBridge?.detachStems()
            is SeparationEffect.Export -> _notices.tryEmit("导出由分离页处理")
            SeparationEffect.ClearCache -> {
                val n = repo.clearCache()
                _notices.tryEmit(if (n >= 0) "已清理 $n 个缓存" else "分离进行中，暂不能清缓存")
            }
            is SeparationEffect.StopForeground -> Unit // SeparationService 自己观察终态后 stopSelf
            is SeparationEffect.Notify -> _notices.tryEmit(effect.message)
        }
    }

    private fun startEngine(effect: SeparationEffect.StartDecode) {
        activeJobId = effect.jobId
        sourceReady = false
        val request = effect.request
        val file = request.sourcePath?.let(::File)
        if (file == null) {
            sendDomain(SeparationEvent.Domain.JobFailed(effect.jobId, "缺少输入文件"))
            return
        }
        try {
            when (request.backend) {
                SeparationBackend.EDGE ->
                    repo.startEdge(file, request.fromPositionMs)
                SeparationBackend.SPLEETER4 ->
                    repo.start4(file)
                SeparationBackend.DEMUCS ->
                    repo.startDemucs(file)
                SeparationBackend.SHERPA_2STEM ->
                    repo.start(
                        source = file,
                        engine = if (request.engineMode >= 0) request.engineMode
                        else SherpaSourceSeparation.MODE_SPLEETER,
                        uvrModelName = request.uvrModelName ?: "UVR-MDX-NET-Voc_Ft.onnx",
                        chunkOverrideFrames = request.chunkOverrideFrames,
                    )
            }
            Log.i(TAG, "startEngine job=${effect.jobId} backend=${request.backend}")
        } catch (t: Throwable) {
            Log.e(TAG, "startEngine failed", t)
            sendDomain(SeparationEvent.Domain.JobFailed(effect.jobId, "启动失败: ${t.message}"))
        }
    }

    private fun loadStems(effect: SeparationEffect.LoadStems) {
        val bridge = playerBridge
        if (bridge == null) {
            sendDomain(SeparationEvent.Domain.StemsLoadFailed(effect.jobId, "播放器未就绪"))
            return
        }
        val files = effect.files.paths.map(::File).filter { it.exists() }
        if (files.size != effect.files.paths.size) {
            sendDomain(SeparationEvent.Domain.StemsLoadFailed(effect.jobId, "stem 文件缺失"))
            return
        }
        val ok = runCatching { bridge.loadStems(effect.jobId, files) }.getOrDefault(false)
        if (ok) {
            sendDomain(SeparationEvent.Domain.StemsLoaded(effect.jobId, effect.files))
        } else {
            sendDomain(SeparationEvent.Domain.StemsLoadFailed(effect.jobId, "载入失败"))
        }
    }

    // ==================== Old repo → Domain events ====================

    private fun onRepoState(state: SeparationRepository.State) {
        val jobId = activeJobId ?: return
        val current = controller.snapshot.value.job
        if (current == null || current.jobId != jobId) return

        when (state) {
            is SeparationRepository.State.Preparing -> Unit
            is SeparationRepository.State.Running -> {
                val total = state.totalFrames.toLong().coerceAtLeast(0L)
                val done = state.doneFrames.toLong().coerceAtLeast(0L)
                val coverage = Coverage(
                    totalFrames = total,
                    startFrame = 0L,
                    coveredRanges = if (done > 0) listOf(0L until done) else emptyList(),
                    watermarkFrame = done,
                    complete = false,
                )
                if (!sourceReady) {
                    sourceReady = true
                    sendDomain(SeparationEvent.Domain.SourceReady(jobId, coverage, files = null))
                } else {
                    sendDomain(SeparationEvent.Domain.CoverageUpdated(jobId, coverage, gateReady = false))
                }
            }
            is SeparationRepository.State.Done -> {
                sourceReady = true
                val total = current.coverage.totalFrames.coerceAtLeast(0L)
                val coverage = Coverage(
                    totalFrames = total,
                    startFrame = 0L,
                    coveredRanges = if (total > 0) listOf(0L until total) else emptyList(),
                    watermarkFrame = total,
                    complete = true,
                )
                sendDomain(
                    SeparationEvent.Domain.JobFinished(
                        jobId = jobId,
                        files = StemFiles(
                            listOf(state.vocalsFile.absolutePath, state.accompanimentFile.absolutePath),
                        ),
                        coverage = coverage,
                        complete = true,
                        metrics = JobMetrics(
                            inferTimeMs = state.inferTimeMs,
                            audioDurationMs = state.audioDurationMs,
                            peakPssMb = state.peakPssMb,
                            modelLoadMs = state.modelLoadMs,
                            fromCache = state.fromCache,
                        ),
                    ),
                )
            }
            is SeparationRepository.State.Done4 -> {
                sourceReady = true
                val total = current.coverage.totalFrames.coerceAtLeast(0L)
                val coverage = Coverage(
                    totalFrames = total,
                    startFrame = 0L,
                    coveredRanges = if (total > 0) listOf(0L until total) else emptyList(),
                    watermarkFrame = total,
                    complete = true,
                )
                sendDomain(
                    SeparationEvent.Domain.JobFinished(
                        jobId = jobId,
                        files = StemFiles(state.stemFiles.map { it.absolutePath }),
                        coverage = coverage,
                        complete = true,
                        metrics = JobMetrics(
                            inferTimeMs = state.inferTimeMs,
                            audioDurationMs = state.audioDurationMs,
                            peakPssMb = state.peakPssMb,
                            fromCache = state.fromCache,
                        ),
                    ),
                )
            }
            is SeparationRepository.State.EdgeDone -> {
                sourceReady = true
                val vf = state.vocalsFile
                val af = state.accompanimentFile
                if (vf == null || af == null) {
                    sendDomain(SeparationEvent.Domain.JobFailed(jobId, "边分离结果文件缺失"))
                    return
                }
                val total = edge.totalFrames.toLong().coerceAtLeast(0L)
                val coverage = Coverage(
                    totalFrames = total,
                    startFrame = edge.startFrame.toLong(),
                    coveredRanges = if (total > 0) listOf(0L until total) else emptyList(),
                    watermarkFrame = total,
                    complete = state.complete,
                )
                sendDomain(
                    SeparationEvent.Domain.JobFinished(
                        jobId = jobId,
                        files = StemFiles(listOf(vf.absolutePath, af.absolutePath)),
                        coverage = coverage,
                        complete = state.complete,
                        metrics = JobMetrics(
                            inferTimeMs = state.inferTimeMs,
                            audioDurationMs = state.audioDurationMs,
                            peakPssMb = state.peakPssMb,
                        ),
                    ),
                )
            }
            is SeparationRepository.State.Failed -> {
                sendDomain(SeparationEvent.Domain.JobFailed(jobId, state.message))
                if (current.kind == SeparationKind.EDGE &&
                    controller.snapshot.value.attachment is AttachmentState.Loaded
                ) {
                    controller.detach()
                }
            }
            is SeparationRepository.State.Cancelled -> {
                sendDomain(SeparationEvent.Domain.JobStopped(jobId))
                if (current.kind == SeparationKind.EDGE &&
                    controller.snapshot.value.attachment is AttachmentState.Loaded
                ) {
                    controller.detach()
                }
            }
            SeparationRepository.State.Idle -> Unit
        }
    }

    private fun tickEdge() {
        val jobId = activeJobId ?: return
        val current = controller.snapshot.value.job ?: return
        if (current.jobId != jobId || current.kind != SeparationKind.EDGE) return
        if (current.isTerminal) return
        val total = edge.totalFrames.toLong()
        if (total <= 0L) return
        val start = edge.startFrame.toLong()
        val end = edge.writtenUpToFrame.toLong().coerceIn(start, total)
        val ranges = ArrayList<LongRange>(2)
        if (edge.frontCovered && start > 0L) ranges += 0L until start
        if (end > start) ranges += start until end
        val coverage = Coverage(
            totalFrames = total,
            startFrame = start,
            coveredRanges = ranges,
            watermarkFrame = end,
            complete = edge.state.value is EdgeSeparationSession.State.Done,
        )
        val files = if (edge.vocalsFile != null && edge.accompFile != null) {
            StemFiles(listOf(edge.vocalsFile!!.absolutePath, edge.accompFile!!.absolutePath))
        } else {
            null
        }
        if (!sourceReady && (files != null || coverage.hasPreview)) {
            sourceReady = true
            sendDomain(SeparationEvent.Domain.SourceReady(jobId, coverage, files))
        } else if (sourceReady) {
            val gateReady = edge.gate.value is EdgeSeparationSession.Gate.Playable ||
                edge.gate.value is EdgeSeparationSession.Gate.Finished
            sendDomain(SeparationEvent.Domain.CoverageUpdated(jobId, coverage, gateReady))
        }
    }

    private fun publishEdgeUi() {
        val snap = controller.snapshot.value
        val job = snap.job
        if (job == null || job.kind != SeparationKind.EDGE) {
            if (_edgeUi.value != SeparationEdgeUi()) _edgeUi.value = SeparationEdgeUi()
            return
        }
        val pct = when (val st = edge.state.value) {
            is EdgeSeparationSession.State.Preparing -> 0
            is EdgeSeparationSession.State.FirstSegmentReady -> st.percent
            is EdgeSeparationSession.State.Running -> st.percent
            else -> null
        }
        val total = edge.totalFrames
        val frac = if (total > 0 && edge.gate.value !is EdgeSeparationSession.Gate.Idle) {
            (edge.writtenUpToFrame.toFloat() / total).coerceIn(0f, 1f)
        } else null
        val bf = if (edge.inBackfill.value) edge.backfillPercent.value else null
        _edgeUi.value = SeparationEdgeUi(
            separationOn = snap.separationOn,
            edgePercent = pct,
            edgeSeparatedFraction = frac,
            edgeStarved = edge.gate.value is EdgeSeparationSession.Gate.Starved,
            edgeBeforeStart = edge.seekInGrayZone,
            edgeStartMs = edge.startMs,
            backfillPercent = bf,
            pendingBackfillMs = edge.pendingBackfillMs(),
        )
    }

    private fun sendDomain(event: SeparationEvent.Domain) {
        controller.send(event)
    }

    companion object {
        private const val TAG = "SepDomain"

        @Volatile
        private var instance: SeparationDomain? = null

        fun get(app: Application): SeparationDomain =
            instance ?: synchronized(this) {
                instance ?: SeparationDomain(app).also { instance = it }
            }
    }
}
