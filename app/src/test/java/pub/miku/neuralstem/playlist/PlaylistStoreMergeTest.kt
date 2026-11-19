package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** P2.2 回归：VM 快照落盘不能覆盖播放器直写的新进度。 */
class PlaylistStoreMergeTest {

    private val store = PlaylistStore(File(System.getProperty("java.io.tmpdir")))

    private fun track(uri: String) = PlaylistStore.TrackRef(uri, uri)

    private fun list(progressUri: String?, pos: Long, tracks: List<PlaylistStore.TrackRef>) =
        PlaylistStore.Playlist(
            id = "l1", name = "list", tracks = tracks,
            lastPlayedUri = progressUri, lastPositionMs = pos,
        )

    @Test
    fun `磁盘新进度优先于 VM 旧快照`() {
        val disk = list("b", 9000, listOf(track("a"), track("b")))
        val incoming = list("a", 1000, listOf(track("a"), track("b")))
        val merged = store.mergeDiskProgress(listOf(incoming), listOf(disk)).single()
        assertEquals("b", merged.lastPlayedUri)
        assertEquals(9000L, merged.lastPositionMs)
    }

    @Test
    fun `曲目被移除后进度清空`() {
        val disk = list("b", 9000, listOf(track("a"), track("b")))
        val incoming = list("b", 9000, listOf(track("a")))
        val merged = store.mergeDiskProgress(listOf(incoming), listOf(disk)).single()
        assertNull(merged.lastPlayedUri)
        assertEquals(0L, merged.lastPositionMs)
    }
}
