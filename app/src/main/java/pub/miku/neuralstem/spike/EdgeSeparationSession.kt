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
    }

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

        @Volatile
        private var instance: EdgeSeparationSession? = null

        fun get(): EdgeSeparationSession =
            instance ?: synchronized(this) {
                instance ?: EdgeSeparationSession().also { instance = it }
            }
    }
}
