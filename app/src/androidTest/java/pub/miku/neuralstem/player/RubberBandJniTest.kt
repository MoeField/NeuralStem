package pub.miku.neuralstem.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** v0.4.0 spike：验证 RB JNI 可加载、能处理、能取出输出。 */
@RunWith(AndroidJUnit4::class)
class RubberBandJniTest {

    private val sr = 44_100
    private val channels = 2

    @Test
    fun rubberBand_pitchShift_producesOutput() {
        val options = RubberBandNative.PROCESS_REAL_TIME or
            RubberBandNative.PITCH_HIGH_QUALITY or
            RubberBandNative.WINDOW_LONG
        // +7 半音
        val pitch = Math.pow(2.0, 7.0 / 12.0).toFloat()
        val handle = RubberBandNative.create(sr, channels, options, 1f, pitch)
        assertTrue("handle 创建失败", handle != 0L)
        try {
            val framesTotal = sr * 2
            val chunk = 1024
            var outFrames = 0L
            var pos = 0
            while (pos < framesTotal) {
                val n = minOf(chunk, framesTotal - pos)
                val input = FloatArray(n * channels) { i ->
                    val t = (pos + i / channels).toDouble() / sr
                    (0.5 * sin(2 * PI * 440.0 * t)).toFloat()
                }
                RubberBandNative.process(handle, input, n, false)
                outFrames += drain(handle)
                pos += n
            }
            RubberBandNative.process(handle, FloatArray(0), 0, true)
            outFrames += drain(handle)
            println("RB: in=$framesTotal frames, out=$outFrames frames, latency=${RubberBandNative.latency(handle)}")
            assertTrue("应有输出（实际 $outFrames）", outFrames > framesTotal / 2)
        } finally {
            RubberBandNative.delete(handle)
        }
    }

    private fun drain(handle: Long): Long {
        var total = 0L
        val buf = FloatArray(4096 * channels)
        while (true) {
            val avail = RubberBandNative.available(handle)
            if (avail <= 0) break
            val n = minOf(avail, 4096)
            val got = RubberBandNative.retrieve(handle, buf, n)
            if (got <= 0) break
            total += got
        }
        return total
    }
}
