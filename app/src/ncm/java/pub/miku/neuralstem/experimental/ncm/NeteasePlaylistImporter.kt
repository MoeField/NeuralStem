package pub.miku.neuralstem.experimental.ncm

import android.content.Context
import android.util.Log

/**
 * ★把网易云私有目录里的音乐批量导入**歌单**。
 *
 * ## 解决什么问题
 * 之前只有「NCM 导入」页能扫到文件，但它们**进不了歌单** ——
 * 用户导入完 1001 首，歌单页还是空的。等于导入了个寂寞。
 *
 * ## 关键难点：歌单里存什么 uri
 *
 * 网易云的文件在 `/storage/XXXX/Android/data/com.netease.cloudmusic/...`，
 * **App 读不了**（实测 `Permission denied`）。所以：
 *
 * | 方案 | 问题 |
 * |---|---|
 * | 存 `file://` | 播不了（无权限） |
 * | 复制到 App 目录 | 一首 20~30MB，1000 首要 25GB，不可接受 |
 * | **存逻辑引用 + 播放时借 Shizuku 读** | ✅ 采用 |
 *
 * ⇒ 存 `netease://<URL编码的绝对路径>`，播放时解回路径、用 Shizuku 读。
 *   歌单本身不依赖文件是否已复制，也不占额外空间。
 *
 * ## 哪些能播
 * 扫描时逐个探 codec（只读文件头 256KB）并标注：
 * - `av3a` → 需自编译解码器
 * - `mp4a`/`alac`/flac/mp3 → 原生可播
 * - `ncm` → 需先解密
 *
 * 这个标注很重要：用户导入 1001 首后应该**一眼看出**哪些能播，
 * 而不是点了没反应。
 */
object NeteasePlaylistImporter {

    private const val TAG = "NeteaseImport"

    /** 导入统计。 */
    data class Result(
        val playableCount: Int,
        val avs3Count: Int,
        val ncmCount: Int,
        val unknownCount: Int,
    ) {
        val total: Int get() = playableCount + avs3Count + ncmCount + unknownCount

        fun summary(): String = buildString {
            append("共 $total 首：")
            append("可直接播 $playableCount")
            if (avs3Count > 0) append("，AVS3 需解码器 $avs3Count")
            if (ncmCount > 0) append("，NCM 需解密 $ncmCount")
            if (unknownCount > 0) append("，未知 $unknownCount")
        }
    }

    /** 一首可入歌单的曲目。 */
    data class ScannedTrack(
        val uri: String,
        val title: String,
        val kind: Kind,
    ) {
        enum class Kind { PLAYABLE, AVS3, NCM, UNKNOWN }
    }

    /**
     * 扫描网易云私有目录，返回可入歌单的曲目（**不写歌单**）。
     *
     * 分成「扫描」和「写入」两步：扫描要读上千个文件头（慢，需进度）；
     * 写入只是改 JSON（快）。
     */
    fun scan(
        context: Context,
        limit: Int = Int.MAX_VALUE,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): List<ScannedTrack> {
        val dirs = NcmImporter.findSourceDirs()
        val out = mutableListOf<ScannedTrack>()

        for (dir in dirs) {
            val entries = runCatching { NcmImporter.listNcm(dir) }.getOrDefault(emptyList())
            if (entries.isEmpty()) continue

            val targets = entries.take((limit - out.size).coerceAtLeast(0))
            Log.i(TAG, "扫描 $dir：${targets.size} 个文件")

            for ((i, e) in targets.withIndex()) {
                onProgress?.invoke(out.size + i, targets.size, e.name)
                val kind = when {
                    e.isEncrypted -> ScannedTrack.Kind.NCM
                    // flac/mp3/aac 等：原生可播，不用探
                    e.ext != "m4a" -> ScannedTrack.Kind.PLAYABLE
                    // m4a 要探内部 codec（实测 220 个 m4a 里 219 个是 av3a）
                    Avs3PlaybackCoordinator.isAvs3Path(e.absolutePath) ->
                        ScannedTrack.Kind.AVS3
                    else -> ScannedTrack.Kind.PLAYABLE
                }
                out += ScannedTrack(
                    uri = Avs3PlaybackCoordinator.toPlayableUri(e.absolutePath).toString(),
                    title = e.name.substringBeforeLast('.').ifBlank { e.name },
                    kind = kind,
                )
            }
            if (out.size >= limit) break
        }
        Log.i(TAG, "扫描完成：${out.size} 首")
        return out
    }

    /** 按类型统计。 */
    fun summarize(tracks: List<ScannedTrack>): Result = Result(
        playableCount = tracks.count { it.kind == ScannedTrack.Kind.PLAYABLE },
        avs3Count = tracks.count { it.kind == ScannedTrack.Kind.AVS3 },
        ncmCount = tracks.count { it.kind == ScannedTrack.Kind.NCM },
        unknownCount = tracks.count { it.kind == ScannedTrack.Kind.UNKNOWN },
    )

    /**
     * 转成歌单用的 `(uri, title)` 列表，标题加状态后缀。
     *
     * 用文字后缀而不是改数据结构：歌单行已有 `uri` 与 `title` 两个字段，
     * 加字段要动 JSON 迁移，成本不划算。
     */
    fun toPlaylistEntries(tracks: List<ScannedTrack>): List<Pair<String, String>> =
        tracks.map { t ->
            val suffix = when (t.kind) {
                ScannedTrack.Kind.PLAYABLE -> ""
                ScannedTrack.Kind.AVS3 -> " [需解码]"
                ScannedTrack.Kind.NCM -> " [加密]"
                ScannedTrack.Kind.UNKNOWN -> " [未知]"
            }
            t.uri to (t.title + suffix)
        }

    /**
     * 把扫描结果**写入歌单**（默认歌单），返回新增加的数量。
     *
     * ## 为何直接操作 PlaylistStore 而不是走 PlaylistViewModel
     * 本文件在 `ncm` 源集，拿不到 Compose 作用域里的 ViewModel，
     * 而 `PlaylistViewModel` 在 `main` 源集又反过来依赖不了 ncm。
     * 直接用它依赖的 `PlaylistStore` 最干净（ncm 已依赖 main）。
     *
     * ⚠️ 写入后需要通知 UI 刷新（歌单页可能已经加载过了）。
     *    目前由调用方在切回歌单页时重新 `reload()` 解决。
     *
     * ## 去重
     * 按 `uri` 去重 —— 重复点「导入」不会产生重复条目。
     */
    fun importToPlaylist(
        context: Context,
        tracks: List<ScannedTrack>,
    ): Int {
        if (tracks.isEmpty()) return 0

        val store = pub.miku.neuralstem.playlist.PlaylistStore(
            context.applicationContext as android.app.Application,
        )
        val lists = store.load().toMutableList()
        val idx = lists.indexOfFirst { it.id == store.defaultListId }
        if (idx < 0) {
            Log.w(TAG, "找不到默认歌单，放弃导入")
            return 0
        }

        val target = lists[idx]
        val existing = target.tracks.map { it.uri }.toHashSet()
        val added = mutableListOf<pub.miku.neuralstem.playlist.PlaylistStore.TrackRef>()

        for ((uri, title) in toPlaylistEntries(tracks)) {
            if (uri in existing) continue
            existing += uri
            added += pub.miku.neuralstem.playlist.PlaylistStore.TrackRef(uri, title)
        }

        if (added.isEmpty()) {
            Log.i(TAG, "无新增（全部已存在）")
            return 0
        }

        lists[idx] = target.copy(tracks = target.tracks + added)
        store.save(lists)
        Log.i(TAG, "写入歌单 ${added.size} 首（共 ${lists[idx].tracks.size} 首）")
        return added.size
    }
}
