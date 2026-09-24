package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 包络提取纯函数验证(不走 Android 类,VM JVM 单测可直接跑)。
 */
class WaveformExtractorTest {

    /** 1kHz 正弦,1 秒,44100Hz 立体声 */
    private fun sine1s(sampleRate: Int = 44100, channels: Int = 2): FloatArray {
        val frames = sampleRate
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (0.8 * sin(2 * PI * 1000.0 * f / sampleRate)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    @Test
    fun `包络点数等于每秒50点 1秒音频得50点`() {
        val data = WaveformExtractor.computeEnvelope(sine1s(), 2, 44100)
        assertEquals(50, data.pointCount)
        assertEquals(44100, data.sampleRate)
        assertEquals(44100L, data.totalFrames)
        assertEquals(1000L, data.durationMs)
    }

    @Test
    fun `正弦包络的峰值接近振幅`() {
        val data = WaveformExtractor.computeEnvelope(sine1s(), 2, 44100)
        val maxOfMax = data.max.max()
        val minOfMin = data.min.min()
        // 正弦峰值 0.8,允许量化误差
        assertTrue("max 峰值 $maxOfMax 应接近 0.8", maxOfMax in 0.75f..0.81f)
        assertTrue("min 谷值 $minOfMin 应接近 -0.8", minOfMin in -0.81f..-0.75f)
    }

    @Test
    fun `positionMs 到包络下标的换算`() {
        val data = WaveformExtractor.computeEnvelope(sine1s(), 2, 44100)
        assertEquals(0, data.indexForPositionMs(0))
        assertEquals(25, data.indexForPositionMs(500))   // 中点
        assertEquals(49, data.indexForPositionMs(999))   // 不越界
        assertEquals(49, data.indexForPositionMs(5000))  // 超出 clamp
    }

    @Test
    fun `静音段包络为0`() {
        val data = WaveformExtractor.computeEnvelope(FloatArray(44100 * 2), 2, 44100)
        assertTrue(data.max.all { it == 0f })
        assertTrue(data.min.all { it == 0f })
    }

    @Test
    fun `超短音频至少产生1个点 不崩溃`() {
        val tiny = FloatArray(4) { 0.1f }  // 2帧立体声
        val data = WaveformExtractor.computeEnvelope(tiny, 2, 44100)
        assertTrue(data.pointCount >= 1)
    }
}