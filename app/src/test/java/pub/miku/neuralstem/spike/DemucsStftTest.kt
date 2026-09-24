package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 阶段 M4 / S2：验证 [DemucsStft] 的正/反变换自洽性。
 *
 * 这是 Demucs 路线 DSP 正确性的**单元级**证据：
 * 真实 STFT 已在真机上确认长度/排布正确（见 .memory.md §S2-f），
 * 这里再验证 **stft → istft 往返能重建原信号**（否则模型输出就接不上时域）。
 */
class DemucsStftTest {

    /** 帧数规则：center=True 时 n_frames = 1 + N/hop。 */
    @Test
    fun frameCount_matches_contract() {
        // 契约中的 343980 → 336 帧（桌面 Python 已验证）
        assertEquals(336, DemucsStft.frameCount(343_980))
        assertEquals(1 + 10_000 / DemucsStft.HOP, DemucsStft.frameCount(10_000))
    }

    /** 窗函数：周期 Hann，首元素应为 0。 */
    @Test
    fun window_is_periodic_hann() {
        val (len, first) = DemucsStft.windowCheck()
        assertEquals(DemucsStft.N_FFT, len)
        assertEquals(0f, first, 1e-6f)
    }

    /**
     * STFT → iSTFT 往返：应能高保真重建原信号。
     * 因为丢弃了 Nyquist bin（2048 而非 2049），允许少量误差，但应远小于信号本身。
     */
    @Test
    fun stft_istft_round_trip_reconstructs_signal() {
        val n = 20_000                       // 约 0.45s，够多个帧
        val rnd = Random(7)
        val src = Array(2) { FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.6f } }

        val frames = DemucsStft.frameCount(n)
        val packed = DemucsStft.stftPacked(src, n)
        assertEquals(4 * DemucsStft.BINS * frames, packed.size)

        val rec = DemucsStft.istftPacked(packed, frames, n)

        // 只比较中心区域（避开 center 补零边界效应）
        val lo = DemucsStft.N_FFT
        val hi = n - DemucsStft.N_FFT
        for (c in 0 until 2) {
            var num = 0.0
            var den = 0.0
            for (i in lo until hi) {
                val d = (rec[c][i] - src[c][i]).toDouble()
                num += d * d
                den += src[c][i].toDouble() * src[c][i]
            }
            val relErr = Math.sqrt(num / den)
            assertTrue("ch$c 往返相对误差过大: $relErr", relErr < 0.05)
        }
    }

    /** 纯正弦：重建后应与原信号高度一致（能量/相关性）。 */
    @Test
    fun stft_istft_round_trip_sine() {
        val n = 16_384
        val src = Array(2) { ch ->
            FloatArray(n) { i ->
                val f = if (ch == 0) 440.0 else 660.0
                0.5f * sin(2.0 * PI * f * i / 44_100.0).toFloat()
            }
        }
        val frames = DemucsStft.frameCount(n)
        val packed = DemucsStft.stftPacked(src, n)
        val rec = DemucsStft.istftPacked(packed, frames, n)

        fun rms(a: FloatArray, from: Int, to: Int): Double {
            var s = 0.0
            for (i in from until to) s += a[i].toDouble() * a[i]
            return Math.sqrt(s / (to - from))
        }
        val lo = DemucsStft.N_FFT
        val hi = n - DemucsStft.N_FFT
        for (c in 0 until 2) {
            val err = rms(rec[c], lo, hi) - rms(src[c], lo, hi)
            assertTrue("ch$c RMS 偏差过大: $err", Math.abs(err) < 0.05)
        }
    }

    /**
     * ★ 回归：在 Demucs 实际窗长（343980）上做往返。
     * 旧实现在大 nSamples 时把 OLA 缓冲截断到 nSamples，
     * 导致尾部 [PAD+nSamples 超出已写区] 读到未写入的 wsum → 尾部错误。
     */
    @Test
    fun round_trip_at_full_demucs_window() {
        val n = 343_980
        val rnd = Random(3)
        val src = Array(2) { FloatArray(n) { (rnd.nextFloat() - 0.5f) * 0.5f } }
        val frames = DemucsStft.frameCount(n)
        val packed = DemucsStft.stftPacked(src, n)
        val rec = DemucsStft.istftPacked(packed, frames, n)
        for (c in 0 until 2) {
            // 重点比**尾部**（旧 bug 处）：后半段相对误差应仍很小
            var num = 0.0; var den = 0.0
            for (i in n / 2 until n) {
                val d = (rec[c][i] - src[c][i]).toDouble()
                num += d * d; den += src[c][i].toDouble() * src[c][i]
            }
            val rel = Math.sqrt(num / den)
            assertTrue("ch$c 尾部往返误差过大: $rel", rel < 0.05)
        }
    }
}
