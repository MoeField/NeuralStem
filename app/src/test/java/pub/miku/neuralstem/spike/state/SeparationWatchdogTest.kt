package pub.miku.neuralstem.spike.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparationWatchdogTest {

    @Test
    fun `只对同一 Stopping job 补发`() {
        assertTrue(shouldForceCancelWatchdog(7L, 7L, true))
        assertFalse(shouldForceCancelWatchdog(7L, 8L, true))
        assertFalse(shouldForceCancelWatchdog(7L, 7L, false))
        assertFalse(shouldForceCancelWatchdog(7L, null, true))
    }
}
