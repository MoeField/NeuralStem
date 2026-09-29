package pub.miku.neuralstem.player

/**
 * SoundTouch 2.4.1 的 JNI 绑定（R2/bug C 最小验证）。
 *
 * 用途：替换 media3 内置 Sonic —— SoundTouch 的 `setTempo/setPitch` **运行期改参不重建、
 * 不重置内部缓冲**（源码确证，见 `research_timescale.artifact.md`），
 * 可避开 Sonic 的“频繁改参→预填充饥饿→StuckPlayerDetector→ERROR_CODE_TIMEOUT”。
 *
 * ⚠️ LGPL-2.1：源码见 `app/src/main/cpp/soundtouch/`（含 `COPYING.TXT`）。
 *
 * 契约：**非线程安全**，单实例只在同一线程（AudioProcessor 的 audio 线程）使用。
 * 样例为 **16-bit 交错 int PCM**（CMake 定义 SOUNDTOUCH_INTEGER_SAMPLES；
 * media3 对 ENCODING_PCM_16BIT 永远 FORMAT_SUPPORTED_DIRECTLY）。
 */
internal class SoundTouchNative(
    private val sampleRate: Int,
    private val channels: Int,
) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate, channels)
    private var closed = false

    private fun requireHandle(): Long {
        check(handle != 0L) { "SoundTouchNative not created (sampleRate=$sampleRate ch=$channels)" }
        return handle
    }

    /** 变速（不变调）。1.0 = 原速。 */
    fun setTempo(tempo: Double) = nativeSetTempo(requireHandle(), tempo)

    /** 变调（不变速）。1.0 = 原调；半音用 PlayerViewModel.semitonesToRate 换算。 */
    fun setPitch(pitch: Double) = nativeSetPitch(requireHandle(), pitch)

    /** 投入交错 16-bit int PCM。[count] 为“样本数”（frame × channels）。 */
    fun putSamples(samples: ShortArray, offset: Int, count: Int) =
        nativePutSamples(requireHandle(), samples, offset, count)

    /** 取出已处理样本，返回实际数量；0 = 暂无输出。 */
    fun receiveSamples(out: ShortArray, offset: Int, maxCount: Int): Int =
        nativeReceiveSamples(requireHandle(), out, offset, maxCount)

    /** EOS：冲刷流水线尾部残留。 */
    fun flush() = nativeFlush(requireHandle())

    /** 当前输出队列“就绪”样本数。 */
    fun numSamples(): Int = nativeNumSamples(requireHandle())

    /** 建议的输入块样本数（反映处理延迟）。 */
    fun inputSampleReq(): Int = nativeGetInputSampleReq(requireHandle())

    override fun close() {
        if (!closed) {
            closed = true
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetTempo(handle: Long, tempo: Double)
    private external fun nativeSetPitch(handle: Long, pitch: Double)
    private external fun nativePutSamples(handle: Long, samples: ShortArray, offset: Int, count: Int)
    private external fun nativeReceiveSamples(
        handle: Long, out: ShortArray, offset: Int, maxCount: Int,
    ): Int
    private external fun nativeFlush(handle: Long)
    private external fun nativeNumSamples(handle: Long): Int
    private external fun nativeGetInputSampleReq(handle: Long): Int

    private companion object {
        init {
            System.loadLibrary("neuralstem_soundtouch")
        }
    }
}
