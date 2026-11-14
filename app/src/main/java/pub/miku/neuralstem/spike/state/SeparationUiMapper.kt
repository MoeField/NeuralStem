package pub.miku.neuralstem.spike.state

/**
 * Domain snapshot → 分离页展示态的纯映射（P2-3 第 2 步）。
 *
 * 只做“显示”映射；启动/取消/自动 attach 仍由 VM 的会话层负责，逐步迁移。
 */
data class SeparationUi(
    val phase: Phase,
    val percent: Int = 0,
    val kind: SeparationKind? = null,
    val fromCache: Boolean = false,
    val failedMessage: String? = null,
    val busy: Boolean = false,
) {
    enum class Phase {
        PREPARING,
        DECODING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLING,
    }
}

object SeparationUiMapper {

    /**
     * @param edgePercent 旧 edge UI 的百分比（page 已从 Domain.edgeUi 派生）；优先于 coverage。
     * @return null = Domain 没有 job，调用方回退到旧 SessionState 显示。
     */
    fun map(
        snapshot: SeparationSnapshot,
        edgePercent: Int? = null,
    ): SeparationUi? {
        val job = snapshot.job ?: return null
        return when (val lc = job.lifecycle) {
            is Lifecycle.Preparing -> SeparationUi(
                phase = if (job.operation is Operation.Decoding) {
                    SeparationUi.Phase.DECODING
                } else {
                    SeparationUi.Phase.PREPARING
                },
                kind = job.kind,
                busy = true,
            )
            is Lifecycle.Running -> SeparationUi(
                phase = SeparationUi.Phase.RUNNING,
                percent = (edgePercent ?: percentOf(job.coverage)).coerceIn(0, 100),
                kind = job.kind,
                busy = true,
            )
            is Lifecycle.Stopping -> SeparationUi(
                phase = SeparationUi.Phase.CANCELLING,
                kind = job.kind,
                busy = true,
            )
            is Lifecycle.Finished -> SeparationUi(
                phase = SeparationUi.Phase.DONE,
                kind = job.kind,
                fromCache = job.metrics?.fromCache == true,
            )
            is Lifecycle.Failed -> SeparationUi(
                phase = SeparationUi.Phase.FAILED,
                kind = job.kind,
                failedMessage = lc.message,
            )
        }
    }

    private fun percentOf(coverage: Coverage): Int {
        if (coverage.totalFrames <= 0L) return 0
        return (coverage.coveredFrames * 100L / coverage.totalFrames).toInt()
    }
}
