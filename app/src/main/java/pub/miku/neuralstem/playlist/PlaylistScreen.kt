package pub.miku.neuralstem.playlist

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R

/**
 * 阶段 UI / U5：**歌单页**。
 *
 * - 多列表：新建 / 删列表；点列表展开曲目。
 * - 文件夹导入：SAF 选目录（[ActivityResultContracts.OpenDocumentTree]），递归入表。
 * - **移除只移列表**（删列表项/曲目不动磁盘文件）。
 * - 点曲目 → 交给播放器 VM 播放原曲。
 */
@Composable
fun PlaylistScreen(
    modifier: Modifier = Modifier,
    vm: PlaylistViewModel = viewModel(),
    // 点曲目 → 交给播放器：以**整个歌单为队列**、从该下标开始。
    onPlayTrack: (listId: String, trackIndex: Int) -> Unit = { _, _ -> },
    // 选中歌单（点卡片头）→ 停当前 + 播目标歌单的“上次/首曲”。
    onSelectPlaylist: (listId: String) -> Unit = {},
    // 歌单被**清空**后通知（播放器侧：若是当前歌单 → 停播+清队列，但**保留选中**）。
    onPlaylistCleared: (listId: String) -> Unit = {},
    // 歌单被**删除**后通知（若是当前歌单 → 停播+清队列+取消选中）。
    onPlaylistRemoved: (listId: String) -> Unit = {},
    // 从歌单**移除一首**后通知（若是当前歌单 → 同步队列）。
    onTrackRemoved: (listId: String, removedUri: String) -> Unit = { _, _ -> },
    // 歌单**排序**后通知（若是当前歌单 → 同步队列顺序）。
    onTrackMoved: (listId: String, from: Int, to: Int) -> Unit = { _, _, _ -> },
    // 当前正在播放的曲目下标（用于高亮）；列表选中状态由 VM 的 selectedListId 派生。
    currentTrackIndex: Int = -1,
    // ★ 当前播放曲的 uri —— **高亮按 uri（曲目身份）**，排序/删其它曲后仍正确。
    currentUri: String? = null,
) {
    val lists by vm.lists.collectAsState()
    // ★R1：当前选中歌单（单一真源，来自 VM；恒存在）。
    val selectedListId by vm.selectedListId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // ★ 进入歌单页 / 当前曲目变化时**重新加载**：歌单可能被播放器侧（导入等）改过，
    //   而本 VM 的 _lists 是内存缓存 → 否则数量/曲目显示陈旧。
    LaunchedEffect(selectedListId) { vm.reload() }
    var newName by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // ★ 持久化 tree 权限：否则 app 重启/后续访问该文件夹下的子文档时
            //   会 SecurityException → 播放报 ERROR_CODE_IO_UNSPECIFIED。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                // ★ 选文件夹导入 = **总是新建一个文件夹歌单**
                vm.importFolder(uri, intoListId = null)
            }
        }
    }

    // ★⑪：横屏一行（否则下方没空间选歌单）；竖屏两行（命名单独一行，不挤）。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ★⑩：去掉顶部"歌单"大标题（底栏已有"歌单"tab，避免重复）。
        val nameField: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.pl_new_name)) },
                singleLine = true,
                modifier = m,
            )
        }
        val createBtn: @Composable () -> Unit = {
            Button(onClick = {
                if (newName.isNotBlank()) {
                    vm.createList(newName.trim()); newName = ""
                }
            }) { Text(stringResource(R.string.pl_create), maxLines = 1) }
        }
        val importBtn: @Composable () -> Unit = {
            Button(onClick = { pickFolder.launch(null) }) {
                Text(stringResource(R.string.pl_import_folder), maxLines = 1)
            }
        }
        if (isLandscape) {
            // 横屏：三件挤一行
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                nameField(Modifier.weight(1f))
                createBtn()
                importBtn()
            }
        } else {
            // 竖屏：命名单独一行；两个按钮一行
            nameField(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                createBtn()
                importBtn()
            }
        }

        HorizontalDivider()

        if (lists.isEmpty()) {
            Text(stringResource(R.string.pl_empty), style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lists, key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // ★ 点左侧信息区 = 选中该歌单并播放（停当前 → 播它上次/首曲）
                            Column(
                                modifier = Modifier.weight(1f)
                                    .clickable { vm.selectList(p.id); onSelectPlaylist(p.id) },
                            ) {
                                // ★R1：高亮 = 当前**选中**歌单（恒存在）
                                val isSelected = p.id == selectedListId
                                Text(
                                    (if (isSelected) "♪ " else "") + p.name +
                                        if (p.isDefault) stringResource(R.string.pl_default_suffix) else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    stringResource(
                                        if (p.type == PlaylistStore.ListType.FOLDER) R.string.pl_summary_folder
                                        else R.string.pl_summary_file,
                                        p.tracks.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.isDefault) {
                                // 默认歌单：不可删；提供“清空”（清空后若正在播它 → 停播，但**保留选中**）
                                TextButton(onClick = { vm.clearList(p.id); onPlaylistCleared(p.id) }) { Text(stringResource(R.string.pl_clear)) }
                            } else {
                                TextButton(onClick = { vm.deleteList(p.id); onPlaylistRemoved(p.id) }) { Text(stringResource(R.string.pl_delete)) }
                            }
                            // ★ 展开/收起：独立箭头按钮
                            TextButton(onClick = {
                                expandedId = if (expandedId == p.id) null else p.id
                            }) { Text(if (expandedId == p.id) "▴" else "▾") }
                        }

                        if (expandedId == p.id) {
                            p.tracks.forEachIndexed { idx, t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { onPlayTrack(p.id, idx) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // ★ 高亮按“曲目身份（uri）”而非 index：排序/删其它曲后仍正确。
                                    val isCurrent = p.id == selectedListId &&
                                        (if (currentUri != null) t.uri == currentUri
                                        else idx == currentTrackIndex)
                                    Text(
                                        (if (isCurrent) "▶ " else "") + t.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // 排序：仅**文件歌单**可排（文件夹歌单随外部文件夹）
                                    if (p.type == PlaylistStore.ListType.FILE) {
                                        TextButton(
                                            onClick = { vm.moveTrack(p.id, idx, idx - 1); onTrackMoved(p.id, idx, idx - 1) },
                                            enabled = idx > 0,
                                        ) { Text("↑") }
                                        TextButton(
                                            onClick = { vm.moveTrack(p.id, idx, idx + 1); onTrackMoved(p.id, idx, idx + 1) },
                                            enabled = idx < p.tracks.size - 1,
                                        ) { Text("↓") }
                                        // ★R3：移除仅**文件歌单**可用（文件夹歌单由文件管理器代劳）
                                        TextButton(onClick = {
                                            vm.removeTrack(p.id, t.uri)
                                            onTrackRemoved(p.id, t.uri)
                                        }) {
                                            Text(stringResource(R.string.pl_remove))
                                        }
                                    }
                                }
                            }
                            if (p.folderUri != null) {
                                TextButton(onClick = { scope.launch { vm.rescanFolder(p.id) } }) {
                                    Text(stringResource(R.string.pl_rescan))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
