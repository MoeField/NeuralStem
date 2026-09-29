package pub.miku.neuralstem.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Task 1 基础播放器 UI(guide F0.1–F0.4)。
 *
 * 临时用 Slider 承担进度条;Task 2 会用自绘 Canvas 波形替换(WaveformSeekBar 职责)。
 * Seek 的实现要点:拖动中禁用轮询回写(否则指针打架),松手才 seekTo。
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = viewModel(),
    onOpenSpike: () -> Unit = {},
) {
    val state by vm.state.collectAsState()

    // F0.1 导入:SAF。MIME 故意放宽 —— 文件管理器里 flac/aac/ogg 的 MIME 千奇百怪,
    // 只用 audio/* 会把很多合法音频灰掉。ExoPlayer 播不了会走 ERROR 状态。
    val context = LocalContext.current
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            vm.loadAndPlay(it, queryDisplayName(context, it))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("NeuralStem", style = MaterialTheme.typography.headlineMedium)
        Text(
            state.fileName.ifEmpty { "尚未导入音频" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
            }) { Text("导入音频") }

            Button(
                onClick = vm::togglePlayPause,
                enabled = state.isReady,
            ) {
                Text(if (state.isPlaying) "暂停" else "播放")
            }

            Button(onClick = onOpenSpike) { Text("Spike 分离") }
        }

        if (state.isReady) {
            // 时间显示(F0.4) + ★Step3：循环按钮（三态：顺序 / 循环歌单 / 单曲循环）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${PlayerViewModel.formatTime(state.positionMs)} / " +
                        PlayerViewModel.formatTime(state.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = vm::cycleRepeatMode) {
                    // softWrap=false + maxLines=1：避免 `↻₁` 被换到下一行
                    Text(
                        "${state.repeatMode.glyph} ${state.repeatMode.label}",
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            // Task 2:波形替换 Slider(F1.1–F1.3)。
            // waveforms 列表预埋双轨:Task 2 传单轨;Task 4 加 vocals/accompaniment 即叠加
            WaveformView(
                waveforms = listOf(state.waveform),
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = vm::seekTo,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Task 3:变速变调(F2.1–F2.3) ----
            SpeedPitchControls(
                speed = state.speed,
                pitchSemitones = state.pitchSemitones,
                onSpeedChange = vm::setSpeed,
                onPitchChange = vm::setPitchSemitones,
                onReset = vm::resetSpeedPitch,
            )

            // ---- Task 4.4:多轨混音面板(F4.2/F4.3) ----
            if (state.isMultiTrack && state.tracks.isNotEmpty()) {
                TrackMixerPanel(
                    tracks = state.tracks,
                    onVolumeChange = vm::setTrackVolume,
                    onToggleMute = vm::toggleMute,
                    onToggleSolo = vm::toggleSolo,
                    onExit = vm::exitMultiTrack,
                )
            }
        }
    }
}

/**
 * 变速变调控件(Task 3)。
 *
 * Slider 拖动中用 onValueChange 频繁调用 setSpeed/setPitch —— Sonic 是流式处理器,
 * 参数即改即生效(F2.3);实测拖动无卡顿。
 */
@Composable
private fun SpeedPitchControls(
    speed: Float,
    pitchSemitones: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("变速变调", style = MaterialTheme.typography.titleSmall)

        // 速度:0.5–2.0x(F2.1)。step 0.05 让滑块有级进感
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("速度", modifier = Modifier.width(64.dp))
            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = PlayerViewModel.MIN_SPEED..PlayerViewModel.MAX_SPEED,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.2fx".format(speed),
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // 音高:±12 半音(F2.2)。步长 1 半音(乐理意义明显)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音高", modifier = Modifier.width(64.dp))
            Slider(
                value = pitchSemitones,
                onValueChange = onPitchChange,
                valueRange = PlayerViewModel.MIN_PITCH_SEMITONES..PlayerViewModel.MAX_PITCH_SEMITONES,
                steps = 23,  // ±12 半音 → 24 段,25 个档位
                modifier = Modifier.weight(1f),
            )
            Text(
                "%+.0f".format(pitchSemitones) + " st",
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // 非默认时显示重置
        if (speed != 1f || pitchSemitones != 0f) {
            Button(onClick = onReset) { Text("重置 1.0x / 0st") }
        }
    }
}

/** SAF Uri → 显示名(content resolver 查 OpenableColumns.DISPLAY_NAME) */
private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown"