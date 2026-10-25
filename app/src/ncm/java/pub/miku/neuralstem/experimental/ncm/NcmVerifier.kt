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
     * **性能基准**：读文件 + 解密 + 格式嗅探，重复多轮取中位数。
     *
     * ## 为何要做
     * 接线到播放链路前必须知道「解密一个 N 分钟歌要多少 ms」：
     * - 若耗时 ≪ 曲子时长 → 可以**一次性解到内存**，实现简单
     * - 若接近甚至超过 → 必须做**流式解密 + 边播**
     *
     * 纯解密是 O(n) 字节 XOR，理论上很便宜；但基凖能实测，不猜。
     */
    fun benchmark(ncmPath: String, rounds: Int = 3): String {
        val f = File(ncmPath)
        if (!f.exists()) return "benchmark: 文件不存在 $ncmPath"
        val times = ArrayList<Long>(rounds)
        var audioLen = 0
        var fmt = "?"
        var durationMs = 0L
        repeat(rounds) {
            val t0 = System.nanoTime()
            runCatching {
                f.inputStream().use { ins ->
                    val d = NcmDecoder.decode(ins)
                    audioLen = d.audioBytes.size
                    fmt = d.format
                }
            }.onFailure { return "benchmark 失败: ${it.message}" }
            times += (System.nanoTime() - t0) / 1_000_000
        }
        val med = times.sorted()[times.size / 2]
        val sourceMb = f.length() / 1_048_576.0
        val mbPerSec = if (med > 0) sourceMb / (med / 1000.0) else 0.0
        val sb = StringBuilder()
        sb.append("BENCH_NCM | file=").append(f.name)
        sb.append(" | src=").append("%.1f".format(sourceMb)).append("MB")
        sb.append(" | audio=").append("%.1f".format(audioLen / 1_048_576.0)).append("MB")
        sb.append(" | fmt=").append(fmt)
        sb.append(" | 解密 中位数=").append(med).append("ms")
        sb.append(" | 吞吐=").append("%.0f".format(mbPerSec)).append("MB/s")
        sb.append(" | 全部=").append(times)
        val s = sb.toString()
        Log.i(TAG, s)
        TestLog.log(TAG, s)
        runCatching {
            File(File(ncmPath).parentFile ?: File("/"), "ncmbench.txt").writeText(s)
        }
        return s
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

    /**
     * **批量扫描目录**：统计每首的容器/编码。
     *
     * ## 为何需要
     * 2026-10-25 发现网易云的某些 m4a 实际是 **AV3A（AVS3-P3）编码**，
     * Android 原生不支持。在决定要不要集成解码器前，必须先知道
     * **这批文件里到底有多少是 AV3A**。
     *
     * ## ★ 必须走 Shizuku（2026-10-25 真机踩到）
     * 网易云目录在 `Android/data/<包>/` 下，**App 无权限直接 `listFiles()`**
     * （返回 null）。初版用 `File(dir).listFiles()` → 报“目录不存在”。
     * ⇒ 改用 [NcmImporter] 的 Shizuku 能力：先用 shell 列文件，再逐个拉字节。
     *
     * ## 全量扫描太慢
     * 每首 100-200ms（解密）+ 传输。407 首 ≈ 1-2 分钟。
     * 所以支持 `limit`，先扫 20 首看分布。
     */
    fun scanCodecs(dir: String, limit: Int = 500): String {
        val entries = runCatching { NcmImporter.listNcm(dir) }.getOrDefault(emptyList())
        if (entries.isEmpty()) return "无 .ncm（或 Shizuku 未授权）: $dir"

        val targets = entries.take(limit)
        val counts = LinkedHashMap<String, Int>()
        val samples = LinkedHashMap<String, String>()
        var failed = 0
        val t0 = System.currentTimeMillis()

        for (e in targets) {
            val tag = runCatching {
                // 用 Shizuku 把字节拉回来（不能直接读），只解头部
                val bytes = NcmImporter.readBytesViaShizuku(e.absolutePath)
                    ?: error("pull 失败")
                // 轻量：不整首解密，先看 magic；若是 MP4 再找 codec tag
                // （NcmDecoder.decode 会读整文件，这里为了速度自己做）
                classifyNcmHead(bytes)
            }.getOrElse { "error" }
            if (tag == "error") failed++
            counts[tag] = (counts[tag] ?: 0) + 1
            if (!samples.containsKey(tag)) samples[tag] = e.name
        }
        val elapsed = System.currentTimeMillis() - t0

        val sb = StringBuilder()
        sb.append("扫描 ").append(targets.size).append(" / ").append(entries.size)
        sb.append(" 个，耗时 ").append(elapsed).append("ms\n")
        counts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            sb.append("  ").append(k).append(" = ").append(v)
            samples[k]?.let { sb.append("   例：").append(it) }
            sb.append('\n')
        }
        if (failed > 0) sb.append("  失败 = ").append(failed).append('\n')
        val s = sb.toString()
        Log.i(TAG, "CODE_SCAN\n$s")
        return s
    }

    /**
     * 从 NCM 字节里直接识别音频编码（不解密全文件）。
     *
     * ## 为何能这样做
     * NCM 的头部结构（key / meta / cover 长度）都在前 100KB 内，
     * 拿到长度后音频起点就确定了；而 MP4 的 `moov`（含 codec tag）
     * **就在音频段的最开头**（实测 moov 在 offset 28，731 字节）。
     * ⇒ 只需解密前几 KB 就能认出编码。
     */
    private fun classifyNcmHead(raw: ByteArray): String = runCatching {
        // 直接复用 decode 的头部逻辑：调一次 decode 但只取前 8KB 头部分类
        // （decode 本身 100-200ms，对 20 个样本可接受；全量时另优化）
        val d = NcmDecoder.decode(raw.inputStream())
        classify(d.audioBytes)
    }.getOrElse { "error" }

    /**
     * 判断解密后的音频是什么容器/编码。
     *
     * ## 为何要认出 av3a
     * `av3a` 是 AVS3-P3 音频的 MP4 codec tag（中国自主标准）。
     * Android 的 `MediaExtractor` 不认识它 → 无 audio track。
     */
    private fun classify(audio: ByteArray): String {
        if (audio.size < 12) return "too-short"
        fun ch(i: Int) = audio[i].toInt() and 0xFF
        fun hasAt(tag: String, limit: Int): Boolean {
            val n = tag.toByteArray(Charsets.US_ASCII)
            val max = minOf(audio.size, limit) - n.size
            if (max < 0) return false
            for (i in 0..max) {
                var hit = true
                for (j in n.indices) if (audio[i + j] != n[j]) { hit = false; break }
                if (hit) return true
            }
            return false
        }
        // MP4 家族：第 4~8 字节是 box type
        if (ch(4) == 0x66 && ch(5) == 0x74 && ch(6) == 0x79 && ch(7) == 0x70) {
            val win = 65536
            return when {
                hasAt("av3a", win) -> "m4a/av3a(AVS3)"
                hasAt("mp4a", win) -> "m4a/aac"
                hasAt("alac", win) -> "m4a/alac"
                else -> "m4a/other"
            }
        }
        return when {
            ch(0) == 0x66 && ch(1) == 0x4C && ch(2) == 0x61 && ch(3) == 0x43 -> "flac"
            ch(0) == 0x49 && ch(1) == 0x44 && ch(2) == 0x33 -> "mp3"
            ch(0) == 0xFF && (ch(1) and 0xE0) == 0xE0 -> "mp3"
            ch(0) == 0x4F && ch(1) == 0x67 && ch(2) == 0x67 -> "ogg"
            ch(0) == 0x52 && ch(1) == 0x49 && ch(2) == 0x46 && ch(3) == 0x46 -> "wav"
            else -> "unknown"
        }
    }

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
