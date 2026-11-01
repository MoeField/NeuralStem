package pub.miku.neuralstem.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ★多轨漂移校正的**判定逻辑**测试（2026-11-01）。
 *
 * ## 背景（真机实测）
 * 起播对齐后，播放中仍会间歇性错位。连续采样得到的轨间差形态：
 * `10ms → 1022ms → 18ms → 9ms → 1010ms`（非单调、无 seek 事件）。
 * 根因是 AudioTrack underrun —— 某实例一时拿不到数据，AudioTrack 停等，
 * 恢复后位置永久落后一截。4 个独立 ExoPlayer 各自渲染，低端机几乎必然遇到。
 *
 * ## 为何测这个而不是直接测 correctDriftOnce()
 * `correctDriftOnce()` 要操作真实 ExoPlayer（JVM 里跑不了）。所以这里测
 * “该不该校正”这部分纯判定，并与实现保持同步。
 *
 * ## 阈值取舍（写进测试以免以后被随手改小）
 * - 人耳对强相关信号（人声/伴奏）~50ms 就能听出错位
 * - 但 re-seek 本身有微小跳音，阈值太小会频繁跳（体验更差）
 * - 150ms 是折中
 */
class DriftThresholdTest {

    private val threshold = 150L

    /** 复刻 `correctDriftOnce()` 的判定。 */
    private fun needsCorrection(anchorPos: Long, trackPos: Long): Boolean =
        abs(anchorPos - trackPos) > threshold

    @Test
    fun `起播后的正常抖动不触发校正`() {
        // 实测起播对齐后轨间差 1–23ms，不该被拉
        assertFalse(needsCorrection(70_060, 70_061))
        assertFalse(needsCorrection(105_101, 105_105))
        assertFalse(needsCorrection(136_959, 136_936))
    }

    @Test
    fun `实测到的 1010ms underrun 必须触发校正`() {
        // 真实采样：vocals=40102 drums=39092 → 差 1010ms
        assertTrue(needsCorrection(40_102, 39_092))
        assertTrue(needsCorrection(84_967, 83_946))
    }

    @Test
    fun `刚好在阈值上不触发以避免频繁 seek`() {
        assertFalse(needsCorrection(10_000, 10_000 - threshold))
        assertTrue(needsCorrection(10_000, 10_000 - threshold - 1))
    }

    @Test
    fun `超前也会被纠正`() {
        // 若跟随轨跑到主轨前面（例如主轨 underrun），同样要拉回
        assertTrue(needsCorrection(10_000, 12_000))
    }

    @Test
    fun `阈值必须大于人耳可听阈`() {
        assertTrue("阈值应 > 50ms（人耳可听），实际 $threshold", threshold > 50)
    }

    @Test
    fun `阈值不能大到放任明显错位`() {
        assertTrue("阈值应 < 500ms，实际 $threshold", threshold < 500)
    }
}
