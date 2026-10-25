package pub.miku.neuralstem.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ThermalProbe.Snapshot` 判据的 JVM 单测。
 *
 * ## 为何要写这些测试
 * 这个判据**已经错过一次**（2026-10-25 OPPO 实测）：
 * 初版用「所有核心频率比的最小值 < 80%」→ **空闲时小核本来就在 25%，几乎每次都误报**。
 * 当时看到的日志：
 * ```
 * thermal=NONE freq=25% ⚠️SUSPECT      ← 误报
 * 实际 cpu0-7 = 32%~47%（空闲，没负载）
 * ```
 * ⇒ 把判据钉死成测试，避免下次再“凭感觉”改回来。
 *
 * ## 三种必须区分的形态
 * | 形态 | 大核 | 小核 | 应判为 |
 * |---|---|---|---|
 * | 空闲 | 32% | 25% | **不可疑**（数据无效但不是“被污染”）|
 * | 正常负载 | 95% | 40% | **不可疑**（小核低是常态）|
 * | 热降频 | 50% | 25% | **可疑**（大核也降了）|
 */
class ThermalProbeTest {

    /** 快捷构造：`thermal` 热状态、`big` 大核频率比、`small` 小核频率比。 */
    private fun snap(thermal: Int, big: Float?, small: Float? = 0.25f) = ThermalProbe.Snapshot(
        thermalStatus = thermal,
        maxTempC = 45f,
        freqRatio = big,
        allRatios = listOfNotNull(small, big).sorted(),
    )

    // ---------------- 空闲 ----------------

    @Test
    fun `空闲时（大核 32%）不算降频`() {
        val s = snap(thermal = 0, big = 0.32f)
        assertTrue("大核 32% 应判为空闲", s.idle)
        assertFalse("空闲不是降频污染", s.suspect)
    }

    @Test
    fun `空闲时即使热状态 LIGHT 也不算可疑`() {
        val s = snap(thermal = 1, big = 0.30f)
        assertFalse(s.suspect)
    }

    // ---------------- 正常负载 ----------------

    @Test
    fun `正常负载：大核跑满、小核 25% 不算降频`() {
        val s = snap(thermal = 0, big = 0.95f, small = 0.25f)
        assertFalse("小核低是常态，不能算降频", s.idle)
        assertFalse(s.suspect)
    }

    @Test
    fun `正常负载 + 热状态 LIGHT 仍不算可疑`() {
        val s = snap(thermal = 1, big = 0.90f)
        assertFalse(s.suspect)
    }

    // ---------------- 真降频 ----------------

    @Test
    fun `大核降到 50% 且不在空闲`() {
        val s = snap(thermal = 0, big = 0.50f)
        assertFalse(s.idle)
        assertTrue("大核 < 80% 且有负载 → 真降频", s.suspect)
    }

    @Test
    fun `热状态不参与判定（BF00A 热状态粘滞）`() {
        // BF00A 冷却 7 分钟后：CPU 已 37°C、大核能顶满，但 Thermal Status 仍报 2。
        // 若热状态参与判定 → 会误报全部数据不可信。
        val s = snap(thermal = 2, big = 1.0f)
        assertFalse("大核能顶满就算可信，不看热状态", s.suspect)
    }

    @Test
    fun `热状态 MODERATE 且大核被压 → 可疑（靠频率判出）`() {
        val s = snap(thermal = 2, big = 0.55f)
        assertTrue(s.suspect)
    }

    @Test
    fun `温度高了但频率正常 → 不判可疑（应用读不到温度，不作为依据）`() {
        val s = ThermalProbe.Snapshot(
            thermalStatus = 0,
            maxTempC = 60f,
            freqRatio = 0.95f,
            allRatios = listOf(0.4f, 0.95f),
        )
        assertFalse("频率正常就不判可疑", s.suspect)
    }

    // ---------------- 边界 ----------------

    @Test
    fun `频率比恰好 80% 不算可疑（阈值是小于）`() {
        val s = snap(thermal = 0, big = 0.80f)
        assertFalse(s.suspect)
    }

    @Test
    fun `频率比恰好 40% 不算空闲（阈值是小于）`() {
        val s = snap(thermal = 0, big = 0.40f)
        assertFalse("40% 不是空闲（边界在下侧）", s.idle)
    }

    @Test
    fun `读不到频率（null）时不判可疑`() {
        val s = ThermalProbe.Snapshot(thermalStatus = 0, maxTempC = null, freqRatio = null)
        assertFalse("无数据不能当成降频", s.suspect)
        assertFalse(s.idle)
    }

    @Test
    fun `读不到热状态（-1）且频率正常 → 不可疑`() {
        val s = snap(thermal = -1, big = 0.95f)
        assertFalse(s.suspect)
    }

    // ---------------- 展示 ----------------

    @Test
    fun `describe 在可疑时带标记`() {
        val s = snap(thermal = 2, big = 0.5f)
        val text = s.describe()
        assertTrue("应含 SUSPECT 标记", text.contains("SUSPECT"))
        assertTrue("应含热状态名（仅展示）", text.contains("MODERATE"))
    }

    @Test
    fun `describe 在空闲时标注 idle`() {
        assertTrue(snap(thermal = 0, big = 0.30f).describe().contains("idle"))
    }

    @Test
    fun `thermalName 映射正确`() {
        assertTrue(ThermalProbe.thermalName(0) == "NONE")
        assertTrue(ThermalProbe.thermalName(2) == "MODERATE")
        assertTrue(ThermalProbe.thermalName(6) == "SHUTDOWN")
        assertTrue(ThermalProbe.thermalName(99).startsWith("UNKNOWN"))
    }
}
