package pub.miku.neuralstem.spike

import android.util.Log
import java.util.Arrays
import java.util.concurrent.Executors

/**
 * 阶段 M4 / S2 落地：Demucs（htdemucs）**4 源分离器**。
 *
 * ## 数据流
 * ```
 *  FrameSource → 分块（窗 343980，overlap 25%）
 *   → 块内：STFT(波形) → [DemucsEstimator]
 *   → 每源：iSTFT(output) + add_67 → 4 条时域
 *   → 三角窗 overlap-add → 4 × WavWriter
 * ```
 *
 * ## 关键约定（桌面已验证，见 .memory.md §M4-S2）
 * - **窗固定 343980 样本**（模型硬约束，7.8s @44.1k）
 * - **overlap 25%**（实测 NCC vs 50% = 0.9966；Demucs 对窗口边缘不敏感）
 * - **最后一块向前对齐到末尾**（不是补零）
 * - **正确性判据用 NCC，不是能量守恒**（htdemucs 不含 mask-和为-1 约束，
 *   `sum(4)−mix ≈ −31 dB` 是正常水平）
 *
 * ## 内存
 * 每块：STFT 缓冲 `2ch×336×2048×2×4B ≈ 11MB` + 模型输出 `44MB(频谱) + 11MB(时域)`
 * + 4 源输出 ≈ 22MB → 峰值与歌长**无关**（已实测 40s→120s PSS 不变）。
 */
class DemucsSeparator(
    private val reader: FrameSource,
    /** 4 个 writer，顺序 = [STEM_KINDS]。 */
    private val writers: List<WavWriter>,
    private val estimator: DemucsEstimator,
    /** overlap 帧数，默认窗的 25%。 */
    private val overlap: Int = DEFAULT_OVERLAP,
    private val onProgress: ((doneFrames: Int, totalFrames: Int) -> Unit)? = null,
) {
    init {
        require(writers.size == 4) { "需要 4 个 writer，当前 ${writers.size}" }
        require(overlap >= 0 && overlap < WAVE / 2) { "非法 overlap=$overlap" }
    }

    data class Stats(val inputFrames: Int, val outputFrames: Int, val blocks: Int)

    fun run(): Stats {
        val channels = reader.channels
        val total = reader.totalFrames
        require(channels in 1..2) { "only mono/stereo, got $channels" }
        val step = WAVE - overlap
        Log.i(TAG, "demucs: total=$total ch=$channels win=$WAVE overlap=$overlap step=$step")

        // ★ OOM 修复：不再分配 size=total 的 acc（完整歌 4×2×total Float = 几 GB/百 MB）。
        //   改为**滑动窗口**：块 b 写 [sB, sB+WAVE)；下一块写 [sB+step, ...)。
        //   处理完块 b 后，[sB, sB+step) 区域**不会再被后续块触碰** → 可立即归一化 + 落盘 + 丢弃。
        //   缓冲只需覆盖 [sB, sB+WAVE+step) → W = WAVE + step。内存 O(WAVE)，与歌长无关。
        val W = WAVE + step
        // acc[src][c][r] where r = 绝对位置 - base（base = 当前块 start）。
        val acc = Array(4) { Array(2) { FloatArray(W) } }
        val wsum = FloatArray(W)

        var b = 0
        var s = 0
        var tModel = 0L; var tStft = 0L; var tIstft = 0L; var tRead = 0L
        // iSTFT 并行池（4 个 stem 并行）。用完即关。
        val isttfPool = Executors.newFixedThreadPool(4)
        // 落盘用交织缓冲（每次 flush 的区域）
        val outInter = Array(4) { FloatArray(step * 2) }
        try {
            while (s < total || (total < WAVE && b == 0)) {
                var start = s
                if (start + WAVE > total) start = (total - WAVE).coerceAtLeast(0)   // 末块向前对齐
                // 注：末块“向前对齐”时 start 可能小于上一块的 s+step → 需保证 base 连续性。
                // 下方 flush 按 start 推进，末块不会产生新的 final 区域（见循环末判断）。
                val base = s
                val tr = System.currentTimeMillis()
                val block = readBlock(start, channels)
                tRead += System.currentTimeMillis() - tr

                val planar = arrayOf(block[0], block[1])
                val ts = System.currentTimeMillis()
                val spec = DemucsStft.stftPacked(planar, WAVE)          // [1,4,2048,336]
                tStft += System.currentTimeMillis() - ts
                val flatWave = FloatArray(2 * WAVE)
                System.arraycopy(planar[0], 0, flatWave, 0, WAVE)
                System.arraycopy(planar[1], 0, flatWave, WAVE, WAVE)

                val tm = System.currentTimeMillis()
                val out = estimator.estimate(flatWave, spec)
                tModel += System.currentTimeMillis() - tm
                val frames = DemucsStft.frameCount(WAVE)
                val perStemSpec = 4 * DemucsStft.BINS * frames
                val stemStride = 2 * WAVE

                // 把本块结果按“相对 base”的偏移写入滑动窗口 acc。
                val relBase = start - base   // 通常 0；末块对齐时可能 < 0（则丢弃超前的部分）
                val ti = System.currentTimeMillis()
                val tasks = (0 until 4).map { src -> isttfPool.submit {
                    val specStem = FloatArray(perStemSpec)
                    System.arraycopy(out.spec, src * perStemSpec, specStem, 0, perStemSpec)
                    val time = DemucsStft.istftPacked(specStem, frames, WAVE)
                    val addBase = src * stemStride
                    for (c in 0 until 2) {
                        val a = acc[src][c]
                        val addOff = addBase + c * WAVE
                        for (i in 0 until WAVE) {
                            val r = relBase + i
                            if (r < 0 || r >= W) continue
                            val abs = base + r
                            if (abs >= total) break
                            a[r] += (time[c][i] + out.add[addOff + i]) * weight(i, overlap)
                        }
                    }
                } }
                tasks.forEach { fut -> fut.get() }
                tIstft += System.currentTimeMillis() - ti

                for (i in 0 until WAVE) {
                    val r = relBase + i
                    if (r < 0 || r >= W) continue
                    val abs = base + r
                    if (abs >= total) break
                    wsum[r] += weight(i, overlap)
                }
                b++

                // ★ flush：把 [base, base+step) （相对 [0, step)）归一化 + 写盘（仅当不会被后续块触碰）。
                //   是否 safe：下一块 start = base + step（末块除外）。末块（start+WAVE>=total）后直接 flush 剩余。
                val isLast = (start + WAVE >= total)
                val flushLen = if (isLast) minOf(W, total - base) else step
                if (flushLen > 0) {
                    for (src in 0 until 4) {
                        val inter = outInter[src]
                        for (i in 0 until flushLen) {
                            val w = if (wsum[i] > 1e-8f) wsum[i] else 1f
                            inter[i * 2] = acc[src][0][i] / w
                            inter[i * 2 + 1] = acc[src][1][i] / w
                        }
                        writers[src].append(if (flushLen * 2 == inter.size) inter else inter.copyOf(flushLen * 2))
                    }
                    // 滑动：把 [flushLen, W) 左移到 [0, W-flushLen)
                    val keep = W - flushLen
                    if (keep > 0) {
                        for (src in 0 until 4) for (c in 0 until 2)
                            System.arraycopy(acc[src][c], flushLen, acc[src][c], 0, keep)
                        System.arraycopy(wsum, flushLen, wsum, 0, keep)
                    }
                    // flush 区域清零（重新填充前保持干净）
                    for (src in 0 until 4) for (c in 0 until 2)
                        Arrays.fill(acc[src][c], keep, W, 0f)
                    Arrays.fill(wsum, keep, W, 0f)
                }

                onProgress?.invoke(minOf(start + WAVE, total), total)
                if (isLast) break
                s += step
            }
        } finally {
            isttfPool.shutdown()
        }
        Log.i(TAG, "timing(ms): read=$tRead stft=$tStft model=$tModel istft=$tIstft blocks=$b")

        val stats = Stats(total, total, b)
        Log.i(TAG, "demucs done: in=${stats.inputFrames} out=${stats.outputFrames} blocks=${stats.blocks}")
        return stats
    }

    /** 读块为 2 声道（不足窗长补零；单声道复制）。 */
    private fun readBlock(start: Int, channels: Int): Array<FloatArray> {
        val want = minOf(WAVE, reader.totalFrames - start).coerceAtLeast(0)
        val inter = if (want > 0) reader.readFrames(start, want) else FloatArray(0)
        val p = Array(2) { FloatArray(WAVE) }
        val n = inter.size / channels
        for (i in 0 until n) {
            if (channels == 1) { p[0][i] = inter[i]; p[1][i] = inter[i] }
            else { p[0][i] = inter[i * 2]; p[1][i] = inter[i * 2 + 1] }
        }
        return p
    }

    /** 三角淡入淡出窗（桌面验证同款）。 */
    private fun weight(i: Int, ov: Int): Float {
        if (ov <= 0) return 1f
        if (i < ov) return i.toFloat() / ov
        if (i >= WAVE - ov) return (WAVE - 1 - i).toFloat() / ov
        return 1f
    }

    companion object {
        private const val TAG = "DemucsSep"
        const val WAVE = 343_980
        const val DEFAULT_OVERLAP = WAVE / 4            // 25%
        val STEM_KINDS = listOf("drums", "bass", "other", "vocals")
    }
}
