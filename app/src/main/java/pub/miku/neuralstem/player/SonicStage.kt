@file:OptIn(UnstableApi::class)

package pub.miku.neuralstem.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ★多轨变速变调的**输出端 Sonic 阶段**（2026-11-06，P0-3 第 1 步）。
 *
 * ## 定位
 * 把「混音后的 float 块 → Sonic → AudioTrack」这一段收敛成一个**纯 JVM** 类，
 * 不依赖 Android framework，可被单测覆盖。`StemMixer` 只在**渲染线程**调它。
 *
 * ## 与单轨的区别
 * 单轨走 ExoPlayer 的 `PlaybackParameters`（原生 AudioTrack 拉伸）；
 * 多轨只有一个 AudioTrack 承载**混音结果**，只能自己做时间轴变换。
 * 三方案实证见 `docs/STEP_HISTORY.md`（方案 2/3 已排除）。
 *
 * ## 位置时钟（关键）
 * `StemMixer.framePos` 是**源读头**，不能再用 `write()/channels` 当媒体位置：
 * 变速后写入帧数 ≠ 媒体帧数。这里用两个计数器：
 * ```
 * anchorSrcFrame      最近一次 flush 时对应的源帧
 * outBytes            本次 flush 以来写进 AudioTrack 的字节
 * positionFrames() = anchorSrcFrame + getMediaDuration(outBytes)/bytesPerFrame
 * ```
 * `getMediaDuration` 是 media3 的现成 API（按实际处理比例换算，pitch 不影响时长）。
 *
 * ## 参数切换
 * Sonic 有内部延迟线；改参必须 `flush` 并**回退未处理输入**，否则会丢一小段
 * （≈31ms）或重复。`applyParams(srcFrame)` 返回修正后的源读头，调用方拿去继续读。
 *
 * ## 1.0× 旁路
 * `speed == 1 && pitch == 1` 时 `isActive == false`；调用方应走原路径，
 * 一次 Sonic 调用都不发生，行为与今天完全一致。
 *
 * ## 线程
 * **非线程安全**：所有方法只允许渲染线程调用；参数由 volatile 标量跨线程传入
 * （见 `StemMixer`）。
 */
class SonicStage(
    private val sampleRate: Int,
    private val channels: Int,
    /** 单次最大帧数（= StemMixer 的 CHUNK_FRAMES）。 */
    maxFramesPerCall: Int = 4096,
) {

    private val bytesPerFrame = channels * CODEC_BYTES

    private val processor = SonicAudioProcessor().apply {
        configure(AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT))
    }

    /** 常驻 direct buffer：避免每块分配（渲染线程上 GC 会造成 underrun）。 */
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(maxFramesPerCall * bytesPerFrame).order(ByteOrder.nativeOrder())
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()

    private var speed = 1f
    private var pitchRate = 1f
    private var dirty = false
    private var active = processor.isActive

    /** 本次 flush 以来累计 queueInput 的字节。 */
    private var queuedBytes = 0L
    /** 本次 flush 以来累计写出的字节。 */
    private var outBytes = 0L
    /** 上次 flush 时对应的源帧。 */
    private var anchorSrcFrame = 0L
    private var eosSent = false

    /** 当前是否真的需要经过 Sonic（1.0× 时为 false）。 */
    val isActive: Boolean get() = active

    /** 是否已经排空并结束（曲尾）。 */
    val isEnded: Boolean get() = processor.isEnded

    /**
     * 更新目标参数。真正生效要等 [applyParams]（渲染线程上）。
     * 重复设同值不会标脏。
     */
    fun setParams(speed: Float, pitchRate: Float) {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        val p = if (pitchRate.isFinite() && pitchRate > 0f) pitchRate else 1f
        if (s == this.speed && p == this.pitchRate) return
        this.speed = s
        this.pitchRate = p
        dirty = true
    }

    /**
     * 应用待生效参数（渲染线程）。
     *
     * @param srcFrame 当前源读头；返回**修正后**的源读头（回退了未处理的输入）。
     *   ⚠️ 返回后必须用返回值覆盖调用方的 srcFrame，并继续从该帧读。
     */
    fun applyParams(srcFrame: Long): Long {
        if (!dirty) return srcFrame
        dirty = false

        // flush 会丢掉 Sonic 内部尚未处理的输入（延迟线 + pending）→ 先算回退量。
        val pendingBytes = if (active) {
            (queuedBytes - processor.processedInputBytes).coerceAtLeast(0L)
        } else {
            0L
        }
        processor.setSpeed(speed)
        processor.setPitch(pitchRate)
        processor.flush(StreamMetadata.DEFAULT)
        active = processor.isActive

        val rewind = (pendingBytes / bytesPerFrame).toInt().coerceAtMost(MAX_REWIND_FRAMES)
        val newSrc = (srcFrame - rewind).coerceAtLeast(0L)
        anchorSrcFrame = newSrc
        queuedBytes = 0L
        outBytes = 0L
        eosSent = false
        return newSrc
    }

    /**
     * 喂一块混音结果，把 Sonic 的输出写进 [sink]。
     *
     * @param sink `(buffer, bytesToWrite) -> 实际写入字节数`；返回 <=0 视为写入失败。
     * @return 本次实际写出的字节数（0 = 暂时没有输出，调用方继续下一块即可）。
     */
    fun process(input: FloatArray, frames: Int, sink: (ByteBuffer, Int) -> Int): Long {
        if (!active) return 0L
        if (frames <= 0) return 0L

        // 先排空上一轮残留，再 queueInput —— AudioProcessor 要求输出消费完才可喂新输入。
        drain(sink)

        inputFloats.clear()
        inputFloats.put(input, 0, frames * channels)
        inputBuffer.position(0)
        inputBuffer.limit(frames * bytesPerFrame)
        queuedBytes += frames.toLong() * bytesPerFrame
        processor.queueInput(inputBuffer)

        return drain(sink)
    }

    /** 曲尾：通知 Sonic 结束并把延迟线里的尾巴排空。 */
    fun finish(sink: (ByteBuffer, Int) -> Int) {
        if (!active) return
        if (!eosSent) {
            processor.queueEndOfStream()
            eosSent = true
        }
        drain(sink)
    }

    /** seek 后重定位时钟（丢弃 Sonic 内部残留）。 */
    fun flushAt(sourceFrame: Long) {
        processor.flush(StreamMetadata.DEFAULT)
        anchorSrcFrame = sourceFrame.coerceAtLeast(0L)
        queuedBytes = 0L
        outBytes = 0L
        eosSent = false
    }

    /** 当前应对外报告的媒体帧位置（= 实际听到的位置，不含 AudioTrack 缓冲）。 */
    fun positionFrames(): Long {
        if (!active) return anchorSrcFrame
        if (outBytes <= 0L) return anchorSrcFrame
        return anchorSrcFrame + processor.getMediaDuration(outBytes) / bytesPerFrame
    }

    /** 本次 flush 以来写出的字节数（诊断用）。 */
    val writtenBytes: Long get() = outBytes

    private fun drain(sink: (ByteBuffer, Int) -> Int): Long {
        var written = 0L
        // ★每次 getOutput() 会**取走并清空** Sonic 的输出缓冲（下一次返回新数据），
        //   所以必须在同一个 buffer 上循环写完，绝不能“短写后重新 getOutput” —— 那会丢字节。
        val out = processor.getOutput()
        while (out.hasRemaining()) {
            val w = sink(out, out.remaining())
            if (w <= 0) break // 写入失败：上层会重建 AudioTrack，丢掉剩余字节可接受
            out.position(out.position() + w)
            outBytes += w
            written += w
        }
        return written
    }

    private companion object {
        const val CODEC_BYTES = 4 // ENCODING_PCM_FLOAT
        /** 改参最多回退 4096 帧（~93ms @44.1k），防御异常大的 pending。 */
        const val MAX_REWIND_FRAMES = 4096
    }
}
