package pub.miku.neuralstem.spike.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1：分离领域 reducer 的迁移矩阵与交错行为。
 *
 * 这些测试不启动 Android/协程/Service，只验证：
 * - 状态迁移是否确定
 * - 旧 job 事件是否被丢弃
 * - EDGE/FULL 的 auto attach 策略
 * - 换曲/播放结束/服务丢失/超时等外部事件
 */
class SeparationReducerTest {

    private val trackA = TrackKey("content://track/a", 180_000L)
    private val trackB = TrackKey("content://track/b", 200_000L)
    private val vocalsAndAccomp = StemFiles(listOf("/cache/a/vocals.wav", "/cache/a/accompaniment.wav"))
    private val fourStems = StemFiles(listOf("/cache/a/v.wav", "/cache/a/d.wav", "/cache/a/b.wav", "/cache/a/o.wav"))

    private fun reduce(s: SeparationSnapshot, e: SeparationEvent): ReduceResult =
        SeparationReducer.reduce(s, e)

    private fun start(
        s: SeparationSnapshot = SeparationSnapshot(),
        kind: SeparationKind = SeparationKind.EDGE,
        track: TrackKey = trackA,
        startFrame: Long = 0L,
    ): ReduceResult = reduce(
        s,
        SeparationEvent.User.Start(
            StartRequest(kind = kind, trackKey = track, startFrame = startFrame, trackTitle = "A"),
        ),
    )

    private fun runningEdge(
        startFrame: Long = 100L,
        coverage: Coverage = Coverage(
            totalFrames = 1_000L,
            startFrame = startFrame,
            coveredRanges = listOf(startFrame until startFrame + 100L),
            watermarkFrame = startFrame + 100L,
            complete = false,
        ),
    ): SeparationSnapshot {
        val started = start(kind = SeparationKind.EDGE, startFrame = startFrame).snapshot
        val ready = reduce(
            started,
            SeparationEvent.Domain.SourceReady(started.job!!.jobId, coverage, vocalsAndAccomp),
        ).snapshot
        assertEquals(Lifecycle.Running, ready.job!!.lifecycle)
        return ready
    }

    private fun runningFull(
        kind: SeparationKind = SeparationKind.FULL_2,
        track: TrackKey = trackA,
    ): SeparationSnapshot {
        val started = start(kind = kind, track = track).snapshot
        return reduce(
            started,
            SeparationEvent.Domain.SourceReady(
                started.job!!.jobId,
                Coverage(totalFrames = 1_000L, startFrame = 0L),
                vocalsAndAccomp,
            ),
        ).snapshot
    }

    // ---------------- 启动/准备 ----------------

    @Test
    fun start_allocates_job_and_emits_decode() {
        val r = start()
        assertEquals(Lifecycle.Preparing, r.snapshot.job!!.lifecycle)
        assertEquals(Operation.Decoding(1L), r.snapshot.job!!.operation)
        assertEquals(2L, r.snapshot.nextJobId)
        assertEquals(listOf(SeparationEffect.StartDecode(1L, StartRequest(
            SeparationKind.EDGE, trackA, startFrame = 0L, trackTitle = "A",
        ))), r.effects)
    }

    @Test
    fun sourceReady_moves_to_running_and_starts_separation() {
        val started = start().snapshot
        val coverage = Coverage(
            totalFrames = 1_000L,
            startFrame = 0L,
            coveredRanges = listOf(0L..99L),
            watermarkFrame = 100L,
        )
        val r = reduce(
            started,
            SeparationEvent.Domain.SourceReady(1L, coverage, vocalsAndAccomp),
        )
        assertEquals(Lifecycle.Running, r.snapshot.job!!.lifecycle)
        assertEquals(Operation.Separating(1L), r.snapshot.job!!.operation)
        assertEquals(listOf(SeparationEffect.StartSeparation(1L)), r.effects)
    }

    @Test
    fun stale_sourceReady_is_ignored() {
        val started = start().snapshot
        val r = reduce(
            started,
            SeparationEvent.Domain.SourceReady(99L, Coverage(totalFrames = 1_000L)),
        )
        assertEquals(started, r.snapshot)
        assertTrue(r.effects.isEmpty())
    }

    // ---------------- EDGE 自动 attach ----------------

    @Test
    fun edge_coverage_with_gate_ready_auto_loads_once() {
        val s = runningEdge()
        val coverage = s.job!!.coverage.copy(
            watermarkFrame = 900L,
            coveredRanges = listOf(100L..899L),
        )
        val first = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = true),
        )
        val loads = first.effects.filterIsInstance<SeparationEffect.LoadStems>()
        assertEquals(1, loads.size)
        assertEquals(1L, first.snapshot.autoAttachAttemptedFor)
        assertEquals(Operation.LoadingStems(1L), first.snapshot.job!!.operation)

        // 第二次 coverage 不再重复 LoadStems。
        val second = reduce(
            first.snapshot,
            SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = true),
        )
        assertTrue(second.effects.none { it is SeparationEffect.LoadStems })
    }

    @Test
    fun edge_does_not_auto_load_when_gate_not_ready() {
        val s = runningEdge()
        val coverage = s.job!!.coverage.copy()
        val r = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = false),
        )
        assertTrue(r.effects.none { it is SeparationEffect.LoadStems })
        assertNull(r.snapshot.autoAttachAttemptedFor)
    }

    @Test
    fun edge_does_not_auto_load_when_track_changed() {
        val s = runningEdge()
        val other = reduce(s, SeparationEvent.External.TrackChanged(trackB)).snapshot
        val coverage = Coverage(
            totalFrames = 1_000L,
            startFrame = 100L,
            coveredRanges = listOf(100L..999L),
            watermarkFrame = 1_000L,
        )
        val r = reduce(
            other,
            SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = true),
        )
        assertTrue(r.effects.none { it is SeparationEffect.LoadStems })
    }

    @Test
    fun user_detach_suppresses_auto_attach_for_that_job() {
        val s = runningEdge()
        val attached = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        )
        val loadedR = reduce(
            attached.snapshot,
            SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp),
        )
        assertTrue(loadedR.snapshot.separationOn)

        val detached = reduce(loadedR.snapshot, SeparationEvent.User.Detach)
        assertEquals(AttachmentState.None, detached.snapshot.attachment)
        assertEquals(1L, detached.snapshot.autoAttachSuppressedFor)

        val coverage = loadedR.snapshot.job!!.coverage.copy(
            watermarkFrame = 1_000L,
            coveredRanges = listOf(100L..999L),
        )
        val again = reduce(
            detached.snapshot,
            SeparationEvent.Domain.CoverageUpdated(1L, coverage, gateReady = true),
        )
        assertTrue(again.effects.none { it is SeparationEffect.LoadStems })
    }

    @Test
    fun manual_attach_clears_suppression() {
        val s = runningEdge()
        // 先抑制自动 attach，再完成，保证 job 处于 finished 且没有 loading 操作。
        val suppressed = s.copy(autoAttachSuppressedFor = 1L)
        val finished = reduce(
            suppressed,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.Attach())
        assertTrue(r.effects.any { it is SeparationEffect.LoadStems })
        assertNull(r.snapshot.autoAttachSuppressedFor)
    }

    @Test
    fun stemsLoaded_sets_attachment_and_switch_state() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val r = reduce(loading, SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp))
        assertTrue(r.snapshot.separationOn)
        assertEquals(AttachmentState.Loaded(1L, trackA, vocalsAndAccomp), r.snapshot.attachment)
        assertNull(r.snapshot.job!!.operation)
    }

    // ---------------- FULL 不自动 attach ----------------

    @Test
    fun full_finish_notifies_but_does_not_auto_attach() {
        val s = runningFull()
        val r = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        )
        assertTrue(r.effects.none { it is SeparationEffect.LoadStems })
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
        assertEquals(Lifecycle.Finished, r.snapshot.job!!.lifecycle)
        assertFalse(r.snapshot.separationOn)
    }

    @Test
    fun full_manual_attach_loads_finished_stems() {
        val s = runningFull()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.Attach())
        assertTrue(r.effects.any { it is SeparationEffect.LoadStems })
        assertEquals(Operation.LoadingStems(1L), r.snapshot.job!!.operation)
    }

    // ---------------- 取消/让位 ----------------

    @Test
    fun cancel_active_moves_to_stopping_and_keeps_attachment() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.User.Cancel())
        assertEquals(Lifecycle.Stopping, r.snapshot.job!!.lifecycle)
        assertEquals(Operation.Cancelling(1L), r.snapshot.job!!.operation)
        assertTrue(r.effects.any { it == SeparationEffect.CancelJob(1L) })
        assertTrue(r.snapshot.separationOn.not())
    }

    @Test
    fun start_while_stopping_queues_latest_start() {
        val s = runningEdge()
        val stopping = reduce(s, SeparationEvent.User.Cancel()).snapshot
        val r = reduce(
            stopping,
            SeparationEvent.User.Start(StartRequest(SeparationKind.FULL_2, trackA)),
        )
        val op = r.snapshot.job!!.operation as Operation.Cancelling
        assertEquals(SeparationKind.FULL_2, op.pendingStart!!.kind)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun jobStopped_with_pending_starts_new_job_with_new_id() {
        val s = runningEdge()
        val stopping = reduce(s, SeparationEvent.User.Cancel()).snapshot
        val pending = reduce(
            stopping,
            SeparationEvent.User.Start(StartRequest(SeparationKind.FULL_2, trackA)),
        ).snapshot
        val r = reduce(pending, SeparationEvent.Domain.JobStopped(1L))
        assertEquals(2L, r.snapshot.job!!.jobId)
        assertEquals(Lifecycle.Preparing, r.snapshot.job!!.lifecycle)
        assertEquals(3L, r.snapshot.nextJobId)
        assertTrue(r.effects.any { it is SeparationEffect.StartDecode })
    }

    @Test
    fun cancel_during_stopping_clears_pending() {
        val s = runningEdge()
        val stopping = reduce(s, SeparationEvent.User.Cancel()).snapshot
        val pending = reduce(
            stopping,
            SeparationEvent.User.Start(StartRequest(SeparationKind.FULL_2, trackA)),
        ).snapshot
        val r = reduce(pending, SeparationEvent.User.Cancel())
        val op = r.snapshot.job!!.operation as Operation.Cancelling
        assertNull(op.pendingStart)
    }

    @Test
    fun jobStopped_without_pending_clears_job() {
        val s = runningEdge()
        val stopping = reduce(s, SeparationEvent.User.Cancel()).snapshot
        val r = reduce(stopping, SeparationEvent.Domain.JobStopped(1L))
        assertNull(r.snapshot.job)
        assertTrue(r.effects.any { it == SeparationEffect.StopForeground(1L) })
    }

    @Test
    fun stale_jobStopped_is_ignored() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.Domain.JobStopped(99L))
        assertEquals(s, r.snapshot)
    }

    // ---------------- 换曲/播放结束 ----------------

    @Test
    fun trackChanged_on_edge_cancels_and_detaches() {
        val s = runningEdge()
        val loaded = reduce(
            s,
            SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp),
        ).snapshot
        val r = reduce(loaded, SeparationEvent.External.TrackChanged(trackB))
        assertEquals(Lifecycle.Stopping, r.snapshot.job!!.lifecycle)
        assertEquals(AttachmentState.None, r.snapshot.attachment)
        assertTrue(r.effects.any { it == SeparationEffect.CancelJob(1L) })
        assertTrue(r.effects.any { it is SeparationEffect.DetachStems })
    }

    @Test
    fun full_trackChanged_keeps_running_and_only_detaches() {
        val s = runningFull()
        val r = reduce(s, SeparationEvent.External.TrackChanged(trackB))
        assertEquals(Lifecycle.Running, r.snapshot.job!!.lifecycle)
        assertTrue(r.effects.none { it is SeparationEffect.CancelJob })
    }

    @Test
    fun trackChanged_same_key_is_noop() {
        val s = runningFull()
        val r = reduce(s, SeparationEvent.External.TrackChanged(trackA))
        assertEquals(s, r.snapshot)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun playbackEnded_on_edge_cancels_and_detaches() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.External.PlaybackEnded)
        assertEquals(Lifecycle.Stopping, r.snapshot.job!!.lifecycle)
        assertEquals(AttachmentState.None, r.snapshot.attachment)
        assertTrue(r.effects.any { it == SeparationEffect.CancelJob(1L) })
    }

    @Test
    fun playbackEnded_on_full_keeps_job() {
        val s = runningFull()
        val r = reduce(s, SeparationEvent.External.PlaybackEnded)
        assertEquals(Lifecycle.Running, r.snapshot.job!!.lifecycle)
        assertTrue(r.effects.none { it is SeparationEffect.CancelJob })
    }

    // ---------------- 加载竞态 ----------------

    @Test
    fun stemsLoaded_after_track_change_is_ignored() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val moved = reduce(loading, SeparationEvent.External.TrackChanged(trackB)).snapshot
        val r = reduce(moved, SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp))
        assertTrue(r.snapshot.attachment !is AttachmentState.Loaded)
    }

    @Test
    fun detach_during_loading_suppresses_result() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val off = reduce(loading, SeparationEvent.User.Detach).snapshot
        val r = reduce(off, SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp))
        assertTrue(r.snapshot.attachment !is AttachmentState.Loaded)
    }

    @Test
    fun stemsLoadFailed_clears_loading_and_notifies() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val r = reduce(
            loading,
            SeparationEvent.Domain.StemsLoadFailed(1L, "boom"),
        )
        assertNull(r.snapshot.job!!.operation)
        assertTrue(r.snapshot.attachment !is AttachmentState.Loaded)
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
    }

    @Test
    fun attach_while_active_is_rejected() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.User.Attach())
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
        assertNull(r.snapshot.job!!.operation as? Operation.LoadingStems)
    }

    @Test
    fun attach_without_result_is_rejected() {
        val r = reduce(SeparationSnapshot(), SeparationEvent.User.Attach())
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
    }

    // ---------------- 终止/失败/超时 ----------------

    @Test
    fun jobFinished_is_terminal_fence_for_coverage() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val late = reduce(
            finished,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage,
                gateReady = true,
            ),
        )
        assertEquals(finished, late.snapshot)
    }

    @Test
    fun jobFailed_is_terminal_and_stops_foreground() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.Domain.JobFailed(1L, "boom"))
        assertTrue(r.snapshot.job!!.lifecycle is Lifecycle.Failed)
        assertTrue(r.effects.any { it is SeparationEffect.StopForeground })
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
    }

    @Test
    fun decoding_timeout_fails_job() {
        val s = start().snapshot
        val r = reduce(
            s,
            SeparationEvent.Timeout.OperationTimeout(1L, OperationKind.DECODING),
        )
        assertTrue(r.snapshot.job!!.lifecycle is Lifecycle.Failed)
        assertTrue(r.effects.any { it is SeparationEffect.StopForeground })
    }

    @Test
    fun loading_timeout_clears_loading() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val r = reduce(
            loading,
            SeparationEvent.Timeout.OperationTimeout(1L, OperationKind.LOADING_STEMS),
        )
        assertNull(r.snapshot.job!!.operation)
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
    }

    @Test
    fun cancelling_timeout_marks_worker_alive_and_keeps_stopping() {
        val s = runningEdge()
        val stopping = reduce(s, SeparationEvent.User.Cancel()).snapshot
        val r = reduce(
            stopping,
            SeparationEvent.Timeout.OperationTimeout(1L, OperationKind.CANCELLING),
        )
        assertEquals(Lifecycle.Stopping, r.snapshot.job!!.lifecycle)
        assertTrue((r.snapshot.job!!.operation as Operation.Cancelling).workerAlive)
    }

    // ---------------- 缓存/导出/服务 ----------------

    @Test
    fun clearCache_rejected_when_active_or_attached() {
        val active = reduce(runningEdge(), SeparationEvent.User.ClearCache)
        assertTrue(active.effects.none { it == SeparationEffect.ClearCache })

        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val loaded = reduce(
            reduce(finished, SeparationEvent.User.Attach()).snapshot,
            SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp),
        ).snapshot
        val r = reduce(loaded, SeparationEvent.User.ClearCache)
        assertTrue(r.effects.none { it == SeparationEffect.ClearCache })
    }

    @Test
    fun clearCache_allowed_when_idle_finished() {
        val s = runningFull()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                fourStems,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.ClearCache)
        assertTrue(r.effects.any { it == SeparationEffect.ClearCache })
        assertNull(r.snapshot.lastFinished)
    }

    @Test
    fun export_requires_finished_complete_current_track() {
        val partial = reduce(runningFull(), SeparationEvent.User.Export)
        assertTrue(partial.effects.none { it is SeparationEffect.Export })

        val s = runningFull()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                fourStems,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.Export)
        assertTrue(r.effects.any { it is SeparationEffect.Export })
    }

    @Test
    fun playbackServiceLost_detaches_and_cancels_edge() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.External.ServiceLost(ServiceKind.PLAYBACK))
        assertEquals(AttachmentState.None, r.snapshot.attachment)
        assertEquals(Lifecycle.Stopping, r.snapshot.job!!.lifecycle)
        assertTrue(r.effects.any { it == SeparationEffect.CancelJob(1L) })
    }

    @Test
    fun separationServiceLost_fails_job() {
        val s = runningFull()
        val r = reduce(s, SeparationEvent.External.ServiceLost(ServiceKind.SEPARATION))
        assertTrue(r.snapshot.job!!.lifecycle is Lifecycle.Failed)
    }

    @Test
    fun processRestored_clears_active_job_and_attachment() {
        val s = runningEdge()
        val r = reduce(s, SeparationEvent.External.ProcessRestored)
        assertNull(r.snapshot.job)
        assertEquals(AttachmentState.None, r.snapshot.attachment)
        assertNull(r.snapshot.currentTrackKey)
    }

    // ---------------- Backfill/attachment 身份 ----------------

    @Test
    fun backfill_starts_new_job_while_old_attachment_keeps_playing() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                Coverage(
                    totalFrames = 1_000L,
                    startFrame = 100L,
                    coveredRanges = listOf(100L..999L),
                    watermarkFrame = 1_000L,
                    complete = false,
                ),
                complete = false,
            ),
        ).snapshot
        val loaded = reduce(
            reduce(finished, SeparationEvent.User.Attach()).snapshot,
            SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp),
        ).snapshot
        assertTrue(loaded.separationOn)

        val backfilled = reduce(loaded, SeparationEvent.User.BackfillNow)
        assertEquals(2L, backfilled.snapshot.job!!.jobId)
        assertEquals(AttachmentState.Loaded(1L, trackA, vocalsAndAccomp), backfilled.snapshot.attachment)
        assertTrue(backfilled.snapshot.separationOn)
        assertTrue(backfilled.effects.any { it is SeparationEffect.StartDecode })
    }

    // ---------------- fast path / 操作保护 ----------------

    @Test
    fun fastCacheHit_can_finish_directly_from_preparing_and_auto_loads_edge() {
        val started = start(kind = SeparationKind.EDGE).snapshot
        val r = reduce(
            started,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        )
        assertEquals(Lifecycle.Finished, r.snapshot.job!!.lifecycle)
        assertEquals(1L, r.snapshot.autoAttachAttemptedFor)
        assertTrue(r.effects.any { it is SeparationEffect.LoadStems })
    }

    @Test
    fun attach_while_auto_loading_is_noop() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        assertEquals(Operation.LoadingStems(1L), finished.job!!.operation)
        val r = reduce(finished, SeparationEvent.User.Attach())
        assertEquals(finished, r.snapshot)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun backfill_while_auto_loading_is_rejected() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.BackfillNow)
        assertTrue(r.effects.any { it is SeparationEffect.Notify })
        assertTrue(r.effects.none { it is SeparationEffect.StartDecode })
    }

    @Test
    fun clearCache_while_auto_loading_is_rejected() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(finished, SeparationEvent.User.ClearCache)
        assertTrue(r.effects.none { it == SeparationEffect.ClearCache })
    }

    @Test
    fun stemsLoaded_after_cancel_started_is_ignored() {
        val s = runningEdge()
        val loading = reduce(
            s,
            SeparationEvent.Domain.CoverageUpdated(
                1L,
                s.job!!.coverage.copy(
                    watermarkFrame = 1_000L,
                    coveredRanges = listOf(100L..999L),
                ),
                gateReady = true,
            ),
        ).snapshot
        val stopping = reduce(loading, SeparationEvent.User.Cancel()).snapshot
        val r = reduce(stopping, SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp))
        assertTrue(r.snapshot.attachment !is AttachmentState.Loaded)
    }

    @Test
    fun stemsLoaded_after_failure_is_ignored() {
        val failed = reduce(
            runningEdge(),
            SeparationEvent.Domain.JobFailed(1L, "boom"),
        ).snapshot
        val r = reduce(failed, SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp))
        assertTrue(r.snapshot.attachment !is AttachmentState.Loaded)
        assertTrue(r.snapshot.job!!.lifecycle is Lifecycle.Failed)
    }

    @Test
    fun separating_timeout_preserves_backfilling_operation() {
        val s = runningEdge()
        val backfilling = s.copy(
            job = s.job!!.copy(operation = Operation.Backfilling(1L)),
        )
        val r = reduce(
            backfilling,
            SeparationEvent.Timeout.OperationTimeout(1L, OperationKind.BACKFILLING),
        )
        assertTrue(r.snapshot.job!!.operation is Operation.Backfilling)
    }

    @Test
    fun stale_separating_timeout_does_not_overwrite_loading() {
        val s = runningEdge()
        val finished = reduce(
            s,
            SeparationEvent.Domain.JobFinished(
                1L,
                vocalsAndAccomp,
                s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        ).snapshot
        val r = reduce(
            finished,
            SeparationEvent.Timeout.OperationTimeout(1L, OperationKind.SEPARATING),
        )
        assertEquals(finished, r.snapshot)
        assertTrue(r.effects.isEmpty())
    }

    @Test
    fun full_finishing_after_track_change_does_not_attach() {
        val s = runningFull()
        val moved = reduce(s, SeparationEvent.External.TrackChanged(trackB)).snapshot
        val r = reduce(
            moved,
            SeparationEvent.Domain.JobFinished(
                1L,
                fourStems,
                Coverage(totalFrames = 1_000L, complete = true, watermarkFrame = 1_000L),
                complete = true,
            ),
        )
        assertEquals(Lifecycle.Finished, r.snapshot.job!!.lifecycle)
        assertTrue(r.effects.none { it is SeparationEffect.LoadStems })
        assertTrue(r.effects.none { it is SeparationEffect.Notify })
    }

    @Test
    fun playbackEnded_detaches_loaded_edge_stems() {
        val s = runningEdge()
        val loaded = reduce(
            reduce(
                s,
                SeparationEvent.Domain.JobFinished(
                    1L,
                    vocalsAndAccomp,
                    s.job!!.coverage.copy(complete = true, watermarkFrame = 1_000L),
                    complete = true,
                ),
            ).snapshot,
            SeparationEvent.Domain.StemsLoaded(1L, vocalsAndAccomp),
        ).snapshot
        assertTrue(loaded.separationOn)
        val r = reduce(loaded, SeparationEvent.External.PlaybackEnded)
        assertEquals(AttachmentState.None, r.snapshot.attachment)
        assertTrue(r.effects.any { it is SeparationEffect.DetachStems })
    }

    @Test
    fun attachment_track_mismatch_is_not_separation_on() {
        val loaded = AttachmentState.Loaded(1L, trackA, vocalsAndAccomp)
        val s = SeparationSnapshot(currentTrackKey = trackB, attachment = loaded)
        assertFalse(s.separationOn)
    }
}
