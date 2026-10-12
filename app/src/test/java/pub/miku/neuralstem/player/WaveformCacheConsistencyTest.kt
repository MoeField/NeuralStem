package pub.miku.neuralstem.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★⑭c：`WaveformCache.isSelfConsistent` 纯逻辑单测。
 *
 * 规则：点数应 ≈ totalFrames × 50 / sampleRate（±1.5）。用于拒绝“半截”波形写缓存。
 */
class WaveformCacheConsistencyTest {

    private fun wf(points: Int, frames: Long, sr: Int) =
        WaveformData(FloatArray(points), FloatArray(points), sr, frames)

    @Test
    fun `完整包络（5 分钟 @44k）自洽`() {
        // 314s 音频 → 约 15700 点
        val frames = 314L * 44100
        val pts = (frames * 50 / 44100).toInt()
        assertTrue(WaveformCache.isSelfConsistent(wf(pts, frames, 44100)))
    }

    @Test
    fun `半截包络（3s 数据声称为 314s）不自洽`() {
        // 声称为 314s，但只给了 150 点（实际 3s）→ 不自洽
        assertFalse(WaveformCache.isSelfConsistent(wf(points = 150, frames = 314L * 44100, sr = 44100)))
    }

    @Test
    fun `真实 3s 音频 + 150 点自洽`() {
        val frames = 3L * 44100
        assertTrue(WaveformCache.isSelfConsistent(wf(150, frames, 44100)))
    }

    @Test
    fun `非法参数（零或负）不自洽`() {
        assertFalse(WaveformCache.isSelfConsistent(wf(0, 1000, 44100)))
        assertFalse(WaveformCache.isSelfConsistent(wf(100, 0, 44100)))
        assertFalse(WaveformCache.isSelfConsistent(wf(100, 1000, 0)))
    }
}
