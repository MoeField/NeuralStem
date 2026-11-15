package pub.miku.neuralstem.spike.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparationUiMapperTest {

    private val track = TrackKey("content://a", 0L)

    private fun snapshot(
        lifecycle: Lifecycle,
        operation: Operation? = null,
        coverage: Coverage = Coverage(startFrame = 0L),
        metrics: JobMetrics? = null,
    ) = SeparationSnapshot(
        currentTrackKey = track,
        job = JobState(
            jobId = 1L,
            trackKey = track,
            kind = SeparationKind.FULL_2,
            lifecycle = lifecycle,
            operation = operation,
            coverage = coverage,
            metrics = metrics,
        ),
    )

    @Test
    fun none_when_no_job() {
        assertNull(SeparationUiMapper.map(SeparationSnapshot()))
    }

    @Test
    fun preparing_and_decoding_are_busy() {
        val preparing = SeparationUiMapper.map(snapshot(Lifecycle.Preparing))!!
        assertEquals(SeparationUi.Phase.PREPARING, preparing.phase)
        assertTrue(preparing.busy)

        val decoding = SeparationUiMapper.map(
            snapshot(Lifecycle.Preparing, operation = Operation.Decoding(1L)),
        )!!
        assertEquals(SeparationUi.Phase.DECODING, decoding.phase)
        assertTrue(decoding.busy)
    }

    @Test
    fun running_uses_coverage_or_edge_percent() {
        val coverage = Coverage(
            totalFrames = 100L,
            startFrame = 0L,
            coveredRanges = listOf(0L until 40L),
            watermarkFrame = 40L,
        )
        assertEquals(40, SeparationUiMapper.map(snapshot(Lifecycle.Running, coverage = coverage))!!.percent)
        assertEquals(
            77,
            SeparationUiMapper.map(
                snapshot(Lifecycle.Running, coverage = coverage),
                edgePercent = 77,
            )!!.percent,
        )
    }

    @Test
    fun finished_carries_kind_and_cache() {
        val ui = SeparationUiMapper.map(
            snapshot(
                Lifecycle.Finished,
                metrics = JobMetrics(fromCache = true),
            ),
        )!!
        assertEquals(SeparationUi.Phase.DONE, ui.phase)
        assertEquals(SeparationKind.FULL_2, ui.kind)
        assertTrue(ui.fromCache)
        assertTrue(!ui.busy)
    }

    @Test
    fun failed_carries_message() {
        val ui = SeparationUiMapper.map(snapshot(Lifecycle.Failed("boom")))!!
        assertEquals(SeparationUi.Phase.FAILED, ui.phase)
        assertEquals("boom", ui.failedMessage)
        assertTrue(!ui.busy)
    }
}
