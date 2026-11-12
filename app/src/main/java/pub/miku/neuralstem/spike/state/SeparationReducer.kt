package pub.miku.neuralstem.spike.state

/**
 * 分离领域 reducer（M1）。
 *
 * 纯函数：输入 Snapshot + Event，输出 Snapshot + Effects。不碰 Android、不启动协程。
 * 单写者/actor 在 M2 接入；本文件先用单测把迁移和交错行为钉死。
 */
object SeparationReducer {

    private const val FINISHED_AVAILABLE = "分离完成，可打开分离开关"

    fun reduce(snapshot: SeparationSnapshot, event: SeparationEvent): ReduceResult =
        when (event) {
            is SeparationEvent.User.Start -> onStart(snapshot, event.request)
            is SeparationEvent.User.Cancel -> onCancel(snapshot, event.jobId)
            is SeparationEvent.User.Attach -> onAttach(snapshot, event.jobId)
            SeparationEvent.User.Detach -> onDetach(snapshot)
            SeparationEvent.User.BackfillNow -> onBackfill(snapshot)
            SeparationEvent.User.Export -> onExport(snapshot)
            SeparationEvent.User.ClearCache -> onClearCache(snapshot)
            is SeparationEvent.Domain.SourceReady -> onSourceReady(snapshot, event)
            is SeparationEvent.Domain.CoverageUpdated -> onCoverageUpdated(snapshot, event)
            is SeparationEvent.Domain.JobFinished -> onJobFinished(snapshot, event)
            is SeparationEvent.Domain.JobFailed -> onJobFailed(snapshot, event)
            is SeparationEvent.Domain.JobStopped -> onJobStopped(snapshot, event)
            is SeparationEvent.Domain.StemsLoaded -> onStemsLoaded(snapshot, event)
            is SeparationEvent.Domain.StemsLoadFailed -> onStemsLoadFailed(snapshot, event)
            is SeparationEvent.External.TrackChanged -> onTrackChanged(snapshot, event.trackKey)
            SeparationEvent.External.PlaybackEnded -> onPlaybackEnded(snapshot)
            is SeparationEvent.External.ServiceLost -> onServiceLost(snapshot, event.service)
            SeparationEvent.External.ProcessRestored -> onProcessRestored(snapshot)
            SeparationEvent.External.ConfigChanged -> ReduceResult(snapshot)
            is SeparationEvent.Timeout.OperationTimeout -> onTimeout(snapshot, event)
        }

    // ==================== User intents ====================

    private fun onStart(s: SeparationSnapshot, request: StartRequest): ReduceResult {
        val cur = s.job
        if (cur == null || cur.isTerminal) {
            val id = s.nextJobId
            val job = newJob(id, request)
            var next = s.copy(
                job = job,
                currentTrackKey = s.currentTrackKey ?: request.trackKey,
                nextJobId = id + 1,
                lastError = null,
                autoAttachSuppressedFor = null,
                autoAttachAttemptedFor = null,
            )
            val effects = mutableListOf<SeparationEffect>(SeparationEffect.StartDecode(id, request))
            // 新任务针对另一首时，旧 attachment 必须立即解除；不能等 TrackChanged。
            next = detachIfOtherTrack(next, request.trackKey, effects)
            return ReduceResult(next, effects)
        }

        if (cur.lifecycle is Lifecycle.Stopping) {
            val op = cur.operation as? Operation.Cancelling ?: Operation.Cancelling(cur.jobId)
            return ReduceResult(
                s.copy(job = cur.copy(operation = op.copy(pendingStart = request))),
            )
        }

        if (cur.lifecycle is Lifecycle.Preparing || cur.lifecycle is Lifecycle.Running) {
            val stopping = cur.copy(
                lifecycle = Lifecycle.Stopping,
                operation = Operation.Cancelling(cur.jobId, pendingStart = request),
            )
            val effects = mutableListOf<SeparationEffect>(SeparationEffect.CancelJob(cur.jobId))
            var next = s.copy(job = stopping, lastError = null)
            next = detachIfOtherTrack(next, request.trackKey, effects)
            return ReduceResult(next, effects)
        }

        return ReduceResult(s)
    }

    private fun onCancel(s: SeparationSnapshot, jobId: Long?): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (jobId != null && jobId != job.jobId) return ReduceResult(s)
        return when (job.lifecycle) {
            is Lifecycle.Finished, is Lifecycle.Failed -> ReduceResult(s)
            is Lifecycle.Stopping -> {
                val op = (job.operation as? Operation.Cancelling)
                    ?: Operation.Cancelling(job.jobId)
                ReduceResult(
                    s.copy(job = job.copy(operation = op.copy(pendingStart = null))),
                )
            }

            is Lifecycle.Preparing, is Lifecycle.Running -> {
                val stopping = job.copy(
                    lifecycle = Lifecycle.Stopping,
                    operation = Operation.Cancelling(job.jobId),
                )
                ReduceResult(
                    s.copy(job = stopping),
                    listOf(SeparationEffect.CancelJob(job.jobId)),
                )
            }
        }
    }

    private fun onAttach(s: SeparationSnapshot, jobId: Long?): ReduceResult {
        val job = s.job
        if (job == null) return notify(s, SeparationMessages.NO_RESULT)
        if (jobId != null && jobId != job.jobId) return notify(s, SeparationMessages.NO_RESULT)
        if (!job.isTerminal) return notify(s, SeparationMessages.BUSY)
        if (job.lifecycle is Lifecycle.Failed) return notify(s, SeparationMessages.NO_RESULT)
        if (job.operation is Operation.LoadingStems) return ReduceResult(s)
        if (s.currentTrackKey == null || s.currentTrackKey != job.trackKey) {
            return notify(s, SeparationMessages.RESULT_OTHER_TRACK)
        }
        val files = job.files ?: return notify(s, SeparationMessages.NO_RESULT)
        if (s.attachment is AttachmentState.Loaded &&
            (s.attachment as AttachmentState.Loaded).jobId == job.jobId
        ) {
            return ReduceResult(s)
        }
        val loading = job.copy(operation = Operation.LoadingStems(job.jobId))
        return ReduceResult(
            s.copy(
                job = loading,
                autoAttachSuppressedFor = null,
                autoAttachAttemptedFor = job.jobId,
                lastError = null,
            ),
            listOf(SeparationEffect.LoadStems(job.jobId, files)),
        )
    }

    private fun onDetach(s: SeparationSnapshot): ReduceResult {
        val loaded = s.attachment as? AttachmentState.Loaded
            ?: return if (s.job?.operation is Operation.LoadingStems) {
                ReduceResult(s.copy(autoAttachSuppressedFor = s.job.jobId))
            } else {
                ReduceResult(s)
            }
        return ReduceResult(
            s.copy(
                attachment = AttachmentState.None,
                autoAttachSuppressedFor = loaded.jobId,
            ),
            listOf(SeparationEffect.DetachStems(DetachReason.USER)),
        )
    }

    private fun onBackfill(s: SeparationSnapshot): ReduceResult {
        val job = s.job ?: return notify(s, SeparationMessages.NO_RESULT)
        if (!job.isTerminal) return notify(s, SeparationMessages.BUSY)
        if (job.lifecycle is Lifecycle.Failed || job.kind != SeparationKind.EDGE) {
            return notify(s, SeparationMessages.NO_RESULT)
        }
        if (job.operation is Operation.LoadingStems) return notify(s, SeparationMessages.BUSY)
        if (s.currentTrackKey == null || s.currentTrackKey != job.trackKey) {
            return notify(s, SeparationMessages.RESULT_OTHER_TRACK)
        }
        if (job.coverage.frontGapFrames <= 0L) return notify(s, SeparationMessages.NO_RESULT)
        job.files ?: return notify(s, SeparationMessages.NO_RESULT)

        val id = s.nextJobId
        val request = StartRequest(
            kind = SeparationKind.EDGE,
            trackKey = job.trackKey,
            startFrame = 0L,
            fromPositionMs = 0L,
            trackTitle = job.trackTitle,
        )
        val next = newJob(id, request)
        return ReduceResult(
            s.copy(
                job = next,
                nextJobId = id + 1,
                lastError = null,
                autoAttachSuppressedFor = null,
                autoAttachAttemptedFor = null,
                attachment = if (s.attachment is AttachmentState.Loaded &&
                    (s.attachment as AttachmentState.Loaded).trackKey == job.trackKey
                ) {
                    s.attachment
                } else {
                    AttachmentState.None
                },
            ),
            listOf(SeparationEffect.StartDecode(id, request)),
        )
    }

    private fun onExport(s: SeparationSnapshot): ReduceResult {
        val job = s.job ?: return notify(s, SeparationMessages.EXPORT_MISSING)
        if (job.lifecycle !is Lifecycle.Finished) {
            return notify(s, SeparationMessages.EXPORT_MISSING)
        }
        if (s.currentTrackKey == null || s.currentTrackKey != job.trackKey) {
            return notify(s, SeparationMessages.RESULT_OTHER_TRACK)
        }
        if (!job.coverage.complete || job.files == null) {
            return notify(s, SeparationMessages.EXPORT_MISSING)
        }
        return ReduceResult(s, listOf(SeparationEffect.Export(job.jobId)))
    }

    private fun onClearCache(s: SeparationSnapshot): ReduceResult {
        if (s.job?.isTerminal == false ||
            s.job?.operation is Operation.LoadingStems ||
            s.attachment is AttachmentState.Loaded
        ) {
            return notify(s, SeparationMessages.CLEAR_BUSY)
        }
        return ReduceResult(
            s.copy(
                lastFinished = null,
                lastError = null,
                autoAttachSuppressedFor = null,
                autoAttachAttemptedFor = null,
            ),
            listOf(SeparationEffect.ClearCache),
        )
    }

    // ==================== Domain events ====================

    private fun onSourceReady(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.SourceReady,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId || job.lifecycle !is Lifecycle.Preparing) {
            return ReduceResult(s)
        }
        if (job.operation !is Operation.Decoding) return ReduceResult(s)
        val running = job.copy(
            lifecycle = Lifecycle.Running,
            operation = Operation.Separating(job.jobId),
            coverage = e.coverage,
            files = e.files ?: job.files,
        )
        return ReduceResult(
            s.copy(job = running, lastError = null),
            listOf(SeparationEffect.StartSeparation(job.jobId)),
        )
    }

    private fun onCoverageUpdated(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.CoverageUpdated,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId) return ReduceResult(s)
        if (job.lifecycle !is Lifecycle.Running) return ReduceResult(s)
        if (job.operation !is Operation.Separating && job.operation !is Operation.Backfilling) {
            return ReduceResult(s)
        }

        var nextJob = job.copy(coverage = e.coverage)
        val effects = mutableListOf<SeparationEffect>()

        // EDGE：gate 就绪 + 有文件 + 用户没关 + 本 job 没试过 → 自动加载一次。
        if (job.kind == SeparationKind.EDGE &&
            e.gateReady &&
            e.coverage.hasPreview &&
            nextJob.files != null &&
            s.currentTrackKey == job.trackKey &&
            s.autoAttachSuppressedFor != job.jobId &&
            s.autoAttachAttemptedFor != job.jobId
        ) {
            nextJob = nextJob.copy(operation = Operation.LoadingStems(job.jobId))
            effects += SeparationEffect.LoadStems(job.jobId, nextJob.files!!)
        }

        return ReduceResult(
            s.copy(job = nextJob, autoAttachAttemptedFor =
                if (effects.any { it is SeparationEffect.LoadStems }) job.jobId
                else s.autoAttachAttemptedFor),
            effects,
        )
    }

    private fun onJobFinished(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.JobFinished,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId) return ReduceResult(s)
        if (job.lifecycle is Lifecycle.Stopping || job.lifecycle is Lifecycle.Failed) {
            return ReduceResult(s)
        }

        val coverage = e.coverage.copy(complete = e.complete)
        var nextJob = job.copy(
            lifecycle = Lifecycle.Finished,
            operation = null,
            coverage = coverage,
            files = e.files,
        )
        val effects = mutableListOf<SeparationEffect>(SeparationEffect.StopForeground(job.jobId))
        var next = s.copy(
            job = nextJob,
            lastFinished = FinishedJob(
                job.jobId,
                job.trackKey,
                job.kind,
                e.files,
                coverage,
                e.metrics,
            ),
            lastError = null,
        )

        if (job.kind == SeparationKind.EDGE &&
            s.currentTrackKey == job.trackKey &&
            s.autoAttachSuppressedFor != job.jobId &&
            s.autoAttachAttemptedFor != job.jobId
        ) {
            nextJob = nextJob.copy(operation = Operation.LoadingStems(job.jobId))
            next = next.copy(
                job = nextJob,
                autoAttachAttemptedFor = job.jobId,
            )
            effects += SeparationEffect.LoadStems(job.jobId, e.files)
        } else if (job.kind != SeparationKind.EDGE && s.currentTrackKey == job.trackKey) {
            effects += SeparationEffect.Notify(FINISHED_AVAILABLE)
        }

        return ReduceResult(next, effects)
    }

    private fun onJobFailed(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.JobFailed,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId) return ReduceResult(s)
        if (job.lifecycle is Lifecycle.Finished || job.lifecycle is Lifecycle.Failed) {
            return ReduceResult(s)
        }
        val failed = job.copy(
            lifecycle = Lifecycle.Failed(e.message),
            operation = null,
        )
        return ReduceResult(
            s.copy(job = failed, lastError = e.message),
            listOf(
                SeparationEffect.StopForeground(job.jobId),
                SeparationEffect.Notify(e.message),
            ),
        )
    }

    private fun onJobStopped(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.JobStopped,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId || job.lifecycle !is Lifecycle.Stopping) {
            return ReduceResult(s)
        }
        val pending = (job.operation as? Operation.Cancelling)?.pendingStart
        if (pending == null) {
            return ReduceResult(
                s.copy(
                    job = null,
                    autoAttachSuppressedFor = null,
                    autoAttachAttemptedFor = null,
                    lastError = null,
                ),
                listOf(SeparationEffect.StopForeground(job.jobId)),
            )
        }

        val id = s.nextJobId
        val started = newJob(id, pending)
        var next = s.copy(
            job = started,
            nextJobId = id + 1,
            lastError = null,
            autoAttachSuppressedFor = null,
            autoAttachAttemptedFor = null,
        )
        val effects = mutableListOf<SeparationEffect>(SeparationEffect.StartDecode(id, pending))
        next = detachIfOtherTrack(next, pending.trackKey, effects)
        return ReduceResult(next, effects)
    }

    private fun onStemsLoaded(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.StemsLoaded,
    ): ReduceResult {
        val job = s.job
        if (job == null || job.jobId != e.jobId) return ReduceResult(s)
        if (job.lifecycle is Lifecycle.Failed || job.lifecycle is Lifecycle.Stopping) {
            return ReduceResult(s.copy(job = job.copy(operation = null)))
        }
        // 用户在加载期间关掉开关：不 attach；清掉 loading，避免一直转。
        if (s.autoAttachSuppressedFor == e.jobId) {
            return ReduceResult(
                s.copy(
                    job = if (job.operation is Operation.LoadingStems) {
                        job.copy(operation = null)
                    } else {
                        job
                    },
                ),
            )
        }
        if (s.currentTrackKey == null || s.currentTrackKey != job.trackKey) {
            return ReduceResult(
                s.copy(job = job.copy(operation = null)),
            )
        }
        return ReduceResult(
            s.copy(
                job = job.copy(operation = null),
                attachment = AttachmentState.Loaded(job.jobId, job.trackKey, e.files),
                lastError = null,
            ),
        )
    }

    private fun onStemsLoadFailed(
        s: SeparationSnapshot,
        e: SeparationEvent.Domain.StemsLoadFailed,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId) return ReduceResult(s)
        if (job.operation !is Operation.LoadingStems) return ReduceResult(s)
        return ReduceResult(
            s.copy(
                job = job.copy(operation = null),
                attachment = AttachmentState.None,
                lastError = e.message,
            ),
            listOf(SeparationEffect.Notify(SeparationMessages.LOAD_FAILED)),
        )
    }

    // ==================== External events ====================

    private fun onTrackChanged(s: SeparationSnapshot, newTrack: TrackKey): ReduceResult {
        if (s.currentTrackKey == newTrack) return ReduceResult(s)
        var next = s.copy(currentTrackKey = newTrack)
        val effects = mutableListOf<SeparationEffect>()

        // 当前 attachment 不再属于新曲 → 立即解除。
        next = detachIfOtherTrack(next, newTrack, effects)

        val job = next.job ?: return ReduceResult(next, effects)
        if (job.trackKey == newTrack) return ReduceResult(next, effects)

        when (job.kind) {
            SeparationKind.EDGE -> {
                when (job.lifecycle) {
                    is Lifecycle.Preparing, is Lifecycle.Running -> {
                        next = next.copy(
                            job = job.copy(
                                lifecycle = Lifecycle.Stopping,
                                operation = Operation.Cancelling(job.jobId),
                            ),
                        )
                        effects += SeparationEffect.CancelJob(job.jobId)
                    }

                    is Lifecycle.Stopping -> {
                        val op = job.operation as? Operation.Cancelling
                        if (op?.pendingStart != null && op.pendingStart.trackKey != newTrack) {
                            next = next.copy(job = job.copy(operation = op.copy(pendingStart = null)))
                        }
                    }

                    is Lifecycle.Finished, is Lifecycle.Failed -> Unit
                }
            }

            SeparationKind.FULL_2, SeparationKind.FULL_4 -> {
                // D1：FULL 后台继续，只解除 attachment；不取消、不自动切。
                // 若正处于 Stopping，保持 Stopping，等 JobStopped。
            }
        }
        return ReduceResult(next, effects)
    }

    private fun onPlaybackEnded(s: SeparationSnapshot): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        var next = s
        val effects = mutableListOf<SeparationEffect>()
        if (job.kind == SeparationKind.EDGE &&
            (job.lifecycle is Lifecycle.Preparing || job.lifecycle is Lifecycle.Running)
        ) {
            next = next.copy(
                job = job.copy(
                    lifecycle = Lifecycle.Stopping,
                    operation = Operation.Cancelling(job.jobId),
                ),
            )
            effects += SeparationEffect.CancelJob(job.jobId)
        }
        if (next.attachment is AttachmentState.Loaded) {
            next = next.copy(attachment = AttachmentState.None)
            effects += SeparationEffect.DetachStems(DetachReason.PLAYBACK_LOST)
        }
        return ReduceResult(next, effects)
    }

    private fun onServiceLost(s: SeparationSnapshot, service: ServiceKind): ReduceResult {
        return when (service) {
            ServiceKind.PLAYBACK -> {
                var next = s.copy(currentTrackKey = null, attachment = AttachmentState.None)
                val effects = mutableListOf<SeparationEffect>(
                    SeparationEffect.DetachStems(DetachReason.PLAYBACK_LOST),
                )
                val job = next.job
                if (job != null && job.kind == SeparationKind.EDGE &&
                    (job.lifecycle is Lifecycle.Preparing || job.lifecycle is Lifecycle.Running)
                ) {
                    next = next.copy(
                        job = job.copy(
                            lifecycle = Lifecycle.Stopping,
                            operation = Operation.Cancelling(job.jobId),
                        ),
                    )
                    effects += SeparationEffect.CancelJob(job.jobId)
                }
                ReduceResult(next, effects)
            }

            ServiceKind.SEPARATION -> {
                val job = s.job ?: return ReduceResult(s)
                if (job.isTerminal) return ReduceResult(s)
                val failed = job.copy(
                    lifecycle = Lifecycle.Failed("分离服务丢失"),
                    operation = null,
                )
                ReduceResult(
                    s.copy(job = failed, lastError = "分离服务丢失"),
                    listOf(SeparationEffect.StopForeground(job.jobId)),
                )
            }
        }
    }

    private fun onProcessRestored(s: SeparationSnapshot): ReduceResult {
        // active job 不恢复；完整缓存由下次 Start 的 fast path 发现。
        return ReduceResult(SeparationSnapshot(nextJobId = s.nextJobId + 1))
    }

    // ==================== Timeouts ====================

    private fun onTimeout(
        s: SeparationSnapshot,
        e: SeparationEvent.Timeout.OperationTimeout,
    ): ReduceResult {
        val job = s.job ?: return ReduceResult(s)
        if (job.jobId != e.jobId) return ReduceResult(s)

        return when (e.operation) {
            OperationKind.LOADING_STEMS -> {
                if (job.operation !is Operation.LoadingStems) {
                    ReduceResult(s)
                } else {
                    ReduceResult(
                        s.copy(
                            job = job.copy(operation = null),
                            lastError = SeparationMessages.LOAD_TIMEOUT,
                        ),
                        listOf(SeparationEffect.Notify(SeparationMessages.LOAD_TIMEOUT)),
                    )
                }
            }

            OperationKind.CANCELLING -> {
                val op = job.operation as? Operation.Cancelling ?: return ReduceResult(s)
                ReduceResult(
                    s.copy(
                        job = job.copy(operation = op.copy(workerAlive = true)),
                        lastError = SeparationMessages.CANCEL_SLOW,
                    ),
                    listOf(SeparationEffect.Notify(SeparationMessages.CANCEL_SLOW)),
                )
            }

            OperationKind.DECODING -> {
                if (job.operation !is Operation.Decoding) return ReduceResult(s)
                val failed = job.copy(
                    lifecycle = Lifecycle.Failed(SeparationMessages.DECODE_TIMEOUT),
                    operation = null,
                )
                ReduceResult(
                    s.copy(job = failed, lastError = SeparationMessages.DECODE_TIMEOUT),
                    listOf(
                        SeparationEffect.StopForeground(job.jobId),
                        SeparationEffect.Notify(SeparationMessages.DECODE_TIMEOUT),
                    ),
                )
            }

            OperationKind.SEPARATING -> {
                if (job.operation !is Operation.Separating) return ReduceResult(s)
                ReduceResult(
                    s.copy(
                        job = job.copy(operation = Operation.Separating(job.jobId)),
                        lastError = SeparationMessages.STALLED,
                    ),
                    listOf(SeparationEffect.Notify(SeparationMessages.STALLED)),
                )
            }

            OperationKind.BACKFILLING -> {
                if (job.operation !is Operation.Backfilling) return ReduceResult(s)
                ReduceResult(
                    s.copy(
                        job = job.copy(operation = Operation.Backfilling(job.jobId)),
                        lastError = SeparationMessages.STALLED,
                    ),
                    listOf(SeparationEffect.Notify(SeparationMessages.STALLED)),
                )
            }
        }
    }

    // ==================== helpers ====================

    private fun newJob(id: Long, request: StartRequest): JobState = JobState(
        jobId = id,
        trackKey = request.trackKey,
        kind = request.kind,
        lifecycle = Lifecycle.Preparing,
        operation = Operation.Decoding(id),
        coverage = Coverage(
            totalFrames = 0L,
            startFrame = request.startFrame.coerceAtLeast(0L),
            coveredRanges = emptyList(),
            watermarkFrame = request.startFrame.coerceAtLeast(0L),
            complete = false,
        ),
        files = null,
        trackTitle = request.trackTitle,
    )

    private fun notify(s: SeparationSnapshot, message: String): ReduceResult =
        ReduceResult(s, listOf(SeparationEffect.Notify(message)))

    private fun detachIfOtherTrack(
        s: SeparationSnapshot,
        track: TrackKey,
        effects: MutableList<SeparationEffect>,
    ): SeparationSnapshot {
        val loaded = s.attachment as? AttachmentState.Loaded ?: return s
        if (loaded.trackKey == track) return s
        effects += SeparationEffect.DetachStems(DetachReason.TRACK_CHANGED)
        return s.copy(attachment = AttachmentState.None)
    }
}
