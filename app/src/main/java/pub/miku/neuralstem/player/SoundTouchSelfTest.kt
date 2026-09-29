package pub.miku.neuralstem.player

import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * SoundTouch JNI 烟测（R2/bug C 最小验证，不碰播放，纯 native）。
 *
 * 验证内容：
 * 1. JNI 可创建/关闭实例（库加载、包名+签名匹配）；
 * 2. 喂入正弦波（改速/改调），能取回输出且样本数合理（说明块处理与缓冲工作）；
 * 3. 高频改参（复刻 bug C 时间线）不抛异常、不卡死。
 *
 * 标签：`SoundTouchSelfTest`（logcat -s SoundTouchSelfTest）
 */
object SoundTouchSelfTest {

    private const val TAG = "SoundTouchSelfTest"

    fun run() {
        val sampleRate = 44100
        val channels = 1
        val chunk = 1024  // frame
        val totalFrames = sampleRate  // 1 秒

        Log.i(TAG, "START sampleRate=$sampleRate ch=$channels")

        try {
            SoundTouchNative(sampleRate, channels).use { st ->
                val inBuf = ShortArray(chunk)
                val outBuf = ShortArray(chunk * 4)
                var produced = 0L

                var speed = 1.0
                var pitch = 1.0
                st.setTempo(speed)
                st.setPitch(pitch)

                var frame = 0
                var changeCount = 0
                while (frame < totalFrames) {
                    // 生成正弦（440Hz）
                    for (i in 0 until chunk) {
                        val t = (frame + i).toDouble() / sampleRate
                        inBuf[i] = (sin(2.0 * PI * 440.0 * t) * 32767.0).toInt().toShort()
                    }
                    // ★ 高频改参：每 ~1/8 秒改一次（复刻 bug C）
                    if (frame % (chunk * 5) == 0) {
                        speed = when (changeCount % 4) {
                            0 -> 0.52; 1 -> 1.64; 2 -> 1.86; else -> 0.90
                        }
                        pitch = when (changeCount % 4) {
                            0 -> 1.0; 1 -> 0.749; 2 -> 1.587; else -> 1.414
                        }
                        st.setTempo(speed)
                        st.setPitch(pitch)
                        changeCount++
                        Log.i(TAG, "change#$changeCount speed=$speed pitch=$pitch")
                    }

                    st.putSamples(inBuf, 0, chunk)
                    var got: Int
                    while (true) {
                        got = st.receiveSamples(outBuf, 0, outBuf.size)
                        if (got <= 0) break
                        produced += got
                    }
                    frame += chunk
                }

                // EOS：flush 后取出残留
                st.flush()
                var tail = 0
                while (true) {
                    val got = st.receiveSamples(outBuf, 0, outBuf.size)
                    if (got <= 0) break
                    tail += got
                    produced += got
                }

                Log.i(TAG, "OK produced=$produced tail=$tail changes=$changeCount " +
                    "inputFrames=$totalFrames (no crash / no hang)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "FAILED", t)
        }
    }
}
