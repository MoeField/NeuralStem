package pub.miku.neuralstem.spike

import android.app.Application
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 分离任务的宿主(Task 4.2)。
 *
 * 为什么要独立于 ViewModel:
 * - SpikeViewModel 随 SpikeActivity 销毁(guide N5 要求「切页面/切后台不中断」)
 * - 分离是 30s–几分钟的长任务,绑在页面 ViewModel 上会被 onCleared 打断
 *
 * 生命周期:进程级单例,持有 [SupervisorJob] 的应用级 CoroutineScope ——
 * 任务不随任何页面销毁,仅随进程结束。
 *
 * 附带 F3.3 缓存:分离结果按「输入文件内容哈希 + 引擎」落盘到
 * `filesDir/stems-cache/<hash>-<engine>/`,同一首歌重复分离直接命中,不重跑。
 */
class SeparationRepository(private val app: Application) {

    /** 分离进度/结果状态 */
    sealed interface State {
        data object Idle : State

        /**
         * ★准备阶段（可长达 10+ 秒：解码整首音频 + 加载模型）。
         * 拆出 [phase] 让 UI 能告诉用户“在干什么”，而不是只显示静态的“准备中…”。
         */
        data class Preparing(val phase: PrepPhase = PrepPhase.START) : State

        /**
         * ★准备阶段的子步骤（用于 UI 文案）。
         *
         * 背景：实测从点「快速」到首块推理开始要 **13 秒**（主要是解码 150s 的 mp3），
         * 期间 UI 只显示静态“准备中…”→ 用户以为卡死。有了子步骤至少能看出“在动”。
         */
        enum class PrepPhase {
            /** 刚启动。 */
            START,
            /** 解码/重采样输入（mp3/flac 需先解成 PCM，通常是最耗时的一步）。 */
            DECODING,
            /** 加载分离模型（~0.4s）。 */
            LOADING_MODEL,
            /** 模型就绪，即将开始推理。 */
            MODEL_READY,
        }

        data class Running(
            val doneFrames: Int,
            val totalFrames: Int,
            val elapsedMs: Long,
            val peakPssMb: Float,
        ) : State {
            val percent: Int
                get() = if (totalFrames > 0) doneFrames * 100 / totalFrames else 0
        }

        data class Done(
            val vocalsFile: File,
            val accompanimentFile: File,
            val modelLoadMs: Long,
            val inferTimeMs: Long,
            val peakPssMb: Float,
            val audioDurationMs: Long,
            /** true = 命中缓存,未真正推理 */
            val fromCache: Boolean = false,
        ) : State

        /**
         * **M4/S3：4-stem 分离完成**（vocals / drums / bass / other）。
         *
         * 与 [Done] 并列而非合并：2-stem 路径已被大量测试与 UI 依赖，
         * 4-stem 走新增并行路径，不改动既有行为（见计划“不动已工作代码”原则）。
         *
         * @param stemFiles 按 [Spleeter4Dsp.STEM_KINDS] 顺序：vocals/drums/bass/other
         */
        data class Done4(
            val stemFiles: List<File>,
            val inferTimeMs: Long,
            val peakPssMb: Float,
            val audioDurationMs: Long,
            val fromCache: Boolean = false,
        ) : State

        data class Failed(val message: String, val cause: Throwable? = null) : State
        data object Cancelled : State

        /**
         * ★G-3fix：边分离边播**完成**（无落盘文件）。
         *
         * 为何不用 [Done]：edge 的输出只进 ring buffer（边算边播），**不写 wav 文件**；
         * [Done] 必带 `vocalsFile/accompanimentFile`，语义不匹配。
         *
         * ⚠️ **曾经缺失此终态** → `startEdge()` 跑完后 `_state` 永远停在
         * `Running(99)`（最后一块因 overlap 导致 done<total）⇒ UI 卡在“分离中 99%”
         * 且 `busy` 永为 true（取消按钮不消失）。这是用户报的“卡 99%”的根因。
         */
        data class EdgeDone(
            val inferTimeMs: Long,
            val peakPssMb: Float,
            val audioDurationMs: Long,
            /** ★G-3fix：落盘的 stem 文件（内容 = [startFrame, 曲尾)）；null = 落盘失败。 */
            val vocalsFile: File? = null,
            val accompanimentFile: File? = null,
            /** 落盘文件对应的起始帧（播放切过去时 seek 到这里）。 */
            val startFrame: Int = 0,
            val sampleRate: Int = 44_100,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 应用级作用域:不随页面销毁(进程级存活) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 启动分离。若已有任务在跑则先取消旧任务。
     *
     * @param source 输入音频(本地可随机访问的文件)
     * @param engine Spleeter / UVR
     * @param uvrModelName UVR 模型文件名(engine=UVR 时用)
     * @param chunkOverrideFrames 块长覆写(实验用)
     */
    fun start(
        source: File,
        engine: Int = SherpaSourceSeparation.MODE_SPLEETER,
        uvrModelName: String = "UVR-MDX-NET-Voc_FT.onnx",
        chunkOverrideFrames: Int? = null,
    ) {
        // V4（并发多首歌）：已有任务在跑则先取消旧的，启动新的。
        // 行为定义：后发起的请求胜（用户导入新歌后旧任务无意义）。
        if (job?.isActive == true) {
            Log.i(TAG, "start: cancelling running job (concurrent request wins)")
        }
        job?.cancel()
        // ★G-4：升前台（分离窗口跑在 dataSync 前台服务里，避免后台被 /background cgroup 降频 ~2×）。
        SeparationService.start(app)
        job = scope.launch {
            try {
                _state.value = State.Preparing(State.PrepPhase.START)

                val engineTag = engineLabel(engine, uvrModelName)
                val outDir = File(app.filesDir, "stems-cache/${contentHash(source)}-$engineTag")

                // F3.3:缓存命中直接返回
                val cached = readCache(outDir)
                if (cached != null) {
                    Log.i(TAG, "cache HIT: ${outDir.absolutePath}")
                    _state.value = cached
                    return@launch
                }

                val result = separate(source, engine, uvrModelName, chunkOverrideFrames, outDir)
                writeCacheManifest(outDir, result)
                _state.value = result
            } catch (ce: CancellationException) {
                Log.i(TAG, "separation cancelled")
                _state.value = State.Cancelled
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "separation failed", t)
                _state.value = State.Failed("分离失败: ${t.message}", t)
            }
        }
    }

    /**
     * ★G-3：开始一次**边分离边播**（从 [fromPositionMs] 当前位置起，2-stem Spleeter）。
     *
     * 与 [start] 的区别：
     * - 从当前位置起（不从头），首段就绪快；
     * - 输出写 [EdgeSeparationSession] 的两个 ring buffer（不写文件/不写缓存）；
     * - 首段就绪 → `session.markFirstSegmentReady`（上层据此自动切轨）。
     *
     * 仅 Spleeter 2-stem（快速档）支持；其他引擎仍走 [start]/[start4]/[startDemucs]。
     */
    fun startEdge(
        source: File,
        fromPositionMs: Long,
        uvrModelName: String = "UVR-MDX-NET-Voc_FT.onnx",
    ) {
        job?.cancel()
        SeparationService.start(app)
        val session = EdgeSeparationSession.get()
        job = scope.launch {
            // ★取消支持：捕获本协程的 Job（`cancel()` 会把外层 `job` 置 null，不能用它判断）。
            val thisJob = coroutineContext[Job]
            try {
                _state.value = State.Preparing(State.PrepPhase.DECODING)
                // 1) 解码/重采样输入
                val frameSource = SeparationInput.open(app, source)
                try {
                    val startFrame = (fromPositionMs.coerceAtLeast(0) * frameSource.sampleRate / 1000L)
                        .toInt().coerceIn(0, frameSource.totalFrames)
                    // 2) 初始化会话 buffers（传 totalFrames → 合成 WAV 头能算出正确时长）
                    session.begin(startFrame, frameSource.sampleRate, frameSource.channels, frameSource.totalFrames)

                    // ★G-3fix：并行落盘到缓存目录。
                    //   分离完成后 buffer 会被关（读端 EOF）→ 播放器误判 STATE_ENDED。
                    //   落盘后上层可无缝切到文件播放，支持 seek 到 startFrame 之后。
                    val outDir = File(app.filesDir, "stems-cache/${contentHash(source)}-spleeter")
                    var vWriter: WavWriter? = null
                    var aWriter: WavWriter? = null
                    runCatching {
                        outDir.mkdirs()
                        vWriter = WavWriter(File(outDir, "vocals.edge.wav"), frameSource.channels, frameSource.sampleRate)
                        aWriter = WavWriter(File(outDir, "accompaniment.edge.wav"), frameSource.channels, frameSource.sampleRate)
                        Log.i(TAG, "edge: 落盘到 ${outDir.absolutePath}（from frame=$startFrame）")
                    }.onFailure {
                        Log.w(TAG, "edge: 落盘初始化失败，将仅边播不落盘", it)
                        vWriter = null
                        aWriter = null
                    }
                    try {
                        // 3) 定位模型（仅 Spleeter 2-stem）
                        _state.value = State.Preparing(State.PrepPhase.LOADING_MODEL)
                        val (vocalsPath, accompPath) = resolveModels(SherpaSourceSeparation.MODE_SPLEETER, uvrModelName)
                        val sep = SherpaSourceSeparation.create(
                            vocalsPath, accompPath,
                            numThreads = NUM_THREADS,
                            mode = SherpaSourceSeparation.MODE_SPLEETER,
                        )
                        try {
                            val schedule = SegmentedSeparator.defaultSchedule(frameSource.sampleRate)
                            // ★G-3fix：推理计时起点（提前于 Separator 构造）。
                            val t0 = System.currentTimeMillis()
                            val stats = SegmentedSeparator(
                                reader = frameSource,
                                separator = sep::separate,
                                // ★Step 2：输出走回调（本步先保持旧行为：写 ring buffer + writer）。
                                onWrite = { v, a, frames, absFrame ->
                                    session.vocalsBuffer?.append(v, 0, frames)
                                    session.accompBuffer?.append(a, 0, frames)
                                    // 旧 writer 是顺序 append；此处 absFrame 递增，等价。
                                    vWriter?.appendRegion(v, 0, frames)
                                    aWriter?.appendRegion(a, 0, frames)
                                },
                                startFrame = startFrame,
                                chunkSchedule = schedule,
                                onProgress = { done, total ->
                                    // ★G-3fix：用浮点算再取整，避免最后一块因 overlap 导致
                                    //   done*100/total 少 1（永远到不了 100）。
                                    val pct = if (total > 0) {
                                        ((done.toDouble() * 100.0) / total).toInt().coerceIn(0, 100)
                                    } else 0
                                    session.updateProgress(pct)
                                    _state.value = State.Running(done, total, 0L, currentPssMb())
                                },
                                onFirstSegmentReady = {
                                    session.markFirstSegmentReady(1)
                                },
                                // ★取消支持：每块开始前检查 job 是否已取消。
                                //   注意：不能读外层 `job`（cancel() 会置 null）→ 持久化到局部 val。
                                isCancelled = { thisJob?.isActive == false },
                            ).run()
                            // ★G-3fix（关键）：**必须发终态**，否则 _state 永远停在 Running(99)。
                            session.complete()
                            val audioMs = frameSource.totalFrames.toLong() * 1000L /
                                frameSource.sampleRate.coerceAtLeast(1)
                            _state.value = State.EdgeDone(
                                inferTimeMs = System.currentTimeMillis() - t0,
                                peakPssMb = currentPssMb(),
                                audioDurationMs = audioMs,
                                vocalsFile = File(outDir, "vocals.edge.wav").takeIf { vWriter != null },
                                accompanimentFile = File(outDir, "accompaniment.edge.wav").takeIf { aWriter != null },
                                startFrame = startFrame,
                                sampleRate = frameSource.sampleRate,
                            )
                            Log.i(TAG, "edge done: vFrames=${stats.vocalsFrames} aFrames=${stats.accompFrames} " +
                                "chunks=${stats.chunks} files=${vWriter != null}")
                        } finally {
                            sep.release()
                        }
                    } finally {
                        // 关闭落盘文件（回填 WAV 头部 size）。幂等。
                        runCatching { vWriter?.close() }.onFailure { Log.w(TAG, "close vocals.edge.wav failed", it) }
                        runCatching { aWriter?.close() }.onFailure { Log.w(TAG, "close accomp.edge.wav failed", it) }
                    }
                } finally {
                    frameSource.close()
                }
            } catch (ce: CancellationException) {
                session.abort()
                _state.value = State.Cancelled
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "edge separation failed", t)
                session.fail(t.message ?: "edge separation failed")
                _state.value = State.Failed("边分离边播失败: ${t.message}", t)
            }
        }
    }

    /** guide N5:取消正在跑的分离任务 */
    fun cancel() {
        job?.cancel()
        job = null
    }

    // ---------------- M4/S3：4-stem 分离 ----------------

    /**
     * 启动 **4-stem** 分离（vocals/drums/bass/other）。
     *
     * 与 [start] 互斥：新任务先取消旧任务。结果放
     * `stems-cache/<hash>-spleeter4/`（含 manifest 缓存）。
     *
     * @param modelDir 4 个 fp16 模型目录（默认 `filesDir/spleeter4`）
     */
    fun start4(source: File, modelDir: File? = null) {
        if (job?.isActive == true) {
            Log.i(TAG, "start4: cancelling running job (concurrent request wins)")
        }
        job?.cancel()
        SeparationService.start(app)   // ★G-4 升前台
        job = scope.launch {
            try {
                _state.value = State.Preparing(State.PrepPhase.START)
                val dir = modelDir ?: File(app.filesDir, "spleeter4")
                val outDir = File(app.filesDir, "stems-cache/${contentHash(source)}-spleeter4")

                readCache4(outDir)?.let {
                    Log.i(TAG, "cache HIT (4-stem): ${outDir.absolutePath}")
                    _state.value = it
                    return@launch
                }

                val result = separate4(source, dir, outDir)
                writeCacheManifest4(outDir, result)
                _state.value = result
            } catch (ce: CancellationException) {
                Log.i(TAG, "4-stem separation cancelled")
                _state.value = State.Cancelled
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "4-stem separation failed", t)
                _state.value = State.Failed("4-stem 分离失败: ${t.message}", t)
            }
        }
    }

    private suspend fun separate4(
        source: File,
        modelDir: File,
        outDir: File,
    ): State.Done4 = withContext(Dispatchers.IO) {
        val frameSource = SeparationInput.open(app, source)
        var estimator: Spleeter4Estimator? = null
        try {
            estimator = Spleeter4Estimator.create(modelDir, threads = NUM_THREADS)

            outDir.mkdirs()
            val outFiles = Spleeter4Dsp.STEM_KINDS.map { File(outDir, "$it.wav") }
            var peak = currentPssMb()
            val inferStart = System.currentTimeMillis()

            val writers = outFiles.map { WavWriter(it, frameSource.channels, frameSource.sampleRate) }
            try {
                Spleeter4Separator(
                    reader = frameSource,
                    writers = writers,
                    estimator = estimator!!,
                    onProgress = { done, total ->
                        peak = maxOf(peak, currentPssMb())
                        _state.value = State.Running(
                            doneFrames = done,
                            totalFrames = total,
                            elapsedMs = System.currentTimeMillis() - inferStart,
                            peakPssMb = peak,
                        )
                    },
                ).run()
            } finally {
                writers.forEach { runCatching { it.close() } }
            }

            peak = maxOf(peak, currentPssMb())
            val inferMs = System.currentTimeMillis() - inferStart
            val audioMs = frameSource.totalFrames * 1000L / frameSource.sampleRate
            val rtf = audioMs / 1000.0 / (inferMs / 1000.0)
            Log.i(
                TAG,
                "4-stem done: infer=${inferMs}ms audio=${audioMs}ms " +
                    "realtime=${String.format("%.2f", rtf)}x " +
                    "peakPss=${String.format("%.1f", peak)}MB",
            )
            State.Done4(
                stemFiles = outFiles,
                inferTimeMs = inferMs,
                peakPssMb = peak,
                audioDurationMs = audioMs,
            )
        } finally {
            runCatching { estimator?.close() }
            frameSource.close()
        }
    }

    private fun readCache4(cacheDir: File): State.Done4? {
        val manifest = File(cacheDir, "manifest4.txt")
        if (!manifest.exists()) return null
        val files = Spleeter4Dsp.STEM_KINDS.map { File(cacheDir, "$it.wav") }
        if (files.any { !it.exists() }) return null
        return try {
            val parts = manifest.readText().trim().split(",")
            State.Done4(
                stemFiles = files,
                inferTimeMs = parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                peakPssMb = 0f,
                audioDurationMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                fromCache = true,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "4-stem cache manifest unreadable", t)
            null
        }
    }

    private fun writeCacheManifest4(cacheDir: File, d: State.Done4) {
        runCatching {
            File(cacheDir, "manifest4.txt").writeText("${d.inferTimeMs},${d.audioDurationMs}")
        }.onFailure { Log.w(TAG, "cannot write 4-stem cache manifest", it) }
    }

    // ---------------- M4/S2 落地：Demucs 4 源 ----------------

    /**
     * 启动 **Demucs（htdemucs）4 源**分离。输出顺序 [DemucsSeparator.STEM_KINDS]
     * = drums/bass/other/vocals。模型默认 `filesDir/demucs/htdemucs_embedded.onnx`。
     */
    fun startDemucs(source: File, modelFile: File? = null, threads: Int = DEMUCS_THREADS, useNnapi: Boolean = false) {
        if (job?.isActive == true) Log.i(TAG, "startDemucs: cancelling running job")
        job?.cancel()
        SeparationService.start(app)   // ★G-4 升前台
        job = scope.launch {
            try {
                _state.value = State.Preparing(State.PrepPhase.START)
                val mdl = modelFile ?: File(app.filesDir, "demucs/htdemucs_embedded.onnx")
                val outDir = File(app.filesDir, "stems-cache/${contentHash(source)}-demucs")

                readCacheDemucs(outDir)?.let {
                    Log.i(TAG, "cache HIT (demucs): ${outDir.absolutePath}")
                    _state.value = it
                    return@launch
                }
                val result = separateDemucs(source, mdl, outDir, threads, useNnapi)
                writeCacheManifest4(outDir, result)
                _state.value = result
            } catch (ce: CancellationException) {
                Log.i(TAG, "demucs separation cancelled")
                _state.value = State.Cancelled
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "demucs separation failed", t)
                _state.value = State.Failed("Demucs 分离失败: ${t.message}", t)
            }
        }
    }

    private suspend fun separateDemucs(
        source: File,
        model: File,
        outDir: File,
        threads: Int,
        useNnapi: Boolean,
    ): State.Done4 = withContext(Dispatchers.IO) {
        val frameSource = SeparationInput.open(app, source)
        var estimator: DemucsEstimator? = null
        try {
            estimator = DemucsEstimator.create(model, threads = threads, useNnapi = useNnapi)
            outDir.mkdirs()
            val outFiles = DemucsSeparator.STEM_KINDS.map { File(outDir, "$it.wav") }
            var peak = currentPssMb()
            val inferStart = System.currentTimeMillis()

            val writers = outFiles.map { WavWriter(it, frameSource.channels, frameSource.sampleRate) }
            try {
                DemucsSeparator(
                    reader = frameSource,
                    writers = writers,
                    estimator = estimator!!,
                    onProgress = { done, total ->
                        peak = maxOf(peak, currentPssMb())
                        _state.value = State.Running(
                            doneFrames = done,
                            totalFrames = total,
                            elapsedMs = System.currentTimeMillis() - inferStart,
                            peakPssMb = peak,
                        )
                    },
                ).run()
            } finally {
                writers.forEach { runCatching { it.close() } }
            }

            peak = maxOf(peak, currentPssMb())
            val inferMs = System.currentTimeMillis() - inferStart
            val audioMs = frameSource.totalFrames * 1000L / frameSource.sampleRate
            val rtf = audioMs / 1000.0 / (inferMs / 1000.0)
            Log.i(
                TAG,
                "demucs done: infer=${inferMs}ms audio=${audioMs}ms " +
                    "realtime=${String.format("%.2f", rtf)}x " +
                    "peakPss=${String.format("%.1f", peak)}MB",
            )
            State.Done4(outFiles, inferMs, peak, audioMs)
        } finally {
            runCatching { estimator?.close() }
            frameSource.close()
        }
    }

    private fun readCacheDemucs(cacheDir: File): State.Done4? {
        val manifest = File(cacheDir, "manifest4.txt")
        if (!manifest.exists()) return null
        val files = DemucsSeparator.STEM_KINDS.map { File(cacheDir, "$it.wav") }
        if (files.any { !it.exists() }) return null
        return try {
            val parts = manifest.readText().trim().split(",")
            State.Done4(
                stemFiles = files,
                inferTimeMs = parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                peakPssMb = 0f,
                audioDurationMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                fromCache = true,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "demucs cache manifest unreadable", t)
            null
        }
    }

    /** 复位(仅在无任务运行时) */
    fun reset() {
        if (isRunning) return
        _state.value = State.Idle
    }

    /**
     * V4（技术债收口）：清理分离结果缓存（`filesDir/stems-cache/` 下的全部目录）。
     *
     * 背景：分离产物会随使用不断累积（每首/每引擎一份，实测 4 分钟歌 ~85MB），
     * 但此前无任何 UI 管理入口（HANDOFF §十 已记为技术债）。
     *
     * 安全约束：有任务在跑时**拒绝**清理（否则删掉正在写的目录）。
     *
     * @return 删除的目录数；-1 表示因任务在跑而拒绝
     */
    fun clearCache(): Int {
        if (isRunning) {
            Log.w(TAG, "clearCache refused: separation running")
            return -1
        }
        val root = File(app.filesDir, "stems-cache")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        var n = 0
        dirs.forEach { d ->
            if (d.deleteRecursively()) n++ else Log.w(TAG, "failed to delete ${d.absolutePath}")
        }
        Log.i(TAG, "clearCache: removed $n cache dir(s)")
        return n
    }

    // ---------------- 内部:实际分离 ----------------

    private suspend fun separate(
        source: File,
        engine: Int,
        uvrModelName: String,
        chunkOverrideFrames: Int?,
        outDir: File,
    ): State.Done = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        // 1) 准备输入(解码 / 重采样 / 补声道)
        _state.value = State.Preparing(State.PrepPhase.DECODING)
        val frameSource = SeparationInput.open(app, source)
        try {
            // 2) 定位模型
            _state.value = State.Preparing(State.PrepPhase.LOADING_MODEL)
            val (vocalsPath, accompPath) = resolveModels(engine, uvrModelName)

            // 2b) 阶段 M2 任务 A:取模型元信息(决定是否需要交换两轨——Inst 系反标签)
            val spec = if (engine == SherpaSourceSeparation.MODE_UVR) {
                ModelRegistry.UVR_VARIANTS.firstOrNull { it.fileName == uvrModelName }
                    ?: ModelRegistry.byId("uvr-" + uvrModelName.removeSuffix(".onnx"))
                    ?: ModelRegistry.SPLEETER  // 未知模型:按正常顺序(安全默认)
            } else {
                ModelRegistry.SPLEETER
            }

            // 3) 建引擎
            val t0 = System.currentTimeMillis()
            val sep = SherpaSourceSeparation.create(
                vocalsPath, accompPath,
                numThreads = NUM_THREADS,
                mode = engine,
            )
            val loadMs = System.currentTimeMillis() - t0
            Log.i(TAG, "engine ready in ${loadMs}ms")
            _state.value = State.Preparing(State.PrepPhase.MODEL_READY)

            // 4) 流式分离
            outDir.mkdirs()
            val vocalsFile = File(outDir, "vocals.wav")
            val accompFile = File(outDir, "accompaniment.wav")
            var peak = currentPssMb()
            val inferStart = System.currentTimeMillis()

            try {
                val chunk = chunkOverrideFrames
                    ?: if (engine == SherpaSourceSeparation.MODE_UVR) {
                        UVR_CHUNK_FRAMES
                    } else {
                        StreamingSeparator.DEFAULT_CHUNK_FRAMES
                    }

                WavWriter(vocalsFile, frameSource.channels, frameSource.sampleRate).use { vw ->
                    WavWriter(accompFile, frameSource.channels, frameSource.sampleRate).use { aw ->
                        // ★ 阶段 M2 任务 A:Inst 系模型运行时两轨对调 → 交换写入目标，
                        //   使 vocals.wav 真的装人声、accompaniment.wav 真的装伴奏。
                        //   （separator 输出的「第 0 流/第 1 流」语义固定，我们只是反向接线）
                        val (stream0Writer, stream1Writer) =
                            if (spec.needsStemSwap) aw to vw else vw to aw
                        StreamingSeparator(
                            reader = frameSource,
                            vocalsWriter = stream0Writer,
                            accompWriter = stream1Writer,
                            separator = sep::separate,
                            chunkFrames = chunk,
                            onProgress = { done, total ->
                                peak = maxOf(peak, currentPssMb())
                                _state.value = State.Running(
                                    doneFrames = done,
                                    totalFrames = total,
                                    elapsedMs = System.currentTimeMillis() - inferStart,
                                    peakPssMb = peak,
                                )
                            },
                        ).run()
                    }
                }
            } finally {
                sep.release()
            }

            peak = maxOf(peak, currentPssMb())
            val inferMs = System.currentTimeMillis() - inferStart
            val audioMs = frameSource.totalFrames * 1000L / frameSource.sampleRate
            Log.i(
                TAG,
                "separation done: total=${System.currentTimeMillis() - started}ms " +
                    "infer=${inferMs}ms audio=${audioMs}ms " +
                    "realtime=${"%.2f".format(audioMs / 1000.0 / (inferMs / 1000.0))}x " +
                    "peakPss=${"%.1f".format(peak)}MB",
            )

            State.Done(
                vocalsFile = vocalsFile,
                accompanimentFile = accompFile,
                modelLoadMs = loadMs,
                inferTimeMs = inferMs,
                peakPssMb = peak,
                audioDurationMs = audioMs,
            )
        } finally {
            frameSource.close()
        }
    }

    private fun resolveModels(engine: Int, uvrModelName: String): Pair<String, String?> {
        if (engine == SherpaSourceSeparation.MODE_UVR) {
            // 先在应用**私有**目录找(坑 41:外部 files/uvr/ 由 shell 属主写入时 app 看不到),
            // 再回退到外部目录(兼容手动 push 的旧环境)。
            val priv = File(app.filesDir, "uvr")
            val extDir = File(app.getExternalFilesDir(null), "uvr")
            val model = listOf(File(priv, uvrModelName), File(extDir, uvrModelName))
                .firstOrNull { it.exists() }
                ?: error(
                    "UVR 模型缺失: 请放到私有目录 ${File(priv, uvrModelName).absolutePath}" +
                        "（run-as 写入）或外部 ${File(extDir, uvrModelName).absolutePath}",
                )
            return model.absolutePath to null
        }
        val modelDir = File(app.filesDir, "sherpa")
        val vocals = File(modelDir, "vocals.fp16.onnx")
        val accomp = File(modelDir, "accompaniment.fp16.onnx")
        if (!vocals.exists() || !accomp.exists()) {
            error(
                "Spleeter 模型缺失: 请把 vocals/accompaniment.fp16.onnx " +
                    "放到 ${modelDir.absolutePath}",
            )
        }
        return vocals.absolutePath to accomp.absolutePath
    }

    // ---------------- F3.3 缓存 ----------------

    /** 输入文件内容哈希(首歌级缓存键)—— 不依赖文件名/路径 */
    private fun contentHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun engineLabel(engine: Int, uvrModelName: String): String =
        if (engine == SherpaSourceSeparation.MODE_UVR) {
            "uvr-${uvrModelName.removeSuffix(".onnx")}"
        } else {
            "spleeter"
        }

    private fun readCache(cacheDir: File): State.Done? {
        val manifest = File(cacheDir, "manifest.txt")
        if (!manifest.exists()) return null
        val vocals = File(cacheDir, "vocals.wav")
        val accomp = File(cacheDir, "accompaniment.wav")
        if (!vocals.exists() || !accomp.exists()) return null
        return try {
            val parts = manifest.readText().trim().split(",")
            State.Done(
                vocalsFile = vocals,
                accompanimentFile = accomp,
                modelLoadMs = 0,
                inferTimeMs = parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                peakPssMb = 0f,
                audioDurationMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                fromCache = true,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cache manifest unreadable, will re-run", t)
            null
        }
    }

    private fun writeCacheManifest(cacheDir: File, d: State.Done) {
        runCatching {
            File(cacheDir, "manifest.txt").writeText("${d.inferTimeMs},${d.audioDurationMs}")
        }.onFailure { Log.w(TAG, "cannot write cache manifest", it) }
    }

    private fun currentPssMb(): Float {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss / 1024f
    }

    companion object {
        private const val TAG = "SepRepo"
        const val NUM_THREADS = 4

        /** Demucs 默认线程数。
         *  ★ 实测（Gen3/8 核大小核）：6 最快（4→38s、6→32s、8→51s）。
         *  8 线程会超订（调度到小核 + 与系统争抢）—— 反而变慢。 */
        const val DEMUCS_THREADS = 6

        /** UVR 模式块长约束(模型内部 chunk ≈5.92s,见 Task 0g) */
        const val UVR_CHUNK_FRAMES = 220_500

        /** 进程级单例(Task 4.2:任务不随页面销毁) */
        @Volatile
        private var instance: SeparationRepository? = null

        fun get(app: Application): SeparationRepository =
            instance ?: synchronized(this) {
                instance ?: SeparationRepository(app).also { instance = it }
            }
    }
}
