package pub.miku.neuralstem.spike

import android.util.Log
import java.util.concurrent.Executors

/**
 * 阶段 M4 / S3：Spleeter **4-stem 分离器**（整条管线）。
 *
 * ## 数据流
 * ```
 *  FrameSource（任意长度）
 *   → 逐「块」读入（每块 BLOCK_FRAMES 帧，块间留 STFT 上下文）
 *   → 块内：STFT → 幅度块 → [Spleeter4Estimator] → 4 估计
 *   → ratio mask + 带扩展 → iSTFT → 4 轨块
 *   → 4 × WavWriter 顺序落盘
 * ```
 *
 * ## 为什么分块（而非整曲一次）
 * 4 分钟整曲若一次做 STFT：`2ch × ~10350 帧 × 2049 bin × 2(复) × 4B ≈ 340MB` —— 必爆。
 * 分块后峰值与歌长**无关**（与 2-stem 的 [StreamingSeparator] 同理）。
 *
 * ## 分块与 STFT「接缝」
 * STFT 帧只需 `N_FFT` 样本上下文。本实现让相邻块**重叠 `N_FFT` 帧**，且每块只
 * **落盘其中「安全区」**（避开需要邻居上下文的边界帧），从而无需加权拼接。
 * 简化取舍：块内重算重叠区的 STFT 帧（比 cross-fade 简单，且 Spleeter 的
 * ratio mask 本身对上下文不敏感——桌面重建不变量已证）。
 *
 * ## 单线程调用约定
 * 估计器持有 native 会话，**非线程安全**；本类不做并发保护（与 [StreamingSeparator] 一致，
 * 由 Repository 保证串行）。
 */
class Spleeter4Separator(
    private val reader: FrameSource,
    /** 4 个 writer，顺序必须与 [Spleeter4Dsp.STEM_KINDS] 一致：vocals/drums/bass/other。 */
    private val writers: List<WavWriter>,
    private val estimator: Spleeter4Estimator,
    /**
     * 每块「净推进」帧数（默认 ≈ 10s @44.1k）。
     *
     * 内存：块内 STFT 缓冲 ≈ `2ch × (block+context) 帧 × 2049 × 2 × 4B`，
     * 10s 块（441000 帧）≈ 14.5MB，4 轨输出再 ≈ 14MB —— 安全。
     */
    private val blockFrames: Int = DEFAULT_BLOCK_FRAMES,
    private val onProgress: ((doneFrames: Int, totalFrames: Int) -> Unit)? = null,
) {
    init {
        require(writers.size == 4) { "需要 4 个 writer，当前 ${writers.size}" }
    }

    /** STFT 上下文（两侧各 N_FFT 帧），保证块边界处的帧有完整窗。 */
    private val context = Spleeter4Dsp.N_FFT

    data class Stats(
        val inputFrames: Int,
        val outputFrames: Int,
        val blocks: Int,
    )

    fun run(): Stats {
        val channels = reader.channels
        val sampleRate = reader.sampleRate
        require(channels in 1..2) { "only mono/stereo, got $channels" }
        val total = reader.totalFrames

        Log.i(
            TAG,
            "spleeter4: total=$total frames ch=$channels sr=$sampleRate " +
                "block=$blockFrames context=$context",
        )

        var start = 0
        var blocks = 0
        var written = 0
        while (start < total) {
            val core = minOf(blockFrames, total - start)          // 本块要输出的帧
            // 读取范围含上下文
            val readStart = (start - context).coerceAtLeast(0)
            val readEnd = (start + core + context).coerceAtMost(total)
            val want = readEnd - readStart
            val block = reader.readFrames(readStart, want)
            val gotFrames = block.size / channels
            if (gotFrames <= 0) break

            // 转平面（2 声道）
            val planar = planarize(block, gotFrames, channels)

            // 本块内：STFT → estimates → mask+iSTFT → 取 core 段
            val coreStartInBlock = start - readStart                // core 在块内偏移
            val stems = separateBlock(planar, gotFrames, coreStartInBlock, core)

            // 落盘（交织）
            for (i in 0 until 4) writers[i].append(stems[i])

            written += core
            blocks++
            start += core
            onProgress?.invoke(written, total)
            Log.i(TAG, "block#$blocks: core=$core done=$written/$total")
        }

        val stats = Stats(outputFrames = written, inputFrames = total, blocks = blocks)
        Log.i(TAG, "spleeter4 done: in=${stats.inputFrames} out=${stats.outputFrames} blocks=${stats.blocks}")
        return stats
    }

    /**
     * 对一个块做「STFT → 4 估计 → mask+iSTFT」，返回 4 条**交织**、长度 `core` 帧的轨。
     * 只输出块内 `[coreStartInBlock, coreStartInBlock+core)` 段（避开上下文边界）。
     */
    private fun separateBlock(
        planar: Array<FloatArray>,
        blockFrames: Int,
        coreStartInBlock: Int,
        core: Int,
    ): List<FloatArray> {
        val frames = Spleeter4Dsp.frameCount(blockFrames)
        val splits = Spleeter4Dsp.splitCount(frames)

        // 2 声道各自的复谱
        val specRe = arrayOfNulls<FloatArray>(2)
        val specIm = arrayOfNulls<FloatArray>(2)
        // 模型输入：2 声道 × [splits,512,1024]
        val perChBlock = Spleeter4Dsp.SPLIT_FRAMES * Spleeter4Dsp.MODEL_BINS
        val mag = FloatArray(2 * splits * perChBlock)
        for (c in 0 until 2) {
            val (re, im) = Spleeter4Dsp.stft(planar[c], frames)
            specRe[c] = re
            specIm[c] = im
            val mb = Spleeter4Dsp.magnitudeBlock(re, im, frames)
            // 拷到 2 声道布局：[c, splits, 512, 1024]
            System.arraycopy(mb, 0, mag, c * splits * perChBlock, mb.size)
        }

        val estimates = estimator.estimate(mag, splits)   // [4][2*splits*512*1024]
        val denom = Spleeter4Dsp.denominator(estimates)
        val denomPerCh = splits * perChBlock

        // 每个 stem 每声道做 mask+iSTFT，再取 core 段交织。
        // ★ 4 个 stem 互相独立（各自 estCh/denCh，写入各自 inter）→ 并行。
        val out: List<FloatArray> = List(4) { s ->
            esttPool.submit<FloatArray> {
                val estStem = estimates[s]
                val inter = FloatArray(core * 2)
                for (c in 0 until 2) {
                    val estCh = FloatArray(denomPerCh)
                    System.arraycopy(estStem, c * denomPerCh, estCh, 0, denomPerCh)
                    val denCh = FloatArray(denomPerCh)
                    System.arraycopy(denom, c * denomPerCh, denCh, 0, denomPerCh)
                    val time = Spleeter4Dsp.maskAndIstft(
                        estCh, denCh, specRe[c]!!, specIm[c]!!, frames, blockFrames,
                    )
                    for (i in 0 until core) {
                        inter[i * 2 + c] = time[coreStartInBlock + i]
                    }
                }
                inter
            }
        }.map { fut -> fut.get() }
        return out
    }

    private fun planarize(interleaved: FloatArray, frames: Int, channels: Int): Array<FloatArray> {
        val p = Array(2) { FloatArray(frames) }
        if (channels == 1) {
            for (i in 0 until frames) { p[0][i] = interleaved[i]; p[1][i] = interleaved[i] }
        } else {
            for (i in 0 until frames) {
                p[0][i] = interleaved[i * 2]
                p[1][i] = interleaved[i * 2 + 1]
            }
        }
        return p
    }

    companion object {
        private const val TAG = "Spleeter4Sep"

        /** ≈ 10s @44.1k。 */
        const val DEFAULT_BLOCK_FRAMES = 441_000

        /** 4 个 stem 的 iSTFT 并行池（进程级共享，避免每块重建）。 */
        private val esttPool = Executors.newFixedThreadPool(4)
    }
}
