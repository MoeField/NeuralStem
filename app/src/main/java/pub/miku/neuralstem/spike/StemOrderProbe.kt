package pub.miku.neuralstem.spike

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ★实验模型 stem 顺序实证（NCC）。
 *
 * ## 为什么需要
 * UVR/MDX 运行时是「模型只预测一个 stem → non_vocals = 源 − 预测」（残差法），
 * 而 [SherpaSourceSeparation] 的输出**恒为** `stem[0]=vocals` / `stem[1]=accompaniment`。
 * 对 **Inst 系**（instrumental 导向）模型，它预测的其实是**伴奏** →
 * `stem[0]` 里装的其实是伴奏，两轨**完全对调**。App 侧靠 [ModelSpec.stemOrder] 纠正。
 *
 * [ModelRegistry.UVR_VARIANTS] 的 5 个已实证（见 [ModelSpec] 类注释）；
 * [ModelRegistry.EXPERIMENTAL] 的 13 个**只按名字猜**，未验证。
 * 直接放进模型库 = 把未验证风险变成默认可达路径（用户听到对调的人声/伴奏）。
 *
 * ## 判据
 * 用一个**参考模型**（[ModelRegistry.SPLEETER]，已知 stem 顺序正确）的人声作锚，
 * 跑两个模型各自分离同一段音频，比较：
 * ```
 * nccSame = corr(probe.stem0, ref.vocals)      // 高 = probe.stem0 也是人声 → 顺序正确
 * nccSwap = corr(probe.stem0, ref.accomp)      // 高 = probe.stem0 其实是伴奏 → 需交换
 * ```
 * `nccSwap > nccSame` ⇒ 该模型应标 `ACCOMP_FIRST`。
 *
 * ## 与阶段 M 的做法一致
 * `Inst_HQ_3.vocals ↔ Voc_FT.accompaniment NCC = +0.992` vs `↔ Voc_FT.vocals = +0.032`
 * —— 同一套思路，只是这里用 Spleeter 当参考（避免参考模型自己也需要交换）。
 *
 * ## 局限（必须在报告里写明）
 * - 不同模型音质不同 → NCC 不会到 0.99，但**两个方向的差值**足以判定
 *   （判据：`|nccSwap − nccSame| > MIN_MARGIN` 才采信，否则保留原标注并标“证据不足”）。
 * - 只验证**相对顺序**，不验证音质。
 */
class StemOrderProbe(private val app: Application) {

    /** 单个模型的结果。 */
    data class Result(
        val modelId: String,
        /** probe.stem0 与参考人声的相关（顺序正确 → 高）。 */
        val nccSame: Double,
        /** probe.stem0 与参考伴奏的相关（顺序反了 → 高）。 */
        val nccSwap: Double,
        /** 判定出的顺序。 */
        val verdict: StemOrder,
        /** 该判定有多可信（|差值|）。 */
        val margin: Double,
        val error: String? = null,
    ) {
        /** 是否值得信任（差值 >= [MIN_MARGIN] 且无错误）。 */
        val confident: Boolean get() = error == null && margin >= MIN_MARGIN
    }

    /**
     * 跑一组模型的实证。
     *
     * @param source 测试音频（建议 20–30s：够长让相关稳定，又不会跑太久）
     * @param models 要验的模型（默认全部实验模型）
     * @param onProgress (done, total, modelId)
     */
    suspend fun run(
        source: File,
        models: List<ModelSpec> = ModelRegistry.EXPERIMENTAL,
        onProgress: ((Int, Int, String) -> Unit)? = null,
    ): List<Result> = withContext(Dispatchers.Default) {
        // 1) 参考：Spleeter 2-stem（已知顺序正确）
        val ref = runCatching { separateWith(ModelRegistry.SPLEETER, source) }
            .getOrElse {
                Log.e(TAG, "reference (spleeter) failed", it)
                return@withContext models.map { spec ->
                    Result(spec.id, 0.0, 0.0, spec.stemOrder, 0.0, "参考模型分离失败: ${it.message}")
                }
            }
        Log.i(TAG, "reference ready: ${ref.vocals.size / 2} frames")

        // 2) 逐个 probe
        val out = ArrayList<Result>(models.size)
        models.forEachIndexed { idx, spec ->
            onProgress?.invoke(idx, models.size, spec.id)
            if (!spec.isInstalled(app)) {
                out += Result(spec.id, 0.0, 0.0, spec.stemOrder, 0.0, "未安装")
                return@forEachIndexed
            }
            // ★注意：separateWith 返回**运行时原始顺序**（不按 stemOrder 交换）——
            //   我们要测的就是“原始输出长什么样”。
            val r = runCatching { separateWith(spec, source) }.getOrElse {
                out += Result(spec.id, 0.0, 0.0, spec.stemOrder, 0.0, it.message ?: "分离失败")
                return@forEachIndexed
            }
            val nSame = ncc(r.vocals, ref.vocals)
            val nSwap = ncc(r.vocals, ref.accompaniment)
            val margin = abs(nSwap - nSame)
            val verdict = if (margin >= MIN_MARGIN) {
                if (nSwap > nSame) StemOrder.ACCOMP_FIRST else StemOrder.VOCALS_FIRST
            } else {
                spec.stemOrder   // 证据不足 → 保留现有（按名字猜的）标注
            }
            out += Result(spec.id, nSame, nSwap, verdict, margin)
            Log.i(
                TAG,
                "ncc ${spec.id}: same=${fmt(nSame)} swap=${fmt(nSwap)} margin=${fmt(margin)}" +
                    " → ${verdict}" +
                    if (margin >= MIN_MARGIN) "" else "  (证据不足，保留原标注 ${spec.stemOrder})",
            )
        }
        onProgress?.invoke(models.size, models.size, "")
        out
    }

    // ---------------- 内部 ----------------

    private data class Stems(val vocals: FloatArray, val accompaniment: FloatArray)

    /** 用 [spec] 分离 [source] 的前 [MAX_SECONDS] 秒，返回**运行时原始**两轨（交织 float）。 */
    private fun separateWith(spec: ModelSpec, source: File): Stems {
        val input = SeparationInput.open(app, source)
        try {
            val sr = input.sampleRate
            val ch = input.channels
            val limitFrames = minOf(input.totalFrames, MAX_SECONDS * sr)
            val block = input.readFrames(0, limitFrames)
            val (vPath, aPath) = resolveModels(spec)
            val engine = SherpaSourceSeparation.create(
                vPath, aPath, numThreads = 2, mode = spec.engine,
            )
            try {
                val r = engine.separate(block, ch, sr)
                return Stems(r.vocals, r.accompaniment)
            } finally {
                engine.release()
            }
        } finally {
            input.close()
        }
    }

    private fun resolveModels(spec: ModelSpec): Pair<String, String?> {
        val dir = File(
            app.filesDir,
            if (spec.engine == SherpaSourceSeparation.MODE_UVR) "uvr" else "sherpa",
        )
        val main = File(dir, spec.fileName)
        val accomp = spec.spleeterAccompFile?.let { File(dir, it).absolutePath }
        return main.absolutePath to accomp
    }

    companion object {
        private const val TAG = "StemOrderProbe"

        /** 只跑前 N 秒（20s 足够让相关稳定，又不会让 13 个模型跑太久）。 */
        private const val MAX_SECONDS = 20

        /**
         * 判定阈值：两个方向的 NCC 差值需 >= 此值才采信。
         * 低于它 = 证据不足（两轨都不像参考的任何一轨，通常是模型音质差异过大）。
         */
        const val MIN_MARGIN = 0.10

        private fun fmt(x: Double) = "%.3f".format(x)

        /**
         * 归一化互相关（逐样本，两轨等长）。
         *
         * 返回 [-1, 1]；1 = 完全同相。每隔 [STRIDE] 个样本取一点
         * （够统计且快 16×；NCC 是统计量，不需要逐样本）。
         */
        fun ncc(a: FloatArray, b: FloatArray): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0.0
            var sAB = 0.0
            var sAA = 0.0
            var sBB = 0.0
            var i = 0
            while (i < n) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                sAB += x * y
                sAA += x * x
                sBB += y * y
                i += STRIDE
            }
            val den = sqrt(sAA * sBB)
            return if (den < 1e-12) 0.0 else sAB / den
        }

        private const val STRIDE = 16
    }
}
