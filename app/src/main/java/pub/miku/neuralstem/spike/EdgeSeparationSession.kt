package pub.miku.neuralstem.spike

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** 是否首段已就绪。 */
    val firstSegmentReady: Boolean
        get() = _state.value is State.FirstSegmentReady ||
            _state.value is State.Running ||
            _state.value is State.Done

    /**
     * 开始一次会话：新建两个 ring buffer。
     * 若已有活跃会话，先 [abort]。
     *
     * @param capacityFrames 每个 stem 缓冲容量（默认 60s @44.1k）。
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
        _state.value = State.Preparing
        Log.i(TAG, "begin: startFrame=$startFrame sr=$sampleRate ch=$channels cap=$capacityFrames")
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
        _state.value = State.Done
        Log.i(TAG, "complete")
    }

    /** 失败。 */
    fun fail(message: String) {
        vocalsBuffer?.close()
        accompBuffer?.close()
        _state.value = State.Failed(message)
        Log.w(TAG, "failed: $message")
    }

    /** 取消/中止：关闭 buffers 并清空。 */
    fun abort() {
        val had = vocalsBuffer != null || accompBuffer != null
        vocalsBuffer?.close()
        accompBuffer?.close()
        vocalsBuffer = null
        accompBuffer = null
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
