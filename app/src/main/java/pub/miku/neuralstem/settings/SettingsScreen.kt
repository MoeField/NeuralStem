package pub.miku.neuralstem.settings

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import pub.miku.neuralstem.spike.SpeedTier
import pub.miku.neuralstem.spike.TierRegistry

/**
 * 设置页（阶段 M2 任务 E；★Step4 2026-10-20 拆分）。
 *
 * ## 拆分后的结构
 * ```
 * 设置（ROOT）
 *   ├─▸ 模型库       → ModelLibraryScreen   （下载/删除/看实测速度）
 *   ├─  设备能力      （摘要 + 重测/长测试 + 未测提示）
 *   ├─▸ 分离策略      → SeparationPolicyScreen（2 轨簇开关/候选 + 4 轨只读）
 *   ├─  转盘一圈时长
 *   └─  速度范围
 * ```
 * **为何不移走「设备能力」**：重测/长测试是**诊断**动作（用完就走），
 * 放根页比再点一层更顺手；而「模型库/分离策略」是**长期调参**，值得独立一页。
 *
 * ## 为何内部 page 状态而不是 navigation
 * `AppShell` 是扁平 `when(current)` + BottomNavigationBar（5 个 tab，无返回栈）。
 * 引 navigation 需改宿主 + 加依赖；本轮只需「设置 → 二级页 → 返回」，
 * 用 `page` + [BackHandler] 足够，**改动面锁在 settings 包内**。
 *
 * ## 已移除（Step 4）
 * - 内联模型列表（→ 模型库）
 * - 旧的「模型按 RTF 三档 + RadioButton 选默认引擎」列表
 *   （职责被「分离策略」接管；`SettingsRepository.defaultEngineId` **字段保留**，
 *   `SpikeViewModel.applyDefaultEngine()` 仍用它做兜底）
 * - `TierModelSetting`（旧 4 轨候选选择 → 分离策略的 4 轨只读区）
 * - `EdgeSeparationSetting`（→ 分离策略页）
 *
 * 注：★⑨ EQ 开关已从本页移除（EQ 仅由「混音」页提供）。
 */

/** 设置页内部的页面。 */
private enum class SettingsPage { ROOT, MODELS, POLICY, NCM }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    /**
     * ★打开「模型库」二级页（2026-10-20）。
     *
     * 用途：分离页在“无可用模型”时提供“去模型库”按钮 → 宿主把用户送到设置页
     * 并直接打开模型库，而不是让他自己找。递增 token 而非布尔：重复点可重触发。
     */
    openModelLibraryToken: Int = 0,
) {
    val st by vm.state.collectAsState()
    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    // ★外部请求打开模型库 → 切到 MODELS 页
    LaunchedEffect(openModelLibraryToken) {
        if (openModelLibraryToken > 0) page = SettingsPage.MODELS
    }

    // 二级页 → 系统返回键回 ROOT（而不是退出 App）
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    when (page) {
        SettingsPage.MODELS -> ModelLibraryScreen(
            modifier = modifier,
            onBack = { page = SettingsPage.ROOT },
        )
        SettingsPage.POLICY -> SeparationPolicyScreen(
            modifier = modifier,
            onBack = { page = SettingsPage.ROOT },
        )
        // ★NCM 导入页：靠 AndroidView 承载 ncm 源集里的 ComposeView。
        //   为何不直接调 @Composable：它编译期需要 Composer 参数，反射调不了；
        //   而 main 源集又不能直接 import（默认构建不编译 ncm 源集）。
        //   故由 ncm 源集提供一个普通 Java/Android 入口 `NcmImportViewHolder.build(ctx, onBack)`。
        SettingsPage.NCM -> androidx.compose.ui.viewinterop.AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { c ->
                runCatching {
                    val cls = Class.forName(
                        "pub.miku.neuralstem.experimental.ncm.NcmImportViewHolder",
                    )
                    val inst = cls.getField("INSTANCE").get(null)
                    cls.getMethod(
                        "build",
                        android.content.Context::class.java,
                        Runnable::class.java,
                    ).invoke(inst, c, Runnable { page = SettingsPage.ROOT }) as android.view.View
                }.getOrElse {
                    android.widget.TextView(c).apply {
                        text = "NCM 导入不可用：${it.message}"
                        setPadding(60, 60, 60, 60)
                    }
                }
            },
        )
        SettingsPage.ROOT -> SettingsRoot(
            modifier = modifier,
            vm = vm,
            st = st,
            onOpenModels = { page = SettingsPage.MODELS },
            onOpenPolicy = { page = SettingsPage.POLICY },
            onOpenNcm = if (pub.miku.neuralstem.BuildConfig.WITH_NCM) {
                { page = SettingsPage.NCM }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SettingsRoot(
    modifier: Modifier,
    vm: SettingsViewModel,
    st: SettingsViewModel.UiState,
    onOpenModels: () -> Unit,
    onOpenPolicy: () -> Unit,
    /** ★NCM 导入入口；null = 不显示（默认构建 / 未启用 NCM）。 */
    onOpenNcm: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current

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

    // 入口行的副标题数据：已装数 / 总数 / 总下载量
    val installedCount = remember(st.benchmarked) {
        ModelCatalog.installed(ctx.filesDir).size
    }
    val totalCount = ModelCatalog.ALL.size
    val totalBytes = remember { ModelCatalog.ALL.sumOf { it.totalBytes } }
    // 2 轨启用档数（读 prefs；进出本页时重算即可）
    val enabledTwo = remember { SpeedTier.ordered.count { SettingsRepository(ctx.applicationContext as Application).twoTierEnabled(it) } }
    // ★4 轨启用档数（2026-10-20 放开禁用；与 2 轨对称展示）
    val enabledFour = remember {
        SettingsRepository(ctx.applicationContext as Application)
            .let { repo -> TierRegistry.FOUR_TIERS.count { repo.fourTierEnabled(it.tier) } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- ▸ 模型库（未装模型时分离不可用，放最前）----
        EntryRow(
            title = stringResource(R.string.settings_page_models),
            subtitle = stringResource(
                R.string.settings_page_models_sub,
                installedCount,
                totalCount,
                ModelCatalog.humanSize(totalBytes),
            ),
            onClick = onOpenModels,
        )

        HorizontalDivider()

        // ---- 设备能力 ----
        Text(stringResource(R.string.settings_device), style = MaterialTheme.typography.titleMedium)
        Text(vm.deviceSummary(), style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.invalidateAndRun(long = false) },
                enabled = !st.benchmarking,
            ) {
                Text(
                    stringResource(
                        if (st.benchmarking) R.string.settings_bench_running
                        else R.string.settings_bench_short_run,
                    ),
                )
            }
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

        // ---- ▸ 分离策略 ----
        EntryRow(
            title = stringResource(R.string.settings_page_policy),
            subtitle = stringResource(
                R.string.settings_page_policy_sub,
                enabledTwo,
                enabledFour,
            ),
            onClick = onOpenPolicy,
        )

        // ---- ▸ NCM 导入（仅 with_ncm 构建；默认构建 onOpenNcm == null 不显示）----
        if (onOpenNcm != null) {
            HorizontalDivider()
            EntryRow(
                title = "NCM 导入",
                subtitle = "从网易云音乐导入加密的 .ncm 文件（需 Shizuku）",
                onClick = onOpenNcm,
            )
        }

        HorizontalDivider()

        // Step5：转盘一圈时长
        DeckTurnSetting()

        HorizontalDivider()

        // Step5：变速范围（单一项 X → 滑块 [1/X, X]）
        SpeedRangeSetting()
    }
}

/**
 * 一个「▸」入口行。
 *
 * 用 Card + `clickable`（零图标依赖，字形 `▸` 与项目风格一致）。
 */
@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("▸", style = MaterialTheme.typography.titleMedium)
        }
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

/** ★G-1b：**强制**检测弹窗（无“稍后”/不可取消）——未检测不能用分离。 */
@Composable
private fun BenchmarkPrompt(onRunNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 不可取消：必须检测 */ },
        title = { Text(stringResource(R.string.settings_prompt_title)) },
        text = { Text(stringResource(R.string.settings_prompt_body)) },
        confirmButton = {
            TextButton(onClick = onRunNow) { Text(stringResource(R.string.settings_prompt_run)) }
        },
    )
}

/**
 * ★G-3：边分离边播开关（默认开）。
 *
 * ★Step4：从设置根页**迁到**「分离策略」页 —— 它本质是分离行为策略，
 * 与簇开关/候选选择同属一类。
 *
 * 开启后，在「分离」页选 **2 轨·极快** 档时，分离从**当前播放位置**开始，
 * 首段就绪后自动切到分离结果播放（而不必等整首分完）。
 */
@Composable
internal fun EdgeSeparationSetting() {
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
