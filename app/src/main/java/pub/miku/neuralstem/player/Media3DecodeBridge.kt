package pub.miku.neuralstem.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 容器 → PCM 解码桥(Task 2 波形用)。
 *
 * 实现选型(2026-02 修订):曾尝试手写 media3 Extractor 的 TrackOutput 实现,
 * 但 Kotlin 对其 default/abstract 多重载方法(sampleData 2/3/4 参)的 override
 * 解析持续报错(见 .critical_steps.md Task 2 节),故改用**平台 MediaExtractor
 * + MediaCodec**:
 * - 系统自带,API 21+ 全支持,零兼容性坑
 * - 压缩容器(mp3/flac/aac/m4a)也能解出 PCM → 波形覆盖所有 guide 支持的格式
 * - 唯一代价:平台类与 JVM 单测不兼容(与 sherpa 等原生组件一致,测试走
 *   computeEnvelope 纯函数)
 *
 * 内存:整曲 PCM 驻留(5min 立体声 ≈ 110MB)。6G+largeHeap 可行;
 * 低配优化(流式喂包络)留待 Task 4。
 */
object Media3DecodeBridge {

    data class DecodedPcm(
        val samples: FloatArray,
        val channels: Int,
        val sampleRate: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is DecodedPcm && samples.contentEquals(other.samples)
        override fun hashCode(): Int = samples.contentHashCode()
    }

    fun decodeToPcm(context: Context, uri: Uri): DecodedPcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            // 找音频轨
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

                while (!outputDone && guard++ < 100_000) {
                    // 喂输入
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
                                codec.queueInputBuffer(
                                    inBuf, 0, size, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                        }
                    }
                    // 收输出
                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 部分解码器在这里才给出真实输出格式
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> { /* busy wait 10ms */ }
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
                val pcmEnc = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
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

    private const val TAG = "Waveform"
}