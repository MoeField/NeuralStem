package pub.miku.neuralstem.player

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
    data class Running(val percent: Int) : SeparationTask
    data class Done(val cached: Boolean) : SeparationTask
    data class Failed(val message: String) : SeparationTask
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

    var eq: EqState = EqState.Unsupported
        private set

    var separation: SeparationTask = SeparationTask.Idle
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

    fun startSeparation() {
        separation = SeparationTask.Running(0)
    }

    fun onSeparationProgress(percent: Int) {
        if (separation is SeparationTask.Running) separation = SeparationTask.Running(percent)
    }

    fun onSeparationDone(cached: Boolean) {
        separation = SeparationTask.Done(cached)
    }

    fun onSeparationFailed(message: String) {
        separation = SeparationTask.Failed(message)
    }

    /**
     * **消费**一次 [SeparationTask.Done]（返回并清空）；无则返回 `null`。
     *
     * UI 只能经此拿到结果 → 天然“只响应 `Running→Done` 的**转换**、不响应遗留态”，
     * 从而根治“一进分离页就误触发自动切多轨”。
     */
    fun consumeSeparationDone(): SeparationTask.Done? {
        val d = separation as? SeparationTask.Done ?: return null
        separation = SeparationTask.Idle
        return d
    }
}
