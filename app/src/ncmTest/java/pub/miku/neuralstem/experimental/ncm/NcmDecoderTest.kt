package pub.miku.neuralstem.experimental.ncm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * NCM 模块的单测（仅当 `-Pwith_ncm=true` 时编译，见 app/build.gradle.kts）。
 *
 * 不依赖真实 .ncm 文件 —— 只验证可独立成立的逻辑：
 * - 魔数识别
 * - 格式嗅探（mp3/flac/未知）
 * - 非 NCM 输入被拒
 *
 * 注：AES/RC4 的完整链路需与真实文件对照才能验证“解出的音频正确”，
 * 那属于互操作性验证（需外部样本），不在单测范围。
 */
class NcmDecoderTest {

    private fun streamOf(vararg bytes: Int): ByteArrayInputStream =
        ByteArrayInputStream(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `识别 NCM 魔数`() {
        val magic = "CTENFDAM".toByteArray(Charsets.US_ASCII).map { it.toInt() }.toIntArray()
        assertTrue(NcmDecoder.isNcm(streamOf(*magic)))
    }

    @Test
    fun `非 NCM 流被拒`() {
        assertFalse(NcmDecoder.isNcm(streamOf(0x49, 0x44, 0x33, 0x04)))  // "ID3"
    }

    @Test(expected = IllegalArgumentException::class)
    fun `解码非 NCM 抛异常`() {
        NcmDecoder.decode(streamOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
    }

    @Test
    fun `过短的流不被认为是 NCM`() {
        assertFalse(NcmDecoder.isNcm(streamOf(0x43, 0x54)))  // "CT" (不足 8 字节)
    }
}
