package pub.miku.neuralstem.spike

import android.util.Log
import java.io.File

/**
 * 阶段 M4 / S2：ONNX Runtime **真机探针**（自写 JNI）。
 *
 * ## 为什么自写 JNI（而非用 Maven 的 onnxruntime-android）
 * sherpa AAR 自带 `libonnxruntime.so`(**1.28.2**)，而 Maven `onnxruntime-android`
 * 的 Java 绑定要求**完全同版**的版本化符号（`VERS_x.y.z`）——实测 dlopen 失败。
 * ⇒ 改为自写 JNI，**链接 sherpa 那份 .so**（符号版本天然一致）。
 *
 * ## 本次修正（重要）
 * 上一版探针给模型喂的是**合成垃圾数据**（三角波），结果是 `Run()` 里立即 SIGBUS。
 * 但垃圾 STFT 会触发 NaN → 非法索引 → 未对齐访问，**无法据此判断 ORT 是否可用**。
 * 本版改为：读一段**真实 WAV** → 用 [DemucsStft] 算**真实 STFT** → 喂给模型。
 * 若仍崩，才说明 ORT 真有算子问题。
 *
 * 见 `app/src/main/cpp/ort_probe.c` 与 `doc/.memory.md` §阶段 M4-S2。
 */
object OrtProbe {

    private const val TAG = "OrtProbe"

    /** htdemucs 固定窗长。 */
    const val WAVE_LEN = 343_980

    init {
        // 必须先加载 libonnxruntime.so（sherpa 那份），本库的 OrtGetApiBase 依赖它
        System.loadLibrary("onnxruntime")
        System.loadLibrary("neuralstem_ort")
    }

    /** 传入**真实**波形与 STFT，跑一次推理，返回摘要字符串。 */
    private external fun nativeProbeReal(
        modelPath: String,
        wave: FloatArray,
        spec: FloatArray,
    ): String

    /**
     * 便捷入口：模型放 `filesDir/demucs/htdemucs_embedded.onnx`，
     * 音频放 `filesDir/demucs/probe_input.wav`（可 adb push）。
     */
    fun probeWithRealAudio(filesDir: File): String {
        val model = File(filesDir, "demucs/htdemucs_embedded.onnx")
        val wav = File(filesDir, "demucs/probe_input.wav")
        if (!model.exists()) return "ORT_SKIP 模型不存在: ${model.absolutePath}".also { Log.e(TAG, it) }
        if (!wav.exists()) return "ORT_SKIP 音频不存在: ${wav.absolutePath}".also { Log.e(TAG, it) }

        return try {
            // 1) 读 WAV（PCM16 立体声），取前 WAVE_LEN 帧；不足则零填充
            val info = WavIo.probe(wav)
            Log.i(TAG, "wav: ch=${info.channels} sr=${info.sampleRate} frames=${info.totalFrames}")
            val planar = readPlanar(wav, info, WAVE_LEN)

            // 2) 真实 STFT（契约见 DemucsStft）
            val t0 = System.currentTimeMillis()
            val spec = DemucsStft.stftPacked(planar, WAVE_LEN)
            Log.i(TAG, "stft done in ${System.currentTimeMillis() - t0}ms, spec.size=${spec.size}, " +
                "expected=${4 * DemucsStft.BINS * DemucsStft.frameCount(WAVE_LEN)}")

            // 3) 交错波形 [ch0[0], ch1[0], ch0[1], ...]（native 侧按 [1,2,N] 行优先读）
            val wave = FloatArray(2 * WAVE_LEN)
            for (i in 0 until WAVE_LEN) {
                wave[i] = planar[0][i]
                wave[WAVE_LEN + i] = planar[1][i]
            }

            val r = nativeProbeReal(model.absolutePath, wave, spec)
            Log.i(TAG, "PROBE_RESULT $r")
            r
        } catch (t: Throwable) {
            val msg = "ORT_FAIL ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, msg, t)
            msg
        }
    }

    /** 读 WAV 为**平面** 2 声道；缺声道时复制；不足 WAVE_LEN 补零。 */
    private fun readPlanar(wav: File, info: WavIo.WavInfo, want: Int): Array<FloatArray> {
        val n = minOf(want, info.totalFrames)
        val planar = Array(2) { FloatArray(want) }
        WavReader(wav).use { r ->
            val interleaved: FloatArray = r.readFrames(0, n) // 交织 [ch, ch, ...]，长度 n*channels
            for (i in 0 until n) {
                if (info.channels == 1) {
                    planar[0][i] = interleaved[i]
                    planar[1][i] = interleaved[i]
                } else {
                    planar[0][i] = interleaved[i * 2]
                    planar[1][i] = interleaved[i * 2 + 1]
                }
            }
        }
        return planar
    }
}
