package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayingSourceTest {

    @Test
    fun `单轨只接受门面或显式`() {
        assertEquals(true, resolvePlayingUpdate(false, PlayingSource.FACADE, true))
        assertEquals(false, resolvePlayingUpdate(false, PlayingSource.FACADE, false))
        assertNull(resolvePlayingUpdate(false, PlayingSource.MIX, true))
        assertEquals(true, resolvePlayingUpdate(false, PlayingSource.EXPLICIT, true))
    }

    @Test
    fun `多轨只接受 mixer 或显式`() {
        assertEquals(true, resolvePlayingUpdate(true, PlayingSource.MIX, true))
        assertEquals(false, resolvePlayingUpdate(true, PlayingSource.MIX, false))
        assertNull(resolvePlayingUpdate(true, PlayingSource.FACADE, true))
        assertEquals(false, resolvePlayingUpdate(true, PlayingSource.EXPLICIT, false))
    }
}
