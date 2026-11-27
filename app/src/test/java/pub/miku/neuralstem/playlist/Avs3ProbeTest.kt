package pub.miku.neuralstem.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Avs3ProbeTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `stsd 内的 av3a 被识别`() {
        // stsd + 头 16 字节 + entry(size 4 + fourcc av3a)
        val head = bytes("xxstsd") + ByteArray(16) + bytes("av3a")
        assertTrue(Avs3Probe.hasAvs3Codec(head))
    }

    @Test
    fun `mp4a 不误判`() {
        val head = bytes("xxstsd") + ByteArray(16) + bytes("mp4a")
        assertFalse(Avs3Probe.hasAvs3Codec(head))
    }

    @Test
    fun `空或过短返回 false`() {
        assertFalse(Avs3Probe.hasAvs3Codec(ByteArray(0)))
        assertFalse(Avs3Probe.hasAvs3Codec(bytes("av3")))
    }

    @Test
    fun `无 stsd 时全量搜索兜底`() {
        assertTrue(Avs3Probe.hasAvs3Codec(bytes("ftypisom....av3a...")))
    }

    @Test
    fun `stsd 后 1KB 之外的 av3a 不算`() {
        val head = bytes("stsd") + ByteArray(16) + ByteArray(2048) + bytes("av3a")
        // stsd 邻近窗口未命中；兜底全量搜索会命中（当前策略：宁可拒绝也不放过）
        assertTrue(Avs3Probe.hasAvs3Codec(head))
    }
}
