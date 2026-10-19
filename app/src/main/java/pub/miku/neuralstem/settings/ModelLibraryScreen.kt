package pub.miku.neuralstem.settings

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R
import pub.miku.neuralstem.spike.DeviceBenchmark
import pub.miku.neuralstem.spike.ModelRegistry
import pub.miku.neuralstem.spike.SpeedTier
import java.io.File

/**
 * ★Step4：**模型库**（全屏二级页）。
 *
 * ## 与旧「设置页内联模型管理」的区别
 * | 旧（`ModelManagerSection`） | 新（本页） |
 * |---|---|
 * | 8 张卡片平铺，找模型靠滚 | **按体积分节可折叠**（体积 = 速度簇的物理成因） |
 * | 只有 名称/体积/许可/已装 | 多一列 **实测 RTF**（来自 `DeviceBenchmark.cached()`） |
 * | 只能下载/重下 | 多了 **删除**（带二次确认） |
 *
 * ## 为何按体积分组
 * 19 个模型实测 RTF **与名字无关、与体积强相关**（`doc/model_speed_tiers.md`）：
 * 28MB→1.0× / 39MB→8.6× / 50MB→0.52× / 56MB→0.41× / 64MB→0.35× / 172MB→0.06×。
 * 用户看到「64 MB 这一组都是 0.35×」，比看任何说明都直观。
 *
 * ## 交互
 * - 顶部固定：下载源 FilterChip + 解析出的实际源 + GitHub 代理
 * - 列表：`▸ 39 MB · 2 个模型`（点节头折叠）；默认**只展开含已装模型的节**
 * - 每行右侧三个按钮：已装标记 / 下载 / 删除；下载中显示进度条 + 取消
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelLibraryScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val repo = remember { SettingsRepository(app) }
    val filesDir = remember { ctx.filesDir }
    val downloader = remember {
        ModelDownloader(filesDir).apply {
            // 注入代理前缀：UVR 模型只在 GitHub，国内常需代理
            proxyProvider = { repo.downloadProxy }
        }
    }
    val scope = rememberCoroutineScope()

    // 已安装状态（下载/删除后要刷新 → 用可变 state 承载）
    var installedIds by remember { mutableStateOf(ModelCatalog.installed(filesDir).toSet()) }
    var pref by remember { mutableStateOf(repo.modelSourcePreference) }
    var resolvedSource by remember { mutableStateOf(repo.lastResolvedModelSource) }
    var licenseFor by remember { mutableStateOf<ModelPack?>(null) }
    var deleteFor by remember { mutableStateOf<ModelPack?>(null) }
    var deleteError by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // ★按包分开的状态（支持并发下载多个模型而不互相覆盖）
    val statesMap by downloader.states.collectAsStateInComposition()

    // 实测 RTF（键 = modelId）；模型库只展示，不触发检测
    val rtf = remember { DeviceBenchmark(app).cached().mapValues { it.value.rtf } }

    // ★不接受“有 Done 才刷新”：Done 是瞬时态，并发下载时另一个包可能在其后才写完。
    //   `ModelCatalog.installed()` 只是几次文件 stat，廉价 → 每次状态变化都重算。
    LaunchedEffect(statesMap) {
        val fresh = ModelCatalog.installed(filesDir).toSet()
        if (fresh != installedIds) installedIds = fresh
        resolvedSource = downloader.lastProbedBaseUrl
        resolvedSource?.let { repo.lastResolvedModelSource = it }
    }

    // 体积分节 + 展开状态。
    //
    // ★为何要记“用户手动折叠过哪些”而不是只记“展开哪些”：
    //   旧实现用 `remember { 含已装模型的节 }` 初始化，**此后不再跟随**。
    //   于是：展开→关一节（用户意图要记住）+ 下载完成（理应自动展开新装的那节）
    //   两个需求冲突时只能用一套状态 → 初版选了“记住用户意图”，代价是
    //   “下载完却看不到已安装”（真机实报）。
    //   ⇒ 拆成两个不相交的集合：**自动展开**（含已装模型的节）∪ **用户手动展开** − **用户手动折叠**。
    val groups = remember { ModelCatalog.groupBySize() }
    var manualOpen by remember { mutableStateOf(emptySet<Long>()) }
    var manualClosed by remember { mutableStateOf(emptySet<Long>()) }
    // 含已装模型的节“自动”展开（派生值：下载/删除后会跟着变）
    val installedBuckets = remember(installedIds) {
        groups.filter { (_, list) -> list.any { it.pack in installedIds } }
            .map { it.first }.toSet()
    }
    fun isOpen(bytes: Long) = (bytes in installedBuckets || bytes in manualOpen) && bytes !in manualClosed

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_page_models)) },
                navigationIcon = {
                    // 零图标依赖：用文本字形做返回
                    TextButton(onClick = onBack) {
                        Text("‹ " + stringResource(R.string.page_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------- 顶部固定：下载源 + 代理 ----------------
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_models_source),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelSourcePreference.entries.forEach { p ->
                            FilterChip(
                                selected = pref == p,
                                onClick = {
                                    pref = p
                                    repo.modelSourcePreference = p
                                    downloader.clearProbeCache() // 换源后重新探测
                                },
                                label = { Text(ModelSources.label(p), maxLines = 1) },
                            )
                        }
                    }
                    resolvedSource?.let {
                        Text(
                            "→ $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    GithubProxySetting(repo = repo)
                    HorizontalDivider()

                    // 汇总：已装 x/y · 共 z
                    val total = ModelCatalog.ALL.size
                    Text(
                        stringResource(
                            R.string.settings_page_models_sub,
                            installedIds.size,
                            total,
                            ModelCatalog.humanSize(ModelCatalog.ALL.sumOf { it.totalBytes }),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------------- 体积分节 ----------------
            groups.forEach { (bytes, list) ->
                val isOpen = isOpen(bytes)
                item(key = "hdr-$bytes") {
                    // 节头：▸ / ▾ + 体积 + 数量 + 该体积簇的典型 RTF
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 用户手动切换：若当前开→记入 closed；若当前关→记入 open 并撤销 closed
                                if (isOpen) {
                                    manualOpen = manualOpen - bytes
                                    manualClosed = manualClosed + bytes
                                } else {
                                    manualClosed = manualClosed - bytes
                                    manualOpen = manualOpen + bytes
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (isOpen) "▾ " else "▸ ") + stringResource(
                                R.string.models_group_header,
                                ModelCatalog.humanSize(bytes),
                                list.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        ModelCatalog.sizeBucketHint(bytes)?.let { hint ->
                            Text(
                                stringResource(R.string.models_group_hint, hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (isOpen) {
                    items(list, key = { it.pack.id }) { info ->
                        ModelRow(
                            info = info,
                            installed = info.pack in installedIds,
                            packState = statesMap[info.pack] ?: ModelDownloader.State.Idle,
                            rtfText = modelRtfText(info, rtf),
                            onDownload = { licenseFor = info.pack },
                            onCancel = { downloadJob?.cancel() },
                            onDelete = { deleteFor = info.pack },
                        )
                    }
                }
            }
        }
    }

    // ---- 许可确认弹窗 ----
    licenseFor?.let { pack ->
        val info = ModelCatalog.info(pack)
        AlertDialog(
            onDismissRequest = { licenseFor = null },
            title = { Text(stringResource(R.string.settings_models_license_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_models_license, info.license),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(info.licenseNote, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.settings_models_source_page, info.sourceUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    licenseFor = null
                    downloadJob = scope.launch { downloader.download(pack, pref) }
                }) { Text(stringResource(R.string.settings_models_license_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { licenseFor = null }) {
                    Text(stringResource(R.string.settings_models_license_cancel))
                }
            },
        )
    }

    // ---- 删除确认弹窗（★Step4 新增能力）----
    deleteFor?.let { pack ->
        val info = ModelCatalog.info(pack)
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.models_delete_body,
                        info.displayName,
                        ModelCatalog.humanSize(info.totalBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    val ok = downloader.delete(pack)
                    installedIds = ModelCatalog.installed(filesDir).toSet()
                    deleteError = !ok
                }) { Text(stringResource(R.string.models_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResource(R.string.settings_models_license_cancel))
                }
            },
        )
    }

    if (deleteError) {
        AlertDialog(
            onDismissRequest = { deleteError = false },
            confirmButton = {
                TextButton(onClick = { deleteError = false }) {
                    Text(stringResource(R.string.settings_models_license_cancel))
                }
            },
            text = { Text(stringResource(R.string.models_delete_failed)) },
        )
    }
}

/**
 * 一行模型：`名称` / `体积 · 许可 · 实测RTF` / 右侧操作。
 */
@Composable
private fun ModelRow(
    info: ModelPackInfo,
    installed: Boolean,
    packState: ModelDownloader.State,
    rtfText: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloading = packState is ModelDownloader.State.Downloading
    // 已安装 = 磁盘上完整，**或者本次刚下载完**（磁盘检查可能受刷新时机影响）
    val shownInstalled = installed || packState is ModelDownloader.State.Done

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                info.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ModelCatalog.humanSize(info.totalBytes) + " · " +
                    stringResource(R.string.settings_models_license, info.license) + " · " +
                    stringResource(R.string.models_rtf_value, rtfText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (downloading) {
                val d = packState as ModelDownloader.State.Downloading
                LinearProgressIndicator(
                    progress = { d.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.settings_models_downloading,
                            d.percent,
                            ModelCatalog.humanSize(d.bytesDone) + " / " +
                                ModelCatalog.humanSize(d.bytesTotal),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.settings_models_cancel))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (shownInstalled) {
                        Text(
                            stringResource(R.string.settings_models_installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    TextButton(onClick = onDownload) {
                        Text(
                            if (shownInstalled) {
                                stringResource(R.string.settings_models_redownload)
                            } else {
                                stringResource(
                                    R.string.settings_models_download,
                                    ModelCatalog.humanSize(info.totalBytes),
                                )
                            },
                        )
                    }
                    if (shownInstalled) {
                        TextButton(onClick = onDelete) {
                            Text(
                                stringResource(R.string.models_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            (packState as? ModelDownloader.State.Failed)?.let { f ->
                Text(
                    stringResource(R.string.settings_models_failed, f.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 该包的「实测 RTF」文案。
 *
 * ## 为何要跨三张表查
 * `DeviceBenchmark` 的键是 **ModelSpec.id**，而模型库遍历的是 **ModelPack**；
 * 两者并不一一对应（见 `ModelCatalogGroupingTest` 的已知缺口测试）：
 * - `SPLEETER_2STEM` → `spleeter-2stems-fp16`
 * - `UVR_*` → 同名 UVR spec（按文件名反查，最稳）
 * - `SPLEETER_4STEM` → `spleeter4`（走 4 轨 DSP，不是 sherpa spec）
 * - `DEMUCS` → `demucs`
 *
 * 查不到 → 显示「未测」。注意 Spleeter 2-stem 的 `sizeMb=39` 对应两个文件共 39MB，
 * 与 bench 里的 8.6× 是同一条记录。
 */
private fun modelRtfText(info: ModelPackInfo, rtf: Map<String, Float>): String {
    val id = when (info.pack) {
        ModelPack.SPLEETER_2STEM -> ModelRegistry.SPLEETER.id
        ModelPack.SPLEETER_4STEM -> "spleeter4"
        ModelPack.DEMUCS -> "demucs"
        else -> {
            // UVR：按首个文件名反查 ModelSpec（文件名唯一，比按 index 更稳）
            val file = info.files.firstOrNull()?.fileName
            ModelRegistry.ALL.firstOrNull { it.fileName == file }?.id
                ?: ModelRegistry.EXPERIMENTAL.firstOrNull { it.fileName == file }?.id
        }
    }
    val v = id?.let { rtf[it] } ?: return ""
    return "%.2f×".format(v)
}

/** 供设置页入口行显示副标题：`已装 x/y · 共 z` 的数据源。 */
internal fun installedSummary(filesDir: File): Triple<Int, Int, String> {
    val installed = ModelCatalog.installed(filesDir).size
    val total = ModelCatalog.ALL.size
    return Triple(installed, total, ModelCatalog.humanSize(ModelCatalog.ALL.sumOf { it.totalBytes }))
}

/** 供设置页入口行显示副标题：`2 轨 n 档启用 · 4 轨 m 档固定`。 */
internal fun policySummary(enabledTwo: Int): Triple<Int, Int, Int> =
    Triple(enabledTwo, SpeedTier.ordered.size, 2)
