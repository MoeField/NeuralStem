package pub.miku.neuralstem.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pub.miku.neuralstem.player.EqScreen
import pub.miku.neuralstem.player.PlayerScreen
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.playlist.PlaylistScreen
import pub.miku.neuralstem.settings.SettingsScreen
import pub.miku.neuralstem.spike.SeparationScreen
import pub.miku.neuralstem.spike.Spleeter4Dsp

/**
 * 应用导航壳（阶段 M2 任务 C；**导航 IA 重构 · 批次 1**）。
 *
 * ## 演进
 * v1：`MainActivity(播放器) + SpikeActivity(分离页)` 两个平级 Activity，
 * 靠 `registerForActivityResult` 传分离结果 —— 引出坑 32（`am start` 直启无父级、setResult 丢失）。
 * v2（M2）：改为 `ModalNavigationDrawer`（☰ 唤起）同 Activity 切换，移除 setResult 链路。
 * **v3（本次）**：改为**底部导航栏**（5 项）—— 高频页一点即达，无需先拉抽屉再点。
 *
 * ## 导航项（用户定稿）
 * `歌单 · 播放器 · 分离 · EQ · 设置`
 * - **歌单排第一**（用户："够得到"），但**启动页仍为播放器**。
 * - **EQ 独立成页**（从播放器折叠卡片 + 设置页开关收敛到一处）。
 * - **关于**并入设置页（设置页底部一节）。
 *
 * 分离结果**直接回调**给播放器 VM（不再跨 Activity）。
 *
 * 不引入 Navigation 库（页面不多，用枚举状态切换即可）。
 * 图标用 `material-icons-core` 自带的基础矢量（`Icons.Filled.*`）—— 见各 destination 的 icon。
 */
enum class AppDestination(val label: String) {
    PLAYLIST("歌单"),
    PLAYER("播放器"),
    SEPARATE("分离"),
    EQ("EQ"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    playerVm: PlayerViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initial: AppDestination = AppDestination.PLAYER,
) {
    var current by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    // 当前歌单/曲下标（传给歌单页高亮当前曲）。
    val playerState by playerVm.state.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("NeuralStem") })
        },
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = dest == current,
                        onClick = { current = dest },
                        icon = { Text(glyphFor(dest)) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (current) {
            AppDestination.PLAYER -> PlayerScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playerVm,
                onSeparateToggle = { on ->
                    // U3：打开且尚未多轨 → 提示去分离页
                    val handled = playerVm.toggleSeparation(on)
                    if (!handled) {
                        scope.launchSnack(snackbarHostState, "尚未分离：请先在“分离”页分离当前歌曲")
                    }
                },
                // 阶段 3.1：播放页「分离开关」卡片里的指引 → 切到分离导航页
                onOpenSeparate = { current = AppDestination.SEPARATE },
            )

            AppDestination.SEPARATE -> SeparationScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                // 当前播放曲信息（来自 PlayerViewModel，经参数注入以避免平级 VM 相互引用）
                fileName = playerState.fileName,
                playlistName = playerState.playlistName,
                trackIndex = playerState.trackIndex,
                trackCount = playerState.trackCount,
                separationOn = playerState.separationOn,
                currentUri = playerState.currentUri,
                currentRepeatMode = playerState.repeatMode,
                // 分离开关 = 播放页同一状态
                onToggleSeparation = { on ->
                    val handled = playerVm.toggleSeparation(on)
                    if (!handled) {
                        scope.launchSnack(snackbarHostState, "尚未分离：请先分离当前歌曲")
                    }
                },
                // 非实时模型：单曲循环 / 完成后恢复
                onSetRepeatMode = { mode -> playerVm.setRepeatMode(mode) },
                // ★ 批次4：分轨混音（分离调整）搬到分离页
                tracks = playerState.tracks,
                isMultiTrack = playerState.isMultiTrack,
                onSetTrackVolume = playerVm::setTrackVolume,
                onToggleMute = playerVm::toggleMute,
                onToggleSolo = playerVm::toggleSolo,
                onExitMultiTrack = playerVm::exitMultiTrack,
                onPlayStems = { done ->
                    playerVm.loadMultiTrack(
                        listOf(
                            Triple("vocals", "人声", done.vocalsFile),
                            Triple("accompaniment", "伴奏", done.accompanimentFile),
                        ),
                    )
                    // ★ 显式 play（幂等）：不能用 togglePlayPause —— 后台分离期间原曲在播，
                    //   toggle 会反而暂停。
                    playerVm.play()
                    current = AppDestination.PLAYER
                },
                onPlayStems4 = { files ->
                    // M4/S3：4-stem → 四轨播放（顺序 = vocals/drums/bass/other）
                    val labels = listOf("人声", "鼓", "贝斯", "其他")
                    playerVm.loadMultiTrack(
                        files.mapIndexed { i, f ->
                            Triple(Spleeter4Dsp.STEM_KINDS[i], labels.getOrElse(i) { "轨$i" }, f)
                        },
                    )
                    // ★ 显式 play（幂等，同上）
                    playerVm.play()
                    current = AppDestination.PLAYER
                },
            )

            AppDestination.EQ -> EqScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playerVm,
            )

            AppDestination.SETTINGS -> SettingsScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            AppDestination.PLAYLIST -> PlaylistScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                onPlayTrack = { listId, trackIndex ->
                    // ★ 以整个歌单为队列、从该曲开始（歌单 = 播放队列）。
                    playerVm.playPlaylist(listId, trackIndex)
                    current = AppDestination.PLAYER
                },
                onSelectPlaylist = { listId -> playerVm.selectPlaylist(listId) },
                onPlaylistCleared = { listId -> playerVm.onPlaylistCleared(listId) },
                onPlaylistRemoved = { listId -> playerVm.onPlaylistRemoved(listId) },
                onTrackRemoved = { listId, uri -> playerVm.onTrackRemoved(listId, uri) },
                onTrackMoved = { listId, from, to -> playerVm.onTrackMoved(listId, from, to) },
                currentListId = playerState.playlistId,
                currentTrackIndex = playerState.trackIndex,
                currentUri = playerState.currentUri,
            )
        }
    }
}

/** 导航图标：用**文本字形**代替 material-icons（项目既定：零新增图标依赖）。 */
private fun glyphFor(dest: AppDestination): String = when (dest) {
    AppDestination.PLAYLIST -> "\u2630"   // ☰（列表感）
    AppDestination.PLAYER -> "\u25B6"     // ▶
    AppDestination.SEPARATE -> "\u2702"   // ✂（分离）
    AppDestination.EQ -> "\u2637"         // ☷（均衡器横条）
    AppDestination.SETTINGS -> "\u2699"   // ⚙
}

/** 小工具：在给定 scope 里弹 snackbar。 */
private fun CoroutineScope.launchSnack(
    host: SnackbarHostState,
    msg: String,
) {
    launch { host.showSnackbar(msg) }
}
