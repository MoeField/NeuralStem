package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Spleeter4OutputValidationTest {

    private val splits = 3
    private val expected = 2 * splits * Spleeter4Dsp.SPLIT_FRAMES * Spleeter4Dsp.MODEL_BINS

    @Test
    fun `四个 stem 正常通过`() {
        val r = checkedSpleeter4Outputs(Array<FloatArray?>(4) { FloatArray(expected) }, splits)
        assertEquals(4, r.size)
    }

    @Test
    fun `null stem 被拒绝`() {
        val bad = Array<FloatArray?>(4) { FloatArray(expected) }
        bad[2] = null
        assertThrows(IllegalStateException::class.java) { checkedSpleeter4Outputs(bad, splits) }
    }

    @Test
    fun `错长度被拒绝`() {
        val bad = Array<FloatArray?>(4) { FloatArray(expected) }
        bad[1] = FloatArray(expected - 1)
        assertThrows(IllegalArgumentException::class.java) { checkedSpleeter4Outputs(bad, splits) }
    }
}
