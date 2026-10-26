package pub.miku.neuralstem.player

import java.io.File

/**
 * ★ 会话状态机（骨架）。
 *
 * ## 背景
 * 2026-09-29 回退后决定：引入**显式状态机 + 不变量**，根治本会话反复出现的
 * “状态分散、缺显式转移”类 bug（EQ 显示错 / 分离遗留态误触发 / 切歌未退多轨 / 播放态双源）。
 * 详见 `implementation_plan.artifact.md §三`。
 *
 * ## 本文件定位（骨架阶段）
 * - 只定义**状态模型 + 转移 + 不变量**（纯逻辑，**无 Android 依赖** → 可 JVM 单测）。
 * - **尚未接入运行时**（不改 `PlaybackService` 行为）。随后逐步：
 *   - Step 4（歌单）/ Step 7（EQ）/ Step 9（分离开关）/ Step 10（分离重构）时把对应状态
 *     **收归此处**，直到 Full（单一真源）。
 *
 * ## 4 条不变量（转移点强制）
 * - **I1**：换队列前若多轨 → 先退出多轨。
 * - **I2**：EQ 由“会话就绪”派生（`Unsupported` ⟺ 无有效会话），非粘性布尔。
 * - **I3**：分离 `Done` **一次性消费**（`consumeSeparationDone()`），遗留态不会自动触发。
 * - **I4**：`isPlaying` 单一来源（本状态机不持有；由 Service 的主轨驱动，见后续接入）。
 */

// ============================ 状态定义 ============================

/** 会话模式（单一真源）。 */
sealed interface SessionMode {
    /** 单轨：播原曲（或多轨退出后回到的单曲）。 */
    data object Single : SessionMode

    /** 多轨：主轨 = session player（[primaryId]），其余为跟随轨（[trackIds]）。 */
    data class MultiTrack(val primaryId: String, val trackIds: List<String>) : SessionMode
}

/** EQ 挂载状态（由会话派生，非粘性布尔）。 */
sealed interface EqState {
    /** 设备不支持 / 当前无有效会话（bandCount==0 或 sessionId==0）。 */
    data object Unsupported : EqState

    /** 已挂到若干 audio session。 */
    data class Attached(val sessionIds: List<Int>, val enabled: Boolean) : EqState
}

/** 分离任务（一次性消费语义）。 */
sealed interface SeparationTask {
    data object Idle : SeparationTask

    /** 准备中（源解析 / 模型加载）。 */
    data class Preparing(val phase: PrepPhase = PrepPhase.START) : SeparationTask

    /**
     * ★准备子阶段：供 UI 显示“在干什么”。
     *
     * 实测（BF00A）：点「快速」到首块推理要 **~13 秒**，其中绝大部分是解码整首 mp3。
     * 之前只显示静态“准备中…”→ 像卡死。
     */
    enum class PrepPhase { START, DECODING, LOADING_MODEL, MODEL_READY }

    data class Running(val percent: Int) : SeparationTask

    /**
     * 完成态（**2-stem / 4-stem 两型**）：两者均实现 [SeparationDone]，
     * 一律经 [SessionStateMachine.consumeSeparationDone] **一次性消费**。
     */
    sealed interface SeparationDone : SeparationTask {
        val fromCache: Boolean
        val inferTimeMs: Long
        val peakPssMb: Float
        val audioDurationMs: Long
    }

    /** 2-stem 完成（人声 / 伴奏）。 */
    data class Done2(
        val vocalsFile: File,
        val accompanimentFile: File,
        val modelLoadMs: Long,
        override val inferTimeMs: Long,
        override val peakPssMb: Float,
        override val audioDurationMs: Long,
        override val fromCache: Boolean = false,
    ) : SeparationDone

    /** 4-stem 完成（stemFiles 顺序 = 人声/鼓/贝斯/其他）。 */
    data class Done4(
        val stemFiles: List<File>,
        override val inferTimeMs: Long,
        override val peakPssMb: Float,
        override val audioDurationMs: Long,
        override val fromCache: Boolean = false,
    ) : SeparationDone

    data class Failed(val message: String, val cause: Throwable? = null) : SeparationTask

    /** 用户取消（区别于 [Failed]：不是错误，不弹错误提示）。 */
    data object Cancelled : SeparationTask
}

/**
 * ★R-a：**播放队列**（歌单 = 队列）。
 *
 * 用途：支撑上/下一首、自动下一首、循环×队列。**单一真源**（收归状态机）。
 * 队列项带 `uri` 与 `title`（load 时需要标题）。顺序 = 歌单顺序。
 */
sealed interface QueueState {
    /** 无队列（如仅单曲播放/多轨直接载入）。 */
    data object Empty : QueueState

    /** 有队列。[currentIndex] 可能越界（如被删）—— 由调用方校验。 */
    data class Active(val tracks: List<QueueTrack>, val currentIndex: Int) : QueueState {
        val current: QueueTrack? get() = tracks.getOrNull(currentIndex)
    }
}

/** 队列中的一首曲（uri + 显示标题）。 */
data class QueueTrack(val uri: String, val title: String)

/**
 * ★AVS3 解码会话（一次性任务，与 [SeparationTask] 平级）。
 *
 * ## 为什么需要它进状态机
 * AVS3 解码是**异步且耗时**的（一首 4 分钟约 31 秒），
 * 而 `doLoad()` 是同步的。如果让解码自己一个协程随便跑，
 * 会出现「解完了才发现用户已经切走了」的竞态 —— 结果覆盖了错的曲目。
 *
 * 所以把它做成**单一真源**：
 * - 当前正在为哪个 uri 解码、进度多少、是不是在收尾，全部只有这里说了算
 * - 切歌时根据这个状态决定「立即取消」还是「让它收尾」
 *
 * ## 为何不做可续（不像分离那样用 manifest）
 * 分离可以续，因为它按**帧区间**独立处理，写出去就永久有效。
 * 但 AVS3 是**帧间预测**编码：解码器内部有 MDCT 重叠缓冲、
 * 神经网络上下文状态，跳到第 N 帧续解会得到与连续解码**不一致**的结果
 * （开头几百毫秒会出错）。⇒ 只能从头再解。
 */
sealed interface Avs3Decode {
    /** 无解码任务。 */
    data object Idle : Avs3Decode

    /**
     * 正在解码。
     *
     * @param uri 目标源 uri（用于判断「还是不是当前要播的那首」）
     * @param percent 0..100
     * @param finishing 是否已进入「收尾」阶段（用户切走了但进度 >80%，
     *        让它跑完以保留缓存，代价是新歌多等几秒）
     */
    data class Running(
        val uri: String,
        val title: String,
        val percent: Int,
        val finishing: Boolean = false,
    ) : Avs3Decode

    /** 失败（解码器不可用 / 解码出错）。UI 展示后回到 [Idle]。 */
    data class Failed(val uri: String, val message: String) : Avs3Decode
}

// ============================ 状态机 ============================

/**
 * 会话状态机：持有 [mode] / [eq] / [separation]，**所有变更走这里**（转移点强制不变量）。
 *
 * 副作用（关跟随轨 / 挂 EQ / 卸 EQ）经 [Effects] 回出去，由 `PlaybackService` 实现；
 * 单测用假实现即可（与 `EqController` 的 `factory` 注入同思路）。
 */
class SessionStateMachine(private val effects: Effects) {

    /** 需要外部执行的副作用。 */
    interface Effects {
        /** 关掉跟随轨（**不**释放主轨）。用于 I1 / 退出多轨。 */
        fun closeFollowers()

        /** 把 EQ 挂到这些会话（[sessionIds] 非空）。 */
        fun attachEq(sessionIds: List<Int>, enabled: Boolean)

        /** 卸载 EQ。 */
        fun detachEq()
    }

    var mode: SessionMode = SessionMode.Single
        private set

    /**
     * 从外部（Service 快照）**同步** [mode]，**不触发副作用**。
     * 用于把“真源”状态带入本状态机（而非当作一次转移）。
     */
    fun syncMode(newMode: SessionMode) {
        mode = newMode
    }

    /**
     * ★I2：从 Service 的 EQ 快照**同步** EQ 可用性/开关（**无副作用**）。
     * `eqAvailable` 因此**由本状态机派生**（不再是一个可能过时/粘性的独立布尔）。
     */
    fun syncEq(available: Boolean, enabled: Boolean) {
        eq = if (available) EqState.Attached(emptyList(), enabled) else EqState.Unsupported
    }

    var eq: EqState = EqState.Unsupported
        private set

    var separation: SeparationTask = SeparationTask.Idle
        private set

    /** ★AVS3 解码会话（与分离平级的一次性任务）。单一真源。 */
    var avs3Decode: Avs3Decode = Avs3Decode.Idle
        private set

    /** ★R-a：播放队列（歌单=队列）。单一真源。 */
    var queue: QueueState = QueueState.Empty
        private set

    // ---- 派生量（不单独存）----

    val isMultiTrack: Boolean get() = mode is SessionMode.MultiTrack

    /** 分离开关语义：多轨 = 已分离在播。 */
    val separationOn: Boolean get() = isMultiTrack

    /** EQ 是否可用：由会话派生（非粘性布尔）。 */
    val eqAvailable: Boolean get() = eq is EqState.Attached

    /** EQ 是否开启。 */
    val eqEnabled: Boolean get() = (eq as? EqState.Attached)?.enabled == true

    // ============================ I1：队列 ============================

    /**
     * 换队列（`setMediaItems` / `playPlaylist` / `loadAndPlay`）**前**调用：
     * 若处于多轨，则先退出多轨（否则跟随轨不会被停 → 两首同时响）。
     */
    fun beforeSetQueue() {
        if (mode is SessionMode.MultiTrack) exitMultiTrack()
    }

    // ============================ R-a：播放队列（歌单=队列）============================

    /**
     * 设置播放队列（歌单=队列）。
     * @param trackIds 曲目 uri 列表（顺序=歌单顺序）
     * @param startIndex 起始下标（越界则归 0；空列表则置 [QueueState.Empty]）
     */
    fun setQueue(tracks: List<QueueTrack>, startIndex: Int = 0) {
        queue = if (tracks.isEmpty()) QueueState.Empty
        else QueueState.Active(tracks, startIndex.coerceIn(0, tracks.size - 1))
    }

    /** 清空队列（回到无队列的单曲语义）。 */
    fun clearQueue() {
        queue = QueueState.Empty
    }

    /**
     * 推进到下一首（成功则更新 [queue] 下标并返回 true；无队列/无下一首返回 false）。
     * 调用方根据返回值决定是否 load 新曲。
     */
    fun advanceQueue(): Boolean {
        val a = queue as? QueueState.Active ?: return false
        val next = a.currentIndex + 1
        if (next >= a.tracks.size) return false
        queue = a.copy(currentIndex = next)
        return true
    }

    /** 回退到上一首（同上）。 */
    fun retreatQueue(): Boolean {
        val a = queue as? QueueState.Active ?: return false
        val prev = a.currentIndex - 1
        if (prev < 0) return false
        queue = a.copy(currentIndex = prev)
        return true
    }

    /** 回到队列开头（循环歌单时“到尾绕回”）。 */
    fun wrapQueueToStart(): Boolean {
        val a = queue as? QueueState.Active ?: return false
        if (a.tracks.isEmpty()) return false
        queue = a.copy(currentIndex = 0)
        return true
    }

    /**
     * 移除队列中某曲（如失效曲）。会自动维持 [currentIndex] 合理性：
     * 移除当前曲时下标不变（指向“下一个”）；移除更前的曲则下标前移。
     */
    fun removeFromQueue(uri: String) {
        val a = queue as? QueueState.Active ?: return
        val removedIdx = a.tracks.indexOfFirst { it.uri == uri }
        if (removedIdx < 0) return
        val newTracks = a.tracks.filterNot { it.uri == uri }
        if (newTracks.isEmpty()) {
            queue = QueueState.Empty
            return
        }
        val newIndex = a.currentIndex - (if (removedIdx < a.currentIndex) 1 else 0)
        queue = QueueState.Active(newTracks, newIndex.coerceIn(0, newTracks.size - 1))
    }

    // ============================ 多轨 ============================

    /** 进入多轨。 */
    fun enterMultiTrack(primaryId: String, trackIds: List<String>, sessions: List<Int>) {
        mode = SessionMode.MultiTrack(primaryId, trackIds)
        attachEq(sessions, eqEnabled)
    }

    /** 退出多轨：关跟随轨 + 回到单轨（EQ 需由调用方在会话就绪后重挂）。 */
    fun exitMultiTrack() {
        if (mode !is SessionMode.MultiTrack) return
        effects.closeFollowers()
        mode = SessionMode.Single
    }

    // ============================ I2：EQ（会话派生）============================

    /** 会话就绪（单轨或多轨的 player `STATE_READY`）→ 挂 EQ。 */
    fun onSessionsReady(sessions: List<Int>) {
        attachEq(sessions, eqEnabled)
    }

    /** 设备/会话不支持 EQ（bandCount==0）。 */
    fun onEqUnsupported() {
        eq = EqState.Unsupported
        effects.detachEq()
    }

    /** 开 / 关 EQ（无有效会话时忽略）。 */
    fun setEqEnabled(enabled: Boolean) {
        val a = eq as? EqState.Attached ?: return
        eq = a.copy(enabled = enabled)
        effects.attachEq(a.sessionIds, enabled)
    }

    private fun attachEq(sessions: List<Int>, enabled: Boolean) {
        val valid = sessions.filter { it != 0 }
        if (valid.isEmpty()) {
            eq = EqState.Unsupported
            effects.detachEq()
            return
        }
        eq = EqState.Attached(valid, enabled)
        effects.attachEq(valid, enabled)
    }

    // ============================ I3：分离（一次性消费）============================

    /** 进入准备态（源解析 / 模型加载）。 */
    fun onSeparationPreparing(phase: SeparationTask.PrepPhase = SeparationTask.PrepPhase.START) {
        separation = SeparationTask.Preparing(phase)
    }

    fun startSeparation() {
        separation = SeparationTask.Running(0)
    }

    /** 进度：仅当处于 [SeparationTask.Running] 时推进（Preparing 不算）。 */
    fun onSeparationProgress(percent: Int) {
        if (separation is SeparationTask.Running) separation = SeparationTask.Running(percent)
    }

    /** 完成：接受 [SeparationTask.Done2] 或 [SeparationTask.Done4]。 */
    fun onSeparationDone(done: SeparationTask.SeparationDone) {
        separation = done
    }

    fun onSeparationFailed(message: String, cause: Throwable? = null) {
        separation = SeparationTask.Failed(message, cause)
    }

    fun onSeparationCancelled() {
        separation = SeparationTask.Cancelled
    }

    /**
     * ★G-3fix：边分离边播**正常完成**（无落盘文件）。
     *
     * 语义：任务结束、不再 busy；但不产生 [SeparationTask.Done2]（没有 stem 文件）。
     * 回到 [SeparationTask.Idle] —— 此时多轨已在播（由 edge 自动切换完成）。
     *
     * 为何必须存在：`startEdge()` 跑完若不发终态，`SeparationTask` 会永久停在
     * `Running(99)` → `busy=true` → UI 卡在“分离中 99%”且取消按钮不消失。
     */
    fun onSeparationEdgeDone() {
        separation = SeparationTask.Idle
    }

    /**
     * **消费**一次分离完成结果（[SeparationTask.Done2]/[SeparationTask.Done4]）；无则返回 `null`。
     *
     * UI 只能经此拿到结果 → 天然“只响应 `Running→Done` 的**转换**、不响应遗留态”，
     * 从而根治“一进分离页就误触发自动切多轨”。消费后回到 [SeparationTask.Idle]。
     */
    fun consumeSeparationDone(): SeparationTask.SeparationDone? {
        val d = separation as? SeparationTask.SeparationDone ?: return null
        separation = SeparationTask.Idle
        return d
    }

    // ==================== ★AVS3 解码会话 ====================

    /**
     * 请求开始解码某首曲。
     *
     * @return 上一个任务是否需要它**自行收尾**（true = 不要取消，让它跑完）。
     *         调用方据返回值决定：true → 不取消旧协程；false → 立即取消。
     *
     * ## 为何返回值而不是直接取消
     * 协程的取消只有持有 Job 的调用方能做，
     * 状态机只提供**决策**、不持有协程 —— 保持纯逻辑，可 JVM 单测。
     */
    fun requestAvs3Decode(): Boolean {
        val prev = avs3Decode
        val letFinish = prev is Avs3Decode.Running &&
            !prev.finishing &&
            prev.percent >= AVS3_FINISH_THRESHOLD

        // 旧任务进入「收尾」：只更新它自己，不阻挠新目标
        avs3Decode = if (letFinish && prev is Avs3Decode.Running) {
            prev.copy(finishing = true)
        } else {
            Avs3Decode.Idle
        }
        return letFinish
    }

    /**
     * 真正开始执行解码（旧任务已处理完）。
     *
     * 与 [requestAvs3Decode] 分开：先决策旧任务，再启动新任务。
     * 避免「已发起但还没开始」的假 Running 状态。
     */
    fun beginAvs3Decode(uri: String, title: String) {
        avs3Decode = Avs3Decode.Running(uri, title, percent = 0)
    }

    /** 解码进度（只接受当前目标的进度，忽略已过期回调）。 */
    fun onAvs3Progress(uri: String, percent: Int) {
        val cur = avs3Decode as? Avs3Decode.Running ?: return
        // ★防竞态的关键一行：目标已换 → 丢弃陈旧回调
        if (cur.uri != uri) return
        avs3Decode = cur.copy(percent = percent.coerceIn(0, 100))
    }

    /** 解码完成（写缓存后调用）。与目标不匹配的回调丢弃。 */
    fun onAvs3Done(uri: String) {
        val cur = avs3Decode as? Avs3Decode.Running ?: return
        if (cur.uri != uri) return
        avs3Decode = Avs3Decode.Idle
    }

    /** 解码失败。 */
    fun onAvs3Failed(uri: String, message: String) {
        val cur = avs3Decode
        if (cur is Avs3Decode.Running && cur.uri != uri) return
        avs3Decode = Avs3Decode.Failed(uri, message)
    }

    /** 清除失败态（UI 展示过后调）。 */
    fun clearAvs3Fail() {
        if (avs3Decode is Avs3Decode.Failed) avs3Decode = Avs3Decode.Idle
    }

    /** 取消当前解码（用户主动取消 / 退出播放）。 */
    fun cancelAvs3Decode() {
        avs3Decode = Avs3Decode.Idle
    }

    /** 当前是否正在为 [uri] 解码（用于避免重复发起）。 */
    fun isDecodingFor(uri: String): Boolean =
        (avs3Decode as? Avs3Decode.Running)?.uri == uri

    companion object {
        /**
         * 解码进度的**收尾阈值**：超过它就不再「切歌即取消」，而是让它跑完。
         *
         * ## 为何是 80%
         * 解码总共约 31 秒，80% = 还剩大约 6 秒。
         * 到这个程度还强行杀掉，等于把已烧的 25 秒 CPU 全丢掉；
         * 让它在后台跑完写进缓存，下次打开就是秒开。
         * 代价：新歌最多晚 6 秒开始解码（而不是立刻）。
         *
         * 反过来，如果才解了 20% 就切走，硬跑完还要再等 25 秒 —— 那才是真浪费。
         */
        const val AVS3_FINISH_THRESHOLD = 80
    }
}
