package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ★模型清单单测。
 *
 * 重点锁死一个真机踩过的坑：**2-stem 与 4-stem 的 onnx 文件大小不同**
 * （都是 U-Net 架构但权重不同）。曾因为两者复用同一个字节常量，
 * 导致 4-stem 已下载完却显示「未安装」（真机实报）。
 */
class ModelCatalogTest {

    @Test
    fun `每个包都有非空文件与来源`() {
        for (info in ModelCatalog.ALL) {
            assertTrue("${info.pack} 应有文件", info.files.isNotEmpty())
            assertTrue("${info.pack} 应有许可说明", info.license.isNotBlank())
            assertTrue("${info.pack} 应有许可详情", info.licenseNote.isNotBlank())
            assertTrue("${info.pack} 来源应为 HF", info.sourceUrl.startsWith("https://huggingface.co/"))
            assertTrue("${info.pack} 总体积应 > 0", info.totalBytes > 0)
        }
    }

    @Test
    fun `文件名与远端路径是一致的`() {
        for (info in ModelCatalog.ALL) {
            for (f in info.files) {
                assertTrue(
                    "${f.fileName} 应出现在 remotePath 里：${f.remotePath}",
                    f.remotePath.endsWith(f.fileName),
                )
                assertTrue("targetSubdir 不应为空", f.targetSubdir.isNotBlank())
            }
        }
    }

    /**
     * 核心回归：2-stem 与 4-stem 的期望字节数**必须不同**。
     *
     * 若有人把它们统一成一个常量，本测试会失败 —— 那正是真机 bug 的来源。
     */
    @Test
    fun `spleeter 2stem 与 4stem 的字节数不同`() {
        val two = ModelCatalog.info(ModelPack.SPLEETER_2STEM).files.first().expectedBytes
        val four = ModelCatalog.info(ModelPack.SPLEETER_4STEM).files.first().expectedBytes
        assertTrue("两者都应 > 0", two > 0 && four > 0)
        assertNotEquals("2-stem 与 4-stem 的 onnx 大小不同（架构同、权重不同）", two, four)
    }

    @Test
    fun `2stem 两轨字节数接近但不要求相等`() {
        val files = ModelCatalog.info(ModelPack.SPLEETER_2STEM).files
        assertEquals(2, files.size)
        val diff = abs(files[0].expectedBytes - files[1].expectedBytes)
        assertTrue("两轨大小应接近（差 <1MB），实际差 $diff", diff < 1_000_000)
    }

    @Test
    fun `体积格式化可读`() {        assertEquals("39 MB", ModelCatalog.humanSize(39_000_000))
        assertEquals("181 MB", ModelCatalog.humanSize(181_000_000))
        assertTrue(ModelCatalog.humanSize(1_500).endsWith("KB"))
    }

    @Test
    fun `每个包的文件目标目录各不相同`() {
        val dirs = ModelCatalog.ALL.map { it.files.first().targetSubdir }
        assertEquals("不同包的落地目录不应重复", dirs.size, dirs.toSet().size)
    }
}
