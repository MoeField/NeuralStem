package pub.miku.neuralstem.experimental.ncm

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ★从 MP4/M4A 容器里**提取音频轨的裸样本**（喂给 AVS3 解码器）。
 *
 * ## 为什么需要自己写
 * `MediaExtractor` 认不出 `av3a` 这个 codec tag（只认 mp4a/alac 等），
 * 所以拿不到 AVS3 轨的样本。但 **MP4 容器结构是公开且简单的** ——
 * 只需要 `moov/trak/mdia/minf/stbl` 里的几张表：
 *
 * ```
 * stsd      → codec tag（判断是不是 av3a）
 * stsz      → 每个样本的大小（或统一大小）
 * stco/co64 → 每个 chunk 在文件中的偏移
 * stsc      → 样本到 chunk 的映射（每 chunk 几个样本）
 * mdhd      → timescale / duration（算时长用）
 * ```
 */
object Mp4TrackExtractor {

    private const val TAG = "Mp4Track"

    /** 提取结果。 */
    data class Result(
        val codecTag: String,
        val samples: List<ByteArray>,
        val sampleSizes: List<Int>,
        val totalSamples: Int,
        val timescale: Int,
        val durationUnits: Long,
    ) {
        /** 时长（毫秒），timescale 为 0 时返回 0。 */
        fun durationMs(): Long =
            if (timescale > 0) durationUnits * 1000L / timescale else 0L
    }

    /**
     * 提取第一条**音频轨**的所有样本。
     *
     * @param mp4 完整 m4a 文件字节
     * @return null = 找不到音频轨或结构不支持
     */
    fun extractSamples(mp4: ByteArray): Result? {
        val root = readBoxes(mp4, 0, mp4.size) ?: return null
        val moov = root.firstOrNull { it.type == "moov" } ?: return null
        val moovChildren = readBoxes(mp4, moov.payloadStart, moov.end) ?: return null

        for (trak in moovChildren.filter { it.type == "trak" }) {
            val trakChildren = readBoxes(mp4, trak.payloadStart, trak.end) ?: continue
            val mdia = trakChildren.firstOrNull { it.type == "mdia" } ?: continue
            val mdiaChildren = readBoxes(mp4, mdia.payloadStart, mdia.end) ?: continue

            // hdlr 判断是不是音频轨（'soun'）
            val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" }
            val isSound = hdlr != null && hdlr.payloadStart + 12 <= mp4.size &&
                String(mp4, hdlr.payloadStart + 8, 4, Charsets.US_ASCII) == "soun"
            if (!isSound) continue

            val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" }
            var timescale = 0
            var durationUnits = 0L
            if (mdhd != null) {
                runCatching {
                    val buf = ByteBuffer.wrap(mp4).order(ByteOrder.BIG_ENDIAN)
                    val v = mp4[mdhd.payloadStart].toInt()
                    if (v == 1) {
                        timescale = buf.getInt(mdhd.payloadStart + 4 + 16)
                        durationUnits = buf.getLong(mdhd.payloadStart + 4 + 16 + 4)
                    } else {
                        timescale = buf.getInt(mdhd.payloadStart + 4 + 8)
                        durationUnits =
                            buf.getInt(mdhd.payloadStart + 4 + 8 + 4).toLong() and 0xFFFFFFFFL
                    }
                }
            }

            val minf = mdiaChildren.firstOrNull { it.type == "minf" } ?: continue
            val minfChildren = readBoxes(mp4, minf.payloadStart, minf.end) ?: continue
            val stbl = minfChildren.firstOrNull { it.type == "stbl" } ?: continue
            val stblChildren = readBoxes(mp4, stbl.payloadStart, stbl.end) ?: continue

            val stsd = stblChildren.firstOrNull { it.type == "stsd" } ?: continue
            val codecTag = readCodecTag(mp4, stsd)

            val stsz = stblChildren.firstOrNull { it.type == "stsz" } ?: continue
            val sampleSizes = readStsz(mp4, stsz) ?: continue

            val stco = stblChildren.firstOrNull { it.type == "stco" || it.type == "co64" }
                ?: continue
            val chunkOffsets = readStco(mp4, stco) ?: continue

            val stsc = stblChildren.firstOrNull { it.type == "stsc" }
            val stscEntries = stsc?.let { readStsc(mp4, it) } ?: emptyList()

            val offsets = readSampleOffsets(sampleSizes, chunkOffsets, stscEntries) ?: continue

            val samples = ArrayList<ByteArray>(offsets.size)
            for ((i, off) in offsets.withIndex()) {
                val sz = sampleSizes[i]
                if (off < 0 || off + sz > mp4.size) {
                    Log.w(TAG, "sample[$i] 越界 off=$off sz=$sz（跳过）")
                    continue
                }
                samples += mp4.copyOfRange(off.toInt(), (off + sz).toInt())
            }
            Log.i(
                TAG,
                "extracted: codec=$codecTag samples=${samples.size} " +
                    "totalBytes=${sampleSizes.sum()} timescale=$timescale",
            )
            return Result(
                codecTag = codecTag,
                samples = samples,
                sampleSizes = sampleSizes,
                totalSamples = sampleSizes.size,
                timescale = timescale,
                durationUnits = durationUnits,
            )
        }
        return null
    }

    // ---------------- MP4 box 解析 ----------------

    private data class Box(val type: String, val start: Int, val payloadStart: Int, val end: Int)

    private fun readBoxes(data: ByteArray, from: Int, to: Int): List<Box>? {
        val out = ArrayList<Box>()
        var p = from
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (p + 8 <= to) {
            var size = buf.getInt(p).toLong() and 0xFFFFFFFFL
            if (size < 8) break
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = buf.getLong(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) break
            out += Box(type, p, p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    /** 从 stsd 读 codec tag（如 `av3a` / `mp4a`）。 */
    private fun readCodecTag(data: ByteArray, stsd: Box): String {
        // stsd: version(1)+flags(3) entryCount(4) + sampleEntry: size(4) format(4) ...
        val p = stsd.payloadStart + 8
        if (p + 12 > data.size) return "?"
        return String(data, p + 4, 4, Charsets.US_ASCII)
    }

    /** stsz: version+flags(4) sampleSize(4) sampleCount(4) [sizes...] */
    private fun readStsz(data: ByteArray, stsz: Box): List<Int>? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val p = stsz.payloadStart
        val uniform = buf.getInt(p + 4)
        val count = buf.getInt(p + 8)
        if (count <= 0 || count > 5_000_000) return null
        return if (uniform != 0) {
            List(count) { uniform }
        } else {
            val out = IntArray(count)
            var q = p + 12
            for (i in 0 until count) {
                if (q + 4 > data.size) return null
                out[i] = buf.getInt(q); q += 4
            }
            out.toList()
        }
    }

    /** stco/co64: version+flags(4) entryCount(4) [offset(4|8)...] */
    private fun readStco(data: ByteArray, stco: Box): LongArray? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val p = stco.payloadStart
        val count = buf.getInt(p + 4)
        if (count <= 0 || count > 5_000_000) return null
        return if (stco.type == "co64") {
            LongArray(count) { i ->
                val o = p + 8 + i * 8
                if (o + 8 > data.size) return null
                buf.getLong(o)
            }
        } else {
            LongArray(count) { i ->
                val o = p + 8 + i * 4
                if (o + 4 > data.size) return null
                buf.getInt(o).toLong() and 0xFFFFFFFFL
            }
        }
    }

    /** stsc 条目：(firstChunk 1-based, samplesPerChunk, descIdx)。 */
    private fun readStsc(data: ByteArray, stsc: Box): List<Triple<Int, Int, Int>> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val p = stsc.payloadStart
        val count = buf.getInt(p + 4)
        if (count <= 0 || count > 100_000) return emptyList()
        return (0 until count).mapNotNull { i ->
            val o = p + 8 + i * 12
            if (o + 12 > data.size) return@mapNotNull null
            Triple(buf.getInt(o), buf.getInt(o + 4), buf.getInt(o + 8))
        }
    }

    /**
     * 把 (stsz, stco, stsc) 展开成**每个样本的绝对偏移**。
     *
     * ## stsc 语义
     * 每条 `(firstChunk, samplesPerChunk, _)` 表示：**从 `firstChunk`（1-based）起**，
     * 直到下一条目之前，每个 chunk 含 `samplesPerChunk` 个样本。
     */
    private fun readSampleOffsets(
        sampleSizes: List<Int>,
        chunkOffsets: LongArray,
        stsc: List<Triple<Int, Int, Int>>,
    ): LongArray? {
        if (sampleSizes.isEmpty() || chunkOffsets.isEmpty()) return null
        val n = sampleSizes.size
        val out = LongArray(n)
        var sampleIdx = 0
        val entries = stsc.ifEmpty { listOf(Triple(1, 1, 1)) }

        for (chunkIdx in chunkOffsets.indices) {
            val chunkNo = chunkIdx + 1
            var perChunk = 1
            for (e in entries) {
                if (e.first <= chunkNo) perChunk = e.second else break
            }
            var off = chunkOffsets[chunkIdx]
            repeat(perChunk) {
                if (sampleIdx >= n) return@repeat
                out[sampleIdx] = off
                off += sampleSizes[sampleIdx]
                sampleIdx++
            }
            if (sampleIdx >= n) break
        }
        if (sampleIdx < n) {
            Log.w(TAG, "只解析到 $sampleIdx / $n 个样本（stsc 不完整）")
        }
        return out
    }

    /** 把样本拼成连续字节流。 */
    fun concat(samples: List<ByteArray>): ByteArray {
        val total = samples.sumOf { it.size }
        val bos = ByteArrayOutputStream(total)
        samples.forEach { bos.write(it) }
        return bos.toByteArray()
    }
}
