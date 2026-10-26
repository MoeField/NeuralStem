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

    // ============================ ★R1：当前选中歌单（恒存在，不为空） ============================
    // 需求（用户定）：**任何时候都存在唯一“选中歌单”**；若未明确选中 / 选中的被删除 →
    // 回落默认歌单。绝不允许“无选中”状态。
    // - R2：导入只能进“选择型（FILE）”歌单（文件夹歌单不提供导入）。
    // - R5：删除当前歌单（非默认）→ 回落默认。

    private val _selectedListId = MutableStateFlow(store.defaultListId)
    val selectedListId: StateFlow<String> = _selectedListId.asStateFlow()

    /** 当前选中的歌单（恒非空——由 [ensureValidSelection] 保证）。 */
    val selectedList: PlaylistStore.Playlist?
        get() = _lists.value.firstOrNull { it.id == _selectedListId.value }

    /** 选中某歌单（不存在则忽略）。 */
    fun selectList(id: String) {
        if (_lists.value.any { it.id == id }) _selectedListId.value = id
    }

    /**
     * ★R1/R5：保证选中态指向一个**存在**的歌单；否则回落默认歌单。
     * 在 [reload]/[deleteList] 后调用。
     */
    private fun ensureValidSelection() {
        val cur = _selectedListId.value
        val exists = _lists.value.any { it.id == cur }
        if (!exists) {
            val fallback = if (_lists.value.any { it.id == defaultListId }) defaultListId
            else _lists.value.firstOrNull()?.id
            if (fallback != null && fallback != cur) {
                Log.i(TAG, "selected list $cur 不存在 → 回落 $fallback")
                _selectedListId.value = fallback
            }
        }
    }

    private fun persist() = store.save(_lists.value)

    /**
     * 重新从磁盘加载。
     *
     * 为何需要：歌单可能被**其它入口**修改（如 `PlayerViewModel` 导入/增删曲目直接写文件），
     * 而本 VM 的 `_lists` 是内存缓存 → 会显示旧数据。进入歌单页时调一次即可对齐。
     */
    fun reload() {
        _lists.value = store.load()
        ensureValidSelection()
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
        // ★R5：删了当前选中的歌单 → 回落默认（保证选中态恒存在）。
        ensureValidSelection()
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

    // ---------------- 单曲导入（2026-10-26）----------------

    /**
     * 把选中的若干文件加进歌单。
     *
     * ## 与文件夹导入的区别
     * - 文件夹导入 → **新建**一个文件夹歌单（随外部文件同步）
     * - 单曲导入 → 追加进**当前选中**的歌单（不创建新歌单）
     *
     * 后者更符合“我看到一首歌，想加进现有列表”的直觉。
     *
     * ## 真实路径处理
     * 与 `scanAudio` 一致：`.m4a` / `.ncm` 必须解析成真实路径，
     * 否则 AVS3 解码与 NCM 解密都做不了。
     */
    suspend fun importFiles(uris: List<Uri>, intoListId: String? = null) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()

        val found = withContext(Dispatchers.IO) {
            uris.mapNotNull { u ->
                val name = runCatching {
                    DocumentFile.fromSingleUri(ctx, u)?.name
                }.getOrNull() ?: u.lastPathSegment ?: return@mapNotNull null

                if (!isAudio(name)) {
                    Log.i(TAG, "importFiles 跳过非音频：$name")
                    return@mapNotNull null
                }

                val uriStr = if (SafPathResolver.needsRealPath(name)) {
                    SafPathResolver.resolve(ctx, u)?.let {
                        Uri.fromFile(java.io.File(it)).toString()
                    } ?: u.toString()
                } else {
                    u.toString()
                }
                PlaylistStore.TrackRef(uriStr, name)
            }
        }
        Log.i(TAG, "importFiles: ${found.size}/${uris.size} 首可导入")
        if (found.isEmpty()) return

        val targetId = intoListId ?: selectedListId.value
        _lists.value = _lists.value.map { p ->
            if (p.id == targetId) p.copy(tracks = mergeDistinct(p.tracks, found)) else p
        }
        persist()
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

    /**
     * 递归扫描 Tree URI 下的音频文件。
     *
     * ## 为何要处理真实路径（2026-10-26）
     * SAF 拿到的是 `content://`。普通的 mp3/flac 可以直接用，
     * 但 **AVS3（.m4a）与 NCM（.ncm）必须有真实路径**：
     * - AVS3 解码要 `File.readBytes()`
     * - NCM 解密要路径（还要跟 `netease://` 体系对得上）
     *
     * 所以对这两类尝试 `SafPathResolver` 解析；
     * 解析不了就退回 `content://`（至少能加进歌单，播不了时 UI 会提示）。
     */
    private fun scanAudio(ctx: Context, treeUri: Uri): List<PlaylistStore.TrackRef> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val out = ArrayList<PlaylistStore.TrackRef>()

        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    walk(f)
                    continue
                }
                if (!f.isFile) continue
                val name = f.name ?: continue
                if (!isAudio(name)) continue

                // ★需要真实路径的（AVS3 / NCM）优先解析
                val uriStr = if (SafPathResolver.needsRealPath(name)) {
                    val real = SafPathResolver.resolve(ctx, f.uri)
                    if (real != null) {
                        android.net.Uri.fromFile(java.io.File(real)).toString()
                    } else {
                        // 解析不了：保留 content://，至少可见
                        Log.w(TAG, "需要真实路径但解析失败，退回 content://: $name")
                        f.uri.toString()
                    }
                } else {
                    f.uri.toString()
                }

                out.add(PlaylistStore.TrackRef(uriStr, name))
            }
        }
        walk(root)
        return out
    }

    /**
     * 是不是音频扩展名。
     *
     * ★2026-10-26：实现搬到 [AudioFileTypes]（顶层对象，可 JVM 单测）。
     * 以前是 `private fun`，导致“文件夹导入”与“单曲导入”
     * 各维护一份白名单，而且没法测 —— 实测就因为漏了 `.ncm`
     * 让 732 首网易云的歌全部进不了歌单。
     */
    private fun isAudio(name: String): Boolean = AudioFileTypes.isAudio(name)

    companion object {
        private const val TAG = "PlaylistVm"
    }
}
