package pub.miku.neuralstem.spike

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ★G-3：单写多读的 PCM 环形缓冲（交织 float，单 stem 一份）。
 *
 * ## 为什么需要
 * “边分离边播”要求播放器能从**仍在产出**的分离结果里读。文件不行（WAV 头 size 未定），
 * 所以分离输出写进本缓冲，由 `pub.miku.neuralstem.player.StemPcmDataSource` 读取。
 *
 * ## 语义
 * - **单写**：分离线程调 [append]；缓冲满 → **阻塞**（背压，避免内存无限增长）。
 * - **多读**：每个读取方持有自己的 [Cursor]（打开时从当前写位置或指定 offset 开始）。
 *   `read` 数据不足 → **阻塞等待**（有超时，供 Media3 loader 使用）；分离结束/取消 → [close] 唤醒并返回 EOF。
 * - **总量有界**：容量固定（帧数），超出则写端等待消费；这样内存与曲长无关（对齐 N2）。
 *
 * ## offset 语义
 * 对外用**绝对帧号**（相对本 stem 的第 0 帧）。
 *
 * ⚠️ MVP 取舍：**不回收**已读数据（简单、正确）；容量按“分离可领先播放的上限”给足（默认 60s）。
 * 若未来要支持“分离远超播放”，再实现回收。
 */
class StemPcmRingBuffer(
    val channels: Int,
    /** 容量（帧）。默认 60s @44.1k。 */
    capacityFrames: Int = DEFAULT_CAPACITY_FRAMES,
) {
    init {
        require(channels in 1..2) { "channels must be 1..2, got $channels" }
        require(capacityFrames > 0)
    }

    private val lock = ReentrantLock()
    /** 可读=已写；等待=数据到来或关闭。 */
    private val dataAvailable = lock.newCondition()
    /** 可写=有空位；等待=消费或关闭。 */
    private val spaceAvailable = lock.newCondition()

    private val capFrames = capacityFrames
    private val buf = FloatArray(capFrames * channels)

    /** 绝对写帧号（已写入总帧数）。 */
    private var writeFrames = 0L
    /** 已被丢弃的最旧绝对帧号（MVP 恒 0）。 */
    private var headFrames = 0L
    /** 是否已关闭（不再有新数据；读端读到末尾返回 EOF）。 */
    private var closed = false

    /** 已写入总帧数（= 可读的最大绝对帧上界）。 */
    val totalWrittenFrames: Long get() = lock.withLock { writeFrames }
    val isClosed: Boolean get() = lock.withLock { closed }

    /** 已产出时长（ms）。 */
    fun durationMs(sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else totalWrittenFrames * 1000L / sampleRate

    /**
     * 追加 [samples]（交织 float）。**背压**：缓冲满则阻塞直到有空位或关闭。
     */
    fun append(samples: FloatArray, startFrame: Int = 0, frames: Int = samples.size / channels) {
        if (frames <= 0) return
        require((startFrame + frames) * channels <= samples.size) { "append 越界" }
        var srcFrame = 0
        while (srcFrame < frames) {
            lock.withLock {
                if (closed) return
                val used = (writeFrames - headFrames).toInt()
                val free = capFrames - used
                if (free <= 0) {
                    try {
                        spaceAvailable.awaitNanos(SPACE_WAIT_NS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    return@withLock
                }
                val n = minOf(free, frames - srcFrame)
                var written = 0
                while (written < n) {
                    val absFrame = writeFrames + written
                    val ringIdx = (absFrame % capFrames).toInt()
                    val run = minOf(n - written, capFrames - ringIdx)
                    System.arraycopy(
                        samples,
                        (startFrame + srcFrame + written) * channels,
                        buf,
                        ringIdx * channels,
                        run * channels,
                    )
                    written += run
                }
                writeFrames += n
                srcFrame += n
                dataAvailable.signalAll()
            }
        }
    }

    /** 标记结束：不再有数据，唤醒所有读端（读到末尾返回 EOF）。 */
    fun close() {
        lock.withLock {
            closed = true
            dataAvailable.signalAll()
            spaceAvailable.signalAll()
        }
    }

    /** 活跃游标集合（用于回收已读空间）。由 lock 保护。 */
    private val cursors = mutableSetOf<Cursor>()

    /**
     * 回收“所有游标都已读过”的旧数据：把 [headFrames] 推进到最慢活游标的 position。
     * 无活游标 → 可推进到 writeFrames（全部可回收）。须在持锁时调。
     */
    private fun reclaimLocked() {
        val minPos = if (cursors.isEmpty()) writeFrames else cursors.minOf { it.position }
        if (minPos > headFrames) headFrames = minOf(minPos, writeFrames)
    }

    /** 新建一个读取游标。[startFrame] = 起始绝对帧（0 = 从头）。 */
    fun openCursor(startFrame: Long = 0): Cursor = lock.withLock {
        Cursor(startFrame).also { cursors.add(it) }
    }

    /** 关闭一个游标（读完/不再需要时调），使其不再阻止回收。 */
    fun closeCursor(cursor: Cursor) {
        lock.withLock {
            cursors.remove(cursor)
            reclaimLocked()
            spaceAvailable.signalAll()
        }
    }

    /**
     * 一个读取游标。非线程安全（每个读端独占一个）。
     * 读语义：数据不足则阻塞等待（带超时）；关闭且读完 → 返回 -1（EOF）。
     */
    inner class Cursor(startFrame: Long) {
        /** 下一个要读的绝对帧号。 */
        @Volatile
        var position: Long = startFrame.coerceAtLeast(0)
            private set

        /**
         * 读最多 [frames] 帧到 [dst]（交织）。
         * @return 实际读到的帧数；-1 = EOF（已关闭且无更多数据）。
         */
        fun read(dst: FloatArray, offsetFrames: Int = 0, frames: Int = dst.size / channels): Int {
            while (true) {
                var retry = false
                val result = lock.withLock {
                    // 若本游标落后于“已被回收/可回收”的 head，则 seek 到最旧可得处
                    // （该段数据已被写端回收；上层需容忍此 clamp）。
                    if (position < headFrames) position = headFrames
                    val avail = writeFrames - position
                    if (avail <= 0) {
                        if (closed) return -1
                        try {
                            dataAvailable.awaitNanos(READ_WAIT_NS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return 0
                        }
                        retry = true
                        0
                    } else {
                        val n = minOf(avail, frames.toLong()).toInt()
                        var read = 0
                        while (read < n) {
                            val absFrame = position + read
                            val ringIdx = (absFrame % capFrames).toInt()
                            val run = minOf(n - read, capFrames - ringIdx)
                            System.arraycopy(
                                buf,
                                ringIdx * channels,
                                dst,
                                (offsetFrames + read) * channels,
                                run * channels,
                            )
                            read += run
                        }
                        position += n
                        // 读完即回收“所有游标都已读过”的旧数据 → 腾出写端空间。
                        reclaimLocked()
                        spaceAvailable.signalAll()
                        n
                    }
                }
                if (!retry) return result
            }
        }

        /** 关闭本游标（读完后调）：不再阻止旧数据回收。幂等。 */
        fun close() {
            closeCursor(this)
        }

        /** 跳到绝对帧 [frame]（用于 seek）。不能倒退到已回收区之前。 */
        fun seekTo(frame: Long) {
            lock.withLock {
                position = frame.coerceAtLeast(headFrames)
                reclaimLocked()
                spaceAvailable.signalAll()
            }
        }
    }

    companion object {
        /** 60s @44.1k —— 覆盖“分离最多领先播放 60s”的常规场景。 */
        const val DEFAULT_CAPACITY_FRAMES = 44_100 * 60
        private const val SPACE_WAIT_NS = 200_000_000L   // 写端满等待 200ms/次
        private const val READ_WAIT_NS = 2_000_000_000L  // 读端等数据 2s/次（交给上层重试）
    }
}
