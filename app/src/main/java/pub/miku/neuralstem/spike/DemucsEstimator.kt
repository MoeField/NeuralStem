package pub.miku.neuralstem.spike

import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * 阶段 M4 / S2 落地：Demucs（htdemucs）**估计器**（句柄式，持有 ORT 会话）。
 *
 * ## 契约（一手核对，桌面已验）
 * ```
 * IN  input : [1, 2, 343980]        波形（固定 7.8s 窗）
 * IN  x     : [1, 4, 2048, 336]     STFT(波形)
 * OUT output: [1, 4, 4, 2048, 336]  频谱分支
 * OUT add_67: [1, 4, 2, 343980]     时域分支
 * ```
 * 最终每源波形 = `iSTFT(output) + add_67`（在 [DemucsSeparator] 里做）。
 */
class DemucsEstimator private constructor(private var handle: Long) : Closeable {

    private var released = false

    data class Output(
        /** 频谱分支 `[1,4,4,2048,336]`（展平）。 */
        val spec: FloatArray,
        /** 时域分支 `[1,4,2,343980]`（展平）。 */
        val add: FloatArray,
    )

    fun estimate(wave: FloatArray, spec: FloatArray): Output {
        check(!released) { "estimator released" }
        require(wave.size == 2 * WAVE_LEN) { "wave.size=${wave.size} != ${2 * WAVE_LEN} (2ch×$WAVE_LEN)" }
        require(spec.size == SPEC_LEN) { "spec.size=${spec.size} != $SPEC_LEN" }
        val r = nativeEstimate(handle, wave, spec) ?: error("nativeEstimate null")
        require(r.size == 2) { "expected 2 outputs, got ${r.size}" }
        return Output(r[0], r[1])
    }

    override fun close() {
        if (!released) {
            nativeRelease(handle)
            handle = 0L
            released = true
        }
    }

    private external fun nativeEstimate(handle: Long, wave: FloatArray, spec: FloatArray): Array<FloatArray>?

    companion object {
        private const val TAG = "DemucsEst"
        const val WAVE_LEN = 343_980
        const val SPEC_LEN = 4 * 2048 * 336

        init {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("neuralstem_ort")
        }

        @JvmStatic
        private external fun nativeCreate(modelPath: String, threads: Int, useNnapi: Boolean): Long

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        /**
         * @param useNnapi 尝试 NNAPI 执行提供者（骁龙 NPU）；不支持时自动回退 CPU。
         */
        fun create(modelPath: File, threads: Int = 6, useNnapi: Boolean = false): DemucsEstimator {
            require(modelPath.exists()) { "模型不存在: ${modelPath.absolutePath}" }
            val h = nativeCreate(modelPath.absolutePath, threads, useNnapi)
            if (h == 0L) error("demucs estimator create failed: ${modelPath.absolutePath}")
            Log.i(TAG, "created: ${modelPath.absolutePath} threads=$threads nnapi=$useNnapi")
            return DemucsEstimator(h)
        }
    }
}
