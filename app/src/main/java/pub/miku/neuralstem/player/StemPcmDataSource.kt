package pub.miku.neuralstem.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
class StemPcmDataSource private constructor(
    private val buffer: StemPcmRingBuffer,
    private val sampleRate: Int,
    private val channels: Int,
) : DataSource {

    /** 每帧字节数（PCM16 × channels）。 */
    private val frameBytes: Int = channels * 2

    private var cursor: StemPcmRingBuffer.Cursor? = null
    private var opened = false
    /** 当前绝对字节位置（含 WAV 头）。 */
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val c = buffer.openCursor(0)
        cursor = c
        position = 0
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
        if (framesRead < 0) return C.RESULT_END_OF_INPUT  // EOF
        if (framesRead == 0) return 0                      // 暂无数据（上层重试）
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

    private fun wavHeader(): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val h = ByteArray(WAV_HEADER_SIZE)
        var p = 0
        fun ascii(s: String) { for (ch in s) h[p++] = ch.code.toByte() }
        fun le32(v: Int) { h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte(); h[p++] = ((v shr 16) and 0xFF).toByte(); h[p++] = ((v shr 24) and 0xFF).toByte() }
        fun le16(v: Int) { h[p++] = (v and 0xFF).toByte(); h[p++] = ((v shr 8) and 0xFF).toByte() }
        ascii("RIFF"); le32(-1)                        // RIFF size = 未知（0xFFFFFFFF 占位）
        ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(blockAlign); le16(16)
        ascii("data"); le32(-1)                        // data size = 未知
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
        const val WAV_HEADER_SIZE = 44
        /** 伪 scheme：`stem://vocals` / `stem://accompaniment`。 */
        const val SCHEME = "stem"

        /**
         * ★G-3：路由式 DataSource —— 每次 [open] 实时取会话当前 buffer。
         * 这是 `PlaybackService.singlePlayer` 全程安装的那个（既播文件也播分离流）。
         */
        @UnstableApi
        fun createRouter(): DataSource = RouterDataSource()
    }

    /**
     * 路由数据源：依据 DataSpec.uri 的 host（`vocals`/`accompaniment`）从
     * [EdgeSeparationSession] 取当前 buffer，委派给 [StemPcmDataSource]。
     * 若尚无会话/无 buffer → 抛 IOException（播放器会重试/报错，上层可回退原曲）。
     */
    @UnstableApi
    private class RouterDataSource : DataSource {
        private var delegate: StemPcmDataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            val session = EdgeSeparationSession.get()
            val host = dataSpec.uri.host ?: ""
            val buffer = when (host) {
                "accompaniment" -> session.accompBuffer
                else -> session.vocalsBuffer   // 默认 vocals
            } ?: throw IOException("no active edge separation buffer for host=$host")
            val d = StemPcmDataSource(buffer, session.sampleRate, session.channels)
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

/**
 * ★G-3：按 uri scheme 分发的 DataSource 工厂。
 *
 * `PlaybackService.singlePlayer` 在**构建时**就装它 → 同一个 player 既能播文件（原曲/旧多轨），
 * 又能播分离流（`stem://…`）；无需运行时更换 MediaSourceFactory（ExoPlayer 实例不支持）。
 *
 * 每次 [createDataSource]（即每次 open）都**实时**从 [EdgeSeparationSession] 取当前 buffers，
 * 所以会话重建（新一轮边分离边播）后无需重建 player。
 */
@UnstableApi
class StemStreamFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = StemPcmDataSource.createRouter()
}
