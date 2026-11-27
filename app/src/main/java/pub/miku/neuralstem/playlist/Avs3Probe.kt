package pub.miku.neuralstem.playlist

/**
 * 检测 MP4 头部是否含 **AVS3（`av3a`）空间音频** codec tag。
 *
 * ## 为什么需要
 * 公开版没有 AVS3 解码器（`Avs3Bridge.available == false`）：这类文件能通过扩展名
 * 检查（`.m4a`），但播放时必然失败。导入阶段直接拒绝，比加入歌单后再「无法播放」更清楚。
 *
 * ## 检测方式
 * MP4 的 codec tag 在 `stsd` box 的 entry fourcc 里：
 * `stsd` + size(4) + type(4) + version/flags(4) + entry_count(4) → entry(size + fourcc)。
 * 优先在 `stsd` 后 1KB 内找 `av3a`；找不到 `stsd`（异常/截断）时退回全量搜索。
 */
object Avs3Probe {

    private val STSD = "stsd".toByteArray(Charsets.US_ASCII)
    private val AV3A = "av3a".toByteArray(Charsets.US_ASCII)

    /** @param head 文件头部字节（`MediaSourceReader.readHead`）。 */
    fun hasAvs3Codec(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val stsd = indexOf(head, STSD, 0, head.size)
        if (stsd >= 0) {
            // stsd box 头 8 字节 + version/flags 4 + entry_count 4 → entry 从 +16 开始
            val start = (stsd + 16).coerceAtMost(head.size)
            val end = (stsd + 16 + 1024).coerceAtMost(head.size)
            if (start < end && indexOf(head, AV3A, start, end) >= 0) return true
        }
        // 兜底：moov 结构异常或超出窗口时全量搜索（m4a 头部通常很小，误判概率低）
        return indexOf(head, AV3A, 0, head.size) >= 0
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty() || to - from < needle.size) return -1
        val last = to - needle.size
        var i = from
        while (i <= last) {
            var match = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
