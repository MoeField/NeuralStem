package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★分离档位注册表（[TierRegistry]）单测。
 *
 * 冻结设计（用户定稿 2026-10-05）：
 * - 2 轨：3 档（快速 / 均衡 / 质量）
 * - 4 轨：2 档（快速 / 质量，**无均衡**）
 * - 档位归属按实测 RTF 分组（组②=均衡 9482/KARA；组③=质量 Voc_FT/Inst_HQ_3/Main）。
 */
class TierRegistryTest {

    @Test
    fun `2 轨恰有 3 档，4 轨恰有 2 档（4 轨无均衡）`() {
        val two = TierRegistry.forStem(StemCount.TWO)
        val four = TierRegistry.forStem(StemCount.FOUR)
        assertEquals(listOf(Tier.FAST, Tier.BALANCE, Tier.QUALITY), two.map { it.tier })
        assertEquals(listOf(Tier.FAST, Tier.QUALITY), four.map { it.tier })
        assertNull("4 轨不应有均衡", TierRegistry.find(StemCount.FOUR, Tier.BALANCE))
    }

    @Test
    fun `2 轨快速 = Spleeter 2-stem 唯一候选`() {
        val c = TierRegistry.TWO_FAST
        assertEquals(1, c.candidates.size)
        assertEquals(ModelRegistry.SPLEETER.id, c.candidates.single().id)
    }

    @Test
    fun `2 轨均衡 = 9482 与 KARA（组②）`() {
        val ids = TierRegistry.TWO_BALANCE.candidates.map { it.id }.toSet()
        assertEquals(setOf("uvr-9482", "uvr-KARA"), ids)
    }

    @Test
    fun `2 轨质量 = Voc_FT, Inst_HQ_3, Main（组③）`() {
        val ids = TierRegistry.TWO_QUALITY.candidates.map { it.id }.toSet()
        assertEquals(setOf("uvr-Voc_FT", "uvr-Inst_HQ_3", "uvr-Main"), ids)
    }

    @Test
    fun `4 轨快速 = Spleeter4，质量 = Demucs`() {
        assertEquals(SepOption.Spleeter4, TierRegistry.FOUR_FAST.candidates.single())
        assertEquals(SepOption.Demucs, TierRegistry.FOUR_QUALITY.candidates.single())
    }

    @Test
    fun `每档 defaultId 都在候选内，default() 可解析`() {
        for (c in TierRegistry.ALL) {
            assertTrue(
                "${c.stem}/${c.tier} defaultId=${c.defaultId} 不在候选内",
                c.candidates.any { it.id == c.defaultId },
            )
            assertNotNull(c.default())
        }
    }

    @Test
    fun `全部档位 = 5 个（2轨3 + 4轨2）`() {
        assertEquals(5, TierRegistry.ALL.size)
    }
}
