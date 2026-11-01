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
}
