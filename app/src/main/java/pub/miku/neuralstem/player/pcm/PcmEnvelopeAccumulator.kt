package pub.miku.neuralstem.player.pcm

import pub.miku.neuralstem.player.WaveformData

/**
 * 共享 PCM 解码时的实时包络累加器（50 点/秒）。
 *
 * 输入是交织 Float PCM（pipeline 已 downmix 为 stereo）；
 * 只读第 0 声道计算 min/max，和旧 WaveformExtractor 的包络算法一致。
 */
class PcmEnvelopeAccumulator {
    private val min = ArrayList<Float>(4096)
    private val max = ArrayList<Float>(4096)
    private var framesPerPoint = 0
    private var sampleRate = 0
    private var curLo = Float.MAX_VALUE
    private var curHi = -Float.MAX_VALUE
    private var curFrames = 0
    private var totalFrames = 0L

    fun onChunk(samples: FloatArray, channels: Int, sr: Int): WaveformData? {
        if (channels <= 0 || samples.isEmpty()) return snapshot()
        if (framesPerPoint == 0) {
            sampleRate = sr
            framesPerPoint = (sr / POINTS_PER_SECOND).coerceAtLeast(1)
        }
        var i = 0
        while (i < samples.size) {
            val v = samples[i]
            if (v < curLo) curLo = v
            if (v > curHi) curHi = v
            curFrames++
            if (curFrames >= framesPerPoint) flushPoint()
            i += channels
        }
        totalFrames += (samples.size / channels).toLong()
        return snapshot()
    }

    fun finish(): WaveformData? {
        if (curFrames > 0) flushPoint()
        return snapshot()
    }

    private fun flushPoint() {
        min.add(if (curLo == Float.MAX_VALUE) 0f else curLo)
        max.add(if (curHi == -Float.MAX_VALUE) 0f else curHi)
        curLo = Float.MAX_VALUE
        curHi = -Float.MAX_VALUE
        curFrames = 0
    }

    private fun snapshot(): WaveformData? {
        if (sampleRate <= 0 || min.isEmpty()) return null
        return WaveformData(
            min = min.toFloatArray(),
            max = max.toFloatArray(),
            sampleRate = sampleRate,
            totalFrames = totalFrames,
        )
    }

    companion object {
        const val POINTS_PER_SECOND = 50
    }
}
