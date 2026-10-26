package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ★波形缓存的 **key 推导**测试（2026-10-26）。
 *
 * ## 为何测这个
 * `ncm://<key>` 不是真实文件，`stampFor()` 取不到 size/mtime → 以前**永远不缓存**
 * （每次进页都重新解 30MB + 解码，波形还失败）。
 * 新增的 `load(context, key, stamp)` 重载绕开文件系统，但**key 撞车会让不同歌显示同一张波形**
 * —— 这种 bug 很难从界面上看出来（波形看起来"有内容"，只是内容不对），所以用测试钉死。
 *
 * ## 为何不能直接测 WaveformCache 的方法
 * 它依赖 `Context`（`filesDir`）与 `DataInputStream` 文件 IO，JVM 单测没有真实 Context。
 * 所以这里测的是**派生 key 的纯函数部分**（与实现保持同步）。
 */
class WaveformCacheKeyTest {

    /** 复刻 `WaveformCache.fileForStamp` 的哈希推导。 */
    private fun cacheName(key: String, stamp: String): String {
        val h = (key + "|" + stamp).hashCode().toUInt().toString(16)
        return "wf_$h.bin"
    }

    @Test
    fun `同一 key 与 stamp 产生同一文件名`() {
        val a = cacheName("ncm://5fda704", "ncm://5fda704")
        val b = cacheName("ncm://5fda704", "ncm://5fda704")
        assertEquals(a, b)
    }

    @Test
    fun `不同 ncm key 不碰撞`() {
        // 这两首是设备上真实存在的：Revive（Original Mix） 与 Revive
        assertNotEquals(
            cacheName("ncm://5fda704", "ncm://5fda704"),
            cacheName("ncm://21e404d", "ncm://21e404d"),
        )
    }

    @Test
    fun `内容变化导致文件名变化`() {
        // 同一个 uri，但 stamp 变了（字节长度变化 = 文件被替换）
        assertNotEquals(
            cacheName("ncm://abc", "31127315"),
            cacheName("ncm://abc", "31127316"),
        )
    }

    @Test
    fun `文件名前缀与扩展名稳定`() {
        // cleanCorrupt() 会遍历目录里所有文件并校验魔数，
        // 文件名格式变了不会崩，但保持稳定便于人工排查。
        val n = cacheName("k", "s")
        assertEquals(true, n.startsWith("wf_"))
        assertEquals(true, n.endsWith(".bin"))
    }

    @Test
    fun `哈希输出是合法十六进制串`() {
        val n = cacheName("ncm://x", "ncm://x")
        val hex = n.removePrefix("wf_").removeSuffix(".bin")
        assertEquals(true, hex.all { it in "0123456789abcdef" })
    }
}
