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
                // ★ 缓存优先（仅 MediaUri）：命中则秒出，免除全曲解码。
                if (source is AudioSource.MediaUri) {
                    val cached = WaveformCache.load(contextProvider(), source.uri)
                    if (cached != null) {
                        Log.i(TAG, "waveform(cache hit): points=${cached.pointCount}, dur=${cached.durationMs}ms")
                        _waveform.value = cached
                        return@launch
                    }
                }
                val result = when (source) {
                    is AudioSource.WavFile -> ExtractResult(extractFromWav(source.file), complete = true)
                    is AudioSource.MediaUri -> extractFromExtractor(source, selfJob)
                }
                Log.i(
                    TAG,
                    "waveform ready: points=${result.data.pointCount}, dur=${result.data.durationMs}ms complete=${result.complete}",
                )
                _waveform.value = result.data
                // ★ 只在**完整解完**时写缓存：中途被取消会得到“半截波形”，
                //   若写入，下次命中就会显示残缺（曾导致某首歌波形“卡在一半”）。
                if (source is AudioSource.MediaUri && result.complete) {
                    WaveformCache.save(contextProvider(), source.uri, result.data)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "waveform extraction failed", t)
                _waveform.value = null
            }
        }
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
        // ★ 流式：边解码边算包络，**定期 emit 已解码部分**（不再整曲驻留）→
        //   进页 ~1s 内即可显示波形开头，并逐步铺满。
        // 每包络点固定 20ms（=1/POINTS_PER_SECOND）→ 不需预估总时长；
        // 增量边界天然对齐，pointCount 单调增长。
        val accMin = ArrayList<Float>(4096)
        val accMax = ArrayList<Float>(4096)
        var framesPerPoint = 0
        var sampleRate = 0
        // 当前正在累积的点（跨解码块时保留半满状态）
        var curLo = Float.MAX_VALUE
        var curHi = -Float.MAX_VALUE
        var curFrames = 0
        var totalFrames = 0L
        var lastEmitMs = 0L
        val t0 = System.nanoTime()

        fun flushPoint() {
            accMin.add(if (curLo == Float.MAX_VALUE) 0f else curLo)
            accMax.add(if (curHi == -Float.MAX_VALUE) 0f else curHi)
            curLo = Float.MAX_VALUE; curHi = -Float.MAX_VALUE; curFrames = 0
        }

        val completed = Media3DecodeBridge.decodeToPcmStreaming(contextProvider(), source.uri) { samples, ch, sr ->
            // 首次拿到格式
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
            selfJob?.isActive ?: true
        }
        // 收尾：把半满的点也写出去（若 curFrames>0）
        if (curFrames > 0) flushPoint()
        val data = WaveformData(
            accMin.toFloatArray(), accMax.toFloatArray(), sampleRate, totalFrames,
        )
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(
            TAG,
            "waveform(stream) done: points=${data.pointCount}, dur=${data.durationMs}ms " +
                "complete=$completed | total=${totalMs}ms",
        )
        return ExtractResult(data, completed)
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
}