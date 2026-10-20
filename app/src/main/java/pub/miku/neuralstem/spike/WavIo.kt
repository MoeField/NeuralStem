package pub.miku.neuralstem.spike

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * WAV 读写工具(Task 0e:全面改为流式,支持长音频)。
 *
 * ============================ 为什么必须流式 ============================
 * guide N2 要求"内存安全:必要时使用流式加载或分块处理"。
 *
 * 2026-02 实测(5:14 的 Good Night,44.1kHz 立体声):
 *   totalFrames = 13,850,487 帧
 *   浮点样本数 = 13.85M × 2ch = 27.7M 个 float ≈ **110 MB**
 * 而 API 25 模拟器默认 Java 堆只有 **48 MB**(`dalvik.vm.heapgrowthlimit=48m`,`largeHeap` 才 256MB)。
 * 旧实现把整个文件读进 `ByteArray` 再转 `FloatArray`,直接:
 *   `OutOfMemoryError: Failed to allocate a 55397576 byte allocation with 2302464 free bytes
 *    and 44MB until OOM`  ← 崩在 WavIo.read 第 78 行
 *
 * 结论:**整曲驻留内存的架构对 4 分钟以上歌曲不成立**。因此改为三层:
 *   - [probe]      只解析 RIFF 头,拿元信息(声道/采样率/总帧数),不碰音频数据
 *   - [WavReader]  按帧随机读,内存占用 = 一个块的量(与歌曲总长无关)
 *   - [WavWriter]  顺序追加写,不需要在内存里攒整首歌
 *
 * ⚠️ Task 0e 已**删除**旧的全量 API(`WavIo.read()` / `WavIo.write()` / `WavData`)。
 * 它们已经没有调用者,而 `read()` 正是当初 OOM 的那一处 ——
 * 留着一个「已经爆过的 API」在代码里,等于下次有人顺手调用时再炸一次。
 * 需要整块内存时请显式用 [WavReader.readFrames] 并自担内存开销。
 * =====================================================================
 */
object WavIo {

    /** WAV 元信息(不包含音频数据)。 */
    data class WavInfo(val channels: Int, val sampleRate: Int, val totalFrames: Int) {
        val durationMs: Long get() = (totalFrames * 1000L) / sampleRate
    }

    // ---------------- 头部解析 ----------------

    /**
     * 只解析 RIFF 头拿元信息。内存占用 O(1),与文件大小无关。
     * 仅支持 PCM 16-bit / mono 或 stereo(Spike 从简)。
     */
    fun probe(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val h = parseHeader(raf, file)
            val actualDataLen = minOf(h.dataLen, raf.length() - h.dataPos).toInt()
            val totalFrames = actualDataLen / (2 * h.channels)
            return WavInfo(h.channels, h.sampleRate, totalFrames)
        }
    }

    /** RIFF 头部解析结果。internal:供 [WavReader] 复用(避免二次解析)。 */
    internal class Header(
        val channels: Int,
        val sampleRate: Int,
        val dataPos: Long,
        val dataLen: Long,
    )

    internal fun parseHeader(raf: RandomAccessFile, file: File): Header {
        require(readFourCc(raf) == "RIFF") { "Not a RIFF file: $file" }
        raf.readIntLE() // riff size
        require(readFourCc(raf) == "WAVE") { "Not a WAVE file: $file" }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataPos = -1L
        var dataLen = -1L

        while (raf.filePointer < raf.length()) {
            val chunkId = readFourCc(raf)
            val chunkSize = raf.readIntLE().toLong() and 0xFFFFFFFFL
            when (chunkId) {
                "fmt " -> {
                    audioFormat = raf.readShortLE()
                    channels = raf.readShortLE()
                    sampleRate = raf.readIntLE()
                    raf.readIntLE()  // byte rate
                    raf.readShortLE() // block align
                    bitsPerSample = raf.readShortLE()
                    val skip = (chunkSize - 16).toInt()
                    if (skip > 0) raf.seek(raf.filePointer + skip) // 跳过 fmt 扩展区
                }
                "data" -> {
                    dataPos = raf.filePointer
                    dataLen = chunkSize
                    raf.seek(raf.filePointer + chunkSize)
                }
                else -> raf.seek(raf.filePointer + chunkSize) // 跳过未知 chunk
            }
        }

        require(dataPos >= 0) { "No data chunk in $file" }
        require(audioFormat == 1) { "Only PCM(1) supported, got format=$audioFormat" }
        require(bitsPerSample == 16) { "Only 16-bit PCM supported, got $bitsPerSample-bit" }
        require(channels in 1..2) { "Only mono/stereo supported, got $channels channels" }
        return Header(channels, sampleRate, dataPos, dataLen)
    }

    // ---------------- 全量读:已删除(见文件头警告) ----------------
    //
    // 早期版本有 `fun read(file: File): WavData`,一次性把整曲读进 FloatArray。
    // Task 0e 实测它在 5:14 全曲上直接 OOM(48MB 堆 vs 110MB 数组),
    // 且已无调用者,故整体移除以免被重新引入。

    // ---------------- 写(writer 见下方 WavWriter) ----------------

    // 注:早期的 `write(file, samples, channels, sampleRate)` 一次性写入口已删除。
    // 需要时直接用 [WavWriter](内部会回填头部)。

}

// ---------------- 小端 IO helpers ----------------
// 顶层声明:WavIo / WavReader / WavWriter 三者共用。
// (若放回 object WavIo 内部,会变成"成员扩展函数",顶层类 WavWriter 就看不见了)

internal fun readFourCc(raf: RandomAccessFile): String {
    val b = ByteArray(4)
    raf.readFully(b)
    return String(b, Charsets.US_ASCII)
}

internal fun RandomAccessFile.readIntLE(): Int =
    readUnsignedByte() or
        (readUnsignedByte() shl 8) or
        (readUnsignedByte() shl 16) or
        (readUnsignedByte() shl 24)

internal fun RandomAccessFile.readShortLE(): Int =
    readUnsignedByte() or (readUnsignedByte() shl 8)

internal fun RandomAccessFile.writeIntLE(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
}

internal fun RandomAccessFile.writeShortLE(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF)
}

/**
 * 流式 WAV 读取器(Task 0e)。
 *
 * 按帧随机访问,内存占用 = 请求的那一块(不随歌曲总长增长)。
 * 读取自动 clamp 到文件末尾,调用方不必额外判断边界。
 */
class WavReader(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val header: WavIo.Header
    val info: WavIo.WavInfo

    init {
        header = WavIo.parseHeader(raf, file)
        val actualDataLen = minOf(header.dataLen, raf.length() - header.dataPos).toInt()
        info = WavIo.WavInfo(header.channels, header.sampleRate, actualDataLen / (2 * header.channels))
    }

    val channels: Int get() = info.channels
    val sampleRate: Int get() = info.sampleRate
    val totalFrames: Int get() = info.totalFrames

    /**
     * 读 [frames] 帧,从 [startFrame] 开始,返回**交织** FloatArray。
     * 超出末尾的部分会被截断(返回值可能短于请求长度)。
     */
    fun readFrames(startFrame: Int, frames: Int): FloatArray {
        val availFrames = (totalFrames - startFrame).coerceAtLeast(0)
        val n = minOf(frames, availFrames)
        if (n <= 0) return FloatArray(0)

        val byteOffset = header.dataPos + startFrame.toLong() * 2 * channels
        raf.seek(byteOffset)

        val bytes = ByteArray(n * channels * 2)
        raf.readFully(bytes)

        val out = FloatArray(n * channels)
        var b = 0
        for (i in out.indices) {
            val lo = bytes[b++].toInt() and 0xFF
            val hi = bytes[b++].toInt()
            val s16 = (hi shl 8) or lo
            out[i] = s16 / 32768f
        }
        return out
    }

    override fun close() {
        raf.close()
    }
}

/**
 * 流式 WAV 写入器(Task 0e)。
 *
 * 总帧数在写入前**未知**(分块流式推理,每块输出长度还要看模型),
 * 因此头部 size 字段先写 0,fmt 之后立即开始写 data,
 * [close] 时再 seek 回填 RIFF size(off=4)与 data size(off=40)为**实际**写入长度。
 *
 * 与旧实现的差异(重要,别再改回去):
 * - 旧版按入参 `totalFrames` **预先写死**头部,并在 [append] 里 clamp 超长部分。
 *   而实测 sherpa 输出帧数**少于**输入(Task 0d:1150976 vs 1151115,
 *   差 139 帧,STFT 边界效应)。旧实现会把这段差距静默截断/补静音,
 *   使输出文件的头部声明与实际内容不符。改为回填后,写多少就是多少。
 * - 新增 [appendRegion]:分块流式写盘中,「只有本块覆盖」的那一段是样本数组的
 *   连续子区间,直接写子区间可避免为它再复制一份整块大小的数组
 *   (30s 块×2 轨 ≈ 21MB)—— 48MB 堆上不可接受。
 *   (早期曾计划用「回写重叠区」(overwriteAt) 落盘,后因「归一化头先算后顺序写」
 *    使落盘顺序天然单调而废弃,见 StreamingSeparator。)
 */
class WavWriter(
    file: File,
    private val channels: Int,
    private val sampleRate: Int,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var closed = false

    /** 已写入的帧数。写入过程中可读,供日志与进度核对。 */
    var framesWritten: Int = 0
        private set

    init {
        require(channels in 1..2) { "Only mono/stereo supported, got $channels" }
        raf.setLength(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(0)                            // RIFF size  ← close() 回填
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(16)
        raf.writeShortLE(1)                          // PCM
        raf.writeShortLE(channels)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(sampleRate * channels * 2)    // byte rate
        raf.writeShortLE(channels * 2)               // block align
        raf.writeShortLE(16)                         // bits per sample
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(0)                            // data size  ← close() 回填
    }

    /** 顺序追加一段交织 float 样本。**不 clamp**:写多少算多少。 */
    fun append(samples: FloatArray) {
        appendRegion(samples, 0, samples.size / channels)
    }

    /**
     * 顺序追加 [samples] 中从 [startFrame] 起的 [frames] 帧。
     *
     * 存在理由见类注释:流式写盘时「只有本块覆盖」的段是样本数组的连续子区间,
     * 直接写子区间可避免为它再整块复制一份。
     */
    fun appendRegion(samples: FloatArray, startFrame: Int, frames: Int) {
        require(startFrame >= 0) { "negative startFrame: $startFrame" }
        require(frames >= 0) { "negative frames: $frames" }
        if (frames == 0) return
        require((startFrame + frames) * channels <= samples.size) {
            "appendRegion 越界: ($startFrame+$frames)*$channels > ${samples.size}"
        }
        raf.seek(DATA_START + framesWritten.toLong() * channels * 2)
        raf.write(toPcm16(samples, startFrame, frames))
        framesWritten += frames
    }

    /** 交织 float [-1,1] → 16-bit little-endian PCM 字节。[startFrame] 起共 [frames] 帧。 */
    private fun toPcm16(samples: FloatArray, startFrame: Int, frames: Int): ByteArray {
        val buf = ByteArray(frames * channels * 2)
        var b = 0
        val base = startFrame * channels
        for (i in 0 until frames * channels) {
            var v = samples[base + i]
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            val s16 = (v * 32767f).toInt()
            buf[b++] = (s16 and 0xFF).toByte()
            buf[b++] = ((s16 shr 8) and 0xFF).toByte()
        }
        return buf
    }

    /** 回填头部两处 size,然后关闭。幂等。 */
    override fun close() {
        if (closed) return
        closed = true
        val dataSize = framesWritten * channels * 2
        raf.seek(4)
        raf.writeIntLE(36 + dataSize)
        raf.seek(40)
        raf.writeIntLE(dataSize)
        raf.close()
    }

    private companion object {
        /** 头部固定 44 字节(data 起始偏移)。 */
        const val DATA_START = 44L
    }
}

/**
 * ★G-3-重构：**随机写 WAV**（完整曲长、按绝对帧位置写入）。
 *
 * ## 与 [WavWriter] 的区别
 * | | [WavWriter] | 本类 |
 * |---|---|---|
 * | 长度 | 写完才回填 | **创建时就写死完整长度** |
 * | 写入 | 只能顺序 append | **任意位置 writeAt(absFrame)** |
 * | 用途 | 分离完再播（文件一次性写完） | **边分离边填（播放器同时读）** |
 *
 * ## 为何需要（解决用户实报的三个缺陷）
 * 旧方案“只落盘 [startFrame, 曲尾) + 播放时 seek 补偿”导致：
 * 1. 分离完跳回起始点；2. 结果不含前面部分；3. 波形差一截。
 * 本类让**缓存文件从头就是完整曲长**，分离只是按绝对位置填充，上述问题自然消失。
 *
 * ## 技术前提（已由 `RandomWriteProtoTest` + 真机验证）
 * - 预分配区读出来是 **0（静音）而非错误**；
 * - ExoPlayer 能正常播“前段有数据、后段全 0”的 WAV（播到 10002ms 正常结束）；
 * - 边写边读（双 fd）安全。
 *
 * ## 并发
 * 写侧为本类单线程调用（分离协程）；读侧是播放器另一个 fd。
 * 不保证“写完立刻可见”—— 需要时调用 [flush]（分离完成时调一次足够）。
 *
 * @param totalFrames 完整曲长帧数（头部与文件长度均按它写死）。
 */
class RandomAccessWavWriter(
    file: File,
    private val channels: Int,
    private val sampleRate: Int,
    totalFrames: Int,
    /**
     * ★续算（2026-10-20）：**复用已存在的文件**，不重新预分配/不重写头。
     *
     * 为何需要：两阶段分离后，若用户杀掉进程再回来续补，
     * 重新 `setLength()` 会把**已分离的数据全部抹掉**（变成全 0）。
     *
     * 前提：调用方已确认文件存在且长度正确（见 [RangeManifest]）。
     * 若文件长度不符，仍会按 [totalFrames] 重新预分配。
     */
    private val reuseExisting: Boolean = false,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var closed = false

    /** 完整曲长（帧）。 */
    val totalFrames: Int = totalFrames

    /** 已写入的**最大绝对帧号 + 1**（= 水位线；用于快速判断“能否读到该位置”）。 */
    @Volatile
    var writtenUpToFrame: Int = 0
        private set

    init {
        require(channels in 1..2) { "Only mono/stereo supported, got $channels" }
        require(totalFrames >= 0) { "totalFrames must be >= 0, got $totalFrames" }
        val frameBytes = channels * 2
        val expectedLen = DATA_START + totalFrames.toLong() * frameBytes

        // ★续算：文件已经完整（长度对）→ **不碰数据**，直接接着写。
        //   注意：头部也不重写（内容一样，重写只会多一次 sync）。
        //   `init` 里不能用 `return` → 用 if/else 表达。
        val canReuse = reuseExisting && raf.length() == expectedLen
        if (canReuse) {
            writtenUpToFrame = 0   // 由 manifest 决定“实际上有什么”，这里不猜
        } else {
            // 预分配完整长度（未写区域由文件系统填 0 = 静音）。
            raf.setLength(expectedLen)
            raf.seek(0)
            val dataSize = totalFrames * frameBytes
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.writeIntLE(36 + dataSize)
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.writeIntLE(16)
            raf.writeShortLE(1)                       // PCM
            raf.writeShortLE(channels)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * frameBytes)   // byte rate
            raf.writeShortLE(frameBytes)              // block align
            raf.writeShortLE(16)                      // bits per sample
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.writeIntLE(dataSize)
            // 立刻落盘头部 —— 播放器可能在分离开始后立即打开本文件。
            raf.fd.sync()
        }
    }

    /**
     * 将 [samples] 中从 [startFrame] 起的 [frames] 帧，写到文件的**绝对帧位置** [absFrame]。
     *
     * @param absFrame 目标绝对帧号（相对整曲开头，非块内偏移）。
     */
    fun writeAt(samples: FloatArray, startFrame: Int, frames: Int, absFrame: Int) {
        require(startFrame >= 0) { "negative startFrame: $startFrame" }
        require(frames >= 0) { "negative frames: $frames" }
        require(absFrame >= 0) { "negative absFrame: $absFrame" }
        if (frames == 0) return
        require((startFrame + frames) * channels <= samples.size) {
            "writeAt 越界: ($startFrame+$frames)*$channels > ${samples.size}"
        }
        require(absFrame + frames <= totalFrames) {
            "writeAt 超出总长: absFrame=$absFrame frames=$frames total=$totalFrames"
        }
        raf.seek(DATA_START + absFrame.toLong() * channels * 2)
        raf.write(toPcm16(samples, startFrame, frames))
        // 更新水位线（水位线语义 = “连续可播到哪”；乱序写时取 max，够用）。
        val end = absFrame + frames
        if (end > writtenUpToFrame) writtenUpToFrame = end
    }

    /** 把已写数据刷到磁盘（分离完成或阶段结束时调，确保播放器读得到）。 */
    fun flush() {
        runCatching { raf.fd.sync() }
    }

    /** 关闭。文件的 data size 已在构造时写死，无需回填。幂等。 */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { raf.fd.sync() }
        raf.close()
    }

    private fun toPcm16(samples: FloatArray, startFrame: Int, frames: Int): ByteArray {
        val buf = ByteArray(frames * channels * 2)
        var b = 0
        val base = startFrame * channels
        for (i in 0 until frames * channels) {
            var v = samples[base + i]
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            // 与 WavWriter 一致：用 round 而非截断，避免系统性偏小。
            val s16 = Math.round(v * 32767f).toInt()
            buf[b++] = (s16 and 0xFF).toByte()
            buf[b++] = ((s16 shr 8) and 0xFF).toByte()
        }
        return buf
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(
            byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ),
        )
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    private companion object {
        const val DATA_START = 44L
    }
}