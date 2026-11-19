package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** P2.6 回归：下载后 SHA-256 计算与标准值一致。 */
class ModelDownloaderHashTest {

    @Test
    fun `abc 的 SHA-256 与标准值一致`() {
        val f = File.createTempFile("sha256-", ".bin").apply { deleteOnExit() }
        f.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(f),
        )
    }
}
