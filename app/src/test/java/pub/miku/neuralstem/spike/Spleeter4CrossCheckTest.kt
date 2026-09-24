package pub.miku.neuralstem.spike

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 阶段 M4 / S3：把 Kotlin 的 STFT 与 **numpy 参考值**逐点对比。
 *
 * 参考值由桌面 Python 生成（写入 stdout，测试里用硬编码常量），
 * 用于确认我们的 FFT/窗/补零/split 排布与最佳实践 README 的实现**数值一致**，
 * 而不只是「自洽」。
 *
 * 生成方式（Python）：
 * ```python
 * N_FFT,HOP,PAD,BINS=4096,1024,4096-1024,1024
 * W=np.hanning(N_FFT+1)[:-1]
 * x=np.sin(2*np.pi*440*np.arange(40000)/44100)*0.5
 * n=int(np.ceil((PAD+len(x))/HOP))
 * p=np.zeros((n-1)*HOP+N_FFT); p[PAD:PAD+len(x)]=x
 * idx=np.arange(N_FFT)[None,:]+HOP*np.arange(n)[:,None]
 * S=np.fft.rfft(p[idx]*W,axis=-1)
 * print(np.abs(S[:3,:3]))
 * ```
 */
class Spleeter4CrossCheckTest {

    /** 第 0/1/2 帧、前 3 个 bin 的 |STFT|（numpy 参考值）。 */
    private val refMag = arrayOf(
        doubleArrayOf(3.987105441553387, 3.9891750829998323, 3.9956946408780536),
        doubleArrayOf(7.973425892586981, 7.982588929587045, 7.992577123835365),
        doubleArrayOf(3.9852101371658035, 3.9891434764613654, 3.9976172163163777),
    )

    @Test
    fun stft_magnitude_matches_numpy_reference() {
        val n = 40_000
        val x = FloatArray(n) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 44_100.0)).toFloat() }
        val frames = Spleeter4Dsp.frameCount(n)
        assertTrue("frame count 应为 43", frames == 43)
        val (re, im) = Spleeter4Dsp.stft(x, frames)

        for (f in 0 until 3) {
            for (b in 0 until 3) {
                val idx = f * Spleeter4Dsp.FULL_BINS + b
                val rr = re[idx].toDouble()
                val ii = im[idx].toDouble()
                val mag = Math.sqrt(rr * rr + ii * ii)
                val exp = refMag[f][b]
                // FFT 数值精度 + float32 下允许 ~1% 相对误差
                val rel = Math.abs(mag - exp) / exp
                assertTrue(
                    "frame$f bin$b: kotlin=$mag numpy=$exp rel=$rel",
                    rel < 1e-2,
                )
            }
        }
    }
}
