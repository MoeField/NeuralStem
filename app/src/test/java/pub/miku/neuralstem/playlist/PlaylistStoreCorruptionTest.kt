package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistStoreCorruptionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `损坏歌单不被进度写覆盖`() {
        val dir = tmp.newFolder("files")
        val f = File(dir, "playlists.json")
        val original = "{ not valid json"
        f.writeText(original)

        val store = PlaylistStore(dir)
        val loaded = store.load()
        assertTrue("损坏文件应被标记", store.isCorrupt)
        assertTrue("仍应返回默认歌单", loaded.any { it.isDefault })
        assertTrue("应留下损坏备份", dir.listFiles().orEmpty().any { it.name.startsWith("playlists.json.corrupt-") })

        store.recordProgress(store.defaultListId, "content://x", 123L)
        store.save(loaded)
        assertEquals("损坏现场不能被覆盖", original, f.readText())
    }

    @Test
    fun `用户确认后可重建空歌单并恢复保存`() {
        val dir = tmp.newFolder("files_reset")
        val f = File(dir, "playlists.json")
        f.writeText("{ broken")
        val store = PlaylistStore(dir)
        val loaded = store.load()
        assertTrue(store.isCorrupt)
        assertTrue("损坏备份必须存在", dir.listFiles().orEmpty().any { it.name.startsWith("playlists.json.corrupt-") })

        assertTrue("重建空歌单应成功", store.resetCorruptToDefault())
        assertFalse("重建后 corrupt 标志应清除", store.isCorrupt)
        val repaired = store.load()
        assertFalse(store.isCorrupt)
        assertTrue(repaired.any { it.isDefault })
        assertTrue("重建后文件应是合法 JSON", f.readText().trim().startsWith("["))

        // 恢复保存能力
        store.save(loaded)
        assertTrue(f.readText().contains("默认歌单"))
    }
}
