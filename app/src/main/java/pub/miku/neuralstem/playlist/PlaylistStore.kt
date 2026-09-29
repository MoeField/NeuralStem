package pub.miku.neuralstem.playlist

import android.app.Application
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 歌单持久化（多列表 + 曲目）。
 *
 * ## 为何用 JSON 文件而非 Room/DataStore
 * 项目暂无 Room/DataStore（guide 未要求数据库）；歌单是轻量结构，
 * 一个 JSON 文件足够，且零新依赖。文件：`filesDir/playlists.json`。
 *
 * ## 数据模型（歌单/切歌打通后）
 * - 两种歌单：[ListType.FILE]（手动加、可排序）/ [ListType.FOLDER]（随外部文件夹、不排序）。
 * - **不变量**：单轨播放的曲目必须属于"当前歌单"（歌单 = 播放队列）。
 * - 每个歌单记 [Playlist.lastPlayedUri]/[Playlist.lastPositionMs]（"回到歌单继续播"）。
 * - **默认歌单**（[Playlist.isDefault]）：文件类型、不可删、可清空、不改名。
 *
 * ## 迁移（旧 JSON → 新模型）
 * - 旧数据无 `type` → 由 `folderUri` 推断（非空 = FOLDER，否则 FILE）。
 * - 旧数据无 `isDefault`/进度字段 → 默认值。
 * - **若无"默认歌单" → 自动创建一个并持久化**（保证"不切歌单就进默认歌单"成立）。
 */
class PlaylistStore(app: Application) {

    /** 歌单类型：文件选择 / 文件夹。 */
    enum class ListType { FILE, FOLDER }

    data class TrackRef(val uri: String, val title: String)

    data class Playlist(
        val id: String,
        val name: String,
        val type: ListType = ListType.FILE,
        val folderUri: String? = null,   // 仅 FOLDER 歌单
        val tracks: List<TrackRef> = emptyList(),
        /** 默认歌单标记：不可删、可清空、不改名。 */
        val isDefault: Boolean = false,
        /** 进度记忆：最后播放的曲目 uri + 位置(ms)。 */
        val lastPlayedUri: String? = null,
        val lastPositionMs: Long = 0L,
    )

    private val file = File(app.filesDir, PLAYLISTS_FILE)

    /** 默认歌单的稳定 id（便于"取默认歌单"）。 */
    val defaultListId: String = DEFAULT_LIST_ID

    /**
     * 读取歌单。**保证**：返回结果里**必有一个默认歌单**（没有则创建并持久化）。
     */
    fun load(): List<Playlist> {
        val raw = readRaw()
        val migrated = ensureDefault(raw)
        // ★ 只要规范化**产生了任何变化**（新增默认歌单，或改动了字段）就落盘，
        //   否则会出现“内存已修正、文件仍是旧值”的不一致（下次又读到旧的）。
        if (migrated != raw) {
            save(migrated)
        }
        return migrated
    }

    private fun readRaw(): List<Playlist> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tArr = o.optJSONArray("tracks") ?: JSONArray()
                val tracks = (0 until tArr.length()).map { j ->
                    val t = tArr.getJSONObject(j)
                    TrackRef(t.getString("uri"), t.optString("title"))
                }
                val folderUri = o.optString("folderUri").ifBlank { null }
                // 迁移：无 type → 由 folderUri 推断
                val type = o.optString("type").ifBlank { null }?.let {
                    runCatching { ListType.valueOf(it) }.getOrNull()
                } ?: if (folderUri != null) ListType.FOLDER else ListType.FILE
                Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = type,
                    folderUri = folderUri,
                    tracks = tracks,
                    isDefault = o.optBoolean("isDefault", false),
                    lastPlayedUri = o.optString("lastPlayedUri").ifBlank { null },
                    lastPositionMs = o.optLong("lastPositionMs", 0L),
                )
            }
        }.onFailure { Log.w(TAG, "load failed", it) }.getOrDefault(emptyList())
    }

    /** 确保存在一个默认歌单；若已有标记为 default 的，保留（且强制规范）。 */
    private fun ensureDefault(list: List<Playlist>): List<Playlist> {
        val existing = list.firstOrNull { it.isDefault }
        if (existing != null) {
            // 规范默认歌单：FILE 类型、不可删、名为"默认歌单"
            return list.map {
                if (it.isDefault) {
                    it.copy(type = ListType.FILE, isDefault = true, name = DEFAULT_LIST_NAME)
                } else it
            }
        }
        val def = Playlist(
            id = DEFAULT_LIST_ID,
            name = DEFAULT_LIST_NAME,
            type = ListType.FILE,
            isDefault = true,
        )
        return listOf(def) + list
    }

    /** 记某歌单“最后播放的曲目 + 位置”(ms)。（播放器直接调用，不经 PlaylistViewModel。） */
    fun recordProgress(listId: String, trackUri: String?, positionMs: Long) {
        val cur = load()
        var changed = false
        val next = cur.map {
            if (it.id == listId && (it.lastPlayedUri != trackUri || it.lastPositionMs != positionMs)) {
                changed = true
                it.copy(lastPlayedUri = trackUri, lastPositionMs = positionMs)
            } else it
        }
        if (changed) save(next)
    }

    fun save(list: List<Playlist>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    put("folderUri", p.folderUri ?: "")
                    put("isDefault", p.isDefault)
                    put("lastPlayedUri", p.lastPlayedUri ?: "")
                    put("lastPositionMs", p.lastPositionMs)
                    put("tracks", JSONArray().apply {
                        p.tracks.forEach { t ->
                            put(JSONObject().apply {
                                put("uri", t.uri)
                                put("title", t.title)
                            })
                        }
                    })
                })
            }
            file.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "save failed", it) }
    }

    companion object {
        private const val TAG = "PlaylistStore"
        private const val PLAYLISTS_FILE = "playlists.json"

        /** 默认歌单的固定 id（避免"找不到默认歌单"）。 */
        const val DEFAULT_LIST_ID = "default"
        const val DEFAULT_LIST_NAME = "默认歌单"
    }
}
