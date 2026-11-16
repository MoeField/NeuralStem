package pub.miku.neuralstem.player.pcm

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前 App 内共享 PCM 缓存的**唯一入口**（Phase 1）。
 *
 * 能力：
 * - 按 [SourceDescriptor.cacheKey] 单飞：同一 key 同时只有一个 decoder 在跑
 * - 完整 decode 后原子发布为 [PcmArtifact]
 * - [PcmLease] 引用计数，使用中不允许 LRU 删除
 * - 当前曲可 pin，LRU 不删
 *
 * 本阶段的 decoder 由外层注入；Phase 2 再接 MediaCodec/PcmDecodePipeline。
 */
class PcmStore(
    cacheDir: File,
    private val scope: CoroutineScope,
    private val decoder: PcmDecoder,
    /** 默认 12GB；当前曲 pin，不会被 LRU 删除。 */
    private val maxBytes: Long = 12L * 1024L * 1024L * 1024L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun interface PcmDecoder {
        /**
         * 把 source 解码到 [output]（临时文件），并周期性回调已写字节数。
         * 只有返回 true 才代表完整 EOS；false/throw = 失败。
         */
        suspend fun decode(
            source: SourceDescriptor,
            output: File,
            onProgress: (bytesWritten: Long, envelope: pub.miku.neuralstem.player.WaveformData?) -> Unit,
        ): PcmArtifact?
    }

    private data class Entry(
        val source: SourceDescriptor,
        var state: State,
        var leases: Int = 0,
        var waiters: Int = 0,
        var pinned: Boolean = false,
        var lastAccessMs: Long = 0L,
    )

    private sealed interface State {
        data class Decoding(val session: PcmSession) : State
        data class Ready(val artifact: PcmArtifact) : State
        data class Failed(val message: String) : State
    }

    private val dir = cacheDir.apply { if (!exists()) mkdirs() }

    init {
        // 进程被杀后可能留下 tmp-*；只清理 1 小时前的，避免误删正在写的。
        runCatching {
            val now = System.currentTimeMillis()
            dir.listFiles { f -> f.name.startsWith("tmp-") && now - f.lastModified() > 3_600_000L }
                ?.forEach { it.delete() }
        }
    }
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val pinnedKeys = mutableSetOf<String>()
    /** 正在解码的 temp 文件；clear/trim 不能删。 */
    private val activeTemps = mutableSetOf<File>()

    /** 单飞准备：Ready/Decoding 直接复用，Missing 启动解码。 */
    suspend fun prepare(source: SourceDescriptor): PcmSession? {
        if (!source.pcmCacheable) return null
        val key = source.cacheKey
        val finalFile = fileFor(key)

        val (session, shouldLaunch) = mutex.withLock {
            val existing = entries[key]
            when (val st = existing?.state) {
                is State.Ready -> {
                    existing.lastAccessMs = clock()
                    readySession(source, st.artifact) to false
                }
                is State.Decoding -> {
                    existing.waiters++
                    st.session to false
                }
                is State.Failed, null -> {
                    if (existing != null) entries.remove(key)
                    if (finalFile.exists() && finalFile.length() > 0L) {
                        val artifact = PcmArtifact(
                            file = finalFile,
                            sizeBytes = finalFile.length(),
                            createdAtMs = finalFile.lastModified(),
                        )
                        val e = Entry(
                            source = source,
                            state = State.Ready(artifact),
                            lastAccessMs = clock(),
                        )
                        entries[key] = e
                        readySession(source, artifact) to false
                    } else {
                        val s = PcmSession(source)
                        val e = Entry(
                            source = source,
                            state = State.Decoding(s),
                            waiters = 1,
                            lastAccessMs = clock(),
                        )
                        entries[key] = e
                        s to true
                    }
                }
            }
        }

        if (shouldLaunch) {
            scope.launch { decode(source, session) }
        }
        return session
    }

    /**
     * 获取可安全使用的 PCM 租约。
     * - 缓存命中：直接返回
     * - 正在解码：等待同一任务
     * - 失败：返回 null，调用方回退旧解码路径
     */
    suspend fun acquire(source: SourceDescriptor): PcmLease? {
        val session = prepare(source) ?: return null
        return session.acquireLease()
    }

    /** 当前播放曲 pin：LRU/clear 都不会删。 */
    suspend fun pin(source: SourceDescriptor) {
        if (!source.pcmCacheable) return
        mutex.withLock {
            pinnedKeys.add(source.cacheKey)
            entries[source.cacheKey]?.pinned = true
        }
    }

    suspend fun unpin(source: SourceDescriptor) {
        if (!source.pcmCacheable) return
        mutex.withLock {
            pinnedKeys.remove(source.cacheKey)
            entries[source.cacheKey]?.pinned = false
        }
        trim()
    }

    /** 按容量上限做 LRU。使用中/等待中/pin 的 entry 永不被删。 */
    suspend fun trim() {
        mutex.withLock {
            var total = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            if (total <= maxBytes) return
            val candidates = entries.values
                .filter { e ->
                    e.state is State.Ready &&
                        e.leases == 0 &&
                        e.waiters == 0 &&
                        !e.pinned &&
                        e.source.cacheKey !in pinnedKeys
                }
                .sortedBy { it.lastAccessMs }
            for (e in candidates) {
                if (total <= maxBytes) break
                val artifact = (e.state as? State.Ready)?.artifact ?: continue
                val len = artifact.file.length()
                if (artifact.file.delete()) {
                    total -= len
                    entries.remove(e.source.cacheKey)
                }
            }
            // 跨进程遗留的 cache 文件不在 entries 里；按 mtime LRU 补删。
            if (total > maxBytes) {
                val protected = entries.values
                    .filter { e ->
                        e.state is State.Ready &&
                            (e.leases > 0 || e.waiters > 0 || e.pinned ||
                                e.source.cacheKey in pinnedKeys)
                    }
                    .mapNotNull { (it.state as? State.Ready)?.artifact?.file }
                    .toSet() + pinnedKeys.map { fileFor(it) }.toSet()
                dir.listFiles()
                    ?.filter { it.isFile && it !in activeTemps && it !in protected }
                    ?.sortedBy { it.lastModified() }
                    ?.forEach { f ->
                        if (total <= maxBytes) return@forEach
                        val len = f.length()
                        if (f.delete()) total -= len
                    }
            }
        }
    }

    /** 清理未使用 PCM；正在使用/等待/pin 的保留。返回删除文件数。 */
    suspend fun clearUnpinned(): Int = mutex.withLock {
        var removed = 0
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val ready = e.state as? State.Ready ?: continue
            if (e.leases > 0 || e.waiters > 0 || e.pinned ||
                e.source.cacheKey in pinnedKeys
            ) continue
            if (ready.artifact.file.delete()) removed++
            it.remove()
        }
        // 清理上次进程遗留、未被 entries 跟踪的文件（当前播放曲 pin 除外）
        val activeFiles = entries.values
            .filter { e ->
                e.state is State.Ready &&
                    (e.leases > 0 || e.waiters > 0 || e.pinned ||
                        e.source.cacheKey in pinnedKeys)
            }
            .mapNotNull { (it.state as? State.Ready)?.artifact?.file }
            .toSet() + pinnedKeys.map { fileFor(it) }.toSet()
        dir.listFiles()?.filter { it.isFile && it !in activeFiles }?.forEach {
            if (it in activeTemps) return@forEach
            if (it.delete()) removed++
        }
        removed
    }

    /** 设置页显示用：固定上限（当前 12GB，LRU）。 */
    val cacheLimitBytes: Long get() = maxBytes

    suspend fun cacheBytes(): Long = mutex.withLock {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /** 设置页显示用：缓存文件数（含 tmp，便于排查）。 */
    suspend fun cacheFileCount(): Int = mutex.withLock {
        dir.listFiles()?.count { it.isFile } ?: 0
    }

    private suspend fun decode(source: SourceDescriptor, session: PcmSession) {
        val key = source.cacheKey
        val temp = File(dir, "tmp-${hash(key)}-${UUID.randomUUID()}.wav")
        val finalFile = fileFor(key)
        mutex.withLock { activeTemps.add(temp) }
        try {
            val decoded = decoder.decode(source, temp) { written, envelope ->
                session.updateProgress(
                    PcmProgress(
                        PcmProgress.Phase.DECODING,
                        bytesWritten = written,
                        envelope = envelope,
                    ),
                )
            }
            if (decoded == null || !temp.exists() || temp.length() <= 0L) {
                fail(source, temp, "decoder returned incomplete output")
                return
            }
            if (!temp.renameTo(finalFile)) {
                // same-directory rename should succeed; fallback copy is intentionally avoided
                fail(source, temp, "rename failed")
                return
            }
            val artifact = decoded.copy(
                file = finalFile,
                sizeBytes = finalFile.length(),
                createdAtMs = clock(),
            )
            val shouldPublish = mutex.withLock {
                val e = entries[key] ?: return@withLock false
                if (e.state !is State.Decoding) return@withLock false
                e.state = State.Ready(artifact)
                e.lastAccessMs = clock()
                true
            }
            if (shouldPublish) {
                session.complete(artifact)
                trim()
            } else {
                runCatching { finalFile.delete() }
                session.fail("cache entry replaced")
            }
        } catch (t: Throwable) {
            fail(source, temp, t.message ?: t::class.java.simpleName)
        } finally {
            mutex.withLock { activeTemps.remove(temp) }
        }
    }

    private suspend fun fail(source: SourceDescriptor, temp: File, message: String) {
        runCatching { temp.delete() }
        val key = source.cacheKey
        var sessionToFail: PcmSession? = null
        mutex.withLock {
            val e = entries[key] ?: return@withLock
            val s = e.state as? State.Decoding ?: return@withLock
            e.state = State.Failed(message)
            e.waiters = 0
            sessionToFail = s.session
        }
        sessionToFail?.fail(message)
    }

    private suspend fun release(key: String) {
        mutex.withLock {
            val e = entries[key] ?: return@withLock
            if (e.leases > 0) e.leases--
            e.lastAccessMs = clock()
        }
        trim()
    }

    /** 调用方 await 完成：减少 waiters。失败/成功都要减。 */
    private suspend fun doneWaiting(key: String) {
        mutex.withLock {
            val e = entries[key] ?: return@withLock
            if (e.waiters > 0) e.waiters--
            e.lastAccessMs = clock()
        }
    }

    private fun readySession(source: SourceDescriptor, artifact: PcmArtifact): PcmSession =
        PcmSession(source).also { it.complete(artifact) }

    private fun fileFor(key: String): File = File(dir, "pcm-${hash(key)}.wav")

    private fun hash(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val out = md.digest(key.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** 单飞会话：同一 key 的调用方拿到同一个对象。 */
    inner class PcmSession internal constructor(
        val source: SourceDescriptor,
    ) {
        private val _progress = MutableStateFlow(
            PcmProgress(PcmProgress.Phase.DECODING),
        )
        val progress: StateFlow<PcmProgress> = _progress.asStateFlow()

        private val result = CompletableDeferred<PcmArtifact?>()

        val isReady: Boolean
            get() = result.isCompleted && result.getCompleted() != null

        suspend fun await(): PcmArtifact? {
            return try {
                result.await()
            } finally {
                doneWaiting(source.cacheKey)
            }
        }

        /**
         * acquire 专用：先等 artifact，再在仍持有 waiter 保护时原子加 lease，
         * 最后才减少 waiter。避免 decode 发布后 trim 在 acquire 前把刚解好的文件删掉。
         */
        internal suspend fun acquireLease(): PcmLease? {
            val artifact = result.await()
            if (artifact == null) {
                doneWaiting(source.cacheKey)
                return null
            }
            val lease = mutex.withLock {
                val entry = entries[source.cacheKey]
                if (entry == null || entry.state !is State.Ready) {
                    null
                } else {
                    entry.leases++
                    entry.lastAccessMs = clock()
                    PcmLease(artifact) {
                        scope.launch { release(source.cacheKey) }
                    }
                }
            }
            doneWaiting(source.cacheKey)
            return lease
        }

        internal fun complete(artifact: PcmArtifact) {
            _progress.value = PcmProgress(PcmProgress.Phase.READY)
            result.complete(artifact)
        }

        internal fun fail(message: String) {
            _progress.value = PcmProgress(PcmProgress.Phase.FAILED, message = message)
            result.complete(null)
        }

        internal fun updateProgress(p: PcmProgress) {
            _progress.value = p
        }
    }
}
