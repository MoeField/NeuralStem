package pub.miku.neuralstem.spike

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 阶段 M4：分离管线共用的 **固定长度 radix-2 FFT + 周期 Hann 窗**。
 *
 * Demucs（S2）与 Spleeter 4-stem（S3）都用 **n_fft=4096 / hop=1024 / 周期 Hann**，
 * 只在下游的「bins 数 / 张量排布」上不同。故把最底层抽到这里，两个管线共用，
 * 避免两份 FFT 实现漂移。
 *
 * > ⚠️ 窗必须是**周期 Hann**（`hann(N)[i] = 0.5 - 0.5·cos(2πi/N)`），
 * > 而不是对称 Hann（`np.hanning(N)`）——两者差一个样本，正是它决定
 * > 「匹配训练期频谱」还是「仅仅长得像」（见 Best-Practice README）。
 */
internal object Fft4096 {

    const val N_FFT = 4096

    /** 周期 Hann 窗；懒加载并缓存。 */
    val window: FloatArray by lazy {
        FloatArray(N_FFT) { i -> (0.5f - 0.5f * cos(2.0 * PI * i / N_FFT)).toFloat() }
    }

    private val bitRev = IntArray(N_FFT).also { rev ->
        var j = 0
        for (i in 0 until N_FFT) {
            rev[i] = j
            var bit = N_FFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
        }
    }

    /**
     * 每级用到的 twiddle（`cos/sin(2πk/len)`）预先算好，避免每次 FFT 重算 sin/cos。
     * 这是 iSTFT 热点的主要优化点。key = len*2 + (inverse?1:0)。
     *
     * ★ 并发安全：全部 twiddle 在对象初始化时**一次性预计算**（阶段数很少），
     * 之后只读。这样 [fft] 可安全地被多线程并行调用（iSTFT 并行化）。
     */
    private val sinCos: Map<Int, Pair<FloatArray, FloatArray>> = buildMap {
        var len = 2
        while (len <= N_FFT) {
            for (inv in 0..1) {
                val half = len / 2
                val r = FloatArray(half)
                val im = FloatArray(half)
                for (k in 0 until half) {
                    val ang = (if (inv == 1) 2.0 else -2.0) * PI * k / len
                    r[k] = cos(ang).toFloat()
                    im[k] = sin(ang).toFloat()
                }
                put(len * 2 + inv, r to im)
            }
            len = len shl 1
        }
    }

    private fun twiddles(len: Int, inverse: Boolean): Pair<FloatArray, FloatArray> =
        sinCos.getValue(len * 2 + (if (inverse) 1 else 0))

    /**
     * 原地 radix-2 FFT（长度必须是 [N_FFT]）。
     * @param inverse true = 反变换（含 1/N 归一化）
     */
    fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = N_FFT
        for (i in 0 until n) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val (twR, twI) = twiddles(len, inverse)
            val half = len / 2
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val curR = twR[k]
                    val curI = twI[k]
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + half] * curR - im[i + k + half] * curI
                    val vI = re[i + k + half] * curI + im[i + k + half] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + half] = uR - vR
                    im[i + k + half] = uI - vI
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1f / n
            for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
        }
    }
}
