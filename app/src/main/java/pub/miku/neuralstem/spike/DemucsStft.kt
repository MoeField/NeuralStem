package pub.miku.neuralstem.spike

import java.util.Arrays

/**
 * 阶段 M4 / S2：htdemucs（Demucs）ONNX 所需的 **STFT / iSTFT** 实现。
 *
 * ## 契约（已在桌面用 onnxruntime 验证，见 `doc/.memory.md` §M4-S2）
 *
 * htdemucs 的混合架构把「正向 STFT」拆到了图外，必须由调用方算好传入：
 * ```
 * input : [1, 2, 343980]      波形（固定 7.8s @44.1k）
 * x     : [1, 4, 2048, 336]   = STFT(波形)
 * ```
 * `x` 的排布（4 = 2ch × (real, imag)）：
 * ```
 * x[0,0] = L 实部   x[0,1] = L 虚部
 * x[0,2] = R 实部   x[0,3] = R 虚部
 * ```
 * 每个是 `[2048 bins, 336 frames]`。
 *
 * ## STFT 参数（demucs 约定）
 * - `n_fft = 4096`，`hop = 1024`
 * - 窗函数 = **周期 Hann**（`hann(N+1)[:-1]`，等价 `torch.hann_window(4096)` 默认 `periodic=True`）
 * - `center = True` → `n_frames = 1 + N // hop`（= 1 + 335 = 336），两侧各补 `n_fft/2 = 2048`
 * - 只取前 **2048** 个 bin（`rfft(4096)` 会给 2049 个，丢弃 Nyquist）
 *
 * ## 反向（iSTFT）
 * 用于把模型的频谱输出 `output` 变回时域：**最终输出 = iSTFT(output) + add_67**。
 * 本类同时实现 [istft]，采用与 [stft] 完全对称的重叠相加（OLA）+ 窗平方归一。
 *
 * > ⚠️ 本类的正确性依赖「正/反变换严格互为逆」以及「与 demucs 约定一致」。
 * > 已在桌面 Python 上验证：4 源之和与原波形残差 1.335%（见 .memory.md）。
 */
object DemucsStft {

    const val N_FFT = 4096
    const val HOP = 1024
    const val BINS = 2048          // 实际使用的频率 bin 数（丢弃 Nyquist）
    private const val PAD = N_FFT / 2

    /** 周期 Hann 窗；懒加载并缓存。 */
    private val window: FloatArray get() = Fft4096.window

    /** 给定样本数 N，STFT 之后的帧数（center=True 约定）。 */
    fun frameCount(nSamples: Int): Int = 1 + nSamples / HOP

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) = Fft4096.fft(re, im, inverse)

    /**
     * 对**平面**声道数据做 STFT，输出打包好的 `[1, 4, BINS, frames]` 张量（行优先）。
     *
     * @param planar `planar[c]` 是第 c 声道的样本（长度 N），必须 2 声道
     * @return 长度 `4 * BINS * frames` 的 FloatArray
     */
    fun stftPacked(planar: Array<FloatArray>, nSamples: Int): FloatArray {
        require(planar.size == 2) { "htdemucs 需要 2 声道" }
        val frames = frameCount(nSamples)
        val out = FloatArray(4 * BINS * frames)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (c in 0 until 2) {
            val x = planar[c]
            for (f in 0 until frames) {
                Arrays.fill(re, 0f)
                Arrays.fill(im, 0f)
                val start = f * HOP - PAD
                for (k in 0 until N_FFT) {
                    val idx = start + k
                    // center=True 用零填充（与桌面验证一致）
                    val s = if (idx in 0 until nSamples) x[idx] else 0f
                    re[k] = s * window[k]
                }
                fft(re, im, inverse = false)
                // 写入 x[0, c*2 + 0] = real, x[0, c*2 + 1] = imag；形状 [BINS, frames]
                val reBase = (c * 2 + 0) * BINS * frames
                val imBase = (c * 2 + 1) * BINS * frames
                for (b in 0 until BINS) {
                    out[reBase + b * frames + f] = re[b]
                    out[imBase + b * frames + f] = im[b]
                }
            }
        }
        return out
    }

    /**
     * 对一个 stem 的频谱做 iSTFT，重建时域 `[2, nSamples]`（平面）。
     *
     * @param reIm 打包的频谱，排布同 [stftPacked]（同一 stem）：`[4, BINS, frames]`
     */
    fun istftPacked(reIm: FloatArray, frames: Int, nSamples: Int): Array<FloatArray> {
        val out = Array(2) { FloatArray(nSamples) }
        // ★ OLA 必须在**完整 padded 长度**上做，最后才裁剪；
        //   若把缓冲直接开成 nSamples，尾部 2048 样本会读到未写入的 wsum → 除零/错位。
        val totalLen = (frames - 1) * HOP + N_FFT
        val ola = FloatArray(totalLen)
        val wsum = FloatArray(totalLen)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (c in 0 until 2) {
            val reBase = (c * 2 + 0) * BINS * frames
            val imBase = (c * 2 + 1) * BINS * frames
            Arrays.fill(wsum, 0f)
            Arrays.fill(ola, 0f)
            for (f in 0 until frames) {
                Arrays.fill(re, 0f)
                Arrays.fill(im, 0f)
                for (b in 0 until BINS) {
                    re[b] = reIm[reBase + b * frames + f]
                    im[b] = reIm[imBase + b * frames + f]
                }
                // 频谱共轭对称：上半部分填共轭
                for (b in 1 until N_FFT / 2) {
                    re[N_FFT - b] = re[b]
                    im[N_FFT - b] = -im[b]
                }
                fft(re, im, inverse = true)
                // OLA：乘窗 + 累计窗平方
                val start = f * HOP
                for (k in 0 until N_FFT) {
                    ola[start + k] += re[k] * window[k]
                    wsum[start + k] += window[k] * window[k]
                }
            }
            // 归一（全长度）+ 去掉 center padding
            for (i in 0 until nSamples) {
                val p = i + PAD
                val w = if (p < totalLen) wsum[p] else 0f
                out[c][i] = if (w > 1e-8f) ola[p] / w else 0f
            }
        }
        return out
    }

    /** 供单测用：窗函数长度自检。 */
    internal fun windowCheck(): Pair<Int, Float> = window.size to window[0]
}
