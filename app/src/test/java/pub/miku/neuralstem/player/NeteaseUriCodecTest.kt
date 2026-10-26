package pub.miku.neuralstem.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★`netease://` 逻辑引用编解码的往返一致性测试。
 *
 * ## 为什么这个测试很关键
 * 歌单里存的**就是**这个 uri。如果 encode/decode 不对称，
 * 1000 首歌的路径全对不上 → 点了全播不了，而且**错误很隐蔽**
 * （uri 看起来"有内容"，只是路径不对）。
 *
 * ## 为何不直接用 android.net.Uri 测
 * `android.net.Uri` 在 JVM 单测里是 **stub**（调什么都返回 null）。
 * 所以实现放在了纯字符串的 [NeteaseUriCodec] 里，这里才测得动。
 * ——这个限制本身是实测发现的（第一版测试直接 NPE）。
 */
class NeteaseUriCodecTest {

    @Test
    fun `基本路径往返一致`() {
        val p = "/storage/6AA6-9706/Android/data/com.netease.cloudmusic/files/Documents/Music/song.m4a"
        assertEquals(p, NeteaseUriCodec.decode(NeteaseUriCodec.encode(p)))
    }

    @Test
    fun `含空格与中文的路径往返一致`() {
        val p = "/storage/6AA6-9706/Android/data/com.netease.cloudmusic/files/Documents/Music/" +
            "23.exe 初音ミク - Loow.m4a"
        assertEquals(p, NeteaseUriCodec.decode(NeteaseUriCodec.encode(p)))
    }

    @Test
    fun `含全角符号与括号的路径往返一致`() {
        // 真实文件名里出现过的写法
        val p = "/storage/x/Music/6＆9 - Cornfield Chase（Piano Concerto Ver).m4a"
        assertEquals(p, NeteaseUriCodec.decode(NeteaseUriCodec.encode(p)))
    }

    @Test
    fun `文件名里含加号时不能被变成空格`() {
        // ★这是最容易错的一条：URLEncoder 把空格编成 '+'，
        //   而 URLDecoder 会把 '+' 解成空格 —— 如果文件名本来就有 '+'，
        //   不做处理就会被误变成空格。
        val p = "/storage/x/Music/a+b c.m4a"
        val round = NeteaseUriCodec.decode(NeteaseUriCodec.encode(p))
        assertEquals(p, round)
        assertTrue("加号必须保留", round!!.contains("a+b"))
    }

    @Test
    fun `文件名里含百分号时原样还原`() {
        val p = "/storage/x/Music/100% pure.m4a"
        assertEquals(p, NeteaseUriCodec.decode(NeteaseUriCodec.encode(p)))
    }

    @Test
    fun `编码结果里不出现双重编码`() {
        // 手拼字符串会让 %2F 变成 %252F（%2F 又被编码一次）。
        val encoded = NeteaseUriCodec.encode("/storage/y.m4a")
        assertFalse("不应出现 %252F（双重编码特征）", encoded.contains("%252F"))
        assertTrue("应出现 %2F（正常的 / 编码）", encoded.contains("%2F"))
    }

    @Test
    fun `isNetease 只认本 scheme`() {
        assertTrue(NeteaseUriCodec.isNetease("netease://%2Fa.m4a"))
        assertFalse(NeteaseUriCodec.isNetease("file:///sdcard/a.m4a"))
        assertFalse(NeteaseUriCodec.isNetease("content://x/a.m4a"))
    }

    @Test
    fun `decode 对非本 scheme 返回 null`() {
        assertNull(NeteaseUriCodec.decode("file:///sdcard/a.m4a"))
        assertNull(NeteaseUriCodec.decode("content://media/a.mp3"))
        assertNull(NeteaseUriCodec.decode(""))
    }

    @Test
    fun `decode 对空路径返回 null`() {
        assertNull(NeteaseUriCodec.decode("netease://"))
    }

    @Test
    fun `编码后无未转义空格`() {
        // 空格必须编码，否则 uri 里出现裸空格会导致解析歧义
        val encoded = NeteaseUriCodec.encode("/a b/c d.m4a")
        assertFalse("不应有裸空格", encoded.contains(" "))
    }
}
