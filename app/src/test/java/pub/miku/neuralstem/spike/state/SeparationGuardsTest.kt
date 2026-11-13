package pub.miku.neuralstem.spike.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparationGuardsTest {

    private val trackA = TrackKey("content://a", 100L)
    private val trackB = TrackKey("content://b", 200L)

    @Test
    fun request_matches_current_by_uri() {
        assertTrue(
            SeparationGuards.requestMatchesCurrent(
                StartRequest(SeparationKind.EDGE, trackA),
                trackA,
            ),
        )
        assertFalse(
            SeparationGuards.requestMatchesCurrent(
                StartRequest(SeparationKind.EDGE, trackA),
                trackB,
            ),
        )
    }

    @Test
    fun request_allows_unknown_current_or_blank_uri() {
        assertTrue(
            SeparationGuards.requestMatchesCurrent(
                StartRequest(SeparationKind.EDGE, trackA),
                null,
            ),
        )
        assertTrue(
            SeparationGuards.requestMatchesCurrent(
                StartRequest(SeparationKind.EDGE, TrackKey("", 0L)),
                trackB,
            ),
        )
    }

    @Test
    fun terminal_source_must_match_active_job() {
        assertTrue(SeparationGuards.terminalSourceMatches("/a", "/a"))
        assertFalse(SeparationGuards.terminalSourceMatches("/b", "/a"))
        assertTrue(SeparationGuards.terminalSourceMatches("/a", null))
        assertTrue(SeparationGuards.terminalSourceMatches(null, "/a"))
    }

    @Test
    fun edge_source_must_match_active_job() {
        assertTrue(SeparationGuards.edgeSourceMatches("/a", "/a"))
        assertFalse(SeparationGuards.edgeSourceMatches("/b", "/a"))
        assertTrue(SeparationGuards.edgeSourceMatches("/a", null))
    }
}
