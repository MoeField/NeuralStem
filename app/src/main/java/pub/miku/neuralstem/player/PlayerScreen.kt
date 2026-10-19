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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import pub.miku.neuralstem.R
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
    // ★⑮-1：无分离结果时点开关 → 弹**简提示**（不再选档位）；确定后跳「分离」页。
    var showNoSepDialog by remember { mutableStateOf(false) }
    // ★⑭a/⑮-1：分离开关切换——开且无结果 → 弹提示；否则直接切。
    val onSeparationToggle: (Boolean) -> Unit = { on ->
        val ok = vm.toggleSeparation(on)
        if (on && !ok) showNoSepDialog = true
    }
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
        // ★⑫：横屏**有曲**时曲名移入右栏顶（此处不显，避免在栏上方独占整宽）；
        //   竖屏 / 空态（无曲）仍在此显。
        if (!isLandscape || !state.hasMedia) {
            Text(
                state.fileName.ifEmpty { stringResource(R.string.player_no_media) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }

        // ★⑪：删除了顶部行的「播放/暂停」（与下方控制行重复）与「Spike 分离」（与底栏 tab 重复）。
        //   保留的「导入音频」不再独占行：竖屏→入右侧转盘块顶；横屏→入右栏（控制区）顶。
        val onImport: () -> Unit = {
            pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
        }

        // ★空态导入入口（2026-10-17）：此前控件均在 `if (state.hasMedia)` 内，
        //   新装首次启动落在播放器页 → 只有“尚未导入音频”而**无任何导入入口**（需自行发现歌单页）。
        //   此处为“空态”补一个「导入音频」按钮（与有曲时同款，直接弹文件选择器）。
        if (!state.hasMedia && importEnabled) {
            Button(onClick = onImport) {
                Text(stringResource(R.string.player_import), maxLines = 1)
            }
        }

        if (state.hasMedia) {
            if (isLandscape) {
                // ★⑧F5.2/⑩：横屏 —— **三栏**：左(音高+速度竖条) ‖ 中(大圆盘) ‖ 右(控制/分离开关)。
                //   左栏固定窄宽（不随比例缩放，否则竖条放不下）；中/右各 weight 1f。
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PitchSpeedColumn(
                        modifier = Modifier.width(72.dp).fillMaxHeight(),
                        state = state, vm = vm, speedRange = speedRange, haptic = haptic,
                    )
                    DeckView(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        waveformProvider = { state.waveform },
                        stemWaveformProvider = { state.stemWaveforms },
                        cover = state.cover,
                        songName = state.fileName,
                        progressFlow = progressFlow,
                        anchorFlow = vm.anchorFlow,
                        speed = state.speed,
                        isPlaying = state.isPlaying,
                        secPerTurn = secPerTurn,
                        onTogglePlayPause = vm::togglePlayPause,
                        onPrev = { vm.playPrev() },
                        onNext = { vm.playNext() },
                        onSeekFraction = { f ->
                            val d = progressFlow.value.durationMs
                            val dur = if (d > 0) d else state.durationMs
                            if (dur > 0) vm.seekTo((f * dur).toLong())
                        },
                    )
                    ControlsArea(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        state = state, vm = vm, progressFlow = progressFlow,
                        onOpenSpike = onOpenSpike,
                        onImport = if (importEnabled) onImport else null,
                        // ★⑫：横屏曲名入右栏顶。
                        songName = state.fileName,
                        // ★⑬：横屏不显“去分离页处理”。
                        showGoSeparate = false,
                        // ★⑭a：分离开关切换（无结果→弹档位）。
                        onSeparationToggle = onSeparationToggle,
                    )
                }
            } else {
                // 竖屏 —— 转盘在上、控制在下
                DeckArea(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = state, vm = vm, secPerTurn = secPerTurn,
                    speedRange = speedRange, haptic = haptic, progressFlow = progressFlow,
                    // ★⑪：竖屏导入音频入右侧转盘块顶（不否独占一行）。
                    onImport = if (importEnabled) onImport else null,
                )
                ControlsArea(
                    modifier = Modifier.fillMaxWidth(),
                    state = state, vm = vm, progressFlow = progressFlow,
                    onOpenSpike = onOpenSpike,
                    onSeparationToggle = onSeparationToggle,
                )
            }
        }
        // ★方案1：「分轨混音」已移到**混音页**（转盘/速度条因此恢复大尺寸）。
    }

    // ★⑮-1：无分离结果时的**简提示**——确定即跳「分离」页（档位在那里选）。
    if (showNoSepDialog) {
        AlertDialog(
            onDismissRequest = { showNoSepDialog = false },
            title = { Text(stringResource(R.string.sep_none_title)) },
            text = { Text(stringResource(R.string.sep_none_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showNoSepDialog = false
                    onOpenSpike()   // 跳「分离」页（选档位/跑分离在那里）
                }) { Text(stringResource(R.string.sep_none_go)) }
            },
            dismissButton = {
                TextButton(onClick = { showNoSepDialog = false }) { Text(stringResource(R.string.sep_cancel)) }
            },
        )
    }
}

/**
 * ★⑩：横屏左栏 —— **音高条 + 速度条左右并排**（固定窄宽）+ “重置”。
 *
 * 为何横屏把音高从旋钮换成**竖条**：竖向空间充裕、横向紧张，竖条更能利用高度且占地小。
 * 复用现有 [VerticalSlider]（支持 label / snapTargets / logScale）。
 */
@Composable
private fun PitchSpeedColumn(
    modifier: Modifier,
    state: PlayerViewModel.PlayerState,
    vm: PlayerViewModel,
    speedRange: ClosedFloatingPointRange<Float>,
    haptic: HapticFeedback,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 两根竖条并排：音高（半音）| 速度（对数，1.0× 居中）
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VerticalSlider(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                value = state.pitchSemitones,
                onValueChange = vm::setPitchSemitones,
                valueRange = -12f..12f,
                label = { "%+.0f".format(it) },
                snapTargets = listOf(0f),
                snapTolerance = 0.5f,
            )
            VerticalSlider(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                value = state.speed,
                onValueChange = { v ->
                    if (v == 1f && state.speed != 1f) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.setSpeed(v)
                },
                valueRange = speedRange,
                label = { "%.2fx".format(it) },
                snapTargets = listOf(1f),
                snapTolerance = 0.06f,
                logScale = true,
            )
        }
        Text(
            stringResource(R.string.player_reset),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.resetSpeedPitch() }
                .padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** 转盘区：左栏（音调旋钮 + 竖向速度条 + 重置）+ 黑胶转盘。
 *  ★⑪：竖屏时右块顶部可放「导入音频」（不否独占一行）。 */
@Composable
private fun DeckArea(
    modifier: Modifier,
    state: PlayerViewModel.PlayerState,
    vm: PlayerViewModel,
    secPerTurn: Int,
    speedRange: ClosedFloatingPointRange<Float>,
    haptic: HapticFeedback,
    progressFlow: StateFlow<PlayerViewModel.Progress>,
    // ★⑪：竖屏导入音频（否 null 则不显）
    onImport: (() -> Unit)? = null,
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
                stringResource(R.string.player_reset),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.resetSpeedPitch() }
                    .padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }

        // ★⑪：右块 = [导入音频]（竖屏）+ 转盘（导入键不独占整行）。
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            onImport?.let {
                Button(onClick = it) {
                    Text(stringResource(R.string.player_import), maxLines = 1)
                }
            }
            DeckView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                waveformProvider = { state.waveform },
                stemWaveformProvider = { state.stemWaveforms },
                cover = state.cover,
                songName = state.fileName,
                progressFlow = progressFlow,
                anchorFlow = vm.anchorFlow,
                speed = state.speed,
                isPlaying = state.isPlaying,
                secPerTurn = secPerTurn,
                onTogglePlayPause = vm::togglePlayPause,
                // ★R-a：横向滑动唱片 → 上/下一首（队列）
                onPrev = { vm.playPrev() },
                onNext = { vm.playNext() },
                onSeekFraction = { f ->
                    val d = progressFlow.value.durationMs
                    val dur = if (d > 0) d else state.durationMs
                    if (dur > 0) vm.seekTo((f * dur).toLong())
                },
            )
        }
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
    // ★⑪：横屏时把「导入音频」放本区（否 null 则不显）。
    onImport: (() -> Unit)? = null,
    // ★⑫：横屏时曲名放本区顶部（非空才显）。
    songName: String? = null,
    // ★⑬：是否显“去「分离」页处理 →”链接（横屏不显；竖屏显）。
    showGoSeparate: Boolean = true,
    // ★⑭a：分离开关切换（由 PlayerScreen 处理“无结果→弹档位”）。
    onSeparationToggle: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ★⑫：横屏——曲名置右栏顶。
        songName?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        ProgressBar(
            progressFlow = progressFlow,
            anchorFlow = vm.anchorFlow,
            onSeek = vm::seekTo,
            modifier = Modifier.fillMaxWidth(),
        )
        // ★R-a：控制行**不设**上/下一首按钮 —— 切歌交给「横向滑动唱片」（DeckView）。
        // 快退/快进用 ⏪/⏩ 符号（语义 = ±10s 跳转），比文字更紧凑。
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { vm.seekTo((vm.progress.positionMs - 10_000).coerceAtLeast(0)) }) {
                Text("⏪", style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = vm::togglePlayPause) { Text(stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play)) }
            Button(onClick = { vm.seekTo(vm.progress.positionMs + 10_000) }) {
                Text("⏩", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = vm::cycleRepeatMode,
                // 紧凑内容边距（避免默认横向 padding 把 "↻₁" 逼窄 → 换行）。
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // ★ maxLines=1 + softWrap=false：防止 "↻₁" 两个码点被容器宽度逼成两行
                // （否则 “₁” 会掉到下面，按钮高度翻倍 —— 真机实测）。
                Text(
                    state.repeatMode.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Text(
            stringResource(state.repeatMode.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- ★⑫：导入音频 + 分离开关（原曲 ↔ 多轨）**同一行** ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onImport?.let {
                Button(onClick = it) { Text(stringResource(R.string.player_import), maxLines = 1) }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.separationOn) R.string.player_sep_on else R.string.player_sep_off),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // ★G-3ux：边分离边播进行中 → 明确告知“已可听，但后台还在分离”。
                //   没有这一行，用户会看到“多轨”却没意识到分离尚未完成。
                //
                // ★闸门（2026-10-20）：分离跟不上播放时优先显示 **Starved** 文案
                //   （“已暂停等待”），否则用户只看到“暂停”而不知道是⾃在等分离。
                //   慢模型（0.34×）会反复进这个态，必须解释清楚。
                val pct = state.edgePercent
                when {
                    state.edgeStarved && pct != null -> Text(
                        stringResource(R.string.sep_edge_starved_paused, pct),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.edgeStarved -> Text(
                        stringResource(R.string.sep_edge_starved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    pct != null -> Text(
                        stringResource(R.string.sep_running_playable, pct),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(
                checked = state.separationOn,
                onCheckedChange = { onSeparationToggle(it) },
            )
        }
        // ★⑬：横屏不显“去「分离」页处理 →”（底栏已有“分离”tab）；竖屏保留。
        if (showGoSeparate) {
            Text(
                stringResource(R.string.player_go_separate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSpike() }
                    .padding(vertical = 4.dp),
            )
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
