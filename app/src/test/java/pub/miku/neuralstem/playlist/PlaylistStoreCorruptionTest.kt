package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
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
}
