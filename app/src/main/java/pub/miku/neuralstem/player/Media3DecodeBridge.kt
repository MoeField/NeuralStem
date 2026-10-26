package pub.miku.neuralstem.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
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

    /**
     * 流式解码：每解出一块 PCM 就回调，**不累积整曲**（内存 O(chunk)）。
     *
     * 用途：波形提取 —— 可实现“边解码边显示”；且不再需要整曲 PCM 驻留
     * （旧 [decodeToPcm] 的 5min 立体声 ≈110MB 问题）。
     *
     * @param onChunk 回传 (float 交错样本, channels, sampleRate)；返回 false 则**提前终止**
     *                （用于切歌/取消）。返回的样本为**本块的**，可在回调内立即处理。
     * @return 是否跑完（false = 被 onChunk 取消）
     */
    fun decodeToPcmStreaming(
        context: Context,
        uri: Uri,
        onChunk: (FloatArray, Int, Int) -> Boolean,
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            return decodeStreamingFrom(extractor, uri.toString(), onChunk)
        } finally {
            extractor.release()
        }
    }

    /**
     * ★流式解码「**内存字节**」源（2026-10-26）。
     *
     * ## 为何需要
     * `ncm://<key>` 是自定义 scheme，[MediaExtractor.setDataSource] **不认识** →
     * 波形提取直接 `IOException: Failed to instantiate extractor`（真机实测 16ms 就挂）。
     * 而 NCM 解密后的字节就存在 `NcmPlaybackStore` 里（flac/mp3/m4a 原始容器），
     * 直接喂给 `MediaDataSource` 即可正常解码。
     *
     * 与 uri 版本共用同一个解码循环（[decodeStreamingFrom]），不复制粘贴。
     *
     * @param bytes 整段音频字节（如 NCM 解密产物）
     * @return 是否跑完（false = 被 onChunk 取消）
     */
    fun decodeToPcmStreaming(
        bytes: ByteArray,
        onChunk: (FloatArray, Int, Int) -> Boolean,
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArrayMediaSource(bytes))
            return decodeStreamingFrom(extractor, "<bytes:${bytes.size}>", onChunk)
        } finally {
            extractor.release()
        }
    }

    /**
     * 解码循环（两个入口共用）。
     *
     * 调用方负责建/释放 `MediaExtractor`，本函数只负责选轨 + 解码 + 回调。
     *
     * @param label 仅用于日志/异常信息
     * @return `outputDone`（**已真收到 EOS**）。靠 guard 熔断退出 = 未真解完。
     */
    private fun decodeStreamingFrom(
        extractor: MediaExtractor,
        label: String,
        onChunk: (FloatArray, Int, Int) -> Boolean,
    ): Boolean {
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
            check(trackIndex >= 0) { "no audio track in $label" }
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var channels = 2
                var sampleRate = 44100
                var guard = 0

                // ★⑮-3：`guard` 是**防死循环**上限，**不是**“解码完成”。
                //   若靠 guard 熔断退出 = 未收到 EOS = **未真正解完**，绝不能当完整（否则写半截缓存）。
                while (!outputDone && guard++ < 200_000) {
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
                            // ★ 部分解码器在这里才给出真实输出格式
                            val of = codec.outputFormat
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> { /* busy wait 10ms */ }
                        else -> if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = sb.remaining()
                            val floats = FloatArray(n)
                            for (i in 0 until n) floats[i] = sb.get(i) / 32768f
                            codec.releaseOutputBuffer(outIdx, false)
                            if (n > 0 && !onChunk(floats, channels, sampleRate)) {
                                return false   // 取消
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                // ★⑮-3：**返回 `outputDone`**（而非无条件 true）。
                //   背景：旧代码这里 `return true`，导致“guard 熔断（未到 EOF）”也被当成完整
                //   → WaveformExtractor 写入**半截波形缓存**（实证：3.0s 坏缓存）。
                //   现语义：仅当真收到 `BUFFER_FLAG_END_OF_STREAM`（outputDone=true）才算完整。
                return outputDone
            } finally {
                codec.stop()
                codec.release()
            }
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
                // ★坑 29/57：`KEY_PCM_ENCODING` 是**可选键**——部分设备（如 BF00A/MTK）解码器
                //   不提供该键 → `getInteger` 内部拆箱 null → NPE（曾导致波形提取全失败）。
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

    /**
     * ★把**内存字节**伪装成 [MediaDataSource]，交给 `MediaExtractor`。
     *
     * ## 为何需要
     * `MediaExtractor` 只认「文件路径 / content uri / FileDescriptor / MediaDataSource」。
     * NCM 解密后的音频在**内存**里（不在磁盘、也没有 uri），只能走 MediaDataSource。
     *
     * ## 为何不用写临时文件
     * 原本可以写临时文件再解，但那要多一次 30MB 磁盘写 + 读（且要管清理）。
     * MediaDataSource 是平台类（API 23+，与本项目 minSdk 一致），直接包一层更干净。
     *
     * ## 注意
     * 本类是**只读**的：`readAt` 不支持越界（返回 -1 表示 EOF）。
     */
    private class ByteArrayMediaSource(
        private val bytes: ByteArray,
    ) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val remaining = bytes.size - position
            val n = minOf(size.toLong(), remaining).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() { /* 无需释放（字节由调用方持有） */ }
    }

    private const val TAG = "Waveform"
}