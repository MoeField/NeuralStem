package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pub.miku.neuralstem.spike.WavReader
import java.io.File

/**
 * 振幅包络数据:把音频切成 [pointCount] 个时间片,每片记录 min/max。
 * 绘制时每片画一根竖线(从 min 到 max),这就是主流播放器波形的画法。
 */
data class WaveformData(
    val min: FloatArray,
    val max: FloatArray,
    val sampleRate: Int,
    val totalFrames: Long,
) {
    val pointCount: Int get() = min.size
    val durationMs: Long get() = totalFrames * 1000 / sampleRate

    /** 换算:播放位置(ms) → 包络下标 */
    fun indexForPositionMs(ms: Long): Int {
        if (durationMs <= 0) return 0
        return ((ms * pointCount) / durationMs).toInt().coerceIn(0, pointCount - 1)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return min.contentEquals(other.min) && max.contentEquals(other.max) &&
            sampleRate == other.sampleRate && totalFrames == other.totalFrames
    }

    override fun hashCode(): Int =
        min.contentHashCode() * 31 + max.contentHashCode() * 7 + sampleRate
}

/**
 * Task 2:波形包络提取(guide F1.1)。
 *
 * - wav:复用 Task 0e 的 [WavReader](按帧随机读,内存只占一个读块)
 * - mp3/flac/aac 等:走 Media3 的 Extractor 解码(Task 1 已依赖 media3,零新增)
 * - 每秒固定 [POINTS_PER_SECOND] 个包络点,与采样率无关,数据量可控
 *   (5 分钟歌 = 15000 点 × 2 × 4B ≈ 120KB,远小于波形库常见做法)
 *
 * 输出经 StateFlow 交给 [WaveformView];提取在 Default 线程池,可取消(切歌时)。
 *
 * R-h(W-2,从 checkpoint 线移植)：
 * - **缓存优先**(仅 [AudioSource.MediaUri])：命中 [WaveformCache] 则**秒出**，免除全曲解码。
 * - **流式 + 节流 emit**(仅 MediaUri)：边解码边算包络，每 ≥[EMIT_THROTTLE_MS] ms emit 一次
 *   已解码部分 → 进页 ~1s 内即可显示波形开头并逐步铺满(不再整曲一次性出现)。
 * - **只缓存完整波形**：中途被取消(切歌)会得到“半截波形”，写缓存会导致下次命中残缺
 *   (参 checkpoint `.critical_steps.md` 坑 60)，故仅 `complete==true` 时落盘。
 */
class WaveformExtractor(
    private val scope: CoroutineScope,
) {

    private val _waveform = MutableStateFlow<WaveformData?>(null)
    val waveform: StateFlow<WaveformData?> = _waveform.asStateFlow()

    private var job: Job? = null

    /** 取消上一次提取(切歌时),启动新的 */
    fun extractAsync(source: AudioSource) {
        job?.cancel()
        _waveform.value = null
        job = scope.launch(Dispatchers.Default) {
            // 本协程的 Job（供同步解码循环检查“是否已取消”，见 extractFromExtractor）。
            val selfJob = currentCoroutineContext()[Job]
            try {
                // ★ 缓存优先：命中则秒出，免除全曲解码。
                val cached = when (source) {
                    is AudioSource.MediaUri ->
                        WaveformCache.load(contextProvider(), source.uri)
                    is AudioSource.RawBytes -> {
                        // ncm 没有真实文件，stamp 用**缓存键本身**（见 AudioSource.RawBytes 注释：
                        // key 已包含 store 分配的稳定 hash，内容变则 key 变）。
                        // 注意：不要在这里调 bytesProvider() —— 那是 30MB 的解密结果，
                        // 仅为了算缓存 key 而取一遍不划算。
                        WaveformCache.load(contextProvider(), source.cacheKey, source.cacheKey)
                    }
                    is AudioSource.WavFile -> null
                }
                if (cached != null) {
                    Log.i(TAG, "waveform(cache hit): points=${cached.pointCount}, dur=${cached.durationMs}ms")
                    _waveform.value = cached
                    return@launch
                }

                val result = when (source) {
                    is AudioSource.WavFile -> ExtractResult(extractFromWav(source.file), complete = true)
                    is AudioSource.MediaUri -> extractFromExtractor(source, selfJob)
                    is AudioSource.RawBytes -> extractFromBytes(source, selfJob)
                }
                Log.i(
                    TAG,
                    "waveform ready: points=${result.data.pointCount}, dur=${result.data.durationMs}ms complete=${result.complete}",
                )
                // ★ 空包络（0 点）不要推给 UI：那会画出一道空白坐标轴，
                //   比“没有波形”更让人困惑（用户会以为波形成了直线）。
                _waveform.value = result.data.takeIf { it.pointCount > 0 }
                // ★ 只在**完整解完**时写缓存：中途被取消会得到“半截波形”，
                //   若写入，下次命中就会显示残缺（曾导致某首歌波形“卡在一半”）。
                if (result.complete && result.data.pointCount > 0) {
                    when (source) {
                        is AudioSource.MediaUri ->
                            WaveformCache.save(contextProvider(), source.uri, result.data)
                        is AudioSource.RawBytes ->
                            WaveformCache.save(
                                contextProvider(), source.cacheKey, source.cacheKey, result.data,
                            )
                        is AudioSource.WavFile -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // ★ 日志带上源类型 + 标识：下次一眼能定位是 ncm/content/file 哪个挂的。
                Log.e(TAG, "waveform extraction failed: ${describe(source)}", t)
                _waveform.value = null
            }
        }
    }

    /** 源描述（日志用）：scheme 一眼可见，方便定位是哪类源挂的。 */
    private fun describe(source: AudioSource): String = when (source) {
        is AudioSource.WavFile -> "file(wav)=${source.file.name}"
        is AudioSource.MediaUri -> "uri(${source.uri.scheme})=${source.uri}"
        is AudioSource.RawBytes -> "rawBytes=${source.cacheKey}"
    }

    companion object {
        private const val TAG = "Waveform"
        const val POINTS_PER_SECOND = 50

        /** 增量 emit 节流（ms）：避免每块都重组/刷新。 */
        private const val EMIT_THROTTLE_MS = 300L

        /**
         * 从 float PCM 交织数据计算包络。
         * internal 供单测直接验证(不需要真音频文件)。
         */
        internal fun computeEnvelope(
            samples: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): WaveformData {
            require(channels in 1..2) { "only mono/stereo, got $channels" }
            val totalFrames = (samples.size / channels).toLong()
            val points = (totalFrames * POINTS_PER_SECOND / sampleRate)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val framesPerPoint = (totalFrames / points).coerceAtLeast(1L)

            val min = FloatArray(points) { 0f }
            val max = FloatArray(points) { 0f }
            for (p in 0 until points) {
                val startFrame = p * framesPerPoint
                val endFrame = minOf(startFrame + framesPerPoint, totalFrames).toInt()
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                var i = (startFrame * channels).toInt()
                val end = (endFrame * channels).toInt()
                // 交织数据步进 channels:每帧只看第 0 声道即可(包络用途足够),
                // 省一半遍历;立体声相位差对包络影响可忽略
                while (i < end) {
                    val v = samples[i]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    i += channels
                }
                min[p] = if (lo == Float.MAX_VALUE) 0f else lo
                max[p] = if (hi == -Float.MAX_VALUE) 0f else hi
            }
            return WaveformData(min, max, sampleRate, totalFrames)
        }
    }

    private fun extractFromWav(file: File): WaveformData {
        WavReader(file).use { reader ->
            val sr = reader.sampleRate
            val total = reader.totalFrames
            val points = (total.toLong() * POINTS_PER_SECOND / sr)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val framesPerPoint = (total.toLong() / points).coerceAtLeast(1L)
            val min = FloatArray(points)
            val max = FloatArray(points)
            // 流式:每次读一个包络点对应的帧块,内存 = 块大小,不整曲驻留(guide N2)
            val blockFrames = framesPerPoint.coerceAtMost(1 shl 20) // 上限 1M 帧
            for (p in 0 until points) {
                val start = (p * framesPerPoint).toInt()
                val want = minOf(
                    framesPerPoint.toInt(),
                    total - start,
                ).coerceAtLeast(0)
                if (want <= 0) { min[p] = 0f; max[p] = 0f; continue }
                val block = reader.readFrames(start, want)
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                var i = 0
                val ch = reader.channels
                while (i < block.size) {
                    val v = block[i]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    i += ch
                }
                min[p] = if (lo == Float.MAX_VALUE) 0f else lo
                max[p] = if (hi == -Float.MAX_VALUE) 0f else hi
            }
            return WaveformData(min, max, sr, total.toLong())
        }
    }

    /**
     * 从 MediaUri 提取包络（流式）。
     * @return [ExtractResult]：`data` 为包络；`complete` 表示**是否完整解完**
     *   （false = 被取消/中途终止）——**只有完整时才应写缓存**，否则会缓存“半截波形”。
     */
    private fun extractFromExtractor(source: AudioSource.MediaUri, selfJob: Job?): ExtractResult {
        val accum = EnvelopeAccumulator(selfJob)
        val completed = Media3DecodeBridge
            .decodeToPcmStreaming(contextProvider(), source.uri, accum::onChunk)
        return accum.finish(completed, "stream")
    }

    /**
     * ★从**内存字节**提取包络（NCM 解密产物等无 uri 的源）。
     *
     * 与 [extractFromExtractor] 共用 [EnvelopeAccumulator]，区别只在“从哪拿 PCM”。
     */
    private fun extractFromBytes(source: AudioSource.RawBytes, selfJob: Job?): ExtractResult {
        val bytes = source.bytesProvider()
        if (bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "RawBytes 取不到数据（${source.cacheKey}）")
            return ExtractResult(WaveformData(FloatArray(0), FloatArray(0), 0, 0L), complete = false)
        }
        Log.i(TAG, "RawBytes 解码：${source.cacheKey}（${bytes.size / 1_048_576.0}MB）")
        val accum = EnvelopeAccumulator(selfJob)
        val completed = Media3DecodeBridge.decodeToPcmStreaming(bytes, accum::onChunk)
        return accum.finish(completed, "bytes")
    }

    /**
     * 包络累加器（两个解码入口共用）。
     *
     * 把“每块 PCM → 20ms 一个包络点”的累加逻辑集中在这里，避免两个入口各写一份。
     * 内部状态跨解码块保留（半满点不会被丢弃）。
     */
    private inner class EnvelopeAccumulator(private val selfJob: Job?) {
        private val accMin = ArrayList<Float>(4096)
        private val accMax = ArrayList<Float>(4096)
        private var framesPerPoint = 0
        private var sampleRate = 0
        private var curLo = Float.MAX_VALUE
        private var curHi = -Float.MAX_VALUE
        private var curFrames = 0
        private var totalFrames = 0L
        private var lastEmitMs = 0L
        private val t0 = System.nanoTime()

        private fun flushPoint() {
            accMin.add(if (curLo == Float.MAX_VALUE) 0f else curLo)
            accMax.add(if (curHi == -Float.MAX_VALUE) 0f else curHi)
            curLo = Float.MAX_VALUE
            curHi = -Float.MAX_VALUE
            curFrames = 0
        }

        /** 回调：返回 false 让解码循环提前终止（切歌/取消）。 */
        fun onChunk(samples: FloatArray, ch: Int, sr: Int): Boolean {
            if (framesPerPoint == 0) {
                sampleRate = sr
                framesPerPoint = (sr / POINTS_PER_SECOND).coerceAtLeast(1)
            }
            var i = 0
            val n = samples.size
            while (i < n) {
                // 取一个声道样本（包络用途足够，省一半遍历）
                val v = samples[i]
                if (v < curLo) curLo = v
                if (v > curHi) curHi = v
                curFrames++
                if (curFrames >= framesPerPoint) flushPoint()
                i += ch
            }
            totalFrames += (n / ch).toLong()
            // 节流 emit：每 ≥300ms 发一次（避免过度重组/刷新）
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= EMIT_THROTTLE_MS && accMin.isNotEmpty()) {
                lastEmitMs = now
                _waveform.value = WaveformData(
                    accMin.toFloatArray(), accMax.toFloatArray(), sampleRate, totalFrames,
                )
            }
            // ★ 关键：协程被取消（切歌）时返回 false → 终止同步解码循环。
            //   否则 job.cancel() 停不住阻塞在 MediaCodec 的解码 → 旧歌波形持续 emit，
            //   与新歌交替 → 外环“乱跳”。
            return selfJob?.isActive ?: true
        }

        /** 收尾：把半满的点也写出去（若 curFrames>0），返回包络 + 是否完整。 */
        fun finish(completed: Boolean, kind: String): ExtractResult {
            if (curFrames > 0) flushPoint()
            val data = WaveformData(
                accMin.toFloatArray(), accMax.toFloatArray(), sampleRate, totalFrames,
            )
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(
                TAG,
                "waveform($kind) done: points=${data.pointCount}, dur=${data.durationMs}ms " +
                    "complete=$completed | total=${totalMs}ms",
            )
            return ExtractResult(data, completed)
        }
    }

    /** 流式提取结果：包络 + 是否完整。 */
    private data class ExtractResult(val data: WaveformData, val complete: Boolean)

    /** 解码桥需要 context;由持有方(VM)注入,避免本类持有 Activity */
    lateinit var contextProvider: () -> Context
}

/** 波形数据来源:文件(wav 直读)或 SAF/Media Uri(走 extractor) */
sealed interface AudioSource {
    data class WavFile(val file: File) : AudioSource
    data class MediaUri(val uri: Uri, val label: String = "") : AudioSource

    /**
     * ★**已解好的整段音频字节**（如 NCM 解密产物）。
     *
     * ## 为何需要（2026-10-26 真机实证）
     * `ncm://<key>` 是自定义 scheme，`MediaExtractor.setDataSource()` **不认识** →
     * 波形提取 16ms 就挂 `IOException: Failed to instantiate extractor`，
     * 波形**完全空白**。而解密后的字节就在 `NcmPlaybackStore` 里（flac/mp3/m4a 原始容器），
     * 拿过来直接喂 `MediaDataSource` 即可。
     *
     * @param bytesProvider 惰性取字节（一首 15–40MB，不要构造时就读）
     * @param cacheKey 稳定标识（如 `ncm://5fda704`）；字节长度做 stamp，见 [WaveformCache]
     */
    data class RawBytes(
        val bytesProvider: () -> ByteArray?,
        val cacheKey: String,
    ) : AudioSource
}