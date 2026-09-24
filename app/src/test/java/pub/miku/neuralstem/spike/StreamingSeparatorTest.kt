package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 0e 的核心验证:分块交叉淡化拼接的正确性。
 *
 * 不需要 native 库/ONNX 模型 —— 用 [ChunkSeparator] 注入「假分离器」即可。
 * 这里注入的是**恒等分离器**(vocals = accomp = 输入块),于是
 * `vocals == accomp == 原始输入` 应当逐样本成立。
 * 这直接验证了类注释里那条数学结论:
 *   重叠区两块权重和恰为 1 → `Σw·x / Σw == x`
 * 若权重设计有误(斜坡不成对、重叠不足留下空洞、归一化漏做),
 * 这个测试会立刻在拼接边界处失败。
 *
 * 另外用「可辨识分离器」(vocals = +x, accomp = -x)验证两轨确实独立落盘。
 */
class StreamingSeparatorTest {

    private fun tempFile(): File =
        File.createTempFile("streamsep-test", ".wav").also { it.deleteOnExit() }

    /** 斜坡信号:相邻样本差 1e-3,足以暴露任何拼接错位(比正弦更敏感)。 */
    private fun ramp(frames: Int, channels: Int): FloatArray =
        FloatArray(frames * channels) { i -> ((i / channels) % 1000) * 1e-3f - 0.5f }

    /**
     * 模拟 sherpa 的单次调用行为:`N_out = N_in - (N_in mod 1024)`
     * (由 Task 0d 实测数据精确推导出的规则)。
     */
    private class SherpaLikeSeparator(
        private val channels: Int,
        private val negateAccomp: Boolean = false,
        private val hop: Int = 1024,
    ) : ChunkSeparator {
        val observedInputFrames = mutableListOf<Int>()

        override fun separate(
            block: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): SherpaSourceSeparation.SeparationResult {
            assertEquals("channels 应与 reader 一致", this.channels, channels)
            val nIn = block.size / channels
            observedInputFrames += nIn
            val nOut = nIn - (nIn % hop)          // ← 实测规则
            val v = FloatArray(nOut * channels)
            val a = FloatArray(nOut * channels)
            System.arraycopy(block, 0, v, 0, nOut * channels)
            System.arraycopy(block, 0, a, 0, nOut * channels)
            if (negateAccomp) for (i in a.indices) a[i] = -a[i]
            return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
        }
    }

    /** 跑一轮流式分离,返回 (输入, vocals, accomp, stats)。 */
    private fun runStreaming(
        input: FloatArray,
        channels: Int,
        sampleRate: Int = 44100,
        chunkFrames: Int = 20_000,
        overlapFrames: Int = 4096,
        negateAccomp: Boolean = false,
    ): Pair<StreamingSeparator.Stats, Triple<FloatArray, FloatArray, FloatArray>> {
        val inputFile = tempFile()
        WavWriter(inputFile, channels, sampleRate).use { it.append(input) }

        val vocalsFile = tempFile()
        val accompFile = tempFile()
        val sep = SherpaLikeSeparator(channels, negateAccomp)

        val stats: StreamingSeparator.Stats
        WavWriter(vocalsFile, channels, sampleRate).use { vw ->
            WavWriter(accompFile, channels, sampleRate).use { aw ->
                WavReader(inputFile).use { wavReader ->
                    // Task 4.1:StreamingSeparator 改为接受 FrameSource 接口
                    stats = StreamingSeparator(
                        reader = WavFrameSource(wavReader),
                        vocalsWriter = vw,
                        accompWriter = aw,
                        separator = sep,
                        chunkFrames = chunkFrames,
                        overlapFrames = overlapFrames,
                    ).run()
                }
            }
        }

        val vocals = WavReader(vocalsFile).use { it.readFrames(0, stats.vocalsFrames) }
        val accomp = WavReader(accompFile).use { it.readFrames(0, stats.accompFrames) }
        return stats to Triple(input, vocals, accomp)
    }

    @Test
    fun `分块拼接不引入额外误差 与单块结果一致`() {
        val channels = 2
        val frames = 50_000
        val input = ramp(frames, channels)

        // 参照:整个文件当一块处理 → 无重叠、无交叉淡化。
        // 关键:参照与待测走**同一套** WavWriter/WavReader,量化行为完全相同,
        // 因此两者之差只可能来自「拼接」本身。这样就不必去推导 PCM 量化误差上界
        // (那个上界受截断/常量不对称影响,手推容易出错,见 WavIoStreamingTest)。
        val (refStats, refTriple) = runStreaming(
            input, channels, chunkFrames = frames, overlapFrames = 4096,
        )
        val (_, refVocals, refAccomp) = refTriple
        assertEquals("参照应为单块", 1, refStats.chunks)

        // 待测:多块 + 交叉淡化(50k / 20k → 3 块,
        // 即至少两个重叠区被真正验证)
        val (stats, triple) = runStreaming(input, channels, chunkFrames = 20_000)
        val (_, vocals, accomp) = triple
        assertTrue("应为多块,实际 ${stats.chunks}", stats.chunks >= 3)

        // 末尾最多丢 1 个 hop(1023 帧),不应更多。
        val lost = stats.inputFrames - stats.vocalsFrames
        assertTrue("末尾丢失 $lost 帧,超过 1 个 hop(1023)", lost in 0..1023)

        val n = minOf(stats.vocalsFrames, refStats.vocalsFrames)
        assertTrue("有效对比长度应为正,实际 $n", n > 0)
        var maxV = 0f
        var maxA = 0f
        for (i in 0 until n * channels) {
            maxV = maxOf(maxV, Math.abs(refVocals[i] - vocals[i]))
            maxA = maxOf(maxA, Math.abs(refAccomp[i] - accomp[i]))
        }
        // 阈值 1e-5 远小于 1 个 LSB(≈3.05e-5):足以判定「交叉淡化拼接无误差」,
        // 又不去要求浮点求和顺序逐位一致。
        assertTrue("vocals 拼接相对单块差异 $maxV 过大", maxV < 1e-5f)
        assertTrue("accomp 拼接相对单块差异 $maxA 过大", maxA < 1e-5f)
    }

    @Test
    fun `两轨独立落盘 互不串音`() {
        val channels = 1
        val frames = 30_000
        val input = ramp(frames, channels)

        val (stats, triple) = runStreaming(
            input, channels, chunkFrames = 20_000, negateAccomp = true,
        )
        val (_, vocals, accomp) = triple

        assertNotEquals("两轨不应相同", vocals[100], accomp[100])
        // vocals = +x, accomp = -x ⇒ vocals ≈ -accomp
        for (i in 0 until stats.vocalsFrames) {
            val sum = vocals[i] + accomp[i]
            assertTrue("帧 $i: 两轨应为相反数,和 $sum 偏离 0", Math.abs(sum) <= 2f / 32768f)
        }
    }

    @Test
    fun `短音频退化单块 与整文件单块处理一致`() {
        val channels = 2
        val frames = 5_000          // < chunk → 应退化为单块
        val input = ramp(frames, channels)

        val (stats, triple) = runStreaming(
            input, channels, chunkFrames = 20_000, overlapFrames = 4096,
        )
        val (_, vocals, accomp) = triple
        assertEquals("短音频应只用 1 块", 1, stats.chunks)

        // 单块路径无交叉淡化,应与「整文件当一块」逐样本一致。
        val (refStats, refTriple) = runStreaming(
            input, channels, chunkFrames = frames, overlapFrames = 4096,
        )
        val (_, refVocals, refAccomp) = refTriple
        assertEquals(1, refStats.chunks)
        assertEquals(stats.vocalsFrames, refStats.vocalsFrames)

        for (i in 0 until stats.vocalsFrames * channels) {
            assertTrue(
                "帧 $i 与单块参照不一致(应完全相同)",
                Math.abs(refVocals[i] - vocals[i]) < 1e-7f,
            )
            assertTrue(
                "帧 $i accomp 与单块参照不一致",
                Math.abs(refAccomp[i] - accomp[i]) < 1e-7f,
            )
        }
    }

    @Test
    fun `块输出长度符合 sherpa 规则 且不足以留空洞`() {
        val channels = 2
        val frames = 50_000
        val input = ramp(frames, channels)

        val (stats, _) = runStreaming(input, channels, chunkFrames = 20_000)

        assertTrue("应为多块", stats.chunks >= 3)
        // 每块输出长度必须是 1024 的整数倍(实测规则 N_out = N_in - N_in mod 1024)
        for ((i, len) in stats.chunkOutputLengths.withIndex()) {
            assertEquals("块 $i 输出长度 $len 不是 1024 的倍数", 0, len % 1024)
        }
        // 非尾块输出必须 >= hop,否则块间会有空洞
        val hop = 20_000 - 4096
        for (i in 0 until stats.chunks - 1) {
            assertTrue(
                "块 $i 输出 ${stats.chunkOutputLengths[i]} < hop $hop",
                stats.chunkOutputLengths[i] >= hop,
            )
        }
    }

    @Test
    fun `重叠不足 1 个 hop 时拒绝运行 而不是产出带空洞的音轨`() {
        // 必须是**多块**场景,约束 A 才会生效(单块无重叠区)。
        val input = ramp(100_000, 1)
        val failed = try {
            runStreaming(input, 1, chunkFrames = 40_000, overlapFrames = 512) // 512 < 1024
            false
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应说明 overlap 不足", e.message!!.contains("overlapFrames"))
            true
        }
        assertTrue("overlap <= 1024 时应拒绝运行", failed)
    }

    @Test
    fun `chunk 小于 2 倍 overlap 时拒绝运行(约束 B)`() {
        // 约束 B 是正确性前提:不满足时 body 区不再单覆盖,不能静默产出错音频。
        val input = ramp(100_000, 1)
        val failed = try {
            runStreaming(input, 1, chunkFrames = 6_000, overlapFrames = 4_096) // 6000 < 8192
            false
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "错误信息应说明 chunk/overlap 关系,实际: ${e.message}",
                e.message!!.contains("2 * overlapFrames"),
            )
            true
        }
        assertTrue("chunk < 2*overlap 时应拒绝运行", failed)
    }

    @Test
    fun `短音频单块时 不因 overlap 参数被误拒`() {
        // 回归:约束 A/B 曾写成无条件校验,导致短音频 + 小 overlap 被无故拒绝。
        // 单块根本没有重叠区,overlap 取任何值都应可以跑。
        val input = ramp(5_000, 1)
        val (stats, _) = runStreaming(input, 1, chunkFrames = 20_000, overlapFrames = 512)
        assertEquals("短音频应只用 1 块", 1, stats.chunks)
        assertTrue("应有输出", stats.vocalsFrames > 0)
    }

    @Test
    fun `进度回调单调递增 且终值不超过总帧数`() {
        val channels = 2
        val frames = 50_000
        val inputFile = tempFile()
        WavWriter(inputFile, channels, 44100).use { it.append(ramp(frames, channels)) }

        val seen = mutableListOf<Pair<Int, Int>>()
        val vocalsFile = tempFile()
        val accompFile = tempFile()
        WavWriter(vocalsFile, channels, 44100).use { vw ->
            WavWriter(accompFile, channels, 44100).use { aw ->
                WavReader(inputFile).use { wavReader ->
                    StreamingSeparator(
                        reader = WavFrameSource(wavReader),
                        vocalsWriter = vw,
                        accompWriter = aw,
                        separator = SherpaLikeSeparator(channels),
                        chunkFrames = 20_000,
                        overlapFrames = 4096,
                        onProgress = { done, total ->
                            seen += done to total
                            Unit                // 显式 void:避免 lambda 末表达式被推断为值
                        },
                    ).run()
                }
            }
        }

        assertTrue("应有进度回调", seen.isNotEmpty())
        assertTrue("总帧数应一致", seen.all { it.second == frames })
        for (i in 1 until seen.size) {
            assertTrue("进度应单调不减", seen[i].first >= seen[i - 1].first)
        }
        assertTrue("终值不应超过总帧数", seen.last().first <= frames)
    }

    // ==================== 边界:various total 长度 ====================

    /**
     * 总长度刚好跨过「单块/两块的临界点」时,拼接必须仍然正确。
     *
     * 关注点:尾块长度 = `total - s` 可能很短(甚至 < overlap),
     * 而 pending 尾巴缓冲容量正是 `overlap` —— 需要确认不会触发容量断言。
     */
    @Test
    fun `总长略超一块时 拼接仍正确`() {
        val channels = 2
        val chunk = 40_000
        // 取一批“敏感”总长:刚好一块+1、一半 hop、略超一块+overlap 等
        val totals = listOf(
            chunk + 1,
            chunk + 1024,
            chunk + 4096,
            chunk + 20_000,
            chunk + 35_904,        // = chunk + hop → 恰好两块、尾块极短
            chunk + 35_905,
        )
        for (total in totals) {
            val input = ramp(total, channels)
            val (stats, triple) = runStreaming(input, channels, chunkFrames = chunk)
            val (_, vocals, _) = triple

            val lost = stats.inputFrames - stats.vocalsFrames
            assertTrue(
                "total=$total: 末尾丢失 $lost 帧,超过 1 个 hop",
                lost in 0..1023,
            )
            // 与单块参照逐样本对比
            val (refStats, refTriple) = runStreaming(
                input, channels, chunkFrames = total, overlapFrames = 4096,
            )
            val (_, refVocals, _) = refTriple
            val n = minOf(stats.vocalsFrames, refStats.vocalsFrames)
            var maxDiff = 0f
            for (i in 0 until n * channels) {
                maxDiff = maxOf(maxDiff, Math.abs(refVocals[i] - vocals[i]))
            }
            assertTrue(
                "total=$total: 拼接与单块差异 $maxDiff 过大(chunks=${stats.chunks})",
                maxDiff < 1e-5f,
            )
        }
    }

    /**
     * 扫描一批总长与块长组合,确保**没有任何一个**组合触发
     * pending 容量断言或留空洞 —— 把边界条件交给暴力扫描,而不是靠推理。
     */
    @Test
    fun `参数扫描 任何组合都不抛异常且尾部丢失不超过 1 个 hop`() {
        val channels = 1
        val chunkSizes = listOf(8_192, 20_000, 40_000)   // 均 >= 2*4096
        val overlap = 4096
        var cases = 0
        for (chunk in chunkSizes) {
            val hop = chunk - overlap
            // 覆盖:单块、两块、多块、以及尾块极短的几种长度
            val totals = listOf(
                chunk, chunk + 1, chunk + hop - 1, chunk + hop, chunk + hop + 1,
                2 * chunk, 3 * chunk - 1, 3 * chunk, 5 * chunk + 12345,
            )
            for (total in totals) {
                if (total <= 0) continue
                val input = ramp(total, channels)
                val (stats, _) = runStreaming(
                    input, channels, chunkFrames = chunk, overlapFrames = overlap,
                )
                val lost = stats.inputFrames - stats.vocalsFrames
                assertTrue(
                    "chunk=$chunk total=$total: 尾部丢失 $lost 帧,超过 1 个 hop",
                    lost in 0..1023,
                )
                assertTrue("chunk=$chunk total=$total: 无输出", stats.vocalsFrames > 0)
                cases++
            }
        }
        assertTrue("扫描用例数过少: $cases", cases >= 20)
    }
}