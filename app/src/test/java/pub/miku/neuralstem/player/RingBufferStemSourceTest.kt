package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pub.miku.neuralstem.spike.StemPcmRingBuffer

/**
 * ★2026-11-06：[RingBufferStemSource] 的“音频线程安全”契约。
 *
 * 边播时这个 source 由 [StemMixer] 的**渲染线程**直接调用。旧实现走阻塞版
 * `Cursor.read`（无数据 awaitNanos(2s)），会把音频线程卡住；现在必须：
 * - 没有数据 → 立即返回 0（交给上层/Gate 决策），绝不阻塞；
 * - 是否“真的结束”由 [StemSource.isFinished] 单独表达，不能靠 read 返回 0 推断。
 */
class RingBufferStemSourceTest {

    private fun sourceOf(
        buffer: StemPcmRingBuffer,
        ringStartFrame: Long = 0L,
    ) = RingBufferStemSource(buffer, sampleRate = 44_100, ringStartFrame = ringStartFrame)

    @Test
    fun `分离未结束时 isFinished 为 false`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        val src = sourceOf(rb)
        try {
            assertFalse("分离还在进行，不能说“不会再有数据”", src.isFinished)
            rb.append(FloatArray(4) { 1f })
            val out = FloatArray(4)
            assertEquals(4, src.read(0, out, 4))
        } finally {
            rb.close()
        }
    }

    @Test
    fun `追上写头时 read 立即返回 0（不阻塞），且 isFinished 仍为 false`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 64)
        val src = sourceOf(rb)
        try {
            rb.append(FloatArray(4) { 2f })
            val out = FloatArray(4)
            assertEquals(4, src.read(0, out, 4))

            val t0 = System.nanoTime()
            assertEquals("边播中暂时没数据应返回 0", 0, src.read(4, out, 4))
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("read 不应阻塞（实际 ${elapsedMs}ms）", elapsedMs < 100)
            assertFalse("追上写头 ≠ 曲终", src.isFinished)
        } finally {
            rb.close()
        }
    }

    @Test
    fun `close 之后 isFinished 为 true，读尽返回 0`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        val src = sourceOf(rb)
        try {
            rb.append(FloatArray(3) { 3f })
            rb.close()
            assertTrue("分离结束/取消后应标记 finished", src.isFinished)
            val out = FloatArray(3)
            assertEquals(3, src.read(0, out, 3))
            assertEquals(0, src.read(3, out, 3))
        } finally {
            rb.close()
            src.close()
        }
    }

    @Test
    fun `分离起点之前的帧返回 0（startFrame 偏移语义）`() {
        val rb = StemPcmRingBuffer(channels = 1, capacityFrames = 16)
        val src = sourceOf(rb, ringStartFrame = 1_000)
        try {
            rb.append(FloatArray(4) { 5f })
            val out = FloatArray(4)
            assertEquals("起点之前没有数据", 0, src.read(0, out, 4))
            assertEquals(4, src.read(1_000, out, 4))
        } finally {
            rb.close()
            src.close()
        }
    }
}
