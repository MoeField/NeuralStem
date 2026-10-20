package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★成本门控（2026-10-20）：何时该自动补前段，何时该交给用户决定。
 *
 * ## 要防的场景
 * 用户选了一小时的演唱会，从 55 分开始分离：
 * - 正向只需 5 分钟（能听）
 * - 回头补前段要 55 分钟（按 1× RTF）
 * - 但他很可能听完就切歌 → 白算 55 分钟 CPU/电
 *
 * ⇒ 预估耗时超过阈值就**不自动补**，改为在分离页显示「前 55 分钟未分离 · 补齐前段」。
 *
 * ## 为何单测这条逻辑而不是真机跑
 * 触发门控需要“缺口 > 5 分钟”的长音频；本项目测试素材只有 2:30 的歌。
 * 临时改阈值能强制触发，但那是在测“改过的代码”。判定公式是纯函数，单测更可靠。
 */
class BackfillCostGateTest {

    /** 与 SeparationRepository.AUTO_BACKFILL_LIMIT_SEC 保持一致。 */
    private val limitSec = 300.0

    /** 复刻生产代码的判定：`缺口时长 / RTF`。 */
    private fun estimatedBackfillSec(gapFrames: Int, sampleRate: Int, rtf: Float): Double {
        val gapSeconds = gapFrames.toDouble() / sampleRate
        return if (rtf > 0.01f) gapSeconds / rtf else gapSeconds
    }

    private fun tooExpensive(gapFrames: Int, rtf: Float, sr: Int = 44_100): Boolean =
        estimatedBackfillSec(gapFrames, sr, rtf) > limitSec

    @Test
    fun `normal song from the middle still auto-backfills`() {
        // 4 分钟歌从 2 分处开始 → 缺口 2 分钟；RTF 8.5（Spleeter）→ 约 14 秒
        val gap = 120 * 44_100
        val est = estimatedBackfillSec(gap, 44_100, 8.5f)
        assertEquals(14.1, est, 0.5)
        assertFalse("普通歌曲应自动补齐", tooExpensive(gap, 8.5f))
    }

    @Test
    fun `one hour concert from minute 55 is gated`() {
        // 60 分钟，从 55 分开始 → 缺口 55 分钟；RTF 8.5 → 约 388 秒 > 300
        val gap = 55 * 60 * 44_100
        val est = estimatedBackfillSec(gap, 44_100, 8.5f)
        assertTrue("预估 $est 秒应超过阈值", est > limitSec)
        assertTrue("一小时演唱会的 55 分钟缺口应被门控", tooExpensive(gap, 8.5f))
    }

    @Test
    fun `slow model gates much earlier`() {
        // 同样的 55 分钟缺口，但用 0.34× 的 UVR → 约 9706 秒
        val gap = 55 * 60 * 44_100
        val fast = estimatedBackfillSec(gap, 44_100, 8.5f)
        val slow = estimatedBackfillSec(gap, 44_100, 0.34f)
        assertTrue("慢模型预估应远大于快模型", slow > fast * 10)
        assertTrue(tooExpensive(gap, 0.34f))
    }

    @Test
    fun `boundary near the limit`() {
        // RTF 8.5，缺口 = 300 * 8.5 = 2550 秒 ≈ 42.5 分钟
        val justUnder = (42 * 60 * 44_100)
        val justOver = (43 * 60 * 44_100)
        assertFalse("42 分钟缺口（约 296 秒）应自动补齐", tooExpensive(justUnder, 8.5f))
        assertTrue("43 分钟缺口（约 304 秒）应被门控", tooExpensive(justOver, 8.5f))
    }

    @Test
    fun `no gap means no backfill at all`() {
        // 从 0 开始分离 → 缺口为 0 → 阶段 2 根本不会跑
        assertEquals(0.0, estimatedBackfillSec(0, 44_100, 8.5f), 0.001)
        assertFalse(tooExpensive(0, 8.5f))
    }

    @Test
    fun `rtf of zero falls back to gap duration`() {
        // 未测速的模型 rtf=0 → 保守按 1× 估（不会因为除以 0 得到无穷大而误判）
        val gap = 120 * 44_100
        val est = estimatedBackfillSec(gap, 44_100, 0f)
        assertEquals("rtf=0 时应退化为缺口秒数", 120.0, est, 0.5)
        assertFalse("2 分钟缺口仍应自动补齐", tooExpensive(gap, 0f))
    }
}
