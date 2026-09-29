package pub.miku.neuralstem.player

import android.util.Log

/**
 * 多实例 EQ 的统一出口(阶段 M2 任务 D)。
 *
 * ## 为什么需要(核心风险点)
 * 我们是「N 个 ExoPlayer 实例」架构(方案 A)。每个实例有**自己的 AudioTrack 会话**，
 * 因此每个实例都需要挂**一份** [AudioEq]。
 *
 * 若各挂各的、参数不同步 → 两轨频响不一致 → 听感异常(伴奏亮/人声闷)。
 * 这与坑 35(音量多个写入点互相覆盖)、坑 37(音频焦点多实例互踩)是**同一族**问题。
 *
 * ## 做法:收敛到单一出口
 * - 本类持有 `Map<trackId, AudioEq>`，**所有参数写入都经过本类**
 * - `setBandLevels(...)` 一次算好 → 广播给每条轨的 EQ(与 [MultiTrackController.applySoloMute] 同精神)
 * - 单轨模式也用同一套接口(只有一条轨)
 *
 * ## 生命周期
 * EQ 必须挂在**已建立的 AudioTrack 会话**上。ExoPlayer 在 prepare 前 sessionId 可能是 0；
 * 因此挂载时机应在 `STATE_READY` 之后(或至少 play 之后)。本类提供 `attach()` 供调用方
 * 在合适的时机挂载，并允许重复挂载(先释放旧的)。
 */
class EqController(
    /**
     * 创建 [BandEq] 的工厂（可按会话 id 生产）。默认 = 真实硬件 [AudioEq]。
     * 单测可注入假实现，以验证“广播/同步”逻辑（M3.5/T1）。
     */
    private val factory: (Int) -> BandEq = { AudioEq(it) },
) {

    /** cursor: 0=mB 级的各段目标增益；键为轨 id */
    private val eqs = LinkedHashMap<String, BandEq>()

    /** 当前目标曲线（mB 数组，按段下标）。全部 EQ 都同步到它。 */
    var targetLevels: ShortArray = ShortArray(0)
        private set

    private var enabled = false

    /** 已挂载的轨数(供 UI/日志)。 */
    val attachedCount: Int get() = eqs.size

    /** 硬件是否支持 EQ(至少一条轨挂成功)。 */
    val available: Boolean get() = eqs.values.any { it.available }

    /** 可选段数(取任一已挂载者；未挂载返回 0)。 */
    val bandCount: Int get() = eqs.values.firstOrNull()?.bandCount ?: 0

    /** 各段中心频率(Hz)。 */
    val bandFreqsHz: IntArray get() = eqs.values.firstOrNull()?.bandFreqsHz ?: IntArray(0)

    /** 硬件增益范围(mB)。 */
    val levelRangeMb: IntRange
        get() {
            val e = eqs.values.firstOrNull() ?: return 0..0
            return e.minLevelMb.toInt()..e.maxLevelMb.toInt()
        }

    /**
     * 为一条轨挂载 EQ。可重复调用(先释放旧的)。
     *
     * @param trackId 稳定标识("vocals"/"accompaniment"/"single")
     * @param audioSessionId 来自 `player.getAudioSessionId()`
     */
    fun attach(trackId: String, audioSessionId: Int) {
        eqs.remove(trackId)?.release()
        val eq = factory(audioSessionId)
        eqs[trackId] = eq
        // 新挂载的轨立即同步到当前曲线 + 开关状态，避免"两轨不一致"
        if (targetLevels.isNotEmpty()) applyTo(eq, targetLevels)
        eq.setEnabled(enabled)
        Log.i(
            TAG,
            "attach($trackId, session=$audioSessionId) available=${eq.available} " +
                "bands=${eq.bandCount} attachedNow=${eqs.size}",
        )
    }

    /** 移除一条轨(切歌/退出多轨时)。 */
    fun detach(trackId: String) {
        eqs.remove(trackId)?.release()
        Log.i(TAG, "detach($trackId) remaining=${eqs.size}")
    }

    fun detachAll() {
        eqs.values.forEach { it.release() }
        eqs.clear()
    }

    /**
     * 设定整条曲线（mB 数组）并**广播到所有轨**。
     * 这是唯一的写入出口(与坑 35 的教训一致)。
     */
    fun setBandLevels(levelsMb: ShortArray) {
        targetLevels = levelsMb.copyOf()
        eqs.values.forEach { applyTo(it, targetLevels) }
    }

    /** 设单段增益(便捷:基于当前曲线改一段)。 */
    fun setBandLevel(band: Int, levelMb: Short) {
        // ★修复：首次写入时 targetLevels 可能为空 → 按当前段数初始化，避免丢弃首次写入。
        if (targetLevels.isEmpty()) {
            val n = bandCount
            if (n == 0) return
            targetLevels = ShortArray(n)
        }
        if (band !in targetLevels.indices) return
        val copy = targetLevels.copyOf()
        copy[band] = levelMb
        setBandLevels(copy)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        eqs.values.forEach { it.setEnabled(on) }
        Log.i(TAG, "enabled=$on across ${eqs.size} track(s)")
    }

    fun isEnabled(): Boolean = enabled

    /** 复位为全零。 */
    fun reset() {
        if (targetLevels.isNotEmpty()) {
            setBandLevels(ShortArray(targetLevels.size))
        } else {
            eqs.values.forEach { it.reset() }
        }
    }

    /**
     * 默认曲线 —— 低频 **+3dB**、高频 **+3dB**（“微笑/V 形”），中频 0。
     * 仅在首次开启 EQ（用户尚未调过）时应用，避免“开启听着没变化”。
     * 依据各段中心频率判定：`f < 300` 或 `f ≥ 3000` → +300mB，其余 0。
     */
    fun defaultCurve() {
        val n = bandCount
        if (n == 0) return
        val levels = ShortArray(n) { b ->
            val f = bandFreqsHz.getOrElse(b) { 1000 }
            if (f < 300 || f >= 3000) 300 else 0
        }
        setBandLevels(levels)
        Log.i(TAG, "defaultCurve bands=$n set=${levels.toList()}")
    }

    /** 当前目标曲线是否全零（未调过）。 */
    fun isFlat(): Boolean = targetLevels.all { it == 0.toShort() }

    /** 读回首条轨的实际段增益(验证写入生效)。 */
    fun readBackLevels(): ShortArray {
        val e = eqs.values.firstOrNull() ?: return ShortArray(0)
        return ShortArray(e.bandCount) { e.getBandLevel(it) }
    }

    private fun applyTo(eq: BandEq, levels: ShortArray) {
        if (!eq.available) return
        for (b in 0 until eq.bandCount) {
            eq.setBandLevel(b, levels.getOrElse(b) { 0 })
        }
    }

    companion object {
        const val TAG = "EqCtl"
    }
}
