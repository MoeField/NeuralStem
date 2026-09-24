package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 阶段 M4 / S3：验证 [Spleeter4Dsp] 的 **mask + 带扩展 + iSTFT** 管线。
 *
 * 核心判据与桌面一致：Spleeter 的 ratio mask 之和恒为 1 ⇒ **4 个 stem 之和 = 原信号**。
 * 这里用一个「4 个估计完全相同」的合成情形验证：此时 mask 各为 1/4，
 * 4 个 stem 相加应等于原信号（重建不变量）。
 *
 * 这正是桌面 Python 上得到的 −163.5 dB 那条判据的单元级复现。
 */
class Spleeter4DspTest {

    private val n = 40_000    // ~0.9s，够多个帧/多个 split

    private fun sineStereo(): Array<FloatArray> {
        val a = FloatArray(n) { i -> 0.5f * sin(2.0 * PI * 440.0 * i / 44_100.0).toFloat() }
        val b = FloatArray(n) { i ->
            0.4f * sin(2.0 * PI * 660.0 * i / 44_100.0).toFloat() +
                0.1f * sin(2.0 * PI * 220.0 * i / 44_100.0).toFloat()
        }
        return arrayOf(a, b)
    }

    /** 帧数/切分规则。 */
    @Test
    fun frame_and_split_counts() {
        // 与桌面参考实现一致：n = ceil((PAD + len) / HOP)
        assertEquals(Math.ceil((3072 + n).toDouble() / 1024).toInt(),
            Spleeter4Dsp.frameCount(n))
        val frames = Spleeter4Dsp.frameCount(n)
        assertEquals(Math.ceil(frames.toDouble() / 512).toInt(),
            Spleeter4Dsp.splitCount(frames))
    }

    /** 完整管线：4 个相同估计 → mask=1/4 → 4 stem 之和 = 原信号。 */
    @Test
    fun four_equal_estimates_reconstruct_mix() {
        val src = sineStereo()
        val frames = Spleeter4Dsp.frameCount(n)
        val splits = Spleeter4Dsp.splitCount(frames)
        val blockLen = splits * Spleeter4Dsp.SPLIT_FRAMES * Spleeter4Dsp.MODEL_BINS

        // 每个 stem 的估计都用「与原信号相同的 magnitude」→ mask 各为 1/4
        for (c in 0 until 2) {
            val (re, im) = Spleeter4Dsp.stft(src[c], frames)
            val mag = Spleeter4Dsp.magnitudeBlock(re, im, frames)
            assertEquals(blockLen, mag.size)

            val estimates = Array(4) { mag.copyOf() }         // 4 个相同估计
            val denom = Spleeter4Dsp.denominator(estimates)

            val recon = FloatArray(n)
            for (e in estimates) {
                val stem = Spleeter4Dsp.maskAndIstft(e, denom, re, im, frames, n)
                for (i in 0 until n) recon[i] += stem[i]
            }

            // 只比中心区域（避开 front-pad / 首尾帧边界效应）
            val lo = Spleeter4Dsp.N_FFT
            val hi = n - Spleeter4Dsp.N_FFT
            var num = 0.0
            var den = 0.0
            for (i in lo until hi) {
                val d = (recon[i] - src[c][i]).toDouble()
                num += d * d
                den += src[c][i].toDouble() * src[c][i]
            }
            val relErr = Math.sqrt(num / den)
            assertTrue("ch$c 重建相对误差过大: $relErr", relErr < 0.05)
        }
    }

    /** 随机信号也能重建（不依赖周期性）。 */
    @Test
    fun random_signal_reconstructs() {
        val rnd = Random(11)
        val src = Array(2) { FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.6f } }
        val frames = Spleeter4Dsp.frameCount(n)

        for (c in 0 until 2) {
            val (re, im) = Spleeter4Dsp.stft(src[c], frames)
            val mag = Spleeter4Dsp.magnitudeBlock(re, im, frames)
            val estimates = Array(4) { mag.copyOf() }
            val denom = Spleeter4Dsp.denominator(estimates)

            val recon = FloatArray(n)
            for (e in estimates) {
                val stem = Spleeter4Dsp.maskAndIstft(e, denom, re, im, frames, n)
                for (i in 0 until n) recon[i] += stem[i]
            }
            val lo = Spleeter4Dsp.N_FFT
            val hi = n - Spleeter4Dsp.N_FFT
            var num = 0.0
            var den = 0.0
            for (i in lo until hi) {
                val d = (recon[i] - src[c][i]).toDouble()
                num += d * d
                den += src[c][i].toDouble() * src[c][i]
            }
            val relErr = Math.sqrt(num / den)
            assertTrue("ch$c 随机信号重建误差过大: $relErr", relErr < 0.05)
        }
    }

    /** mask 分母：4 个相同估计（e=2）时，分母 = 4·e² + 1e-10 ≈ 16。 */
    @Test
    fun denominator_sums_all_stems() {
        val e = FloatArray(10) { 2f }
        val denom = Spleeter4Dsp.denominator(Array(4) { e.copyOf() })
        for (d in denom) {
            assertTrue(Math.abs(d - 16f) < 1e-3f)
        }
    }
}
