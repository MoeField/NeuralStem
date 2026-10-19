package pub.miku.neuralstem.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R

/**
 * ★模型一键下载：设置页的「模型管理」区块。
 *
 * ## 为什么需要
 * 此前模型必须 adb push 到私有目录，别人装了 APK **用不了分离功能**。
 *
 * ## 交互
 * 1. 列出全部模型包（名称 / 体积 / 许可 / 是否已装）
 * 2. 点「下载」→ 先弹**许可确认**（逐模型如实说明，含 Demucs 转换仓库未声明许可的情形）
 * 3. 下载中显示进度 + 可取消；完成后即时变为「✓ 已安装」
 * 4. 顶部「下载源」：自动 / 官方 HF / hf-mirror
 */
@Composable
fun ModelManagerSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext as Application) }
    val filesDir = remember { ctx.filesDir }
    val downloader = remember { ModelDownloader(filesDir) }
    val scope = rememberCoroutineScope()

    // 已安装状态（下载完成后要刷新 → 用可变 state 承载）
    var installedIds by remember { mutableStateOf(ModelCatalog.installed(filesDir).toSet()) }
    var pref by remember { mutableStateOf(repo.modelSourcePreference) }
    var resolvedSource by remember { mutableStateOf(repo.lastResolvedModelSource) }
    // 许可确认中的包
    var licenseFor by remember { mutableStateOf<ModelPack?>(null) }
    // 当前下载任务
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // ★按包分开的状态（支持并发下载多个模型而不互相覆盖）
    val statesMap by downloader.states.collectAsStateInComposition()

    // 下载完成 → 刷新已安装集合
    //
    // ★为何不接受“有 Done 才刷新”：Done 是**瞬时态**，并发下载时另一个包
    //   可能在 Done 之后才写完 → 只刷新一次会漏。
    //   `ModelCatalog.installed()` 只是几次文件 stat，廉价 → 每次状态变化都重算。
    LaunchedEffect(statesMap) {
        val fresh = ModelCatalog.installed(filesDir).toSet()
        if (fresh != installedIds) installedIds = fresh
        resolvedSource = downloader.lastProbedBaseUrl
        resolvedSource?.let { repo.lastResolvedModelSource = it }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_models), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 下载源 ----
        Text(stringResource(R.string.settings_models_source), style = MaterialTheme.typography.labelLarge)
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
                "→ " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ---- 各模型包 ----
        ModelCatalog.ALL.forEach { info ->
            val packState = statesMap[info.pack] ?: ModelDownloader.State.Idle
            val downloading = packState is ModelDownloader.State.Downloading
            // 已安装 = 磁盘上完整，**或者”本次刚下载完”**（磁盘检查可能受刷新时机影响）。
            val installed = info.pack in installedIds ||
                packState is ModelDownloader.State.Done
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(info.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_models_license, info.license) +
                            " · " + ModelCatalog.humanSize(info.totalBytes),
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
                            TextButton(onClick = { downloadJob?.cancel() }) {
                                Text(stringResource(R.string.settings_models_cancel))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (installed) stringResource(R.string.settings_models_installed) else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(onClick = { licenseFor = info.pack }) {
                                Text(
                                    if (installed) {
                                        stringResource(R.string.settings_models_redownload)
                                    } else {
                                        stringResource(
                                            R.string.settings_models_download,
                                            ModelCatalog.humanSize(info.totalBytes),
                                        )
                                    },
                                )
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
}

/**
 * `collectAsStateWithLifecycle` 的轻量替代。
 *
 * 不引 `lifecycle-runtime-compose` 依赖（项目当前没有），
 * 用 `LaunchedEffect` + `mutableStateOf` 实现同等效果：
 * 进入 composition 开始收集，离开自动取消。
 */
@Composable
private fun <T> StateFlow<T>.collectAsStateInComposition(): State<T> {
    val state = remember { mutableStateOf(value) }
    LaunchedEffect(this) {
        collect { state.value = it }
    }
    return state
}
