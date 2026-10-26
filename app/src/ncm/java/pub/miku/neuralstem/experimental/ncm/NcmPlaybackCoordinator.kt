package pub.miku.neuralstem.experimental.ncm

import android.app.Application
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * ★`.ncm` 播放前的完整处理：**解密 → 分流 → 转成可播 uri**。
 *
 * ## 在整条链路里的位置
 *
 * ```
 * 歌单点歌（netease:// 指向 .ncm）
 *         ↓
 *   NcmPlaybackCoordinator.prepare()
 *         ├─ 解密（NcmDecoder）
 *         ├─ 分流（NcmPlaybackPlanner）：
 *         │    ├ flac/mp3/mp4a  → ncm://<key>（字节存 store，带格式）
 *         │    └ av3a           → 再走 AVS3 解码 → 缓存 WAV
 *         ↓
 *   PlayerViewModel.doLoad(uri)   ← 收到的一定是可播 uri
 * ```
 *
 * ## ★为何要分流（而不是统一当 AVS3 处理）
 * NCM 只是容器壳。实测 60 个样本：59 个解出 av3a，**1 个解出 flac**。
 * flac 本身 Android 原生就能播 —— 把它丢进 AVS3 解码器是纯浪费（而且必然失败）。
 *
 * ## 为何原生格式走内存而不是落盘
 * 解密结果只有 15–40MB，放 `NcmPlaybackStore`（LRU 2 条）即可。
 * 落盘反而要额外管清理。只有 AVS3 需要落盘（因为解码产物是重建的）。
 */
object NcmPlaybackCoordinator {

    private const val TAG = "NcmPlayback"

    /** 处理结果。 */
    sealed interface Prepared {
        /** 可直接交给播放器（`ncm://` 或缓存 WAV）。 */
        data class Ready(val uri: Uri) : Prepared

        /** 失败。 */
        data class Failed(val message: String) : Prepared
    }

    /** 进度回调。 */
    fun interface Progress {
        /** @param percent 0..100（只覆盖「AVS3 解码」阶段，解密本身很快） */
        fun onProgress(percent: Int)
    }

    /**
     * 处理一个 `.ncm` 源文件。
     *
     * ⚠️ **阻塞调用**（解密 100–200ms + 可能的 AVS3 解码几十秒），
     * 必须在 `Dispatchers.IO` 里跑。
     *
     * @param srcPath .ncm 绝对路径（可能在私有目录，自动走 Shizuku）
     */
    fun prepare(
        app: Application,
        srcPath: String,
        onProgress: Progress? = null,
        shouldCancel: () -> Boolean = { false },
    ): Prepared {
        // 1) 读源文件（可能在私有目录）
        val raw = runCatching {
            val f = File(srcPath)
            if (f.exists() && f.canRead()) f.readBytes()
            else NcmImporter.readBytesViaShizuku(srcPath)
        }.getOrNull() ?: return Prepared.Failed("读不到 .ncm 文件")

        // 2) 解密
        val decoded = runCatching { NcmDecoder.decode(raw.inputStream()) }
            .getOrElse { return Prepared.Failed("解密失败: ${it.message}") }
        val audio = decoded.audioBytes
        Log.i(TAG, "解密完成：${decoded.format}，${audio.size / 1_048_576.0}MB")

        // 3) 分流
        return when (val plan = NcmPlaybackPlanner.decide(decoded.format, audio)) {
            is NcmPlaybackPlanner.Plan.Native -> {
                // 原生格式：字节放 store，uri 带 key（格式由 store 记录）
                val key = cacheKeyFor(srcPath)
                NcmPlaybackStore.put(key, audio, plan.format)
                Log.i(TAG, "原生可播（${plan.format}）→ ncm://$key")
                Prepared.Ready(NcmMemoryDataSource.uriFor(key))
            }

            is NcmPlaybackPlanner.Plan.Avs3 -> {
                // AVS3：复用已有的解码链（与独立 .m4a 的完全同一套）
                Log.i(TAG, "m4a 里是 av3a → 走 AVS3 解码")
                // 先把解密结果落成临时 m4a，让 AVS3 链能按统一路径处理
                val tmp = File(app.cacheDir, "ncm_avs3_${cacheKeyFor(srcPath)}.m4a")
                runCatching { tmp.writeBytes(audio) }
                    .getOrElse { return Prepared.Failed("写临时文件失败") }

                val res = Avs3PlaybackCoordinator.prepare(
                    context = app,
                    srcUri = Uri.fromFile(tmp),
                    srcPath = tmp.absolutePath,
                    onProgress = { _, pct -> onProgress?.onProgress(pct) },
                    shouldCancel = shouldCancel,
                )
                // 清理临时文件（解码产物已进缓存，源不再需要）
                runCatching { tmp.delete() }

                when (res) {
                    is Avs3PlaybackCoordinator.Prepared.Ready -> Prepared.Ready(res.uri)
                    is Avs3PlaybackCoordinator.Prepared.Failed ->
                        Prepared.Failed(res.message)
                }
            }

            is NcmPlaybackPlanner.Plan.Unsupported ->
                Prepared.Failed(plan.reason)
        }
    }

    /** 由源路径生成稳定 key（同一首歌重复打开能复用 store 条目）。 */
    private fun cacheKeyFor(srcPath: String): String =
        srcPath.hashCode().toUInt().toString(16) +
            "_" + File(srcPath).name.hashCode().toUInt().toString(16)
}
