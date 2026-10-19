package pub.miku.neuralstem.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import pub.miku.neuralstem.spike.EdgeSeparationSession
import pub.miku.neuralstem.spike.StemPcmRingBuffer
import java.io.IOException
import kotlin.math.min

/**
 * ★G-3：“边分离边播”的**流式数据源**。
 *
 * ## 做什么
 * 把一个仍在增长的 [StemPcmRingBuffer]（分离器不断 append）**伪装成一个 WAV 流**，
 * 交给 Media3 的 `WavExtractor` 解码 → 复用现有 ExoPlayer/AudioTrack 通路（含变速变调），
 * **彻底绕开“WAV 头 size 要 close 才回填”导致的“0 长度文件”问题**。
 *
 * ## 关键设计
 * - **合成 WAV 头**：在流的最前面（offset 0 起）输出一个标准 44 字节 PCM WAV 头；
 *   其 `RIFF/data size` 填 `C.LENGTH_UNSET`（0xFFFFFFFF）语义的“未知长度”（用最大值占位），
 *   使 WavExtractor 以 `LENGTH_UNSET` 处理、不提前判定文件结束（依赖 read 返回 EOF）。
 * - **偏移 = 头长 + 帧**：外部 position（字节）< [WAV_HEADER_SIZE] 时先吐头；其后映射到绝对帧。
 *   因此 `open(DataSpec.position = T)` / `skip` 能实现“从任意已分离位置开始读”（用于切轨 carry-over）。
 * - **read 阻塞**：数据不足时由 [StemPcmRingBuffer.Cursor.read] 阻塞等（带超时）；分离结束读到 EOF。
 *
 * ## 局限（MVP）
 * - 只支持 PCM 16-bit 交织（与分离产物一致）。
 * - 若 seek 到“尚未分离到”的位置，read 会等待（由上层 clamp/提示）。
 */
@UnstableApi
class StemPcmDataSource internal constructor(
    private val buffer: StemPcmRingBuffer,
    private val sampleRate: Int,
    private val channels: Int,
    /** ★G-3：整段总帧数（合成 WAV 头用，使 ExoPlayer 能算出正确时长）。0=未知。 */
    private val totalFramesHint: Int = 0,
) : DataSource {

    /** 每帧字节数（PCM16 × channels）。 */
    private val frameBytes: Int = channels * 2

    private var cursor: StemPcmRingBuffer.Cursor? = null
    private var opened = false
    /** 当前绝对字节位置（含 WAV 头）。 */
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    /** 本次 open 的起始字节偏移（用于 header 声明的 data size）。 */
    private var openStartByte: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        // ★诊断：DataSource 打开（追“静音”问题时看这一行是否出现、位置多少）
        Log.i(TAG_STEM, "open: uri=${dataSpec.uri} pos=${dataSpec.position} len=${dataSpec.length} " +
            "closed=${buffer.isClosed} written=${buffer.totalWrittenFrames} " +
            "sr=$sampleRate ch=$channels totalHint=$totalFramesHint")
        val c = buffer.openCursor(0)
        cursor = c
        position = 0
        openStartByte = dataSpec.position
        opened = true
        // 支持从任意 position 打开（seek）：头部区间 [0,44) 之外映射到帧。
        val startPos = dataSpec.position
        if (startPos > 0) skipInternal(startPos)
        bytesRemaining = dataSpec.length
        return bytesRemaining
    }

    override fun read(bufferDst: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val c = cursor ?: throw IOException("read before open")
        // 1) 先吐 WAV 头（若当前位置在头部区间）。
        if (position < WAV_HEADER_SIZE) {
            val hdr = wavHeader()
            val from = position.toInt()
            val n = min((WAV_HEADER_SIZE - position).toInt(), length)
            System.arraycopy(hdr, from, bufferDst, offset, n)
            position += n
            return n
        }
        // 2) 再吐 PCM 数据。
        val framesWanted = length / frameBytes
        if (framesWanted <= 0) return 0
        val floatTmp = FloatArray(framesWanted * channels)
        val framesRead = c.read(floatTmp, 0, framesWanted)
        if (framesRead < 0) {
            // ★诊断：EOF（分离结束且已读完全部数据）。若未 Done 就 EOF = 异常。
            Log.i(TAG_STEM, "read EOF @frame=${position / frameBytes} closed=${buffer.isClosed} " +
                "written=${buffer.totalWrittenFrames}")
            return C.RESULT_END_OF_INPUT
        }
        if (framesRead == 0) {
            // ★诊断：暂无数据（读端等待超时）—— 若频繁出现，说明“播放追上分离”。
            Log.w(TAG_STEM, "read STARVED @frame=${position / frameBytes} " +
                "written=${buffer.totalWrittenFrames} closed=${buffer.isClosed}")
            return 0
        }
        val bytes = framesRead * frameBytes
        floatToPcm16(floatTmp, framesRead * channels, bufferDst, offset)
        position += bytes
        return bytes
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // 不需要传输统计（内存源），忽略。
    }

    override fun getUri(): Uri = Uri.parse("stem://${channels}ch_${sampleRate}")

    override fun close() {
        Log.i(TAG_STEM, "close @frame=${position / frameBytes} " +
            "written=${buffer.totalWrittenFrames} closed=${buffer.isClosed}")
        cursor?.close()
        cursor = null
        opened = false
    }

    /** 跳过 [bytes] 字节（含头）；用于 open(position)/seek。 */
    private fun skipInternal(bytes: Long) {
        val c = cursor ?: return
        var remaining = bytes
        // 跳过头部区间。
        if (position < WAV_HEADER_SIZE) {
            val headSkip = min(WAV_HEADER_SIZE - position, remaining)
            position += headSkip
            remaining -= headSkip
        }
        if (remaining <= 0) return
        val frames = remaining / frameBytes
        if (frames > 0) c.seekTo(c.position + frames)
        position += frames * frameBytes
    }

    // ---------------- WAV 头（合成） ----------------

    /**
     * 声明给 ExoPlayer 的 **data 字节数**。
     * - 若知道总帧数：`(totalFrames*channels*2) - 起始帧字节` → ExoPlayer 能算对时长；
     * - 否则回退 `-1`（未知，时长会算错——但不致命）。
     */
    private fun dataSizeBytes(): Int {
        if (totalFramesHint <= 0) return -1
        val totalBytes = totalFramesHint.toLong() * channels * 2
        val startByteInData = (openStartByte - WAV_HEADER_SIZE).coerceAtLeast(0)
        val size = (totalBytes - startByteInData).coerceAtLeast(0L)
        return if (size > Int.MAX_VALUE) -1 else size.toInt()
    }

    private val riffSize: Int get() = dataSizeBytes().let { if (it < 0) -1 else it + 36 }

    private fun wavHeader(): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val h = ByteArray(WAV_HEADER_SIZE)
        var p = 0
        fun ascii(s: String) { for (ch in s) h[p++] = ch.code.toByte() }
        fun le32(v: Int) { h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte(); h[p++] = ((v shr 16) and 0xFF).toByte(); h[p++] = ((v shr 24) and 0xFF).toByte() }
        fun le16(v: Int) { h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte() }
        ascii("RIFF"); le32(riffSize)
        ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(blockAlign); le16(16)
        ascii("data"); le32(dataSizeBytes())
        return h
    }

    private fun floatToPcm16(src: FloatArray, n: Int, dst: ByteArray, dstOffset: Int) {
        var b = dstOffset
        for (i in 0 until n) {
            var v = src[i]
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            // 用 round 而非截断：避免 0.5 LSB 系统性偏小（如 1000/32768*32767=999）。
            val s16 = Math.round(v * 32767f).toInt()
            dst[b++] = (s16 and 0xFF).toByte()
            dst[b++] = ((s16 shr 8) and 0xFF).toByte()
        }
    }

    /** Factory：供 ExoPlayer `DefaultMediaSourceFactory` 使用。 */
    class Factory(
        private val buffer: StemPcmRingBuffer,
        private val sampleRate: Int,
        private val channels: Int,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = StemPcmDataSource(buffer, sampleRate, channels)
    }

    companion object {
        /** ★诊断 TAG：`adb logcat -s StemTrace` 一次看全流式源生命周期。 */
        internal const val TAG_STEM = "StemTrace"

        const val WAV_HEADER_SIZE = 44
        /** 伪 scheme：`stem://vocals` / `stem://accompaniment`（播放入口用）。 */
        const val SCHEME = "stem"
    }
}

/**
 * ★G-3：按 uri scheme 分发的 DataSource 工厂。
 *
 * `PlaybackService.singlePlayer` 在**构建时**就装它 → 同一个 player 既能播文件（原曲/旧多轨），
 * 又能播分离流（`stem://…`）；无需运行时更换 MediaSourceFactory（ExoPlayer 实例不支持）。
 *
 * ✅ 关键：用 [ResolvingDataSource] —— **只**将 `stem://` 改写成 ring-buffer 数据源；
 *   其他所有 scheme（file/content/…）**原样透传**给默认 DataSource。
 *   （曾经的版本对所有 uri 都返回 RouterDataSource → 普通文件播放全坏，真机抓到。）
 */
@UnstableApi
class StemStreamFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = DispatchingDataSource(upstream)

    /**
     * 单个 DataSource，但 open 时按 uri scheme 决定：
     * - `stem://<host>` → [StemPcmDataSource]（从 session 取对应 buffer）；
     * - 其他 → 委派给 [upstream]（普通文件/内容）。
     *
     * ⚠️ 两个 delegate 必须各自 close（都实现了 close）。
     */
    private class DispatchingDataSource(
        private val upstream: DataSource.Factory,
    ) : DataSource {
        private var delegate: DataSource? = null
        private var isStem = false

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            if (scheme == StemPcmDataSource.SCHEME) {
                isStem = true
                val session = EdgeSeparationSession.get()
                val host = dataSpec.uri.host ?: "vocals"
                val picked = if (host == "accompaniment") session.accompBuffer else session.vocalsBuffer
                Log.i(StemPcmDataSource.TAG_STEM, "dispatch: STEM uri=${dataSpec.uri} host=$host " +
                    "buffer=${if (picked != null) "ok" else "NULL"} state=${session.state.value}")
                val buffer: StemPcmRingBuffer = picked
                    ?: throw IOException("no active edge buffer for host=$host")
                // 注意：合成头的 data size 用“本行开始位置处理”→ totalFrames 传全曲帧数；
                // dataSizeBytes() 会减去 open 的起始字节（已包含 startFrame 偏移）。
                val d = StemPcmDataSource(
                    buffer, session.sampleRate, session.channels, session.totalFrames,
                )
                delegate = d
                return d.open(dataSpec.buildUpon().setUri(Uri.parse("stem-local://$host")).build())
            }
            // ★诊断：非 stem scheme → 委派给 upstream（file/content）。
            //   若“普通文件没声音”，先看这一行是否存在、scheme 是否如预期。
            Log.i(StemPcmDataSource.TAG_STEM, "dispatch: PASS-THROUGH scheme=$scheme uri=${dataSpec.uri}")
            isStem = false
            val d = upstream.createDataSource()
            delegate = d
            return d.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }
}
