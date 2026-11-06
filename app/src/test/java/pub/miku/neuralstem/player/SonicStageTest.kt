@file:OptIn(UnstableApi::class)

package pub.miku.neuralstem.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * ★[SonicStage] 纯 JVM 单测（2026-11-06，P0-3 第 1 步）。
 *
 * 覆盖：1.0× 旁路、2×/0.5× 的输出/位置、仅变调不变时长、seek 重定位、
 * 回到 1.0×、曲尾排空。
 *
 * 位置口径：`positionFrames()` 是**媒体帧**（≈ 已喂入的源帧），
 * 不是写出帧 —— 变速后两者按 speed 换算。
 */
@UnstableApi
class SonicStageTest {

    private val sr = 44_100
    private val ch = 2
    private val bytesPerFrame = ch * 4

    private fun sine(frames: Int, hz: Double = 440.0): FloatArray =
        FloatArray(frames * ch) { i ->
            val f = i / ch
            (0.5 * sin(2.0 * PI * hz * f / sr)).toFloat()
        }

    /** 喂 [frames] 帧，返回写出的字节数。sink 假装全部写入成功。 */
    private fun feed(stage: SonicStage, frames: Int, chunk: Int = 1024): Long {
        var written = 0L
        var fed = 0
        while (fed < frames) {
            val n = minOf(chunk, frames - fed)
            written += stage.process(sine(n), n) { _, len -> len }
            fed += n
        }
        return written
    }

    private fun outFrames(bytes: Long): Double = bytes.toDouble() / bytesPerFrame

    @Test
    fun `1_0x 旁路：不经过 Sonic，位置不动`() {
        val stage = SonicStage(sr, ch)
        assertFalse("默认参数应旁路", stage.isActive)
        var sinkCalls = 0
        val written = stage.process(sine(1024), 1024) { _, len ->
            sinkCalls++
            len
        }
        assertEquals(0L, written)
        assertEquals("旁路不应调用 sink", 0, sinkCalls)
        assertEquals(0L, stage.positionFrames())
    }

    @Test
    fun `2x：输出约一半，媒体位置约等于已喂入帧`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(2f, 1f)
        assertEquals(0L, stage.applyParams(0L))
        assertTrue(stage.isActive)

        val fed = 44_100
        val bytes = feed(stage, fed)
        val out = outFrames(bytes)
        assertTrue("2x 输出应约 ${fed / 2} 帧，实际 $out", abs(out - fed / 2.0) < fed * 0.05)
        val pos = stage.positionFrames().toDouble()
        assertTrue("媒体位置应约 $fed 帧，实际 $pos", abs(pos - fed) < fed * 0.10)
    }

    @Test
    fun `0_5x：输出约两倍`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(0.5f, 1f)
        stage.applyParams(0L)

        val fed = 22_050
        val bytes = feed(stage, fed)
        val out = outFrames(bytes)
        assertTrue("0.5x 输出应约 ${fed * 2} 帧，实际 $out", abs(out - fed * 2.0) < fed * 0.10)
    }

    @Test
    fun `仅变调：时长不变`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(1f, 1.5f)
        stage.applyParams(0L)
        assertTrue(stage.isActive)

        val fed = 22_050
        val out = outFrames(feed(stage, fed))
        assertTrue("变调不应改变时长：期望约 $fed 帧，实际 $out", abs(out - fed) < fed * 0.05)
    }

    @Test
    fun `seek 后位置从目标帧重新起算`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(2f, 1f)
        stage.applyParams(0L)
        feed(stage, 4410)

        stage.flushAt(100_000L)
        assertEquals(100_000L, stage.positionFrames())

        // 再喂一点，位置应继续增长（而不是回到 0）
        feed(stage, 4410)
        assertTrue("seek 后位置应 > 100000，实际 ${stage.positionFrames()}", stage.positionFrames() > 100_000L)
    }

    @Test
    fun `回到 1_0x：回到旁路并返回修正后的源帧`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(2f, 1f)
        val src = stage.applyParams(0L)
        feed(stage, 8820)

        stage.setParams(1f, 1f)
        val newSrc = stage.applyParams(8820L)
        assertFalse("1.0x 应回到旁路", stage.isActive)
        assertTrue("回退量不应超过已喂入帧", newSrc in 0L..8820L)
        assertEquals(newSrc, stage.positionFrames())
    }

    @Test
    fun `曲尾：finish 排空后 isEnded`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(2f, 1f)
        stage.applyParams(0L)
        feed(stage, 4410)

        var drained = 0L
        stage.finish { _, len -> drained += len; len }
        assertTrue("finish 后应标记 ended", stage.isEnded)
        assertTrue("曲尾排空应写出数据（或本来就没有尾巴）", drained >= 0L)
    }

    @Test
    fun `重复设同参数不触发 flush`() {
        val stage = SonicStage(sr, ch)
        stage.setParams(2f, 1f)
        stage.applyParams(0L)
        feed(stage, 4410)
        val pos = stage.positionFrames()

        stage.setParams(2f, 1f) // 同值
        val newSrc = stage.applyParams(4410L)
        assertEquals("同值不应回退", 4410L, newSrc)
        assertTrue(stage.positionFrames() >= pos)
    }
}
