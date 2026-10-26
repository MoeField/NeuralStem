package pub.miku.neuralstem.experimental.ncm

import android.app.Application
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * ★播放前的「AVS3 → 可播 uri」转换器。
 *
 * ## 在整条链路里的位置
 *
 * ```
 * 用户点歌 / 自动切歌 / 恢复会话
 *         ↓
 *   Avs3PlaybackCoordinator.prepare()      ← 本类
 *         ├ 不是 AVS3     → 原 uri 直接返回
 *         ├ 有磁盘缓存    → 缓存 WAV 的 file:// uri
 *         └ 无缓存        → 解码 → 存缓存 → file:// uri
 *         ↓
 *   PlayerViewModel.doLoad(uri)            ← 一行不改，收到的一定是「已能播」的 uri
 * ```
 *
 * ## 为什么放在 doLoad **之前**
 * `doLoad()` 是同步的（立刻 setMediaItem + prepare + play），
 * 而且里面有 **I1 不变量**（换曲前先退多轨）。若在它内部插异步解码，
 * 会出现「解码完成时用户已经切走」的竞态，直接污染状态机。
 *
 * 放在前面，[doLoad] 收到的永远是已经就绪的 uri，状态机完全无感。
 *
 * ## 为何用 `file://` 而不是复用 Avs3PcmDataSource
 * 缓存命中时 WAV 已经在磁盘上，直接给 `file://` 最简单 ——
 * ExoPlayer 自己会 `WavExtractor` 解析，不需要我们造 DataSource。
 */
object Avs3PlaybackCoordinator {

    private const val TAG = "Avs3Playback"

    /** 转换结果。 */
    sealed interface Prepared {
        /** 可以直接播放的 uri（原文件，或缓存 WAV）。 */
        data class Ready(val uri: Uri) : Prepared

        /** 无法播放（没有解码器 / 解码失败）。 */
        data class Failed(val message: String) : Prepared
    }

    /** 进度回调。 */
    fun interface Progress {
        fun onProgress(uri: String, percent: Int)
    }

    /**
     * 判断某个 uri 是否**确实是** AVS3（`av3a`）编码。
     *
     * ## 为何不能只看扩展名
     * 实测：220 个 `.m4a` 里 **219 个是 `av3a`**。扩展名是 m4a，编码却是 AVS3。
     * 所以必须真的读 MP4 的 `stsd` 才能确定（见 [Mp4CodecProbe]）。
     *
     * ## 为何要快
     * 每次切歌都会调，不能阻塞。只读文件头 256KB（moov 通常在前部）。
     */
    fun isAvs3Source(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return isAvs3Path(path)
    }

    /**
     * 同上，但直接给文件路径（供歌单的 `netease://` 引用使用）。
     *
     * ## 两条读取路径
     * - **普通文件**（如 /sdcard/Download/x.m4a）→ `File` 直接读
     * - **网易云私有目录** → App 无权限（实测 `Permission denied`）
     *   → 必须走 Shizuku 的 `cat`
     *
     * 所以判断“是不是 AVS3”也得能跨越这两条路径。
     */
    fun isAvs3Path(path: String): Boolean {
        if (!path.lowercase().endsWith(".m4a")) return false
        val head = readHeadOf(path, 256 * 1024) ?: return false
        if (head.isEmpty()) return false
        return runCatching { Mp4CodecProbe.probeCodec(head) == "av3a" }.getOrDefault(false)
    }

    /**
     * 读文件前 [maxBytes] 字节（自动选择 File 或 Shizuku）。失败返回 null。
     */
    private fun readHeadOf(path: String, maxBytes: Int): ByteArray? {
        val f = File(path)
        // 能直接读就用 File（快，且不需要 Shizuku）
        if (f.exists() && f.canRead()) {
            return runCatching {
                f.inputStream().use { ins ->
                    val cap = minOf(f.length(), maxBytes.toLong()).toInt()
                    val buf = ByteArray(cap)
                    var off = 0
                    while (off < cap) {
                        val n = ins.read(buf, off, cap - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off < cap) buf.copyOf(off) else buf
                }
            }.getOrNull()
        }
        // 私有目录 → 借 Shizuku
        return runCatching { NcmImporter.readHead(path, maxBytes) }.getOrNull()
    }

    /**
     * 读源文件的完整字节（自动选择 File 或 Shizuku）。
     *
     * @return null = 读不到（无权限 / 文件不存在）
     */
    fun readSourceBytes(path: String): ByteArray? {
        val f = File(path)
        if (f.exists() && f.canRead()) {
            return runCatching { f.readBytes() }.getOrNull()
        }
        return runCatching { NcmImporter.readBytesViaShizuku(path) }.getOrNull()
    }

    /**
     * 把源 uri 转成「可播放的 uri」。
     *
     * @param srcUri 源文件 uri（`file://...`）
     * @param srcPath 源文件绝对路径（用于算缓存键）
     * @param onProgress 解码进度（仅在真需要解码时回调）
     * @param shouldCancel 定期询问是否应中止（由状态机决策驱动）
     */
    fun prepare(
        context: Application,
        srcUri: Uri,
        srcPath: String,
        onProgress: Progress? = null,
        shouldCancel: () -> Boolean = { false },
    ): Prepared {
        // 1) 缓存优先
        val stat = fileStat(srcPath)
        if (stat != null) {
            val key = Avs3PcmCache.keyFor(srcPath, stat[0], stat[1])
            Avs3PcmCache.get(context, key)?.let {
                Log.i(TAG, "缓存命中 $key → ${it.name}")
                return Prepared.Ready(Uri.fromFile(it))
            }
        }

        // 2) 解码
        val cached = decodeAndCache(context, srcPath, onProgress, shouldCancel)
            ?: return Prepared.Failed("解码失败（解码器不可用或文件不是有效 AVS3）")

        return Prepared.Ready(Uri.fromFile(cached))
    }

    /** 解一首并写缓存，返回缓存文件；失败返回 null。 */
    fun decodeAndCache(
        context: Application,
        srcPath: String,
        onProgress: Progress?,
        shouldCancel: () -> Boolean,
    ): File? {
        val stat = fileStat(srcPath) ?: run {
            Log.w(TAG, "拿不到 $srcPath 的 size/mtime，无法建缓存键")
            return null
        }
        val key = Avs3PcmCache.keyFor(srcPath, stat[0], stat[1])

        // 可能预解码刚跑完，先看一眼
        Avs3PcmCache.get(context, key)?.let {
            Log.i(TAG, "解码前发现已有缓存 $key")
            return it
        }

        var decoder: Avs3AudioDecoder? = null
        return try {
            // ★readSourceBytes：自动选 File 或 Shizuku。
            //   网易云私有目录 App 读不了（实测 Permission denied），
            //   必须借 Shizuku 的 cat。
            val bytes = readSourceBytes(srcPath) ?: run {
                Log.w(TAG, "读不到源文件（无权限？需要 Shizuku）：$srcPath")
                return null
            }
            val track = Mp4TrackExtractor.extractSamples(bytes) ?: run {
                Log.w(TAG, "MP4 提取失败")
                return null
            }

            decoder = Avs3AudioDecoder()
            if (!decoder.available) {
                Log.w(TAG, "解码器不可用（未运行 tools/fetch-avs3.sh？）")
                return null
            }
            if (!decoder.open()) return null
            if (decoder.initSequence(track.samples[0]) != 0) {
                Log.w(TAG, "initSequence 失败")
                return null
            }

            val rate = decoder.sourceSampleRate().takeIf { it > 0 } ?: 44100
            val srcCh = decoder.sourceChannels().takeIf { it > 0 } ?: 12
            val total = track.samples.size.coerceAtLeast(1)

            val result = Avs3PcmDecoder.decodeAll(
                decoder = decoder,
                frames = track.samples,
                headerBytes = 7,
                sampleRate = rate,
                sourceChannels = srcCh,
                downmix = Avs3PcmDecoder.Downmix.BS775,
                onProgress = { done, _ ->
                    onProgress?.onProgress(srcPath, done * 100 / total)
                },
                isCancelled = shouldCancel,
            ) ?: run {
                Log.i(TAG, "解码被取消或失败")
                return null
            }

            val wav = Avs3WavWriter.toWavBytes(result.pcm, result.sampleRate, result.channels)
            val limit = pub.miku.neuralstem.settings.SettingsRepository(context)
                .decodeCacheLimitBytes()
            Avs3PcmCache.put(context, key, wav, limit)
        } catch (t: Throwable) {
            Log.w(TAG, "解码失败 $srcPath", t)
            null
        } finally {
            runCatching { decoder?.close() }
        }
    }

    /**
     * 取 size + mtime，用于建缓存键。
     *
     * 本地文件直接拿；私有目录走 Shizuku 的 `stat`。
     */
    private fun fileStat(path: String): LongArray? {
        val f = File(path)
        if (f.exists()) return longArrayOf(f.length(), f.lastModified())
        // 私有目录：App 看不到，借 Shizuku
        return runCatching { NcmImporter.statFor(path) }.getOrNull()
    }

    /**
     * 解析歌单里的“逻辑引用”为真实文件路径。
     *
     * ## 为何需要
     * 网易云的歌在私有目录，App 拿不到可用的 `file://` uri，
     * 所以歌单里只能存逻辑引用 `netease://<URL编码的绝对路径>`。
     * 播放前再解回真实路径。
     *
     * 非 `netease://` 的 uri 原样返回路径（兼容已有歌单）。
     */
    fun resolvePath(uri: Uri): String? {
        return when (uri.scheme) {
            NET_SRC_SCHEME -> {
                // ★交给纯字符串的 NeteaseUriCodec（可 JVM 单测），
                //   不要用 uri.host/schemeSpecificPart —— 实测 `%2F` 会被当成 host，
                //   而 Uri 在单测里是 stub 没法验证。
                pub.miku.neuralstem.player.NeteaseUriCodec.decode(uri.toString())
            }
            "file" -> uri.path
            else -> null
        }
    }

    /**
     * 把一个绝对路径包成歌单可存的 uri。
     *
     * 私有目录（能读到但不在 App 沙箱内）用 `netease://`，
     * 其余用普通 `file://`。
     */
    fun toPlayableUri(path: String): Uri {
        val f = File(path)
        return if (f.exists() && f.canRead()) {
            Uri.fromFile(f)
        } else {
            // ★编解码统一走 NeteaseUriCodec（纯字符串，可 JVM 单测）。
            //   手拼 `Uri.parse("netease://$encoded")` 会让 %2F 被当成已编码 host，
            //   toString 时二次编码成 %252F（实测踩到）。
            Uri.parse(pub.miku.neuralstem.player.NeteaseUriCodec.encode(path))
        }
    }

    /** 歌单里存“私有目录逻辑引用”的 scheme。 */
    const val NET_SRC_SCHEME = "netease"
}
