package pub.miku.neuralstem.spike

import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * ★按**绝对输出帧**重采样的 FrameSource（2026-11-09，修高采样率 OOM）。
 *
 * ## 为什么需要
 * `SeparationInput` 旧实现把整曲按**源采样率**解码成一个 `FloatArray`：
 * 192kHz / 148s / 立体声 ≈ **227MB 一次分配** → 真机 `OutOfMemoryError`。
 * 现在压缩源先**流式解码到临时 WAV**（内存 O(chunk)），再用本类按 44.1k 读。
 *
 * ## 语义
 * - 输出永远 2 声道、[sampleRate]（44.1k）；mono 复制、>2 取前两声道。
 * - `readFrames(startFrame, frames)` 是**无状态**的：每个输出帧 f 的源位置
 *   `f * srcRate / dstRate` 直接计算，线性插值 —— 因此随机读（seek/补前段）
 *   与顺序读结果完全一致，不需要维护重采样相位。
 * - 每次只读回本批输出需要的源帧（默认 4096 输出帧一批），内存与请求量线性。
 *
 * ## 临时文件
 * [tempFile] 非 null 时在 [close] 删除（压缩源的临时 WAV）。
 */
class ResamplingFrameSource(
    private val inner: FrameSource,
    private val srcRate: Int,
    override val sampleRate: Int,
    private val tempFile: File? = null,
) : FrameSource {

    override val channels: Int = 2

    override val totalFrames: Int =
        (inner.totalFrames.toLong() * sampleRate / srcRate).toInt()

    /** 每个输出帧对应的源帧步长。 */
    private val step = srcRate.toDouble() / sampleRate

    override fun readFrames(startFrame: Int, frames: Int): FloatArray {
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(frames * channels)
        // 超出曲尾的部分保持 0（与 MemoryFrameSource/WavFrameSource 的截断语义一致）
        val valid = minOf(frames, (totalFrames - startFrame).coerceAtLeast(0))
        if (valid <= 0) return out
        var done = 0
        while (done < valid) {
            val n = minOf(OUT_BATCH_FRAMES, valid - done)
            resampleBatch(startFrame + done, n, out, done)
            done += n
        }
        return out
    }

    private fun resampleBatch(firstOut: Int, n: Int, out: FloatArray, outFrameOffset: Int) {
        val srcStart = floor(firstOut * step).toInt()
        val srcEnd = ceil((firstOut + n - 1) * step).toInt() + 2
        val src = inner.readFrames(srcStart, (srcEnd - srcStart).coerceAtLeast(1))
        val innerCh = inner.channels
        val srcFrames = src.size / innerCh
        if (srcFrames <= 0) return // EOF → 保持静音

        for (i in 0 until n) {
            val pos = (firstOut + i) * step - srcStart
            var i0 = pos.toInt()
            if (i0 < 0) i0 = 0
            if (i0 > srcFrames - 1) i0 = srcFrames - 1
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val frac = (pos - i0).toFloat().coerceIn(0f, 1f)
            val o = (outFrameOffset + i) * 2
            if (innerCh == 1) {
                val v = lerp(src[i0], src[i1], frac)
                out[o] = v
                out[o + 1] = v
            } else {
                val b0 = i0 * innerCh
                val b1 = i1 * innerCh
                out[o] = lerp(src[b0], src[b1], frac)
                out[o + 1] = lerp(src[b0 + 1], src[b1 + 1], frac)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    override fun close() {
        runCatching { inner.close() }
        tempFile?.let { runCatching { it.delete() } }
    }

    private companion object {
        /** 每批输出帧数：4k 输出 ≈ 18k 源帧 @192k，内存 ~144KB。 */
        const val OUT_BATCH_FRAMES = 4096
    }
}

/**
 * 包一层「关闭时删除临时文件」的 FrameSource（压缩源解码到临时 44.1k WAV 时用）。
 */
class TempFileFrameSource(
    private val inner: FrameSource,
    private val temp: File,
) : FrameSource {
    override val channels: Int get() = inner.channels
    override val sampleRate: Int get() = inner.sampleRate
    override val totalFrames: Int get() = inner.totalFrames
    override fun readFrames(startFrame: Int, frames: Int): FloatArray =
        inner.readFrames(startFrame, frames)

    override fun close() {
        runCatching { inner.close() }
        runCatching { temp.delete() }
    }
}
