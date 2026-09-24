package pub.miku.neuralstem.spike

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 分离输入的统一帧源(Task 4.1)。
 *
 * 背景:`StreamingSeparator` 原先把 `WavReader` 写死在构造参数里,导致
 * **只有 WAV 能分离**(而播放侧早已支持全格式)。本接口把「按帧读 PCM」抽出来,
 * 让两种来源都能喂进去:
 * - [WavFrameSource]    : WAV 直接复用 Task 0e 的 [WavReader] 流式按帧读(不驻留内存)
 * - [MemoryFrameSource] : 压缩格式(mp3/flac/aac)先解码成整曲 PCM 再按帧切片
 *
 * 采样率契约由 [Resampler] 保证统一到 44.1kHz(模型要求)。
 */
interface FrameSource : Closeable {
    val channels: Int
    val sampleRate: Int
    val totalFrames: Int

    /** 读 [frames] 帧(交织 float),从 [startFrame] 起;超末尾自动截断。 */
    fun readFrames(startFrame: Int, frames: Int): FloatArray
}

/** WAV:复用 Task 0e 的流式随机读,内存占用与文件长度无关。 */
class WavFrameSource(private val reader: WavReader) : FrameSource {
    override val channels: Int get() = reader.channels
    override val sampleRate: Int get() = reader.sampleRate
    override val totalFrames: Int get() = reader.totalFrames
    override fun readFrames(startFrame: Int, frames: Int): FloatArray =
        reader.readFrames(startFrame, frames)
    override fun close() = reader.close()
}

/**
 * 已解码到内存的 PCM(压缩格式走这条)。
 *
 * 内存账:5 分钟 44.1kHz 立体声 ≈ 110MB。6G 环境 + largeHeap 可承受;
 * 低配机优化(边解边喂块)留待后续,当前先打通功能。
 */
class MemoryFrameSource(
    private val samples: FloatArray,
    override val channels: Int,
    override val sampleRate: Int,
) : FrameSource {
    override val totalFrames: Int = samples.size / channels

    override fun readFrames(startFrame: Int, frames: Int): FloatArray {
        val avail = (totalFrames - startFrame).coerceAtLeast(0)
        val n = minOf(frames, avail)
        if (n <= 0) return FloatArray(0)
        val from = startFrame * channels
        return samples.copyOfRange(from, from + n * channels)
    }

    override fun close() { /* 内存由 GC 管理 */ }
}

/**
 * 线性插值重采样(Task 4.1)。
 *
 * 为什么需要:模型契约是 44.1kHz,而用户导入的 mp3 常见 48k/32k/22.05k。
 * 只在**采样率不匹配时**调用 —— 44.1k 输入零损失零开销(直接返回原数组)。
 *
 * 为何选线性插值:分离模型的输出本来就是软掩码,插值引入的轻微高频滚降
 * 远小于模型本身的渗漏;而 sinc/多相滤波的复杂度对当前需求不划算。
 */
object Resampler {

    private const val TAG = "Resampler"

    /**
     * 把交织 PCM 从 [srcRate] 重采样到 [dstRate]。
     * 相同采样率时原样返回(零拷贝)。
     */
    fun resample(
        samples: FloatArray,
        channels: Int,
        srcRate: Int,
        dstRate: Int,
    ): FloatArray {
        if (srcRate == dstRate) return samples
        require(srcRate > 0 && dstRate > 0) { "invalid rates: $srcRate -> $dstRate" }

        val srcFrames = samples.size / channels
        val ratio = dstRate.toDouble() / srcRate
        val dstFrames = (srcFrames * ratio).toInt()
        val out = FloatArray(dstFrames * channels)

        for (f in 0 until dstFrames) {
            val srcPos = f / ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until channels) {
                val a = samples[i0 * channels + c]
                val b = samples[i1 * channels + c]
                out[f * channels + c] = a + (b - a) * frac
            }
        }
        Log.i(TAG, "resampled $srcRate -> $dstRate Hz: $srcFrames -> $dstFrames frames")
        return out
    }
}

/**
 * 解码 + 重采样,把任意音频来源统一成 44.1kHz 立体的 [FrameSource](Task 4.1)。
 *
 * 分支:
 * - .wav → [WavFrameSource](流式,内存与文件长无关);采样率不符时退化为解码+重采样
 * - 其余 → MediaExtractor+MediaCodec 解码成 PCM([MemoryFrameSource])
 *
 * 单声道会**复制成双声道**(模型要 stereo);多声道取前两声道。
 */
object SeparationInput {

    private const val TAG = "SepInput"

    /** 模型要求的采样率 */
    const val MODEL_SAMPLE_RATE = 44100

    /** 模型要求的声道数(固定立体声) */
    private const val MODEL_CHANNELS = 2

    /**
     * 只探测元信息,**不解码样本**(Task 4.1)。
     *
     * - wav:走 [WavIo.probe] 解析 RIFF 头,O(1) 内存
     * - 其余:MediaExtractor 读轨道格式(不解码),帧数由时长×采样率估算
     */
    fun probe(context: Context, localFile: File): WavIo.WavInfo {
        if (localFile.extension.equals("wav", ignoreCase = true)) {
            return WavIo.probe(localFile)
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.fromFile(localFile), null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                // MediaFormat 的 KEY_DURATION 单位是微秒(部分容器不提供,则估 0)
                // 注意:KEY_DURATION 是 Long,必须用 getLong —— 用 getInteger 会抛异常
                val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    f.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                val frames = (durUs / 1_000_000.0 * sr).toInt()
                Log.i(TAG, "probe(extractor): sr=$sr ch=$ch dur=${durUs / 1000}ms frames≈$frames")
                return WavIo.WavInfo(ch, sr, frames)
            }
            error("no audio track in ${localFile.name}")
        } finally {
            extractor.release()
        }
    }

    /**
     * 准备分离输入。调用方负责 [FrameSource.close]。
     *
     * @param localFile 已在本地可随机访问的文件(SAF 导入会先拷到 cacheDir)
     */
    fun open(context: Context, localFile: File): FrameSource {
        val isWav = localFile.extension.equals("wav", ignoreCase = true)

        if (isWav) {
            // 先探采样率:44.1k 才能走流式直读(零解码、零驻留)
            val info = WavIo.probe(localFile)
            if (info.sampleRate == MODEL_SAMPLE_RATE) {
                Log.i(TAG, "wav @44.1k → 流式直读(零解码)")
                return WavFrameSource(WavReader(localFile))
            }
            Log.i(TAG, "wav @${info.sampleRate}Hz → 需要重采样,转解码路径")
        }

        // 通用路径:解码全部 PCM → 重采样 → 补声道
        val decoded = decodeToPcm(context, Uri.fromFile(localFile))
        var samples = decoded.samples
        var channels = decoded.channels

        samples = Resampler.resample(
            samples, channels, decoded.sampleRate, MODEL_SAMPLE_RATE,
        )

        if (channels == 1) {
            samples = toStereo(samples)
            channels = MODEL_CHANNELS
            Log.i(TAG, "mono → stereo (duplicated)")
        } else if (channels > MODEL_CHANNELS) {
            samples = takeFirstTwoChannels(samples, channels)
            Log.i(TAG, "multichannel($channels) → first 2 channels")
            channels = MODEL_CHANNELS
        }

        return MemoryFrameSource(samples, channels, MODEL_SAMPLE_RATE)
    }

    private fun toStereo(mono: FloatArray): FloatArray {
        val frames = mono.size
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = mono[f]
            out[f * 2 + 1] = mono[f]
        }
        return out
    }

    private fun takeFirstTwoChannels(src: FloatArray, srcChannels: Int): FloatArray {
        val frames = src.size / srcChannels
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = src[f * srcChannels]
            out[f * 2 + 1] = src[f * srcChannels + 1]
        }
        return out
    }

    // ---------------- 解码(平台 MediaExtractor + MediaCodec) ----------------

    data class DecodedPcm(
        val samples: FloatArray,
        val channels: Int,
        val sampleRate: Int,
    )

    private fun decodeToPcm(context: Context, uri: Uri): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            check(trackIndex >= 0) { "no audio track in $uri" }
            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()

                val out = ByteArrayOutputStream(1 shl 22)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var guard = 0

                while (!outputDone && guard++ < 200_000) {
                    if (!inputDone) {
                        val inBuf = codec.dequeueInputBuffer(10_000)
                        if (inBuf >= 0) {
                            val buf = codec.getInputBuffer(inBuf)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inBuf, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inBuf, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 稍后读 outputFormat */ }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续等 */ }
                        else -> if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(chunk)
                            out.write(chunk)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                val all = out.toByteArray()
                val outFormat = codec.outputFormat
                val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                // KEY_PCM_ENCODING 是可选的(API24+,不少解码器不给)——
                // 缺失时按默认 PCM16 处理;直接 getInteger 会因 null 拆箱抛 NPE(真机实测踩到)
                val pcmEnc = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                check(pcmEnc == AudioFormat.ENCODING_PCM_16BIT) {
                    "decoder gave PCM encoding $pcmEnc, only 16-bit handled"
                }

                val frames = all.size / 2 / channels
                val samples = FloatArray(frames * channels)
                val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) samples[i] = bb.short / 32768f

                Log.i(TAG, "decoded: frames=$frames ch=$channels sr=$sampleRate")
                return DecodedPcm(samples, channels, sampleRate)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
