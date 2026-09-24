package pub.miku.neuralstem.player

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * 一个“带通均衡器”的抽象接口（阶段 M3.5 / T1）。
 *
 * 抽取目的：[EqController] 原本直接 `new` [AudioEq]（内部触碰真实硬件
 * `android.media.audiofx.Equalizer`），导致**多轨同步/广播逻辑无法在 JVM 上单测**。
 * 有了此接口，[EqController] 可注入假实现（记录调用），从而验证：
 * - 参数写入是否**广播到全部轨**
 * - 新轨挂载时是否**立即同步到当前曲线/开关**
 *
 * 生产代码仍用真实 [AudioEq]（默认工厂），行为不变。
 */
interface BandEq {
    val available: Boolean
    val bandCount: Int
    val bandFreqsHz: IntArray
    val minLevelMb: Short
    val maxLevelMb: Short
    fun setBandLevel(band: Int, levelMb: Short)
    fun getBandLevel(band: Int): Short
    fun setEnabled(on: Boolean)
    fun reset()
    fun release()
}

/**
 * 多段均衡器封装(阶段 M2 任务 D — spike 方案①:平台 `audiofx.Equalizer`)。
 *
 * ## 原理
 * `android.media.audiofx.Equalizer(priority, audioSession)` 把均衡器挂在
 * **指定的 AudioTrack 会话**上。ExoPlayer 通过 `Player.getAudioSessionId()`
 * 暴露它当前使用的会话 id；把它交给 Equalizer 即可让该 player 的声音经过 EQ。
 *
 * ## ⚠️ 多轨的坑(本任务的核心风险)
 * 我们是「N 个 ExoPlayer 实例」架构 —— **每个实例有自己的 audioSessionId**。
 * ⇒ 每个实例都要挂**一份** Equalizer，并且**参数必须同步**，
 *    否则两轨频响不一致(听感上像"跑调")。
 * 这与坑 37(音频焦点)、坑 35(音量单一出口)是**同一类**问题：
 * 多实例/进程级资源必须统一持有、统一计算。
 */
class AudioEq(
    /** 目标 AudioTrack 会话 id（来自 `player.audioSessionId`） */
    audioSessionId: Int,
    /** 会话 0 = 全局输出（调试用）；真机同步时应传 player 的 session */
    priority: Int = 0,
) : BandEq {
    private var effect: Equalizer? = null

    /** 硬件 EQ 的可用性(建不出来就为 false，调用方据此降级)。 */
    override val available: Boolean
    override val bandCount: Int
    override val bandFreqsHz: IntArray

    /** 增益范围（mB，毫贝） */
    override val minLevelMb: Short
    override val maxLevelMb: Short

    init {
        var ok = false
        var ranges = ShortArray(2)
        var freqs = IntArray(0)
        var bands = 0
        try {
            val eq = Equalizer(priority, audioSessionId)
            effect = eq
            bands = eq.numberOfBands.toInt()
            ranges = eq.bandLevelRange
            freqs = IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1000 }  // mHz → Hz
            ok = true
            Log.i(
                TAG,
                "Equalizer attached: session=$audioSessionId bands=$bands " +
                    "freqs=${freqs.toList()} levelRange=${ranges.toList()}",
            )
        } catch (t: Throwable) {
            // 某些设备/会话下 Equalizer 会抛 RuntimeException（不支持/被占用）
            Log.w(TAG, "Equalizer unavailable for session $audioSessionId", t)
        }
        available = ok
        bandCount = if (ok) bands else 0
        bandFreqsHz = freqs
        minLevelMb = if (ok) ranges[0] else 0
        maxLevelMb = if (ok) ranges[1] else 0
    }

    /** 设某段增益(mB)。越界自动 clamp 到硬件范围。 */
    override fun setBandLevel(band: Int, levelMb: Short) {
        val eq = effect ?: return
        if (band !in 0 until bandCount) return
        val clamped = levelMb.coerceIn(minLevelMb, maxLevelMb)
        runCatching { eq.setBandLevel(band.toShort(), clamped) }
            .onFailure { Log.w(TAG, "setBandLevel($band,$clamped) failed", it) }
    }

    /** 读回某段增益(mB)，用于验证写入生效。 */
    override fun getBandLevel(band: Int): Short {
        val eq = effect ?: return 0
        return runCatching { eq.getBandLevel(band.toShort()) }.getOrDefault(0)
    }

    /** 整体开关。 */
    override fun setEnabled(on: Boolean) {
        runCatching { effect?.enabled = on }
            .onFailure { Log.w(TAG, "setEnabled($on) failed", it) }
    }

    /** 全部置零（复位）。 */
    override fun reset() {
        for (b in 0 until bandCount) setBandLevel(b, 0)
    }

    /** 应用一个简单的「V 形/低切」测试曲线，用于 spike 听感与频响验证。 */
    fun applyTestCurve() {
        if (!available) return
        // 低频 +6dB、中频 -6dB、高频 +6dB（各段按位置线性近似）
        for (b in 0 until bandCount) {
            val f = bandFreqsHz.getOrElse(b) { 1000 }
            val level = when {
                f < 300 -> 600      // +6 dB
                f < 3000 -> -600    // -6 dB
                else -> 600         // +6 dB
            }
            setBandLevel(b, level.toShort())
        }
    }

    override fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    companion object {
        const val TAG = "AudioEq"
    }
}
