package pub.miku.neuralstem.spike

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pub.miku.neuralstem.export.StemExporter
import java.io.File

/**
 * 分离页（**Step 10 重构**）：**直接作用于当前播放曲**（不再手动导入）。
 *
 * - 顶部显示当前曲目（歌单名 + 曲名 + n/N）；无曲时空态。
 * - **模式单选**（[SeparationMode]：2-stem / 4-stem / 高质量）+ 一个「开始分离」按钮。
 * - **分离开关**与播放页同一状态（[onToggleSeparation]）。
 * - 完成 → **自动切多轨**（[onPlayStems]/[onPlayStems4]，**只对转换响应**，避免遗留 Done 误触发）。
 * - 布局用 [FlowRow]/全宽，避免 720px 宽屏挤压。
 *
 * ## 架构约束
 * [SpikeViewModel]（分离）与播放 VM 是**平级 VM，互不引用**；当前曲信息与播放器动作由宿主
 * （AppShell）以**参数 + 回调**注入。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onPlayStems: (SpikeViewModel.State.Done) -> Unit = {},
    onPlayStems4: (List<File>) -> Unit = {},
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val scope = rememberCoroutineScope()
    val spikeState by vm.state.collectAsState()
    val repo = remember { SeparationRepository.get(app) }
    val repoState by repo.state.collectAsState()
    // ★F5.4：分离完成时的触感反馈。
    val haptic = LocalHapticFeedback.current
    var mode by rememberSaveable { mutableStateOf(SeparationMode.TWO_STEM) }
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    val hasSong = fileName.isNotBlank() && !currentUri.isNullOrBlank()
    val busy = spikeState is SpikeViewModel.State.Separating ||
        repoState is SeparationRepository.State.Preparing ||
        repoState is SeparationRepository.State.Running

    // ---- 完成 → 自动切多轨（**只对「非 Done → Done」的转换**响应，忽略进入时的遗留态）----
    var prevSpike by remember { mutableStateOf<SpikeViewModel.State?>(spikeState) }
    var prevRepo by remember { mutableStateOf<SeparationRepository.State?>(repoState) }
    LaunchedEffect(spikeState, repoState) {
        val wasS = prevSpike; prevSpike = spikeState
        val s = spikeState
        if (s is SpikeViewModel.State.Done && wasS !is SpikeViewModel.State.Done) {
            onPlayStems(s)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        val wasR = prevRepo; prevRepo = repoState
        val r = repoState
        if (r is SeparationRepository.State.Done4 && wasR !is SeparationRepository.State.Done4) {
            onPlayStems4(r.stemFiles)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /** 开始分离：Uri→File → 按模式启动。 */
    fun start() {
        val uri = currentUri ?: return
        scope.launch {
            val src = CurrentTrackBridge.toFile(app, uri)
            if (src == null) {
                cacheMsg = "无法读取当前曲目（Uri 不可访问）"
                return@launch
            }
            when (mode) {
                SeparationMode.TWO_STEM -> {
                    // ★ 先 await 源解析再启动（否则 startSeparation 会读到 null → 静默不分离）
                    if (!vm.selectSeparationSource(src)) {
                        cacheMsg = "当前曲目解析失败，无法分离"
                        return@launch
                    }
                    vm.startSeparation()
                }
                SeparationMode.FOUR_STEM -> repo.start4(src)
                SeparationMode.HIGH_QUALITY -> repo.start4(src) // Demucs 未装 → 回退 4-stem
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("分离", style = MaterialTheme.typography.titleLarge)

        // ---- 当前曲目 ----
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "当前曲目",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasSong) {
                Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    buildString {
                        if (playlistName.isNotBlank()) { append(playlistName); append(" · ") }
                        append(
                            if (trackIndex >= 0 && trackCount > 0) "${trackIndex + 1}/$trackCount" else "—",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "先在播放器导入并播放一首歌",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- 分离开关（与播放页同一状态）----
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

        // ---- 模式单选（FlowRow 避免挤成竖条）----
        Text("分离模式", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeparationMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
            }
        }
        Text(
            mode.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 开始（全宽）----
        Button(
            onClick = { start() },
            enabled = hasSong && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "分离中…" else "开始分离") }

        // ---- 进度 / 结果 ----
        when (val s = spikeState) {
            is SpikeViewModel.State.Separating -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分离中 ${s.percent}% — 已耗时 ${s.elapsedMs / 1000}s")
            }
            is SpikeViewModel.State.Done ->
                Text(
                    if (s.fromCache) "✅ 命中缓存（未重新推理）" else "✅ 分离完成",
                    color = MaterialTheme.colorScheme.primary,
                )
            is SpikeViewModel.State.Error ->
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        when (val s = repoState) {
            is SeparationRepository.State.Preparing -> Text("准备中…")
            is SeparationRepository.State.Running -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("4-stem 分离中 ${s.percent}% — ${s.elapsedMs / 1000}s")
            }
            is SeparationRepository.State.Done4 -> {
                Text(
                    if (s.fromCache) "✅ 4-stem 命中缓存" else "✅ 4-stem 完成",
                    color = MaterialTheme.colorScheme.primary,
                )
                // ★U7：导出到 Music/separate/<歌名>/（WAV，不含 EQ/音量）。
                Button(onClick = {
                    exportMsg = StemExporter.export(app, fileName.ifBlank { "song" }, s.stemFiles)
                }) { Text("导出到 Music/separate/") }
                exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            is SeparationRepository.State.Failed ->
                Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        if (busy) {
            Button(onClick = { vm.cancelSeparation(); repo.cancel() }) { Text("取消分离") }
        }

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
}

/** 分离模式（单选）。 */
enum class SeparationMode(val label: String, val hint: String) {
    TWO_STEM("2-stem", "Spleeter 2-stem：人声 / 伴奏（最快）"),
    FOUR_STEM("4-stem", "Spleeter 4-stems：人声 / 鼓 / 贝斯 / 其他"),
    HIGH_QUALITY("高质量", "Demucs（已安装则用）或 Spleeter 4-stem，音质优先"),
}
