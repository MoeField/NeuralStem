package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import pub.miku.neuralstem.spike.SpeedTier
import pub.miku.neuralstem.spike.TierRegistry

/**
 * ★4 轨档位禁用（2026-10-20）—— 纯逻辑断言。
 *
 * ## 为何不直接测 [SettingsRepository]
 * 它依赖 `SharedPreferences`，而本模块 JVM 单测只开了 `isReturnDefaultValues`
 * （没引 Robolectric）→ 构造即抛 "not mocked"。所以这里测**可推导的契约**：
 * 4 轨两档的身份、与 2 轨 key 不冲突、过滤后的可见集合符合预期。
 * 真实的读写往返在真机验收里核对（`settings.xml` + logcat `fourTierEnabled`）。
 */
class FourTierEnabledTest {

    @Test
    fun `4-tracks has exactly two tiers`() {
        assertEquals(2, TierRegistry.FOUR_TIERS.size)
        assertEquals(
            listOf("FAST", "QUALITY"),
            TierRegistry.FOUR_TIERS.map { it.tier.name },
        )
    }

    /**
     * ★核心：4 轨用 [pub.miku.neuralstem.spike.Tier]，2 轨用 [SpeedTier]。
     *
     * 两套枚举的 name 集合**不相交**，所以 `fourTierEnabled_<Tier>` 与
     * `twoTierEnabled_<SpeedTier>` 两个 prefs 前缀**不可能撞**。
     * 若将来有人把 4 轨改成 SpeedTier，本测试会失败并阻止这种静默混用。
     */
    @Test
    fun `four-tier enum names never collide with speed-tier names`() {
        val fourNames = TierRegistry.FOUR_TIERS.map { it.tier.name }.toSet()
        val twoNames = SpeedTier.ordered.map { it.name }.toSet()
        assertEquals(
            "4 轨与 2 轨的枚举名不应相交，实际交集=${fourNames intersect twoNames}",
            emptySet<String>(),
            fourNames intersect twoNames,
        )
        assertNotEquals("两套枚举不应是同一个类型", fourNames, twoNames)
    }

    /** 关掉一档 → 可见集合少一个；关两档 → 空（分离页据此显示灰字提示）。 */
    @Test
    fun `filtering by enabled tier yields expected visible sets`() {
        fun visible(enabled: Set<String>) =
            TierRegistry.FOUR_TIERS.filter { it.tier.name in enabled }

        assertEquals("全开 → 2 个按钮", 2, visible(setOf("FAST", "QUALITY")).size)
        assertEquals(
            "只开 QUALITY → 仅质量",
            listOf("QUALITY"),
            visible(setOf("QUALITY")).map { it.tier.name },
        )
        assertEquals(
            "只开 FAST → 仅快速",
            listOf("FAST"),
            visible(setOf("FAST")).map { it.tier.name },
        )
        assertEquals("全关 → 空（触发灰字提示）", 0, visible(emptySet()).size)
    }

    /** 每档只有 1 个候选（所以 4 轨没有“簇内选模型”UI，只有开关）。 */
    @Test
    fun `each four-tier has a single candidate`() {
        for (tc in TierRegistry.FOUR_TIERS) {
            assertEquals("${tc.tier} 应只有 1 个候选", 1, tc.candidates.size)
            assertEquals("默认候选应与唯一候选一致", tc.candidates.first().id, tc.defaultId)
        }
    }
}
