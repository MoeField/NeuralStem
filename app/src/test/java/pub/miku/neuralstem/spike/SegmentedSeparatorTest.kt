package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ★G-3：[SegmentedSeparator] 单测（写 ring buffer，不涉及 WAV 文件）。
 *
 * 用「恒等分离器」（vocals = accomp = 输入块）验证：从任意 startFrame 起、
 * 变长递增块 + 0.5s 重叠下，输出仍能逐样本还原输入（交叉淡化无误差）。
 */
class SegmentedSeparatorTest {

    private fun ramp(frames: Int, channels: Int): FloatArray =
        FloatArray(frames * channels) { i -> ((i / channels) % 1000) * 1e-3f - 0.5f }

    /** 模拟 sherpa：N_out = N_in - (N_in mod 1024)。 */
    private class SherpaLike(private val channels: Int, private val hop: Int = 1024) : ChunkSeparator {
        override fun separate(
            block: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): SherpaSourceSeparation.SeparationResult {
            assertEquals(this.channels, channels)
            val nIn = block.size / channels
            val nOut = nIn - (nIn % hop)
            val v = FloatArray(nOut * channels)
            val a = FloatArray(nOut * channels)
            System.arraycopy(block, 0, v, 0, nOut * channels)
            System.arraycopy(block, 0, a, 0, nOut * channels)
            return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
        }
    }

    private class MemFrameSource(
        private val samples: FloatArray,
        override val channels: Int,
        override val sampleRate: Int,
    ) : FrameSource {
        override val totalFrames: Int = samples.size / channels
        override fun readFrames(startFrame: Int, frames: Int): FloatArray {
            val avail = (totalFrames - startFrame).coerceAtLeast(0)
            val n = minOf(frames, avail)
            val out = FloatArray(n * channels)
            System.arraycopy(samples, startFrame * channels, out, 0, n * channels)
            return out
        }
        override fun close() {}
    }

    private fun runSeg(
        input: FloatArray,
        channels: Int,
        sampleRate: Int = 44_100,
        startFrame: Int = 0,
        overlap: Int = 4096,
        schedule: List<Int>? = null,
        chunkFrames: Int = 20_000,
    ): Triple<SegmentedSeparator.Stats, FloatArray, FloatArray> {
        val total = input.size / channels
        val rbV = StemPcmRingBuffer(channels, capacityFrames = total + 8192)
        val rbA = StemPcmRingBuffer(channels, capacityFrames = total + 8192)
        val stats = SegmentedSeparator(
            reader = MemFrameSource(input, channels, sampleRate),
            vocalsBuffer = rbV,
            accompBuffer = rbA,
            separator = SherpaLike(channels),
            startFrame = startFrame,
            overlapFrames = overlap,
            chunkSchedule = schedule,
            chunkFrames = chunkFrames,
        ).run()
        rbV.close(); rbA.close()
        val cv = rbV.openCursor(); val ca = rbA.openCursor()
        val outV = FloatArray(stats.vocalsFrames.toInt() * channels)
        val outA = FloatArray(stats.accompFrames.toInt() * channels)
        cv.read(outV, 0, stats.vocalsFrames.toInt())
        ca.read(outA, 0, stats.accompFrames.toInt())
        cv.close(); ca.close()
        return Triple(stats, outV, outA)
    }

    @Test
    fun `固定块 恒等分离 输出与输入逐样本一致`() {
        val ch = 2
        val frames = 50_000
        val input = ramp(frames, ch)
        val (stats, v, a) = runSeg(input, ch, overlap = 4096, chunkFrames = 20_000)

        assertEquals("应多块", true, stats.chunks >= 3)
        // 从 0 起，输出应等于输入前 vocalsFrames 帧。
        val n = stats.vocalsFrames.toInt()
        for (i in 0 until n * ch) {
            assertEquals("vocals[$i]", input[i], v[i], 1e-5f)
            assertEquals("accomp[$i]", input[i], a[i], 1e-5f)
        }
    }

    @Test
    fun `从非 0 位置起分离 输出从该帧开始`() {
        val ch = 1
        val frames = 40_000
        val input = ramp(frames, ch)
        val start = 12_000
        val (stats, v, _) = runSeg(input, ch, startFrame = start, overlap = 4096, chunkFrames = 10_000)

        assertEquals(start, stats.startFrame)
        val n = stats.vocalsFrames.toInt()
        assertTrue("应有输出", n > 0)
        for (i in 0 until n) {
            assertEquals("第 $i 帧应等于输入 $start+$i", input[start + i], v[i], 1e-5f)
        }
    }

    @Test
    fun `变长递增块 + 0_5s 重叠 恒等还原且首段回调触发一次`() {
        val ch = 1
        val sr = 44_100
        val frames = sr * 20
        val input = ramp(frames, ch)
        val overlap = sr / 2                 // 0.5s
        val schedule = listOf(sr * 4, sr * 8, sr * 16)

        val rbV = StemPcmRingBuffer(ch, capacityFrames = frames + 4096)
        val rbA = StemPcmRingBuffer(ch, capacityFrames = frames + 4096)
        val firstCalls = AtomicInteger(0)
        val stats = SegmentedSeparator(
            reader = MemFrameSource(input, ch, sr),
            vocalsBuffer = rbV,
            accompBuffer = rbA,
            separator = SherpaLike(ch),
            overlapFrames = overlap,
            chunkSchedule = schedule,
            onFirstSegmentReady = { firstCalls.incrementAndGet() },
        ).run()
        rbV.close(); rbA.close()

        assertEquals("首段回调应恰好 1 次", 1, firstCalls.get())
        assertTrue("应多块", stats.chunks >= 2)

        val cv = rbV.openCursor()
        val out = FloatArray(stats.vocalsFrames.toInt())
        cv.read(out, 0, out.size)
        cv.close()
        for (i in out.indices) assertEquals("frame $i", input[i], out[i], 1e-5f)
    }

    @Test
    fun `startFrame 超过总长 时无块`() {
        val ch = 1
        val input = ramp(1000, ch)
        val (stats, _, _) = runSeg(input, ch, startFrame = 5000)
        assertEquals(0, stats.chunks)
        assertEquals(0L, stats.vocalsFrames)
    }
}
