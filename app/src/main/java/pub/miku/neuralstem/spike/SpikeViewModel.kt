package pub.miku.neuralstem.spike

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.miku.neuralstem.devtools.EdgeProbe
import pub.miku.neuralstem.settings.SettingsRepository
import pub.miku.neuralstem.player.pcm.PcmStoreProvider
import java.io.File
import pub.miku.neuralstem.spike.state.SeparationBackend
import pub.miku.neuralstem.spike.state.SeparationDomain
import pub.miku.neuralstem.spike.state.SeparationKind
import pub.miku.neuralstem.spike.state.StartRequest
import pub.miku.neuralstem.spike.state.TrackKey
import pub.miku.neuralstem.spike.state.Lifecycle
import pub.miku.neuralstem.spike.state.JobMetrics
import pub.miku.neuralstem.spike.state.SeparationSnapshot
import pub.miku.neuralstem.spike.state.SeparationGuards

/**
 * Spike 编排(Task 0b,sherpa-onnx 主路线)。
 *
 * 与 TFLite 版(SpleeterSeparator)的差异:
 * - sherpa-onnx 内部自己做分块/overlap-add → Chunker 不参与推理主路径
 * - 模型是两个 ONNX(vocals/accompaniment),由 build 前脚本或手动放置到 filesDir/sherpa/
 *
 * 状态机:Idle → Ready → Separating → Done / Error
 * 全部推理在 Dispatchers.Default/IO,不阻塞 UI(guide F3.2/N2)。
 * 内存采样对应 guide Task 0.4:峰值 PSS 记录到 docs/DEVELOP.md。
 *
 * 设计说明(Task 0c Phase A):
 * 加载与分离各自拆成"suspend 版(内部) + launch 版(公开,供按钮点击)"。
 * 这样 [runAutoSpike] 能在**同一协程**里顺序 await 两步,不必依赖状态回调,
 * 从而支持"应用内自驱动"的自动化验收(不依赖任何坐标点击)。
 */
class SpikeViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * 页面状态(Task 4.2 后):
     * - Idle / Ready 是「页面本地」状态(选文件等)
     * - 分离本身的状态转发自 [SeparationRepository](进程级,跨页面存活)
     */
    sealed interface State {
        data object Idle : State
        data class Ready(val fileName: String, val durationMs: Long, val sampleRate: Int) : State
        /** 分离进行中(来自 Repo) */
        data class Separating(val elapsedMs: Long, val peakPssMb: Float, val percent: Int) : State
        data class Done(
            val vocalsFile: File,
            val accompanimentFile: File,
            val modelLoadMs: Long,
            val inferTimeMs: Long,
            val peakPssMb: Float,
            val audioDurationMs: Long,
            val fromCache: Boolean,
        ) : State
        data class Error(val message: String, val cause: Throwable? = null) : State
    }

    private val domain = SeparationDomain.get(app)

    /** ★P2-3：UI 的只读 Domain 快照入口（第一步先迁导出/归属判断）。 */
    val domainSnapshot: StateFlow<SeparationSnapshot> = domain.snapshot

    /**
     * 当前曲可导出的 stem 文件，直接来自 Domain job。
     * 文件存在性再做一次兜底（缓存可能被清）。
     */
    fun exportableFiles(currentUri: String?): List<File>? {
        val snap = domainSnapshot.value
        val files = SeparationGuards.exportableFiles(snap, currentUri)
            ?.map(::File)
            ?.filter { it.exists() }
            ?.takeIf { it.isNotEmpty() }
        Log.i(
            TAG,
            "exportableFiles current=$currentUri domainCurrent=${snap.currentTrackKey?.uri} " +
                "job=${snap.job?.jobId}/${snap.job?.lifecycle}/${snap.job?.trackKey?.uri} " +
                "files=${files?.size ?: 0}",
        )
        return files
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // ============================ P2-3：Domain 驱动 ============================
    // 显示态来自 Domain snapshot；FULL 完成用一次性 CompletedStems 事件。
    // 旧的 SessionStateMachine.separation 适配层已从这里删除。

    /** M3：Domain snapshot 订阅；旧 repo 订阅不再驱动 UI。 */
    private var domainSubscription: Job? = null

    /**
     * ★P2-3 第 3 步：FULL 完成的**一次性事件**。
     *
     * 旧实现由 UI 的 `LaunchedEffect(separationTask)` 调 `consumeSeparationDone()`；
     * 现在改为 Domain snapshot 派生，按 jobId 去重，页面重入时仍可消费。
     * EDGE 不在这里：Domain 自动 attach，页面只显示短完成提示。
     */
    data class CompletedStems(
        val jobId: Long,
        val kind: SeparationKind,
        val files: List<File>,
        val metrics: JobMetrics,
    )

    private val _pendingCompletion = MutableStateFlow<CompletedStems?>(null)
    val pendingCompletion: StateFlow<CompletedStems?> = _pendingCompletion.asStateFlow()
    private var lastCompletionJobId: Long? = null

    fun consumePendingCompletion(): CompletedStems? {
        val c = _pendingCompletion.value ?: return null
        _pendingCompletion.value = null
        return c
    }

    /** 取消后的短提示（Domain job 已被清空，mapper 没有 job 可显示）。 */
    private val _cancelledNotice = MutableStateFlow(false)
    val cancelledNotice: StateFlow<Boolean> = _cancelledNotice.asStateFlow()

    /**
     * ★2026-11-07：边播完成后的**短时完成态**（UI 显示 5 秒后自动消失）。
     */
    data class EdgeDoneUi(
        val complete: Boolean,
        val inferTimeMs: Long,
        val audioDurationMs: Long,
    )

    private val _lastEdgeDone = MutableStateFlow<EdgeDoneUi?>(null)
    val lastEdgeDone: StateFlow<EdgeDoneUi?> = _lastEdgeDone.asStateFlow()
    private var edgeDoneFlashJob: Job? = null
    /** 防止清缓存触发的 Domain snapshot 再次点亮同一个完成提示。 */
    private var lastEdgeDoneJobId: Long? = null

    /** Task 4.2:分离任务宿主(进程级单例),切页面/切后台不中断 */
    private val repo = SeparationRepository.get(app)

    /** 已导入的文件(页面本地状态) */
    private var pendingFile: File? = null

    /** 引擎选择(Task 0f UVR 对比实验);M2-F 后由设置默认引擎初始化 */
    private var engineMode: Int = SherpaSourceSeparation.MODE_SPLEETER

    /** UVR 单模型文件名(仅 engineMode=UVR 时用),放在私有 files/uvr/ 目录 */
    private var uvrModelName: String = "UVR-MDX-NET-Voc_FT.onnx"

    /** 自动化/手动显式指定引擎后置 true，startSeparation 便不再从设置覆盖 */
    private var engineOverride = false

    init {
        // M2-F:应用设置里的默认引擎(仅当用户未通过 extra 指定时)
        applyDefaultEngine()
        // ★3a-2：常驻订阅 repo → 驱动状态机（不再只在“开始时订阅”）。
        //   否则：页面重入 / VM 重建时 SM 停在 Idle，即使 repo 正在跑/已完成。
        subscribeDomain()
        // ★M3：VM 重建时从 Domain snapshot 恢复，而不是再从 repo 镜像一份。
        val seeded = domain.snapshot.value
        if (seeded.job != null) {
            applyDomainSnapshot(seeded)
            Log.i(TAG, "restore separation from domain: ${seeded.job!!.lifecycle}")
        }
    }

    /** 从设置读取默认引擎并应用。 */
    private fun applyDefaultEngine() {
        val spec = SettingsRepository(getApplication()).defaultEngine()
        engineMode = spec.engine
        if (spec.engine == SherpaSourceSeparation.MODE_UVR) {
            uvrModelName = spec.fileName
        }
        Log.i(TAG, "default engine: ${spec.id} (mode=$engineMode, model=${spec.fileName})")
    }

    /** M2-E/F:按具体模型设置分离引擎(设置页选默认引擎后调用). */
    fun selectModel(id: String) {
        val spec = ModelRegistry.byId(id) ?: return
        engineOverride = true
        engineMode = spec.engine
        if (spec.engine == SherpaSourceSeparation.MODE_UVR) {
            uvrModelName = spec.fileName
        }
        Log.i(TAG, "selectModel: ${spec.id} -> mode=$engineMode model=${spec.fileName}")
    }

    /** 自动化入口选择引擎("spleeter"/"uvr"),Task 0f */
    fun selectEngine(name: String) {
        engineOverride = true
        engineMode = when (name.lowercase()) {
            "uvr" -> SherpaSourceSeparation.MODE_UVR
            else -> SherpaSourceSeparation.MODE_SPLEETER
        }
        Log.i(TAG, "selectEngine: $name -> mode=$engineMode")
    }

    /** 自动化入口选择 UVR 模型文件名 */
    fun selectUvrModel(name: String) {
        engineOverride = true
        uvrModelName = name
        Log.i(TAG, "selectUvrModel: $name")
    }

    /** 块长覆写(Task 0g B3 实验用);null = 按引擎默认 */
    private var chunkSecondsOverride: Int? = null

    fun selectChunkSeconds(seconds: Int) {
        chunkSecondsOverride = seconds
        Log.i(TAG, "selectChunkSeconds: ${seconds}s")
    }

    /** 线程数覆写(Task 0g B2 实验用);null = NUM_THREADS */
    private var threadsOverride: Int? = null

    fun selectThreads(n: Int) {
        threadsOverride = n
        Log.i(TAG, "selectThreads: $n")
    }

    /** Spleeter 精度变体(Task 0g C1/C2):"int8"/"fp32",null=内置 fp16 */
    private var spleeterVariantName: String? = null

    fun selectSpleeterVariant(name: String) {
        spleeterVariantName = name
        Log.i(TAG, "selectSpleeterVariant: $name")
    }

    // ---------------- 导入 ----------------

    /**
     * SAF Uri → cacheDir,再解析(SAF 流不可 seek)。
     *
     * Task 4.1:不再局限于 WAV —— 保留原扩展名(解码桥/探测都按扩展名+内容分支),
     * 因此 mp3/flac/aac/m4a 均可导入分离。
     */
    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                // 保留扩展名:下游 SeparationInput 用它选 wav 直读 vs 解码路径
                val ext = ctx.contentResolver.getType(uri)
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "audio"
                val local = File(ctx.cacheDir, "spike_input.$ext")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open $uri")
                }
                Log.i(TAG, "imported to ${local.absolutePath} (${local.length()} B, ext=$ext)")
                loadLocalFileInternal(local)
            } catch (t: Throwable) {
                Log.e(TAG, "importAudio failed", t)
                _state.value = State.Error("导入失败: ${t.message}", t)
            }
        }
    }

    /**
     * 从应用外部目录直接加载(不走 SAF)。
     * 用途:模拟器 DocumentsUI 不可用 / 自动化测试。
     * 文件由 adb push 到 /sdcard/Android/data/pub.miku.neuralstem/files/spike/ 下。
     */
    fun loadExternal() {
        viewModelScope.launch { loadExternalInternal() }
    }

    /**
     * 挂起版:扫描外部 spike 目录并解析第一个音频文件。成功(置 Ready)返回 true。
     * Task 4.1:不再限制 wav —— mp3/flac/aac 等同样可被探测与分离。
     */
    private suspend fun loadExternalInternal(): Boolean {
        return try {
            val ctx = getApplication<Application>()
            val dir = File(ctx.getExternalFilesDir(null), "spike")
            Log.i(TAG, "loadExternalInternal: scan ${dir.absolutePath}")
            val audioExt = setOf("wav", "mp3", "flac", "m4a", "aac", "ogg", "opus")
            val local = dir.listFiles()
                ?.firstOrNull { it.isFile && it.extension.lowercase() in audioExt }
                ?: cacheSpikeInput(ctx, audioExt)
                ?: error("${dir.absolutePath} 下没有音频文件,请先 adb push")
            loadLocalFileInternal(local)
        } catch (t: Throwable) {
            Log.e(TAG, "loadExternalInternal failed", t)
            _state.value = State.Error("外部文件加载失败: ${t.message}", t)
            false
        }
    }

    /**
     * 回退(自动化通道):扫应用**私有**的 `cacheDir`/`filesDir` 找已 push 的音频。
     *
     * 为何需要(实测,Android 16 / API 36):
     * 1. `adb push`(shell 属主)到外部 `Android/data/<pkg>/files/spike/` 后,
     *    app 进程 `listFiles()` **看不到**(FUSE 拒绝,`chown` 无 root 改不了)。
     * 2. `/sdcard/Download/` 虽 shell 可写、app 可读,但 targetSdk 37 + 无
     *    `READ_MEDIA_AUDIO` → app `listFiles()` 同样返回 null。
     * ⇒ 唯一不靠权限、且属主正确的落点是**应用自己的私有目录**,
     *   用 `adb shell "cat /sdcard/Download/x.wav | run-as <pkg> sh -c 'cat > cacheDir/spike_input.wav'"` 写入(属主 u0_aXXX)。
     *
     * 仅用于自动化/调试,不影响 SAF 导入正常路径。
     */
    private fun cacheSpikeInput(ctx: Application, audioExt: Set<String>): File? {
        val dirs = listOf(ctx.cacheDir, ctx.filesDir)
        for (d in dirs) {
            val hit = d.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in audioExt }
                ?.maxByOrNull { it.length() }
            if (hit != null) {
                Log.i(TAG, "cacheSpikeInput: ${hit.absolutePath} (${hit.length()} B)")
                return hit
            }
        }
        return null
    }

    /**
     * 挂起版:探测音频元信息,**不读样本**。成功(置 Ready)返回 true。
     *
     * Task 0e:wav 只解析 RIFF 头(O(1) 内存)——旧版把整曲读进内存,
     * 5:14 全曲(27.7M float ≈ 110MB)在 48MB 堆上直接 OOM。
     * Task 4.1:非 wav 走 [SeparationInput.probe](MediaExtractor 拿格式,不解码),
     * 采样率不必是 44.1k(分离时会自动重采样)。
     */
    private suspend fun loadLocalFileInternal(local: File): Boolean {
        // ★B7：新歌载入 → 先清掉上一首的分离 UI 任务态；迟到的 repo 事件由状态机拒绝。
        //   同一首歌重新加载不清，避免误伤正在跑的分离进度。
        // 新歌载入：Domain 的 TrackChanged 会负责旧任务归属；这里不再维护旧 UI 任务态。
        return try {
            val info = withContext(Dispatchers.IO) {
                SeparationInput.probe(getApplication(), local)
            }
            pendingFile = local
            Log.i(
                TAG,
                "loaded ${local.name}: frames=${info.totalFrames} ch=${info.channels} " +
                    "sr=${info.sampleRate} dur=${info.durationMs}ms",
            )
            _state.value = State.Ready(local.name, info.durationMs, info.sampleRate)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed: ${local.absolutePath}", t)
            _state.value = State.Error("音频解析失败: ${t.message}", t)
            false
        }
    }

    // ---------------- 分离(Task 4.2:委托给进程级 Repository) ----------------

    /**
     * ★Step10（分离页重构）：把**宿主提供的源文件**（当前播放曲经 [CurrentTrackBridge]
     * 拷到 cacheDir）设为待分离输入，并解析元信息。
     *
     * ★ **suspend**（而非内部 `viewModelScope.launch`）：因为紧接着会调 [startSeparation]，
     *   而后者**同步读 [pendingFile]**。若 fire-and-forget，startSeparation 会在 probe
     *   完成前读到 null → **静默不分离**（真机 E2E 抽到的竞态 bug）。故由调用方 await。
     *
     * @return 是否解析成功（成功才可 startSeparation）
     */
    suspend fun selectSeparationSource(file: File): Boolean {
        val ok = loadLocalFileInternal(file)
        Log.i(TAG, "selectSeparationSource: ${file.absolutePath} ok=$ok")
        return ok
    }


    /**
     * 订阅 repo 状态 → 镜像到页面状态。
     *
     * 修复(真机实测发现):按钮路径 [startSeparation] 原先只调 repo.start()
     * 不订阅,导致缓存命中/完成时 UI 停在「分离中 0%」。
     * 现改为「一次性订阅到终态」,按钮路径与自动路径共用。
     */
    /** ★P0：Domain 拒绝“已切歌”的启动时，别把状态机留在 Preparing。 */
    private fun startDomain(request: StartRequest): Boolean {
        _cancelledNotice.value = false
        val ok = domain.start(request)
        if (!ok) {
            _state.value = State.Error("当前曲已切换，请重试")
            _lastEdgeDone.value = null
        }
        return ok
    }

    private fun subscribeDomain() {
        if (domainSubscription != null) return
        domainSubscription = viewModelScope.launch {
            domain.snapshot.collect { snapshot -> applyDomainSnapshot(snapshot) }
        }
    }

    private fun applyDomainSnapshot(snapshot: SeparationSnapshot) {
        val job = snapshot.job
        if (job == null) {
            // 取消后 Domain 会清空 job；UI 用 cancelledNotice 显示短提示。
            return
        }

        // ★P0：旧曲的任务（尤其 FULL 后台继续）不能把完成态推给当前曲 UI。
        if (!SeparationGuards.jobMatchesCurrent(snapshot.currentTrackKey, job.trackKey)) {
            _state.value = State.Idle
            return
        }

        when (val lc = job.lifecycle) {
            is Lifecycle.Preparing -> Unit
            is Lifecycle.Running -> {
                val total = job.coverage.totalFrames.coerceAtLeast(1L)
                val done = job.coverage.coveredFrames.coerceIn(0L, total)
                val percent = ((done * 100L) / total).toInt().coerceIn(0, 100)
                _state.value = State.Separating(0L, job.metrics?.peakPssMb ?: 0f, percent)
            }
            is Lifecycle.Finished -> {
                val files = job.files?.paths?.map { File(it) }.orEmpty()
                val metrics = job.metrics ?: JobMetrics()
                when (job.kind) {
                    SeparationKind.EDGE -> {
                        // 同一个 job 的重复 snapshot 不再重新点亮；清缓存时
                        // onStemsCacheCleared 会触发 snapshot，但提示已被 clearCache 清掉。
                        if (lastEdgeDoneJobId != job.jobId) {
                            lastEdgeDoneJobId = job.jobId
                            _lastEdgeDone.value = EdgeDoneUi(
                                complete = job.coverage.complete,
                                inferTimeMs = metrics.inferTimeMs,
                                audioDurationMs = metrics.audioDurationMs,
                            )
                            edgeDoneFlashJob?.cancel()
                            edgeDoneFlashJob = viewModelScope.launch {
                                delay(5_000)
                                _lastEdgeDone.value = null
                            }
                        }
                    }
                    SeparationKind.FULL_2 -> {
                        if (files.size >= 2 && lastCompletionJobId != job.jobId) {
                            lastCompletionJobId = job.jobId
                            _pendingCompletion.value = CompletedStems(
                                jobId = job.jobId,
                                kind = job.kind,
                                files = files.take(2),
                                metrics = metrics,
                            )
                            _state.value = State.Done(
                                vocalsFile = files[0],
                                accompanimentFile = files[1],
                                modelLoadMs = metrics.modelLoadMs,
                                inferTimeMs = metrics.inferTimeMs,
                                peakPssMb = metrics.peakPssMb,
                                audioDurationMs = metrics.audioDurationMs,
                                fromCache = metrics.fromCache,
                            )
                        }
                    }
                    SeparationKind.FULL_4 -> {
                        if (files.size >= 4 && lastCompletionJobId != job.jobId) {
                            lastCompletionJobId = job.jobId
                            _pendingCompletion.value = CompletedStems(
                                jobId = job.jobId,
                                kind = job.kind,
                                files = files,
                                metrics = metrics,
                            )
                            _state.value = State.Idle
                        }
                    }
                }
            }
            is Lifecycle.Failed -> {
                _state.value = State.Error(lc.message)
            }
            is Lifecycle.Stopping -> Unit
        }
    }

    /**
     * 启动分离(按钮路径)。
     *
     * Task 4.2 重构:分离逻辑已移入 [SeparationRepository](进程级单例),
     * 本节只负责「把页面状态与 repo 状态对齐」并转发参数。
     * 好处:切页面/切后台不中断(N5)、可取消、结果缓存(F3.3)。
     */
    fun startSeparation() {
        val srcFile = pendingFile ?: run {
            _state.value = State.Error("先导入音频")
            return
        }
        // M2-F:若自动化未显式指定引擎,则每次按设置里的默认引擎刷新
        // (用户改了默认引擎无需重建 VM 即生效)
        if (!engineOverride) applyDefaultEngine()
        _state.value = State.Separating(0, 0f, 0)
        startDomain(
            StartRequest(
                kind = SeparationKind.FULL_2,
                trackKey = TrackKey("", 0L),
                sourcePath = srcFile.absolutePath,
                backend = SeparationBackend.SHERPA_2STEM,
                engineMode = engineMode,
                uvrModelName = uvrModelName,
                chunkOverrideFrames = chunkSecondsOverride
                    ?.let { it * SeparationInput.MODEL_SAMPLE_RATE },
            ),
        )
    }

    /**
     * ★3a-2：启动 **4-stem** 分离（经本 VM，以确保状态机也收到转移）。
     *
     * 旧路径：`SeparationScreen` 直接 `repo.start4(src)` → 状态机看不到 → 仍需 UI 双读 repo。
     * 新路径：统一经此方法“先订阅（驱动 SM）再启动”，与 2-stem 同构。
     */
    fun start4Stem(source: File, ownerUri: String? = null) {
        startDomain(
            StartRequest(
                kind = SeparationKind.FULL_4,
                trackKey = TrackKey(ownerUri ?: "", 0L),
                sourcePath = source.absolutePath,
                backend = SeparationBackend.SPLEETER4,
            ),
        )
    }

    /**
     * ★D-3：按「档位选中的 [SepOption]」启动分离（统一入口）。
     *
     * 屏蔽三套引擎：sherpa(2-stem/UVR) → [SeparationRepository.start]；
     * Spleeter4 → [SeparationRepository.start4]；Demucs → [SeparationRepository.startDemucs]。
     * 启动后由 Domain snapshot 驱动页面显示。
     */
    fun startOption(source: File, opt: SepOption, ownerUri: String? = null) {
        lastEdgeDoneJobId = null
        _lastEdgeDone.value = null   // 新一轮开始，清掉上一次的完成提示
        when (opt) {
            is SepOption.Sherpa -> {
                engineOverride = true
                engineMode = opt.spec.engine
                if (opt.spec.engine == SherpaSourceSeparation.MODE_UVR) {
                    uvrModelName = opt.spec.fileName
                }
                Log.i(TAG, "startOption(Sherpa): ${opt.spec.id} engine=${opt.spec.engine}")
                startDomain(
                    StartRequest(
                        kind = SeparationKind.FULL_2,
                        trackKey = TrackKey(ownerUri ?: "", 0L),
                        sourcePath = source.absolutePath,
                        backend = SeparationBackend.SHERPA_2STEM,
                        engineMode = engineMode,
                        uvrModelName = uvrModelName,
                        chunkOverrideFrames = chunkSecondsOverride
                            ?.let { it * SeparationInput.MODEL_SAMPLE_RATE },
                    ),
                )
            }
            SepOption.Spleeter4 -> {
                Log.i(TAG, "startOption: Spleeter4")
                start4Stem(source, ownerUri)
            }
            SepOption.Demucs -> {
                Log.i(TAG, "startOption: Demucs")
                startDomain(
                    StartRequest(
                        kind = SeparationKind.FULL_4,
                        trackKey = TrackKey(ownerUri ?: "", 0L),
                        sourcePath = source.absolutePath,
                        backend = SeparationBackend.DEMUCS,
                    ),
                )
            }
        }
    }

    /**
     * ★G-3：开始“边分离边播”（从 [fromPositionMs] 当前播放位置起，仅 Spleeter 2-stem）。
     * 与 [startOption] 同构（先驱动状态机），但走 [SeparationRepository.startEdge]。
     */
    fun startEdgeOption(source: File, fromPositionMs: Long, ownerUri: String? = null) {
        lastEdgeDoneJobId = null
        _lastEdgeDone.value = null   // 新一轮开始，清掉上一次的完成提示
        engineOverride = true
        engineMode = SherpaSourceSeparation.MODE_SPLEETER
        Log.i(TAG, "startEdgeOption: from=${fromPositionMs}ms")
        startDomain(
            StartRequest(
                kind = SeparationKind.EDGE,
                trackKey = TrackKey(ownerUri ?: "", 0L),
                startFrame = (fromPositionMs.coerceAtLeast(0L) *
                    SeparationInput.MODEL_SAMPLE_RATE / 1000L),
                fromPositionMs = fromPositionMs,
                trackTitle = source.name,
                sourcePath = source.absolutePath,
                backend = SeparationBackend.EDGE,
            ),
        )
    }

    /** guide N5:取消分离任务 */
    fun cancelSeparation() {
        domain.cancel()
        _cancelledNotice.value = true
        _state.value = State.Error("分离已取消")
    }

    /**
     * V4（技术债收口）：清理分离结果缓存。
     * 分离进行中会被拒绝（返回 -1）—— 避免删掉正在写的目录。
     * @return 删除的缓存目录数；-1 = 拒绝
     */
    fun clearCache(): Int {
        val n = repo.clearCache()
        if (n >= 0) {
            // 清缓存后旧结果已完成的事实仍然存在，但“✓ 分离完成 · 全曲可播”
            // 不应再等 5 秒 flash，否则用户会以为缓存没清干净。
            edgeDoneFlashJob?.cancel()
            edgeDoneFlashJob = null
            _lastEdgeDone.value = null
        }
        // PCM 缓存与分离缓存一起清；当前曲 pin 的文件由 PcmStore 保护。
        viewModelScope.launch {
            runCatching { PcmStoreProvider.get(getApplication()).clearUnpinned() }
                .onSuccess { removed -> Log.i(TAG, "pcm cache cleared: $removed") }
        }
        return n
    }

    /**
     * 应用内自驱动的完整 Spike 流程:加载外部音频 → 分离 → 写 stem WAV。
     *
     * Task 0c 的自动化通道;Task 4.2 后改为「启动分离 + 等待 repo 完成」。
     * 触发方式:
     * ```
     * adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity --ez auto true
     * ```
     */
    fun runAutoSpike() {
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "=== AUTO SPIKE START ===")

            if (!loadExternalInternal()) {
                Log.e(TAG, "=== AUTO SPIKE ABORT === load failed, state=${_state.value}")
                return@launch
            }

            // 直接调 repo(不用 startSeparation,避免 _state 竞态),
            // 然后 wait 到终态 —— 自动化场景需要「跑完才打 DONE 日志」
            val srcFile = pendingFile
            if (srcFile == null) {
                Log.e(TAG, "=== AUTO SPIKE ABORT === no pending file")
                return@launch
            }

            // 用 CompletableDeferred 等终态（直接监听 repo.state，UI 镜像由 init 的常驻订阅负责）
            // ★bug fix：StateFlow 订阅会**立即 emit 当前值**（上一轮残留终态）→ 必须 drop(1)
            //   否则 done 会提前用旧值完成（真机实测：AUTO SPIKE 28ms 就误报 FAILED）。
            val done = CompletableDeferred<SeparationRepository.State>()
            val waiter = viewModelScope.launch {
                repo.state.drop(1).collect { s ->
                    if (s !is SeparationRepository.State.Idle &&
                        s !is SeparationRepository.State.Preparing &&
                        s !is SeparationRepository.State.Running
                    ) {
                        done.complete(s)
                    }
                }
            }
            repo.start(
                source = srcFile,
                engine = engineMode,
                uvrModelName = uvrModelName,
                chunkOverrideFrames = chunkSecondsOverride
                    ?.let { it * SeparationInput.MODEL_SAMPLE_RATE },
            )
            val final = done.await()
            waiter.cancel()

            val total = System.currentTimeMillis() - t0
            val d = final as? SeparationRepository.State.Done
            if (d != null) {
                val rt = d.audioDurationMs / 1000.0 / (d.inferTimeMs / 1000.0)
                Log.i(
                    TAG,
                    "=== AUTO SPIKE DONE === total=${total}ms modelLoad=${d.modelLoadMs}ms " +
                        "infer=${d.inferTimeMs}ms peakPss=${"%.1f".format(d.peakPssMb)}MB " +
                        "audio=${d.audioDurationMs}ms realtime=${"%.2f".format(rt)}x " +
                        "fromCache=${d.fromCache}",
                )
                Log.i(TAG, "OUT vocals=${d.vocalsFile.absolutePath} (${d.vocalsFile.length()} B)")
                Log.i(TAG, "OUT accompaniment=${d.accompanimentFile.absolutePath} (${d.accompanimentFile.length()} B)")
                // V3（真机多轮复测）:机器可解析的结构化结果行，供批量脚本提取中位数。
                // 字段顺序固定；日志 TAG 为 SpikeVm，dumpsys/脚本用 grep NS_RESULT 抓取。
                Log.i(
                    TAG,
                    "NS_RESULT {" +
                        "\"modelLoadMs\":${d.modelLoadMs}," +
                        "\"inferMs\":${d.inferTimeMs}," +
                        "\"peakPssMb\":${d.peakPssMb}," +
                        "\"audioMs\":${d.audioDurationMs}," +
                        "\"realtime\":${rt}," +
                        "\"fromCache\":${d.fromCache}," +
                        "\"totalMs\":$total," +
                        "\"file\":\"${pendingFile?.name}\"," +
                        "\"engine\":\"${engineMode}\"," +
                        "\"chunkSeconds\":${chunkSecondsOverride ?: -1}" +
                        "}",
                )
            } else {
                Log.e(TAG, "=== AUTO SPIKE FAILED === after ${total}ms, state=${_state.value}")
            }
        }
    }

    /** 页面销毁时的清理:只清页面本地状态,不动 repo(任务继续跑,N5) */
    fun reset() {
        pendingFile = null
        _state.value = State.Idle
        _lastEdgeDone.value = null
        _cancelledNotice.value = false
    }

    /** M4/S3：供 4-stem 路径复用「已导入的源文件」。 */
    fun sourceFileFor4(): File? = pendingFile

    companion object {
        const val TAG = "SpikeVm"
    }
}
