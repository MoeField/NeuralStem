package pub.miku.neuralstem.experimental.ncm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

/**
 * ★NCM 导入页（设置 › NCM 导入）。
 *
 * ## 用户要走的路径
 * ```
 * ① 没装 Shizuku   → 显示「去安装」引导（附说明为何需要）
 * ② 装了没授权      → 一个「授权」按钮，点了弹 Shizuku 的授权框
 * ③ 已授权          → 列出网易云目录里的 .ncm，可单选/全选导入
 * ④ 导入完成        → 显示成功数量 + **AVS3 提示**
 * ```
 *
 * ## ★ 为何“导入前”就要提示 AVS3
 * 实测用户 98% 的文件是 AVS3-P3 编码，Android 播不了。
 * 如果先导入 400 首、再逐首发现播不了，体验会很差。
 * ⇒ 在列表页顶部**先扫一批**并显示编码分布，让用户知道预期。
 *
 * ## 只在 `-Pwith_ncm=true` 构建中存在
 */
@Composable
fun NcmImportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<NcmImporter.Status?>(null) }
    var sourceDir by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<NcmImporter.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var importedCount by remember { mutableStateOf(0) }
    // ★「全部加入歌单」的独立状态（与上面字节导入的 busy 分开，两者可分别进行）
    var scanBusy by remember { mutableStateOf(false) }
    var scanMsg by remember { mutableStateOf("") }

    /** 刷新：查状态 → 找目录 → 列文件。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        loading = true
        runCatching {
            status = NcmImporter.status(ctx)
            if (status is NcmImporter.Status.Ready) {
                val dirs = NcmImporter.findSourceDirs()
                sourceDir = dirs.firstOrNull()
                entries = sourceDir?.let { NcmImporter.listNcm(it) } ?: emptyList()
                importedCount = NcmImporter.listImported(ctx).size
            }
        }.onFailure { message = "刷新失败：${it.message}" }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(
                "NCM 导入",
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
            // ---- 说明 ----
            Text(
                "网易云把下载的歌加密成 .ncm，存在它自己的私有目录里，" +
                    "普通应用无法读取。本工具借助 Shizuku 把这些文件复制到本应用，" +
                    "即可在歌单中播放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ---- 状态区 ----
            when (val s = status) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    Text("检查 Shizuku…", modifier = Modifier.padding(start = 8.dp))
                }

                NcmImporter.Status.ShizukuNotInstalled -> StatusCard(
                    title = "未安装 Shizuku",
                    body = "Shizuku 是一个免 root 的权限工具，本功能需要它来读取网易云的私有目录。" +
                        "请先从应用商店或 GitHub 安装 Shizuku。",
                    actionLabel = null,
                )

                NcmImporter.Status.ShizukuNotRunning -> StatusCard(
                    title = "Shizuku 未运行",
                    body = "请打开 Shizuku App，按提示启动服务（无线调试或连接电脑）。",
                    actionLabel = "重新检查",
                    onAction = { scope.launch { refresh() } },
                )

                NcmImporter.Status.PermissionNotGranted -> StatusCard(
                    title = "需要授权",
                    body = "本应用还没拿到 Shizuku 权限。点下面的按钮，在弹窗中选「允许」。",
                    actionLabel = "去授权",
                    onAction = {
                        scope.launch {
                            val ok = withContext(Dispatchers.Main) {
                                NcmImporter.requestPermission()
                            }
                            message = if (ok) "已请求授权，请在弹窗中选「允许」，然后点「重新检查」"
                            else "请求授权失败"
                        }
                    },
                )

                is NcmImporter.Status.Ready -> {
                    Text(
                        "✅ Shizuku 已就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    if (sourceDir == null) {
                        StatusCard(
                            title = "没找到网易云音乐目录",
                            body = "请确认已安装网易云音乐，且在 App 内下载过歌曲。",
                            actionLabel = "重新检查",
                            onAction = { scope.launch { refresh() } },
                        )
                    } else {
                        Text(
                            "找到 ${entries.size} 个 .ncm 文件",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            sourceDir ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // ---- ★ AVS3 预警 ----
                        Avs3Warning()

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && entries.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        message = null
                                        val r = withContext(Dispatchers.IO) {
                                            NcmImporter.importByPull(
                                                ctx,
                                                entries.take(10).map { it.absolutePath },
                                            )
                                        }
                                        importedCount = NcmImporter.listImported(ctx).size
                                        message = r.summary()
                                        busy = false
                                    }
                                },
                            ) { Text(if (busy) "导入中…" else "导入前 10 首") }

                            OutlinedButton(
                                enabled = !busy,
                                onClick = { scope.launch { refresh() } },
                            ) { Text("刷新") }
                        }

                        /*
                         * ★「全部加入歌单」（2026-10-26）
                         *
                         * 与上面的「导入前 10 首」是**两件不同的事**：
                         * - 「导入」= 用 Shizuku 把**字节**拉进 App 目录（落盘，占空间）
                         * - 「加入歌单」= 只在歌单里存**逻辑引用**（netease://，不占空间）
                         *
                         * 后者才是 1000 首规模下的正确做法：
                         * 一首 20~30MB，1000 首复制进来要 25GB，不可接受。
                         *
                         * 扫描时逐个探 codec，标题带状态后缀（[需解码] / [加密]），
                         * 让用户一眼看出哪些能直接播。
                         */
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        Text(
                            "加入歌单（不复制文件，只存引用）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "扫描全部 ${entries.size} 个文件并写入默认歌单。" +
                                "不会占用额外空间 —— 播放时才借 Shizuku 读取。",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (scanBusy || scanMsg.isNotEmpty()) {
                            Text(
                                scanMsg,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && !scanBusy && entries.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        scanBusy = true
                                        scanMsg = "扫描中…"
                                        val scanned = withContext(Dispatchers.IO) {
                                            NeteasePlaylistImporter.scan(
                                                ctx,
                                                onProgress = { done, total, name ->
                                                    scanMsg = "扫描 $done/$total：${name.take(28)}"
                                                },
                                            )
                                        }
                                        scanMsg = "扫描完成，写入歌单…"
                                        val added = withContext(Dispatchers.IO) {
                                            NeteasePlaylistImporter.importToPlaylist(ctx, scanned)
                                        }
                                        val stat = NeteasePlaylistImporter.summarize(scanned)
                                        scanMsg = "已加入歌单 $added 首（${stat.summary()}）"
                                        scanBusy = false
                                    }
                                },
                            ) { Text(if (scanBusy) "处理中…" else "全部加入歌单") }
                        }

                        if (importedCount > 0) {
                            Text(
                                "已导入 $importedCount 首 → 可在「歌单」页查看",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            message?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/**
 * AVS3 预警卡片。
 *
 * ## 为什么要单独一块
 * 用户 98% 的文件是 AVS3-P3 编码，Android 不支持。若不提前说，
 * 用户会以为是 App 的 bug（“导入了却播不了”）。
 */
@Composable
private fun Avs3Warning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "⚠️ 大部分网易云文件可能无法播放",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "实测测试中，约 98% 的网易云下载文件使用 AVS3-P3 编码，" +
                    "Android 系统不支持这种格式（FLAC / AAC / MP3 则可以正常播放）。" +
                    "这不是本应用的缺陷。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** 状态卡片：标题 + 说明 + 可选按钮。 */
@Composable
private fun StatusCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
