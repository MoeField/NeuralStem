package pub.miku.neuralstem.spike

import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * 阶段 M4 / S3：Spleeter **4-stem 估计器**（句柄式，持有 4 个 ORT 会话）。
 *
 * ## 职责边界
 * - **本类**：`幅度谱 → 4 个幅度估计`（native，复用已建会话）
 * - [Spleeter4Dsp]：`波形 ↔ 频谱`、`ratio mask`、`带扩展`、`iSTFT`
 * - 组合见 `Spleeter4Separator`
 *
 * ## 契约（一手核对自 ONNX 元数据）
 * ```
 * 模型 IN  x : [2, num_splits, 512, 1024]   幅度谱（1024 of 2049 bins）
 * 模型 OUT y : [2, num_splits, 512, 1024]   该 stem 的幅度估计
 * ```
 * 输出按 stem 顺序 `[vocals, drums, bass, other]`，每个是长度
 * `2 * splits * 512 * 1024` 的行优先数组。
 */
class Spleeter4Estimator private constructor(private var handle: Long) : Closeable {

    private var released = false

    /**
     * 跑一次估计。
     * @param mag 幅度谱，长度必须是 `2 * splits * 512 * 1024`（由 [Spleeter4Dsp.magnitudeBlock] 产出）
     * @return 4 个 stem 的估计；顺序 `[vocals, drums, bass, other]`
     */
    fun estimate(mag: FloatArray, splits: Int): Array<FloatArray> {
        check(!released) { "estimator released" }
        val expected = 2 * splits * Spleeter4Dsp.SPLIT_FRAMES * Spleeter4Dsp.MODEL_BINS
        require(mag.size == expected) { "mag.size=${mag.size} != $expected" }
        val r = nativeEstimate(handle, mag, splits)
            ?: error("nativeEstimate returned null")
        require(r.size == 4) { "expected 4 stems, got ${r.size}" }
        return r
    }

    override fun close() {
        if (!released) {
            nativeRelease(handle)
            handle = 0L
            released = true
        }
    }

    private external fun nativeEstimate(handle: Long, mag: FloatArray, splits: Int): Array<FloatArray>?

    companion object {
        private const val TAG = "Spleeter4Est"

        init {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("neuralstem_ort")
        }

        @JvmStatic
        private external fun nativeCreate(modelDir: String, threads: Int): Long

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        /**
         * 建估计器（加载 4 个模型各一次）。
         * @param modelDir 含 `{vocals,drums,bass,other}.fp16.onnx` 的目录
         */
        fun create(modelDir: File, threads: Int = 4): Spleeter4Estimator {
            val missing = Spleeter4Dsp.STEM_KINDS
                .filterNot { File(modelDir, "$it.fp16.onnx").exists() }
            require(missing.isEmpty()) {
                "缺少模型 $missing（目录 ${modelDir.absolutePath}）"
            }
            val h = nativeCreate(modelDir.absolutePath, threads)
            if (h == 0L) error("estimator create failed: ${modelDir.absolutePath}")
            Log.i(TAG, "estimator created: ${modelDir.absolutePath} threads=$threads")
            return Spleeter4Estimator(h)
        }
    }
}
