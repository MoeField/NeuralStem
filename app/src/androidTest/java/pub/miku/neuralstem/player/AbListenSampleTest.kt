package pub.miku.neuralstem.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import pub.miku.neuralstem.spike.WavReader
import pub.miku.neuralstem.spike.WavWriter

/** v0.4.0 spike：用真实歌曲生成 Sonic / Rubber Band 的 AB 试听样本。 */
@RunWith(AndroidJUnit4::class)
class AbListenSampleTest {

    private val sr = 44_100
    private val channels = 2
    private val seconds = 12

    private fun write(name: String, data: FloatArray, dir: File) {
        WavWriter(File(dir, name), channels, sr).use { it.append(data) }
        Log.i("AB_SAMPLE", "wrote $name frames=${data.size / channels}")
    }

    private fun render(stage: RenderStage, input: FloatArray): FloatArray {
        val out = ArrayList<Float>(input.size * 2)
        val sink: (ByteBuffer, Int) -> Int = { bb, bytes ->
            val fb = bb.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            arr.forEach { out.add(it) }
            bytes
        }
        val frames = input.size / channels
        val chunk = 1024
        var pos = 0
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            val buf = FloatArray(n * channels)
            input.copyInto(buf, 0, pos * channels, (pos + n) * channels)
            stage.process(buf, n, sink)
            pos += n
        }
        stage.finish(sink)
        return out.toFloatArray()
    }

    @Test
    fun generate_ab_samples() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(ctx.filesDir, "song.wav")
        Assume.assumeTrue("缺少 files/song.wav", src.exists())

        val input = WavReader(src).use { r ->
            val frames = minOf(r.totalFrames, r.sampleRate * seconds)
            r.readFrames(0, frames)
        }
        val pitch = Math.pow(2.0, 7.0 / 12.0).toFloat()
        val dir = ctx.filesDir

        val sonic = SonicStage(sr, channels, 1024)
        sonic.setParams(1f, pitch)
        sonic.applyParams(0L)
        write("ab-sonic+7.wav", render(sonic, input), dir)

        Assume.assumeTrue("本构建未包含 RubberBand", RubberBandNative.available)
        val rb = RubberBandStage(sr, channels, 1024)
        rb.setParams(1f, pitch)
        rb.applyParams(0L)
        write("ab-rb+7.wav", render(rb, input), dir)
    }
}
