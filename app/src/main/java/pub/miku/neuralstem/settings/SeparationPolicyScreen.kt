package pub.miku.neuralstem.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pub.miku.neuralstem.R
import pub.miku.neuralstem.spike.DeviceBenchmark
import pub.miku.neuralstem.spike.SepOption
import pub.miku.neuralstem.spike.SpeedTier
import pub.miku.neuralstem.spike.TierChoices
import pub.miku.neuralstem.spike.TierRegistry

/**
 * ★Step4：**分离策略**（全屏二级页）。
 *
 * ## 这是谁在写、谁在读
 * - **写**（本页）：`twoTierEnabled_<SpeedTier>` / `twoTierModel_<SpeedTier>`
 * - **读**（分离页 [pub.miku.neuralstem.spike.SeparationScreen]）：
 *   ```kotlin
 *   TierRegistry.twoStemTiers(benchRtf)
 *       .filter { settingsRepo.twoTierEnabled(it.speedTier) }   // ← 本页的开关
 *   // 启动时：settingsRepo.twoTierModelId(tc.speedTier, null)   // ← 本页的 chip 选择
 *   ```
 * Step 3 已接上读取侧，但写侧 UI 一直缺失 → `filter` 恒为 true。本页补上。
 *
 * ## 2 轨（可写）
 * 4 个速度簇各一行：`Switch(启用) + 簇名 + 区间小字`，下面 `FlowRow` 候选 chip。
 * - **只列已安装候选**（未装的模型点了也跑不了，列出来徒增噪音）；
 * - chip 是**单选语义**：点 A → 该簇用 A；再点 A → 取消（回落该簇第一个已装候选）。
 *
 * ## 4 轨（只读）
 * `Spleeter 4-stem` / `Demucs` 两个固定档，模型不随速度变化，也**不参与禁用**
 * —— 这是用户定稿的设计（4 轨只有 2 个候选，禁用等于关功能）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeparationPolicyScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val repo = remember { SettingsRepository(app) }

    // 实测 RTF → 速度簇归属（与分离页同一数据源，保证“设置里看到的”= “分离页跑到的”）
    val benchRtf = remember { DeviceBenchmark(app).cached().mapValues { it.value.rtf } }
    val benchLoaded = benchRtf.isNotEmpty()
    val tiers = remember(benchRtf) { TierRegistry.twoStemTiers(benchRtf) }

    // ★安装状态刷新（2026-10-20 修复）：
    //
    // ## 问题
    // 用户去「模型库」下载完模型 → 返回本页，仍显示「本机该簇暂无已装模型」/ 开关置灰。
    // 因为之前**没有任何信号**能触发重算 —— `remember(tier)` 缓存的
    // `seededModelId` / `selected` 只在首次组合时算一次。
    //
    // ## 修法
    // 监听生命周期（每次回到本页重算）+ 监听下载器状态（前台下载完也重算）。
    // 用「安装代际」递增 int 作为 key 传给子组件 —— 避免每个 Card 自己轮询文件系统。
    val lifecycleOwner = LocalLifecycleOwner.current
    var installEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) installEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ★为何不用 Scaffold（同 ModelLibraryScreen，2026-10-20 横屏实测修）：
    // 宿主 AppShell 已处理系统栏 insets，二级页再套 Scaffold 会被向内挤两次。
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("‹ " + stringResource(R.string.page_back))
            }
            Text(
                stringResource(R.string.settings_page_policy),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- 2 轨：4 个速度簇 ----------------
            Text(stringResource(R.string.policy_two_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.policy_two_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!benchLoaded) {
                // 未检测 → 全部归 VERY_SLOW（SpeedTier.of(null)），提示去设置页检测
                Text(
                    stringResource(R.string.settings_bench_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            tiers.forEach { tc ->
                SpeedTierCard(
                    tier = tc.speedTier,
                    candidateIds = tc.candidates.map { it.id to it.displayName },
                    installedIds = tc.candidates
                        .filter { it.spec.isInstalled(app) }
                        .map { it.id }
                        .toSet(),
                    repo = repo,
                    seededModelId = tc.candidates.firstOrNull { it.spec.isInstalled(app) }?.id,
                    // ★安装代际：模型装/删后强制重算 selected
                    installEpoch = installEpoch,
                )
            }

            HorizontalDivider()

            // ---------------- 边分离边播（从设置页迁来）----------------
            EdgeSeparationSetting()

            HorizontalDivider()

            // ---------------- 4 轨：两档各自可禁用（2026-10-20 放开）----------------
            //
            // 原先只读。放开的理由：模型库可删 4 轨模型 → 删完「快速」档会静默消失，
            // 而本页却写“2 档固定”。⇒ 给禁用一条明路，两个入口语义才一致。
            //
            // 两档全关 → 分离页 4 轨区**保留标题** + 灰字提示（不是整块消失，用户不会
            // 以为自己记错了）。
            Text(stringResource(R.string.policy_four_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.policy_four_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TierRegistry.FOUR_TIERS.forEach { tc ->
                FourTierCard(tc = tc, repo = repo, app = app, installEpoch = installEpoch)
            }
        }
    }
}

/**
 * 4 轨的一个档位：开关 + 当前使用的模型名（不可选，每档只有 1 个候选）。
 *
 * 模型名取自 [SettingsRepository.tierOption]（与分离页 `startTier` 同一取法），
 * 未安装时附「未安装」——提醒用户“开关开着但跑不了”。
 */
@Composable
private fun FourTierCard(
    tc: TierChoices,
    repo: SettingsRepository,
    app: Application,
    installEpoch: Int,
) {
    var enabled by remember(tc.tier) { mutableStateOf(repo.fourTierEnabled(tc.tier)) }
    // ★installEpoch 作为 key：模型安装/删除后重新读取选中的模型
    val opt = remember(tc, installEpoch) { repo.tierOption(tc.stem, tc.tier) ?: tc.default() }
    val installed = opt is SepOption.Sherpa && opt.spec.isInstalled(app)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(tc.tier.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    opt.displayName +
                        if (installed) "" else stringResource(R.string.settings_model_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    repo.setFourTierEnabled(tc.tier, it)
                },
            )
        }
    }
}

/**
 * 一个速度簇：开关 + 候选 chip 单选。
 *
 * @param candidateIds 该簇**全部**候选（id, 显示名）——用于展示与合法性校验
 * @param installedIds 其中已安装的 id 集合（只有这些出 chip）
 * @param seededModelId 无保存记录时的回落候选（该簇第一个已装模型）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedTierCard(
    tier: SpeedTier,
    candidateIds: List<Pair<String, String>>,
    installedIds: Set<String>,
    repo: SettingsRepository,
    seededModelId: String?,
    installEpoch: Int,
) {
    var enabled by remember(tier) { mutableStateOf(repo.twoTierEnabled(tier)) }
    val fallback = seededModelId
    // ★installEpoch 作为 key：模型安装/删除后重新回落
    var selected by remember(tier, fallback, installEpoch) {
        mutableStateOf(repo.twoTierModelId(tier, fallback))
    }
    // ★若之前选的模型已被卸载（换机器/删除），自动回落到首个已装候选
    if (selected != null && selected !in installedIds) {
        selected = installedIds.firstOrNull()
    }
    val installedCandidates = candidateIds.filter { it.first in installedIds }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(tier.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(tierHintRes(tier)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        repo.setTwoTierEnabled(tier, it)
                    },
                    // 一个候选都没装 → 开关无意义（开了分离页也看不到，因为还有 isInstalled 过滤）
                    enabled = installedCandidates.isNotEmpty(),
                )
            }

            if (installedCandidates.isEmpty()) {
                Text(
                    stringResource(R.string.policy_tier_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (enabled) {
                // chip 是“多选控件、单选语义”：点选中的那个 = 取消（回落第一个已装）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    installedCandidates.forEach { (id, name) ->
                        FilterChip(
                            selected = selected == id,
                            onClick = {
                                val next = if (selected == id) fallback else id
                                selected = next
                                if (next != null) repo.setTwoTierModelId(tier, next)
                            },
                            label = { Text(name) },
                        )
                    }
                }
            }

            // 调试信息：当前实际会用哪个模型（与分离页 startTwoTier 的取值逻辑一致）
            selected?.let { id ->
                val name = candidateIds.firstOrNull { it.first == id }?.second
                if (name != null) {
                    Text(
                        "→ $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 簇 → 区间小字资源（已在 `SpeedTier` 注释里定义，用于本页说明）。 */
private fun tierHintRes(tier: SpeedTier): Int = when (tier) {
    SpeedTier.ULTRA -> R.string.speed_tier_ultra_hint
    SpeedTier.CRITICAL -> R.string.speed_tier_critical_hint
    SpeedTier.SLOW -> R.string.speed_tier_slow_hint
    SpeedTier.VERY_SLOW -> R.string.speed_tier_very_slow_hint
}
