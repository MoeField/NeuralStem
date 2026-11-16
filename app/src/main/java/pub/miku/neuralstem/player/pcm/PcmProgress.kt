package pub.miku.neuralstem.player.pcm

import pub.miku.neuralstem.player.WaveformData

/** 供波形/分离页显示“预解码中”的进度；不承载 PCM 数据。 */
data class PcmProgress(
    val phase: Phase,
    val bytesWritten: Long = 0L,
    val message: String? = null,
    /** 解码中的渐进包络；Ready 前可能一直为空。 */
    val envelope: WaveformData? = null,
) {
    enum class Phase {
        DECODING,
        READY,
        FAILED,
    }
}
