package pub.miku.neuralstem.spike

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阶段 3.1（分离页重构 · C1）：**当前播放曲 Uri → File 桥**。
 *
 * ## 为什么需要
 * 播放器持有的是 `content://`（SAF）或 `file://` 的 [Uri]，
 * 而分离引擎（SegregationRepository.start / start4 / startDemucs）需要可随机访问的 [File]。
 * SAF 流不可 seek、且不保证长期可读 → 先整份拷到应用私有 `cacheDir` 再交给分离引擎。
 *
 * ## 复用
 * 若 [Uri] 本身就是 `file://`（外部目录加载/自动化路径），直接复用该文件，不做拷贝。
 * 其余（`content://`）走 [Context.getContentResolver] 拷贝原始字节——
 * 不在此处解码：下游 [SeparationInput.open] 已按扩展名/内容分支处理
 * （wav 直读 / mp3 等走 MediaExtractor+MediaCodec 解码）。
 */
object CurrentTrackBridge {

    private const val TAG = "TrackBridge"

    /** 分离输入统一落盘名（按扩展名区分，供下游选直读 vs 解码路径）。 */
    private const val CACHE_NAME = "separate_input"

    /**
     * 把当前播放曲的 [uri] 变成可交给分离引擎的本地 [File]。
     *
     * @param uri 当前曲 Uri（`content://` 或 `file://`）
     * @return 本地可随机访问文件；失败返回 null（调用方应展示空态/错误）
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
                val ext = resolveExtension(context, parsed)
                val local = File(context.cacheDir, "$CACHE_NAME.$ext")
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法打开 $parsed")
                Log.i(TAG, "bridged $parsed -> ${local.absolutePath} (${local.length()} B, ext=$ext)")
                local
            } catch (t: Throwable) {
                Log.e(TAG, "toFile failed for $uri", t)
                null
            }
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
