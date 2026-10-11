package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pub.miku.neuralstem.playlist.PlaylistStore

/**
 * ★⑧：冷启动续播的**目标选择**纯逻辑（[PlayerViewModel.pickRestoreTarget]）单测。
 *
 * 覆盖：默认歌单优先 / 扫其它歌单 / uri 定位 / uri 找不到退回 0 / 无记录→null / 空歌单→null。
 */
class RestoreTargetTest {

    private fun list(
        id: String,
        tracks: List<Pair<String, String>> = emptyList(),
        lastPlayedUri: String? = null,
        lastPositionMs: Long = 0L,
        isDefault: Boolean = false,
    ) = PlaylistStore.Playlist(
        id = id, name = id, isDefault = isDefault,
        tracks = tracks.map { PlaylistStore.TrackRef(it.first, it.second) },
        lastPlayedUri = lastPlayedUri, lastPositionMs = lastPositionMs,
    )

    @Test
    fun `默认歌单优先：即使其它歌单也有进度`() {
        val def = list("default", listOf("u1" to "A", "u2" to "B"), lastPlayedUri = "u2", lastPositionMs = 5000, isDefault = true)
        val other = list("x", listOf("u9" to "Z"), lastPlayedUri = "u9", lastPositionMs = 9000)
        val t = PlayerViewModel.pickRestoreTarget(listOf(other, def), "default")!!
        assertEquals("default", t.listId)
        assertEquals(1, t.index)
        assertEquals(5000L, t.positionMs)
        assertEquals("B", t.title)
    }

    @Test
    fun `默认歌单无进度则扫其它歌单`() {
        val def = list("default", listOf("u1" to "A"), lastPlayedUri = null, isDefault = true)
        val other = list("x", listOf("u9" to "Z"), lastPlayedUri = "u9", lastPositionMs = 9000)
        val t = PlayerViewModel.pickRestoreTarget(listOf(def, other), "default")!!
        assertEquals("x", t.listId)
        assertEquals(0, t.index)
        assertEquals(9000L, t.positionMs)
    }

    @Test
    fun `lastPlayedUri 找不到则退回 0 并保留位置`() {
        val def = list("default", listOf("u1" to "A", "u2" to "B"), lastPlayedUri = "gone", lastPositionMs = 1234, isDefault = true)
        val t = PlayerViewModel.pickRestoreTarget(listOf(def), "default")!!
        assertEquals(0, t.index)
        assertEquals(1234L, t.positionMs)
        assertEquals("A", t.title)
    }

    @Test
    fun `负位置被钳到 0`() {
        val def = list("default", listOf("u1" to "A"), lastPlayedUri = "u1", lastPositionMs = -5, isDefault = true)
        assertEquals(0L, PlayerViewModel.pickRestoreTarget(listOf(def), "default")!!.positionMs)
    }

    @Test
    fun `无任何 lastPlayed 记录返回 null`() {
        val def = list("default", listOf("u1" to "A"), isDefault = true)
        assertNull(PlayerViewModel.pickRestoreTarget(listOf(def), "default"))
    }

    @Test
    fun `歌单为空或无曲返回 null`() {
        assertNull(PlayerViewModel.pickRestoreTarget(emptyList(), "default"))
        val empty = list("default", emptyList(), lastPlayedUri = "u1", isDefault = true)
        assertNull(PlayerViewModel.pickRestoreTarget(listOf(empty), "default"))
    }
}
