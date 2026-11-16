package pub.miku.neuralstem.player.pcm

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import kotlinx.coroutines.delay
import pub.miku.neuralstem.player.Media3DecodeBridge
import pub.miku.neuralstem.player.WaveformData
import pub.miku.neuralstem.spike.WavWriter

/**
 * 共享 PCM 的统一解码器（Phase 2）。
 *
 * MediaExtractor/MediaCodec 解一次：
 * - 写出完整 16-bit stereo WAV
 * - 同时累积渐进包络
 *
 * 波形和分离不再各自解码；解码结果由 [PcmStore] 发布/租约。
 */
class PcmDecodePipeline(
    context: Context,
) : PcmStore.PcmDecoder {

    private val app = context.applicationContext

    override suspend fun decode(
        source: SourceDescriptor,
        output: File,
        onProgress: (bytesWritten: Long, envelope: WaveformData?) -> Unit,
    ): PcmArtifact? {
        if (source.kind != SourceDescriptor.Kind.CONTENT_URI &&
            source.kind != SourceDescriptor.Kind.LOCAL_FILE
        ) {
            return null
        }
        // file path 不能直接 Uri.parse(path)：没有 scheme，MediaExtractor 会失败。
        val uri = when (source.kind) {
            SourceDescriptor.Kind.LOCAL_FILE -> {
                val path = source.location ?: return null
                Uri.fromFile(File(path))
            }
            else -> source.location?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        }

        Log.i(TAG, "start ${source.kind} ${source.displayName} size=${source.sizeBytes}")
        var writer: WavWriter? = null
        val env = PcmEnvelopeAccumulator()
        var sampleRate = 0
        var totalFrames = 0L
        var lastProgressMs = 0L
        var envelope: WaveformData? = null

        fun emitProgress() {
            val now = System.currentTimeMillis()
            if (now - lastProgressMs < PROGRESS_THROTTLE_MS) return
            lastProgressMs = now
            val bytes = runCatching { output.length() }.getOrDefault(0L)
            onProgress(bytes, envelope)
        }

        try {
            val complete = Media3DecodeBridge.decodeToPcmStreaming(app, uri) { samples, ch, sr ->
                if (sr <= 0 || ch <= 0) return@decodeToPcmStreaming true
                if (writer == null) {
                    sampleRate = sr
                    writer = WavWriter(output, 2, sr)
                }
                val stereo = toStereo(samples, ch)
                writer?.append(stereo)
                totalFrames += (stereo.size / 2).toLong()
                envelope = env.onChunk(stereo, 2, sr)
                emitProgress()
                true
            }
            if (!complete || writer == null) {
                output.delete()
                return null
            }
            writer?.close()
            writer = null
            envelope = env.finish() ?: envelope
            onProgress(output.length(), envelope)
            if (output.length() <= 44L) {
                output.delete()
                return null
            }
            Log.i(TAG, "done ${source.displayName} sr=$sampleRate frames=$totalFrames bytes=${output.length()}")
            return PcmArtifact(
                file = output,
                sizeBytes = output.length(),
                createdAtMs = System.currentTimeMillis(),
                sampleRate = sampleRate,
                channels = 2,
                totalFrames = totalFrames,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "pcm decode failed: ${source.displayName}", t)
            runCatching { writer?.close() }
            output.delete()
            throw t
        } finally {
            runCatching { writer?.close() }
        }
    }

    private fun toStereo(samples: FloatArray, channels: Int): FloatArray {
        val frames = samples.size / channels
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = samples[f * channels]
            out[f * 2 + 1] = if (channels > 1) samples[f * channels + 1] else samples[f * channels]
        }
        return out
    }

    companion object {
        private const val TAG = "PcmDecode"
        private const val PROGRESS_THROTTLE_MS = 300L
    }
}
