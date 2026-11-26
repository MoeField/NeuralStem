package pub.miku.neuralstem.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer

/**
 * `StemMixer` 的音频输出抽象（v0.3.0 / P1-14）。
 *
 * 目的：把 `android.media.AudioTrack` 从混音器里隔离出来，使
 * 「write 失败 → 重建 → 继续」「曲尾收尾」等不变量能在 JVM 里用
 * [FakeAudioSink] 验证，而不需要真机/录制。
 *
 * 生产默认实现是 [AudioTrackSink]；测试注入 fake。
 */
interface AudioSink {
    /** 输出会话 id（EQ 挂载用）；0 = 不可用。 */
    val audioSessionId: Int

    /** 输出缓冲容量（帧），仅日志/诊断用。 */
    val frameCapacity: Int

    /** 确保进入 PLAYING；幂等。 */
    fun play()

    fun pause()

    fun flush()

    fun release()

    /** 写交织 float PCM；返回实际写入字节数，<0 = 失败。 */
    fun write(buf: FloatArray, len: Int): Int

    /** 写 Sonic 输出的字节缓冲；返回实际写入字节数，<0 = 失败。 */
    fun write(buf: ByteBuffer, len: Int): Int

    companion object {
        /**
         * 仅 debug/TEST_HOOKS：强制下一次 write 失败，验证重建恢复。
         * 由 [StemMixer.failNextWriteForTest] 转发，保持旧测试入口不变。
         */
        @Volatile
        var failNextWriteForTest: Boolean = false
    }
}

/**
 * 生产实现：一个 `AudioTrack`（MODE_STREAM + PCM_FLOAT）。
 *
 * 逻辑与原 `StemMixer.ensureTrack/writeBlock` 等价，只是搬到了独立类。
 */
class AudioTrackSink(
    private val sampleRate: Int,
    private val channels: Int,
    chunkFrames: Int,
) : AudioSink {

    private var track: AudioTrack? = null

    override val frameCapacity: Int

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(chunkFrames * channels * 4 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(
                        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                        else AudioFormat.CHANNEL_OUT_STEREO,
                    )
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        frameCapacity = minBuf / (channels * 4)
        Log.i(TAG, "AudioTrack created: buf=$minBuf bytes (${frameCapacity} frames)")
    }

    override val audioSessionId: Int get() = track?.audioSessionId ?: 0

    override fun play() {
        val t = track ?: return
        // ★必须真的进入 PLAYSTATE_PLAYING，否则 write() 会永远阻塞（真机实测）。
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { t.play() }.onFailure { Log.w(TAG, "AudioTrack.play() 失败", it) }
        }
    }

    override fun pause() {
        runCatching { track?.pause() }
    }

    override fun flush() {
        runCatching { track?.flush() }
    }

    override fun release() {
        val t = track
        track = null
        runCatching { t?.release() }
    }

    override fun write(buf: FloatArray, len: Int): Int {
        val t = track ?: return -1
        if (AudioSink.failNextWriteForTest) {
            AudioSink.failNextWriteForTest = false
            Log.w(TAG, "TEST: forced write failure")
            return -1
        }
        return t.write(buf, 0, len, AudioTrack.WRITE_BLOCKING)
    }

    override fun write(buf: ByteBuffer, len: Int): Int {
        val t = track ?: return -1
        if (AudioSink.failNextWriteForTest) {
            AudioSink.failNextWriteForTest = false
            Log.w(TAG, "TEST: forced write failure")
            return -1
        }
        return t.write(buf, len, AudioTrack.WRITE_BLOCKING)
    }

    companion object {
        private const val TAG = "AudioTrackSink"
    }
}
