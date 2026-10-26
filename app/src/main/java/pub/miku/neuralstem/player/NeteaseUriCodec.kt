package pub.miku.neuralstem.player

/**
 * ★`netease://` 逻辑引用的编解码（**纯 JVM，无 Android 依赖**）。
 *
 * ## 为什么单独抽出来
 * 歌单里存的是这种 uri，一共三处要用（歌单写入 / 播放解析 / devtools 诊断）。
 * 而 `android.net.Uri` 在 JVM 单测里是 **stub**（调什么都返回 null），
 * 所以把编解码逻辑放在 `Uri` 之外，才能**真正测到**它。
 *
 * ## 格式
 * ```
 * netease://<URL编码后的绝对路径>
 * ```
 * 例：`/storage/XX/.../a b.m4a` → `netease://%2Fstorage%2FXX%2F...%2Fa%20b.m4a`
 *
 * ## ★两个实测踩到的坑
 *
 * **坑 1：`%2F` 会被当成 host**
 * `Uri.parse("netease://%2Fstorage%2Fx")` 的 `schemeSpecificPart` 是 **null**，
 * 整串跑到了 `host` 里。所以解析必须**优先取 host**。
 *
 * **坑 2：`URLEncoder` 把空格编成 `+`**
 * 而 `URLDecoder` 又会把 `+` 解回空格 —— 文件名里本来就有 `+` 的会被吃掉。
 * 所以编码后要把 `+` 换回 `%20`。
 *
 * 这两个坑都有对应的单测（`NeteaseUriCodecTest`）。
 */
object NeteaseUriCodec {

    /** scheme 名。 */
    const val SCHEME = "netease"

    /**
     * 绝对路径 → `netease://...`
     *
     * 注意：**不用** `android.net.Uri`，纯字符串拼接 + 手工编码，
     * 这样 JVM 单测可覆盖、行为确定。
     */
    fun encode(absolutePath: String): String {
        val enc = java.net.URLEncoder.encode(absolutePath, "UTF-8")
            // ★坑 2：URLEncoder 把空格变成 '+'，而 '+' 在 URL 里是字面量，
            //   解码时会被 URLDecoder 当空格 → 本来含 '+' 的文件名就错了。
            //   统一换成 %20（标准空格编码），避免歧义。
            .replace("+", "%20")
        return "$SCHEME://$enc"
    }

    /**
     * `netease://...` → 绝对路径；不是本 scheme 返回 null。
     *
     * 纯字符串解析（不用 `Uri`），但要处理「`%2F` 在 host 位置」这个现实。
     */
    fun decode(uriString: String): String? {
        val prefix = "$SCHEME://"
        if (!uriString.startsWith(prefix)) return null
        val raw = uriString.removePrefix(prefix)
        if (raw.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    /** 是不是 `netease://` uri。 */
    fun isNetease(uriString: String): Boolean = uriString.startsWith("$SCHEME://")
}
