package pub.miku.neuralstem.playlist

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
/** 按系统实际记录的权限位组合 release flags；没有权限返回 0。 */
internal fun grantReleaseFlags(read: Boolean, write: Boolean): Int {
    var flags = 0
    if (read) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
    if (write) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return flags
}

/** 启动清理：系统里仍有持久授权、但已不被任何歌单引用的 uri。 */
internal fun orphanGrantUris(persistedUris: Collection<String>, referencedUris: Set<String>): List<String> =
    persistedUris.filterNot { it in referencedUris }

class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PlaylistStore(app)

    /** 默认歌单 id（保证存在——由 store.load() 保证）。 */
    val defaultListId: String get() = store.defaultListId

    // ★P2.1：歌单文件读写统一走单线程 IO，避免主线程 fsync/解析 1000+ 首。
    // ★P1-13：复用 viewModelScope 的 Job（随 VM 取消），只覆盖调度器为单线程 IO。
    private val ioWorker = CoroutineScope(
        viewModelScope.coroutineContext + Dispatchers.IO.limitedParallelism(1),
    )

    private val _lists = MutableStateFlow<List<PlaylistStore.Playlist>>(emptyList())
    val lists: StateFlow<List<PlaylistStore.Playlist>> = _lists.asStateFlow()

    /** 磁盘上的 playlists.json 是否损坏；损坏期间保存会被拒绝，UI 必须明确提示。 */
    private val _corrupt = MutableStateFlow(false)
    val corrupt: StateFlow<Boolean> = _corrupt.asStateFlow()

    /** 最近一次歌单落盘失败；null = 正常。 */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    @Volatile private var loaded = false

    init {
        ioWorker.launch {
            _lists.value = store.load()
            loaded = true
            _corrupt.value = store.isCorrupt
            _saveError.value = null
            ensureValidSelection()
            cleanupOrphanGrants()
        }
    }

    /**
     * 释放“系统里还留着、但已无歌单引用”的 persistable grant。
     * 只清单文件 grant；tree grant 由文件夹歌单的 folderUri 持有，删除时已释放。
     */
    private fun cleanupOrphanGrants() {
        val cr = getApplication<Application>().contentResolver
        val referenced = buildSet {
            _lists.value.forEach { p ->
                p.tracks.forEach { add(it.uri) }
                p.folderUri?.let(::add)
            }
        }
        val persisted = cr.persistedUriPermissions
        val orphans = orphanGrantUris(persisted.map { it.uri.toString() }, referenced)
        if (orphans.isEmpty()) return
        val byUri = persisted.associateBy { it.uri.toString() }
        orphans.forEach { uri ->
            val perm = byUri[uri] ?: return@forEach
            val flags = grantReleaseFlags(perm.isReadPermission, perm.isWritePermission)
            if (flags == 0) return@forEach
            runCatching {
                cr.releasePersistableUriPermission(Uri.parse(uri), flags)
                Log.i(TAG, "cleanup orphan grant: $uri")
            }.onFailure { Log.w(TAG, "cleanup orphan grant failed: $uri", it) }
        }
    }

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

    /** 异步落盘；saveMerged 在 IO_LOCK 内合并播放器直写的进度。 */
    private fun persist() {
        if (!loaded) {
            Log.w(TAG, "persist skipped: playlists not loaded yet")
            return
        }
        val snapshot = _lists.value
        ioWorker.launch {
            val ok = store.saveMerged(snapshot)
            _saveError.value = if (ok) null else "歌单保存失败：请检查存储空间/权限"
        }
    }

    /**
     * 重新从磁盘加载。
     *
     * 为何需要：歌单可能被**其它入口**修改（如 `PlayerViewModel` 导入/增删曲目直接写文件），
     * 而本 VM 的 `_lists` 是内存缓存 → 会显示旧数据。进入歌单页时调一次即可对齐。
     */
    fun reload() {
        ioWorker.launch {
            _lists.value = store.load()
            loaded = true
            _corrupt.value = store.isCorrupt
            ensureValidSelection()
            cleanupOrphanGrants()
        }
    }

    /**
     * 用户显式确认后，把损坏的歌单文件重建成空默认歌单。
     * 原损坏文件已由 Store 保留为 `.corrupt-*`，不会静默丢现场。
     */
    fun recoverCorruptPlaylist() {
        ioWorker.launch {
            val ok = store.resetCorruptToDefault()
            _lists.value = store.load()
            loaded = true
            _corrupt.value = store.isCorrupt
            ensureValidSelection()
            Log.i(TAG, "recoverCorruptPlaylist ok=$ok corrupt=${store.isCorrupt}")
        }
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

    /** 单文件 grant：没有其他歌单引用时才释放 READ。 */
    private fun releaseFileGrantIfUnused(uri: String) {
        if (_lists.value.any { p -> p.tracks.any { it.uri == uri } }) return
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            Log.i(TAG, "released file grant: $uri")
        }.onFailure { Log.w(TAG, "release file grant failed: $uri", it) }
    }

    /** tree grant：按系统实际记录的 READ/WRITE 位释放，避免 READ|WRITE 合并导致异常。 */
    private fun releaseTreeGrant(uri: String) {
        val parsed = Uri.parse(uri)
        val cr = getApplication<Application>().contentResolver
        val perm = cr.persistedUriPermissions.firstOrNull { it.uri == parsed } ?: return
        val flags = grantReleaseFlags(perm.isReadPermission, perm.isWritePermission)
        if (flags == 0) return
        runCatching {
            cr.releasePersistableUriPermission(parsed, flags)
            Log.i(TAG, "released tree grant: $uri flags=$flags")
        }.onFailure { Log.w(TAG, "release tree grant failed: $uri", it) }
    }

    /** 删除整个歌单（不动文件）；**默认歌单禁止删除**。 */
    fun deleteList(id: String) {
        val target = _lists.value.firstOrNull { it.id == id } ?: return
        if (target.isDefault) {
            Log.w(TAG, "refuse deleting default list")
            return
        }
        // ★B6：文件夹歌单删除时释放 SAF tree 持久授权，避免授权槽位耗尽/旧 grant 残留。
        // ★P2：同一个 tree 可能被多个文件夹歌单引用；只在最后一个引用删除时释放，
        //   并按 take 时的 READ|WRITE 一并释放。
        val folderUri = target.folderUri
        val shared = folderUri != null && _lists.value.any { it.id != id && it.folderUri == folderUri }
        val removedTracks = target.tracks.map { it.uri }
        _lists.value = _lists.value.filterNot { it.id == id }
        if (!shared && folderUri != null) releaseTreeGrant(folderUri)
        removedTracks.forEach(::releaseFileGrantIfUnused)
        persist()
        // ★R5：删了当前选中的歌单 → 回落默认（保证选中态恒存在）。
        ensureValidSelection()
    }

    /** 清空某歌单的所有曲目（**默认歌单也允许**；不动磁盘文件）。 */
    fun clearList(id: String) {
        val removed = _lists.value.firstOrNull { it.id == id }?.tracks?.map { it.uri }.orEmpty()
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(tracks = emptyList(), lastPlayedUri = null, lastPositionMs = 0L) else it
        }
        persist()
        removed.forEach(::releaseFileGrantIfUnused)
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
        releaseFileGrantIfUnused(trackUri)
    }

    /** 重新授权：把旧 uri 替换为重新选择后的 uri（保留标题与列表位置）。 */
    fun replaceTrackUri(listId: String, oldUri: String, newUri: String) {
        if (newUri.isBlank() || newUri == oldUri) return
        _lists.value = _lists.value.map { p ->
            if (p.id != listId) p
            else p.copy(
                tracks = p.tracks.map { t -> if (t.uri == oldUri) t.copy(uri = newUri) else t },
                lastPlayedUri = if (p.lastPlayedUri == oldUri) newUri else p.lastPlayedUri,
            )
        }
        persist()
        releaseFileGrantIfUnused(oldUri)
        Log.i(TAG, "replace track uri in $listId: ${oldUri.take(24)}… → ${newUri.take(24)}…")
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
    /** ★P2：导入放到 VM scope，切 tab/离开歌单页不会取消长扫描。 */
    fun importFilesAsync(uris: List<Uri>, intoListId: String? = null) {
        viewModelScope.launch { importFiles(uris, intoListId) }
    }

    fun importFolderAsync(treeUri: Uri, intoListId: String? = null) {
        viewModelScope.launch { importFolder(treeUri, intoListId) }
    }

    fun rescanFolderAsync(listId: String) {
        viewModelScope.launch { rescanFolder(listId) }
    }

    suspend fun importFolder(treeUri: Uri, intoListId: String? = null, replace: Boolean = false) {
        val ctx = getApplication<Application>()
        // ★诊断：确认能从 tree uri 拿到目录（拿不到通常是没授权）
        //
        // ★2026-10-26：fromTreeUri 对**非 tree uri**（如单文件 `document/...`）
        //   会抛 IllegalArgumentException，而不是返回 null。
        //   之前实测就被这个坑卡住：一个错误的 uri 会让整个导入流程静默中断。
        //   所以必须 runCatching 包住。
        val root = runCatching { DocumentFile.fromTreeUri(ctx, treeUri) }
            .onFailure { Log.w(TAG, "importFolder: fromTreeUri 异常（uri 已脱敏）", it) }
            .getOrNull()
        Log.i(
            TAG,
            "importFolder: treeUri=${treeUri.redacted()} root=${root?.name} canRead=${root?.canRead()}",
        )
        if (root == null) {
            Log.w(TAG, "importFolder: fromTreeUri 返回 null（未授权？）")
            return
        }
        val found = withContext(Dispatchers.IO) { scanAudio(ctx, treeUri) }
        Log.i(TAG, "importFolder: ${found.size} audio files from selected folder")
        found.take(3).forEach { Log.i(TAG, "   例：${it.title}") }
        val name = root.name ?: "导入的文件夹"
        _lists.value = if (intoListId != null) {
            _lists.value.map { p ->
                // ★ 默认歌单**永不变为 FOLDER**（它是文件歌单；即便被误传也不改）。
                if (p.id == intoListId && !p.isDefault) {
                    p.copy(
                        type = PlaylistStore.ListType.FOLDER,
                        folderUri = treeUri.toString(),
                        // rescan 用 replace（跟随文件夹的最新内容）；普通导入用合并。
                        tracks = if (replace) found else mergeDistinct(p.tracks, found),
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
        // ★刷新文件夹必须是**替换**：合并会把已删除/不再支持的条目（如公开版过滤掉的 .ncm）永久留在歌单里。
        importFolder(Uri.parse(f), intoListId = listId, replace = true)
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
     * ## 为什么保留 `content://` 而不转真实路径（2026-10-26 修正）
     * 旧版对 `.m4a` / `.ncm` 用 [SafPathResolver] 转成 `file:///storage/...` 绝对路径，
     * 理由是「AVS3 解码要 `File.readBytes()`」。
     * 但本包 `targetSdk=37`，Android 14+ 强制分区存储：
     * **SAF 的 `tree/...` 授权只对 `content://` 通道有效，对 `file://` 完全无效** →
     * 真机 `EACCES (Permission denied)`，歌"能看到但放不了"。
     * 现在读取统一走 [MediaSourceReader]（`ContentResolver.openInputStream()`），
     * 所以这里**原样保留 `content://`**。
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

                // ★保留 content://（见上方注释）。file:// 传进来的（如播放器"导入音频"）也原样保留。
                PlaylistStore.TrackRef(u.toString(), name)
            }
        }
        Log.i(TAG, "importFiles: ${found.size}/${uris.size} 首可导入")
        if (found.isEmpty()) return

        val targetId = intoListId ?: selectedListId.value
        val target = _lists.value.firstOrNull { it.id == targetId }
        if (target?.type != PlaylistStore.ListType.FILE) {
            // R2：单曲导入只能进 FILE 歌单；文件夹歌单是外部镜像，不应被塞入无法移除的曲目。
            Log.w(TAG, "importFiles 拒绝目标 $targetId（type=${target?.type}）")
            return
        }
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
     * ## 为何**不再**转换真实路径（2026-10-26 修正）
     * 旧版对 `.m4a` / `.ncm` 走 [SafPathResolver] 转 `file://` 绝对路径，
     * 理由是「AVS3 解码要 `File.readBytes()` / NCM 解密要路径」。
     *
     * 但真机实测（BF00A，targetSdk=37）：转成绝对路径后**必然 `EACCES`**，
     * 因为 Android 14+ 的分区存储下，SAF 的 `tree/...` 授权只对 `content://` 通道有效，
     * 不覆盖 `file://` 走内核 `open()` 的路径。
     *
     * 现在统一由 [MediaSourceReader] 经 `ContentResolver.openInputStream()` 读取，
     * 所以这里**一律保留 `content://`**。
     *
     * > 唯一例外：`netease://`（网易云私有目录需 Shizuku）不经过这里，
     * > 由 `NeteasePlaylistImporter`（ncm 源集）单独产生。
     */
    private fun scanAudio(ctx: Context, treeUri: Uri): List<PlaylistStore.TrackRef> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: run {
            Log.w(TAG, "scanAudio: fromTreeUri(null)")
            return emptyList()
        }
        val out = ArrayList<PlaylistStore.TrackRef>()

        fun walk(dir: DocumentFile, depth: Int) {
            // listFiles 在没授权时返回空数组（不抛异常）—— 必须打出来，
            // 否则表现为“导入后什么都没有”，完全看不出哪里错。
            val children = runCatching { dir.listFiles() }.getOrElse {
                Log.w(TAG, "scanAudio: listFiles 异常 @${dir.name}", it)
                return
            }
            Log.i(
                TAG,
                "scanAudio: walk " + "  ".repeat(depth) + (dir.name ?: "?") +
                    " → ${children.size} 项",
            )
            for (f in children) {
                if (f.isDirectory) {
                    walk(f, depth + 1)
                    continue
                }
                if (!f.isFile) continue
                val name = f.name ?: continue
                if (!isAudio(name)) {
                    Log.i(TAG, "scanAudio: 跳过（非音频）$name")
                    continue
                }

                // ★一律保留 content://（读取走 MediaSourceReader）。
                out.add(PlaylistStore.TrackRef(f.uri.toString(), name))
            }
        }
        walk(root, 0)
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
