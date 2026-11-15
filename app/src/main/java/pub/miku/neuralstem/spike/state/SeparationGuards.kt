package pub.miku.neuralstem.spike.state

/**
 * P0 归属校验的纯函数，便于 JVM 测试。
 *
 * 这里只回答“这个请求/终态属于当前曲/当前 job 吗”，不持有状态。
 */
object SeparationGuards {

    /**
     * StartRequest 是否仍针对当前曲。
     * - current == null：尚未收到当前曲，放行（由上层稍后对账）
     * - request.trackKey.uri 为空：旧/自动入口，放行
     * - 其余必须 uri 相等。
     */
    fun requestMatchesCurrent(
        request: StartRequest,
        current: TrackKey?,
    ): Boolean {
        if (current == null) return true
        if (request.trackKey.uri.isBlank()) return true
        return current.uri == request.trackKey.uri
    }

    /**
     * repo 终态的 sourcePath 是否属于当前 job。
     * 旧状态未带 sourcePath 时放行，避免兼容性回退；新状态必须严格相等。
     */
    fun terminalSourceMatches(
        activeSourcePath: String?,
        stateSourcePath: String?,
    ): Boolean {
        if (stateSourcePath == null) return true
        if (activeSourcePath == null) return true
        return stateSourcePath == activeSourcePath
    }

    /** 任务所属曲是否就是当前曲；供 UI 映射丢弃旧曲 Finished。 */
    fun jobMatchesCurrent(current: TrackKey?, jobTrack: TrackKey): Boolean {
        if (current == null) return true
        if (jobTrack.uri.isBlank()) return true
        return current.uri == jobTrack.uri
    }

    /**
     * UI 导出/完成态的唯一归属判断：finished job、属于当前曲、文件存在由调用方再校验。
     * 纯函数，避免 UI 继续从 SpikeVM 的并行快照读导出状态。
     */
    fun exportableFiles(
        snapshot: SeparationSnapshot,
        currentUri: String?,
    ): List<String>? {
        val job = snapshot.job ?: return null
        if (job.lifecycle !is Lifecycle.Finished) return null
        val files = job.files?.paths ?: return null
        if (currentUri != null && job.trackKey.uri != currentUri) return null
        val current = snapshot.currentTrackKey
        if (current != null && job.trackKey.uri != current.uri) return null
        return files
    }

    /** EdgeSession 的 sourceUri 是否属于当前 job。 */
    fun edgeSourceMatches(
        activeSourcePath: String?,
        edgeSourceUri: String?,
    ): Boolean {
        if (activeSourcePath == null || edgeSourceUri == null) return true
        return activeSourcePath == edgeSourceUri
    }
}
