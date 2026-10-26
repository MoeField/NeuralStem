package pub.miku.neuralstem.playlist

/**
 * ★「什么算音频文件」的**单一真源**。
 *
 * ## 为什么抽成顶层对象
 * 这个判断在两个地方要用：
 * 1. 文件夹导入（`PlaylistViewModel.scanAudio`）
 * 2. 单曲导入（`PlaylistViewModel.importFiles`）
 *
 * 以前它是 `PlaylistViewModel` 里的 `private fun` —— 结果是：
 * - **没法单测**（只能反射，而反射私有实例方法很容易翻车）
 * - 加扩展名时容易只改一处、漏另一处
 *
 * 抽出来后两份调用点共用，且有 JVM 单测覆盖。
 */
object AudioFileTypes {

    /**
     * 收录的扩展名。
     *
     * ## ★为什么必须有 `.ncm`（2026-10-26）
     * 之前白名单里**没有 .ncm** —— 导致文件夹导入完全扫不到网易云的歌。
     * 实测用户有 732 个 .ncm 文件，一个都进不了歌单。
     *
     * ## 为什么 `.m4a` 也要收
     * 用户的 .m4a 里 99%（219/220）是 AVS3 编码，能播（需自编译解码器）。
     * 剩下 1% 是真 AAC，原生就能播。两种都应该进歌单。
     */
    private val EXTS = listOf(
        ".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg",
        ".ncm",   // ★网易云容器
        ".opus", ".wma", ".ape",  // 少见但存在
    )

    /** 是不是音频文件（按扩展名）。 */
    fun isAudio(name: String): Boolean {
        val n = name.lowercase()
        return EXTS.any { n.endsWith(it) }
    }
}
