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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.TierRegistry

/**
 * 设置页(阶段 M2 任务 E)。
 *
 * 内容:
 * - 设备能力摘要 + [重新检测(短)] / [长测试]
 * - 模型按 RTF 分三档展示（⚡ 实时 / ⏱ 较慢 / 🐢 很慢），显示体积/用途/预计耗时
 * - 默认引擎选择（**仅"实时可用"档**可选）
 * - EQ 开关（转发给 [PlayerViewModel]）
 * - 首启 benchmark 弹窗（非强制，见 [BenchmarkPrompt]）
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    playerVm: PlayerViewModel,
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
        Text("设备能力", style = MaterialTheme.typography.titleMedium)
        Text(vm.deviceSummary(), style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.invalidateAndRun(long = false) },
                enabled = !st.benchmarking,
            ) { Text(if (st.benchmarking) "检测中…" else "重新检测(短)") }
            Button(
                onClick = { vm.invalidateAndRun(long = true) },
                enabled = !st.benchmarking,
            ) { Text("长测试(更准)") }
        }
        // ★G-1c：半态 UI —— 进行中显进度；有未测的已装模型 → “继续检测”（resume）。
        if (st.benchmarking && st.benchTotal > 0) {
            Text(
                "已测 ${st.benchDone}/${st.benchTotal}" +
                    (st.benchCurrentId?.let { " · 当前: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!st.benchmarking && st.installedPending > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "还有 ${st.installedPending} 个已装模型未测。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.runBenchmark(long = false, resume = true) }) {
                    Text("继续检测")
                }
            }
        }
        if (!st.benchmarked && !st.benchmarking) {
            Text(
                "尚未检测。未检测的模型默认归入「很慢」档。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // ★G-2b：区间推断出的默认模型（仅“预选”提示，非免跑）；未命中精确表时显。
        st.estimatedDefaultId?.let { id ->
            val name = ModelRegistry.byId(id)?.displayName ?: id
            Text(
                "本机未收录，按比例预估推荐默认：$name（预估，非实测）",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        // 三档分组
        for (tier in SettingsViewModel.Tier.entries) {
            val rows = st.grouped[tier].orEmpty()
            if (rows.isEmpty()) continue
            Text(tier.title, style = MaterialTheme.typography.titleMedium)
            for (row in rows) {
                ModelCard(
                    row = row,
                    selected = st.defaultEngineId == row.spec.id,
                    selectable = row.spec.id in st.selectableIds && tier == SettingsViewModel.Tier.REALTIME,
                    onSelect = { vm.setDefaultEngine(row.spec.id) },
                )
            }
            Text(
                when (tier) {
                    SettingsViewModel.Tier.REALTIME -> "默认引擎只能从本档选择。"
                    SettingsViewModel.Tier.SLOW -> "较慢：长篇会等一会儿。"
                    SettingsViewModel.Tier.VERY_SLOW -> "很慢：需等待，不建议作为默认。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        // EQ 开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("均衡器(EQ)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (playerVm.state.collectAsState().value.eqAvailable) {
                        "硬件支持"
                    } else {
                        "未挂载(播放后生效)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = st.eqEnabled,
                onCheckedChange = {
                    vm.setEqEnabled(it)
                    playerVm.setEqEnabled(it)
                },
            )
        }

        HorizontalDivider()

        // Step5：转盘一圈时长
        DeckTurnSetting()

        HorizontalDivider()

        // Step5：变速范围（单一项 X → 滑块 [1/X, X]）
        SpeedRangeSetting()

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
        Text("转盘一圈时长", style = MaterialTheme.typography.titleMedium)
        Text(
            "当前 $sec 秒（1.0× 时；实际一圈 = $sec ÷ 速度）",
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
 * Step5：设置「变速范围」（单一项 X，>1）。速度滑块区间 = [1/X, X]。
 * X ∈ [1.2, 4]，step 0.1，默认 1.4（→ 滑块 [0.714, 1.4]）。本 X 滑杆为**线性**。
 */
@Composable
private fun SpeedRangeSetting() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    var x by remember { mutableFloatStateOf(repo.speedRangeX) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("速度范围", style = MaterialTheme.typography.titleMedium)
        Text(
            "X = %.1f  →  速度区间 [%.2fx, %.2fx]".format(x, 1f / x, x),
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
            "X 同时决定速度滑块的上下限（下限 = 1/X）。默认 1.4 → [0.71, 1.40]。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
                        if (!row.installed) append("  ·  未安装")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${row.spec.sizeMb}MB · ${row.spec.purpose} · 实测 ${row.rtfText}",
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
        title = { Text("首次使用需检测设备性能") },
        text = {
            Text(
                "为只把「实时可用」的模型作为默认，首次使用需检测本机运行各分离模型的速度。\n" +
                    "检测很快(数秒)，期间请勿退出。检测完成前无法使用分离功能。",
            )
        },
        confirmButton = { TextButton(onClick = onRunNow) { Text("现在检测") } },
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
        Text("分离档位模型", style = MaterialTheme.typography.titleMedium)
        Text(
            "单模型档（快速 / 4轨）无需选择；下面只列出有多候选的档。",
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
