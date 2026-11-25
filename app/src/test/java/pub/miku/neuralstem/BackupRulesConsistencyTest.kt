package pub.miku.neuralstem

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * P2-15 回归守卫：backup_rules.xml（API \u2264 30 的 fullBackupContent）与
 * data_extraction_rules.xml（API 31+ 的 cloud-backup/device-transfer）必须保持同一排除集。
 *
 * 背景：这两套规则由 manifest 同时声明；只改一套会导致某个 API 段仍把大缓存/外部目录
 * 带上云备份。之前就漏了 backup_rules.xml 的 avs3-pcm-cache / NCM。
 */
class BackupRulesConsistencyTest {

    private val xmlDir = File("src/main/res/xml")

    private fun excludes(file: File, parentTag: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = doc.getElementsByTagName("exclude")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val parent = e.parentNode as? Element ?: continue
            if (parent.tagName == parentTag) {
                out += "${e.getAttribute("domain")}:${e.getAttribute("path")}"
            }
        }
        return out
    }

    @Test
    fun `legacy 与现代备份规则排除集一致`() {
        val legacyFile = File(xmlDir, "backup_rules.xml")
        val modernFile = File(xmlDir, "data_extraction_rules.xml")
        assertTrue("backup_rules.xml 不存在: ${legacyFile.absolutePath}", legacyFile.exists())
        assertTrue("data_extraction_rules.xml 不存在: ${modernFile.absolutePath}", modernFile.exists())

        val legacy = excludes(legacyFile, "full-backup-content")
        val cloud = excludes(modernFile, "cloud-backup")
        val transfer = excludes(modernFile, "device-transfer")

        assertTrue("legacy 规则为空", legacy.isNotEmpty())
        assertEquals("cloud-backup 与 backup_rules 不一致", legacy, cloud)
        assertEquals("device-transfer 与 backup_rules 不一致", legacy, transfer)
    }
}
