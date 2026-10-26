package pub.miku.neuralstem.experimental.ncm

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlin.math.min

/**
 * ★把 **AVS3 解码后的立体声 PCM** 伪装成一个 WAV 流，交给 ExoPlayer 播放。
 *
 * ## 为什么走 WAV 伪装这条路
 * AVS3 (`av3a`) 是 Android 完全不认识的编码，`MediaExtractor` 连音轨都找不到
 * （`codec=av3a` → 无 audio track）。但我们已经有能把它解成 PCM 的解码器了
 * （见 [Avs3AudioDecoder]）。
 *
 * 于是问题从「让 ExoPlayer 支持一种新编码」降级成「让 ExoPlayer 播一段现成 PCM」——
 * 后者有现成解法：**在数据最前面合成一个 44 字节的 WAV 头**，
 * 交给 `WavExtractor` 解析，后面就是裸 PCM。
 *
 * 这条路和「边分离边播」的 `StemPcmDataSource` 是同一套思路，
 * 好处是**完全复用现有播放链路**：MediaSessionService、通知栏、后台播放、
 * 变速变调（Sonic）、EQ —— 全都不用改。
 *
 * ## 与 StemPcmDataSource 的关键差别
 *
 * | 点 | StemPcmDataSource | 本类 |
 * |---|---|---|
 * | 数据来源 | 仍在增长的内存环形缓冲（流式） | **一次性解好的完整 PCM** |
 * | 数据不足 | 阻塞等待 / STARVED | 不存在（数据一开始就齐） |
 * | 解码时机 | 与播放并行 | **播放前**一次性解完 |
 *
 * ## 为什么不做成流式
 * AVS3 一帧约 2408~2422 字节压缩数据 → 解出 24576 字节 PCM（12ch×1024×2）。
 * 整首 5576 帧共 137MB，实测解码只要 18 秒（RTF 7×）。
 * 一次性解完再播，实现简单、绝不断流。
 *
 * 代价是首播前有十几秒等待 —— 这个必须在 UI 上显示进度，不能白屏。
 *
 * ⚠️ 但 137MB 是 **12 声道**的体积；下混成立体声后只有约 **23MB**。
 * 所以真正的解码流程是「边解边下混」，只保留立体声结果，
 * 内存峰值 ≈ 23MB 而不是 137MB。见 `Avs3PcmDecoder`。
 */
@UnstableApi
class Avs3PcmDataSource internal constructor(
    private val pcm: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
) : BaseDataSource(/* isNetwork= */ false) {

    /** 每帧（采样点）字节数：PCM16 × 声道数。 */
    private val frameBytes: Int = channels * 2

    /** 当前读到的绝对字节位置（含 WAV 头）。 */
    private var position: Long = 0

    /** 本次 open 之后还剩多少字节（含头）。`C.LENGTH_UNSET` = 未知。 */
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        position = 0
        if (dataSpec.position > 0) position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            (WAV_HEADER_SIZE + pcm.size - position).coerceAtLeast(0)
        } else {
            dataSpec.length
        }
        transferStarted(dataSpec)
        Log.i(
            TAG,
            "open: pos=${dataSpec.position} len=${dataSpec.length} " +
                "pcm=${pcm.size}B ${channels}ch@${sampleRate}Hz → remaining=$bytesRemaining",
        )
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // 1) 先吐 WAV 头（仅当 position 落在头部区间）。
        if (position < WAV_HEADER_SIZE) {
            val hdr = wavHeader()
            val from = position.toInt()
            val n = min((WAV_HEADER_SIZE - position).toInt(), length)
            System.arraycopy(hdr, from, buffer, offset, n)
            position += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
            bytesTransferred(n)
            return n
        }

        // 2) 再吐 PCM 数据。
        val pcmPos = position - WAV_HEADER_SIZE
        if (pcmPos >= pcm.size) return C.RESULT_END_OF_INPUT

        var n = min((pcm.size - pcmPos).toInt(), length)
        // ★对齐到帧边界：WavExtractor 按块对齐读，给半个采样会算出错误的帧数。
        if (n > frameBytes) n -= n % frameBytes
        if (n <= 0) return C.RESULT_END_OF_INPUT

        System.arraycopy(pcm, pcmPos.toInt(), buffer, offset, n)
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri = Uri.parse("$SCHEME://avs3_${channels}ch_$sampleRate")

    override fun close() {
        transferEnded()
        position = 0
    }

    /*
     * ★注意：不要 override addTransferListener。
     * `BaseDataSource.addTransferListener` 是 **final** 的，而且它已经会
     * 把 listener 放进内部列表并自动派发 transferStarted/bytesTransferred/transferEnded
     * —— 这些调用已经写在 open/read/close 里了，不需要额外处理。
     */

    /**
     * 合成 44 字节标准 PCM WAV 头。
     *
     * data 大小填**整段 PCM 的完整长度**（不是剩余长度）——
     * 这样 ExoPlayer 算出的总时长才是整首，seek 不会因为
     * "文件比目标位置短" 而被 clamp 或判 EOF。
     */
    private fun wavHeader(): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val dataSize = pcm.size
        val h = ByteArray(WAV_HEADER_SIZE)
        var p = 0

        fun ascii(s: String) {
            for (ch in s) h[p++] = ch.code.toByte()
        }

        fun le32(v: Int) {
            h[p++] = (v and 0xFF).toByte()
            h[p++] = ((v shr 8) and 0xFF).toByte()
            h[p++] = ((v shr 16) and 0xFF).toByte()
            h[p++] = ((v shr 24) and 0xFF).toByte()
        }

        fun le16(v: Int) {
            h[p++] = (v and 0xFF).toByte()
            h[p++] = ((v shr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)          // PCM 格式块大小
        le16(1)           // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(16)          // 位深
        ascii("data")
        le32(dataSize)
        return h
    }

    /** Factory：给 `DefaultMediaSourceFactory` 用。 */
    class Factory(
        private val pcm: ByteArray,
        private val sampleRate: Int,
        private val channels: Int,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            Avs3PcmDataSource(pcm, sampleRate, channels)
    }

    companion object {
        private const val TAG = "Avs3PcmSrc"

        const val WAV_HEADER_SIZE = 44

        /** 伪 scheme，与 `ncm://` / `stem://` 并列，由 ResolvingDataSource 分发。 */
        const val SCHEME = "avs3pcm"
    }
}
