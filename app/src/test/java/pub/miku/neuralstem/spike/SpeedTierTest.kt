package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★[SpeedTier] 单测。
 *
 * 重点：
 * 1. **边界**必须精确（0.4 / 0.9 / 2.0 的归属），否则换设备后档位会错位；
 * 2. **用真实 benchmark 数据（BF00A 实测 19 模型）校验归属**，保证分档与实测表一致。
 */
class SpeedTierTest {

    @Test
    fun `边界值归属精确`() {
        // 下界含、上界不含
        assertEquals(SpeedTier.ULTRA, SpeedTier.of(2.0f))
        assertEquals(SpeedTier.ULTRA, SpeedTier.of(8.56f))
        assertEquals(SpeedTier.ULTRA, SpeedTier.of(100f))

        assertEquals(SpeedTier.CRITICAL, SpeedTier.of(1.999f))
        assertEquals(SpeedTier.CRITICAL, SpeedTier.of(0.9f))
        assertEquals(SpeedTier.CRITICAL, SpeedTier.of(1.03f))

        assertEquals(SpeedTier.SLOW, SpeedTier.of(0.899f))
        assertEquals(SpeedTier.SLOW, SpeedTier.of(0.4f))
        assertEquals(SpeedTier.SLOW, SpeedTier.of(0.61f))

        assertEquals(SpeedTier.VERY_SLOW, SpeedTier.of(0.399f))
        assertEquals(SpeedTier.VERY_SLOW, SpeedTier.of(0.34f))
        assertEquals(SpeedTier.VERY_SLOW, SpeedTier.of(0f))
    }

    @Test
    fun `未测模型归 VERY_SLOW`() {
        assertEquals(SpeedTier.VERY_SLOW, SpeedTier.of(null))
    }

    /** 用 BF00A 实测值验证：每个模型的归属应与 doc/model_speed_tiers.md 一致。 */
    @Test
    fun `真实 benchmark 数据归属与实测表一致`() {
        val measured = mapOf(
            "spleeter-2stems-fp16" to 8.562f,
            "uvr-Voc_FT" to 0.348f,
            "uvr-9482" to 1.033f,
            "uvr-KARA" to 1.040f,
            "uvr-Inst_HQ_3" to 0.349f,
            "uvr-Main" to 0.354f,
            "uvr-Inst_1" to 0.338f,
            "uvr-Inst_2" to 0.338f,
            "uvr-Inst_3" to 0.337f,
            "uvr-Inst_HQ_1" to 0.346f,
            "uvr-Inst_HQ_2" to 0.346f,
            "uvr-Inst_HQ_4" to 0.42f,
            "uvr-Inst_HQ_5" to 0.41f,
            "uvr-Inst_Main" to 0.52f,
            "uvr-Crowd_HQ_1" to 0.41f,
            "uvr-9703" to 1.01f,
            "uvr-9682" to 1.00f,
            "uvr-9662" to 1.00f,
            "uvr-KARA_2" to 0.53f,
        )
        val expected = mapOf(
            "spleeter-2stems-fp16" to SpeedTier.ULTRA,
            "uvr-9482" to SpeedTier.CRITICAL,
            "uvr-KARA" to SpeedTier.CRITICAL,
            "uvr-9703" to SpeedTier.CRITICAL,
            "uvr-9682" to SpeedTier.CRITICAL,
            "uvr-9662" to SpeedTier.CRITICAL,
            "uvr-KARA_2" to SpeedTier.SLOW,
            "uvr-Inst_Main" to SpeedTier.SLOW,
            "uvr-Inst_HQ_4" to SpeedTier.SLOW,
            "uvr-Inst_HQ_5" to SpeedTier.SLOW,
            "uvr-Crowd_HQ_1" to SpeedTier.SLOW,
            "uvr-Voc_FT" to SpeedTier.VERY_SLOW,
            "uvr-Inst_HQ_3" to SpeedTier.VERY_SLOW,
            "uvr-Main" to SpeedTier.VERY_SLOW,
            "uvr-Inst_1" to SpeedTier.VERY_SLOW,
            "uvr-Inst_2" to SpeedTier.VERY_SLOW,
            "uvr-Inst_3" to SpeedTier.VERY_SLOW,
            "uvr-Inst_HQ_1" to SpeedTier.VERY_SLOW,
            "uvr-Inst_HQ_2" to SpeedTier.VERY_SLOW,
        )
        for ((id, rtf) in measured) {
            assertEquals("模型 $id (rtf=$rtf) 的归属不符", expected[id], SpeedTier.of(rtf))
        }
    }

    @Test
    fun `四个簇都被覆盖且顺序为快到慢`() {
        assertEquals(
            listOf(
                SpeedTier.ULTRA,
                SpeedTier.CRITICAL,
                SpeedTier.SLOW,
                SpeedTier.VERY_SLOW,
            ),
            SpeedTier.ordered,
        )
        // 区间单调递减（下界严格递减）
        for (i in 0 until SpeedTier.ordered.size - 1) {
            val a = SpeedTier.ordered[i]
            val b = SpeedTier.ordered[i + 1]
            assertTrue(
                "${a.name}.minRtf(${a.minRtf}) 应大于 ${b.name}.minRtf(${b.minRtf})",
                a.minRtf > b.minRtf,
            )
        }
    }
}
