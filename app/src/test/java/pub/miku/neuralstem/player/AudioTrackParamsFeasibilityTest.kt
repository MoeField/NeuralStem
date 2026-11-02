package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ★多轨变速变调 —— **方案 2（AudioTrack.setPlaybackParams）可行性验证**。
 *
 * ## 方案描述
 * [StemMixer] 内部有一个 `AudioTrack`。最省事的想法是直接：
 * ```
 * track.playbackParams = track.playbackParams.setSpeed(speed).setPitch(pitch)
 * ```
 * Android 原生会做时间拉伸 + 音高变换，我们一行代码都不用写。
 *
 * ## 本测试要揭示的两个问题
 *
 * ### 问题 A（结构性的，本测试覆盖）
 * `StemMixer` 的播放头 `framePos` 是按 **"我往 AudioTrack 写了多少帧"** 推进的：
 * ```
 * framePos += (written / channels)     // 见 StemMixer.renderLoop
 * ```
 * 一旦 AudioTrack 以 `speed` 倍速**消费**，写入速度与消费速度就脱钩了：
 * - 我们写 N 帧 → 实际播出 N/speed 秒
 * - 但 `framePos` 只前进了 N
 * - ⇒ **位置显示、进度条、seek、时长全部失准**
 *
 * 本测试用纯数学证明这个失准的幅度（不依赖真机）。
 *
 * ### 问题 B（真机才能验证，本测试只能标注）
 * 频繁改参会触发 AudioTrack 重配 → 可能 `ERROR_CODE_TIMEOUT`。
 * 这条已在单轨上被实测过（见 PlaybackRenderers.kt 坑 48），
 * 多轨是否同样会踩，**必须真机验证**。
 */
class AudioTrackParamsFeasibilityTest {

    /**
     * 模拟 StemMixer 的位置推进逻辑：
     * 我们每轮写 [chunkFrames] 帧，[framePos] 按写入量推进（当前实现）。
     * 而真实播出时间是 `written / speed`。
     */
    private fun simulate(elapsedOutputFrames: Long, speed: Float): Pair<Long, Long> {
        // 返回 (UI 认为的帧位置, 实际播出的源帧位置)
        val uiFramePos = elapsedOutputFrames              // 当前实现：1:1
        val actualPlayedFrames = (elapsedOutputFrames * speed).toLong()  // 实际
        return uiFramePos to actualPlayedFrames
    }

    @Test
    fun `1 1倍速时位置完全正确 无偏差`() {
        val (ui, actual) = simulate(44100L, 1f)
        assertEquals("1.0x 时位置应完全一致", actual, ui)
    }

    @Test
    fun `2 2倍速时位置偏差达 50 percent 这是致命问题`() {
        val elapsed = 44100L   // 输出 1 秒
        val (ui, actual) = simulate(elapsed, 2f)

        // 实际已播过源音频 2 秒，但 UI 显示 1 秒
        assertEquals("实际源位置应为 88200 帧", 88200L, actual)
        assertEquals("UI 位置仍是 44100 帧", 44100L, ui)

        val drift = abs(actual - ui).toDouble() / actual
        assertTrue(
            "偏差达 ${(drift * 100).toInt()}%，位置/进度条/seek 全部失准",
            drift > 0.49,
        )
    }

    @Test
    fun `3 0点5倍速时反向偏差 同样致命`() {
        val elapsed = 44100L
        val (ui, actual) = simulate(elapsed, 0.5f)

        assertEquals("实际源位置应为 22050 帧", 22050L, actual)
        assertEquals("UI 位置仍是 44100 帧（已超过实际播放位置）", 44100L, ui)
        assertTrue("UI 位置超前于实际播放位置", ui > actual)
    }

    @Test
    fun `4 偏差随时长线性累积 播放越久越离谱`() {
        val diffs = listOf(10_000L, 100_000L, 1_000_000L).map { elapsed ->
            val (ui, actual) = simulate(elapsed, 2f)
            abs(actual - ui)
        }
        // 严格单调递增 → 不可能靠"偶尔校准"掩盖
        assertTrue("偏差应随时长累积：$diffs", diffs[0] < diffs[1] && diffs[1] < diffs[2])
    }

    /**
     * 修复问题 A 需要的额外逻辑（本测试证明其必要性；
     * 但注意：这依赖 `AudioTrack.getPlaybackHeadPosition()`，
     * 而 StemMixer 当前用的是**写入计数**，两者是不同的时钟）。
     */
    @Test
    fun `5 若改用 speed 校正位置 则需引入第二个时钟`() {
        // 正确做法：位置 = 写入帧数 / speed（或用 getPlaybackHeadPosition）
        val elapsed = 44100L
        val speed = 2f
        val correctedFramePos = (elapsed * speed).toLong()
        assertEquals("校正后应与实际源位置一致", 88200L, correctedFramePos)
        // 但这意味着 framePos 不能再由 write() 返回值直接累加 ——
        // StemMixer.renderLoop 的核心循环需要改结构
    }
}
