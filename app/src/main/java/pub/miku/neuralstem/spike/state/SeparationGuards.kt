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

    /** EdgeSession 的 sourceUri 是否属于当前 job。 */
    fun edgeSourceMatches(
        activeSourcePath: String?,
        edgeSourceUri: String?,
    ): Boolean {
        if (activeSourcePath == null || edgeSourceUri == null) return true
        return activeSourcePath == edgeSourceUri
    }
}
