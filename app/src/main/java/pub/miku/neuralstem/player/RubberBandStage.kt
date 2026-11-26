package pub.miku.neuralstem.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.4.0：Rubber Band 渲染阶段（相位声码器 R3）。
 *
 * 与 [SonicStage] 同接口，供 `StemMixer` 在 `-Pwith_rubberband=true` 构建中替换使用。
 *
 * ## 参数映射
 * - `speed`（播放速度）→ RB 的 `time_ratio = 1/speed`（输出/输入时长比）
 * - `pitchRate`（音高倍率）→ RB 的 `pitch_scale`
 * - 位置：输出帧率 = 输入帧率 × speed → `源帧 = anchor + 输出帧 / speed`
 *
 * ## 线程
 * 非线程安全；只允许渲染线程调用（与 [SonicStage] 一致）。
 * 许可：依赖的 RB 库为 GPL-3.0-or-later。
 */
internal class RubberBandStage(
    private val sampleRate: Int,
    private val channels: Int,
    private val maxFramesPerCall: Int = 4096,
    private val options: Int = RubberBandNative.PROCESS_REAL_TIME or
        RubberBandNative.PITCH_HIGH_QUALITY or
        RubberBandNative.WINDOW_LONG,
) : RenderStage {

    private val handle: Long = RubberBandNative.create(
        sampleRate,
        channels,
        options,
        1f,
        1f,
    )

    private var speed = 1f
    private var pitchRate = 1f
    private var anchorSrcFrame = 0L
    private var outFrames = 0L
    private var eosSent = false
    private var ended = false

    /** 复用 direct buffer，避免渲染线程每块分配（与 SonicStage 的常驻 buffer 同理）。 */
    private val outBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(maxFramesPerCall * channels * 4)
            .order(ByteOrder.nativeOrder())
    private val retrieveBuf = FloatArray(maxFramesPerCall * channels)

    override val isActive: Boolean
        get() = handle != 0L && (speed != 1f || pitchRate != 1f)

    override val isEnded: Boolean get() = ended

    override val writtenBytes: Long get() = outFrames * channels * 4

    override fun setParams(speed: Float, pitchRate: Float) {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        val p = if (pitchRate.isFinite() && pitchRate > 0f) pitchRate else 1f
        if (s == this.speed && p == this.pitchRate) return
        this.speed = s
        this.pitchRate = p
        if (handle == 0L) return
        RubberBandNative.setTimeRatio(handle, 1f / s)
        RubberBandNative.setPitchScale(handle, p)
    }

    /** RB 实时接口对参数切换内部平滑，不需要像 Sonic 那样回退源读头。 */
    override fun applyParams(srcFrame: Long): Long = srcFrame

    override fun process(input: FloatArray, frames: Int, sink: (ByteBuffer, Int) -> Int): Long {
        if (!isActive || handle == 0L || frames <= 0) return 0L
        RubberBandNative.process(handle, input, frames, false)
        return drain(sink)
    }

    override fun finish(sink: (ByteBuffer, Int) -> Int) {
        if (!isActive || handle == 0L) return
        if (!eosSent) {
            RubberBandNative.process(handle, FloatArray(0), 0, true)
            eosSent = true
        }
        drain(sink)
        ended = true
    }

    override fun flushAt(sourceFrame: Long) {
        if (handle == 0L) return
        RubberBandNative.reset(handle)
        anchorSrcFrame = sourceFrame.coerceAtLeast(0L)
        outFrames = 0L
        eosSent = false
        ended = false
    }

    override fun positionFrames(): Long {
        if (!isActive) return anchorSrcFrame
        return anchorSrcFrame + (outFrames / speed).toLong()
    }

    private fun drain(sink: (ByteBuffer, Int) -> Int): Long {
        var written = 0L
        while (true) {
            val avail = RubberBandNative.available(handle)
            if (avail <= 0) break
            val n = minOf(avail, maxFramesPerCall)
            val got = RubberBandNative.retrieve(handle, retrieveBuf, n)
            if (got <= 0) break
            val bytes = got * channels * 4
            outBuffer.clear()
            outBuffer.asFloatBuffer().put(retrieveBuf, 0, got * channels)
            outBuffer.position(0).limit(bytes)
            val w = sink(outBuffer, bytes)
            if (w <= 0) break
            outFrames += (w / (channels * 4)).toLong()
            written += w
        }
        return written
    }
}
