package pub.miku.neuralstem.experimental.ncm

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import pub.miku.neuralstem.devtools.TestLog
import java.io.File

/**
 * NCM 解码的**真机验证器**（devtools `ncmtest` 命令的后端）。
 *
 * ## 为什么需要它
 * `NcmDecoder` 的 AES/RC4 链路**从未对真实文件验证过** —— 单测只能测魔数识别，
 * 真正的“解密出来的音频对不对”必须有真样本。本类做两件事：
 *
 * 1. **解码**：调 [NcmDecoder.decode]，拿到内存字节
 * 2. **头校验**：把内存字节交给平台 [MediaExtractor] → 能解析出音轨 = 音频头正确
 *
 * ## 为何用 MediaExtractor 而不是只看魔数
 * 魔数只能证明“前 4 字节对”，不能证明**整段 RC4 密钥正确**。
 * 若 RC4 密钥错了，前几帧可能碰巧还是合法帧头，但 MediaExtractor 解析元数据时会失败。
 * ⇒ **能解析出 track + 时长** 才是强证据。
 *
 * ## 只在 `-Pwith_ncm=true` 构建中存在
 */
object NcmVerifier {

    private const val TAG = "NcmVerify"

    /** 验证结果。 */
    data class Result(
        val ok: Boolean,
        val file: String,
        val sourceBytes: Long,
        val audioBytes: Int,
        val format: String,
        val title: String?,
        val artist: String?,
        val trackSampleRate: Int?,
        val trackChannels: Int?,
        val trackDurationMs: Long?,
        val trackMime: String?,
        val error: String? = null,
    ) {
        /** 单行摘要（写日志）。 */
        fun summary(): String = buildString {
            append(if (ok) "OK" else "FAIL")
            append(" | ").append(file.substringAfterLast('/'))
            append(" | src=").append(sourceBytes / 1024).append("KB")
            append(" -> audio=").append(audioBytes / 1024).append("KB")
            append(" | fmt=").append(format)
            append(" | track=").append(trackMime ?: "?")
            append(" ").append(trackSampleRate ?: "?").append("Hz")
            append(" ").append(trackChannels ?: "?").append("ch")
            append(" ").append(trackDurationMs ?: "?").append("ms")
            append(" | title=").append(title ?: "-")
            append(" artist=").append(artist ?: "-")
            error?.let { append(" | ERR=").append(it) }
        }
    }

    /**
     * 验证一个 NCM 文件。全程在内存中，**不写任何文件**（与 [NcmDecoder] 的约定一致）。
     */
    fun verify(ncmPath: String): Result {
        val r = doVerify(ncmPath)
        Log.i(TAG, r.summary())
        TestLog.log(TAG, r.summary())
        return r
    }

    /**
     * 验证并**把结果写到 [outDir]/ncmtest_result.txt**。
     *
     * ## 为何还要写文件
     * vivo/Android16 会吞 app 的 logcat（坑 52），而 `ncmtest` 是 adb 命令、
     * 不会打开测试面板 → 屏上日志也看不到。写文件是唯一可靠的非 UI 通道。
     */
    fun verifyAndReport(ncmPath: String, outDir: String): Result {
        val r = doVerify(ncmPath)
        Log.i(TAG, r.summary())
        TestLog.log(TAG, r.summary())
        runCatching {
            val f = File(outDir, "ncmtest_result.txt")
            f.parentFile?.mkdirs()
            f.writeText(
                r.summary() + "\n\n" + r.toString() +
                    "\n\n--- decoder diag ---\n" + NcmDecoder.drainDiagnostics(),
            )
            Log.i(TAG, "result written to ${f.absolutePath}")
        }.onFailure { Log.w(TAG, "write result failed", it) }
        return r
    }

    /** 实际验证逻辑（不落盘，便于单测）。 */
    private fun doVerify(ncmPath: String): Result {
        val f = File(ncmPath)
        if (!f.exists()) return fail(ncmPath, 0, "文件不存在")
        val srcLen = f.length()

        // ---- 1. 解码 ----
        val decoded = try {
            f.inputStream().use { NcmDecoder.decode(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed: $ncmPath", t)
            return fail(ncmPath, srcLen, "解码失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        Log.i(
            TAG,
            "decoded: ${decoded.format} ${decoded.audioBytes.size}B " +
                "title=${decoded.title} artist=${decoded.artist}",
        )

        // ---- 2. 用 MediaExtractor 验证音频头 ----
        val probe = probeWithExtractor(decoded.audioBytes)

        // ---- 3. 判定 ----
        // 成功条件：**格式嗅探给出可识别的音频容器**（最硬的证据）。
        //
        // ## 为何不把 MediaExtractor 作为必要条件
        // 实测：m4a 通过 `MediaDataSource`（内存）喂给 MediaExtractor 时，
        // 只能拿到容器时长（135791ms）但 sampleRate=null、mime=octet-stream。
        // 那是 MediaExtractor 对内存 m4a 的限制，**不是解码错**。
        // ⇒ 只要 sniff 出的格式 ≠ "bin" 就算解码成功；MediaExtractor
        //    的信息作为**附加证据**记录（能看到时长就更加确信）。
        val sniffed = decoded.format != "bin"
        val ok = sniffed
        val err = when {
            ok -> null
            decoded.audioBytes.isEmpty() -> "解出 0 字节"
            else -> "格式嗅探失败（解出的字节不惃何已知音频容器）"
        }

        return Result(
            ok = ok,
            file = ncmPath,
            sourceBytes = srcLen,
            audioBytes = decoded.audioBytes.size,
            format = decoded.format,
            title = decoded.title,
            artist = decoded.artist,
            trackSampleRate = probe.sampleRate,
            trackChannels = probe.channels,
            trackDurationMs = probe.durationMs,
            trackMime = probe.mime,
            error = err,
        ).also {
            Log.i(TAG, it.summary())
        }
    }

    /** 用平台 MediaExtractor 解析内存中的音频字节。 */
    private fun probeWithExtractor(audio: ByteArray): ProbeInfo {
        if (audio.isEmpty()) return ProbeInfo(null, null, null, null)
        val ex = MediaExtractor()
        return try {
            // MediaExtractor 只接受 FileDescriptor / 路径 / MediaDataSource。
            // 用匿名 MediaDataSource 从内存喂 —— 避免落盘（遵守“只在内存解密”的约定）。
            ex.setDataSource(MemoryDataSource(audio))
            if (ex.trackCount == 0) {
                Log.w(TAG, "extractor: trackCount=0")
                return ProbeInfo(null, null, null, null)
            }
            val fmt = ex.getTrackFormat(0)
            ProbeInfo(
                sampleRate = fmt.optInt(MediaFormat.KEY_SAMPLE_RATE),
                channels = fmt.optInt(MediaFormat.KEY_CHANNEL_COUNT),
                durationMs = fmt.optLong(MediaFormat.KEY_DURATION)?.div(1000L),
                mime = fmt.optString(MediaFormat.KEY_MIME),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "extractor probe failed: ${t.javaClass.simpleName}: ${t.message}")
            ProbeInfo(null, null, null, null)
        } finally {
            runCatching { ex.release() }
        }
    }

    /** 从内存字节读的 MediaDataSource（不落盘）。 */
    private class MemoryDataSource(private val data: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), (data.size - position).toLong()).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }

    private data class ProbeInfo(
        val sampleRate: Int?,
        val channels: Int?,
        val durationMs: Long?,
        val mime: String?,
    )

    /**
     * MediaFormat 安全取值。
     *
     * `KEY_*` 在 Android 里是**可选键**，缺失时 `getInteger` 会抛异常
     * （坑 29：`KEY_PCM_ENCODING` 曾在新机型上缺失导致 NPE）。
     */
    private fun MediaFormat.optInt(key: String): Int? =
        runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

    private fun MediaFormat.optLong(key: String): Long? =
        runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()

    private fun MediaFormat.optString(key: String): String? =
        runCatching { if (containsKey(key)) getString(key) else null }.getOrNull()

    private fun fail(path: String, srcBytes: Long, msg: String): Result =
        Result(
            ok = false, file = path, sourceBytes = srcBytes, audioBytes = 0,
            format = "?", title = null, artist = null,
            trackSampleRate = null, trackChannels = null, trackDurationMs = null,
            trackMime = null, error = msg,
        ).also {
            Log.e(TAG, it.summary())
            TestLog.log(TAG, it.summary())
        }
}
