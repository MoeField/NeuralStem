package pub.miku.neuralstem.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R
import pub.miku.neuralstem.player.MixScreen
import pub.miku.neuralstem.player.PlayerScreen
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.playlist.PlaylistScreen
import pub.miku.neuralstem.playlist.PlaylistStore
import pub.miku.neuralstem.playlist.PlaylistViewModel
import pub.miku.neuralstem.settings.SettingsScreen
import pub.miku.neuralstem.spike.SeparationScreen
import pub.miku.neuralstem.spike.Spleeter4Dsp

/**
 * 应用导航壳。
 *
 * ## 演进
 * - v1：`MainActivity(播放器) + SpikeActivity(分离页)` 两个平级 Activity（坑 32：setResult 丢失）。
 * - v2（M2）：改为 `ModalNavigationDrawer`（☰ 唤起）同 Activity 切换。
 * - **v3（恢复计划 Step 1）**：改为**底部导航栏 5 项** —— 高频页一点即达。
 *
 * ## 导航项（用户定稿）
 * `歌单 · 播放器 · 分离 · EQ · 设置`（「关于」待并入设置页）。
 *
 * > **恢复计划说明**：本步**只改导航骨架**；歌单页 / EQ 页先用占位（后续 Step 恢复其实现）。
 *
 * 不引入 Navigation 库（页面不多，用枚举状态切换即可）；
 * 图标用**文本字形**（项目零 material-icons 依赖）。
 */
enum class AppDestination(@StringRes val labelRes: Int) {
    PLAYLIST(R.string.nav_playlist),
    PLAYER(R.string.nav_player),
    SEPARATE(R.string.nav_separate),
    MIX(R.string.nav_mix),
    SETTINGS(R.string.nav_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    playerVm: PlayerViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initial: AppDestination = AppDestination.PLAYER,
) {
    var current by remember { mutableStateOf(initial) }
    // ★⑭b：用于“分离完成但已切歌”→ snackbar 提示。
    val scope = rememberCoroutineScope()
    // 歌单 VM（供歌单页显示 + 点曲时解析 uri）
    val playlistVm: PlaylistViewModel = viewModel()
    // 播放器状态（供分离页传“当前曲”）
    val playerState by playerVm.state.collectAsState()
    // ★R1/R2：当前选中歌单（单一真源；恒存在）；其类型决定播放页是否可导入。
    val playlistLists by playlistVm.lists.collectAsState()
    val selectedListId by playlistVm.selectedListId.collectAsState()
    val selectedList = playlistLists.firstOrNull { it.id == selectedListId }
    val importEnabled = selectedList?.type == PlaylistStore.ListType.FILE

    // ★⑨④：横屏时隐藏 App 顶部标题栏（太占地方）；竖屏保留。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isLandscape) {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            }
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = dest == current,
                        onClick = { current = dest },
                        icon = { Text(glyphFor(dest)) },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        when (current) {
            AppDestination.PLAYER -> PlayerScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playerVm,
                onOpenSpike = { current = AppDestination.SEPARATE },
                // ★R2：仅选中“选择型(FILE)歌单”时可导入；导入结果追加进该歌单。
                importEnabled = importEnabled,
                onImported = { uri, title ->
                    playlistVm.appendTrack(
                        selectedListId,
                        PlaylistStore.TrackRef(uri.toString(), title),
                    )
                },
            )

            AppDestination.SEPARATE -> SeparationScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                // 当前播放曲信息（由宿主注入, 避免平级 VM 相互引用）
                fileName = playerState.fileName,
                separationOn = playerState.separationOn,
                currentUri = playerState.currentUri,
                onToggleSeparation = { on -> playerVm.toggleSeparation(on) },
                // ★⑭b：发起分离时记录“目标曲”，完成时校验。
                onSeparationStarted = { playerVm.markSeparationTarget(it) },
                onPlayStems = { done ->
                    // ★⑭b：若分离期间已切歌 → **不切多轨**，仅提示（防“播旧曲 stem、显新曲”错配）。
                    if (!playerVm.separationMatchesCurrent()) {
                        val t = playerVm.state.value.fileName
                        scope.launch { snackbarHostState.showSnackbar("分离完成（$t），但你已切歌，未切换多轨") }
                    } else {
                        playerVm.loadMultiTrack(
                            listOf(
                                Triple("vocals", "人声", done.vocalsFile),
                                Triple("accompaniment", "伴奏", done.accompanimentFile),
                            ),
                        )
                        playerVm.markStemsAvailable()
                        // ★显式 play（幂等）: 后台分离期间原曲在播, 不能用 togglePlayPause（会暂停）。
                        playerVm.play()
                        current = AppDestination.PLAYER
                    }
                },
                onPlayStems4 = { files ->
                    if (!playerVm.separationMatchesCurrent()) {
                        val t = playerVm.state.value.fileName
                        scope.launch { snackbarHostState.showSnackbar("分离完成（$t），但你已切歌，未切换多轨") }
                    } else {
                        // M4/S3：4-stem → 四轨播放（顺序 = vocals/drums/bass/other）
                        val labels = listOf("人声", "鼓", "贝斯", "其他")
                        playerVm.loadMultiTrack(
                            files.mapIndexed { i, f ->
                                Triple(Spleeter4Dsp.STEM_KINDS[i], labels.getOrElse(i) { "轨$i" }, f)
                            },
                        )
                        playerVm.markStemsAvailable()
                        playerVm.play()
                        current = AppDestination.PLAYER
                    }
                },
            )

            AppDestination.SETTINGS -> SettingsScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            // Step 4（范围B）：歌单页（列表管理 + 持久化）
            AppDestination.PLAYLIST -> PlaylistScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playlistVm,
                // ★R-a：点曲 → **以整个歌单为队列**、从该下标开始播（传 listId 供 T-5 进度记忆）。
                onPlayTrack = { listId, idx ->
                    playlistVm.listById(listId)?.tracks?.let { tracks ->
                        playerVm.playQueue(tracks.map { Uri.parse(it.uri) to it.title }, idx, listId)
                        current = AppDestination.PLAYER
                    }
                },
                // 选中歌单（点卡片头）→ 空歌单则清播放器（R4）；否则从“上次/首曲”开始播队列
                onSelectPlaylist = { listId ->
                    val tracks = playlistVm.listById(listId)?.tracks
                    if (tracks.isNullOrEmpty()) {
                        // ★R4：选中的歌单没曲 → 播放器清空 + 提示导入
                        playerVm.clearCurrent()
                    } else {
                        val list = playlistVm.listById(listId)
                        val last = list?.lastPlayedUri
                        val lastIdx = last?.let { u -> tracks.indexOfFirst { it.uri == u } } ?: -1
                        val idx = if (lastIdx >= 0) lastIdx else 0
                        // ★T-5：若从“上次曲”开始，则带上上次位置续播（否则从 0）。
                        val resumeMs = if (lastIdx >= 0) (list?.lastPositionMs ?: 0L) else 0L
                        playerVm.playQueue(
                            tracks.map { Uri.parse(it.uri) to it.title }, idx, listId, resumeMs,
                        )
                        current = AppDestination.PLAYER
                    }
                },
                // ★R4：清空的若是**当前选中**歌单 → 播放器清曲（非选中无影响）
                onPlaylistCleared = { listId ->
                    if (listId == selectedListId) playerVm.clearCurrent()
                },
                // ★R5：删除的若是**当前选中**歌单（非默认可删）→ VM 回落默认 + 播放器清曲
                onPlaylistRemoved = { listId ->
                    if (listId == selectedListId) playerVm.clearCurrent()
                },
                // 当前播放曲 uri（高亮用；列表选中状态由 VM.selectedListId 派生）
                currentUri = playerState.currentUri,
            )

            // Step 7 / 方案1：混音页（EQ + 分轨混音）
            AppDestination.MIX -> MixScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playerVm,
            )
        }
    }
}

/** 导航图标：用**文本字形**代替 material-icons（项目既定：零新增图标依赖）。 */
private fun glyphFor(dest: AppDestination): String = when (dest) {
    AppDestination.PLAYLIST -> "\u2630"   // ☰
    AppDestination.PLAYER -> "\u25B6"     // ▶
    AppDestination.SEPARATE -> "\u2702"   // ✂
    AppDestination.MIX -> "\u2637"        // ☷
    AppDestination.SETTINGS -> "\u2699"   // ⚙
}

@Composable
private fun PlaceholderScreen(modifier: Modifier, title: String, note: String) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(note, style = MaterialTheme.typography.bodyMedium)
    }
}
