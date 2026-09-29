package pub.miku.neuralstem.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import pub.miku.neuralstem.BuildConfig

/**
 * 设置页(阶段 M2 任务 E；**IA 重构 批次 1** 去 EQ 开关 + 并入“关于”)。
 *
 * 内容:
 * - 设备能力摘要 + [重新检测(短)] / [长测试]
 * - 模型按 RTF 分三档展示（⚡ 实时 / ⏱ 较慢 / 🐢 很慢），显示体积/用途/预计耗时
 * - 默认引擎选择（**仅"实时可用"档**可选）
 * - 转盘一圈时长 / 变速范围 / 歌单末尾行为
 * - **关于**（原独立导航项，现并入）
 * - 首启 benchmark 弹窗（非强制，见 [BenchmarkPrompt]）
 *
 * > EQ 开关已移至**独立 EQ 页**（导航栏「EQ」），本页不再提供。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
) {
    val st by vm.state.collectAsState()

    // 首次进入且从未询问过 → 弹窗提示(非强制)
    var showPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!st.benchmarked && !vm.wasBenchmarkPrompted) showPrompt = true
    }

    if (showPrompt) {
        BenchmarkPrompt(
            onRunNow = {
                showPrompt = false
                vm.markBenchmarkPrompted()
                vm.runBenchmark(long = false)
            },
            onLater = {
                showPrompt = false
                vm.markBenchmarkPrompted()
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
        if (!st.benchmarked && !st.benchmarking) {
            Text(
                "尚未检测。未检测的模型默认归入「很慢」档。",
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

        // ★ IA 重构（批次 1）：EQ 开关已移至**独立 EQ 页**（导航栏「EQ」）。
        //   设置页不再重复提供开关（避免同一状态两个入口）。

        // UI-V：黑胶转盘一圈时长
        DeckTurnSetting()

        HorizontalDivider()

        // UI-V：变速范围
        SpeedRangeSetting()

        HorizontalDivider()

        // ★3.3：「播放到歌单末尾」已改为**播放页循环按钮**（三态）控制，此处不再提供。

        // ★ IA 重构（批次 1）：「关于」并入设置页（原独立导航项）
        AboutSection()
    }
}

/** 「关于」节（原 AppShell.AboutScreen，随 IA 重构并入设置页）。 */
@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("关于", style = MaterialTheme.typography.titleMedium)
        Text("NeuralStem — 本地 AI 音轨分离播放器", style = MaterialTheme.typography.bodyMedium)
        Text(
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "构建类型 ${BuildConfig.BUILD_TYPE} · 应用 ID ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ★R6：设置「速度范围」（单一项 X，>1）。速度滑块区间 = [1/X, X]。
 * X ∈ [1.2, 4]，step 0.1，默认 1.4（→ 滑块 [0.714, 1.4]）。无刻度点。
 * 播放速度滑块用对数尺度（1.0× 居中）；本 X 滑杆为线性。
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
                // 量化到 0.1 网格
                val q = SpeedRange.quantizeX(v)
                x = q
                repo.speedRangeX = q
            },
            // ★无刻度点：steps 传 0（仅连续拖动，不绘制刻度）
            steps = 0,
            valueRange = SpeedRange.X_MIN..SpeedRange.X_MAX,
            // ★左右留空：轨道两端内缩，避免 min/max 处 thumb 贴边够不到
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            "X 同时决定速度滑块的上下限（下限 = 1/X）。默认 1.4 → [0.71, 1.40]。" +
                "与“转盘一圈时长”是两个独立设置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * UI-V：设置「转盘一圈时长」（秒）。1.0× 时转一圈 = 该值；实际 = 值 ÷ speed。
 * 步进：9–36，step 3，默认 18。
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
            // ★左右留空（同上）
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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

@Composable
private fun BenchmarkPrompt(onRunNow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("检测设备性能?") },
        text = {
            Text(
                "首次使用需检测本机运行各分离模型的速度，以便只把「实时可用」的模型作为默认。\n" +
                    "检测很快(数秒)，期间请勿退出。也可以稍后在设置里运行。",
            )
        },
        confirmButton = { TextButton(onClick = onRunNow) { Text("现在检测") } },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后") } },
    )
}
