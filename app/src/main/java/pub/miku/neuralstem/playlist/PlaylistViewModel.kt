package pub.miku.neuralstem.playlist

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 歌单 VM（多列表 + 文件夹导入 + 增删 + 排序 + 进度记忆）。
 *
 * 持久化经 [PlaylistStore]（JSON）。
 * - **两类歌单**：文件（可排序/手动加）与文件夹（随外部文件夹、不排序）。
 * - **默认歌单**（[PlaylistStore.Playlist.isDefault]）：不可删、可清空、不改名。
 * - 歌单顺序 = 播放顺序（文件可用 [moveTrack] 排序；文件夹不允许）。
 */
class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PlaylistStore(app)

    /** 默认歌单 id（保证存在——由 store.load() 保证）。 */
    val defaultListId: String get() = store.defaultListId

    private val _lists = MutableStateFlow(store.load())
    val lists: StateFlow<List<PlaylistStore.Playlist>> = _lists.asStateFlow()

    private fun persist() = store.save(_lists.value)

    /**
     * 重新从磁盘加载。
     *
     * 为何需要：歌单可能被**其它入口**修改（如 `PlayerViewModel` 导入/增删曲目直接写文件），
     * 而本 VM 的 `_lists` 是内存缓存 → 会显示旧数据。进入歌单页时调一次即可对齐。
     */
    fun reload() {
        _lists.value = store.load()
    }

    // ---------------- 新建 ----------------

    /** 新建**文件歌单**（手动加、可排序）。 */
    fun createFileList(name: String) {
        if (name.isBlank()) return
        val p = PlaylistStore.Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            type = PlaylistStore.ListType.FILE,
        )
        _lists.value = _lists.value + p
        persist()
    }

    /** 兼容旧调用（默认建文件歌单）。 */
    fun createList(name: String) = createFileList(name)

    // ---------------- 删除 ----------------

    /** 删除整个歌单（不动文件）；**默认歌单禁止删除**。 */
    fun deleteList(id: String) {
        val target = _lists.value.firstOrNull { it.id == id } ?: return
        if (target.isDefault) {
            Log.w(TAG, "refuse deleting default list")
            return
        }
        _lists.value = _lists.value.filterNot { it.id == id }
        persist()
    }

    /** 清空某歌单的所有曲目（**默认歌单也允许**；不动磁盘文件）。 */
    fun clearList(id: String) {
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(tracks = emptyList(), lastPlayedUri = null, lastPositionMs = 0L) else it
        }
        persist()
    }

    // ---------------- 曲目增删/排序 ----------------

    /** 从歌单移除一首（不动文件）。 */
    fun removeTrack(listId: String, trackUri: String) {
        _lists.value = _lists.value.map { p ->
            if (p.id == listId) {
                val left = p.tracks.filterNot { it.uri == trackUri }
                val lp = if (p.lastPlayedUri == trackUri) null else p.lastPlayedUri
                p.copy(tracks = left, lastPlayedUri = lp)
            } else p
        }
        persist()
    }

    /**
     * 把 [track] 插入到 [listId] 的 [insertIndex]（越界则追加到末尾）。
     * 若 uri 已存在：先移除旧的再插入（保持"位置更新"语义）。
     */
    fun addTrack(listId: String, track: PlaylistStore.TrackRef, insertIndex: Int) {
        _lists.value = _lists.value.map { p ->
            if (p.id != listId) return@map p
            val without = p.tracks.filterNot { it.uri == track.uri }
            val idx = insertIndex.coerceIn(0, without.size)
            val out = without.toMutableList().apply { add(idx, track) }
            p.copy(tracks = out)
        }
        persist()
    }

    /** 追加到末尾。 */
    fun appendTrack(listId: String, track: PlaylistStore.TrackRef) {
        val size = _lists.value.firstOrNull { it.id == listId }?.tracks?.size ?: 0
        addTrack(listId, track, size)
    }

    /**
     * **文件歌单**排序：把 [from] 移到 [to]。
     * 文件夹歌单不允许 → 忽略（日志）。
     */
    fun moveTrack(listId: String, from: Int, to: Int) {
        _lists.value = _lists.value.map { p ->
            if (p.id != listId) return@map p
            if (p.type != PlaylistStore.ListType.FILE) {
                Log.w(TAG, "moveTrack ignored: folder list is not sortable")
                return@map p
            }
            if (from !in p.tracks.indices) return@map p
            val m = p.tracks.toMutableList()
            val item = m.removeAt(from)
            m.add(to.coerceIn(0, m.size), item)
            p.copy(tracks = m)
        }
        persist()
    }

    // ---------------- 进度记忆 ----------------

    /** 记录某歌单"最后播放的曲目 + 位置(ms)"。 */
    fun recordProgress(listId: String, trackUri: String?, positionMs: Long) {
        _lists.value = _lists.value.map {
            if (it.id == listId) it.copy(lastPlayedUri = trackUri, lastPositionMs = positionMs) else it
        }
        persist()
    }

    // ---------------- 查询工具 ----------------

    fun listById(id: String): PlaylistStore.Playlist? = _lists.value.firstOrNull { it.id == id }

    /**
     * 逐出失效项：移除"文件已不可访问"的曲目（uri 失效）。
     * **同步**判断（SAF 路径）——调用方应在 IO 线程。
     */
    fun filterExisting(listId: String, exists: (String) -> Boolean) {
        _lists.value = _lists.value.map { p ->
            if (p.id == listId) p.copy(tracks = p.tracks.filter { exists(it.uri) }) else p
        }
        persist()
    }

    // ---------------- 文件夹导入 ----------------

    /**
     * 把 SAF Tree URI（文件夹）作为**文件夹歌单**导入：**递归扫描音频**。
     * 若 [intoListId] 为 null 则新建一个以文件夹名为名的歌单。
     */
    suspend fun importFolder(treeUri: Uri, intoListId: String? = null) {
        val ctx = getApplication<Application>()
        val found = withContext(Dispatchers.IO) { scanAudio(ctx, treeUri) }
        Log.i(TAG, "importFolder: ${found.size} audio files from $treeUri")
        val name = DocumentFile.fromTreeUri(ctx, treeUri)?.name ?: "导入的文件夹"
        _lists.value = if (intoListId != null) {
            _lists.value.map { p ->
                // ★ 默认歌单**永不变为 FOLDER**（它是文件歌单；即便被误传也不改）。
                if (p.id == intoListId && !p.isDefault) {
                    p.copy(
                        type = PlaylistStore.ListType.FOLDER,
                        folderUri = treeUri.toString(),
                        tracks = mergeDistinct(p.tracks, found),
                    )
                } else p
            }
        } else {
            _lists.value + PlaylistStore.Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                type = PlaylistStore.ListType.FOLDER,
                folderUri = treeUri.toString(),
                tracks = found,
            )
        }
        persist()
    }

    /** 刷新某歌单的文件夹（补入新增音频；已知的保留）。 */
    suspend fun rescanFolder(listId: String) {
        val p = _lists.value.firstOrNull { it.id == listId } ?: return
        val f = p.folderUri ?: return
        importFolder(Uri.parse(f), intoListId = listId)
    }

    private fun mergeDistinct(
        old: List<PlaylistStore.TrackRef>,
        new: List<PlaylistStore.TrackRef>,
    ): List<PlaylistStore.TrackRef> {
        val byUri = LinkedHashMap<String, PlaylistStore.TrackRef>()
        old.forEach { byUri[it.uri] = it }
        new.forEach { byUri[it.uri] = it }
        return byUri.values.toList()
    }

    /** 递归扫描 Tree URI 下的音频文件。 */
    private fun scanAudio(ctx: Context, treeUri: Uri): List<PlaylistStore.TrackRef> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val out = ArrayList<PlaylistStore.TrackRef>()
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) walk(f)
                else if (f.isFile && isAudio(f.name ?: "")) {
                    out.add(PlaylistStore.TrackRef(f.uri.toString(), f.name ?: "unknown"))
                }
            }
        }
        walk(root)
        return out
    }

    private fun isAudio(name: String): Boolean {
        val n = name.lowercase()
        return listOf(".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg")
            .any { n.endsWith(it) }
    }

    companion object {
        private const val TAG = "PlaylistVm"
    }
}
