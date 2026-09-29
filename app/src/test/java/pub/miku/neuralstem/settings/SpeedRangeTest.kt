package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * ★R6：速度范围设置（单一项 X → 滑块区间 [1/X, X]）的纯函数验证。
 * 覆盖量化、夹紧、派生区间与往返一致性。
 */
class SpeedRangeTest {

    @Test
    fun `默认 X 等于 1点4 派生区间为 0点714 到 1点4`() {
        val x = SpeedRange.quantizeX(SpeedRange.X_DEFAULT)
        assertEquals(1.4f, x, 1e-6f)
        assertEquals(1f / 1.4f, SpeedRange.sliderMin(x), 1e-5f)
        assertEquals(1.4f, SpeedRange.sliderMax(x), 1e-6f)
    }

    @Test
    fun `量化到 0点1 网格`() {
        assertEquals(3.4f, SpeedRange.quantizeX(3.37f), 1e-5f)
        assertEquals(3.3f, SpeedRange.quantizeX(3.31f), 1e-5f)
        assertEquals(1.4f, SpeedRange.quantizeX(1.44f), 1e-5f)
    }

    @Test
    fun `超出范围被夹紧`() {
        assertEquals(4.0f, SpeedRange.quantizeX(9f), 1e-6f)
        // ★下限已从 1.0 提升到 1.2
        assertEquals(1.2f, SpeedRange.quantizeX(0.1f), 1e-6f)
        assertEquals(1.2f, SpeedRange.quantizeX(1.0f), 1e-6f)
        assertEquals(4.0f, SpeedRange.quantizeX(Float.MAX_VALUE), 1e-6f)
    }

    @Test
    fun `非有限值回退为夹紧边界`() {
        // quantizeToStep 对非有限值只夹紧（coerceIn 对 NaN 返回 NaN，属防守期行为）
        assertEquals(SpeedRange.X_MAX, SpeedRange.quantizeX(Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun `X 越大区间越宽 且下限等于上限倒数`() {
        for (x in listOf(1.2f, 1.4f, 2f, 3.2f, 4f)) {
            val q = SpeedRange.quantizeX(x)
            assertEquals(1f / q, SpeedRange.sliderMin(q), 1e-5f)
            assertEquals(q, SpeedRange.sliderMax(q), 1e-5f)
        }
        // 单调：X=4 的区间宽于 X=2
        val a = SpeedRange.sliderMax(4f) / SpeedRange.sliderMin(4f)
        val b = SpeedRange.sliderMax(2f) / SpeedRange.sliderMin(2f)
        assertTrue("X=4 的区间倍率应大于 X=2", a > b)
    }

    @Test
    fun `对数尺度下几何中点为 1点0 对任意 X 成立`() {
        // 播放滑块对数映射：中点值 = √(min·max) = √((1/X)·X) = 1.0
        for (x in listOf(1.2f, 1.4f, 2f, 3.2f, 4f)) {
            val lo = SpeedRange.sliderMin(x)
            val hi = SpeedRange.sliderMax(x)
            val logMid = sqrt((lo * hi).toDouble()).toFloat()
            assertEquals("X=$x 时对数中点应为 1.0", 1.0f, logMid, 1e-4f)
        }
    }

    @Test
    fun `通用量化在自定义网格上正确`() {
        assertEquals(4.0f, SpeedRange.quantizeToStep(3.9f, 1f, 4f, 0.5f), 1e-5f)
        assertEquals(18f, SpeedRange.quantizeToStep(17f, 9f, 36f, 3f), 1e-5f)
        // step<=0 → 只夹紧不量化
        assertEquals(3.7f, SpeedRange.quantizeToStep(3.7f, 1f, 4f, 0f), 1e-6f)
    }
}
