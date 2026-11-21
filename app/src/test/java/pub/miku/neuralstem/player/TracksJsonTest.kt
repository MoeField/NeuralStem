package pub.miku.neuralstem.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TracksJsonTest {

    @Test
    fun `保留 track uri`() {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("id", "vocals"); put("label", "人声"); put("uri", "file:///tmp/vocals.wav")
            put("volume", 0.5); put("muted", true); put("solo", false)
        })
        val r = parseTracksJson(arr.toString())
        assertEquals(1, r.size)
        assertEquals("file:///tmp/vocals.wav", r[0].uri)
        assertEquals(0.5f, r[0].volume, 0.0001f)
        assertEquals(true, r[0].muted)
    }

    @Test
    fun `空快照不崩`() {
        assertEquals(emptyList<PlayerViewModel.TrackUiState>(), parseTracksJson(null))
        assertEquals(emptyList<PlayerViewModel.TrackUiState>(), parseTracksJson(""))
        assertEquals(emptyList<PlayerViewModel.TrackUiState>(), parseTracksJson("not-json"))
    }

    @Test
    fun `没有 uri 字段时为 null`() {
        val arr = JSONArray().put(JSONObject().put("id", "x").put("label", "x"))
        assertNull(parseTracksJson(arr.toString()).single().uri)
    }
}
