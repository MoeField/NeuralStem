package pub.miku.neuralstem.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★导出文件名映射的测试（2026-11-01 加 2 轨导出时补）。
 *
 * ## 这个测试第一次跑就抓到一个真 bug
 * 旧实现是 `STEM_NAMES[f.name.lowercase()]`，而表里的**键是不带扩展名的 stem 名**
 * （`"vocals"`），传进来的却是 `"vocals.wav"` —— **永远匹配不上**，
 * 整张映射表是死代码，所有文件都走 `englishName` 兜底。
 *
 * 它一直“看起来对”，因为兜底凑巧把 `vocals.wav` 原样返回。
 * 但兜底保留原始大小写（`VOCALS.WAV` → `VOCALS.WAV`），与预期的小写规范名不一致。
 *
 * ## 为何直接调真实实现而不是复刻逻辑
 * 复刻逻辑的测试只能验证「我抄对了没有」，**拦不住实现自己漂移**。
 * 所以这里调 [StemExporter.outNameFor]（internal，同模块测试可见）。
 * 它不碰 Context / MediaStore，纯字符串推导，JVM 单测能跑。
 */
class StemExportNamingTest {

    @Test
    fun `2 轨产物名规范为小写`() {
        assertEquals("vocals.wav", StemExporter.outNameFor("vocals.wav"))
        assertEquals("accompaniment.wav", StemExporter.outNameFor("accompaniment.wav"))
    }

    @Test
    fun `4 轨产物名规范为小写`() {
        assertEquals("drums.wav", StemExporter.outNameFor("drums.wav"))
        assertEquals("bass.wav", StemExporter.outNameFor("bass.wav"))
        assertEquals("other.wav", StemExporter.outNameFor("other.wav"))
    }

    /** ★这是旧实现会失败的那条：大写输入必须被规范化，而不是原样保留。 */
    @Test
    fun `大写输入被规范化为小写`() {
        assertEquals("vocals.wav", StemExporter.outNameFor("VOCALS.WAV"))
        assertEquals("accompaniment.wav", StemExporter.outNameFor("Accompaniment.wav"))
    }

    @Test
    fun `未知 stem 走兜底且保留扩展名`() {
        val out = StemExporter.outNameFor("unknown_stem.wav")
        assertTrue("应以 .wav 结尾，实际 $out", out.endsWith(".wav"))
    }

    @Test
    fun `中文名走兜底后不含非 ASCII`() {
        val out = StemExporter.outNameFor("人声.wav")
        assertTrue("输出应全为 ASCII，实际 $out", out.all { it.code < 128 })
        assertTrue("应以 .wav 结尾，实际 $out", out.endsWith(".wav"))
    }

    @Test
    fun `无扩展名的输入也能得到 wav 扩展名`() {
        assertEquals("stem.wav", StemExporter.outNameFor(""))
    }

    // ==================== 目录名（2 轨/4 轨共用）====================

    /**
     * ★ 2026-11-01 真机发现的丑目录名。
     *
     * 旧实现把所有非 ASCII 变下划线，实测：
     * `colate ななひら - Good Night.mp3` → `colate______-_Good_Night_mp3`
     * （日文全变下划线，且 `.mp3` 也进了目录名）
     */
    @Test
    fun `中文日文目录名保留原字符`() {
        val n = StemExporter.safeDirName("colate ななひら - Good Night.mp3")
        assertEquals("colate ななひら - Good Night", n)
    }

    @Test
    fun `目录名不带扩展名`() {
        assertEquals("katyusha", StemExporter.safeDirName("katyusha.mp3"))
        assertEquals("Timber", StemExporter.safeDirName("Timber.m4a"))
    }

    @Test
    fun `非法字符被替换`() {
        // / \ : * ? " < > | 与控制字符
        val n = StemExporter.safeDirName("a/b\\c:d*e?f\"g<h>i|j")
        assertEquals(false, n.any { it in "/\\:*?\"<>|" })
        assertTrue("应保留主体字符，实际 $n", n.startsWith("a"))
    }

    @Test
    fun `全非法或空名回退为 song`() {
        assertEquals("song", StemExporter.safeDirName(""))
        assertEquals("song", StemExporter.safeDirName("   "))
    }

    @Test
    fun `过长名称被截断`() {
        val n = StemExporter.safeDirName("あ".repeat(200) + ".wav")
        assertTrue("应 <=80 字符，实际 ${n.length}", n.length <= 80)
    }
}
