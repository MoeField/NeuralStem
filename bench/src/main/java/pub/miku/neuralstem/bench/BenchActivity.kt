package pub.miku.neuralstem.bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * 阶段 M4 — Demucs EP 上限验证（独立 bench）。
 *
 * 加载 `htdemucs_embedded.onnx`，用不同执行提供者（EP）各跑一次，记录耗时：
 *   CPU / NNAPI / XNNPACK / QNN
 *
 * 目的：回答「若 ORT 支持 GPU/NPU，Demucs 能快多少」——主工程因符号冲突用不了这些 EP
 * （见坑 49）。本模块**不与 sherpa 共存**，故可自由启用。
 *
 * 用法：
 * ```
 * adb push htdemucs_embedded.onnx /sdcard/Android/data/pub.miku.neuralstem.bench/files/
 * adb shell am start -n pub.miku.neuralstem.bench/.BenchActivity
 * adb logcat -s BenchEp
 * ```
 */
class BenchActivity : Activity() {

    private val tag = "BenchEp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                runBench()
            } catch (t: Throwable) {
                Log.e(tag, "BENCH_FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }.start()
    }

    private fun modelPath(): String {
        val candidates = listOf(
            File(filesDir, "htdemucs_embedded.onnx"),
            File(getExternalFilesDir(null), "htdemucs_embedded.onnx"),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
            ?: error("模型不存在，请 push 到 ${candidates[0].absolutePath}")
    }

    private fun runBench() {
        val path = modelPath()
        Log.i(tag, "model=$path size=${File(path).length()}")

        val env = OrtEnvironment.getEnvironment()
        Log.i(tag, "ORT version = ${env.version}")
        Log.i(tag, "available providers = ${OrtEnvironment.getAvailableProviders()}")

        // 输入张量（固定形状）：input [1,2,343980]、x [1,4,2048,336]
        val wave = FloatArray(2 * 343_980) { 0.01f * ((it % 100) - 50) / 50f }
        val spec = FloatArray(4 * 2048 * 336) { 0.01f * ((it % 100) - 50) / 50f }

        // 依次尝试各 EP
        runOne(env, path, "CPU", wave, spec) { /* 默认即 CPU */ }
        runOne(env, path, "NNAPI", wave, spec) { opts -> opts.addNnapi() }
        runOne(env, path, "XNNPACK", wave, spec) {
            opts -> opts.addXnnpack(mapOf("intra_op_num_threads" to "6"))
        }
        runOne(env, path, "QNN", wave, spec) { opts ->
            opts.addQnn(mapOf("backend_path" to "libQnnHtp.so"))
        }
        Log.i(tag, "BENCH_DONE")
    }

    private fun runOne(
        env: OrtEnvironment,
        path: String,
        name: String,
        wave: FloatArray,
        spec: FloatArray,
        configure: (OrtSession.SessionOptions) -> Unit,
    ) {
        var session: OrtSession? = null
        try {
            val t0 = System.currentTimeMillis()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(6)
                configure(this)
            }
            session = env.createSession(path, opts)
            val loadMs = System.currentTimeMillis() - t0

            val tWave = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(wave), longArrayOf(1, 2, 343_980),
            )
            val tSpec = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(spec), longArrayOf(1, 4, 2048, 336),
            )
            val inputs = mapOf("input" to tWave, "x" to tSpec)

            // 预热一次（首次含图优化/内存分配）
            session.run(inputs).use { }

            // 计时：跑 3 次取中位
            val times = ArrayList<Long>(3)
            repeat(3) {
                val t = System.currentTimeMillis()
                session.run(inputs).use { res ->
                    res.forEach { (k, v) -> if (k == "add_67") { /* touch */ } }
                }
                times += System.currentTimeMillis() - t
            }
            times.sort()
            tWave.close(); tSpec.close()
            Log.i(
                tag,
                "EP=$name OK loadMs=$loadMs inferMs=${times} median=${times[1]} " +
                    "realtime=${"%.2f".format(343980 / 44100.0 / (times[1] / 1000.0))}x",
            )
        } catch (t: Throwable) {
            Log.e(tag, "EP=$name FAIL ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { session?.close() }
        }
    }
}
