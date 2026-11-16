package pub.miku.neuralstem.player.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEnvelopeAccumulatorTest {

    @Test
    fun empty_input_has_no_envelope() {
        val acc = PcmEnvelopeAccumulator()
        assertNull(acc.finish())
    }

    @Test
    fun builds_fifty_points_per_second_and_keeps_peak() {
        val acc = PcmEnvelopeAccumulator()
        val channels = 2
        val sampleRate = 1000
        // 1 秒；前半 +0.25，后半 -1.0，验证 min/max 与点数
        val samples = FloatArray(sampleRate * channels) { i ->
            val frame = i / channels
            if (frame < sampleRate / 2) 0.25f else -1.0f
        }
        acc.onChunk(samples, channels, sampleRate)
        val wf = acc.finish()!!
        assertEquals(50, wf.pointCount)
        assertTrue(wf.min.min() <= -0.99f)
        assertTrue(wf.max.max() >= 0.24f)
        assertEquals(sampleRate.toLong(), wf.totalFrames)
    }

    @Test
    fun multiple_chunks_keep_accumulating() {
        val acc = PcmEnvelopeAccumulator()
        val chunk = FloatArray(500 * 2) { 0.5f }
        acc.onChunk(chunk, 2, 1000)
        acc.onChunk(chunk, 2, 1000)
        val wf = acc.finish()!!
        assertEquals(1000L, wf.totalFrames)
        assertEquals(50, wf.pointCount)
    }
}
