package pub.miku.neuralstem.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** v0.4.0 spike：Sonic vs Rubber Band 的 2ch 处理耗时对比 + 输出正确性。 */
@RunWith(AndroidJUnit4::class)
class RenderStageBenchmarkTest {

    private val sr = 44_100
    private val channels = 2

    private fun signal(seconds: Int): FloatArray {
        val frames = sr * seconds
        return FloatArray(frames * channels) { i ->
            val t = (i / channels).toDouble() / sr
            (0.4 * sin(2 * PI * 440.0 * t) + 0.2 * sin(2 * PI * 880.0 * t)).toFloat()
        }
    }

    /** 处理整段，返回（输出帧数, 耗时 ms）。 */
    private fun run(stage: RenderStage, input: FloatArray): Pair<Long, Long> {
        val frames = input.size / channels
        val chunk = 1024
        var outFrames = 0L
        val sink: (ByteBuffer, Int) -> Int = { _, bytes -> bytes }
        val started = System.nanoTime()
        var pos = 0
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            val buf = FloatArray(n * channels)
            input.copyInto(buf, 0, pos * channels, (pos + n) * channels)
            // process 返回写出字节数；换算成帧
            outFrames += stage.process(buf, n, sink) / (channels * 4)
            pos += n
        }
        stage.finish { _, bytes -> bytes }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return outFrames to elapsed
    }

    @Test
    fun sonic_vs_rubberband_pitch7() {
        val seconds = 10
        val input = signal(seconds)
        val pitch = Math.pow(2.0, 7.0 / 12.0).toFloat()

        val sonic = SonicStage(sr, channels, 1024)
        sonic.setParams(1f, pitch)
        sonic.applyParams(0L)
        val (sOut, sMs) = run(sonic, input)
        android.util.Log.i("RB_BENCH", "Sonic: out=$sOut frames, ${sMs}ms, RTF=${"%.2f".format(seconds * 1000.0 / sMs)}")

        Assume.assumeTrue("本构建未包含 RubberBand", RubberBandNative.available)
        val rb = RubberBandStage(sr, channels, 1024)
        rb.setParams(1f, pitch)
        rb.applyParams(0L)
        val (rOut, rMs) = run(rb, input)
        android.util.Log.i("RB_BENCH", "RB: out=$rOut frames, ${rMs}ms, RTF=${"%.2f".format(seconds * 1000.0 / rMs)}")
    }
}
