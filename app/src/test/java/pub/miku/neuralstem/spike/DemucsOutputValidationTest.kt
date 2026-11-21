package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DemucsOutputValidationTest {

    private fun valid(): Array<FloatArray?> = arrayOf(
        FloatArray(DemucsEstimator.OUT_SPEC_LEN),
        FloatArray(DemucsEstimator.OUT_WAVE_LEN),
    )

    @Test
    fun `正常长度通过`() {
        val r = checkedDemucsOutputs(valid())
        assertEquals(2, r.size)
    }

    @Test
    fun `null 输出被拒绝`() {
        val bad = arrayOf<FloatArray?>(null, FloatArray(DemucsEstimator.OUT_WAVE_LEN))
        assertThrows(IllegalStateException::class.java) { checkedDemucsOutputs(bad) }
    }

    @Test
    fun `错长度被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            checkedDemucsOutputs(
                arrayOf(
                    FloatArray(DemucsEstimator.OUT_SPEC_LEN - 1),
                    FloatArray(DemucsEstimator.OUT_WAVE_LEN),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkedDemucsOutputs(
                arrayOf(
                    FloatArray(DemucsEstimator.OUT_SPEC_LEN),
                    FloatArray(DemucsEstimator.OUT_WAVE_LEN + 1),
                ),
            )
        }
    }
}
