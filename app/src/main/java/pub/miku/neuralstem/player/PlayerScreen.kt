package pub.miku.neuralstem.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pub.miku.neuralstem.settings.SettingsRepository

/**
 * 播放页（**Step 6：R 系列转盘布局**）。
 *
 * 布局（对齐存档的 R1/R2/R3）：
 * ```
 *  ├ 歌名
 *  ├ 按钮（导入 / 播放暂停 / Spike 分离）
 *  ├ 转盘固定区：左栏[音调旋钮 + 竖向速度滑块 + 重置] + 黑胶转盘(三环)
 *  ├ 横向进度条 + [ -10s / 播放 / +10s / 循环 ]
 *  └ 底部可滚动：分轨混音（多轨时）
 * ```
 * ★ 进度流**不在本层 collect**（否则每 100ms 重组整页）→ 传给 [DeckView] / [ProgressBar] 内部 collect。
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = viewModel(),
    onOpenSpike: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    // ★ 进度流传引用即可（子组件内部 collect）。
    val progressFlow = vm.progressFlow

    val context = LocalContext.current
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // ★ 持久化读权限：否则进程重启/后续访问时该 content:// 会变得不可读
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.loadAndPlay(it, queryDisplayName(context, it))
        }
    }

    // 设置（转盘一圈时长 / 变速范围）
    val settings = remember { SettingsRepository(context.applicationContext as Application) }
    var secPerTurn by remember { mutableIntStateOf(settings.deckSecondsPerTurn) }
    LaunchedEffect(settings.deckSecondsPerTurn) { secPerTurn = settings.deckSecondsPerTurn }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 顶部：歌名 ----
        Text(
            state.fileName.ifEmpty { "尚未导入音频" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        // ---- 按钮 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
            }) { Text("导入音频") }

            Button(
                onClick = vm::togglePlayPause,
                // ★用 hasMedia（已载入）而非 isReady（会随 seek 抖动）。
                enabled = state.hasMedia,
            ) { Text(if (state.playWhenReady) "暂停" else "播放") }

            Button(onClick = onOpenSpike) { Text("Spike 分离") }
        }

        // ★ UI 门控用 hasMedia（“已载入”）。
        if (state.hasMedia) {
            // ---- 转盘固定区：左栏（R2） + 转盘（R3）----
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左栏：音调旋钮（上）+ 竖向速度滑块（下）+ 重置
                Column(
                    modifier = Modifier.width(84.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PitchKnob(
                        semitones = state.pitchSemitones,
                        onPitchChange = vm::setPitchSemitones,
                        knobSize = 48.dp,
                    )
                    VerticalSlider(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        value = state.speed,
                        onValueChange = vm::setSpeed,
                        // ★区间由设置项 X 决定 = [1/X, X]
                        valueRange = settings.speedSliderMin..settings.speedSliderMax,
                        label = { "%.2fx".format(it) },
                        // 连续无步进；1.0× 吸附；对数尺度（1.0 居中）
                        snapTargets = listOf(1f),
                        snapTolerance = 0.06f,
                        logScale = true,
                    )
                    Text(
                        "重置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.resetSpeedPitch() }
                            .padding(vertical = 6.dp),
                        textAlign = TextAlign.Center,
                    )
                }

                // R3：真实黑胶转盘（三环：外环整曲波形+seek / 中环局部滚动 / 内圈自转）
                DeckView(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    waveformProvider = { state.waveform },
                    cover = state.cover,
                    songName = state.fileName,
                    progressFlow = progressFlow,
                    anchorFlow = vm.anchorFlow,
                    speed = state.speed,
                    isPlaying = state.isPlaying,
                    secPerTurn = secPerTurn,
                    onTogglePlayPause = vm::togglePlayPause,
                    onSeekFraction = { f ->
                        val d = progressFlow.value.durationMs
                        val dur = if (d > 0) d else state.durationMs
                        if (dur > 0) vm.seekTo((f * dur).toLong())
                    },
                )
            }

            // ---- 横向进度条 + 控制 ----
            ProgressBar(
                progressFlow = progressFlow,
                anchorFlow = vm.anchorFlow,
                onSeek = vm::seekTo,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.seekTo((vm.progress.positionMs - 10_000).coerceAtLeast(0)) }) { Text("-10s") }
                Button(onClick = vm::togglePlayPause) { Text(if (state.playWhenReady) "暂停" else "播放") }
                Button(onClick = { vm.seekTo(vm.progress.positionMs + 10_000) }) { Text("+10s") }
                // ★循环按钮（三态）：字形 + 小字模式名（避免 '↻₁' 换行）
                Button(onClick = vm::cycleRepeatMode) {
                    Text(state.repeatMode.glyph, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                state.repeatMode.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- ★Step9：分离开关（原曲 ↔ 多轨）+ 指引 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.separationOn) "分离开关：多轨" else "分离开关：原曲",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = state.separationOn,
                    onCheckedChange = { vm.toggleSeparation(it) },
                )
            }
            Text(
                "去「分离」页处理 →",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSpike() }
                    .padding(vertical = 4.dp),
            )
        }

        // ---- 底部：分轨混音（多轨时）----
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            if (state.isMultiTrack && state.tracks.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
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
}

/** SAF Uri → 显示名(content resolver 查 OpenableColumns.DISPLAY_NAME) */
private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown"
