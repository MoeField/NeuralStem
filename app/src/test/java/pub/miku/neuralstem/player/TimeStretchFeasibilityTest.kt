package pub.miku.neuralstem.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ★多轨变速变调 —— **方案 1（输出端 Sonic）可行性验证**。
 *
 * ## 背景
 * 多轨播放是「单个 AudioTrack + 自研混音」（见 [StemMixer]），固定 1.0×。
 * 单轨走 ExoPlayer 的 `PlaybackParameters` + 原生 AudioTrack，多轨这条路用不了。
 *
 * ## 本测试要回答的问题
 * `SonicAudioProcessor` 能否**独立于 ExoPlayer**，直接吃我们混音好的 float 块？
 *
 * ## 前置事实（查源码确认，见 SonicAudioProcessor 的 configure 方法）
 * configure() 只在 encoding 既不是 PCM_16BIT 也不是 PCM_FLOAT 时才抛
 * UnhandledAudioFormatException ==> **float 是被支持的**。
 * 这一点与历史上 SoundTouch 的失败原因不同：
 * SoundTouch 死在设备解码器 `getFormatSupport(PCM_FLOAT)=2`（解不出 float），
 * 而多轨这里 PCM 已经在内存里是 float，**不经过解码器**。
 *
 * ## 通过标准
 * 1. 纯 JVM（无 Android framework）能构造 + configure + 处理，不抛异常
 * 2. 2.0x 时输出帧数约为输入帧数的一半
 * 3. 1.0x 时 `isActive()==false`（免开销旁路）
 * 4. pitch 单独改变时（speed=1）输出帧数与输入基本一致（音高不变时长）
 */
@UnstableApi
class TimeStretchFeasibilityTest {

    private val sampleRate = 44100
    private val channels = 2

    /** 生成一段 float 正弦波 PCM（交织），返回 DirectByteBuffer。 */
    private fun tone(frames: Int, hz: Double = 440.0): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (0.5 * Math.sin(2.0 * Math.PI * hz * i / sampleRate)).toFloat()
            repeat(channels) { buf.putFloat(v) }
        }
        buf.flip()
        return buf
    }

    private fun audioFormat(): AudioFormat =
        AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT)

    /** 跑完整管线，返回输出总帧数。 */
    private fun runSonic(
        inputFrames: Int,
        speed: Float,
        pitch: Float,
    ): Long {
        val proc = SonicAudioProcessor()
        proc.setSpeed(speed)
        proc.setPitch(pitch)
        proc.configure(audioFormat())
        // ★media3 1.11.1：无参 flush() 已废弃，且默认实现直接抛 IllegalStateException
        //   （"AudioProcessor must implement at least one #flush() overload"）。
        //   必须用 flush(StreamMetadata)。
        proc.flush(StreamMetadata.DEFAULT)

        var outBytes = 0L
        val inBuf = tone(inputFrames)

        // 分块喂，模拟 StemMixer 的 CHUNK_FRAMES 节奏
        val chunkBytes = 1024 * channels * 4
        while (inBuf.hasRemaining()) {
            val n = minOf(chunkBytes, inBuf.remaining())
            val slice = inBuf.slice().order(ByteOrder.nativeOrder())
            slice.limit(n)
            proc.queueInput(slice)
            inBuf.position(inBuf.position() + n)
            while (true) {
                val out = proc.getOutput()
                if (!out.hasRemaining()) break
                outBytes += out.remaining()
            }
        }
        proc.queueEndOfStream()
        while (true) {
            val out = proc.getOutput()
            if (!out.hasRemaining()) break
            outBytes += out.remaining()
        }
        return outBytes / (channels * 4)
    }

    @Test
    fun `1 JVM 环境能构造并处理 float PCM 不抛异常`() {
        val out = runSonic(inputFrames = 44100, speed = 1.5f, pitch = 1f)
        assertTrue("应产出数据，实际 $out 帧", out > 0)
    }

    @Test
    fun `2 speed 2倍时输出帧数约为输入一半`() {
        val inFrames = 44100
        val out = runSonic(inFrames, speed = 2f, pitch = 1f)
        val ratio = out.toDouble() / inFrames
        // Sonic 有内部攒帧延迟，允许 +/-15% 误差
        assertTrue("2.0x 时应约 0.5，实际 $ratio（out=$out / in=$inFrames）", ratio in 0.42..0.58)
    }

    @Test
    fun `3 speed 0点5倍时输出帧数约为输入两倍`() {
        val inFrames = 44100
        val out = runSonic(inFrames, speed = 0.5f, pitch = 1f)
        val ratio = out.toDouble() / inFrames
        assertTrue("0.5x 时应约 2.0，实际 $ratio（out=$out / in=$inFrames）", ratio in 1.7..2.3)
    }

    @Test
    fun `4 默认参数时 isActive 为 false 可免开销旁路`() {
        val proc = SonicAudioProcessor()
        proc.configure(audioFormat())
        proc.flush(StreamMetadata.DEFAULT)
        assertEquals("speed=1 pitch=1 时应旁路", false, proc.isActive())
    }

    @Test
    fun `5 只改音高时输出时长基本不变`() {
        val inFrames = 44100
        val out = runSonic(inFrames, speed = 1f, pitch = 1.5f)
        val ratio = out.toDouble() / inFrames
        assertTrue("仅改音高时长度应约 1.0，实际 $ratio", ratio in 0.85..1.15)
    }

    @Test
    fun `6 16bit 编码同样被支持 作为回退路线`() {
        val proc = SonicAudioProcessor()
        proc.setSpeed(1.5f)
        val fmt = AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        val outFmt = proc.configure(fmt)
        proc.flush(StreamMetadata.DEFAULT)
        assertEquals("16bit 应被接受", C.ENCODING_PCM_16BIT, outFmt.encoding)
    }
}
