package pub.miku.neuralstem.spike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * ★2026-11-09：[ResamplingFrameSource] 单测（高采样率 OOM 修复的纯逻辑部分）。
 *
 * 关键契约：按**绝对输出帧**重采样 → 顺序读与随机读必须完全一致
 * （分离器会 seek/补前段，不能有“重采样相位”状态）。
 */
class ResamplingFrameSourceTest {

    private class FakeSource(
        private val data: FloatArray,
        override val channels: Int,
        override val sampleRate: Int,
    ) : FrameSource {
        override val totalFrames: Int = data.size / channels

        override fun readFrames(startFrame: Int, frames: Int): FloatArray {
            val avail = (totalFrames - startFrame).coerceAtLeast(0)
            val n = minOf(frames, avail)
            if (n <= 0) return FloatArray(0)
            val from = startFrame * channels
            return data.copyOfRange(from, from + n * channels)
        }

        override fun close() {}
    }

    private fun sine(frames: Int, channels: Int, sr: Int, hz: Double = 440.0): FloatArray =
        FloatArray(frames * channels) { i ->
            (0.5 * sin(2.0 * PI * hz * (i / channels) / sr)).toFloat()
        }

    @Test
    fun `48k to 44_1k：长度正确且波形连续`() {
        val srcRate = 48_000
        val srcFrames = 48_000
        val src = FakeSource(sine(srcFrames, 2, srcRate), 2, srcRate)
        val r = ResamplingFrameSource(src, srcRate, 44_100)
        assertEquals((srcFrames.toLong() * 44_100 / 48_000).toInt(), r.totalFrames)

        val out = r.readFrames(0, 4_410)
        assertEquals(4_410 * 2, out.size)
        // 440Hz @44.1k 相邻样本差应很小（线性插值不能产生断点）
        for (i in 1 until 4_410) {
            val d = abs(out[i * 2] - out[(i - 1) * 2])
            assertTrue("样本跳变过大 @$i: $d", d < 0.2f)
        }
    }

    @Test
    fun `随机读与顺序读的同一区间完全一致`() {
        val src = FakeSource(sine(48_000, 2, 48_000), 2, 48_000)
        val r = ResamplingFrameSource(src, 48_000, 44_100)
        val full = r.readFrames(0, 10_000)
        val slice = r.readFrames(3_000, 1_000)
        assertArrayEquals(
            full.copyOfRange(3_000 * 2, 4_000 * 2),
            slice,
            0f,
        )
    }

    @Test
    fun `mono 复制成双声道`() {
        val mono = FloatArray(4_800) { 0.25f }
        val r = ResamplingFrameSource(FakeSource(mono, 1, 48_000), 48_000, 44_100)
        assertEquals(2, r.channels)
        val out = r.readFrames(0, 100)
        for (i in 0 until 100) {
            assertEquals(out[i * 2], out[i * 2 + 1], 0f)
        }
    }

    @Test
    fun `close 删除临时文件`() {
        val tmp = File.createTempFile("sep-src-test", ".wav")
        tmp.writeText("x")
        val r = ResamplingFrameSource(
            FakeSource(sine(1_000, 2, 48_000), 2, 48_000),
            48_000,
            44_100,
            tempFile = tmp,
        )
        r.close()
        assertFalse("临时 WAV 应被删除", tmp.exists())
    }

    @Test
    fun `读取超出末尾返回静音而不越界`() {
        val src = FakeSource(FloatArray(2_000) { 0.5f }, 2, 48_000)
        val r = ResamplingFrameSource(src, 48_000, 44_100)
        val total = r.totalFrames
        val tail = r.readFrames(total - 10, 100)
        assertEquals(100 * 2, tail.size)
        // 末尾之后没有样本 → 保持 0
        assertEquals(0f, tail[99 * 2], 0f)
    }
}
