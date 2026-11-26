package pub.miku.neuralstem.player

import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import pub.miku.neuralstem.spike.WavReader
import pub.miku.neuralstem.spike.WavWriter

/**
 * 变调/变速音质验证（正弦叠加 → 当前 SonicStage → 频谱分析 + WAV 输出）。
 *
 * 目的：用可量化信号回答「Sonic 变调是否引入明显音质损失」。
 * 输出 WAV 供试听：`stability-out/sonic-test/`。
 */
class SonicPitchQualityTest {

    private val sr = 44_100
    private val seconds = 2.0

    /** 440Hz(0.5) + 880Hz(0.25) 叠加；无 DC、无削波。 */
    private fun signal(): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            (0.5 * sin(2 * PI * 440.0 * t) + 0.25 * sin(2 * PI * 880.0 * t)).toFloat()
        }
    }

    /** 用当前 SonicStage 渲染整段。 */
    private fun render(input: FloatArray, speed: Float, pitchRate: Float): FloatArray {
        val stage = SonicStage(sr, 1, 1024)
        stage.setParams(speed, pitchRate)
        stage.applyParams(0L)
        val out = ArrayList<Float>(input.size * 2)
        val chunk = 1024
        var pos = 0
        val collect: (FloatArray, Int) -> Unit = { arr, n ->
            for (i in 0 until n) out.add(arr[i])
        }
        val sink: (java.nio.ByteBuffer, Int) -> Int = { bb, bytes ->
            val fb = bb.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            collect(arr, arr.size)
            bytes
        }
        while (pos < input.size) {
            val n = minOf(chunk, input.size - pos)
            val buf = FloatArray(n)
            input.copyInto(buf, 0, pos, pos + n)
            stage.process(buf, n, sink)
            pos += n
        }
        stage.finish(sink)
        return out.toFloatArray()
    }

    private fun writeWav(name: String, samples: FloatArray) {
        val dir = File("../stability-out/sonic-test").apply { mkdirs() }
        WavWriter(File(dir, name), 1, sr).use { it.append(samples) }
    }

    /** Goertzel：单频点幅值。 */
    private fun goertzel(samples: FloatArray, freq: Double, start: Int, len: Int): Double {
        val k = 2.0 * cos(2.0 * PI * freq / sr)
        var s1 = 0.0
        var s2 = 0.0
        for (i in start until (start + len)) {
            val s0 = samples[i] + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / len * 2.0
    }

    private fun rms(samples: FloatArray, start: Int, len: Int): Double {
        var sum = 0.0
        for (i in start until (start + len)) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / len)
    }

    private fun semitonesToRate(semitones: Double) = Math.pow(2.0, semitones / 12.0)

    /** Hann 窗 Goertzel（减少非整数周期泄漏）；返回正弦幅值。 */
    private fun goertzelHann(samples: FloatArray, freq: Double, start: Int, len: Int): Double {
        val k = 2.0 * cos(2.0 * PI * freq / sr)
        var s1 = 0.0
        var s2 = 0.0
        val denom = (len - 1).toDouble()
        for (i in 0 until len) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / denom) // Hann
            val s0 = samples[start + i] * w + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        val mag = sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / len
        // Hann 窗相干增益 0.5 → ×2；Goertzel 幅值到正弦幅值再 ×2（与无窗版本口径一致）
        return mag * 4.0
    }

    /** 在 center ± halfWidth 内找最大幅值频点。 */
    private fun peakNear(
        samples: FloatArray,
        start: Int,
        len: Int,
        center: Double,
        halfWidth: Double = 25.0,
        step: Double = 0.25,
    ): Pair<Double, Double> {
        var bestF = center
        var bestM = -1.0
        var f = (center - halfWidth).coerceAtLeast(20.0)
        while (f <= center + halfWidth) {
            val m = goertzelHann(samples, f, start, len)
            if (m > bestM) {
                bestM = m
                bestF = f
            }
            f += step
        }
        return bestF to bestM
    }

    /** 粗扫频：返回幅度最高的前 [topN] 个频点（步进 [step] Hz）。 */
    private fun topPeaks(
        samples: FloatArray,
        start: Int,
        len: Int,
        minF: Double = 200.0,
        maxF: Double = 3200.0,
        step: Double = 5.0,
        topN: Int = 3,
    ): List<Pair<Double, Double>> {
        val all = ArrayList<Pair<Double, Double>>()
        var f = minF
        while (f <= maxF) {
            all += f to goertzel(samples, f, start, len)
            f += step
        }
        return all.sortedByDescending { it.second }.take(topN)
    }

    @Test
    fun `变调变速的基频准确性与失真`() {
        val input = signal()
        writeWav("00-input.wav", input)
        val start = (sr * 0.25).toInt()
        val len = (sr * 1.0).toInt()

        val baseRms = rms(input, start, len)
        val cases = listOf(
            Triple("speed1.0_pitch0", 1f, 1f),
            Triple("speed1.0_pitch+2", 1f, semitonesToRate(2.0).toFloat()),
            Triple("speed1.0_pitch+7", 1f, semitonesToRate(7.0).toFloat()),
            Triple("speed1.0_pitch+12", 1f, semitonesToRate(12.0).toFloat()),
            Triple("speed1.2_pitch0", 1.2f, 1f),
            Triple("speed0.8_pitch0", 0.8f, 1f),
        )

        val report = StringBuilder()
        report.appendLine("输入: 440Hz(0.5) + 880Hz(0.25)，2s @44.1k；分析窗 [0.25s, 1.25s]，Hann 窗")
        report.appendLine("pitch 变调后期望: 440×rate 与 880×rate；合成 RMS = 0.395")
        report.appendLine()
        report.appendLine("name                 rate   f1实测(偏差,m)           f2实测(偏差,m)           捕获%  噪声%  原频残留")
        for ((name, speed, pitchRate) in cases) {
            val out = if (speed == 1f && pitchRate == 1f) input else render(input, speed, pitchRate)
            writeWav("$name.wav", out)
            val st = minOf(start, out.size / 4)
            val l = minOf(len, out.size - st)
            if (l < sr / 2) {
                report.appendLine("$name: 输出太短（${out.size}），跳过分析")
                continue
            }
            val exp1 = 440.0 * pitchRate
            val exp2 = 880.0 * pitchRate
            val (p1f, p1m) = peakNear(out, st, l, exp1)
            val (p2f, p2m) = peakNear(out, st, l, exp2)
            val total = rms(out, st, l)
            val captured = sqrt((p1m * p1m + p2m * p2m) / 2.0)
            val capturedPct = captured / total * 100.0
            val noisePct = 100.0 - capturedPct
            val residual = goertzelHann(out, 440.0, st, l) + goertzelHann(out, 880.0, st, l)
            report.appendLine(
                "%-20s %6.3f  %7.1f(%+5.2f,m=%.3f) %7.1f(%+5.2f,m=%.3f)  %6.1f%% %5.1f%%  %.4f".format(
                    name, pitchRate,
                    p1f, p1f - exp1, p1m,
                    p2f, p2f - exp2, p2m,
                    capturedPct, noisePct, residual,
                ),
            )
        }
        File("../stability-out/sonic-test/analysis.txt").writeText(report.toString())
        assertTrue("输入 RMS 正常", baseRms > 0.1)
    }

    // ---------------- 真实音乐对比 ----------------

    private fun renderCh(input: FloatArray, channels: Int, speed: Float, pitchRate: Float): FloatArray {
        val stage = SonicStage(sr, channels, 1024)
        stage.setParams(speed, pitchRate)
        stage.applyParams(0L)
        val out = ArrayList<Float>(input.size * 2)
        val sink: (java.nio.ByteBuffer, Int) -> Int = { bb, bytes ->
            val fb = bb.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            arr.forEach { out.add(it) }
            bytes
        }
        var pos = 0
        while (pos < input.size) {
            val n = minOf(1024 * channels, input.size - pos)
            val buf = FloatArray(n)
            input.copyInto(buf, 0, pos, pos + n)
            stage.process(buf, n / channels, sink)
            pos += n
        }
        stage.finish(sink)
        return out.toFloatArray()
    }

    /** 平均幅度谱（每 [hop] 帧一个 Hann 窗，对 [freqs] 逐点 Goertzel）。 */
    private fun avgSpectrum(samples: FloatArray, channels: Int, freqs: DoubleArray): DoubleArray {
        val winFrames = 4096
        val hopFrames = 2048
        val winSamples = winFrames * channels
        val hopSamples = hopFrames * channels
        val acc = DoubleArray(freqs.size)
        var count = 0
        var pos = 0
        while (pos + winSamples <= samples.size) {
            for (i in freqs.indices) {
                acc[i] += goertzelHann(samples, freqs[i], pos, winSamples)
            }
            count++
            pos += hopSamples
        }
        return DoubleArray(freqs.size) { acc[it] / count.coerceAtLeast(1) }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-12)
    }

    @Test
    fun `真实音乐变调的频谱保真度`() {
        val song = File("/tmp/song.wav")
        if (!song.exists()) {
            println("缺少 /tmp/song.wav，跳过真实音乐对比")
            return
        }
        val (input, channels, songSr) = WavReader(song).use { r ->
            val frames = minOf(r.totalFrames, r.sampleRate * 12)
            Triple(r.readFrames(0, frames), r.channels, r.sampleRate)
        }
        val freqs = DoubleArray(120) { 120.0 + it * 60.0 } // 120..7260 Hz
        val ref = avgSpectrum(input, channels, freqs)
        val dir = File("../stability-out/sonic-test").apply { mkdirs() }
        WavWriter(File(dir, "song-input.wav"), channels, songSr).use { it.append(input) }

        val report = StringBuilder()
        report.appendLine("真实歌曲（12s, ${channels}ch, ${songSr}Hz）变调频谱保真度")
        report.appendLine("参考：按 pitchRate 把原频谱映射到新频率轴后求 cosine 相似度（1.0 = 完全一致）")
        report.appendLine()
        for (semis in listOf(2.0, 5.0, 7.0, 12.0, -5.0, -12.0)) {
            val rate = semitonesToRate(semis)
            val out = renderCh(input, channels, 1f, rate.toFloat())
            val name = "song-pitch%+d.wav".format(semis.toInt())
            WavWriter(File(dir, name), channels, songSr).use { it.append(out) }
            // 把参考频谱按 rate 重采样到同一批物理频率点上
            val mapped = DoubleArray(freqs.size) { i ->
                val srcF = freqs[i] / rate
                if (srcF < 120.0 || srcF > 7260.0) 0.0 else ref[((srcF - 120.0) / 60.0).toInt().coerceIn(0, 119)]
            }
            val got = avgSpectrum(out, channels, freqs)
            report.appendLine(
                "pitch %+3d 半音 (rate=%.4f): cosine=%.4f  输出 %.1fs".format(
                    semis.toInt(), rate, cosine(mapped, got), out.size.toDouble() / channels / songSr,
                ),
            )
        }
        File(dir, "song-analysis.txt").writeText(report.toString())
        println(report)
    }
}
