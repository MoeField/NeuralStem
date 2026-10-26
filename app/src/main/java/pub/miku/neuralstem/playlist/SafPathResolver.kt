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
     * [resolve] 的逆运算：把绝对路径还原成 SAF 的 `content://` document uri。
     *
     * ## 用途（只有一个）
     * **迁移旧歌单数据**。2026-10-26 之前的版本会把 SAF 导入的文件存成
     * `file:///storage/emulated/0/...`，而本包 `targetSdk=37` 下这类 uri
     * **必然读不了**（`EACCES`）。迁移时用本函数把它们还原成 `content://`。
     * 新的导入路径**不再调用**本函数（直接存 `content://`）。
     *
     * ## 支持的前缀
     * - `/storage/emulated/0/X` → `primary:X`（内置存储）
     * - `/storage/1A2B-3C4D/X` → `1A2B-3C4D:X`（外置 SD 卡）
     *
     * @return 还原后的 uri；不在上述前缀下返回 null
     */
    fun toContentUri(path: String): Uri? {
        val docId = when {
            path.startsWith("/storage/emulated/0/") ->
                "primary:" + path.removePrefix("/storage/emulated/0/")

            path.startsWith("/storage/") -> {
                // /storage/<volume>/<rest> → <volume>:<rest>
                val rest = path.removePrefix("/storage/")
                val slash = rest.indexOf('/')
                if (slash <= 0) return null
                val volume = rest.substring(0, slash)
                // emulated 已被上面处理；剩下的 "1A2B-3C4D" 类型才能这么转
                if (volume == "emulated" || volume == "self") return null
                "$volume:" + rest.substring(slash + 1)
            }

            else -> return null
        }

        // ★必须整体编码成**单个 path segment**：
        //   document uri 的形式是 document/<URL编码的docId>，docId 里的 `/` 要编码成 %2F。
        return runCatching {
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY_EXTERNAL_STORAGE)
                .appendPath("document")
                .appendPath(docId)
                .build()
        }.getOrNull()
    }

    /**
     * 该文件是否**需要真实路径才能播**（AVS3 / NCM）。
     *
     * @deprecated 2026-10-26：**这个判断本身就是 bug 的根源**。
     * 本包 `targetSdk=37`（Android 14+ 强制分区存储）下，SAF 的 `tree/...` 授权
     * 只对 `content://` 通道有效，转成 `file://` 绝对路径后**必然 `EACCES`**。
     * 导入路径应**一律保留 `content://`**，读取走 [MediaSourceReader]。
     * 保留本函数仅为兼容尚未清理的调用点。
     */
    @Deprecated("SAF 导入应一律保留 content://；转绝对路径在 Android 14+ 必然 EACCES")
    fun needsRealPath(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".m4a") || n.endsWith(".ncm")
    }

    private const val AUTHORITY_EXTERNAL_STORAGE =
        "com.android.externalstorage.documents"
}
