package pub.miku.neuralstem.player

/**
 * v0.4.0 spike：Rubber Band C API 的 JNI 声明。
 *
 * 仅在 `RubberBandStage` 里使用；调用方必须保证单线程（渲染线程）。
 * 许可：编译进来的 RB 源码为 GPL-3.0-or-later。
 */
internal object RubberBandNative {
    /** 该构建是否包含 RB（`-Pwith_rubberband=true`）。 */
    val available: Boolean = pub.miku.neuralstem.BuildConfig.WITH_RUBBERBAND

    init {
        // 默认 MIT 构建不含 .so；此时不 load，调用方必须先查 [available]。
        if (available) System.loadLibrary("rubberband_jni")
    }

    /** @return 0 = 创建失败。 */
    external fun create(
        sampleRate: Int,
        channels: Int,
        options: Int,
        timeRatio: Float,
        pitchScale: Float,
    ): Long

    external fun delete(handle: Long)
    external fun reset(handle: Long)
    external fun setTimeRatio(handle: Long, ratio: Float)
    external fun setPitchScale(handle: Long, scale: Float)

    /** @param interleaved `frames * channels` 个 float。 */
    external fun process(handle: Long, interleaved: FloatArray, frames: Int, final: Boolean)

    external fun available(handle: Long): Int

    /** @return 实际取出的帧数（可能小于 [frames]）。 */
    external fun retrieve(handle: Long, out: FloatArray, frames: Int): Int

    external fun latency(handle: Long): Int

    // RubberBandOption 位（来自 rubberband-c.h）
    const val PROCESS_REAL_TIME = 0x00000001
    const val WINDOW_STANDARD = 0x00000000
    const val WINDOW_SHORT = 0x00100000
    const val WINDOW_LONG = 0x00200000
    const val PITCH_HIGH_SPEED = 0x00000000
    const val PITCH_HIGH_QUALITY = 0x02000000
    const val PITCH_HIGH_CONSISTENCY = 0x04000000
}
