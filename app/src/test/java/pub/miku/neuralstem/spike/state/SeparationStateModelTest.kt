package pub.miku.neuralstem.spike.state

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定性方案第 5 节：分离状态机随机序列不变量测试。
 *
 * 不枚举所有事件组合，而是用固定种子的随机序列反复压 reducer，断言
 * “任何一个可达状态都必须满足的硬约束”。失败时 seed 可复现。
 */
class SeparationStateModelTest {

    private val trackA = TrackKey("content://track/a", 180_000L)
    private val trackB = TrackKey("content://track/b", 200_000L)
    private val files = StemFiles(listOf("/cache/a/vocals.wav", "/cache/a/accompaniment.wav"))

    private fun coverage(total: Long, start: Long, end: Long, complete: Boolean = false) =
        Coverage(
            totalFrames = total,
            startFrame = start,
            coveredRanges = if (end > start) listOf(start until end) else emptyList(),
            watermarkFrame = end,
            complete = complete,
        )

    private fun event(r: Random, s: SeparationSnapshot): SeparationEvent {
        val jobId = s.job?.jobId
        return when (r.nextInt(16)) {
            0 -> SeparationEvent.User.Start(
                StartRequest(
                    kind = if (r.nextBoolean()) SeparationKind.EDGE else SeparationKind.FULL_4,
                    trackKey = if (r.nextBoolean()) trackA else trackB,
                    startFrame = 0L,
                    trackTitle = "track",
                ),
            )
            1 -> SeparationEvent.User.Cancel(if (r.nextBoolean()) null else jobId)
            2 -> SeparationEvent.User.Attach(if (r.nextBoolean()) null else jobId)
            3 -> SeparationEvent.User.Detach
            4 -> SeparationEvent.User.BackfillNow
            5 -> SeparationEvent.User.Export
            6 -> SeparationEvent.User.ClearCache
            7 -> SeparationEvent.Domain.SourceReady(
                jobId ?: -1L,
                coverage(1_000L, 0L, 100L),
                files,
            )
            8 -> SeparationEvent.Domain.CoverageUpdated(
                jobId ?: -1L,
                coverage(1_000L, 0L, 500L),
                gateReady = r.nextBoolean(),
            )
            9 -> SeparationEvent.Domain.JobFinished(
                jobId ?: -1L,
                files,
                coverage(1_000L, 0L, 1_000L, complete = true),
                complete = true,
            )
            10 -> SeparationEvent.Domain.JobFailed(jobId ?: -1L, "synthetic")
            11 -> SeparationEvent.Domain.JobStopped(jobId ?: -1L)
            12 -> SeparationEvent.Domain.StemsLoaded(jobId ?: -1L, files)
            13 -> SeparationEvent.External.TrackChanged(if (r.nextBoolean()) trackA else trackB)
            14 -> SeparationEvent.External.ServiceLost(ServiceKind.PLAYBACK)
            else -> SeparationEvent.External.PlaybackEnded
        }
    }

    private fun assertInvariants(seed: Int, step: Int, s: SeparationSnapshot) {
        val job = s.job
        assertTrue("seed=$seed step=$step nextJobId must be >= 1", s.nextJobId >= 1L)
        if (job != null) {
            assertTrue(
                "seed=$seed step=$step jobId=${job.jobId} must be < nextJobId=${s.nextJobId}",
                job.jobId < s.nextJobId,
            )
            assertTrue(
                "seed=$seed step=$step negative total",
                job.coverage.totalFrames >= 0L,
            )
            if (job.lifecycle is Lifecycle.Stopping) {
                assertTrue(
                    "seed=$seed step=$step Stopping must have Cancelling operation",
                    job.operation is Operation.Cancelling,
                )
            }
        }
        val att = s.attachment
        if (att is AttachmentState.Loaded) {
            assertTrue(
                "seed=$seed step=$step attachment jobId=${att.jobId} must be < nextJobId",
                att.jobId < s.nextJobId,
            )
        }
    }

    @Test
    fun random_events_preserve_hard_invariants() {
        repeat(2_000) { seed ->
            val r = Random(seed)
            var s = SeparationSnapshot()
            var lastNext = s.nextJobId
            val trace = mutableListOf<String>()
            repeat(40) { step ->
                val e = event(r, s)
                trace += e.toString()
                val result = SeparationReducer.reduce(s, e)
                s = result.snapshot
                assertTrue("seed=$seed step=$step nextJobId decreased", s.nextJobId >= lastNext)
                lastNext = s.nextJobId
                try {
                    assertInvariants(seed, step, s)
                } catch (t: AssertionError) {
                    throw AssertionError(t.message + "\ntrace=\n" + trace.joinToString("\n"), t)
                }
            }
        }
    }
}
