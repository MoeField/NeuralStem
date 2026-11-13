package pub.miku.neuralstem.spike

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * 阶段 3.1（分离页重构 · C1）：**当前播放曲 Uri → File 桥**。
 *
 * ## 为什么需要
 * 播放器持有的是 `content://`（SAF）或 `file://` 的 [Uri]，
 * 而分离引擎（SegregationRepository.start / start4 / startDemucs）需要 [File]。
 * - WAV：仍需一份本地副本，因为 [WavReader] 要随机访问 File；
 * - 压缩源：只写一个 `.sepmeta` 元数据文件，解码时直接读 content URI，
 *   不再把整首原始字节复制进 cacheDir。
 *
 * ## 复用
 * 若 [Uri] 本身就是 `file://`（外部目录加载/自动化路径），直接复用该文件，不做拷贝。
 * 其余（`content://`）走 [Context.getContentResolver] 拷贝原始字节——
 * 不在此处解码：下游 [SeparationInput.open] 已按扩展名/内容分支处理
 * （wav 直读 / mp3 等走 MediaExtractor+MediaCodec 解码）。
 */
object CurrentTrackBridge {

    private const val TAG = "TrackBridge"

    /** 分离输入落盘名前缀；实际文件名还会带 uri token，避免 A/B 同扩展互相覆盖。 */
    private const val CACHE_NAME = "separate_input"

    /**
     * 把当前播放曲的 [uri] 变成可交给分离引擎的本地 [File]。
     *
     * 压缩 `content://` 返回 `.sepmeta` 元数据文件；下游 SeparationInput 识别后直接解码，
     * 不会生成 `separate_input_<hash>.<ext>` 的原始字节副本。
     *
     * @param uri 当前曲 Uri（`content://` 或 `file://`）
     * @return 本地文件/元数据；失败返回 null（调用方应展示空态/错误）
     */
    suspend fun toFile(context: Context, uri: String?): File? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null

        // file:// → 直接复用（无拷贝、零延迟）
        if (parsed.scheme == "file") {
            val f = File(parsed.path ?: return null)
            return if (f.exists()) f else null
        }

        return withContext(Dispatchers.IO) {
            try {
                cleanupStaleTemps(context)
                val ext = resolveExtension(context, parsed)
                // ★P0：一次分离的源必须唯一，不能所有 content:// 共用 separate_input.flac。
                //   uri token 保证 A/B（同扩展）不会写同一个文件；同 uri 重跑复用。
                if (ext.equals("wav", ignoreCase = true)) {
                    // WAV 需要随机访问（WavReader 吃 File），仍需一份本地副本。
                    val local = File(context.cacheDir, tempSourceName(parsed.toString(), ext))
                    context.contentResolver.openInputStream(parsed)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法打开 $parsed")
                    Log.i(TAG, "bridged wav $parsed -> ${local.absolutePath} (${local.length()} B)")
                    return@withContext local
                }

                // ★P2：压缩源不再整份复制。只写元数据（原始 URI/显示名/扩展/内容 hash），
                //   SeparationInput 用 MediaExtractor 直接读 content URI 解码。
                val hash = sha256(context, parsed)
                val meta = File(
                    context.cacheDir,
                    tempSourceName(parsed.toString(), "sepmeta"),
                )
                val props = Properties().apply {
                    setProperty(KEY_URI, parsed.toString())
                    setProperty(KEY_DISPLAY_NAME, displayName(context, parsed))
                    setProperty(KEY_EXT, ext)
                    setProperty(KEY_HASH, hash)
                }
                meta.outputStream().use { props.store(it, "NeuralStem bridged source") }
                Log.i(TAG, "bridged uri $parsed -> ${meta.absolutePath} (hash=$hash, ext=$ext)")
                meta
            } catch (t: Throwable) {
                Log.e(TAG, "toFile failed for $uri", t)
                null
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"

    private fun sha256(context: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        } ?: error("无法打开 $uri")
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private const val KEY_URI = "uri"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_EXT = "ext"
    private const val KEY_HASH = "hash"

    /** 压缩 content URI 的桥接元数据。 */
    data class BridgedSource(
        val uri: String,
        val displayName: String,
        val extension: String,
        val contentHash: String,
    )

    /** 读取元数据文件；不是桥接文件/损坏时返回 null。 */
    fun readBridgedSource(file: File): BridgedSource? {
        if (file.extension != "sepmeta") return null
        return runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val uri = props.getProperty(KEY_URI) ?: return null
            val hash = props.getProperty(KEY_HASH) ?: return null
            BridgedSource(
                uri = uri,
                displayName = props.getProperty(KEY_DISPLAY_NAME) ?: file.name,
                extension = props.getProperty(KEY_EXT) ?: "audio",
                contentHash = hash,
            )
        }.getOrNull()
    }

    /** 桥接文件对应的原始内容 hash；普通文件返回 null，由调用方按文件内容算。 */
    fun sourceHash(file: File): String? = readBridgedSource(file)?.contentHash

    /** 纯函数，便于 JVM 测试：同一 uri 稳定、不同 uri 不冲突。 */
    internal fun tempSourceName(uriString: String, ext: String): String {
        val token = uriString.hashCode().toUInt().toString(16)
        return "${CACHE_NAME}_$token.$ext"
    }

    /** 清理超过 1 小时的旧分离输入；正常任务结束后不保证调用，避免长期堆 cache。 */
    private fun cleanupStaleTemps(context: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            context.cacheDir.listFiles { f ->
                f.name.startsWith(CACHE_NAME) && now - f.lastModified() > 3_600_000L
            }?.forEach { it.delete() }
        }
    }

    /**
     * 推断扩展名（保留原扩展名，下游据此选解路径）。
     * 依次尝试：displayName 扩展名 → MIME 子类型 → "audio"。
     */
    private fun resolveExtension(context: Context, uri: Uri): String {
        val fromName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
        if (fromName != null) return fromName

        return context.contentResolver.getType(uri)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "audio"
    }
}
