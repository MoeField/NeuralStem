package pub.miku.neuralstem.experimental.ncm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Mp4CodecProbe] 的单测。
 *
 * ## 为何手搓字节而不是放真实样本
 * 真实 m4a 有版权/体积问题，且"只覆盖那 220 个文件里出现过的畸形情况"。
 * 手工拼 box 反而能**精确构造边界**：hdlr 不是 soun、stsd 截断、坏 size……
 * 这些正是真机上最容易踩的坑。
 *
 * ## 为何放在 ncmTest
 * `Mp4CodecProbe` 位于 `src/ncm/java`，**只在 `-Pwith_ncm=true` 时参与编译**。
 * 若把测试放进 `src/test/java`，默认（不带 with_ncm）构建会因为
 * `Unresolved reference: Mp4CodecProbe` 直接编译失败。
 * `app/build.gradle.kts` 的 `with_ncm` 分支里已把 `src/ncmTest/java` 加入
 * `test` sourceSet，所以放这儿天然与主源码同生共死。
 */
class Mp4CodecProbeTest {

    // ==================== 构造工具 ====================

    /** 把一个 box 拼成字节：`[4字节 size][4字节 type][payload...]`。 */
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        require(type.length == 4) { "fourcc 必须 4 字符: $type" }
        val body = payload.fold(ByteArray(0)) { acc, it -> acc + it }
        val out = ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(8 + body.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(body)
        return out.array()
    }

    /** 大端 32 位整数。 */
    private fun be32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    /** uint32（用 Long 表示，便于写 0xFFFFFFFF 这类值）。 */
    private fun beU32(v: Long): ByteArray = be32(v.toInt())

    /** FullBox 的 version+flags。 */
    private fun fullBoxHeader(version: Int, flags: Int): ByteArray =
        byteArrayOf(
            version.toByte(),
            (flags shr 16 and 0xFF).toByte(),
            (flags shr 8 and 0xFF).toByte(),
            (flags and 0xFF).toByte(),
        )

    /** `hdlr`：version+flags(4) pre_defined(4) handler_type(4) reserved(12) name(1)。 */
    private fun hdlr(handlerType: String): ByteArray = box(
        "hdlr",
        fullBoxHeader(0, 0),
        be32(0),                                    // pre_defined
        handlerType.toByteArray(Charsets.US_ASCII), // 'soun' / 'vide' / ...
        ByteArray(12),                              // reserved[3]
        byteArrayOf(0),                             // name（空串）
    )

    /** `mdhd` v0：creation(4) modification(4) timescale(4) duration(4) 语言(2) 质量(2)。 */
    private fun mdhdV0(timescale: Int, duration: Long): ByteArray = box(
        "mdhd",
        fullBoxHeader(0, 0),
        be32(0),            // creation_time
        be32(0),            // modification_time
        be32(timescale),
        beU32(duration),
        ByteArray(4),       // language + pre_defined
    )

    /** `mdhd` v1：creation(8) modification(8) timescale(4) duration(8)。 */
    private fun mdhdV1(timescale: Int, duration: Long): ByteArray = box(
        "mdhd",
        fullBoxHeader(1, 0),
        ByteArray(8),       // creation_time
        ByteArray(8),       // modification_time
        be32(timescale),
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(duration).array(),
        ByteArray(4),
    )

    /** `stsd`：version+flags(4) entryCount(4) + 一个 SampleEntry。 */
    private fun stsd(fourcc: String): ByteArray = box(
        "stsd",
        fullBoxHeader(0, 0),
        be32(1),     // entryCount
        // SampleEntry: size(4) format(4) reserved(6) data_reference_index(2)
        // size 用 16 = 4 + 4 + 6 + 2（一个"只含头部"的最小 AudioSampleEntry）
        be32(16),
        fourcc.toByteArray(Charsets.US_ASCII),
        ByteArray(6),       // reserved
        byteArrayOf(0, 1),  // data_reference_index = 1
    )

    /** 拼一条完整的轨（音频或视频，由 handlerType 决定）。 */
    private fun trak(handlerType: String, codecFourcc: String, mdhd: ByteArray): ByteArray {
        val stbl = box("stbl", stsd(codecFourcc))
        val minf = box("minf", stbl)
        val mdia = box("mdia", hdlr(handlerType), mdhd, minf)
        return box("trak", mdia)
    }

    /** 拼一个完整文件：ftyp + mdat(垃圾数据) + moov(trak...)。 */
    private fun mp4(vararg traks: ByteArray): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII), be32(512))
        // mdat 里故意塞入 "av3a"/"mp4a"/"alac" 字面量 —— 证明实现没偷懒做字符串搜索
        val mdat = box("mdat", "-----av3a-----mp4a-----alac-----".toByteArray(Charsets.US_ASCII))
        val moov = box("moov", *traks)
        return ftyp + mdat + moov
    }

    // ==================== 1. 正常路径 ====================

    @Test
    fun `最小合法 MP4 - 能读出 av3a`() {
        val bytes = mp4(trak("soun", "av3a", mdhdV0(44100, 44100 * 3)))

        assertEquals("av3a", Mp4CodecProbe.findAudioCodec(bytes))
    }

    @Test
    fun `mp4a 与 alac 同样能读出`() {
        assertEquals(
            "mp4a",
            Mp4CodecProbe.findAudioCodec(mp4(trak("soun", "mp4a", mdhdV0(44100, 44100)))),
        )
        assertEquals(
            "alac",
            Mp4CodecProbe.findAudioCodec(mp4(trak("soun", "alac", mdhdV0(44100, 44100)))),
        )
    }

    @Test
    fun `mdat 里的 av3a 字面量不会造成假阳性（证明不是字符串搜索）`() {
        // 文件 mdat 段含 "av3a"，但真正的音频轨是 mp4a → 必须报 mp4a
        val bytes = mp4(trak("soun", "mp4a", mdhdV0(48000, 48000)))

        assertEquals("mp4a", Mp4CodecProbe.findAudioCodec(bytes))
    }

    @Test
    fun `多轨时跳过视频轨 取音频轨的 codec`() {
        // 视频轨在前 —— 真实 mp4 常见，必须靠 hdlr 判断而不是"取第一个 trak"
        val bytes = mp4(
            trak("vide", "avc1", mdhdV0(90000, 90000)),
            trak("soun", "av3a", mdhdV0(44100, 44100)),
        )

        assertEquals("av3a", Mp4CodecProbe.findAudioCodec(bytes))
    }

    // ==================== 2. 非音频轨 ====================

    @Test
    fun `只有 hdlr 为 vide 的轨道 - 返回 null`() {
        val bytes = mp4(trak("vide", "av3a", mdhdV0(90000, 90000)))

        assertNull("不是音频轨就不该返回 codec", Mp4CodecProbe.findAudioCodec(bytes))
    }

    @Test
    fun `没有 moov - 返回 null`() {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII))
        val mdat = box("mdat", ByteArray(64))

        assertNull(Mp4CodecProbe.findAudioCodec(ftyp + mdat))
    }

    // ==================== 3. 截断 / 畸形数据：一律 null 且不抛 ====================

    @Test
    fun `空数组返回 null`() {
        assertNull(Mp4CodecProbe.findAudioCodec(ByteArray(0)))
        assertNull(Mp4CodecProbe.probeCodec(ByteArray(0)))
        assertNull(Mp4CodecProbe.readDurationMs(ByteArray(0)))
    }

    @Test
    fun `不足一个 box 头的短数据返回 null`() {
        assertNull(Mp4CodecProbe.findAudioCodec(ByteArray(3)))
        assertNull(Mp4CodecProbe.findAudioCodec(ByteArray(7)))
        assertNull(Mp4CodecProbe.readDurationMs(ByteArray(5)))
    }

    @Test
    fun `逐字节截断合法文件 任何前缀都只返回 null 或正确值 且不抛异常`() {
        val full = mp4(trak("soun", "av3a", mdhdV0(44100, 44100 * 3)))

        for (len in 0..full.size) {
            val prefix = full.copyOf(len)
            // 不吞异常：若实现抛了，测试直接失败（这正是我们要保证的）
            val codec = Mp4CodecProbe.findAudioCodec(prefix)
            if (codec != null) {
                // 一旦能读出，就必须是正确答案（不能读出垃圾）
                assertEquals("len=$len 读出了错误的 codec", "av3a", codec)
            }
            Mp4CodecProbe.readDurationMs(prefix) // 只要不抛即可
        }
    }

    @Test
    fun `size 小于 8 的坏 box 不会死循环`() {
        // [size=4][type=moov] —— 声称长度比头还短
        val bad = ByteArray(16).also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4)
            "moov".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
        }

        assertNull(Mp4CodecProbe.findAudioCodec(bad))
    }

    @Test
    fun `size 为 0 的坏 box 不会死循环`() {
        val bad = ByteArray(16).also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(0)
            "moov".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
        }

        assertNull(Mp4CodecProbe.findAudioCodec(bad))
    }

    @Test
    fun `size 声称超过父容器的坏 box 返回 null`() {
        val bad = ByteArray(16).also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(0x7FFFFFFF)
            "moov".toByteArray(Charsets.US_ASCII).copyInto(it, 4)
        }

        assertNull(Mp4CodecProbe.findAudioCodec(bad))
    }

    @Test
    fun `stsd entryCount 为 0 返回 null`() {
        val emptyStsd = box(
            "stsd",
            fullBoxHeader(0, 0),
            be32(0), // entryCount = 0
        )
        val mdia = box(
            "mdia",
            hdlr("soun"),
            mdhdV0(44100, 44100),
            box("minf", box("stbl", emptyStsd)),
        )
        val bytes = mp4(box("trak", mdia))

        assertNull(Mp4CodecProbe.findAudioCodec(bytes))
    }

    // ==================== 4. readDurationMs ====================

    @Test
    fun `mdhd v0 - 时长按 timescale 换算成毫秒`() {
        // 44100 samples/s，持续 3 秒 = 132300 units
        val bytes = mp4(trak("soun", "av3a", mdhdV0(44100, 132_300)))

        assertEquals(3000L, Mp4CodecProbe.readDurationMs(bytes))
    }

    @Test
    fun `mdhd v1 - 64 位 duration 也正确`() {
        // 48000 samples/s，持续 2.5 秒 = 120000 units
        val bytes = mp4(trak("soun", "mp4a", mdhdV1(48000, 120_000)))

        assertEquals(2500L, Mp4CodecProbe.readDurationMs(bytes))
    }

    @Test
    fun `duration 为全 1（未知时长）返回 null`() {
        val bytes = mp4(trak("soun", "av3a", mdhdV0(44100, 0xFFFFFFFFL)))

        assertNull("未知时长不能被当成 ~4e9ms", Mp4CodecProbe.readDurationMs(bytes))
    }

    @Test
    fun `timescale 为 0 返回 null（避免除零）`() {
        val bytes = mp4(trak("soun", "av3a", mdhdV0(0, 44100)))

        assertNull(Mp4CodecProbe.readDurationMs(bytes))
    }

    @Test
    fun `视频轨的 mdhd 不会被当成音频时长`() {
        // 视频轨 timescale=90000/9000 units(=100ms)，音频轨 44100/3s → 应返回 3000ms
        val bytes = mp4(
            trak("vide", "avc1", mdhdV0(90000, 9000)),
            trak("soun", "av3a", mdhdV0(44100, 132_300)),
        )

        assertEquals(3000L, Mp4CodecProbe.readDurationMs(bytes))
    }

    // ==================== 5. probeCodec ====================

    @Test
    fun `probeCodec 对完整文件与头部切片结果一致`() {
        val full = mp4(trak("soun", "av3a", mdhdV0(44100, 132_300)))
        val head = full.copyOf(minOf(full.size, Mp4CodecProbe.HEAD_PROBE_BYTES))

        assertEquals("av3a", Mp4CodecProbe.probeCodec(head))
        assertEquals(Mp4CodecProbe.findAudioCodec(full), Mp4CodecProbe.probeCodec(full))
    }

    @Test
    fun `probeCodec 超过 256KB 的输入只取前 256KB`() {
        // 把 moov 放在 256KB 之后 —— 超出探测窗口，应返回 null（而不是读垃圾）
        val padding = ByteArray(Mp4CodecProbe.HEAD_PROBE_BYTES) { 'x'.code.toByte() }
        val bytes = box("ftyp", "isom".toByteArray(Charsets.US_ASCII)) +
            box("mdat", padding) +
            box("moov", trak("soun", "av3a", mdhdV0(44100, 44100)))

        assertNull(Mp4CodecProbe.probeCodec(bytes))
        // 但完整解析仍然拿得到
        assertEquals("av3a", Mp4CodecProbe.findAudioCodec(bytes))
    }
}
