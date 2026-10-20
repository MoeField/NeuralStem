package pub.miku.neuralstem.spike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ★G-3：「边分离边播」会话的**进程级单例**。
 *
 * ## 为什么单例
 * 分离（[SegmentedSeparator]）与播放（`PlaybackService` 里的 ExoPlayer）在**同一进程**。
 * 本类持有两个 [StemPcmRingBuffer]，两边直接共享 → **零跨进程/跨 Binder 大数据传输**。
 * （`PlaybackService` 只需收到一个 trackId 即可，通过 [get] 拿到同一份 buffer。）
 *
 * ## 生命周期
 * - [begin]：开始一次边分离会话（新建 buffers，清空旧状态）。
 * - [complete]/[abort]：分离结束或失败 → 关闭 buffers（唤醒读端 → EOF）。
 * - 一个时刻只有**一个**活跃会话（再次 [begin] 会先 [abort] 旧的）。
 *
 * ## 状态
 * [state] 供 UI/Service 观察进度与“首段就绪”（首段就绪 = 可以切到分离结果播放）。
 */
class EdgeSeparationSession private constructor() {

    /** 会话状态。 */
    sealed interface State {
        data object Idle : State

        /** 正在准备（建模型/解码输入）。 */
        data object Preparing : State

        /** 已产出首段 → **可以切轨播放**。 */
        data class FirstSegmentReady(val percent: Int) : State

        /** 继续产出中（在 FirstSegmentReady 之后）。 */
        data class Running(val percent: Int) : State

        data object Done : State
        data class Failed(val message: String) : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // ==================== ★边播闸门（Gate）====================
    //
    // ## 为何需要
    // 文件模式下 stem 文件是**预分配的整曲长度**，未分离区是静音。
    // 旧逻辑在“首段就绪”时就切轨 → 播 4s 后就掉进静音区 → 用户听到“放一小段就没声”。
    // 闸门用 [writtenUpToFrame]（水位线）判断“当前播放位置到底有没有数据”。
    //
    // ## 为何与 [State] 分开
    // [State] 回答“**分离算到哪**”（UI 进度），Gate 回答“**播放能不能走**”。
    // 两者生命周期相同但含义正交；混在一个枚举里会让每个分支都要考虑无关维度。
    //
    // ## 滞环（hysteresis）
    // 起播/恢复用 [LEAD_FRAMES]，暂停用 [SAFETY_FRAMES]，两者**不相等**：
    // 单一阈值会让 play/pause 在边界上反复翻转（每 100ms 一次），比静音更难受。

    /** 播放闸门状态。 */
    sealed interface Gate {
        /** 无会话。 */
        data object Idle : Gate

        /** 分离还没领先够（或刚 seek 到未分离区）→ **不要切轨 / 保持暂停**。 */
        data object Buffering : Gate

        /** 水位线领先够多 → 可以切轨、可以继续播。 */
        data object Playable : Gate

        /** 播放追上分离 → 已暂停等待（名字与 `StemTrace: read STARVED` 同义）。 */
        data object Starved : Gate

        /** 分离完成，水位线置满 → 可自由 seek。 */
        data object Finished : Gate
    }

    private val _gate = MutableStateFlow<Gate>(Gate.Idle)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    /**
     * 起播/恢复裕度：水位线需领先（播放位置 - startFrame）至少这么多帧。
     * 15s @44.1k —— 与速度簇无关的保守值，真机可调。
     */
    val leadFrames: Int get() = LEAD_FRAMES

    /**
     * 暂停门槛：播放位置距水位线不足这么多帧 → 暂停。
     * 比 [leadFrames] 小很多，避免抖动（见上方滞环说明）。
     */
    val safetyFrames: Int get() = SAFETY_FRAMES

    /**
     * 从 [startFrame] 起**已分离**的帧数（= 可安全播放的上界，相对 startFrame）。
     *
     * 注意下界为 0：`[0, startFrame)` 永远没数据（预分配静音，那段从未参与本次分离）。
     */
    val availableFrames: Int
        get() = (writtenUpToFrame - startFrame).coerceAtLeast(0)

    /** 当前会话的 vocals 缓冲（无会话时为 null）。 */
    @Volatile var vocalsBuffer: StemPcmRingBuffer? = null
        private set

    /** 当前会话的 accompaniment 缓冲。 */
    @Volatile var accompBuffer: StemPcmRingBuffer? = null
        private set

    /** 会话开始时的音频采样率/声道（供 DataSource 合 WAV 头）。 */
    @Volatile var sampleRate: Int = 44_100
        private set
    @Volatile var channels: Int = 2
        private set

    /** 会话从哪个绝对帧开始（= 切轨时的播放位置 T，ms 由调用方换算）。 */
    @Volatile var startFrame: Int = 0
        private set

    /** 整段音频总帧数（用于合成 WAV 头的预测长度）；0=未知。 */
    @Volatile var totalFrames: Int = 0
        private set

    // ==================== ★G-3-重构：文件模式（随机写） ====================

    /**
     * 文件模式下的两个 stem 文件（完整曲长，未写区为静音）；null = 用旧的 ring buffer 模式。
     *
     * 为何要做这个模式：旧方案只落盘 `[startFrame, 曲尾)` → 导致
     * “分离完跳回起点 / 不含前面部分 / 波形差一截”。
     * 文件模式下缓存文件从头就是完整曲长，分离按绝对位置填充 → 上述问题消失。
     */
    @Volatile var vocalsFile: File? = null
        private set
    @Volatile var accompFile: File? = null
        private set

    /**
     * ★水位线：文件模式下**已连续写到的绝对帧号**（不含）。
     *
     * 供 [pub.miku.neuralstem.player.StemPcmDataSource] 判断“该位置可不可以读”。
     * 分离完成时置为 [totalFrames]。
     *
     * 为何用 volatile 而不是 StateFlow：播放器在高频 read() 里要读它，
     * 不能每次走 Flow 收集。
     */
    @Volatile var writtenUpToFrame: Int = 0
        private set

    /** 更新水位线（分离器每写完一块调一次）。只允许前进。 */
    fun updateWaterMark(frame: Int) {
        if (frame > writtenUpToFrame) writtenUpToFrame = frame
        advanceGate()
    }

    // ==================== ★闸门：状态转移 ====================

    /**
     * 水位线前进后重算闸门（只处理 **Buffering → Playable**）。
     *
     * 为何只处理这一条：其余转移（Playable→Starved、Starved→Playable）
     * 依赖**播放位置**，只能由播放侧主动报告（见 [onPlaybackTick]）。
     * 分离线程不知道播到哪，硬要推断会引入双向依赖。
     */
    private fun advanceGate() {
        if (_gate.value is Gate.Buffering && availableFrames >= LEAD_FRAMES) {
            _gate.value = Gate.Playable
            Log.i(TAG, "gate: Buffering → Playable (available=${availableFrames}f " +
                "= ${availableFrames * 1000 / sampleRate.coerceAtLeast(1)}ms)")
        }
    }

    /**
     * ★播放侧心跳：每 ~100ms 调一次，携带**当前播放位置**（相对 startFrame 的帧号）。
     *
     * 滞环逻辑：
     * - `Playable → Starved`：`playedFrames + SAFETY >= available`（快追上水位线了）
     * - `Starved → Playable`：`available - playedFrames >= LEAD`（又领先够了）
     *
     * 在 `Finished` / `Buffering` / `Idle` 下一律不动作
     * （`Buffering` 时播放器还没切到 stem，没有意义）。
     *
     * @param playedFrames 播放到的位置，**相对 [startFrame]**（可负 = 在起点之前，理论上不发生）
     */
    fun onPlaybackTick(playedFrames: Int) {
        val avail: Int = availableFrames
        when (_gate.value) {
            is Gate.Playable -> {
                if (playedFrames + SAFETY_FRAMES >= avail) {
                    _gate.value = Gate.Starved
                    Log.i(TAG, "gate: Playable → Starved (played=${playedFrames}f avail=${avail}f)")
                }
            }
            is Gate.Starved -> {
                if (avail - playedFrames >= LEAD_FRAMES) {
                    _gate.value = Gate.Playable
                    Log.i(TAG, "gate: Starved → Playable (played=${playedFrames}f avail=${avail}f)")
                }
            }
            else -> Unit
        }
    }

    /**
     * ★seek 后立即重算（不能等 100ms 心跳——seek 是跳变，晚一个 tick 就是一段静音）。
     *
     * - 目标在水位线**之外** → 直接 `Starved`（没数据，只能等）
     * - 目标在水位线**之内**且领先够 → `Playable`
     * - 中间灰区 → **不动**（保持当前态，避免抖动）
     */
    fun onSeekTo(playedFrames: Int) {
        if (_gate.value is Gate.Finished || _gate.value is Gate.Idle) return
        val avail = availableFrames
        when {
            playedFrames + SAFETY_FRAMES >= avail -> {
                if (_gate.value !is Gate.Starved) {
                    _gate.value = Gate.Starved
                    Log.i(TAG, "gate: seek → Starved (target=${playedFrames}f avail=${avail}f)")
                }
            }
            avail - playedFrames >= LEAD_FRAMES -> {
                if (_gate.value !is Gate.Playable) {
                    _gate.value = Gate.Playable
                    Log.i(TAG, "gate: seek → Playable (target=${playedFrames}f avail=${avail}f)")
                }
            }
            else -> Unit
        }
        // ★灰区（2026-10-20）：目标落在 [0, startFrame) —— 本次分离**从未处理过**这段，
        //   文件里它是预分配静音，且**永远不会**被填。
        //   不暂停（等也等不到），但必须告诉用户“为什么没声”。
        val inGray = playedFrames < 0
        if (seekInGrayZone != inGray) {
            seekInGrayZone = inGray
            if (inGray) {
                Log.i(TAG, "gate: seek 到分离起点之前 (target=${playedFrames}f) → 灰区（永不填充）")
            }
        }
    }

    /**
     * ★灰区（2026-10-20）：播放位置落在**本次分离起点之前**（`[0, startFrame)`）。
     *
     * ## 为何单独一个标志（而不复用 Gate）
     * 那段数据**永远不会有**（分离从 startFrame 起）→ 进 `Starved` 会让播放器暂停等一个
     * 永远不会到来的水位线，用户会以为卡死。而它也不需要暂停（静音是正确结果）。
     * ⇒ 用独立布尔标志，UI 据此显示“此段未参与本次分离”。
     */
    @Volatile
    var seekInGrayZone: Boolean = false
        private set

    /** 分离起点（ms），供 UI 提示“从 X:XX 起”。 */
    val startMs: Long get() = startFrame.toLong() * 1000L / sampleRate.coerceAtLeast(1)

    // ==================== ★G-3-ux2：边分离边播的**实时波形** ====================

    /**
     * 包络点密度（点/秒）。必须与 `WaveformExtractor.POINTS_PER_SECOND` 一致（20ms/点）。
     */
    val envelopePointsPerSecond: Int get() = 50

    /**
     * 两轨的包络累加器（[0]=vocals [1]=accompaniment）。
     *
     * 为何用可变数组 + 水位线，而不是 StateFlow<List>：
     * 分离器高频（每块 ~1s）写入，UI 只在 draw 阶段读；
     * 直接共享 FloatArray 可避免频繁重建列表/触发重组。
     */
    private val envMin = arrayOf(FloatArray(0), FloatArray(0))
    private val envMax = arrayOf(FloatArray(0), FloatArray(0))

    /** 各轨包络已填充到的点数（= 可安全读取的长度）。 */
    @Volatile private var envMinPoints = 0
    @Volatile private var envMaxPoints = 0

    /**
     * 写入一段包络（由 `SegmentedSeparator.onEnvelope` 调用）。
     *
     * @param stemIndex 0=vocals 1=accompaniment
     * @param absFrame 本段在整曲中的绝对起始帧
     * @param min / max 本段的包络点
     * @param pointCount 点数
     */
    @Synchronized
    fun appendEnvelope(stemIndex: Int, absFrame: Int, min: FloatArray, max: FloatArray, pointCount: Int) {
        if (stemIndex !in 0..1 || pointCount <= 0) return
        val fpp = (sampleRate / envelopePointsPerSecond).coerceAtLeast(1)
        val p0 = absFrame / fpp
        val need = p0 + pointCount
        // 扩容（倍增，避免频繁分配）
        if (envMin[stemIndex].size < need) {
            var cap = maxOf(envMin[stemIndex].size, 1024)
            while (cap < need) cap *= 2
            envMin[stemIndex] = envMin[stemIndex].copyOf(cap)
            envMax[stemIndex] = envMax[stemIndex].copyOf(cap)
        }
        System.arraycopy(min, 0, envMin[stemIndex], p0, pointCount)
        System.arraycopy(max, 0, envMax[stemIndex], p0, pointCount)
        val written = need
        if (stemIndex == 0) {
            if (written > envMinPoints) envMinPoints = written
        } else {
            if (written > envMaxPoints) envMaxPoints = written
        }
        // ★诊断：包络写入（看波形不更新时是否根本没进这里）
        if (need % 200 < pointCount) {
            Log.i(TAG, "envelope: stem=$stemIndex absFrame=$absFrame n=$pointCount → points=$need")
        }
    }

    /** 取某轨到目前为止的包络（min/max 副本；未分离区为 0）。 */
    @Synchronized
    fun snapshotEnvelope(stemIndex: Int): Pair<FloatArray, FloatArray>? {
        if (stemIndex !in 0..1) return null
        val n = if (stemIndex == 0) envMinPoints else envMaxPoints
        if (n <= 0) return null
        val a = envMin[stemIndex]
        val b = envMax[stemIndex]
        val len = minOf(n, a.size, b.size)
        return a.copyOf(len) to b.copyOf(len)
    }

    /** 清空包络（新会话开始时）。 */
    @Synchronized
    private fun resetEnvelopes() {
        envMin[0] = FloatArray(0); envMax[0] = FloatArray(0)
        envMin[1] = FloatArray(0); envMax[1] = FloatArray(0)
        envMinPoints = 0; envMaxPoints = 0
    }

    /** 是否首段已就绪。 */
    val firstSegmentReady: Boolean
        get() = _state.value is State.FirstSegmentReady ||
            _state.value is State.Running ||
            _state.value is State.Done

    /**
     * 开始一次会话。
     * 若已有活跃会话，先 [abort]。
     *
     * @param capacityFrames 每个 stem 缓冲容量（默认 60s @44.1k）；仅 ring buffer 模式用。
     */
    fun begin(
        startFrame: Int,
        sampleRate: Int,
        channels: Int,
        totalFrames: Int = 0,
        capacityFrames: Int = StemPcmRingBuffer.DEFAULT_CAPACITY_FRAMES,
    ) {
        abort()
        this.startFrame = startFrame.coerceAtLeast(0)
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalFrames = totalFrames
        vocalsBuffer = StemPcmRingBuffer(channels, capacityFrames)
        accompBuffer = StemPcmRingBuffer(channels, capacityFrames)
        resetEnvelopes()
        _state.value = State.Preparing
        // ★闸门：ring buffer 模式下数据不足会**阻塞等待**（不是静音），
        //   不需要裕度 → 直接 Playable（保持旧行为）。
        _gate.value = Gate.Playable
        Log.i(TAG, "begin: startFrame=$startFrame sr=$sampleRate ch=$channels cap=$capacityFrames")
    }

    /**
     * ★G-3-重构：开始一次**文件模式**会话（完整曲长随机写）。
     *
     * 与 [begin] 的区别：不建 ring buffer，而是用两个完整曲长的 stem 文件；
     * 播放器直接读文件，并按 [writtenUpToFrame] 判断可播范围。
     */
    fun beginFiles(
        startFrame: Int,
        sampleRate: Int,
        channels: Int,
        totalFrames: Int,
        vocalsFile: File,
        accompFile: File,
    ) {
        abort()
        this.startFrame = startFrame.coerceAtLeast(0)
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalFrames = totalFrames
        this.vocalsFile = vocalsFile
        this.accompFile = accompFile
        writtenUpToFrame = 0
        resetEnvelopes()
        _state.value = State.Preparing
        // ★闸门：新会话从 Buffering 开始（等水位线领先够才允许切轨）。
        _gate.value = Gate.Buffering
        Log.i(TAG, "beginFiles: startFrame=$startFrame sr=$sampleRate ch=$channels " +
            "total=$totalFrames vf=${vocalsFile.name} af=${accompFile.name}")
    }

    /** 首段产出（由分离开关回调）。幂等：只在 Preparing 时切到 FirstSegmentReady。 */
    fun markFirstSegmentReady(percent: Int) {
        if (_state.value is State.Preparing) {
            _state.value = State.FirstSegmentReady(percent)
            Log.i(TAG, "first segment ready @$percent%")
        }
    }

    /** 进度更新（首段就绪后 → Running）。 */
    fun updateProgress(percent: Int) {
        when (_state.value) {
            is State.Preparing -> _state.value = State.FirstSegmentReady(percent)
            is State.FirstSegmentReady, is State.Running -> _state.value = State.Running(percent)
            else -> Unit
        }
    }

    /** 分离完成（buffers 关闭 → 读端 EOF；但 buffer 引用保留供播放读到末尾）。 */
    fun complete() {
        vocalsBuffer?.close()
        accompBuffer?.close()
        // ★文件模式：水位线置满 → 全曲可播可 seek。
        if (vocalsFile != null && totalFrames > 0) writtenUpToFrame = totalFrames
        _state.value = State.Done
        // ★闸门：分离完成 → 解除一切限制（任意 seek 合法）。
        _gate.value = Gate.Finished
        // ★诊断：buffer 引用仍保留（非 null），播放器可读到 EOF。
        Log.i(
            TAG,
            "complete: buffers closed, refs kept " +
                "(vWritten=${vocalsBuffer?.totalWrittenFrames} aWritten=${accompBuffer?.totalWrittenFrames} " +
                "fileMode=${vocalsFile != null} waterMark=$writtenUpToFrame/$totalFrames)",
        )
    }

    /** 失败。 */
    fun fail(message: String) {
        vocalsBuffer?.close()
        accompBuffer?.close()
        _state.value = State.Failed(message)
        // ★闸门：会话失效 → 回 Idle（播放侧据此停止闸门监控）。
        _gate.value = Gate.Idle
        Log.w(TAG, "failed: $message (buffers closed, refs kept)")
    }

    /** 取消/中止：关闭 buffers 并清空。 */
    fun abort() {
        val had = vocalsBuffer != null || accompBuffer != null || vocalsFile != null
        vocalsBuffer?.close()
        accompBuffer?.close()
        vocalsBuffer = null
        accompBuffer = null
        // ★文件模式：保留文件引用（供上层清缓存时参考），但清空水位线。
        //   文件本身不删（若已写入部分数据，可能对用户仍有价值；由缓存清理统一管理）。
        writtenUpToFrame = 0
        // ★闸门：中止 → Idle。
        _gate.value = Gate.Idle
        seekInGrayZone = false
        // ★诊断：buffer 置 null —— 若播放器此时仍持 stem:// uri，下一次 open 会抛 IOException。
        Log.i(TAG, "abort: buffers→null (had=$had) fileMode=${vocalsFile != null} state=${_state.value}")
        if (had) {
            _state.value = State.Cancelled
            Log.i(TAG, "abort")
        } else {
            _state.value = State.Idle
        }
    }

    companion object {
        private const val TAG = "EdgeSession"

        /** 基于 44.1k 的帧数常量（各采样率下按毫秒换算即可，精度足够）。 */
        private const val SR_REF = 44_100

        /**
         * 起播/恢复裕度：水位线需领先播放位置 15s。
         *
         * 为何是 15s：首块的块长是 4s→8s→16s→30s 递增，15s 足以跨过两次切块
         * （>4+8），真机上分离远快于播放时一次就吃掉；模型慢时最多多等一轮。
         */
        private const val LEAD_FRAMES = 15 * SR_REF

        /** 暂停门槛 2s：远小于 [LEAD_FRAMES]，构成滞环，避免 play/pause 抖动。 */
        private const val SAFETY_FRAMES = 2 * SR_REF

        @Volatile
        private var instance: EdgeSeparationSession? = null

        fun get(): EdgeSeparationSession =
            instance ?: synchronized(this) {
                instance ?: EdgeSeparationSession().also { instance = it }
            }
    }
}
