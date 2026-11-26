package pub.miku.neuralstem.playlist

import pub.miku.neuralstem.BuildConfig

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
     * 通用音频扩展名。
     *
     * `.m4a` 也收：用户的 .m4a 里约 99% 是 AVS3 编码（能播需自编译解码器），
     * 剩下是真 AAC（原生可播）。两种都应该进歌单。
     */
    private val EXTS = listOf(
        ".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg",
        ".opus", ".wma", ".ape",
    )

    private const val NCM_EXT = ".ncm"

    /**
     * 是不是**可播放**的音频文件（按扩展名）。
     *
     * ## `.ncm` 为什么按构建开关分流
     * NCM 是网易云加密容器，解密代码只在 `-Pwith_ncm=true` 构建里。
     * 公开版如果把它列进歌单，用户点开只会得到「无法播放」——既误导，
     * 也与公开版「不含 NCM 支持」的合规口径不一致。
     *
     * @param withNcm 是否 NCM 构建；默认取 `BuildConfig.WITH_NCM`，测试可显式传值覆盖两个分支
     */
    fun isAudio(name: String, withNcm: Boolean = BuildConfig.WITH_NCM): Boolean {
        val n = name.lowercase()
        if (n.endsWith(NCM_EXT)) return withNcm
        return EXTS.any { n.endsWith(it) }
    }
}
