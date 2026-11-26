package pub.miku.neuralstem.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★音频扩展名白名单单测。
 *
 * ## 为什么这条链值得单独测
 * 实测发现的线上问题：白名单里**漏了 `.ncm`** —— 用户 732 个网易云文件
 * 一个都进不了歌单，而且**没有任何报错**（静默跳过，最难发现的那种 bug）。
 *
 * 这类"配置型"逻辑没有单测就只能靠人肉记得，迟早再漏。
 */
class AudioFileTypesTest {

    @Test
    fun `ncm 仅在 NCM 构建收录`() {
        // NCM 版：需要扫描网易云文件
        assertTrue(AudioFileTypes.isAudio("a.ncm", withNcm = true))
        assertTrue(AudioFileTypes.isAudio("Carry GLIKER - Revive.ncm", withNcm = true))
        // 公开版：没有解密代码，列出来只会「无法播放」→ 不应算音频
        assertFalse(AudioFileTypes.isAudio("a.ncm", withNcm = false))
        assertFalse(AudioFileTypes.isAudio("Carry GLIKER - Revive.ncm", withNcm = false))
    }

    @Test
    fun `m4a 必须收录`() {
        assertTrue(AudioFileTypes.isAudio("Timber.m4a"))
        assertTrue(AudioFileTypes.isAudio("23.exe 初音ミク - Loow.m4a"))
    }

    @Test
    fun `常见音频格式全部收录`() {
        listOf("a.mp3", "a.wav", "a.flac", "a.aac", "a.ogg", "a.opus")
            .forEach { assertTrue("应收录 $it", AudioFileTypes.isAudio(it)) }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(AudioFileTypes.isAudio("A.NCM", withNcm = true))
        assertFalse(AudioFileTypes.isAudio("A.NCM", withNcm = false))
        assertTrue(AudioFileTypes.isAudio("B.M4A"))
        assertTrue(AudioFileTypes.isAudio("C.Flac"))
    }

    @Test
    fun `非音频文件跳过`() {
        listOf("a.txt", "a.jpg", "a.pdf", "cover.png", "readme.md", "a")
            .forEach { assertFalse("应跳过 $it", AudioFileTypes.isAudio(it)) }
    }

    @Test
    fun `扩展名不能只是包含`() {
        assertFalse(AudioFileTypes.isAudio("a.ncmx"))
        assertFalse(AudioFileTypes.isAudio("ncm"))
        assertFalse(AudioFileTypes.isAudio("a.mp3.bak"))
    }

    @Test
    fun `带空格与特殊字符的真实文件名`() {
        assertTrue(AudioFileTypes.isAudio("6＆9 - Cornfield Chase（Piano Ver).m4a"))
        assertTrue(AudioFileTypes.isAudio("a+b c.ncm", withNcm = true))
        assertFalse(AudioFileTypes.isAudio("a+b c.ncm", withNcm = false))
    }
}
