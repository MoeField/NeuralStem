package pub.miku.neuralstem.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import pub.miku.neuralstem.settings.SettingsRepository

/**
 * 播放页（**Step 6：R 系列转盘布局**；**F5.2：横屏双栏**）。
 *
 * - **竖屏**：`歌名 / 按钮 / [左栏旋钮滑条 + 黑胶转盘] / 进度条 / 控制 / 分离开关 / 分轨混音`
 * - **横屏**：转盘（左） ‖ 控制（右） 并排 —— 避免竖向内容被压扁。
 * ★ 进度流**不在本层 collect**（否则每 100ms 重组整页）→ 传给子组件内部 collect。
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = viewModel(),
    onOpenSpike: () -> Unit = {},
    // ★R2：仅当选中“选择型（FILE）歌单”时可导入；文件夹歌单不提供导入。
    importEnabled: Boolean = true,
    // ★R2：导入成功后通知宿主（→ 追加进当前选中歌单）。
    onImported: (Uri, String) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsState()
    // ★F5.4：触感反馈（分离完成在分离页；这里用于 1.0× 吸附）。
    val haptic = LocalHapticFeedback.current
    // ★ 进度流传引用即可（子组件内部 collect）。
    val progressFlow = vm.progressFlow
    // ★F5.2：横屏判定。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = queryDisplayName(context, it)
            vm.loadAndPlay(it, name)
            // ★R2：导入同时追加进当前选中歌单（由宿主落到 PlaylistViewModel）。
            onImported(it, name)
        }
    }

    val settings = remember { SettingsRepository(context.applicationContext as Application) }
    var secPerTurn by remember { mutableIntStateOf(settings.deckSecondsPerTurn) }
    LaunchedEffect(settings.deckSecondsPerTurn) { secPerTurn = settings.deckSecondsPerTurn }
    val speedRange = settings.speedSliderMin..settings.speedSliderMax

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.fileName.ifEmpty { "尚未导入音频" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ★R2：文件夹歌单不导入（importEnabled=false 时隐藏）
            if (importEnabled) {
                Button(onClick = {
                    pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
                }) { Text("导入音频") }
            }

            Button(
                onClick = vm::togglePlayPause,
                enabled = state.hasMedia,
            ) { Text(if (state.playWhenReady) "暂停" else "播放") }

            Button(onClick = onOpenSpike) { Text("Spike 分离") }
        }

        if (state.hasMedia) {
            if (isLandscape) {
                // ★F5.2：横屏 —— 转盘（左）‖ 控制（右）
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DeckArea(
                        modifier = Modifier.weight(1.2f).fillMaxHeight(),
                        state = state, vm = vm, secPerTurn = secPerTurn,
                        speedRange = speedRange, haptic = haptic, progressFlow = progressFlow,
                    )
                    ControlsArea(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        state = state, vm = vm, progressFlow = progressFlow,
                        onOpenSpike = onOpenSpike,
                    )
                }
            } else {
                // 竖屏 —— 转盘在上、控制在下
                DeckArea(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = state, vm = vm, secPerTurn = secPerTurn,
                    speedRange = speedRange, haptic = haptic, progressFlow = progressFlow,
                )
                ControlsArea(
                    modifier = Modifier.fillMaxWidth(),
                    state = state, vm = vm, progressFlow = progressFlow,
                    onOpenSpike = onOpenSpike,
                )
            }
        }
        // ★方案1：「分轨混音」已移到**混音页**（转盘/速度条因此恢复大尺寸）。
    }
}

/** 转盘区：左栏（音调旋钮 + 竖向速度条 + 重置）+ 黑胶转盘。 */
@Composable
private fun DeckArea(
    modifier: Modifier,
    state: PlayerViewModel.PlayerState,
    vm: PlayerViewModel,
    secPerTurn: Int,
    speedRange: ClosedFloatingPointRange<Float>,
    haptic: HapticFeedback,
    progressFlow: StateFlow<PlayerViewModel.Progress>,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                onValueChange = { v ->
                    // ★F5.4：吸附到 1.0× 时给一次触感。
                    if (v == 1f && state.speed != 1f) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.setSpeed(v)
                },
                valueRange = speedRange,
                label = { "%.2fx".format(it) },
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
}

/** 控制区：进度条 + 控制行（±10s/播放/循环）+ 循环模式名 + 分离开关 + 指引。 */
@Composable
private fun ControlsArea(
    modifier: Modifier,
    state: PlayerViewModel.PlayerState,
    vm: PlayerViewModel,
    progressFlow: StateFlow<PlayerViewModel.Progress>,
    onOpenSpike: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            Button(onClick = vm::cycleRepeatMode) {
                Text(state.repeatMode.glyph, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            state.repeatMode.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 分离开关（原曲 ↔ 多轨）+ 指引 ----
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
}

/** SAF Uri → 显示名(content resolver 查 OpenableColumns.DISPLAY_NAME) */
private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown"
