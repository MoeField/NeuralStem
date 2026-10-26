package pub.miku.neuralstem.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ★「按 uri 读字节」的**单一真源**。
 *
 * ## 为什么需要（2026-10-26 真机实证）
 *
 * 之前所有读取入口都是 `File(path).readBytes()` —— 那时 SAF 导入会把 `content://`
 * **转成绝对路径** `file:///storage/emulated/0/...` 存进歌单。
 *
 * 但本包 `targetSdk = 37`（Android 14+ 强制分区存储）：
 * **`tree/...` 的 SAF 授权只对 `content://` 通道有效，对 `file://` 走内核 `open()` 完全无效。**
 * 真机实测：
 * ```
 * PlayerVm E: 播放失败: ERROR_CODE_IO_NO_PERMISSION
 *   java.io.FileNotFoundException: .../Carry GLIKER - Revive（Original Mix）.ncm:
 *   open failed: EACCES (Permission denied)
 * ```
 * 且 `READ_MEDIA_AUDIO` 已 granted、SAF 授权也已持久化 —— 都不是原因。
 * `.ncm` 不是媒体文件、AVS3 `.m4a` 内容也不是系统认识的格式，两者都不在 MediaStore 白名单里。
 *
 * ⇒ 结论：**必须保留 `content://` 走 `ContentResolver`，不能转绝对路径。**
 *
 * ## 三条通道
 * | scheme | 通道 | 说明 |
 * |---|---|---|
 * | `content://` | `ContentResolver.openInputStream()` | SAF 导入的文件（本类的主要目的） |
 * | `file://` | `File` 直读 | 缓存 WAV、临时文件、旧数据 |
 * | `netease://` | 由调用方处理 | 网易云私有目录需 Shizuku，本类不碰 |
 *
 * ## 线程
 * 全部是**阻塞 IO**，调用方必须在 `Dispatchers.IO` 里跑。
 *
 * ## 与 `SafPathResolver` 的分工
 * 本类**不做路径转换**，只按 uri 读取。路径 <-> uri 的互转仍归 [SafPathResolver]
 * （它的 `resolve` 已降级为**仅用于旧数据迁移**）。
 */
object MediaSourceReader {

    private const val TAG = "MediaSrc"

    /** 读头部时默认上限（MP4 的 `moov` 通常在前部）。 */
    const val DEFAULT_HEAD_BYTES = 256 * 1024

    /**
     * 读**完整**字节。
     *
     * @return null = 读不到（无权限 / 文件不存在 / 不是我们能处理的 scheme）
     */
    fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        when (uri.scheme?.lowercase()) {
            "content", "android.resource" ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

            "file" -> uri.path?.let { p ->
                val f = File(p)
                if (f.exists() && f.canRead()) f.readBytes() else null
            }

            null -> null

            else -> {
                // 未知 scheme（含 netease://）：不归本类管，由调用方降级
                Log.d(TAG, "readBytes: 不处理的 scheme=${uri.scheme}")
                null
            }
        }
    }.onFailure {
        Log.w(TAG, "readBytes 失败 uri=$uri", it)
    }.getOrNull()

    /**
     * 读**头部** [maxBytes] 字节。文件比 [maxBytes] 短时返回整个文件。
     *
     * 与 [readBytes] 分开是为了：判断 codec 只需前几百 KB，
     * 而源文件动辄 20–40MB —— 整读会让每次切歌都卡一下。
     *
     * @return null = 读不到；空文件返回空数组
     */
    fun readHead(context: Context, uri: Uri, maxBytes: Int = DEFAULT_HEAD_BYTES): ByteArray? =
        runCatching {
            when (uri.scheme?.lowercase()) {
                "content", "android.resource" ->
                    context.contentResolver.openInputStream(uri)?.use { readUpTo(it, maxBytes) }

                "file" -> uri.path?.let { p ->
                    val f = File(p)
                    if (!f.exists() || !f.canRead()) return@let null
                    f.inputStream().use { readUpTo(it, maxBytes) }
                }

                null -> null
                else -> null
            }
        }.onFailure {
            Log.w(TAG, "readHead 失败 uri=$uri", it)
        }.getOrNull()

    /**
     * 取「大小 + 修改时间」，用于生成缓存键。
     *
     * @return `[size, mtimeMs]`；拿不到返回 null（调用方跳过缓存）
     *
     * ## mtime 的口径
     * `file://` 用 `File.lastModified()`（毫秒）。
     * `content://` 优先取 `DocumentsContract` 的 `COLUMN_LAST_MODIFIED`（毫秒，
     * 与 `lastModified()` 同口径）；取不到时退回 `0` —— 此时缓存键实际由 `uri + size` 决定，
     * **仍然是稳定的**（同一个 SAF 文件 size 不变就命中）。
     */
    fun stat(context: Context, uri: Uri): LongArray? = runCatching {
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { p ->
                val f = File(p)
                // ★注意：这里不能用 exists() 做门槛 —— 旧数据里的 file:// 可能不可读，
                //   但仍要能拿到 size 去建缓存键（读失败会在 readBytes 那步报错）。
                if (f.exists()) longArrayOf(f.length(), f.lastModified()) else null
            }

            "content" -> contentStat(context, uri)
            else -> null
        }
    }.onFailure {
        Log.w(TAG, "stat 失败 uri=$uri", it)
    }.getOrNull()

    /** `content://` 的 size + mtime，走 `OpenableColumns` / `DocumentsContract`。 */
    private fun contentStat(context: Context, uri: Uri): LongArray? {
        // 1) OpenableColumns：几乎所有 provider 都支持 SIZE
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !(c.isNull(idx))) {
                        return longArrayOf(c.getLong(idx), documentsLastModified(context, uri))
                    }
                }
            }
        }

        // 2) 退路：直接查 DocumentsContract 的两列。
        //    ★不用 getDocumentMetadata() —— 它要求 API 29，而本项目 minSdk=23。
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeIdx < 0 || c.isNull(sizeIdx)) return@use null
                val mtimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mtime = if (mtimeIdx >= 0 && !c.isNull(mtimeIdx)) c.getLong(mtimeIdx) else 0L
                longArrayOf(c.getLong(sizeIdx), mtime)
            }
        }.getOrNull()
    }

    /** 取 `DocumentsContract` 的修改时间（毫秒）；失败返回 0。 */
    private fun documentsLastModified(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
            } else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 这个 uri 是否是我们能读的（供调用方提前判断，避免无谓的重试）。 */
    fun canRead(context: Context, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content", "android.resource" -> runCatching {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)

        "file" -> uri.path?.let { File(it).let { f -> f.exists() && f.canRead() } } ?: false
        else -> false
    }

    /** 从流里最多读 [maxBytes] 字节。 */
    private fun readUpTo(
        ins: java.io.InputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        while (out.size() < maxBytes) {
            val r = ins.read(buf)
            if (r <= 0) break
            out.write(buf, 0, minOf(r, maxBytes - out.size()))
        }
        return out.toByteArray()
    }
}
