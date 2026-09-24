package pub.miku.neuralstem.spike

import android.util.Log

/**
 * sherpa-onnx 音源分离 JNI 薄胶水(Task 0b,Spike)。
 *
 * 背景见 doc/research_separation.md §5 与 doc/.memory.md:
 * - 选型从 "Spleeter→TFLite+Flex" 切换到 "Spleeter→ONNX via sherpa-onnx"
 *   理由(guide §八.1):官方预转换 ONNX 模型可直接下载,免去本地 Python 转换链;
 *   sherpa-onnx AAR 自带 onnxruntime,STFT/mask 在框架 C++ 层,无需端上频谱代码
 * - sherpa-onnx 的 Kotlin API 尚未暴露 source separation(只有 C/C++/dotnet/go),
 *   故用 ~80 行 C 胶水直连其 C API,链接 AAR 内的 libsherpa-onnx-c-api.so
 *
 * 模型(用户需放置,或由 build 脚本拷贝):
 *   filesDir/sherpa/vocals.fp16.onnx
 *   filesDir/sherpa/accompaniment.fp16.onnx
 * 来源:HuggingFace csukuangfj/sherpa-onnx-spleeter-2stems-fp16(35MB,MIT)
 */
class SherpaSourceSeparation private constructor(
    private val nativeHandle: Long,
) {
    private var released = false

    /** 对整段音频做分离(内部由 sherpa-onnx 处理分块/overlap-add)。 */
    fun separate(interleaved: FloatArray, channels: Int, sampleRate: Int): SeparationResult {
        check(!released) { "already released" }
        require(channels == 1 || channels == 2) { "only mono/stereo" }

        // 交织 → 按通道平面(float**),C API 要求每通道连续
        val frames = interleaved.size / channels
        val planar = Array(channels) { FloatArray(frames) }
        for (i in interleaved.indices) {
            planar[i % channels][i / channels] = interleaved[i]
        }

        val stems = nativeSeparate(nativeHandle, planar, channels, frames, sampleRate)
        return stems
    }

    fun release() {
        if (!released) {
            Log.i(TAG, "release engine")
            nativeRelease(nativeHandle)
            released = true
        }
    }

    protected fun finalize() {
        release()
    }

    data class SeparationResult(
        val vocals: FloatArray,       // 交织立体声
        val accompaniment: FloatArray,
        val sampleRate: Int,
    )

    // ---- native ----
    private external fun nativeSeparate(
        handle: Long,
        planar: Array<FloatArray>,
        channels: Int,
        frames: Int,
        sampleRate: Int,
    ): SeparationResult

    private external fun nativeRelease(handle: Long)

    companion object {
        private const val TAG = "SherpaSep"

        init {
            Log.i(TAG, "loading native libs: onnxruntime, sherpa-onnx-c-api, neuralstem_spike")
            // AAR 会带上 libonnxruntime.so / libsherpa-onnx-c-api.so / libsherpa-onnx-jni.so;
            // 我们的胶水库链接 c-api,加载顺序:onnxruntime → c-api → 胶水
            System.loadLibrary("onnxruntime")
            System.loadLibrary("sherpa-onnx-c-api")
            System.loadLibrary("neuralstem_spike")
        }

        /**
         * 创建分离引擎。
         * @param vocalsPath Spleeter: vocals ONNX 路径 / UVR: 单模型 ONNX 路径
         * @param accompPath Spleeter: accompaniment ONNX 路径 / UVR: 传 null
         * @param numThreads onnxruntime CPU 线程数
         * @param mode 0=Spleeter(双模型) 1=UVR-MDX(单模型)。UVR 对比实验(Task 0f)引入
         */
        fun create(
            vocalsPath: String,
            accompPath: String?,
            numThreads: Int = 4,
            mode: Int = MODE_SPLEETER,
        ): SherpaSourceSeparation {
            Log.i(
                TAG,
                "create(mode=$mode, vocals=$vocalsPath, accomp=$accompPath, threads=$numThreads)",
            )
            val h = nativeCreate(vocalsPath, accompPath, numThreads, mode)
            if (h == 0L) {
                error("sherpa-onnx create failed, check model paths: $vocalsPath / $accompPath")
            }
            Log.i(TAG, "engine created, threads=$numThreads")
            return SherpaSourceSeparation(h)
        }

        /** 引擎类型: Spleeter 双模型(vocals+accompaniment) */
        const val MODE_SPLEETER = 0

        /** 引擎类型: UVR-MDX 单模型(vocals + 残差法 non-vocals) */
        const val MODE_UVR = 1

        @JvmStatic
        private external fun nativeCreate(
            vocalsPath: String,
            accompPath: String?,
            numThreads: Int,
            mode: Int,
        ): Long
    }
}
