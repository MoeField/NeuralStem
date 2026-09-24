package pub.miku.neuralstem.spike

import android.util.Log
import java.io.File

/**
 * 阶段 M4 / S3：Spleeter **4-stem** ONNX 真机探针。
 *
 * ## 与 2-stem（sherpa 路径）的区别
 * sherpa 的 Spleeter 路径**只支持 2 模型**，且其 `unet.py` 硬编码 LeakyReLU/ReLU；
 * 4-stem 权重用 **ELU** 激活（架构/权形与 2-stem 完全相同，形状上**看不出来**，
 * 喂错激活会静默输出垃圾）。所以 4-stem 必须**自建 DSP**，走 ORT JNI（同 S2 的 Demucs）。
 *
 * ## 契约（一手核对自 ONNX 元数据）
 * ```
 * IN  x : [2, num_splits, 512, 1024]   幅度谱（1024 of 2049 bins）
 * OUT y : [2, num_splits, 512, 1024]   该 stem 的幅度估计
 * ```
 * 本探针固定 `num_splits=1`（8s 音频 = 348 帧 < 512），只验证「4 个模型能否依次跑通」。
 * 完整 mask/iSTFT 已在桌面 Python 验证（重建不变量 -163.5 dB，见 .memory.md §M4-S3）。
 */
object Spleeter4Probe {

    private const val TAG = "Spleeter4"
    const val SPLITS = 1
    const val FRAMES = 512
    const val BINS = 1024
    private const val IN_LEN = 2 * SPLITS * FRAMES * BINS   // 1,048,576

    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("neuralstem_ort")
    }

    /** 传模型目录 + 幅度谱（长度必须是 [IN_LEN]），依次跑 4 个模型。 */
    private external fun nativeProbe4(modelDir: String, mag: FloatArray): String

    /**
     * `modelDir` = `filesDir/spleeter4`，内含 `{vocals,drums,bass,other}.fp16.onnx`。
     * 本探针**不**算真实 STFT（只需证明“4 模型能依次在 ORT 上跑通”）；
     * 喂小幅确定性数据即可（S2 教训：不要喂随机/垃圾，用小幅度平滑值）。
     */
    fun probe(filesDir: File): String {
        val dir = File(filesDir, "spleeter4")
        val missing = listOf("vocals", "drums", "bass", "other")
            .filterNot { File(dir, "$it.fp16.onnx").exists() }
        if (missing.isNotEmpty()) {
            val msg = "SP4_SKIP 缺模型: $missing (dir=${dir.absolutePath})"
            Log.e(TAG, msg); return msg
        }
        return try {
            // 小幅确定性数据（避免极值触发异常；本步不验证 DSP 数值）
            val mag = FloatArray(IN_LEN) { i -> 0.01f * ((i % 128) - 64) / 64f }
            val r = nativeProbe4(dir.absolutePath, mag)
            Log.i(TAG, "PROBE_RESULT $r")
            r
        } catch (t: Throwable) {
            val msg = "SP4_FAIL ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, msg, t)
            msg
        }
    }

    internal fun expectedInputLen(): Int = IN_LEN
}
