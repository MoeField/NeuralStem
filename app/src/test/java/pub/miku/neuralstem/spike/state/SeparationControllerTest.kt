package pub.miku.neuralstem.spike.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparationControllerTest {

    private val trackA = TrackKey("content://track/a", 180_000L)
    private val files = StemFiles(listOf("/cache/a/v.wav", "/cache/a/a.wav"))

    /** 让 runBlocking 的 actor 消费已入队事件。 */
    private suspend fun settle() = delay(10)

    private fun startRequest(kind: SeparationKind = SeparationKind.EDGE) =
        StartRequest(kind, trackA, startFrame = 0L, trackTitle = "A")

    @Test
    fun start_is_serialized_and_emits_effect() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }

        c.start(startRequest())
        settle()

        assertEquals(1L, c.snapshot.value.job!!.jobId)
        assertEquals(Lifecycle.Preparing, c.snapshot.value.job!!.lifecycle)
        assertEquals(1, effects.count { it is SeparationEffect.StartDecode })
        c.close()
    }

    @Test
    fun domain_events_update_snapshot_in_order() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }
        c.start(startRequest())
        settle()

        val coverage = Coverage(
            totalFrames = 1_000L,
            startFrame = 0L,
            coveredRanges = listOf(0L..99L),
            watermarkFrame = 100L,
        )
        c.send(SeparationEvent.Domain.SourceReady(1L, coverage, files))
        settle()

        assertEquals(Lifecycle.Running, c.snapshot.value.job!!.lifecycle)
        assertEquals(Operation.Separating(1L), c.snapshot.value.job!!.operation)
        assertTrue(effects.any { it is SeparationEffect.StartSeparation })

        c.send(SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = true))
        settle()
        assertTrue(effects.any { it is SeparationEffect.LoadStems })

        c.send(SeparationEvent.Domain.StemsLoaded(1L, files))
        settle()
        assertEquals(AttachmentState.Loaded(1L, trackA, files), c.snapshot.value.attachment)
        assertTrue(c.snapshot.value.separationOn)
        c.close()
    }

    @Test
    fun cancel_and_jobstopped_produce_idle() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }
        c.start(startRequest())
        settle()
        c.cancel()
        settle()
        assertEquals(Lifecycle.Stopping, c.snapshot.value.job!!.lifecycle)
        assertTrue(effects.any { it == SeparationEffect.CancelJob(1L) })

        c.send(SeparationEvent.Domain.JobStopped(1L))
        settle()
        assertEquals(null, c.snapshot.value.job)
        c.close()
    }

    @Test
    fun stale_events_after_new_job_are_ignored() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }
        c.start(startRequest())
        c.send(SeparationEvent.Domain.JobFailed(1L, "old"))
        settle()

        c.start(startRequest(SeparationKind.FULL_2))
        settle()
        val secondJob = c.snapshot.value.job!!.jobId
        assertEquals(2L, secondJob)

        c.send(SeparationEvent.Domain.JobFailed(1L, "late old"))
        settle()
        assertEquals(secondJob, c.snapshot.value.job!!.jobId)
        assertFalse(c.snapshot.value.job!!.lifecycle is Lifecycle.Failed)
        c.close()
    }

    @Test
    fun track_change_while_edge_is_serialized_with_cancel() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }
        val started = run {
            c.start(startRequest())
            settle()
            c.snapshot.value
        }
        assertEquals(1L, started.job!!.jobId)

        c.send(SeparationEvent.External.TrackChanged(TrackKey("content://track/b", 1L)))
        settle()
        assertEquals(Lifecycle.Stopping, c.snapshot.value.job!!.lifecycle)
        assertTrue(effects.any { it == SeparationEffect.CancelJob(1L) })
        c.close()
    }

    @Test
    fun many_events_are_not_lost() = runBlocking {
        val effects = mutableListOf<SeparationEffect>()
        val c = SeparationController(this) { effects += it }
        repeat(50) {
            c.snapshot.value.currentTrackKey // touch
        }
        c.start(startRequest())
        repeat(100) {
            c.send(SeparationEvent.External.ConfigChanged)
        }
        settle()
        assertEquals(Lifecycle.Preparing, c.snapshot.value.job!!.lifecycle)
        c.close()
    }

    @Test
    fun close_makes_further_send_fail_gracefully() = runBlocking {
        val c = SeparationController(this) { }
        c.close()
        // Channel 已关闭；不应抛异常。
        assertFalse(c.send(SeparationEvent.External.ConfigChanged))
    }
}
