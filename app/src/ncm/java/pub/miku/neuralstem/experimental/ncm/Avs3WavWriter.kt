package pub.miku.neuralstem.experimental.ncm

import java.io.File
import java.io.OutputStream

/**
 * ★PCM16 → WAV 字节的小工具。
 *
 * ## 为什么单独抽出来
 * 同一段「44 字节头 + 裸 PCM」的拼接逻辑，现在有三处要用：
 * 1. 写磁盘缓存（[Avs3PcmCache]）
 * 2. devtools 导出 wav 供 Mac 侧听感验证
 * 3. 将来可能的「导出到音乐目录」
 *
 * 拼一块就必须正确一块 —— WAV 头算错 1 个字节，`WavExtractor` 就会
 * 少读或多读一帧，表现为结尾爆音或时长差几毫秒。
 *
 * ## 注意
 * 这里只支持 **PCM16 小端**（本项目解码产物就是这种）。
 * 不支持的格式请用别的库，不要在这里加分支。
 */
object Avs3WavWriter {

    /** WAV 头固定长度（PCM，无扩展块）。 */
    const val HEADER_SIZE = 44

    /**
     * 把交错 PCM16 拼成完整 WAV 字节。
     *
     * @param pcm 交错 PCM16 小端
     * @param sampleRate 采样率（本项目实测 44100）
     * @param channels 声道数（下混后是 2）
     */
    fun toWavBytes(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE + pcm.size)
        writeHeader(out, pcm.size, sampleRate, channels)
        System.arraycopy(pcm, 0, out, HEADER_SIZE, pcm.size)
        return out
    }

    /** 直接写文件（流式，避免大数组再复制一次）。 */
    fun writeTo(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        file.outputStream().buffered().use { writeTo(it, pcm, sampleRate, channels) }
    }

    fun writeTo(out: OutputStream, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val hdr = ByteArray(HEADER_SIZE)
        writeHeader(hdr, pcm.size, sampleRate, channels)
        out.write(hdr)
        out.write(pcm)
    }

    /**
     * 往 [dst] 的前 44 字节写 WAV 头。
     *
     * 列一下每个字段，避免以后改动时算错：
     * ```
     * offset size 内容
     *  0     4    "RIFF"
     *  4     4    36 + dataSize       ← 整个文件长度 - 8
     *  8     4    "WAVE"
     * 12     4    "fmt "
     * 16     4    16                 ← PCM 格式块固定 16
     * 20     2    1                  ← 1 = PCM（未压缩）
     * 22     2    channels
     * 24     4    sampleRate
     * 28     4    byteRate = sampleRate * channels * 2
     * 32     2    blockAlign = channels * 2
     * 34     2    16                 ← 位深
     * 36     4    "data"
     * 40     4    dataSize
     * ```
     */
    private fun writeHeader(dst: ByteArray, dataSize: Int, sampleRate: Int, channels: Int) {
        require(dst.size >= HEADER_SIZE) { "缓冲不足 44 字节" }
        var p = 0

        fun ascii(s: String) {
            for (c in s) dst[p++] = c.code.toByte()
        }

        fun le32(v: Int) {
            dst[p++] = (v and 0xFF).toByte()
            dst[p++] = ((v shr 8) and 0xFF).toByte()
            dst[p++] = ((v shr 16) and 0xFF).toByte()
            dst[p++] = ((v shr 24) and 0xFF).toByte()
        }

        fun le16(v: Int) {
            dst[p++] = (v and 0xFF).toByte()
            dst[p++] = ((v shr 8) and 0xFF).toByte()
        }

        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(16)
        ascii("data")
        le32(dataSize)
    }
}
