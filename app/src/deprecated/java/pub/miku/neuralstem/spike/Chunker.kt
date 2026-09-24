package pub.miku.neuralstem.spike

/**
 * 音频分块器(Task 0b Spike)。
 *
 * Spleeter TFLite 是动态 shape,只能喂小音频块(tinoucas 实践:2s 块 + 4096 样本
 * 交叉淡化,"sounds ok-ish")。本类把交织立体声 PCM 切成固定帧数的块,
 * 并在相邻块之间保留重叠区,供 SpleeterSeparator 推理后做 cross-fade 拼接,
 * 消除块边界的相位不连续/爆音。
 *
 * 对应 guide.md N2(内存安全:每次只有一块进推理,不整曲驻留)
 * 与 F3.1(输出连续无爆音的 stem)。
 */
class Chunker(
    private val channels: Int,
    /** 每块包含的帧数(立体声 = 2 倍样本)。默认 2s @44.1kHz */
    private val framesPerChunk: Int = DEFAULT_FRAMES_PER_CHUNK,
    /** 相邻块重叠的帧数,用于交叉淡化。默认 4096 帧 ≈ 93ms */
    private val overlapFrames: Int = DEFAULT_OVERLAP_FRAMES,
) {
    init {
        require(channels in 1..2) { "Only mono/stereo supported" }
        require(overlapFrames < framesPerChunk) { "Overlap must be smaller than chunk" }
    }

    /** 块与块的步进(帧)。 */
    val hopFrames: Int = framesPerChunk - overlapFrames

    data class Chunk(
        val samples: FloatArray,  // 交织 [frame * channels]
        val startFrame: Int,      // 在原始音频中的起始帧
        val isFirst: Boolean,
        val isLast: Boolean,
    )

    /** 全曲切成块序列(不复制重叠区之前的重复数据,每块独立可推理)。 */
    fun split(samples: FloatArray): List<Chunk> {
        val totalFrames = samples.size / channels
        if (totalFrames == 0) return emptyList()

        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < totalFrames) {
            val end = minOf(start + framesPerChunk, totalFrames)
            val len = (end - start) * channels
            val block = FloatArray(len)
            System.arraycopy(samples, start * channels, block, 0, len)
            chunks += Chunk(block, start, isFirst = start == 0, isLast = end >= totalFrames)
            if (end >= totalFrames) break
            start += hopFrames
        }
        return chunks
    }

    /**
     * 把各块推理输出(stem 波形,交织)用等功率交叉淡化拼回全曲。
     * 每块输出长度可能与输入块不同(模型内部可能改变长度),
     * 以每块自己的实际帧数 + chunk.startFrame 定位。
     *
     * @param chunkedOutputs 每个输入块对应的输出 stem 波形(交织),顺序与 split() 一致
     * @param totalFrames 原始音频总帧数
     */
    fun mergeCrossfade(chunkedOutputs: List<FloatArray>, totalFrames: Int): FloatArray {
        require(chunkedOutputs.isNotEmpty()) { "No chunks to merge" }
        val out = FloatArray(totalFrames * channels)
        val weight = FloatArray(totalFrames) // 每帧累计权重,最后归一化

        for ((index, chunkOut) in chunkedOutputs.withIndex()) {
            val chunk = splitCache[index] // 与 split() 时的 startFrame 对齐
            val outFrames = chunkOut.size / channels
            for (f in 0 until outFrames) {
                val global = chunk.startFrame + f
                if (global >= totalFrames) break
                // 线性 fade-in / fade-out 权重(等功率交叉淡化的简化版)
                var w = 1f
                if (!chunk.isFirst && f < overlapFrames) {
                    w = f.toFloat() / overlapFrames
                }
                val framesFromEnd = outFrames - f
                if (!chunk.isLast && framesFromEnd <= overlapFrames) {
                    w = (framesFromEnd - 1).toFloat() / overlapFrames
                }
                for (c in 0 until channels) {
                    out[global * channels + c] += chunkOut[f * channels + c] * w
                }
                weight[global] += w
            }
        }
        // 归一化:重叠区权重为 fade-in+fade-out 之和 ≈1,非重叠区 =1
        for (i in out.indices) {
            val w = weight[i / channels]
            if (w > 1e-6f) out[i] /= w
        }
        return out
    }

    // split() 时的块元数据缓存,供 mergeCrossfade 对齐 startFrame。
    // 简化设计:split 后立即推理再 merge,生命周期在一同一协程内。
    private var splitCache: List<Chunk> = emptyList()

    fun splitAndCache(samples: FloatArray): List<Chunk> {
        val chunks = split(samples)
        splitCache = chunks
        return chunks
    }

    companion object {
        /** 2 秒 @ 44100Hz(tinoucas 验证过的块长) */
        const val DEFAULT_FRAMES_PER_CHUNK = 88200
        /** 4096 帧重叠 ≈ 93ms(tinoucas 验证过的交叉淡化长度) */
        const val DEFAULT_OVERLAP_FRAMES = 4096
    }
}
