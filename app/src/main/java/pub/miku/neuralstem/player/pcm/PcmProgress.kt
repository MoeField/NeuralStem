package pub.miku.neuralstem.player.pcm

/** 供波形/分离页显示“预解码中”的进度；不承载 PCM 数据。 */
data class PcmProgress(
    val phase: Phase,
    val bytesWritten: Long = 0L,
    val message: String? = null,
) {
    enum class Phase {
        DECODING,
        READY,
        FAILED,
    }
}
