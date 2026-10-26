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
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val f = File(path)
        if (!f.exists() || !f.canRead()) return false
        if (!f.name.lowercase().endsWith(".m4a")) return false
        return runCatching {
            val head = f.inputStream().use { ins ->
                val cap = minOf(f.length(), 256L * 1024).toInt()
                val buf = ByteArray(cap)
                var off = 0
                while (off < cap) {
                    val n = ins.read(buf, off, cap - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < cap) buf.copyOf(off) else buf
            }
            Mp4CodecProbe.probeCodec(head) == "av3a"
        }.getOrDefault(false)
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
            val bytes = File(srcPath).readBytes()
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

    /** 取 size + mtime（本地文件直接拿，不需要 Shizuku）。 */
    private fun fileStat(path: String): LongArray? = runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching null
        longArrayOf(f.length(), f.lastModified())
    }.getOrNull()
}
