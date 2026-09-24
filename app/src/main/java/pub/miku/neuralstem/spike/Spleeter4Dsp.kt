package pub.miku.neuralstem.spike

import java.util.Arrays
import kotlin.math.sqrt

/**
 * 阶段 M4 / S3：Spleeter **4-stem** 的 DSP（STFT → mask → iSTFT）。
 *
 * ## 契约（一手核对自 ONNX 元数据 + Best-Practice README；桌面已验证 -163.5 dB）
 *
 * ```
 * 模型 IN  x : [2, num_splits, 512, 1024]   幅度谱（1024 of 2049 bins）
 * 模型 OUT y : [2, num_splits, 512, 1024]   该 stem 的幅度估计
 * ```
 *
 * ## 完整流程
 * ```
 *  波形 [2ch, N]
 *   → STFT（周期 Hann / n_fft 4096 / hop 1024）        → spec[2, frames, 2049]（复数）
 *   → 振幅 |spec| 取前 1024 bins，按 512 帧切 split      → mag[2, splits, 512, 1024]
 *   → 4 个模型各得 est[stem][2, splits, 512, 1024]      （由 JNI/ORT 完成）
 *   → ratio mask（分母需 4 个 stem 全部）
 *   → 带扩展：1024 → 2049 用 per-frame 均值（"average"，非 "zeros"）
 *   → mask 乘回**原始复谱**（相位天然正确）→ iSTFT       → 4 × [2ch, N]
 * ```
 *
 * ## 关键点
 * - **mask 分母必须用全部 4 个 stem**（不能只挑一个）
 * - **带扩展用 per-frame 均值**：用 zeros 会有 >11kHz 的 −23dB 空洞
 * - **相位用原始复谱**：mask 是实数，乘上去即可，无需重建相位
 *
 * > 正确性判据（桌面已验证）：`sum(4 stems) − mix` ≈ **−163.5 dB**（Spleeter ratio mask 和恒为 1）。
 */
object Spleeter4Dsp {

    const val N_FFT = 4096
    const val HOP = 1024
    const val MODEL_BINS = 1024        // 模型实际建模的 bin 数（到 ~11kHz）
    const val FULL_BINS = 2049         // rfft(4096) 的 bin 数（含 Nyquist）
    const val SPLIT_FRAMES = 512       // 每个 split 的帧数
    /** 前向补零量（★参考实现的 `PAD = N_FFT - HOP = 3072`，**不是** center-pad 的 2048）。
     *  它同时决定帧数公式与 iSTFT 的裁剪偏移，必须与参考实现一致。 */
    private const val PAD = N_FFT - HOP

    /** stem 顺序：必须与 JNI 侧一致。 */
    val STEM_KINDS = listOf("vocals", "drums", "bass", "other")

    private val window: FloatArray get() = Fft4096.window

    /** 给定样本数 N，STFT 帧数（与桌面参考实现一致：`ceil((PAD+N)/HOP)`）。 */
    fun frameCount(nSamples: Int): Int = Math.ceil((PAD + nSamples).toDouble() / HOP).toInt()

    fun splitCount(frames: Int): Int = Math.ceil(frames.toDouble() / SPLIT_FRAMES).toInt()

    /**
     * 计算**单个声道**的 STFT（front-pad `N_FFT-HOP`）。
     * @return 复数谱 `re/frame*FULL_BINS`、`im/...`（行优先 `[frames, FULL_BINS]`）
     */
    private fun stftChannel(x: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
        val re = FloatArray(frames * FULL_BINS)
        val im = FloatArray(frames * FULL_BINS)
        val total = (frames - 1) * HOP + N_FFT
        // 前向补零（与参考实现一致：p[PAD : PAD+N] = x）
        val padded = FloatArray(total)
        val copyLen = minOf(x.size, total - PAD)
        System.arraycopy(x, 0, padded, PAD, copyLen)
        val bufRe = FloatArray(N_FFT)
        val bufIm = FloatArray(N_FFT)
        for (f in 0 until frames) {
            val at = f * HOP
            for (k in 0 until N_FFT) bufRe[k] = padded[at + k] * window[k]
            Arrays.fill(bufIm, 0f)
            Fft4096.fft(bufRe, bufIm, inverse = false)
            val base = f * FULL_BINS
            for (b in 0 until FULL_BINS) {
                re[base + b] = bufRe[b]
                im[base + b] = bufIm[b]
            }
        }
        return re to im
    }

    /**
     * 把单声道复谱打包成模型输入块（**只含该声道**，按 `[splits, 512, 1024]` 展平）。
     *
     * 注意模型输入是 `[2, splits, 512, 1024]`：ch0 与 ch1 各是 `[splits,512,1024]`。
     * 本函数产出**一个声道的振幅块**；JNI 侧负责拼成 2 声道。
     */
    fun magnitudeBlock(re: FloatArray, im: FloatArray, frames: Int): FloatArray {
        val splits = splitCount(frames)
        val out = FloatArray(splits * SPLIT_FRAMES * MODEL_BINS)
        for (f in 0 until frames) {
            val srcBase = f * FULL_BINS
            val split = f / SPLIT_FRAMES
            val fr = f % SPLIT_FRAMES
            val dstBase = (split * SPLIT_FRAMES + fr) * MODEL_BINS
            for (b in 0 until MODEL_BINS) {
                val r = re[srcBase + b]
                val i = im[srcBase + b]
                out[dstBase + b] = sqrt(r * r + i * i)
            }
        }
        return out
    }

    /**
     * 对**一个 stem** 应用 ratio mask 与带扩展，并 iSTFT 回时域（单声道）。
     *
     * @param estStem 该 stem 的模型输出 `[splits*512, 1024]`（前 `frames` 行有效）
     * @param denom   全部 4 个 stem 的 `Σe² + 1e-10`，长度 `[splits*512, 1024]`
     * @param specRe/specIm 原始复谱 `[frames, 2049]`
     * @return 时域 `[nSamples]`
     */
    fun maskAndIstft(
        estStem: FloatArray,
        denom: FloatArray,
        specRe: FloatArray,
        specIm: FloatArray,
        frames: Int,
        nSamples: Int,
    ): FloatArray {
        val paddedTotal = (frames - 1) * HOP + N_FFT
        val out = FloatArray(paddedTotal)
        val wsum = FloatArray(paddedTotal)
        val bufRe = FloatArray(N_FFT)
        val bufIm = FloatArray(N_FFT)

        for (f in 0 until frames) {
            val split = f / SPLIT_FRAMES
            val fr = f % SPLIT_FRAMES
            val estBase = (split * SPLIT_FRAMES + fr) * MODEL_BINS
            val specBase = f * FULL_BINS

            // mask（1024）+ 带扩展（→2049，per-frame 均值）
            var sum = 0f
            val maskVals = FloatArray(MODEL_BINS)
            for (b in 0 until MODEL_BINS) {
                val e2 = estStem[estBase + b] * estStem[estBase + b] + 1e-10f / 4f
                val m = e2 / denom[estBase + b]
                maskVals[b] = m
                sum += m
            }
            val tailVal = sum / MODEL_BINS

            // 复数谱 × mask → 逆变换
            // ★ stftChannel 产出的是**完整 4096 bin** 的复谱，mask 只定义在 0..2048。
            //   必须把 mask 对称地施加到负频率 bin（N_FFT-b ↔ b），否则 iFFT 只用了
            //   单边谱 → 重建幅度恰好减半（实测误差恒为 0.5）。
            Arrays.fill(bufRe, 0f)
            Arrays.fill(bufIm, 0f)
            for (b in 0 until FULL_BINS) {
                val m = if (b < MODEL_BINS) maskVals[b] else tailVal
                val r = specRe[specBase + b] * m
                val imv = specIm[specBase + b] * m
                bufRe[b] = r
                bufIm[b] = imv
                if (b in 1 until N_FFT / 2) {
                    bufRe[N_FFT - b] = r
                    bufIm[N_FFT - b] = -imv
                }
            }
            Fft4096.fft(bufRe, bufIm, inverse = true)

            val at = f * HOP
            for (k in 0 until N_FFT) {
                out[at + k] += bufRe[k] * window[k]
                wsum[at + k] += window[k] * window[k]
            }
        }
        for (i in 0 until paddedTotal) {
            if (wsum[i] > 1e-8f) out[i] /= wsum[i]
        }
        // 去掉 front pad
        return FloatArray(nSamples) { i -> out[PAD + i] }
    }

    /** 计算 4 个 stem 的 mask 分母（`Σe² + 1e-10`）。 */
    fun denominator(estimates: Array<FloatArray>): FloatArray {
        val n = estimates[0].size
        val denom = FloatArray(n) { 1e-10f }
        for (e in estimates) {
            for (i in 0 until n) {
                denom[i] += e[i] * e[i]
            }
        }
        return denom
    }

    /** 暴露 STFT 给调用方（打包 re/im 对）。 */
    internal fun stft(x: FloatArray, frames: Int): Pair<FloatArray, FloatArray> =
        stftChannel(x, frames)
}
