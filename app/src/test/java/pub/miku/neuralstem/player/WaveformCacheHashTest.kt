package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** P3 回归：波形缓存文件名使用稳定 SHA-256，而不是 32-bit hashCode。 */
class WaveformCacheHashTest {

    @Test
    fun `abc 的 SHA-256 与标准值一致`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            WaveformCache.sha256Hex("abc"),
        )
    }

    @Test
    fun `不同 key 前缀得到不同缓存名`() {
        assertEquals(64, WaveformCache.sha256Hex("a|1").length)
        assertEquals(64, WaveformCache.sha256Hex("b|1").length)
        org.junit.Assert.assertNotEquals(
            WaveformCache.sha256Hex("a|1"),
            WaveformCache.sha256Hex("b|1"),
        )
    }
}
