package pub.miku.neuralstem.player

import pub.miku.neuralstem.spike.StemPcmRingBuffer
import pub.miku.neuralstem.spike.WavReader
import java.io.Closeable
import java.io.File

/**
 * ★「一条 stem 的 PCM 来源」抽象（2026-11-01 多轨重构）。
 *
 * ## 为什么需要
 * 多轨播放改成「**单个 AudioTrack + 自研混音**」后，混音器要按**统一采样计数**
 * 从每条 stem 随机读 PCM。但 stem 有两种存在形式：
 *
 * | 场景 | 来源 | 特点 |
 * |---|---|---|
 * | 分离完成 | 磁盘上的 `vocals.wav` / `accompaniment.wav` | 整曲固定，可随机读 |
 * | 边分离边播 | [StemPcmRingBuffer]（分离线程不断 append） | 读到未分离区会**阻塞等** |
 *
 * 收敛到同一接口，混音器只写一份。
 *
 * ## 约定
 * - 所有实现必须 `sampleRate` / `channels` 一致（混音器不做重采样）。
 * - [read] 返回**交织** float（与 AudioTrack 写入格式一致）。
 * - 返回**实际帧数**（可能小于请求）；0 = 暂时无数据 / EOF。
 */
interface StemSource : Closeable {
    val channels: Int
    val sampleRate: Int

    /** 总帧数（边播模式下会增长，调用方不要长期缓存）。 */
    val totalFrames: Long

    /**
     * 是否已**确定**不会再有新数据（2026-11-06）。
     *
     * 混音器要区分两种“读到 0”：
     * - `isFinished == true` 且已到总帧数 → 真的曲终，停渲染线程；
     * - `isFinished == false`（边播中）→ 只是暂时没数据，**不能停**，交给 Gate 暂停。
     *
     * 文件源恒为 true；环缓冲要等分离结束/取消（`buffer.close()`）才为 true。
     */
    val isFinished: Boolean get() = true

    /**
     * 从绝对帧 [framePos] 起读 [frames] 帧，写进 [out]（交织）。
     *
     * @param out 长度需 >= `frames * channels`
     * @return 实际读到的帧数；0 = 无数据/EOF
     */
    fun read(framePos: Long, out: FloatArray, frames: Int): Int
}

/**
 * 磁盘 WAV 版（分离完成后使用）：包 [WavReader] 的随机读。
 */
class FileStemSource(file: File) : StemSource {
    private val reader = WavReader(file)
    override val channels: Int = reader.channels
    override val sampleRate: Int = reader.sampleRate
    override val totalFrames: Long = reader.totalFrames.toLong()

    override fun read(framePos: Long, out: FloatArray, frames: Int): Int {
        if (framePos >= totalFrames) return 0
        val want = minOf(frames.toLong(), totalFrames - framePos).toInt()
        if (want <= 0) return 0
        val block = reader.readFrames(framePos.toInt(), want)
        val n = block.size / channels
        if (n <= 0) return 0
        System.arraycopy(block, 0, out, 0, n * channels)
        return n
    }

    override fun close() {
        runCatching { reader.close() }
    }
}

/**
 * 边分离边播版：包 [StemPcmRingBuffer]。
 *
 * ## 与文件版的关键区别
 * - 数据在**增长**：暂时没数据时 cursor 内部会等（带超时），返回 0 表示“稍后再试”
 * - ring 里第 0 帧对应原曲绝对帧 [ringStartFrame]（分离从播放位置起）
 * - cursor **不能倒退到已回收区之前**（seek 太靠前会被 clamp 到 headFrames）
 */
class RingBufferStemSource(
    private val buffer: StemPcmRingBuffer,
    override val sampleRate: Int,
    private val ringStartFrame: Long = 0L,
) : StemSource {

    override val channels: Int = buffer.channels

    override val totalFrames: Long
        get() = ringStartFrame + buffer.totalWrittenFrames

    /** 分离结束/取消（buffer.close）后才是真的“不会再有数据”。 */
    override val isFinished: Boolean get() = buffer.isClosed

    private var cursor: StemPcmRingBuffer.Cursor? = null

    /**
     * ★2026-11-06：改用**非阻塞** `tryRead`。
     *
     * 本类只被 `StemMixer` 的渲染线程使用（见 MultiTrackController.loadStream）。
     * 旧实现用阻塞版 `Cursor.read`，分离跟不上时音频线程会 `awaitNanos(2s)`
     * → 整个混音停摆，表现为“位置冻结 + 没声”，Gate 也无法介入。
     */
    override fun read(framePos: Long, out: FloatArray, frames: Int): Int {
        val rel = framePos - ringStartFrame
        if (rel < 0) return 0          // 分离起点之前没有数据
        val c = cursor ?: buffer.openCursor(rel).also { cursor = it }
        // cursor 只顺序前进；位置不符就 seek（回退可能被 clamp，属预期）
        if (c.position != rel) runCatching { c.seekTo(rel) }
        val n = runCatching { c.tryRead(out, 0, frames) }.getOrDefault(0)
        return if (n < 0) 0 else n     // -1 = EOF；对混音器而言与“暂无数据”同义
    }

    override fun close() {
        runCatching { cursor?.close() }
        cursor = null
    }
}
