package pub.miku.neuralstem.spike

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.player.TrackMixerPanel
import pub.miku.neuralstem.settings.SettingsRepository
import java.io.File

/**
 * 分离页（**阶段 3.1 重构**）。
 *
 * ## 与旧版的差别
 * - **不再手动导入**：直接作用于**当前播放曲**（歌单名 + 曲名 + n/N）。
 * - **模式单选**（2-stem / 4-stem / 高质量），一个「开始分离」按钮启动所选模式。
 * - **分离开关**与播放页「分离开关」**同一状态**（`separationOn`）；完成自动切多轨。
 * - **非实时模型** → 弹「正在处理中…（可继续听歌）」对话框 + 单曲循环，完成后恢复。
 * - **布局**：用 [FlowRow]/全宽堆叠，避免 720px 宽屏下按钮被挤成竖条。
 *
 * ## 架构约束
 * `SpikeViewModel`（分离）与 `PlayerViewModel`（播放）是**平级 VM，互不引用**；
 * 当前曲信息与播放器动作均由宿主（AppShell）以**参数 + 回调**注入。
 *
 * @param fileName 当前播放曲名
 * @param playlistName 当前歌单名
 * @param trackIndex 当前曲下标（0-based，-1 = 未知）
 * @param trackCount 当前歌单曲目总数
 * @param separationOn 分离开关（= 播放页同一状态）
 * @param currentUri 当前曲 Uri（`content://`/`file://`）；用于 [CurrentTrackBridge] 拷贝
 * @param onToggleSeparation 请求切换分离开关（转发给 PlayerViewModel.toggleSeparation）
 * @param onSetRepeatMode 请求设置播放模式（转发给 PlayerViewModel.setRepeatMode）
 * @param currentRepeatMode 当前播放模式（用于完成后恢复）
 * @param onPlayStems 2-stem 结果 → 多轨播放
 * @param onPlayStems4 4-stem / Demucs 结果（vocals/drums/bass/other 或 Demucs 顺序）→ 多轨播放
 */
@Composable
fun SeparationScreen(
    modifier: Modifier = Modifier,
    vm: SpikeViewModel = viewModel(),
    fileName: String = "",
    playlistName: String = "",
    trackIndex: Int = -1,
    trackCount: Int = 0,
    separationOn: Boolean = false,
    currentUri: String? = null,
    onToggleSeparation: (Boolean) -> Unit = {},
    onSetRepeatMode: (SettingsRepository.RepeatMode) -> Unit = {},
    currentRepeatMode: SettingsRepository.RepeatMode = SettingsRepository.RepeatMode.LOOP_ALL,
    onPlayStems: (SpikeViewModel.State.Done) -> Unit = {},
    onPlayStems4: (List<File>) -> Unit = {},
    // ★ 批次4：分轨混音（分离调整）从播放页搬到本页。
    tracks: List<PlayerViewModel.TrackUiState> = emptyList(),
    isMultiTrack: Boolean = false,
    onSetTrackVolume: (String, Float) -> Unit = { _, _ -> },
    onToggleMute: (String) -> Unit = {},
    onToggleSolo: (String) -> Unit = {},
    onExitMultiTrack: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val scope = rememberCoroutineScope()

    // 页面状态（分离引擎）
    val spikeState by vm.state.collectAsState()
    val repo = remember { SeparationRepository.get(app) }
    val repoState by repo.state.collectAsState()

    // 所选模式（记住）
    var mode by rememberSaveable { mutableStateOf(SeparationMode.TWO_STEM) }
    // 缓存清理提示
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    // 设备基准（决定实时/非实时）
    val bench = remember { DeviceBenchmark(app) }

    // 是否有可分离的当前曲
    val hasSong = fileName.isNotBlank() && !currentUri.isNullOrBlank()
    // 分离是否进行中（2-stem 走 SpikeViewModel，4-stem/Demucs 走 repo）
    val busy = spikeState is SpikeViewModel.State.Separating ||
        repoState is SeparationRepository.State.Preparing ||
        repoState is SeparationRepository.State.Running

    // ---- 非实时模型 → 处理中对话框 + 单曲循环（记住进入前的播放模式）----
    //   rememberSaveable 以便配置变更后仍能恢复。
    var processing by remember { mutableStateOf(false) }
    var prevRepeat by rememberSaveable { mutableStateOf<String?>(null) }

    /** 结束「处理中」态：恢复进入前的播放模式（非单曲循环）。 */
    fun endProcessing() {
        if (!processing) return
        processing = false
        val restore = prevRepeat?.let { runCatching { SettingsRepository.RepeatMode.valueOf(it) }.getOrNull() }
        if (restore != null && restore != SettingsRepository.RepeatMode.LOOP_ONE) {
            onSetRepeatMode(restore)
        }
        prevRepeat = null
    }

    /** 开始分离：拷贝当前曲 → 按所选模式启动对应仓库路径。 */
    fun startSeparation() {
        val uri = currentUri ?: return
        scope.launch {
            val src = CurrentTrackBridge.toFile(app, uri)
            if (src == null) {
                cacheMsg = "无法读取当前曲目（Uri 不可访问）"
                return@launch
            }
            val realtime = mode.isRealtime(bench)
            if (!realtime) {
                // 记录进入前模式 → 置单曲循环 → 弹处理中对话框
                if (!processing) {
                    prevRepeat = currentRepeatMode.name
                    onSetRepeatMode(SettingsRepository.RepeatMode.LOOP_ONE)
                    processing = true
                }
            }
            when (mode) {
                SeparationMode.TWO_STEM -> {
                    // ★ 先 await 源解析（suspend）再启动；否则 startSeparation 会读到 null → 静默不分离。
                    if (!vm.selectSeparationSource(src)) {
                        cacheMsg = "当前曲目解析失败，无法分离"
                        return@launch
                    }
                    vm.selectModel(ModelRegistry.SPLEETER.id)
                    // C2：若该模型未测，首块耗时回写基准
                    vm.startSeparation(onFirstChunkRtf = { rtf, ms ->
                        if (bench.cached()[ModelRegistry.SPLEETER.id] == null) {
                            bench.store(ModelRegistry.SPLEETER.id, rtf, ms)
                        }
                    })
                }
                SeparationMode.FOUR_STEM -> repo.start4(
                    src,
                    onFirstChunkRtf = { rtf, ms ->
                        if (bench.cached()[FOUR_STEM_BENCH_ID] == null) {
                            bench.store(FOUR_STEM_BENCH_ID, rtf, ms)
                        }
                    },
                )
                SeparationMode.HIGH_QUALITY -> {
                    val demucs = File(app.filesDir, "demucs/htdemucs_embedded.onnx")
                    if (demucs.exists()) {
                        repo.startDemucs(src)
                    } else {
                        repo.start4(src)
                    }
                }
            }
        }
    }

    // ---- 完成 → 关闭对话框 + 恢复模式 + 自动开启分离（切多轨）----
    //   ★ 只对**本次进入页面后新发生**的完成做出反应：
    //     否则会因 SpikeViewModel / Repo 的**遗留 Done 态**（VM/进程级存活）
    //     在“刚进入分离页”时就立刻触发 → **点一下就跳回播放器并重播**（真机反馈）。
    //   判据：从「非 Done」变为「Done」才算一次真正的完成（忽略进入时的基线）。
    var prevSpike by remember { mutableStateOf<SpikeViewModel.State?>(spikeState) }
    var prevRepo by remember { mutableStateOf<SeparationRepository.State?>(repoState) }
    LaunchedEffect(spikeState, repoState) {
        val wasSpike = prevSpike
        prevSpike = spikeState
        when (val s = spikeState) {
            is SpikeViewModel.State.Done ->
                if (wasSpike !is SpikeViewModel.State.Done) { endProcessing(); onPlayStems(s) }
            is SpikeViewModel.State.Error ->
                if (wasSpike !is SpikeViewModel.State.Error) endProcessing()
            else -> Unit
        }
        val wasRepo = prevRepo
        prevRepo = repoState
        when (val s = repoState) {
            is SeparationRepository.State.Done4 ->
                if (wasRepo !is SeparationRepository.State.Done4) {
                    endProcessing(); onPlayStems4(s.stemFiles)
                }
            is SeparationRepository.State.Failed ->
                if (wasRepo !is SeparationRepository.State.Failed) endProcessing()
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("分离", style = MaterialTheme.typography.titleLarge)

        // ---------------- 当前曲目信息 ----------------
        CurrentSongCard(
            fileName = fileName,
            playlistName = playlistName,
            trackIndex = trackIndex,
            trackCount = trackCount,
        )

        // ---------------- 分离开关（与播放页同一状态）----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("分离开关", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (separationOn) "当前：多轨" else "当前：原曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = separationOn,
                onCheckedChange = { onToggleSeparation(it) },
                enabled = hasSong,
            )
        }

        // ---------------- 分轨混音（★ 批次4：分离调整统一在本页）----------------
        //   仅在多轨（分离已生效）且已载入轨时显示。
        if (isMultiTrack && tracks.isNotEmpty()) {
            Text("分轨混音", style = MaterialTheme.typography.titleMedium)
            TrackMixerPanel(
                tracks = tracks,
                onVolumeChange = onSetTrackVolume,
                onToggleMute = onToggleMute,
                onToggleSolo = onToggleSolo,
                onExit = onExitMultiTrack,
            )
        }

        // ---------------- 模式选择（单选，FlowRow 避免挤成竖条）----------------
        Text("分离模式", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeparationMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m.label) },
                )
            }
        }
        Text(
            mode.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------- 开始按钮（全宽，避免挤压）----------------
        Button(
            onClick = { startSeparation() },
            enabled = hasSong && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "分离中…" else "开始分离")
        }

        // ---------------- 进度 / 结果 ----------------
        ProgressAndResult(
            spikeState = spikeState,
            repoState = repoState,
            onCancel = { vm.cancelSeparation(); repo.cancel() },
            onReset = { vm.reset() },
            onPlay2 = onPlayStems,
            onPlay4 = onPlayStems4,
        )

        // ---------------- 缓存管理 ----------------
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                val n = vm.clearCache()
                cacheMsg = if (n < 0) "分离进行中,无法清理" else "已清理 $n 项缓存"
            }) { Text("清理缓存") }
            cacheMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    // ---------------- 非实时模型：处理中对话框 ----------------
    if (processing) {
        AlertDialog(
            onDismissRequest = { /* 不可取消：分离在后台继续，仅提示 */ },
            title = { Text("正在处理中…（可继续听歌）") },
            text = {
                Text(
                    "该模式不是实时模型，分离在后台进行。\n" +
                        "为避免切歌打断，已临时切换为**单曲循环**，完成后自动恢复并切到多轨。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { endProcessing() }) { Text("知道了") }
            },
        )
    }
}

/** 4-stem 引擎在 [DeviceBenchmark] 中的虚拟模型 id（无对应 [ModelSpec]）。 */
private const val FOUR_STEM_BENCH_ID = "spleeter-4stems"

/**
 * 分离模式（单选）。
 *
 * 实时性判定（[isRealtime]）：
 * - 已测模型：RTF ≥ [REALTIME_THRESHOLD]（与 SettingsViewModel 的 2× 门槛一致）
 * - 未测模型：仅「最快的 Spleeter 2-stem」视为实时（保守，其余按非实时处理）
 */
enum class SeparationMode(
    val label: String,
    val hint: String,
) {
    TWO_STEM("2-stem", "Spleeter 2-stem：人声 / 伴奏（最快）"),
    FOUR_STEM("4-stem", "Spleeter 4-stems：人声 / 鼓 / 贝斯 / 其他"),
    HIGH_QUALITY("高质量", "Demucs（已安装则用）或 Spleeter 4-stem，音质优先");

    /**
     * 该模式在当前设备上是否实时（RTF ≥ 2×）。
     *
     * @param bench 设备基准（读 [DeviceBenchmark.cached]）
     */
    fun isRealtime(bench: DeviceBenchmark): Boolean {
        val cached = bench.cached()
        val modelId = when (this) {
            TWO_STEM -> ModelRegistry.SPLEETER.id
            FOUR_STEM -> FOUR_STEM_BENCH_ID
            HIGH_QUALITY -> HIGH_QUALITY_BENCH_ID
        }
        val rtf = cached[modelId]?.rtf
        return when {
            rtf != null -> rtf >= REALTIME_THRESHOLD
            // 未测：仅最快的 2-stem 视为实时，其余保守按非实时
            this == TWO_STEM -> true
            else -> false
        }
    }

    companion object {
        /** 与 SettingsViewModel.realtimeThreshold 一致：RTF ≥ 2× 才算实时。 */
        const val REALTIME_THRESHOLD = 2f

        /** Demucs 在 [DeviceBenchmark] 中的虚拟模型 id。 */
        const val HIGH_QUALITY_BENCH_ID = "demucs-htdemucs"
    }
}

/** 当前曲目信息卡片（歌单名 + 曲名 + n/N）；无曲目时展示空态。 */
@Composable
private fun CurrentSongCard(
    fileName: String,
    playlistName: String,
    trackIndex: Int,
    trackCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (fileName.isBlank()) {
            Text(
                "先在播放器导入并播放一首歌",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        Text("当前曲目", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        val meta = buildString {
            if (playlistName.isNotBlank()) {
                append(playlistName)
                append(" · ")
            }
            append(if (trackIndex >= 0 && trackCount > 0) "${trackIndex + 1}/$trackCount" else "—")
        }
        Text(meta, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 统一的进度 / 结果展示（2-stem 来自 spikeState，4-stem/Demucs 来自 repoState）。 */
@Composable
private fun ProgressAndResult(
    spikeState: SpikeViewModel.State,
    repoState: SeparationRepository.State,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onPlay2: (SpikeViewModel.State.Done) -> Unit,
    onPlay4: (List<File>) -> Unit,
) {
    when (val s = spikeState) {
        is SpikeViewModel.State.Separating -> {
            LinearProgressIndicator(
                progress = { s.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("分离中 ${s.percent}% — 已耗时 ${s.elapsedMs / 1000}s")
            Text("峰值 PSS ${"%.1f".format(s.peakPssMb)} MB")
            Text("提示:分离在后台运行,可切走稍后回来", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCancel) { Text("取消分离") }
            return
        }
        is SpikeViewModel.State.Done -> {
            Text(
                if (s.fromCache) "✅ 命中缓存(未重新推理)" else "✅ 分离完成",
                color = MaterialTheme.colorScheme.primary,
            )
            if (!s.fromCache) {
                Text(
                    "处理速度: " +
                        "${"%.2f".format(s.audioDurationMs / 1000.0 / (s.inferTimeMs / 1000.0))}x 实时",
                )
            }
            Button(onClick = { onPlay2(s) }, modifier = Modifier.fillMaxWidth()) { Text("▶ 去多轨播放") }
            return
        }
        is SpikeViewModel.State.Error -> {
            Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            Button(onClick = onReset) { Text("重置") }
            return
        }
        else -> Unit
    }

    when (val s = repoState) {
        is SeparationRepository.State.Preparing -> Text("准备中…")
        is SeparationRepository.State.Running -> {
            LinearProgressIndicator(
                progress = { s.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("分离中 ${s.percent}% — ${s.elapsedMs / 1000}s")
            Text("峰值 PSS ${"%.1f".format(s.peakPssMb)} MB")
            Button(onClick = onCancel) { Text("取消") }
        }
        is SeparationRepository.State.Done4 -> {
            Text(
                if (s.fromCache) "✅ 命中缓存" else "✅ 分离完成",
                color = MaterialTheme.colorScheme.primary,
            )
            Text("输出: " + s.stemFiles.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onPlay4(s.stemFiles) }, modifier = Modifier.fillMaxWidth()) { Text("▶ 去多轨播放") }
        }
        is SeparationRepository.State.Failed ->
            Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}
