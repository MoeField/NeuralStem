package pub.miku.neuralstem.spike

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 设备能力探测(阶段 M2 任务 B)。
 *
 * ## 为什么需要
 * UVR 的"时间换质量"是真实的(用户听感确认单调)，值得保留全部模型。
 * 但**哪些模型"能实时用"取决于设备**：固定阈值会误杀旗舰机的可用模型、
 * 也会漏判中端机能力。⇒ 由**本机实测**决定分组是最稳健的做法。
 *
 * ## 方法
 * 对每个已安装的模型跑**一块**合成音频(不需要真实音频文件)，测 wall-clock：
 * ```
 *   RTF = 块时长(秒) / 耗时(秒)        // >1 表示比播放快
 * ```
 * - 短测试(默认)：每个模型跑 1 块 → 5 个模型总耗时 ≈ 数秒
 * - 长测试(可选)：每个模型跑 3 块取中位数(更稳，更慢)
 *
 * ## 为何用合成音频
 * benchmark 测的是**推理速度**，与音频内容无关(是 compute-bound)。
 * 合成一个 44.1k 立体声正弦即可，既免去外部文件依赖，也避免读盘抖动。
 *
 * ## 缓存
 * 结果按 `设备型号 + SoC + Build.ID` 存入 SharedPreferences；
 * 同一设备不重复跑。用户在设置页可"重新检测"(会失效缓存)。
 */
class DeviceBenchmark(private val app: Application) {

    enum class Mode { SHORT, LONG }

    /**
     * @param rtf 实时率(>1 比播放快)
     * @param ms 本次测量的代表耗时(ms)
     */
    data class ModelBenchmark(
        val modelId: String,
        val rtf: Float,
        val ms: Long,
    ) {
        /** 处理 [audioSeconds] 秒音频预计耗时(ms)。 */
        fun estimateMs(audioSeconds: Float): Long =
            if (rtf <= 0f) 0L else (audioSeconds / rtf * 1000f).toLong()
    }

    // ---------------- 公共 API ----------------

    /** 读取缓存结果(若有)。 */
    fun cached(): Map<String, ModelBenchmark> {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = cacheKey()
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (id in obj.keys()) {
                    val o = obj.getJSONObject(id)
                    put(id, ModelBenchmark(id, o.getDouble("rtf").toFloat(), o.getLong("ms")))
                }
            }
        }.getOrElse {
            Log.w(TAG, "cache parse failed", it); emptyMap()
        }
    }

    /** 清除缓存(设置页"重新检测"用)。 */
    fun invalidate() {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(cacheKey()).apply()
    }

    /**
     * 跑 benchmark。**只测已安装(模型文件存在)的模型** —— 没装的跳过。
     *
     * 注意:必须在**后台线程**调用(会阻塞数秒)。
     *
     * @param mode SHORT(1 块)/ LONG(3 块取中位)
     * @param models 要测的模型(默认全部)
     */
    suspend fun run(
        mode: Mode = Mode.SHORT,
        models: List<ModelSpec> = ModelRegistry.ALL,
    ): Map<String, ModelBenchmark> = withContext(Dispatchers.Default) {
        val rounds = if (mode == Mode.LONG) 3 else 1
        val out = LinkedHashMap<String, ModelBenchmark>()
        for (spec in models) {
            val paths = resolvePaths(spec) ?: run {
                Log.i(TAG, "skip ${spec.id}: not installed")
                continue
            }
            val chunkFrames = chunkFramesFor(spec)
            val chunkSeconds = chunkFrames.toFloat() / SR
            val block = syntheticStereo(chunkFrames)

            // 建引擎(不计入测量) + 预热一次(排除首次分配开销)
            val engine = runCatching {
                SherpaSourceSeparation.create(
                    paths.first, paths.second, numThreads = SeparationRepository.NUM_THREADS,
                    mode = spec.engine,
                )
            }.getOrElse {
                Log.w(TAG, "skip ${spec.id}: engine create failed", it); null
            } ?: continue

            val times = ArrayList<Long>(rounds)
            try {
                // 预热(丢弃):首次 forward 有 lazy 分配/页错误,不代表稳态
                runCatching { engine.separate(block, 2, SR) }
                repeat(rounds) {
                    val t0 = System.nanoTime()
                    engine.separate(block, 2, SR)
                    times += (System.nanoTime() - t0) / 1_000_000
                }
            } finally {
                engine.release()
            }

            val ms = times.sorted()[times.size / 2]     // 中位数
            val rtf = chunkSeconds / (ms / 1000f)
            out[spec.id] = ModelBenchmark(spec.id, rtf, ms)
            Log.i(
                TAG,
                "bench ${spec.id}: chunk=${"%.1f".format(chunkSeconds)}s " +
                    "ms=$ms rtf=${"%.2f".format(rtf)} (rounds=$rounds times=$times)",
            )
        }
        save(out)
        out
    }

    // ---------------- 内部 ----------------

    /** 模型文件路径;缺失返回 null(未安装)。 */
    private fun resolvePaths(spec: ModelSpec): Pair<String, String?>? {
        val modelDir = File(app.filesDir, if (spec.engine == SherpaSourceSeparation.MODE_UVR) "uvr" else "sherpa")
        val main = File(modelDir, spec.fileName)
        if (!main.exists()) return null
        if (spec.engine == SherpaSourceSeparation.MODE_SPLEETER) {
            val accomp = File(modelDir, spec.spleeterAccompFile ?: return null)
            if (!accomp.exists()) return null
            return main.absolutePath to accomp.absolutePath
        }
        return main.absolutePath to null
    }

    private fun chunkFramesFor(spec: ModelSpec): Int =
        if (spec.engine == SherpaSourceSeparation.MODE_UVR) {
            SeparationRepository.UVR_CHUNK_FRAMES
        } else {
            StreamingSeparator.DEFAULT_CHUNK_FRAMES
        }

    /** 合成 44.1k 立体声正弦(两声部相位略不同，避免退化)。 */
    private fun syntheticStereo(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val t = f.toFloat() / SR
            out[f * 2] = 0.2f * sin(2.0 * Math.PI * 440.0 * t).toFloat()
            out[f * 2 + 1] = 0.2f * cos(2.0 * Math.PI * 330.0 * t).toFloat()
        }
        return out
    }

    private fun save(results: Map<String, ModelBenchmark>) {
        val obj = JSONObject()
        results.forEach { (id, b) ->
            obj.put(id, JSONObject().put("rtf", b.rtf.toDouble()).put("ms", b.ms))
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(cacheKey(), obj.toString()).apply()
        Log.i(TAG, "saved ${results.size} benchmark result(s)")
    }

    /** 缓存键：设备型号 + SoC + ROM 版本 —— 换设备/刷机后自动失效。 */
    private fun cacheKey(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        return "bench_${Build.MODEL}_${soc}_${Build.ID}".replace(' ', '_')
    }

    companion object {
        private const val TAG = "DevBench"
        private const val PREFS = "device_benchmark"
        private const val SR = 44_100
    }
}
