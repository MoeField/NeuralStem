package pub.miku.neuralstem.ui

import android.app.Application
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pub.miku.neuralstem.R
import pub.miku.neuralstem.player.MixScreen
import pub.miku.neuralstem.player.PlayerScreen
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.playlist.PlaylistScreen
import pub.miku.neuralstem.playlist.PlaylistStore
import pub.miku.neuralstem.playlist.PlaylistViewModel
import pub.miku.neuralstem.settings.SettingsScreen
import pub.miku.neuralstem.spike.EdgeSeparationSession
import pub.miku.neuralstem.spike.SeparationRepository
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
    // ★G-3ux：用户在播放页点开分离开关但**无缓存** → 跳分离页时**自动开始分离**，
    //   不用用户再手动点一次「快速」。传一个递增的 token 而非布尔（可重复触发）。
    var autoStartSeparate by remember { mutableStateOf(0) }
    // ★分离页“无可用模型 → 去模型库”：递增 token，既跳设置页又直接打开模型库。
    var openModelLibToken by remember { mutableStateOf(0) }
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

    // ★G-3：边分离边播——首段就绪 → **自动切**到流式多轨（位置 carry-over）。
    //   观察进程单例会话状态；仅在首次转到“首段就绪”时触发一次。
    val edgeSession = remember { EdgeSeparationSession.get() }
    val edgeState by edgeSession.state.collectAsState()
    // ★闸门（2026-10-20）：切轨条件从“首段就绪”改为“水位线领先够”。
    //   旧逻辑在首块产出（~4s）就切轨 → 播 4s 就掉进预分配静音区（用户实报“放一小段没声”）。
    val edgeGate by edgeSession.gate.collectAsState()
    var edgeSwitched by remember { mutableStateOf(false) }
    // ★G-3ux：Snackbar 文案在 Composable 上下文取（协程内不能用 stringResource）。
    val edgeSwitchedMsg = stringResource(R.string.sep_edge_switched)
    val edgeAbortedMsg = stringResource(R.string.sep_edge_aborted)
    val edgeBufferingMsg = stringResource(R.string.sep_edge_buffering)

    // ★G-3fix：分离完成（EdgeDone）→ 若为旧“半段文件”模式则切到文件播放。
    //   新版（完整曲长文件）在首段就绪时就已经切过去，此处无需再动。
    val appCtx = LocalContext.current.applicationContext as Application
    val repo = remember { SeparationRepository.get(appCtx) }
    val repoState by repo.state.collectAsState()
    LaunchedEffect(repoState) {
        val rs = repoState as? SeparationRepository.State.EdgeDone ?: return@LaunchedEffect
        val vf = rs.vocalsFile
        val af = rs.accompanimentFile
        if (vf == null || af == null || !vf.exists() || !af.exists()) {
            Log.w("AppShell", "EdgeDone 但无文件 → 保持当前播放")
            return@LaunchedEffect
        }
        // 文件模式下，首段就绪时已经切到这些文件；这里只做一次幂等刷新（水位线置满后）。
        if (playerVm.state.value.separationOn) {
            Log.i("AppShell", "EdgeDone：已在多轨文件播放，水位线已置满（全曲可 seek）")
        }
    }

    // ★缓冲提示（闸门版）：分离在跑、但水位线还没领先够 → 告诉用户“在缓冲”，
    //   而不是傻等（用户不知道为何还没切到分离结果）。
    LaunchedEffect(edgeGate) {
        if (edgeGate is EdgeSeparationSession.Gate.Buffering && !edgeSwitched) {
            scope.launch {
                snackbarHostState.showSnackbar(edgeBufferingMsg)
            }
        }
    }

    LaunchedEffect(edgeState, edgeGate) {
        val st = edgeState
        val gate = edgeGate
        if (st is EdgeSeparationSession.State.Preparing) edgeSwitched = false
        // ★切轨条件 = **闸门**（不是分离进度）：
        //   Playable = 水位线已领先 startFrame 至少 LEAD_FRAMES→ 现在切过去能连续出声；
        //   Finished = 分离已跑完，整曲都有数据 → 自然也可切。
        val ready = gate is EdgeSeparationSession.Gate.Playable ||
            gate is EdgeSeparationSession.Gate.Finished
        if (ready && !edgeSwitched) {
            edgeSwitched = true
            val sr = edgeSession.sampleRate.coerceAtLeast(1)
            val posMs = edgeSession.startFrame.toLong() * 1000L / sr
            val vf = edgeSession.vocalsFile
            val af = edgeSession.accompFile
            if (vf != null && af != null) {
                // ★G-3-重构（文件模式）：直接播**完整曲长的 stem 文件**。
                //   文件从 0 就是对的（未分离区是静音），所以：
                //   - 不需要 seek 补偿（位置天然对齐）；
                //   - 不会“跳回起点”；
                //   - 波形与文件时长一致。
                //
                // ★闸门（2026-10-20）：此时水位线已领先 startFrame 至少 LEAD_FRAMES，
                //   所以从这里起播能连续出声，不会撞上未分离的静音区。
                Log.i("AppShell", "edge gate=${edgeGate} → 文件模式播 ${vf.name} @${posMs}ms")
                playerVm.loadMultiTrack(
                    listOf(
                        Triple("vocals", "人声", vf),
                        Triple("accompaniment", "伴奏", af),
                    ),
                    startPositionMs = posMs,
                )
                playerVm.markStemsAvailable()
                playerVm.play()
            } else {
                // 回退路径（旧 ring buffer 模式）：保持原有行为。
                Log.i("AppShell", "edge gate=${edgeGate} → 流式(ring buffer) @${posMs}ms")
                playerVm.loadEdgeMultiTrack(posMs)
            }
            // ★G-3ux：告知用户“已切到分离结果，可从当前位置继续”——
            //   否则声音在用户无预期的时间点“换了一个”，易误以为出错。
            scope.launch {
                snackbarHostState.showSnackbar(edgeSwitchedMsg)
            }
            return@LaunchedEffect
        }
        // ★G-3fix：取消/失败时若已切到多轨（主轨正播 stem://），
        //   必须回退到原曲——否则 buffer 已关 → 读端 EOF → 播放卡住所声。
        val aborted = st is EdgeSeparationSession.State.Cancelled ||
            st is EdgeSeparationSession.State.Failed
        if (aborted && edgeSwitched) {
            edgeSwitched = false
            Log.i("AppShell", "edge session aborted (${st::class.simpleName}) → 回原曲")
            // ★修正：只用 exitMultiTrack() 会“退了多轨但没播”、“1 Tracks 0 active”。
            //   必须走 toggleSeparation(false) —— 它内部 exitMultiTrack + doLoad(originalUri)
            //   重载原曲并开播（且保留 lastStems，开关再开可恢复）。
            if (!playerVm.toggleSeparation(false)) {
                // 极端情况（无 originalUri）：至少退回播放态，避免卡在 0 active。
                playerVm.exitMultiTrack()
                playerVm.play()
            }
            scope.launch {
                snackbarHostState.showSnackbar(edgeAbortedMsg)
            }
        }
    }

    // ★横屏用右侧栏（2026-10-20）：横屏下底部栏会吃掉 160/720 ≈ 22% 的高度，
    //   而右侧栏只占 ~96/1600 ≈ 6% 的宽度 —— 内容区高度从 560 涨到 720（+29%）。
    //   窄屏（竖屏）继续用底部栏（拇指可达性优先）。
    //
    //   为何右侧而不是左侧：本 App 右手持机场景多（横屏看视频/听歌），
    //   右侧栏更靠近拇指；且左侧留给内容（时间轴/波形）更自然。
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isLandscape) {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            }
        },
        bottomBar = {
            if (!isLandscape) {
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
            }
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize()) {
            // 内容区（横屏时右侧栏占位由外层 Row 的第二个子项承担）
            Box(modifier = Modifier.weight(1f)) {
                when (current) {
            AppDestination.PLAYER -> PlayerScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                vm = playerVm,
                // ★G-3ux：无缓存点开开关 → 跳分离页 **并自动开始分离**。
                onOpenSpike = {
                    autoStartSeparate++
                    current = AppDestination.SEPARATE
                },
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
                // ★G-3：当前播放位置（点击时才读）；供“从当前位置起分离”。
                currentPositionMs = { playerVm.progress.positionMs },
                // ★G-3ux：分离页进度文案区分“已可播/仍在跑”。
                edgePercent = playerState.edgePercent,
                // ★闸门：分离跟不上播放时，分离页也说明“已暂停等待”。
                edgeStarved = playerState.edgeStarved,
                // ★G-3ux：无缓存时跳过来 → 自动开始分离（不用用户再点一次）。
                autoStartToken = autoStartSeparate,
                // ★无可用模型时的出口：跳设置页并直接打开模型库
                onOpenModelLibrary = {
                    openModelLibToken++
                    current = AppDestination.SETTINGS
                },
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
                        // ★#3：延迟 0.8s 再跳页——让用户看到“✅ 分离完成”，
                        //   而不是从 79% 直接消失（观感像“卡住后突然没了”）。
                        scope.launch {
                            delay(JUMP_DELAY_MS)
                            current = AppDestination.PLAYER
                        }
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
                        scope.launch {
                            delay(JUMP_DELAY_MS)
                            current = AppDestination.PLAYER
                        }
                    }
                },
            )

            AppDestination.SETTINGS -> SettingsScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                openModelLibraryToken = openModelLibToken,
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
            // ★横屏：右侧导航栏（图标 + 竖排文字）
            if (isLandscape) {
                NavigationRailBar(
                    current = current,
                    onSelect = { current = it },
                )
            }
        }
    }
}

/**
 * ★横屏的右侧导航栏（2026-10-20）。
 *
 * ## 设计意图：竖屏底部栏“转 90°”
 * 竖屏底部栏是**均分宽度**的（720px ÷ 5 = 144px 每项，中心在 66/213/360/507/654）。
 * 因此横屏就该是**均分高度**，而不是“从顶往下堆、底部留一大块空”。
 * 用 `weight(1f)` 实现均分 → 看起来就像单纯把底栏旋转了一下。
 *
 * ## 为何不直接用 NavigationRail 组件
 * 它默认靠**左**，且用 `NavigationRailItem`（带 icon 槽）——
 * 我们用文本字形不需要那个参数，直接用 Column + clickable 控制力更强。
 *
 * ## 为何图标 + 文字竖排（不是只图标）
 * 项目用**文本字形**做图标（`☰ ▶ ✂ ☷ ⚙`），语义并不直观。
 * 只放图标 = 常驻猜谜游戏；要修就得上 material-icons-extended（~1.5MB），
 * 违背项目既定取舍。⇒ 图标 + 两字标签竖排，宽 88dp 足够。
 */
@Composable
private fun NavigationRailBar(
    current: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    // ★底部额外避让：横屏下 navigationBars 的 inset 实测为 **0**
    //   （frame=[0,720][1600,720]，手势条不占位），于是最后一项会紧贴屏幕下边。
    //   而顶部状态栏有 96px 真实避让 → 视觉上不对称。
    //   按用户要求补“上边的 1/3”（96/3 = 32px）作基准留白。
    //   与 navigationBarsInset 取 max：将来若设备真占位（三键导航），不会重叠。
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val railBottomPad = maxOf(navBarInset, RAIL_BOTTOM_PAD)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxHeight().width(RAIL_WIDTH),
    ) {
        Column(
            // ★C：避开状态栏/刘海。`statusBarsPadding()` 只在横屏竖屏都生效，
            //   但现在只有横屏会走这里（竖屏用底部栏）。
            //   没有它，第一项“歌单”会被推到 y=56（压在状态栏下方边缘）。
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(top = 4.dp, bottom = railBottomPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            // ★B：均分高度（与竖屏底部栏的均分宽度对称）
        ) {
            AppDestination.entries.forEach { dest ->
                val selected = dest == current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)          // ★均分：5 项各占 1/5 高度
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // ★选中态背景：对齐 M3 NavigationBarItem 的“胶囊高亮”，
                            //   让右侧栅更像“把底部栅旋转了 90°”。
                            .background(
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(dest) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            glyphFor(dest),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(dest.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 右侧导航栏宽度（88dp —— 够放两个汉字且不挤压内容）。 */
private val RAIL_WIDTH = 88.dp

/**
 * 右侧导航栏**底部**额外留白。
 *
 * ## 为何需要
 * 横屏下 `navigationBars` 的 inset 实测为 **0**（手势条不占位），
 * 于是最后一项紧贴屏幕下边；而顶部状态栏有 48dp 真实避让 → 视觉不对称。
 *
 * ## 数值取值
 * 顶部视觉留白（状态栏 96px → 文字顶 162px）= 66px。
 * 要求“底部约为顶部 1/3” → 22px。实测 16dp 时底部已到 50px
 * （因为 item 本身在 1/5 区域内居中还有余量），降到 6dp ≈ 22px 正好。
 */
private val RAIL_BOTTOM_PAD = 6.dp

/** 导航图标：用**文本字形**代替 material-icons（项目既定：零新增图标依赖）。 */
private fun glyphFor(dest: AppDestination): String = when (dest) {
    AppDestination.PLAYLIST -> "\u2630"   // ☰
    AppDestination.PLAYER -> "\u25B6"     // ▶
    AppDestination.SEPARATE -> "\u2702"   // ✂
    AppDestination.MIX -> "\u2637"        // ☷
    AppDestination.SETTINGS -> "\u2699"   // ⚙
}

/**
 * ★#3：分离完成后延迟跳页的时长（ms）。
 * 目的：让分离页上的“✅ 分离完成”与进度条收尾能被看到，
 * 而不是从 79% 直接消失（用户会以为“卡住后突然没了”）。
 */
private const val JUMP_DELAY_MS = 800L
