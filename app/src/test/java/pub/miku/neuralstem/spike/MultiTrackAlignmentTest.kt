package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * V1 — 多轨 seek 对齐的「文件级」验证(guide §3.6 硬要求)。
 *
 * ## 背景
 * guide §3.6 要求「seek 后各轨必须严格对齐,不能出现可感知的相位偏差」。
 * Task 4.4 的 `MultiTrackController` 用「同帧下发命令 + 共享 PlaybackParameters」
 * 做**逻辑保证**,但从未实测。本测试验证**分离产物本身同轴兼容**这一前提:
 * 若 vocals/accompaniment 在文件层就不对齐(长度不同、起点偏移),
 * 那么无论播放器怎么同步,叠加出来都会有相位差。
 *
 * ## 为什么用「文件级」而不是「播放级」验证
 * 播放级验证需要真机 + 实时状态,不确定且不可回归。
 * 分离产物是**确定性文件**,可离线做逐样本互相关 —— 这是主证据。
 * (播放期间的 frame-position 差由真机 `dumpsys media.audio_flinger` 作辅证。)
 *
 * ## ⚠️ 判据说可辨识性的两个前提(踩过的坑)
 * 1. **不能用周期信号**(锯齿/正弦):其互相关在「周期整数倍」偏移处同样出现高值,
 *    峰值不唯一 —— 会让延迟估计贴到搜索边界,产生假 lag。
 *    → 用确定性伪随机信号(自相关为尖锐单峰)。
 * 2. **不能用「反相信号」做 fixture**(vocals=x, accomp=−x):这是**负相关**,
 *    互相关峰值不在 lag=0 处,而在自相关最小处(随机位置)—— 延迟不可辨识。
 *    → 两轨必须**共享同一信号成分**(正相关),峰值才唯一落在真延迟处。
 *    本测试用 accomp = 0.6·x(vocals = x),二者共享 x,故峰值唯一在 lag=0。
 *
 * 判据:峰值 lag <= 1 采样点(44.1kHz 下 ≈ 23µs,远低于人耳可感知的相位阈)。
 */
class MultiTrackAlignmentTest {

    private fun tempFile(): File =
        File.createTempFile("align-test", ".wav").also { it.deleteOnExit() }

    /**
     * 确定性伪随机信号(种子固定)。自相关为尖锐单峰,延迟估计唯一。
     */
    private fun noise(frames: Int, seed: Long = 123456789L): FloatArray {
        var s = seed
        return FloatArray(frames) {
            s = s * 6364136223846793005L + 1442695040888963407L
            // 取高位映射到 [-0.5, 0.5)
            (((s ushr 40).toInt() and 0xFFFFF) / 1048576f) - 0.5f
        }
    }

    /**
     * 「共享成分分离器」:vocals = 输入,accomp = gain·输入。
     * 两轨都含同一信号 x → 互相关在真延迟处唯一取正峰(见类注释前提 2)。
     */
    private class SharedComponentSeparator(
        private val gain: Float = 0.6f,
    ) : ChunkSeparator {
        override fun separate(
            block: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): SherpaSourceSeparation.SeparationResult {
            val v = block.copyOf()
            val a = FloatArray(block.size) { block[it] * gain }
            return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
        }
    }

    /**
     * 让两轨**带时间偏移**的分离器:accomp 相对 vocals 延迟 [lagFrames] 帧。
     * 用于端到端阳性对照 —— 若互相关函数正确且拼接不引入额外偏移,应检出该 lag。
     * 仍保留共享成分(accomp = 延迟后的 x),保证延迟可辨识。
     */
    private class DelayingSeparator(
        private val channels: Int,
        private val lagFrames: Int,
        private val hop: Int = 1024,
    ) : ChunkSeparator {
        override fun separate(
            block: FloatArray,
            channels: Int,
            sampleRate: Int,
        ): SherpaSourceSeparation.SeparationResult {
            val nIn = block.size / channels
            val nOut = nIn - (nIn % hop)
            val v = FloatArray(nOut * channels)
            val a = FloatArray(nOut * channels)
            System.arraycopy(block, 0, v, 0, nOut * channels)
            // accomp 延迟 lagFrames 帧(前面补零)
            for (f in 0 until nOut) {
                val srcF = f - lagFrames
                for (c in 0 until channels) {
                    a[f * channels + c] =
                        if (srcF in 0 until nOut) block[srcF * channels + c] else 0f
                }
            }
            return SherpaSourceSeparation.SeparationResult(v, a, sampleRate)
        }
    }

    /** 跑一轮流式分离,返回 (vocals, accomp, frames)。 */
    private fun separate(
        input: FloatArray,
        channels: Int,
        chunkFrames: Int,
        overlapFrames: Int = 4096,
        separator: ChunkSeparator,
    ): Triple<FloatArray, FloatArray, Int> {
        val inFile = tempFile()
        WavWriter(inFile, channels, 44100).use { it.append(input) }
        val vFile = tempFile(); val aFile = tempFile()
        val stats: StreamingSeparator.Stats
        WavWriter(vFile, channels, 44100).use { vw ->
            WavWriter(aFile, channels, 44100).use { aw ->
                WavReader(inFile).use { r ->
                    stats = StreamingSeparator(
                        reader = WavFrameSource(r),
                        vocalsWriter = vw,
                        accompWriter = aw,
                        separator = separator,
                        chunkFrames = chunkFrames,
                        overlapFrames = overlapFrames,
                    ).run()
                }
            }
        }
        val v = WavReader(vFile).use { it.readFrames(0, stats.vocalsFrames) }
        val a = WavReader(aFile).use { it.readFrames(0, stats.accompFrames) }
        return Triple(v, a, stats.vocalsFrames)
    }

    /**
     * 手写互相关:返回使 `Σ v[f]·a[f+lag]` 最大的 lag(采样点,正数 = a 落后 v)。
     * 只看左右 [maxLag] 范围内。
     */
    private fun xcorrLag(v: FloatArray, a: FloatArray, channels: Int, maxLag: Int): Int {
        val nFrames = minOf(v.size, a.size) / channels
        var bestLag = 0
        var bestVal = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var s = 0.0
            for (f in 0 until nFrames) {
                val g = f + lag
                if (g < 0 || g >= nFrames) continue
                // 用第 0 声道即可(两轨同构)
                s += v[f * channels].toDouble() * a[g * channels].toDouble()
            }
            if (s > bestVal) { bestVal = s; bestLag = lag }
        }
        return bestLag
    }

    // ---------------------------------------------------------------

    @Test
    fun `阳性对照-互相关函数能检出注入的偏移`() {
        // 先证明「判据本身有效」:注入已知 lag,互相关必须检出。
        // 这是 V1 方法论的关键一环 —— 避免「测出 0 但其实判据坏了」。
        val v = noise(40_000)
        val a = FloatArray(40_000) { i -> if (i >= 37) v[i - 37] else 0f }
        assertEquals("互相关应检出注入的 +37 帧偏移", 37, xcorrLag(v, a, 1, 200))

        val b = FloatArray(40_000) { i -> if (i >= 111) v[i - 111] else 0f }
        assertEquals("互相关应检出注入的 +111 帧偏移", 111, xcorrLag(v, b, 1, 200))

        // 无偏移 → 必须为 0
        assertEquals("同一信号自身互相关峰值应在 0", 0, xcorrLag(v, v, 1, 200))
    }

    @Test
    fun `分离产物两轨逐样本对齐-lag为零`() {
        // 多块(触发交叉淡化拼接)+ 立体声,最接近真实分离产物的路径。
        val channels = 2
        val frames = 120_000
        val base = noise(frames, seed = 987654321L)
        val input = FloatArray(frames * channels) { i -> base[i / channels] }

        val (v, a, _) = separate(
            input, channels, chunkFrames = 20_000,
            separator = SharedComponentSeparator(),
        )

        val lag = xcorrLag(v, a, channels, maxLag = 200)
        assertEquals("vocals/accomp 应无相对偏移(相位偏差)", 0, lag)
    }

    @Test
    fun `分离产物两轨帧数完全一致-无长度漂移`() {
        // 长度不一致本身就是 seek 对齐的隐患(尾部一段唱一句长一句短)。
        val channels = 2
        val frames = 150_000
        val base = noise(frames, seed = 55555L)
        val input = FloatArray(frames * channels) { i -> base[i / channels] }

        val (v, a, n) = separate(
            input, channels, chunkFrames = 30_000,
            separator = SharedComponentSeparator(),
        )
        assertEquals("两轨帧数必须一致", v.size / channels, a.size / channels)
        assertEquals("两轨帧数应等于 stats", n, v.size / channels)
    }

    @Test
    fun `任意起点切片 互相关仍为零-模拟任意seek位置`() {
        // 模拟 seek 到不同位置:在多个偏移切片上分别做互相关,
        // 每处 lag 都必须为 0(不能只在开头对齐)。
        val channels = 1
        val frames = 100_000
        val input = noise(frames)

        val (v, a, n) = separate(
            input, channels, chunkFrames = 25_000,
            separator = SharedComponentSeparator(),
        )

        val win = 8_000
        for (start in listOf(0, n / 4, n / 2, 3 * n / 4)) {
            if (start + win > n) continue
            val vs = v.copyOfRange(start, start + win)
            val as_ = a.copyOfRange(start, start + win)
            val lag = xcorrLag(vs, as_, channels, maxLag = 100)
            assertEquals("起点 $start 处应无偏移", 0, lag)
        }
    }

    @Test
    fun `DelayingSeparator阳性对照-拼接不引入额外偏移`() {
        // 端到端阳性对照:让分离器真的产生 5 帧偏移,经完整拼接管线后
        // 互相关仍应检出 5 帧 —— 证明「拼接过程本身不引入额外偏移」。
        val channels = 1
        val frames = 60_000
        val input = noise(frames)
        val injected = 5

        val (v, a, _) = separate(
            input, channels, chunkFrames = 20_000,
            separator = DelayingSeparator(channels, injected),
        )

        val lag = xcorrLag(v, a, channels, maxLag = 100)
        assertEquals("拼接后应保留注入的 $injected 帧偏移", injected, lag)
    }
}
