package pub.miku.neuralstem.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放器构建工厂（R2 / bug C）。
 *
 * ## 根因（真机实测 + media3 源码确证；详见 .critical_steps.md 坑 48）
 * 短时间多次改 speed/pitch 时，`PlaybackParameters` 走 **SonicAudioProcessor（PCM 域）**：
 * `Sonic.flush()` 会把 `inputFrameCount=0`（丢弃已攒输入），而 `Sonic.changeSpeed()` 是
 * **块处理**（攒不满 `maxRequiredFrameCount` 就不吐数据）。频繁改参 → Sonic 反复被清空
 * → `getCurrentPosition()` 长时间不推进 → media3 内建 `StuckPlayerDetector` 判卡死
 * → `ERROR_CODE_TIMEOUT`。
 *
 * ## 当前方案（方案 3，实测可行）：AudioTrack 原生变速变调
 * `setEnableAudioOutputPlaybackParameters(true)` 让 `DefaultAudioSink` **绕过 Sonic**，
 * 由原生 `AudioTrack.setPlaybackParams()` 接管 speed 与 pitch。
 * - ✅ 真机压力序列（20s 内 6 次变速变调）**不再卡死**。
 * - ⚠️ **取舍**：原生 speed/pitch 是**重采样级**实现 → **低速(<1x)音质明显降级**。
 *
 * ## SoundTouch 替换 Sonic 的尝试（已废弃/暂缓，见 research_timescale.artifact.md）
 * 曾用 `SoundTouchAudioProcessor` + 自定义 `AudioProcessorChain` 替换 Sonic（float / 16-bit
 * 两条路线均失败）：
 * - float：媒体 3 在 BF00A 与骁龙 8 Gen3 上 `getFormatSupport(PCM_FLOAT)` 均=2（需转码）
 *   ⇒ 解码器不吐 float ⇒ 不可用。
 * - 16-bit：`onConfigure` 可通过，但**集成到 ExoPlayer 后只吐第一批 ~1353 帧就死锁**——
 *   SoundTouch“攒够才吐”与 media3 的“背压”节奏冲突（`audio_flinger`：FrmRdy 恒定、
 *   Underruns=0）。且真机听感“加速后音质很破碎，音调没法用”。
 * ⇒ **放弃 SoundTouch（类保留在仓库未接线）**。
 *
 * 单轨（[PlaybackService]）与多轨（[MultiTrackController]）**共用本工厂**，路径一致。
 *
 * ## ★ 已知限制：MP3 某些位置 seek 后卡死（ERROR_CODE_TIMEOUT）—— 设备解码器缺陷，App 层无法根治
 * **现象**：播放中 seek 到某些 MP3 位置（本例 CBR mp3 的 ~62s）→ 10s 无进展 →
 *   `StuckPlayerDetector` 报 `ERROR_CODE_TIMEOUT` → 由 `recoverFromAudioSinkTimeout()` 重建
 *   pipeline 自恢复（有 ~10s 卡顿）。
 * **实测（T0 命令总线稳定复现）**：
 *   - audio_flinger 卡窗内 `0 active tracks / FrmRdy=0`（确实无帧，非误判）；
 *   - ffmpeg 同位置解码无错（文件未损坏）；
 *   - wav 同位置不卡（定位为 MP3 解码相关）；
 *   - `setConstantBitrateSeekingEnabled(true)` **无效**（定位无问题）；
 *   - 自定义 `MediaCodecSelector` 优先厂商解码器 **无效** —— 实测
 *     `DEFAULT.getDecoderInfos("audio/mpeg")` 在该设备**只返回软件解码器**
 *     `c2.android.mp3.decoder`（无 `c2.mtk.*` 候选可选）。
 * **结论**：设备唯一可用的**软件 MP3 解码器在特定 seek 后挂起**（`Handler on a dead thread`）
 *   —— 系统/厂商编解码器缺陷，App 层无解码器可换。
 * **对策**：保留现有 `recoverFromAudioSinkTimeout()` 兜底（可恢复）。如需根治，
 *   只能引第三方软解（如 FFmpeg 扩展）或等系统修复。详见 `.critical_steps.md`。
 */
@UnstableApi
fun buildRenderersFactory(context: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(context)
        // ★ 方案 3：用 AudioTrack 原生 playback params（speed + pitch）→ 绕过 Sonic
        .setEnableAudioOutputPlaybackParameters(true)

/**
 * 统一的 [ExoPlayer] 构建器：单轨/多轨共用，确保音频渲染配置一致。
 *
 * @param handleAudioFocus 仅主轨应为 true（坑 37：多 ExoPlayer 抢焦点）
 */
@UnstableApi
fun buildAudioExoPlayer(
    context: Context,
    handleAudioFocus: Boolean,
): ExoPlayer =
    ExoPlayer.Builder(context, buildRenderersFactory(context))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            handleAudioFocus,
        )
        .build()
