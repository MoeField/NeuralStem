package pub.miku.neuralstem.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * ★把 SAF 的 `content://` uri 还原成**真实文件路径**。
 *
 * ## 为什么需要
 * 文件夹导入（SAF `OpenDocumentTree`）拿到的是
 * `content://com.android.externalstorage.documents/tree/...`。
 * 但两类播放源**必须用真实路径**：
 * - **AVS3**：解码要 `File.readBytes()`，`content://` 不行
 * - **NCM**：解密要路径（还可能伴随 Shizuku 读取）
 *
 * 普通 mp3/flac 可以继续用 `content://`（ExoPlayer 原生支持），
 * 所以只在需要时才解析。
 *
 * ## 为什么不反射 `_data` 列
 * `MediaStore.MediaColumns.DATA` 在 Android 10+ 已废弃，
 * 对 `content://` 查询常返回 null（分区存储）。
 * 这里走 **`DocumentsContract.getDocumentId()`** —— SAF 的正规接口。
 *
 * ## 已知局限
 * 只处理 `externalstorage` 类型的 document id
 * （形如 `primary:Music/a.mp3` 或 `1A2B-3C4D:Music/a.mp3`）。
 * 其他 provider（如网盘）返回 null，调用方需降级。
 */
object SafPathResolver {

    private const val TAG = "SafPath"

    /**
     * 尝试把 content uri 解析为真实路径。
     *
     * @return 绝对路径（如 `/storage/emulated/0/Music/a.mp3`）；解析不了返回 null
     */
    fun resolve(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null

        return runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            when {
                // 形如 "primary:Music/a.mp3"
                docId.startsWith("primary:") ->
                    "/storage/emulated/0/" + docId.removePrefix("primary:")

                // 形如 "1A2B-3C4D:Music/a.mp3"（外置 SD 卡）
                docId.contains(':') ->
                    "/storage/${docId.substringBefore(':')}/${docId.substringAfter(':')}"

                else -> null
            }
        }.onFailure {
            Log.w(TAG, "resolve 失败 $uri: ${it.message}")
        }.getOrNull()
    }

    /**
     * 该文件是否**需要真实路径才能播**（AVS3 / NCM）。
     *
     * 对这两类，导入时就要尽量解析成可播 uri：
     * - `.m4a` → 可能是 AVS3（占 99%），保守地都算上
     * - `.ncm` → 必然需要
     *
     * 其余（mp3/flac/wav/ogg/aac）用 `content://` 直接播即可。
     */
    fun needsRealPath(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".m4a") || n.endsWith(".ncm")
    }
}
