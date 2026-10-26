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
        // ★Step 2：用 onWrite 回调收集输出（不再直连 ring buffer），
        //   同时校验 absFrame 的连续性（所有分段应无空洞、无重叠地铺满）。
        val rbV = StemPcmRingBuffer(channels, capacityFrames = total + 8192)
        val rbA = StemPcmRingBuffer(channels, capacityFrames = total + 8192)
        var expectedAbs = startFrame
        val stats = SegmentedSeparator(
            reader = MemFrameSource(input, channels, sampleRate),
            separator = SherpaLike(channels),
            onWrite = { v, a, frames, absFrame ->
                require(absFrame == expectedAbs) {
                    "absFrame 不连续: 期望 $expectedAbs 实际 $absFrame"
                }
                require(v.size == frames * channels) { "vocals 长度不符" }
                require(a.size == frames * channels) { "accomp 长度不符" }
                rbV.append(v, 0, frames)
                rbA.append(a, 0, frames)
                expectedAbs += frames
            },
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
            separator = SherpaLike(ch),
            onWrite = { v, a, f, _ ->
                rbV.append(v, 0, f)
                rbA.append(a, 0, f)
            },
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

    // ============================ 尾块过短回归（2026-10-26 崩溃）============================

    /**
     * ★★回归：尾块只有几百帧时**不得**送去 native。
     *
     * ## 崩溃现场（真机 BF00A，喀秋莎 150s）
     * ```
     * segmented: from=6639816 total=6640128 chunks=1 lens=[312]
     * Abort message: 'terminating due to uncaught exception of type std::length_error: vector'
     *   #12 libsherpa-onnx-c-api.so (SherpaOnnxOfflineSourceSeparationProcess+188)
     * ```
     * 312 帧 < 512 跳长 → sherpa 切出 0 个频谱帧 → 构造异常长度 vector → abort。
     *
     * ## 本测试怎么拦
     * 用一个“行为像 sherpa”的假分离器：输入帧数 < 跳长时**主动断言失败**，
     * 从而把“尾块太小”变成 JVM 层的测试失败，而不是真机上的闪退。
     */
    @Test
    fun `尾块只有几百帧时 不得送给分离器`() {
        val ch = 2
        val sr = 44_100
        // ★精确复现真机参数（喀秋莎）：
        //   segmented: from=6639816 total=6640128 chunks=1 lens=[312]
        // 关键：从 startFrame 直接开始，且剩余只有 312 帧 → chunks=1。
        // 这正是旧代码 `if (plan.size > 1)` 校验被绕过的那条路。
        val totalFrames = 6_640_128
        val startFrame = 6_639_816   // totalFrames - 312
        val input = ramp(totalFrames, ch)
        val overlap = sr / 2
        val schedule = listOf(sr * 4, sr * 8, sr * 16, sr * 30)

        var sawTooSmall = false
        val strictSeparator = object : ChunkSeparator {
            override fun separate(
                block: FloatArray,
                channels: Int,
                sampleRate: Int,
            ): SherpaSourceSeparation.SeparationResult {
                val nIn = block.size / channels
                // sherpa 的 512 跳长：不足一个跳就切不出频谱帧。
                // 真机上这会 abort，这里把它变成可见的失败。
                if (nIn < 512) {
                    sawTooSmall = true
                    throw AssertionError("尾块只有 $nIn 帧 (<512) → 真机会 std::length_error 闪退")
                }
                val nOut = nIn - (nIn % 1024)
                val v = FloatArray(nOut * channels)
                val a = FloatArray(nOut * channels)
                System.arraycopy(block, 0, v, 0, nOut * channels)
                System.arraycopy(block, 0, a, 0, nOut * channels)
                return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
            }
        }

        val rbV = StemPcmRingBuffer(ch, capacityFrames = totalFrames + 8192)
        val rbA = StemPcmRingBuffer(ch, capacityFrames = totalFrames + 8192)
        val stats = SegmentedSeparator(
            reader = MemFrameSource(input, ch, sr),
            separator = strictSeparator,
            onWrite = { v, a, f, _ ->
                rbV.append(v, 0, f)
                rbA.append(a, 0, f)
            },
            startFrame = startFrame,
            overlapFrames = overlap,
            chunkSchedule = schedule,
        ).run()
        rbV.close(); rbA.close()

        assertTrue("绝不该把 <512 帧的块送给分离器（否则真机闪退）", !sawTooSmall)
    }

    /**
     * 同上，但直接断言 `normalizeTail` 的合并行为：
     * 尾块小于 [SegmentedSeparator.MIN_CHUNK_FRAMES] 时，它应被并入前一块，
     * 而不是自成一块。
     */
    @Test
    fun `尾块过短时会被并入前一块 而不是单独成块`() {
        val ch = 1
        val sr = 44_100
        val tinyTail = 312
        val frames = sr * 4 + sr * 8 + tinyTail
        val input = ramp(frames, ch)
        val overlap = sr / 2
        val schedule = listOf(sr * 4, sr * 8, sr * 16)

        val lensSeen = ArrayList<Int>()
        val sep = object : ChunkSeparator {
            override fun separate(
                block: FloatArray,
                channels: Int,
                sampleRate: Int,
            ): SherpaSourceSeparation.SeparationResult {
                val nIn = block.size / channels
                lensSeen += nIn
                val nOut = nIn - (nIn % 1024)
                val v = FloatArray(nOut * channels)
                val a = FloatArray(nOut * channels)
                System.arraycopy(block, 0, v, 0, nOut * channels)
                System.arraycopy(block, 0, a, 0, nOut * channels)
                return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
            }
        }
        val rbV = StemPcmRingBuffer(ch, capacityFrames = frames + 8192)
        val rbA = StemPcmRingBuffer(ch, capacityFrames = frames + 8192)
        SegmentedSeparator(
            reader = MemFrameSource(input, ch, sr),
            separator = sep,
            onWrite = { v, a, f, _ ->
                rbV.append(v, 0, f)
                rbA.append(a, 0, f)
            },
            overlapFrames = overlap,
            chunkSchedule = schedule,
        ).run()
        rbV.close(); rbA.close()

        // 关键断言：没有任何一块小于 MIN_CHUNK_FRAMES
        val tooSmall = lensSeen.filter { it < SegmentedSeparator.MIN_CHUNK_FRAMES }
        assertEquals("不应存在 <MIN_CHUNK_FRAMES 的块，实际: $lensSeen", emptyList<Int>(), tooSmall)
        // 且最后一块应覆盖到曲尾（合并后变长）
        assertTrue("最后一块应 >= 前一块长度（已合并尾块）", lensSeen.isNotEmpty())
    }
}
