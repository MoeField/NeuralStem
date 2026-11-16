package pub.miku.neuralstem.player.pcm

import java.io.File

/** 已完整落盘、可安全复用的 PCM WAV。 */
data class PcmArtifact(
    val file: File,
    val sizeBytes: Long,
    val createdAtMs: Long,
    /** 由解码 pipeline 填充；Phase 1 可先为 0。 */
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val totalFrames: Long = 0L,
)
