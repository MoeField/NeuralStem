package pub.miku.neuralstem.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CurrentTrackBridgeNameTest {

    @Test
    fun same_uri_maps_to_same_temp_name() {
        val a = CurrentTrackBridge.tempSourceName("content://music/who.flac", "flac")
        val b = CurrentTrackBridge.tempSourceName("content://music/who.flac", "flac")
        assertEquals(a, b)
    }

    @Test
    fun different_uri_or_extension_does_not_collide() {
        val a = CurrentTrackBridge.tempSourceName("content://music/who.flac", "flac")
        val b = CurrentTrackBridge.tempSourceName("content://music/bullet.flac", "flac")
        val c = CurrentTrackBridge.tempSourceName("content://music/who.flac", "mp3")
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun temp_name_keeps_expected_prefix_and_extension() {
        val n = CurrentTrackBridge.tempSourceName("content://music/x", "wav")
        assertTrue(n.startsWith("separate_input_"))
        assertTrue(n.endsWith(".wav"))
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
