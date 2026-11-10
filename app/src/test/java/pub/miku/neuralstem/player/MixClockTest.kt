package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ★2026-11-10（B2）：多轨混音器位置插值单测。
 *
 * 关键契约：多轨退出/续播必须用 mixer 位置，不是静音门面的 1× 位置。
 */
class MixClockTest {

    @Test
    fun `playing at 1x advances by elapsed`() {
        assertEquals(
            3_000L,
            MixClock.positionMs(1_000L, 10_000L, 12_000L, 1.0f, true, 60_000L),
        )
    }

    @Test
    fun `playing at 1_4x advances media time faster`() {
        assertEquals(
            2_400L,
            MixClock.positionMs(1_000L, 10_000L, 11_000L, 1.4f, true, 60_000L),
        )
    }

    @Test
    fun `playing at 0_5x advances media time slower`() {
        assertEquals(
            1_500L,
            MixClock.positionMs(1_000L, 10_000L, 11_000L, 0.5f, true, 60_000L),
        )
    }

    @Test
    fun `paused does not advance`() {
        assertEquals(
            1_000L,
            MixClock.positionMs(1_000L, 10_000L, 20_000L, 1.4f, false, 60_000L),
        )
    }

    @Test
    fun `no sample yet returns base`() {
        assertEquals(
            1_000L,
            MixClock.positionMs(1_000L, 0L, 20_000L, 1.4f, true, 60_000L),
        )
    }

    @Test
    fun `clamps to duration`() {
        assertEquals(
            10_000L,
            MixClock.positionMs(9_900L, 10_000L, 20_000L, 1.4f, true, 10_000L),
        )
    }
}
