package pub.miku.neuralstem.player

import java.nio.ByteBuffer

/** v0.3.0：JVM 测试用输出层。可注入写失败、记录写入/释放次数。 */
class FakeAudioSink(
    override val frameCapacity: Int = 1024,
    private val sessionId: Int = 42,
) : AudioSink {
    override val audioSessionId: Int get() = sessionId

    val writesF = mutableListOf<Int>()
    val writesB = mutableListOf<Int>()
    var failNextWrites = 0
    var releaseCount = 0
    var playCount = 0
    var flushCount = 0
    var pauseCount = 0

    override fun play() { playCount++ }
    override fun pause() { pauseCount++ }
    override fun flush() { flushCount++ }
    override fun release() { releaseCount++ }

    override fun write(buf: FloatArray, len: Int): Int {
        if (failNextWrites > 0) { failNextWrites--; return -1 }
        writesF += len
        return len
    }

    override fun write(buf: ByteBuffer, len: Int): Int {
        if (failNextWrites > 0) { failNextWrites--; return -1 }
        writesB += len
        return len
    }
}
