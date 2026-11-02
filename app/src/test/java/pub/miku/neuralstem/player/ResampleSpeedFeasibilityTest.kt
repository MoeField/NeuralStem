package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * ★多轨变速变调 —— **方案 3（输入端变步长重采样）可行性验证**。
 *
 * ## 方案描述
 * 不改输出端，而是让每条 stem 的读取"步长"随速度变化：
 * `framePos += speed` 而不是 `+= 1`。这样输出帧率不变，但吃的输入更多/更少。
 *
 * ## 本测试要揭穿的真相
 * 这个方案**实现最简单**（改一行 `framePos` 推进逻辑），但它只实现"变速"，
 * **不实现"变调"** —— 因为变步长采样同时改变**音高**（这就是"转速变快"的物理本质，
 * 如同黑胶唱片 45 转 vs 33 转）。
 *
 * 用户要的是**变速变调独立**（F2.1/F2.2：速度与音高独立可调）。
 * 如果只做变步长，用户拖动"速度"滑块时音调会跟着变 —— 这正是用户抱怨的"体验降级"。
 *
 * 本测试用**主频检测**量化证明：变步长会改变输出信号频率。
 */
class ResampleSpeedFeasibilityTest {

    private val sampleRate = 44100

    /** 生成单声道正弦波。 */
    private fun tone(frames: Int, hz: Double): FloatArray =
        FloatArray(frames) { i -> sin(2.0 * PI * hz * i / sampleRate).toFloat() }

    /**
     * 变步长读取（模拟 scheme 3）：从 [src] 按 [speed] 步长取值。
     * speed>1 = 更快 = 音调更高（这是重采样的物理必然）。
     */
    private fun resampleBySpeed(src: FloatArray, speed: Float): FloatArray {
        val outFrames = (src.size / speed).toInt()
        return FloatArray(outFrames) { i ->
            val pos = i * speed
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            // 线性插值
            src[i0] * (1 - frac) + src[i1] * frac
        }
    }

    /**
     * 用**过零率**估算基频（对纯正弦足够准，且不依赖 FFT）。
     */
    private fun estimateHz(buf: FloatArray): Double {
        var crossings = 0
        for (i in 1 until buf.size) {
            if (buf[i - 1] <= 0f && buf[i] > 0f) crossings++
        }
        val seconds = buf.size.toDouble() / sampleRate
        return crossings / seconds
    }

    @Test
    fun `1 变步长重采样能实现变速 帧数按比例变化`() {
        val src = tone(sampleRate, 440.0)     // 1 秒
        val fast = resampleBySpeed(src, 2f)
        val slow = resampleBySpeed(src, 0.5f)

        assertTrue("2x 应约半长，实际 ${fast.size}", abs(fast.size - sampleRate / 2) < 100)
        assertTrue("0.5x 应约两倍长，实际 ${slow.size}", abs(slow.size - sampleRate * 2) < 100)
    }

    @Test
    fun `2 致命缺陷 变步长同时改变音高 440Hz 变成 880Hz`() {
        val src = tone(sampleRate, 440.0)
        val hz0 = estimateHz(src)
        val hz2x = estimateHz(resampleBySpeed(src, 2f))

        // 原信号 ~440Hz
        assertTrue("原信号应约 440Hz，实际 $hz0", abs(hz0 - 440) < 20)
        // ★2 倍速后音高翻倍 → 880Hz（这就是"变调"了，即使我们只想变速）
        assertTrue("2x 后频率应约 880Hz（音高被连带改变），实际 $hz2x", abs(hz2x - 880) < 40)
    }

    @Test
    fun `3 因此无法做到 变速不变调 这正是用户需要的`() {
        val src = tone(sampleRate, 440.0)
        val hz0 = estimateHz(src)
        val hzFast = estimateHz(resampleBySpeed(src, 1.5f))

        // 明确记录：变速 1.5x 带来了 ~1.5x 的音高变化
        val pitchRatio = hzFast / hz0
        assertTrue(
            "变步长下音高随速度同比变化（期望≈1.5，实际 $pitchRatio）——" +
                "结论：scheme 3 只能做“黑胶转速”，不能做独立变速变调",
            pitchRatio in 1.4..1.6,
        )
        // 反之：若要求音高不变，这个方案必然失败
        assertNotEquals("音高必须不变才符合需求，但实际变了", 1.0, pitchRatio, 0.05)
    }

    @Test
    fun `4 结论 speed 等于 1 时无副作用 可作为默认路径`() {
        val src = tone(1000, 440.0)
        val same = resampleBySpeed(src, 1f)
        assertEquals("1.0x 应逐样本一致", src.size, same.size)
        for (i in 0 until minOf(100, src.size)) {
            assertEquals(src[i], same[i], 1e-6f)
        }
    }
}
