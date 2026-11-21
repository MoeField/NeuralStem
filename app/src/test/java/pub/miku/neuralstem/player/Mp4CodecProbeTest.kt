package pub.miku.neuralstem.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AV3A 检测的容器层测试：不依赖 Android，验证 stsd sample entry 解析。 */
class Mp4CodecProbeTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array())
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun hdlr(handler: String): ByteArray {
        val p = ByteArray(12)
        handler.toByteArray(Charsets.US_ASCII).copyInto(p, 8)
        return box("hdlr", p)
    }

    private fun stsd(format: String): ByteArray {
        val entry = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(8)
            .put(format.toByteArray(Charsets.US_ASCII))
            .array()
        val payload = ByteArray(8) // version/flags + entryCount=1
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(4, 1)
        return box("stsd", payload + entry)
    }

    private fun mp4(format: String): ByteArray {
        val stbl = box("stbl", stsd(format))
        val minf = box("minf", stbl)
        val mdia = box("mdia", hdlr("soun") + minf)
        val trak = box("trak", mdia)
        return box("moov", trak)
    }

    @Test
    fun `识别 av3a 与 mp4a`() {
        assertEquals("av3a", Mp4CodecProbe.probeCodec(mp4("av3a")))
        assertEquals("mp4a", Mp4CodecProbe.probeCodec(mp4("mp4a")))
    }

    @Test
    fun `垃圾数据返回 null`() {
        assertNull(Mp4CodecProbe.probeCodec(ByteArray(0)))
        assertNull(Mp4CodecProbe.probeCodec(ByteArray(64)))
    }
}
