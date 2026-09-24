package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 变速变调的纯函数验证。
 * PlayerViewModel 依赖 Android 框架类,VM 层行为在真机/自动化里验收;
 * 这里覆盖数学换算与边界。
 */
class SpeedPitchTest {

    @Test
    fun `半音到倍率 0半音等于1倍`() {
        assertEquals(1f, PlayerViewModel.semitonesToRate(0f), 1e-6f)
    }

    @Test
    fun `半音到倍率 正12半音等于2倍 八度关系`() {
        assertEquals(2f, PlayerViewModel.semitonesToRate(12f), 1e-4f)
    }

    @Test
    fun `半音到倍率 负12半音等于0点5倍`() {
        assertEquals(0.5f, PlayerViewModel.semitonesToRate(-12f), 1e-4f)
    }

    @Test
    fun `半音到倍率 7半音是纯五度 约1点498`() {
        val rate = PlayerViewModel.semitonesToRate(7f)
        assertTrue("7 半音倍率应 ≈1.4983,实际 $rate", rate in 1.497f..1.499f)
    }

    @Test
    fun `倍率与半音往返一致`() {
        // 给定倍率,反推半音再正推,应回到原倍率(±误差)
        for (semi in listOf(-12f, -7f, -3f, 0f, 2f, 7f, 12f)) {
            val rate = PlayerViewModel.semitonesToRate(semi)
            // rate = 2^(semi/12) => semi = 12*log2(rate)
            val back = 12 * (Math.log(rate.toDouble()) / Math.log(2.0)).toFloat()
            assertEquals(semi, back, 1e-3f)
        }
    }

    @Test
    fun `速度边界值定义正确`() {
        assertEquals(0.5f, PlayerViewModel.MIN_SPEED)
        assertEquals(2.0f, PlayerViewModel.MAX_SPEED)
        assertEquals(-12f, PlayerViewModel.MIN_PITCH_SEMITONES)
        assertEquals(12f, PlayerViewModel.MAX_PITCH_SEMITONES)
    }
}