package pub.miku.neuralstem.spike

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * S2 落地：验证 [DemucsStft.stftPacked] 的**排布与数值**与 numpy 参考一致。
 *
 * 参考值由桌面 Python 生成（同 .memory.md §S2-b 的契约）：
 * ```python
 * N_FFT,HOP,BINS=4096,1024,2048; PAD=N_FFT//2; N=343980; FRAMES=1+N//HOP
 * W=np.hanning(N_FFT+1)[:-1]
 * x = 0.5*sin(2*pi*440*arange(N)/44100)   # 两声道相同
 * p=np.zeros((FRAMES-1)*HOP+N_FFT); p[PAD:PAD+N]=x
 * idx=arange(N_FFT)[None,:]+HOP*arange(FRAMES)[:,None]
 * S=np.fft.rfft(p[idx]*W,axis=-1)[:,:BINS]      # [FRAMES,BINS]
 * # 打包 [1,4,BINS,FRAMES]: L_re,L_im,R_re,R_im = S.real.T,S.imag.T,...
 * ```
 * 本测试对比第 0 帧、bin 1/2 的实部/虚部。
 */
class DemucsStftPackedCheckTest {

    // numpy 参考：frame0, bin1 / bin2 的 (L_re,L_im)
    private val refFrame0Bin1 = doubleArrayOf(-7.982588929268802, 7.127998298898588e-05)
    private val refFrame0Bin2 = doubleArrayOf(7.992577122548623, 0.00014341818112484947)

    @Test
    fun packed_layout_matches_numpy() {
        val n = 343_980
        val frames = DemucsStft.frameCount(n)
        val x = FloatArray(n) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 44_100.0)).toFloat() }
        val planar = arrayOf(x, x)                 // 两声道相同
        val packed = DemucsStft.stftPacked(planar, n)

        fun get(ch: Int, part: Int, bin: Int, frame: Int): Double {
            val base = (ch * 2 + part) * DemucsStft.BINS * frames
            return packed[base + bin * frames + frame].toDouble()
        }
        // ch0(L) real/imag
        val lr1 = get(0, 0, 1, 0); val li1 = get(0, 1, 1, 0)
        val lr2 = get(0, 0, 2, 0); val li2 = get(0, 1, 2, 0)
        assertTrue("bin1: $lr1,$li1 vs ${refFrame0Bin1.toList()}",
            Math.abs(lr1 - refFrame0Bin1[0]) < 0.05 && Math.abs(li1 - refFrame0Bin1[1]) < 0.05)
        assertTrue("bin2: $lr2,$li2 vs ${refFrame0Bin2.toList()}",
            Math.abs(lr2 - refFrame0Bin2[0]) < 0.2 && Math.abs(li2 - refFrame0Bin2[1]) < 0.2)
    }
}
