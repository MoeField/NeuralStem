package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★分离档位注册表（[TierRegistry]）单测。
 *
 * 冻结设计（2026-10-20 重构后）：
 * - **2 轨**：按实测速度簇动态 **4 档**（极快/临界/慢/很慢），候选由 RTF 自动归属；
 * - **4 轨**：固定 **2 档**（快速/质量，无均衡），**不支持禁用**；
 * - 实验模型（`ModelRegistry.EXPERIMENTAL`）**不进档位**。
 */
class TierRegistryTest {

    /** BF00A 实测值（见 doc/model_speed_tiers.md）。 */
    private val bf00aRtf = mapOf(
        "spleeter-2stems-fp16" to 8.562f,
        "uvr-Voc_FT" to 0.348f,
        "uvr-9482" to 1.033f,
        "uvr-KARA" to 1.040f,
        "uvr-Inst_HQ_3" to 0.349f,
        "uvr-Main" to 0.354f,
    )

    // ---------------- 2 轨：4 个速度簇 ----------------

    @Test
    fun `2 轨按 RTF 生成 4 个速度簇且顺序为快到慢`() {
        val tiers = TierRegistry.twoStemTiers(bf00aRtf)
        assertEquals(4, tiers.size)
        assertEquals(
            listOf(
                SpeedTier.ULTRA, SpeedTier.CRITICAL, SpeedTier.SLOW, SpeedTier.VERY_SLOW,
            ),
            tiers.map { it.speedTier },
        )
    }

    @Test
    fun `2 轨 Spleeter 归 ULTRA，9482 KARA 归 CRITICAL`() {
        val tiers = TierRegistry.twoStemTiers(bf00aRtf)
        val byTier = tiers.associateBy { it.speedTier }
        assertEquals(
            setOf("spleeter-2stems-fp16"),
            byTier[SpeedTier.ULTRA]!!.candidates.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("uvr-9482", "uvr-KARA"),
            byTier[SpeedTier.CRITICAL]!!.candidates.map { it.id }.toSet(),
        )
    }

    @Test
    fun `2 轨 SLOW 簇在只有这 6 个模型时为空`() {
        // 实测 6 个模型里没有落 0.4–0.9× 的 → SLOW 簇 candidates 应为空
        val slow = TierRegistry.twoStemTiers(bf00aRtf)
            .first { it.speedTier == SpeedTier.SLOW }
        assertTrue("SLOW 簇应为空（本数据集无 0.4–0.9× 模型）", slow.candidates.isEmpty())
        assertTrue("空簇 hasInstalled 应为 false", !slow.hasInstalled(setOf("spleeter-2stems-fp16")))
    }

    @Test
    fun `2 轨 VERY_SLOW 含三个 0-35 系模型`() {
        val verySlow = TierRegistry.twoStemTiers(bf00aRtf)
            .first { it.speedTier == SpeedTier.VERY_SLOW }
        assertEquals(
            setOf("uvr-Voc_FT", "uvr-Inst_HQ_3", "uvr-Main"),
            verySlow.candidates.map { it.id }.toSet(),
        )
    }

    @Test
    fun `未测模型归 VERY_SLOW`() {
        val tiers = TierRegistry.twoStemTiers(emptyMap())
        val verySlow = tiers.first { it.speedTier == SpeedTier.VERY_SLOW }
        // 全部 6 个模型都未测 → 全在 VERY_SLOW
        assertEquals(6, verySlow.candidates.size)
        assertTrue("其余簇应为空", tiers.filter { it.speedTier != SpeedTier.VERY_SLOW }.all {
            it.candidates.isEmpty()
        })
    }

    @Test
    fun `2 轨候选不包含实验模型`() {
        val allIds = TierRegistry.twoStemTiers(bf00aRtf).flatMap { it.candidates }.map { it.id }.toSet()
        val expIds = ModelRegistry.EXPERIMENTAL.map { it.id }.toSet()
        assertTrue("实验模型不应进 2 轨档位，重叠=${allIds intersect expIds}", (allIds intersect expIds).isEmpty())
    }

    @Test
    fun `2 轨候选不包含 4-stem 与 Demucs`() {
        val allIds = TierRegistry.twoStemTiers(bf00aRtf).flatMap { it.candidates }.map { it.id }.toSet()
        assertTrue(!allIds.contains("spleeter4"))
        assertTrue(!allIds.contains("demucs"))
    }

    // ---------------- 4 轨：保持 2 档 ----------------

    @Test
    fun `4 轨固定 2 档且无均衡`() {
        assertEquals(2, TierRegistry.FOUR_TIERS.size)
        assertEquals(
            listOf(Tier.FAST, Tier.QUALITY),
            TierRegistry.FOUR_TIERS.map { it.tier },
        )
        assertNull("4 轨不应有均衡", TierRegistry.find(StemCount.FOUR, Tier.BALANCE))
    }

    @Test
    fun `4 轨快速 = Spleeter4，质量 = Demucs`() {
        assertEquals(SepOption.Spleeter4, TierRegistry.FOUR_FAST.candidates.single())
        assertEquals(SepOption.Demucs, TierRegistry.FOUR_QUALITY.candidates.single())
    }

    @Test
    fun `4 轨每档 defaultId 都在候选内`() {
        for (c in TierRegistry.FOUR_TIERS) {
            assertTrue(
                "${c.stem}/${c.tier} defaultId=${c.defaultId} 不在候选内",
                c.candidates.any { it.id == c.defaultId },
            )
        }
    }
}
