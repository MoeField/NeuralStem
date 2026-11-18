package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.2 回归：末块 flush 可能大于 step，调用方必须按 W（=WAVE+step）准备缓冲。
 *
 * 触发区间：total > step && total <= WAVE（44.1kHz 约 5.85–7.8s）。
 */
class DemucsFlushBoundsTest {

    private val step = DemucsSeparator.WAVE - DemucsSeparator.DEFAULT_OVERLAP
    private val window = DemucsSeparator.WAVE + step

    @Test
    fun `七秒输入的末块 flush 大于 step 但不超过窗口`() {
        val total = 7 * 44_100 // 308_700 frames, 7s
        val flush = DemucsSeparator.flushFramesFor(
            total = total,
            base = 0,
            isLast = true,
            step = step,
            window = window,
        )
        assertTrue("flush=$flush 应大于 step=$step", flush > step)
        assertEquals(total, flush)
        assertTrue("flush=$flush 必须 <= window=$window", flush <= window)
    }

    @Test
    fun `普通非末块 flush 恒为 step`() {
        val flush = DemucsSeparator.flushFramesFor(
            total = 10_000_000,
            base = 123_456,
            isLast = false,
            step = step,
            window = window,
        )
        assertEquals(step, flush)
    }
}
