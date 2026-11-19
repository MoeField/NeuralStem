package pub.miku.neuralstem.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2.6 回归：所有模型文件必须带官方 SHA-256，且格式合法。 */
class ModelCatalogIntegrityTest {

    @Test
    fun `每个模型文件都有合法且唯一的 SHA-256`() {
        val files = ModelCatalog.ALL.flatMap { it.files }
        assertEquals("模型文件数量变化时同步更新测试", 24, files.size)
        val seen = mutableSetOf<String>()
        files.forEach { f ->
            assertTrue("${f.fileName} 缺少 sha256", f.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue("${f.fileName} sha256 重复: ${f.sha256}", seen.add(f.sha256))
        }
    }

    @Test
    fun `模型包数量仍为二十个`() {
        assertEquals(20, ModelCatalog.ALL.size)
    }
}
