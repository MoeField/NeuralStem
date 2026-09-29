package pub.miku.neuralstem.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain

/**
 * 自定义 [AudioProcessorChain]：用 SoundTouch **替换** media3 内置 Sonic（R2/bug C 最小验证）。
 *
 * ## 为什么必须用 chain（而不是 setAudioProcessors）
 * `DefaultAudioSink.DefaultAudioProcessorChain` 构造器**永远**把 SonicAudioProcessor 追加到
 * 链尾，`setAudioProcessors(...)` 无法移除它（会导致 SoundTouch + Sonic 双重变速）。
 * 只有 `DefaultAudioSink.Builder.setAudioProcessorChain(...)` 能提供一条**不含 Sonic** 的链。
 * （源码确证，见 `research_timescale.artifact.md` §二.6）
 *
 * ## 改参入口
 * media3 每次 `PlaybackParameters` 变更会调用 [applyPlaybackParameters]
 * （仅在处理器无待处理输入时调用）→ 这里把速率/音高下发给 SoundTouch
 * （**运行期改参，不重建**，这是绕开 Sonic 饥饿的关键）。
 */
@UnstableApi
class SoundTouchAudioProcessorChain(
    private val soundTouchProcessor: SoundTouchAudioProcessor,
) : AudioProcessorChain {

    /** 链里**只有** SoundTouch（不含 Sonic，故不会双重变速）。 */
    private val processors: Array<AudioProcessor> = arrayOf(soundTouchProcessor)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        // 直接下发；SoundTouch 的 speed/pitch 是倍率，与 PlaybackParameters 语义一致。
        soundTouchProcessor.setSpeedPitch(playbackParameters.speed, playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        // 本链不做静音跳过
        return false
    }

    override fun getMediaDuration(playoutDuration: Long): Long {
        // SoundTouch: 媒体时长 = 播放时长 × speed（速度只改 tempo，不改音高）
        // speed 越大，媒体时间轴走得越快。
        return (playoutDuration * soundTouchProcessor.currentSpeed).toLong()
    }

    override fun getSkippedOutputFrameCount(): Long = 0L
}
