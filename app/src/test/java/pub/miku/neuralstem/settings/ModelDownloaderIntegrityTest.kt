package pub.miku.neuralstem.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** P2.6 回归：下载前复用判定与 .part 校验必须真的看 SHA-256。 */
class ModelDownloaderIntegrityTest {

    private val shaAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val file = ModelFile("x", "tmp", "m.onnx", expectedBytes = 3, sha256 = shaAbc)

    private fun tempWith(text: String): File =
        File.createTempFile("model-integrity-", ".onnx").apply {
            deleteOnExit()
            writeText(text)
        }

    @Test
    fun `已有文件哈希命中才可复用`() {
        val dl = ModelDownloader(File(System.getProperty("java.io.tmpdir")))
        assertTrue(dl.isUsableExisting(tempWith("abc"), file))
        assertFalse(dl.isUsableExisting(tempWith("abd"), file))
    }

    @Test
    fun `下载 part 哈希不匹配会删除并抛错`() {
        val dl = ModelDownloader(File(System.getProperty("java.io.tmpdir")))
        val bad = tempWith("abd")
        var threw = false
        try {
            dl.verifySha256(bad, file)
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("应抛 IOException", threw)
        assertFalse("校验失败必须删除 part", bad.exists())
    }

    @Test
    fun `下载 part 哈希匹配通过`() {
        val dl = ModelDownloader(File(System.getProperty("java.io.tmpdir")))
        val good = tempWith("abc")
        dl.verifySha256(good, file)
        assertTrue(good.exists())
    }
}
