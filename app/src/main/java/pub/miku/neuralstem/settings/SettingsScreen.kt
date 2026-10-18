package pub.miku.neuralstem.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pub.miku.neuralstem.R
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.TierRegistry

/**
 * 设置页(阶段 M2 任务 E)。
 *
 * 内容:
 * - 设备能力摘要 + [重新检测(短)] / [长测试]
 * - 模型按 RTF 分三档展示（⚡ 实时 / ⏱ 较慢 / 🐢 很慢），显示体积/用途/预计耗时
 * - 默认引擎选择（**仅"实时可用"档**可选）
 * - 首启 benchmark 弹窗（强制，见 [BenchmarkPrompt]）
 *
 * 注：★⑨ EQ 开关已从本页移除（EQ 仅由「混音」页提供）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val st by vm.state.collectAsState()
    // ★G-1b：首次进入且未检测 → **强制**弹窗（无“稍后”；未测完不能用分离）。
    var showPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(st.benchmarked) {
        if (!st.benchmarked) showPrompt = true
    }

    if (showPrompt) {
        BenchmarkPrompt(
            onRunNow = {
                showPrompt = false
                vm.markBenchmarkPrompted()
                vm.runBenchmark(long = false)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_device), style = MaterialTheme.typography.titleMedium)
        Text(vm.deviceSummary(), style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.invalidateAndRun(long = false) },
                enabled = !st.benchmarking,
            ) { Text(stringResource(if (st.benchmarking) R.string.settings_bench_running else R.string.settings_bench_short_run)) }
            Button(
                onClick = { vm.invalidateAndRun(long = true) },
                enabled = !st.benchmarking,
            ) { Text(stringResource(R.string.settings_bench_long)) }
        }
        // ★G-1c：半态 UI —— 进行中显进度；有未测的已装模型 → “继续检测”（resume）。
        if (st.benchmarking && st.benchTotal > 0) {
            Text(
                stringResource(R.string.settings_bench_progress, st.benchDone, st.benchTotal) +
                    (st.benchCurrentId?.let { stringResource(R.string.settings_bench_current, it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!st.benchmarking && st.installedPending > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_bench_pending, st.installedPending),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.runBenchmark(long = false, resume = true) }) {
                    Text(stringResource(R.string.settings_bench_continue))
                }
            }
        }
        if (!st.benchmarked && !st.benchmarking) {
            Text(
                stringResource(R.string.settings_bench_none),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // ★G-2b：区间推断出的默认模型（仅“预选”提示，非免跑）；未命中精确表时显。
        st.estimatedDefaultId?.let { id ->
            val name = ModelRegistry.byId(id)?.displayName ?: id
            Text(
                stringResource(R.string.settings_estimated, name),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        // 三档分组
        for (tier in SettingsViewModel.Tier.entries) {
            val rows = st.grouped[tier].orEmpty()
            if (rows.isEmpty()) continue
            Text(tierTitle(tier), style = MaterialTheme.typography.titleMedium)
            for (row in rows) {
                ModelCard(
                    row = row,
                    selected = st.defaultEngineId == row.spec.id,
                    selectable = row.spec.id in st.selectableIds && tier == SettingsViewModel.Tier.REALTIME,
                    onSelect = { vm.setDefaultEngine(row.spec.id) },
                )
            }
            Text(
                stringResource(
                    when (tier) {
                        SettingsViewModel.Tier.REALTIME -> R.string.settings_tier_realtime_hint
                        SettingsViewModel.Tier.SLOW -> R.string.settings_tier_slow_hint
                        SettingsViewModel.Tier.VERY_SLOW -> R.string.settings_tier_veryslow_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // （★⑨：设置页 EQ 开关已移除 —— EQ 仅在「混音」页提供，避免双源开关。）
        HorizontalDivider()

        // Step5：转盘一圈时长
        DeckTurnSetting()

        HorizontalDivider()

        // Step5：变速范围（单一项 X → 滑块 [1/X, X]）
        SpeedRangeSetting()

        HorizontalDivider()

        // ★G-3：边分离边播（默认关；仅 2 轨·快速档生效）
        EdgeSeparationSetting()

        HorizontalDivider()

        // ★D-2：分离档位选模型（仅“候选 ≥2”的档展示：2轨·均衡 / 2轨·质量）
        TierModelSetting()
    }
}

/**
 * Step5：设置「转盘一圈时长」（秒）。1.0× 时转一圈 = 该值；实际 = 值 ÷ speed。
 * 范围 9–36，step 3，默认 18。
 */
@Composable
private fun DeckTurnSetting() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    var sec by remember { mutableIntStateOf(repo.deckSecondsPerTurn) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_deck_turn), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_deck_turn_value, sec, sec),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = sec.toFloat(),
            onValueChange = {
                val v = it.toInt()
                    .let { x -> (x / SettingsRepository.DECK_SEC_STEP) * SettingsRepository.DECK_SEC_STEP }
                    .coerceIn(SettingsRepository.DECK_SEC_MIN, SettingsRepository.DECK_SEC_MAX)
                sec = v
                repo.deckSecondsPerTurn = v
            },
            valueRange = SettingsRepository.DECK_SEC_MIN.toFloat()..SettingsRepository.DECK_SEC_MAX.toFloat(),
            steps = (SettingsRepository.DECK_SEC_MAX - SettingsRepository.DECK_SEC_MIN) / SettingsRepository.DECK_SEC_STEP - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * ★G-3：边分离边播开关（默认关）。
 * 开启后，在「分离」页选 **2 轨·快速** 档时，分离从**当前播放位置**开始，
 * 首段就绪后自动切到分离结果播放（而不必等整首分完）。
 */
@Composable
private fun EdgeSeparationSetting() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    var enabled by remember { mutableStateOf(repo.edgeSeparationEnabled) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_edge_sep), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    repo.edgeSeparationEnabled = it
                },
            )
        }
        Text(
            stringResource(R.string.settings_edge_sep_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Step5：设置「变速范围」（单一项 X，>1）。速度滑块区间 = [1/X, X]。
 * X ∈ [1.2, 4]，step 0.1，默认 1.4（→ 滑块 [0.714, 1.4]）。本 X 滑杆为**线性**。
 */
@Composable
private fun SpeedRangeSetting() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    var x by remember { mutableFloatStateOf(repo.speedRangeX) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_speed_range), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_speed_range_value, x, 1f / x, x),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = x,
            onValueChange = { v ->
                val q = SpeedRange.quantizeX(v)
                x = q
                repo.speedRangeX = q
            },
            // 无刻度点
            steps = 0,
            valueRange = SpeedRange.X_MIN..SpeedRange.X_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.settings_speed_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun tierTitle(tier: SettingsViewModel.Tier): String = stringResource(
    when (tier) {
        SettingsViewModel.Tier.REALTIME -> R.string.settings_tier_realtime
        SettingsViewModel.Tier.SLOW -> R.string.settings_tier_slow
        SettingsViewModel.Tier.VERY_SLOW -> R.string.settings_tier_veryslow
    },
)

@Composable
private fun ModelCard(
    row: SettingsViewModel.ModelRow,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                enabled = selectable,
                onClick = onSelect,
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    buildString {
                        append(row.spec.displayName)
                        if (!row.installed) append(stringResource(R.string.settings_model_not_installed))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_model_row, row.spec.sizeMb, row.spec.purpose.toString(), row.rtfText),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** ★G-1b：**强制**检测弹窗（无“稍后”/不可取消）——未检测不能用分离。 */
@Composable
private fun BenchmarkPrompt(onRunNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 不可取消：必须检测 */ },
        title = { Text(stringResource(R.string.settings_prompt_title)) },
        text = {
            Text(stringResource(R.string.settings_prompt_body))
        },
        confirmButton = { TextButton(onClick = onRunNow) { Text(stringResource(R.string.settings_prompt_run)) } },
    )
}

/**
 * ★D-2：分离档位选模型。
 *
 * 只展示「候选 ≥ 2」的档（= 2轨·均衡 / 2轨·质量）；其余档固定单模型，无需选择。
 * 选中项持久化到 [SettingsRepository]；分离页按“档位”跑时取这里的选择。
 */
@Composable
private fun TierModelSetting() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    // 只有多候选的档需要选择
    val tierRows = remember { TierRegistry.ALL.filter { it.candidates.size > 1 } }
    if (tierRows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_tier_models), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_tier_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tierRows.forEach { tc ->
            var selected by remember { mutableStateOf(repo.tierModelId(tc.stem, tc.tier)) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${tc.stem.label} · ${tc.tier.label}",
                    style = MaterialTheme.typography.titleSmall,
                )
                // 候选以 FilterChip 单选呈现（零新增依赖；与分离页风格一致）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tc.candidates.forEach { opt ->
                        FilterChip(
                            selected = selected == opt.id,
                            onClick = {
                                selected = opt.id
                                repo.setTierModelId(tc.stem, tc.tier, opt.id)
                            },
                            label = { Text(opt.displayName) },
                        )
                    }
                }
            }
        }
    }
}
