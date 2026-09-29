package pub.miku.neuralstem.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 用 SoundTouch 做实时变速变调的 media3 [AudioProcessor]（R2/bug C 最小验证）。
 *
 * ## 目的
 * 替换 media3 内置 Sonic，避开“频繁改参→Sonic flush→重灌缓冲→饥饿→StuckPlayerDetector
 * →ERROR_CODE_TIMEOUT”。SoundTouch 改参**不重建、不重置内部缓冲**（源码确证，见
 * `research_timescale.artifact.md`），因此不会饥饿。
 *
 * ## 采样格式
 * **始终 16-bit int（交错）**——`ENCODING_PCM_16BIT`，media3 永远 `FORMAT_SUPPORTED_DIRECTLY`
 * （float 路径已实测不可行：BF00A 与骁龙 8 Gen3 的 floatSupport 均为 2，见研究文档）。
 * 非 16-bit 输入直接拒绝（`UnhandledAudioFormatException`）。
 *
 * ## 线程契约
 * 所有方法在 media3 的 **audio 线程**调用，单线程使用，不加锁。
 *
 * ## 关键约束（务必遵守，源于一手调研）
 * 1. `onFlush()` **只 flush SoundTouch，绝不重建实例**（否则重蹈 Sonic 覆辙）。
 * 2. 运行期**不反复开关 AA filter**（`enableAAFilter()` 会 clear() 缓冲）。
 * 3. `queueInput()` 必须把输入 buffer **消费完**（position→limit），否则 media3 会重复喂。
 * 4. `queueInput()` 后可能 **没有输出**（SoundTouch 块处理），必须允许空输出，不能死等。
 */
@UnstableApi
class SoundTouchAudioProcessor : BaseAudioProcessor() {

    /** 当前速度 / 音高倍率（1.0 = 原始）。 */
    private var speed = 1f
    private var pitch = 1f

    /** 供 [SoundTouchAudioProcessorChain] 做时长映射用。 */
    val currentSpeed: Float get() = speed

    /** 当前配置对应的 native 实例（onConfigure 时创建，onReset 时释放）。 */
    private var native: SoundTouchNative? = null

    /** 复用的 16-bit 暂存（输入转换 + 输出收集）。 */
    private var scratch = ShortArray(0)

    /**
     * 运行期改参（media3 改 `PlaybackParameters` 时由 chain 调用）。
     * **不重建实例**，只下发到 SoundTouch。
     */
    fun setSpeedPitch(newSpeed: Float, newPitch: Float) {
        if (speed == newSpeed && pitch == newPitch) return
        speed = newSpeed
        pitch = newPitch
        native?.setTempo(newSpeed.toDouble())
        native?.setPitch(newPitch.toDouble())
    }

    override fun isActive(): Boolean = super.isActive()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // 仅接受 16-bit int PCM（media3 永远直通）
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // 输出格式与输入一致（soundTouch 不改变采样率/声道/编码）
        releaseNative()
        native = SoundTouchNative(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            .also {
                // 应用当前参数（避免重建后参数丢失）
                it.setTempo(speed.toDouble())
                it.setPitch(pitch.toDouble())
            }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val st = native ?: run {
            inputBuffer.position(inputBuffer.limit())  // 无实例：消费但丢弃
            return
        }

        val shortBuf = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val sampleCount = shortBuf.remaining()
        if (sampleCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        ensureScratch(sampleCount)
        shortBuf.get(scratch, 0, sampleCount)
        inputBuffer.position(inputBuffer.limit())  // ★ 必须消费完

        st.putSamples(scratch, 0, sampleCount)

        // 取出所有可用输出，写入 media3 的输出 buffer
        val ready = st.numSamples()
        if (ready <= 0) {
            // SoundTouch 块处理：本帧可能无输出，属正常
            return
        }
        val out = replaceOutputBuffer(ready * BYTES_PER_SAMPLE)
        while (st.numSamples() > 0) {
            val room = out.remaining() / BYTES_PER_SAMPLE
            if (room <= 0) break
            val got = st.receiveSamples(scratch, 0, minOf(room, scratch.size))
            if (got <= 0) break
            val sb = out.asShortBuffer()
            sb.put(scratch, 0, got)
            out.position(out.position() + got * BYTES_PER_SAMPLE)
        }
        out.flip()
    }

    override fun onQueueEndOfStream() {
        // 冲刷 SoundTouch 尾部残留 → 让剩余样本在 getOutput() 里读出
        val st = native ?: return
        st.flush()
        drainToOutput()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // ★ 只 flush，不重建实例（关键墙）
        native?.flush()
        scratch = ShortArray(0)
    }

    override fun onReset() {
        releaseNative()
        scratch = ShortArray(0)
    }

    // ---- 内部 ----

    private fun drainToOutput() {
        val st = native ?: return
        val ready = st.numSamples()
        if (ready <= 0) return
        val out = replaceOutputBuffer(ready * BYTES_PER_SAMPLE)
        while (st.numSamples() > 0) {
            val room = out.remaining() / BYTES_PER_SAMPLE
            if (room <= 0) break
            val got = st.receiveSamples(scratch, 0, minOf(room, scratch.size))
            if (got <= 0) break
            val sb = out.asShortBuffer()
            sb.put(scratch, 0, got)
            out.position(out.position() + got * BYTES_PER_SAMPLE)
        }
        out.flip()
    }

    private fun ensureScratch(minSize: Int) {
        if (scratch.size < minSize) {
            // 留些余量，避免每帧重分配
            scratch = ShortArray(maxOf(minSize, 4096))
        }
    }

    private fun releaseNative() {
        native?.close()
        native = null
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2  // 16-bit
    }
}
