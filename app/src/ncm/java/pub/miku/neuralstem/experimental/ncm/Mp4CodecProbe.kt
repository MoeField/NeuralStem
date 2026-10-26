package pub.miku.neuralstem.experimental.ncm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ★M4A 容器里**音频 sample entry 的 fourcc** 批量探测（纯 Kotlin、无 Android 依赖）。
 *
 * ## 为什么需要
 * 网易云私有目录里那 220 个 `.m4a`，容器都是标准 MP4（`ftypisom`），但**内部 codec 可能不同**：
 *
 * | fourcc | 含义          | Android 原生能播 |
 * |--------|---------------|------------------|
 * | `av3a` | AVS3-P3       | ✗ 必须走自研解码器 |
 * | `mp4a` | AAC           | ✓ |
 * | `alac` | Apple Lossless| ✓ |
 *
 * 也就是「扩展名是 `.m4a`」≠「能播」——已实测：抽第一个 m4a 就是 `av3a`。
 * 导入扫描时必须**先探 codec 再决定走哪条播放/转码路径**，否则会在
 * `MediaPlayer`/`MediaCodec` 上拿一个晦涩的 `setDataSource failed`。
 *
 * ## 为什么不用字符串搜索
 * 天真做法是 `bytes.indexOf("av3a")`，但：
 * 1. `mdat`（音频数据）里完全可能出现字面量 `av3a`/`mp4a` → **假阳性**；
 * 2. 反过来，若为了安全只搜 `moov` 区域，又需要先解析 box 才有 `moov` 边界 → 绕回原点。
 *
 * ⇒ 老老实实按 **ISO/IEC 14496-12 的 box 结构**逐层走：
 * ```
 * [4字节大端 size][4字节 type][payload...]        size == 1 → 后跟 8 字节 largesize
 *                                                 size == 0 → 到父 box 末尾
 * ```
 *
 * ## 与 [Mp4TrackExtractor] 的关系
 * 那个类要**解析全部样本表**（stsz/stco/stsc）并真的把音频数据拷出来，代价大、还要
 * 完整文件。这里只关心**元数据标签**，因此：
 * - 只需要 `moov` 子树（一般就在文件前部）→ 见 [probeCodec]，读前 256KB 即可；
 * - `mdat` 的 payload **整块跳过**，绝不逐字节扫（220 个文件 × 几十 MB 会很痛）。
 */
object Mp4CodecProbe {

    /** box 头最小长度：size(4) + type(4)。 */
    private const val MIN_BOX_HEADER = 8

    /** size == 1 时的扩展头长度：size(4) + type(4) + largesize(8)。 */
    private const val LARGE_BOX_HEADER = 16

    /** 嵌套深度上限。正常 m4a 最深 6 层（moov/trak/mdia/minf/stbl/stsd），8 层留余量。 */
    private const val MAX_DEPTH = 8

    /** 便捷探测建议读取的字节数：`moov` 绝大多数情况下位于文件头部。 */
    const val HEAD_PROBE_BYTES = 256 * 1024

    // ==================== 公开 API ====================

    /**
     * 从 MP4 字节里找出**音频 sample entry** 的 fourcc（`av3a`/`mp4a`/`alac`/...）。
     *
     * 语义：取**第一条 `hdlr == 'soun'`** 的轨道，其 `stsd` 里第一个 sample entry 的 fourcc。
     * 找不到音频轨（例如纯视频、或结构残缺）→ null。
     *
     * @param bytes 完整文件字节，或至少包含 `moov` 的前缀（见 [HEAD_PROBE_BYTES]）
     */
    fun findAudioCodec(bytes: ByteArray): String? {
        if (bytes.size < MIN_BOX_HEADER) return null
        val buf = bytes.asBigEndianBuffer()

        // 顶层：moov 只应有一个（拆片 MP4 才会有多个，那种情况这里不处理）。
        val moov = findChild(buf, 0, bytes.size, "moov", 0) ?: return null

        // moov 下可能有多个 trak（音频 + 视频 + 字幕 + 章节）。
        // 必须逐个检查 hdlr，不能"取第一个 trak"——视频轨在前是很常见的。
        forEachChild(buf, moov.payloadStart, moov.end, 1) { trak ->
            if (trak.type != "trak") return@forEachChild false
            val codec = audioCodecOfTrak(buf, trak, 2)
            if (codec != null) return codec
            false // 非音频轨 / 该轨残缺 → 继续看下一个 trak
        }
        return null
    }

    /**
     * 从 `mdhd` box 里读出 `timescale` 与 `duration`，返回**毫秒**。
     *
     * 同样只认**第一条音频轨**——视频轨的 mdhd 是帧时钟，拿来算音频时长会差很远。
     *
     * 注意 `duration` 可能是 `0xFFFFFFFF`（表示"未知"，常见于流式录制），
     * 这种情况返回 null 而不是一个天文数字。
     */
    fun readDurationMs(bytes: ByteArray): Long? {
        if (bytes.size < MIN_BOX_HEADER) return null
        val buf = bytes.asBigEndianBuffer()

        val moov = findChild(buf, 0, bytes.size, "moov", 0) ?: return null

        forEachChild(buf, moov.payloadStart, moov.end, 1) { trak ->
            if (trak.type != "trak") return@forEachChild false
            if (!isAudioTrak(buf, trak)) return@forEachChild false

            val mdia = findChild(buf, trak.payloadStart, trak.end, "mdia", 2)
                ?: return@forEachChild false
            val mdhd = findChild(buf, mdia.payloadStart, mdia.end, "mdhd", 3)
                ?: return@forEachChild false
            return durationMsOfMdhd(buf, mdhd)
        }
        return null
    }

    /**
     * 便捷：只喂文件**前 [HEAD_PROBE_BYTES] 字节**即可返回 codec。
     *
     * 单独留一个方法而不是直接在 [findAudioCodec] 里截断，是因为调用方可能
     * 已经有完整字节（内存里），那就没必要再拷一份 256KB。
     * 这里做 `copyOf` 的**唯一作用**是防御性：允许调用方传一个比文件大的缓冲区。
     */
    fun probeCodec(headBytes: ByteArray): String? {
        if (headBytes.isEmpty()) return null
        val slice = if (headBytes.size > HEAD_PROBE_BYTES) {
            headBytes.copyOf(HEAD_PROBE_BYTES)
        } else {
            headBytes
        }
        return findAudioCodec(slice)
    }

    // ==================== 轨道级逻辑 ====================

    /** 在一条 trak 内找 stsd 首个 sample entry 的 fourcc（非音频轨返回 null）。 */
    private fun audioCodecOfTrak(buf: ByteBuffer, trak: Box, depth: Int): String? {
        if (!isAudioTrak(buf, trak)) return null

        val mdia = findChild(buf, trak.payloadStart, trak.end, "mdia", depth) ?: return null
        val minf = findChild(buf, mdia.payloadStart, mdia.end, "minf", depth + 1) ?: return null
        val stbl = findChild(buf, minf.payloadStart, minf.end, "stbl", depth + 2) ?: return null
        val stsd = findChild(buf, stbl.payloadStart, stbl.end, "stsd", depth + 3) ?: return null
        return readFirstSampleEntryFourcc(buf, stsd)
    }

    /** `hdlr` 的 handler_type 是否为 `'soun'`（音频轨）。 */
    private fun isAudioTrak(buf: ByteBuffer, trak: Box): Boolean {
        val mdia = findChild(buf, trak.payloadStart, trak.end, "mdia", 2) ?: return false
        val hdlr = findChild(buf, mdia.payloadStart, mdia.end, "hdlr", 3) ?: return false

        // hdlr FullBox: version(1)+flags(3) = 4；然后是 pre_defined(4)；再是 handler_type(4)。
        // ⇒ handler_type 在 payload 起点 + 8 处。
        val p = hdlr.payloadStart + 8
        if (p + 4 > hdlr.end) return false
        return fourccAt(buf, p) == "soun"
    }

    /**
     * stsd（SampleDescriptionBox）:
     * ```
     * [4 version+flags][4 entryCount]
     * [4 size][4 format][6 reserved][2 data_reference_index][...]   ← SampleEntry
     * ```
     * 只要第一个 entry 的 `format` —— 音频轨上它就是 codec fourcc。
     */
    private fun readFirstSampleEntryFourcc(buf: ByteBuffer, stsd: Box): String? {
        val entryCountPos = stsd.payloadStart + 4
        if (entryCountPos + 4 > stsd.end) return null
        val entryCount = buf.getInt(entryCountPos)
        // entryCount 为 0 或负数（恶意/垃圾数据）→ 没有样本描述，退出。
        if (entryCount <= 0) return null

        val entryStart = entryCountPos + 4
        // 至少要有：size(4) + format(4)
        if (entryStart + 8 > stsd.end) return null

        val entrySize = buf.getInt(entryStart)
        // entrySize 必须能覆盖自身头部，否则说明数据坏了。
        if (entrySize < MIN_BOX_HEADER) return null

        val fourcc = fourccAt(buf, entryStart + 4)
        // 全 0 / 空白 fourcc 视为无效 —— 有些坏文件会写 0 填充。
        if (isMeaninglessFourcc(fourcc)) return null
        return fourcc
    }

    /** mdhd → durationMs。 */
    private fun durationMsOfMdhd(buf: ByteBuffer, mdhd: Box): Long? {
        val p = mdhd.payloadStart
        if (p + 4 > mdhd.end) return null
        val version = buf.get(p).toInt() and 0xFF

        val timescale: Long
        val duration: Long
        if (version == 1) {
            // creation(8) modification(8) timescale(4) duration(8)
            if (p + 4 + 16 + 4 + 8 > mdhd.end) return null
            timescale = buf.getInt(p + 4 + 16).toLong() and 0xFFFFFFFFL
            duration = buf.getLong(p + 4 + 16 + 4)
        } else {
            // version 0（也兜住其它未知版本 —— 结构一致，按 0 读）：
            // creation(4) modification(4) timescale(4) duration(4)
            if (p + 4 + 8 + 4 + 4 > mdhd.end) return null
            timescale = buf.getInt(p + 4 + 8).toLong() and 0xFFFFFFFFL
            duration = buf.getInt(p + 4 + 8 + 4).toLong() and 0xFFFFFFFFL
        }

        if (timescale <= 0) return null
        // MP4 规范：全 1 的 duration 表示"未知时长"（流式录制常见）。
        if (duration <= 0L || duration == 0xFFFFFFFFL || duration == -1L) return null

        // 先乘后除会溢出吗？duration 最大 ~4e9（v0），×1000 = 4e12，远小于 Long.MAX。
        return duration * 1000L / timescale
    }

    // ==================== box 遍历原语 ====================

    /** 一个已解析出边界的 box。`end` 是**下一个 box 的起点**（不含）。 */
    private class Box(val type: String, val payloadStart: Int, val end: Int)

    /**
     * 读 [p] 处的 box 头。
     *
     * @return null = 头残缺 / size 非法（size < 8、或声称的长度越过 [limit]）
     */
    private fun readBoxHeader(buf: ByteBuffer, p: Int, limit: Int): Box? {
        if (p < 0 || p + MIN_BOX_HEADER > limit) return null

        var size = buf.getInt(p).toLong() and 0xFFFFFFFFL
        val type = fourccAt(buf, p + 4)
        var header = MIN_BOX_HEADER

        if (size == 1L) {
            // 64 位 largesize（大文件 / mdat 用），规范要求此时 size 字段恰为 1。
            if (p + LARGE_BOX_HEADER > limit) return null
            size = buf.getLong(p + 8)
            header = LARGE_BOX_HEADER
        } else if (size == 0L) {
            // size == 0 表示"延伸到父 box 末尾"。这里沿用现有实现的做法把它**当作非法**：
            // 我们解析的父容器（moov/trak/mdia/minf/stbl）长度都是已知的，
            // 出现 0 几乎只可能是损坏数据；直接拒绝比"猜一个长度"更安全。
            return null
        }

        // size 必须至少覆盖自己的头，否则 p 不会前进 → 死循环。
        if (size < header) return null
        // 越界（声称比父容器还长）→ 拒绝，不做截断猜测。
        if (size > (limit - p).toLong()) return null

        val end = p + size.toInt()
        return Box(type, p + header, end)
    }

    /** 找到 [from, to) 范围内的第一个 [type] 子 box。 */
    private fun findChild(buf: ByteBuffer, from: Int, to: Int, type: String, depth: Int): Box? {
        if (depth > MAX_DEPTH) return null
        var found: Box? = null
        forEachChild(buf, from, to, depth) { box ->
            if (box.type == type) {
                found = box
                true
            } else {
                false
            }
        }
        return found
    }

    /**
     * 遍历 [from, to) 的**直接子 box**；[action] 返回 true 表示提前结束。
     *
     * 循环安全靠 [readBoxHeader] 的双重保证：
     * 1. `size >= header` → 每次至少前进 8 字节；
     * 2. `size <= limit - p` → 不会读到父容器之外。
     * 任一不满足即 break，因此畸形数据**不可能**造成死循环。
     */
    private inline fun forEachChild(
        buf: ByteBuffer,
        from: Int,
        to: Int,
        depth: Int,
        action: (Box) -> Boolean,
    ) {
        if (depth > MAX_DEPTH) return
        var p = from
        while (p + MIN_BOX_HEADER <= to) {
            val box = readBoxHeader(buf, p, to) ?: return
            if (action(box)) return
            p = box.end
        }
    }

    // ==================== 小工具 ====================

    private fun ByteArray.asBigEndianBuffer(): ByteBuffer =
        ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)

    /** 读 4 字节 US-ASCII fourcc；调用方须已保证 `at + 4 <= 容量`。 */
    private fun fourccAt(buf: ByteBuffer, at: Int): String {
        val chars = CharArray(4) { i -> (buf.get(at + i).toInt() and 0xFF).toChar() }
        return String(chars)
    }

    /**
     * fourcc 全是 `\u0000` 或空格 → 无效标签。
     *
     * 刻意不叫 `isNullOrBlank` —— 那个名字与 Kotlin 标准库的 `CharSequence?.isNullOrBlank()`
     * 重名，会靠重载解析"碰巧"工作，后续一旦改动接收者类型就会静默变成另一个语义。
     */
    private fun isMeaninglessFourcc(value: String): Boolean =
        value.all { it == '\u0000' || it == ' ' }
}
