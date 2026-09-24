package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            try {
                val data = when (source) {
                    is AudioSource.WavFile -> extractFromWav(source.file)
                    is AudioSource.MediaUri -> extractFromExtractor(source)
                }
                Log.i(TAG, "waveform ready: points=${data.pointCount}, dur=${data.durationMs}ms")
                _waveform.value = data
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

    private fun extractFromExtractor(source: AudioSource.MediaUri): WaveformData {
        // 平台 MediaExtractor+MediaCodec 解码(Task 2 修订):覆盖 wav/mp3/flac/aac 全格式
        val decoded = Media3DecodeBridge.decodeToPcm(contextProvider(), source.uri)
        return computeEnvelope(decoded.samples, decoded.channels, decoded.sampleRate)
    }

    /** 解码桥需要 context;由持有方(VM)注入,避免本类持有 Activity */
    lateinit var contextProvider: () -> Context
}

/** 波形数据来源:文件(wav 直读)或 SAF/Media Uri(走 extractor) */
sealed interface AudioSource {
    data class WavFile(val file: File) : AudioSource
    data class MediaUri(val uri: Uri, val label: String = "") : AudioSource
}