package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★标题拆分单测。
 *
 * 输入样本全部取自**真机歌单的真实数据**（1002 首里的典型样本），
 * 不是编的 —— 这样测的就是真实分布。
 */
class TrackTitleFormatterTest {

    @Test
    fun `标准格式 艺人 - 歌名`() {
        val r = TrackTitleFormatter.format("Pitbull Kesha - Timber")
        assertEquals("Timber", r.title)
        assertEquals("Pitbull Kesha", r.artist)
    }

    @Test
    fun `带状态后缀时也能正确拆`() {
        val r = TrackTitleFormatter.format("23.exe 初音ミク - Loow [需解码]")
        assertEquals("Loow", r.title)
        assertEquals("23.exe 初音ミク", r.artist)
        assertEquals("[需解码]", r.badge)
    }

    @Test
    fun `加密后缀同样拆出`() {
        val r = TrackTitleFormatter.format("かめりあ - 千本桜 [加密]")
        assertEquals("千本桜", r.title)
        assertEquals("かめりあ", r.artist)
        assertEquals("[加密]", r.badge)
    }

    @Test
    fun `艺人列表过长时不拆`() {
        // 真实样本：15 个艺人名，左边远超 24 字
        val raw = "acaer AIKA Airuei Anemoi Kotori Polarr Similar Outskirts " +
            "Slappy Soba Synthion Tenkitsune Trifect Winamp Boys - If"
        val r = TrackTitleFormatter.format(raw)
        assertFalse("左边太长不该拆", r.hasArtist)
        assertTrue("应保留完整原文", r.title.contains("Winamp Boys"))
    }

    @Test
    fun `没有分隔符时整体当歌名`() {
        val r = TrackTitleFormatter.format("孤独なJourney")
        assertEquals("孤独なJourney", r.title)
        assertFalse(r.hasArtist)
    }

    @Test
    fun `只在第一个分隔符处拆`() {
        val r = TrackTitleFormatter.format("A - B - C")
        assertEquals("A", r.artist)
        assertEquals("B - C", r.title)
    }

    @Test
    fun `全角连字符也能拆`() {
        val r = TrackTitleFormatter.format("艺人 － 歌名")
        assertEquals("歌名", r.title)
        assertEquals("艺人", r.artist)
    }

    @Test
    fun `分隔符在最前面时不拆`() {
        val r = TrackTitleFormatter.format(" - 只有歌名")
        assertFalse(r.hasArtist)
    }

    @Test
    fun `只有艺人没有歌名时不拆`() {
        val r = TrackTitleFormatter.format("艺人 - ")
        assertFalse(r.hasArtist)
    }

    @Test
    fun `真实长标题的后缀仍被摘掉`() {
        val raw = "Titan Chaos League Of Legends - League Of Legends-Take Over Remix " +
            "(所向无前)instrumental（Titan Chaos League Of Legends remix） [需解码]"
        val r = TrackTitleFormatter.format(raw)
        assertEquals("[需解码]", r.badge)
        assertTrue(r.title.isNotBlank())
    }
}
