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
import pub.miku.neuralstem.ui.components.CollapsibleCard

/**
 * 播放页（阶段 UI 重做 · R1：骨架）。
 *
 * ## 布局（定稿）
 * ```
 *  ┌ 顶部：歌名（无标题）
 *  │ 转盘固定区：左栏[音调/速度] + 黑胶转盘   ← R1 先占位，R2/R3 填充
 *  │ 横向进度条 + -10s/播放/+10s
 *  ├ 下方滚动：分离开关 / 分轨混音 / EQ（默认收起）
 *  └
 * ```
 *
 * R1 只定**骨架与分区**：转盘/左栏用占位框，先确认布局与"转盘常驻、卡片滚动"。
 */
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    vm: PlayerViewModel = viewModel(),
    onSeparateToggle: (Boolean) -> Unit = {},
    /** 阶段 3.1：点「去「分离」页处理 →」时切到分离导航页。 */
    onOpenSeparate: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    // ★ 进度流**不在此层 collect**（否则每 100ms 重组整个播放页 → “时间+转盘一起卡”）。
    //   改为传给 DeckView / ProgressBar，各子组件内部 collect + 仅 draw 阶段读。
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
        // ---- 顶部：歌名（去掉"NeuralStem"标题）----
        Text(
            state.fileName.ifEmpty { "尚未导入音频" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        // 当前歌单（歌单 = 播放队列）：如 "默认歌单 [文件夹] · 3/12"
        if (state.playlistId != null) {
            Text(
                buildString {
                    append(state.playlistName)
                    // ★ 显式标注歌单**类型**：文件夹歌单不可应用内添加曲目
                    append(if (state.playlistIsFolder) " · 文件夹" else " · 默认")
                    if (state.trackIndex >= 0 && state.trackCount > 0) {
                        append(" · ${state.trackIndex + 1}/${state.trackCount}")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // ★ 选中**文件夹歌单**时不允许应用内添加 → 隐藏“导入音频”按钮。
        //   IA 重构（批次 1）：「分离」跳转按钮已移除（分离为顶级导航页，点击底栏即可到达）。
        if (!state.playlistIsFolder) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pickAudio.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
                }) { Text("导入音频") }
            }
        }

        // ★ 错误展示（不白屏）
        state.error?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // ★ UI 门控用 hasMedia（“已载入”）而非 isReady（“已就绪”）：
        //   加载失败/超时也能看到 UI 与错误，不再白屏。
        if (state.hasMedia) {
            // ---- 转盘固定区：左栏（R2） + 转盘（R3 占位）----
            //   主区 weight(1f)：吃掉进度条/控制/卡片之外的**全部剩余高度** → 滑块行程最大化
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左栏：音调旋钮（上）+ 竖向速度滑块（下）；去掉冗余文字标签（值已自解释）
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
                        // ★R6：区间由设置项 X 决定 = [1/X, X]
                        valueRange = settings.speedSliderMin..settings.speedSliderMax,
                        label = { "%.2fx".format(it) },
                        // ★速度滑块**连续无步进**（不传 step），仅保留 1.0× 吸附
                        snapTargets = listOf(1f),
                        snapTolerance = 0.06f,
                        // ★对数尺度：保证 1.0× 恒定居中
                        logScale = true,
                    )
                    // 一键重置（速度/音调）—— 与它所重置的控件同栏
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
                // ★ 进度流传入，各子组件内部 collect（避免本页每 100ms 重组）。
                // seek 目标与绘制同一 duration 源（progressFlow），否则“点位对不上”。
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
                    onPrev = vm::prev,
                    onNext = vm::next,
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
                Button(onClick = vm::togglePlayPause) { Text(if (state.isPlaying) "暂停" else "播放") }
                Button(onClick = { vm.seekTo(vm.progress.positionMs + 10_000) }) { Text("+10s") }
                // ★3.3：循环按钮（三态）。用**字形**保持紧凑（避免 720px 宽屏挤成竖排）；
                //   模式名通过下方小字提示。
                Button(onClick = vm::cycleRepeatMode) {
                    Text(state.repeatMode.glyph, style = MaterialTheme.typography.titleMedium)
                }
            }
            // 循环模式名（小字，避免按钮内文字换行）
            Text(
                state.repeatMode.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 下方：可滚动卡片区（默认收起）----
        //   高度封顶（避免展开时挤掉上方转盘区）
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            if (state.hasMedia) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CollapsibleCard(
                        title = "分离开关",
                        subtitle = if (state.separationOn) "当前：多轨" else "当前：原曲",
                        trailing = {
                            Switch(
                                checked = state.separationOn,
                                onCheckedChange = { onSeparateToggle(it) },
                            )
                        },
                    ) {
                        Text(
                            "打开：切到分离结果；关闭：回到原曲。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 阶段 3.1：指向「分离」页（分离在该页对当前曲处理）。
                        Text(
                            "去「分离」页处理 →",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSeparate() }
                                .padding(vertical = 6.dp),
                        )
                    }

                    // ★ 3.1/批次4：“分轨混音”已移到**分离页**（分离调整统一在那一页）。
                    // ★ IA 重构（批次 1）：EQ 已提升为**顶级导航页**（[EqScreen]），
                    //   不再嵌入播放器折叠卡片。
                }
            }
        }
    }
}

/** SAF Uri → 显示名。 */
private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown"
