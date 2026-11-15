package pub.miku.neuralstem.spike.state

import org.junit.Assert.assertEquals
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
    fun job_track_must_match_current() {
        assertTrue(SeparationGuards.jobMatchesCurrent(trackA, trackA))
        assertFalse(SeparationGuards.jobMatchesCurrent(trackA, trackB))
        assertTrue(SeparationGuards.jobMatchesCurrent(null, trackA))
        assertTrue(SeparationGuards.jobMatchesCurrent(trackB, TrackKey("", 0L)))
    }

    @Test
    fun exportable_files_only_for_finished_current_job() {
        val files = StemFiles(listOf("/cache/vocals.wav", "/cache/accompaniment.wav"))
        fun snapshot(
            track: TrackKey,
            lifecycle: Lifecycle,
            stemFiles: StemFiles?,
            current: TrackKey? = track,
        ) = SeparationSnapshot(
            currentTrackKey = current,
            job = JobState(
                jobId = 1L,
                trackKey = track,
                kind = SeparationKind.EDGE,
                lifecycle = lifecycle,
                files = stemFiles,
            ),
        )

        assertEquals(
            files.paths,
            SeparationGuards.exportableFiles(
                snapshot(trackA, Lifecycle.Finished, files),
                trackA.uri,
            ),
        )
        assertEquals(
            null,
            SeparationGuards.exportableFiles(
                snapshot(trackA, Lifecycle.Finished, files),
                trackB.uri,
            ),
        )
        assertEquals(
            null,
            SeparationGuards.exportableFiles(
                snapshot(trackA, Lifecycle.Finished, files, current = trackB),
                trackA.uri,
            ),
        )
        assertEquals(
            null,
            SeparationGuards.exportableFiles(
                snapshot(trackA, Lifecycle.Running, files),
                trackA.uri,
            ),
        )
        assertEquals(
            null,
            SeparationGuards.exportableFiles(
                snapshot(trackA, Lifecycle.Finished, null),
                trackA.uri,
            ),
        )
    }

    @Test
    fun edge_source_must_match_active_job() {
        assertTrue(SeparationGuards.edgeSourceMatches("/a", "/a"))
        assertFalse(SeparationGuards.edgeSourceMatches("/b", "/a"))
        assertTrue(SeparationGuards.edgeSourceMatches("/a", null))
    }
}
