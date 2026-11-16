package pub.miku.neuralstem.player.pcm

import java.io.Closeable

/**
 * 一份 PCM 的使用租约。
 *
 * 使用期间 store 不会 LRU 删除对应文件；必须 try/finally 或 use {} 关闭。
 */
class PcmLease internal constructor(
    val artifact: PcmArtifact,
    private val onClose: () -> Unit,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
