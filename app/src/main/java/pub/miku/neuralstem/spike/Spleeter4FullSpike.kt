package pub.miku.neuralstem.spike

import android.util.Log
import java.io.File

/**
 * 阶段 M4 / S3：Spleeter 4-stem **端到端**真机验证入口（非 UI 通道）。
 *
 * 流程：`filesDir/spleeter4` 的模型 + `--es sp4_input <wav>` → 4 轨落盘
 * 到 `filesDir/spleeter4-out/`，并计算 **重建不变量** `sum(4)−mix`（客观判据）。
 *
 * 用法：
 * ```
 * adb shell am start -n pub.miku.neuralstem/.spike.SpikeActivity \
 *   --ez spleeter4_full true --es sp4_input <abs wav path>
 * ```
 */
object Spleeter4FullSpike {

    private const val TAG = "Spleeter4Full"

    fun run(filesDir: File, inputPath: String?): String {
        val modelDir = File(filesDir, "spleeter4")
        val input = if (inputPath != null && inputPath.isNotEmpty()) File(inputPath)
        else File(filesDir, "spleeter4/probe_input.wav")
        if (!input.exists()) return skip("音频不存在: ${input.absolutePath}")

        val outDir = File(filesDir, "spleeter4-out").apply { mkdirs() }
        val outFiles = Spleeter4Dsp.STEM_KINDS.map { File(outDir, "$it.wav") }

        var reader: FrameSource? = null
        val writers = ArrayList<WavWriter>()
        var estimator: Spleeter4Estimator? = null
        return try {
            val t0 = System.currentTimeMillis()
            estimator = Spleeter4Estimator.create(modelDir, threads = 4)
            val createMs = System.currentTimeMillis() - t0

            reader = WavFrameSource(WavReader(input))
            val ch = reader.channels
            val sr = reader.sampleRate
            for (f in outFiles) writers.add(WavWriter(f, channels = ch, sampleRate = sr))

            val t1 = System.currentTimeMillis()
            val est = estimator ?: error("estimator null")
            val sep = Spleeter4Separator(
                reader = reader,
                writers = writers,
                estimator = est,
                onProgress = { done, total -> Log.i(TAG, "progress $done/$total") },
            )
            val stats = sep.run()
            val inferMs = System.currentTimeMillis() - t1
            writers.forEach { it.close() }
            writers.clear()

            // 重建不变量：sum(4) − mix
            val recon = reconstructionErrorDb(input, outFiles)

            val summary = "SP4_FULL_OK createMs=$createMs inferMs=$inferMs " +
                "inFrames=${stats.inputFrames} outFrames=${stats.outputFrames} " +
                "blocks=${stats.blocks} reconDb=${"%.1f".format(recon)} " +
                "files=${outFiles.joinToString{it.name}}"
            Log.i(TAG, summary)
            summary
        } catch (t: Throwable) {
            Log.e(TAG, "SP4_FULL_FAIL", t)
            "SP4_FULL_FAIL ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { estimator?.close() }
            runCatching { reader?.close() }
            writers.forEach { runCatching { it.close() } }
        }
    }

    /** `10·log10( mean((sum4−mix)²) / mean(mix²) )`，越小越好（桌面参考 −163.5 dB）。 */
    private fun reconstructionErrorDb(mixWav: File, stemWavs: List<File>): Double {
        WavReader(mixWav).use { mixR ->
            val n = mixR.totalFrames
            val ch = mixR.channels
            val mix = mixR.readFrames(0, n)
            val sum = FloatArray(n * ch)
            for (f in stemWavs) {
                WavReader(f).use { r ->
                    val s = r.readFrames(0, minOf(n, r.totalFrames))
                    for (i in s.indices) sum[i] += s[i]
                }
            }
            var num = 0.0
            var den = 0.0
            for (i in mix.indices) {
                val d = (sum[i] - mix[i]).toDouble()
                num += d * d
                den += mix[i].toDouble() * mix[i]
            }
            return if (den <= 0.0) Double.NaN else 10.0 * Math.log10(num / den)
        }
    }

    private fun skip(msg: String): String {
        Log.e(TAG, "SP4_FULL_SKIP $msg"); return "SP4_FULL_SKIP $msg"
    }
}
