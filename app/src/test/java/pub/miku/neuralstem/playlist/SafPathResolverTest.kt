package pub.miku.neuralstem.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ★`SafPathResolver.toContentUri` 的测试 —— 它是 [SafPathResolver.resolve] 的**逆运算**，
 * 用于把旧歌单里读不了的 `file://` 绝对路径迁回 `content://`。
 *
 * ## 为何测的是「docId 推导」而不是最终 uri 字符串
 * `android.net.Uri` 在 JVM 单测里是 **stub**（`Uri.Builder.build()` 返回 null），
 * 且本项目**没有引入 Robolectric**（见 `build.gradle.kts` 的
 * `testOptions { unitTests.isReturnDefaultValues = true }`）。
 * 所以这里测的是「路径 → SAF documentId」这一步纯字符串推导 ——
 * 它才是迁移里唯一可能出错的地方（`primary:` vs `<volume>:` 前缀）。
 *
 * ## 为何这个测试很关键
 * 迁移是**一次性且会写盘**的（改写用户的 `playlists.json`）。
 * 前缀判断错了 → 歌单里所有 uri 指向不存在的 document → 全播不了，
 * 而且**文件已经被覆盖**，原始数据回不来。
 */
class SafPathResolverTest {

    /** 复刻 `toContentUri` 的前缀推导（与实现保持同步；改实现必须改这里）。 */
    private fun docIdFor(path: String): String? {
        return when {
            path.startsWith("/storage/emulated/0/") ->
                "primary:" + path.removePrefix("/storage/emulated/0/")

            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                val slash = rest.indexOf('/')
                val volume = if (slash > 0) rest.substring(0, slash) else null
                when {
                    volume == null || volume == "emulated" || volume == "self" -> null
                    else -> "$volume:" + rest.substring(slash + 1)
                }
            }

            else -> null
        }
    }

    @Test
    fun `内置存储映射为 primary 前缀`() {
        assertEquals(
            "primary:Download/NSImportTest/a.ncm",
            docIdFor("/storage/emulated/0/Download/NSImportTest/a.ncm"),
        )
    }

    @Test
    fun `内置存储根下的文件`() {
        assertEquals("primary:Music/a.mp3", docIdFor("/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun `外置 SD 卡映射为卷名前缀`() {
        assertEquals(
            "6AA6-9706:Music/b.m4a",
            docIdFor("/storage/6AA6-9706/Music/b.m4a"),
        )
    }

    @Test
    fun `emulated 不能被当成卷名`() {
        // /storage/emulated/X 这种不是合法的 SD 卡卷名；
        // 误判会造出 "emulated:X" 这种不存在的 documentId。
        assertNull(docIdFor("/storage/emulated/1/Music/a.mp3"))
    }

    @Test
    fun `self 不能被当成卷名`() {
        assertNull(docIdFor("/storage/self/primary/Music/a.mp3"))
    }

    @Test
    fun `缺少路径段时返回 null`() {
        assertNull(docIdFor("/storage/"))
        assertNull(docIdFor("/storage/6AA6-9706"))
    }

    @Test
    fun `非 storage 前缀返回 null`() {
        assertNull(docIdFor("/data/user/0/pub.miku.neuralstem/files/a.wav"))
        assertNull(docIdFor("/sdcard/a.mp3"))
    }

    @Test
    fun `含空格与全角括号的路径原样保留`() {
        // %20 / %EF%BC%88 的编码由 Uri.Builder 负责，这里只验证不丢字符
        assertEquals(
            "primary:Download/NSImportTest/Carry GLIKER - Revive（Original Mix）.ncm",
            docIdFor("/storage/emulated/0/Download/NSImportTest/Carry GLIKER - Revive（Original Mix）.ncm"),
        )
    }

    // ---------------- 迁移判定（migrateUri 的语义） ----------------

    @Test
    fun `只有 file 开头才需要迁移`() {
        assertNull(migrateCandidate("content://com.android.externalstorage.documents/document/primary%3AA.ncm"))
        assertNull(migrateCandidate("netease://%2Fstorage%2Fa.m4a"))
        assertNull(migrateCandidate("ncm:abc123"))
        assertEquals(
            "/storage/emulated/0/a.ncm",
            migrateCandidate("file:///storage/emulated/0/a.ncm"),
        )
    }

    @Test
    fun `file uri 里编码的空格会被解回`() {
        assertEquals(
            "/storage/emulated/0/Download/Carry GLIKER - Revive.ncm",
            migrateCandidate("file:///storage/emulated/0/Download/Carry%20GLIKER%20-%20Revive.ncm"),
        )
    }

    /** 复刻 `PlaylistStore.migrateUri` 里「是否 file:// + 取出路径」这一步。 */
    private fun migrateCandidate(uri: String): String? {
        if (!uri.startsWith("file://")) return null
        return runCatching { java.net.URI(uri).path }.getOrNull()
    }
}
