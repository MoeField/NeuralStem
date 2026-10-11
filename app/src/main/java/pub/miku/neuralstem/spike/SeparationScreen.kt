package pub.miku.neuralstem.spike

import android.app.Application
import android.util.Log
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R
import pub.miku.neuralstem.export.StemExporter
import pub.miku.neuralstem.player.SeparationTask
import pub.miku.neuralstem.settings.SettingsRepository
import java.io.File

/** ★D-4：分离页档位按钮的“实时档”门槛（纯推理 RTF）。与 SettingsViewModel 保持一致。 */
private const val REALTIME_RTF = 2f

/**
 * 分离页（**Step 10 重构**）：**直接作用于当前播放曲**（不再手动导入）。
 *
 * - 顶部显示当前曲目（歌单名 + 曲名 + n/N）；无曲时空态。
 * - **档位按钮**（★D-3）：按轨数 2 行（2 轨：快速/均衡/质量；4 轨：快速/质量），
 *   点即跑；每档的**具体模型**在设置里选（[SettingsRepository.tierOption]）。
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
    // ★3a-2：分离任务状态 = 单一真源（来自状态机）；repoState 不再直接读。
    val separationTask by vm.separationTask.collectAsState()
    // ★F5.4：分离完成时的触感反馈。
    val haptic = LocalHapticFeedback.current
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    val hasSong = fileName.isNotBlank() && !currentUri.isNullOrBlank()
    // ★3a-2：busy 单一来源（不再拼接 spikeState + repoState）。
    val busy = separationTask is SeparationTask.Preparing ||
        separationTask is SeparationTask.Running
    // ★G-1b：首启**强制**检测完成才能用分离——未检测(无缓存)则门控。
    //   直读（不 remember）：确保 benchmark 完成/缓存变化后重新进入即时反映。
    val benchRtfAll = DeviceBenchmark(app).cached()
    val benchmarked = benchRtfAll.isNotEmpty()

    // ---- 完成 → 自动切多轨（★I3：**一次性消费**，天然只响应"Running→Done 转换"，----
    //      不响应进入时的遗留态；不再用 prevSpike/prevRepo 手搓 → 重建也不会误触发）----
    LaunchedEffect(separationTask) {
        val done = vm.consumeSeparationDone() ?: return@LaunchedEffect
        when (done) {
            is SeparationTask.Done2 -> onPlayStems(
                SpikeViewModel.State.Done(
                    vocalsFile = done.vocalsFile,
                    accompanimentFile = done.accompanimentFile,
                    modelLoadMs = done.modelLoadMs,
                    inferTimeMs = done.inferTimeMs,
                    peakPssMb = done.peakPssMb,
                    audioDurationMs = done.audioDurationMs,
                    fromCache = done.fromCache,
                ),
            )
            is SeparationTask.Done4 -> onPlayStems4(done.stemFiles)
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** ★D-3：点「档位」→ 用设置里该档选的模型启动分离。 */
    fun startTier(tc: TierChoices) {
        val uri = currentUri ?: return
        scope.launch {
            val src = CurrentTrackBridge.toFile(app, uri)
            if (src == null) {
                cacheMsg = app.getString(R.string.sep_uri_unreadable)
                return@launch
            }
            val repo = SettingsRepository(app)
            val opt = repo.tierOption(tc.stem, tc.tier) ?: tc.default()
            Log.i("SepScreen", "startTier: ${tc.stem}/${tc.tier} → ${opt.id} (${opt.displayName})")
            vm.startOption(src, opt)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sep_title), style = MaterialTheme.typography.titleLarge)

        // ---- 当前曲目 ----
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.sep_current),
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
                    stringResource(R.string.sep_no_song),
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
                Text(stringResource(R.string.sep_switch_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (separationOn) R.string.sep_state_multi else R.string.sep_state_orig),
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

        // ---- ★D-3：按轨数 2 行；每行 = 档位按钮（点即跑）；按钮显示当前选中模型 ----
        //   ★D-4：按 benchmark 结果在按钮标“⚡ 实时档”（选模型 RTF ≥ 门槛）。
        //   ★G-1b：未检测（无 benchmark 缓存）→ 门控：按钮禁用 + 提示先检测。
        Text("分离", style = MaterialTheme.typography.titleMedium)
        val settingsRepo = remember { SettingsRepository(app) }
        // benchmark 缓存（本机实测/内置免跑）→ 判断各档选中模型是否实时档。
        val benchRtf: Map<String, Float> = benchRtfAll.mapValues { it.value.rtf }
        // ★G-1b：未检测 → 门控提示（档位按钮保持可见但禁用，引导去检测）。
        if (!benchmarked) {
            Text(
                stringResource(R.string.sep_gate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TierRegistry.ALL.groupBy { it.stem }.forEach { (stem, rows) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (stem == StemCount.TWO) R.string.sep_stem_two else R.string.sep_stem_four),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { tc ->
                        val opt = settingsRepo.tierOption(tc.stem, tc.tier) ?: tc.default()
                        // ★D-4：选中模型的实测 RTF（未测/非 sherpa 模型 → null → 不标）。
                        val rtf = benchRtf[opt.id]
                        val realtime = rtf != null && rtf >= REALTIME_RTF
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = { startTier(tc) },
                                enabled = hasSong && !busy && benchmarked,
                            ) { Text(if (realtime) "⚡ ${tc.tier.label}" else tc.tier.label) }
                            Text(
                                opt.displayName +
                                    (rtf?.let { " · %.2f×".format(it) } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (realtime) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- 进度 / 结果（★3a-2：**单一真源** separationTask；不再并列读 spikeState + repoState）----
        when (val t = separationTask) {
            is SeparationTask.Preparing -> Text(stringResource(R.string.sep_preparing))
            is SeparationTask.Running -> {
                LinearProgressIndicator(
                    progress = { t.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.sep_running_pct, t.percent))
            }
            is SeparationTask.Done2 -> Text(
                stringResource(if (t.fromCache) R.string.sep_done_cache else R.string.sep_done),
                color = MaterialTheme.colorScheme.primary,
            )
            is SeparationTask.Done4 -> {
                Text(
                    stringResource(if (t.fromCache) R.string.sep_done4_cache else R.string.sep_done4),
                    color = MaterialTheme.colorScheme.primary,
                )
                // ★U7：导出到 Music/separate/<歌名>/（WAV，不含 EQ/音量）。
                Button(onClick = {
                    exportMsg = StemExporter.export(app, fileName.ifBlank { "song" }, t.stemFiles)
                }) { Text(stringResource(R.string.sep_export)) }
                exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            is SeparationTask.Failed ->
                Text(stringResource(R.string.sep_failed, t.message), color = MaterialTheme.colorScheme.error)
            SeparationTask.Cancelled ->
                Text(stringResource(R.string.sep_cancelled), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SeparationTask.Idle -> Unit
        }
        if (busy) {
            Button(onClick = { vm.cancelSeparation() }) { Text(stringResource(R.string.sep_cancel)) }
        }

        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                val n = vm.clearCache()
                cacheMsg = if (n < 0) app.getString(R.string.sep_cache_busy)
                else app.getString(R.string.sep_cache_cleared, n)
            }) { Text(stringResource(R.string.sep_clear_cache)) }
            cacheMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
