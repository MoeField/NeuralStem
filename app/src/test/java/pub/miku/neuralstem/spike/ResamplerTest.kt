package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Task 4.1 重采样的纯函数验证(不依赖 Android 类,可直接 JVM 单测)。
 */
class ResamplerTest {

    /** 生成 1kHz 正弦,给定采样率与秒数,单声道 */
    private fun sine(sampleRate: Int, seconds: Double, freq: Double = 1000.0): FloatArray {
        val frames = (sampleRate * seconds).toInt()
        return FloatArray(frames) { i ->
            (0.8 * sin(2 * PI * freq * i / sampleRate)).toFloat()
        }
    }

    @Test
    fun `相同采样率 零拷贝原样返回`() {
        val src = sine(44100, 0.1)
        val out = Resampler.resample(src, 1, 44100, 44100)
        assertSame("同采样率应直接返回原数组(零拷贝)", src, out)
    }

    @Test
    fun `48k 到 44点1k 帧数按比例缩放`() {
        val src = sine(48000, 1.0)
        val out = Resampler.resample(src, 1, 48000, 44100)
        // 1 秒 → 帧数应从 48000 变到 44100
        assertEquals(44100, out.size)
    }

    @Test
    fun `32k 到 44点1k 帧数增加`() {
        val src = sine(32000, 1.0)
        val out = Resampler.resample(src, 1, 32000, 44100)
        assertEquals(44100, out.size)
    }

    @Test
    fun `重采样后波形仍是正弦 幅度不显著变化`() {
        val src = sine(48000, 0.5)
        val out = Resampler.resample(src, 1, 48000, 44100)
        val peak = out.maxOf { abs(it) }
        // 线性插值不应明显改变幅度(允许少量尖峰衰减)
        assertTrue("重采样后峰值 $peak 应在 0.7–0.85 之间", peak in 0.7f..0.85f)
    }

    @Test
    fun `立体声重采样保持声道交织结构`() {
        // 左声道 +0.5,右声道 -0.5(交织),采样率翻倍
        val frames = 1000
        val src = FloatArray(frames * 2) { i -> if (i % 2 == 0) 0.5f else -0.5f }
        val out = Resampler.resample(src, 2, 22050, 44100)
        assertEquals(frames * 2 * 2, out.size)  // 帧数翻倍 × 2 声道
        // 声道关系保持:偶数下标为正,奇数为负
        assertTrue("左声道应保持为正", out[0] > 0)
        assertTrue("右声道应保持为负", out[1] < 0)
    }

    @Test
    fun `非法采样率被拒绝`() {
        val failed = try {
            Resampler.resample(FloatArray(100), 1, 0, 44100)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("采样率 0 应被拒绝", failed)
    }
}
