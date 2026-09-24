package pub.miku.neuralstem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pub.miku.neuralstem.BuildConfig
import pub.miku.neuralstem.player.PlayerScreen
import pub.miku.neuralstem.player.PlayerViewModel
import pub.miku.neuralstem.settings.SettingsScreen
import pub.miku.neuralstem.spike.SeparationScreen
import pub.miku.neuralstem.spike.Spleeter4Dsp

/**
 * 应用导航壳(阶段 M2 任务 C)。
 *
 * ## 为什么
 * 用户要求一个**可唤起的栏目**(侧拉/汉堡按钮)，长期容纳:
 * 播放器 / 分离 / 设置 / 歌单 / 关于 / 版本号。
 * 之前是「MainActivity(播放器) + SpikeActivity(分离页)」两个平级 Activity，
 * 靠 `registerForActivityResult` 传递分离结果 —— 引入了
 * 坑 32(「`am start` 直启的 Activity 无父级，setResult 丢失」)那一类问题。
 *
 * ## 做法
 * 把「分离页」改成可组合的 [SeparationScreen]，与播放器**同属一个 Activity**，
 * 用 [ModalNavigationDrawer] 切换。分离结果**直接回调**给播放器 VM，
 * 彻底移除跨 Activity 的 setResult 链路。
 *
 * 不引入 Navigation 库（页面不多，用枚举状态切换即可）；
 * 也不引入 material-icons（用 ☰ 文本，保持零新增依赖）。
 */
enum class AppDestination(val label: String) {
    PLAYER("播放器"),
    SEPARATE("分离"),
    SETTINGS("设置"),
    PLAYLIST("歌单"),
    ABOUT("关于"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    playerVm: PlayerViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initial: AppDestination = AppDestination.PLAYER,
) {
    var current by remember { mutableStateOf(initial) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "NeuralStem",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                HorizontalDivider()
                AppDestination.entries.forEach { dest ->
                    NavigationDrawerItem(
                        label = { Text(dest.label) },
                        selected = dest == current,
                        onClick = {
                            current = dest
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(current.label) },
                    navigationIcon = {
                        // 用文本 ☰ 代替 material-icons（零新增依赖）
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("\u2630", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                )
            },
        ) { padding ->
            when (current) {
                AppDestination.PLAYER -> PlayerScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    vm = playerVm,
                    onOpenSpike = { current = AppDestination.SEPARATE },
                )

                AppDestination.SEPARATE -> SeparationScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onPlayStems = { done ->
                        // 直接回调播放器 VM —— 不再走 setResult / 跨 Activity
                        playerVm.loadMultiTrack(
                            listOf(
                                Triple("vocals", "人声", done.vocalsFile),
                                Triple("accompaniment", "伴奏", done.accompanimentFile),
                            ),
                        )
                        playerVm.togglePlayPause()
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
                        playerVm.togglePlayPause()
                        current = AppDestination.PLAYER
                    },
                )

                AppDestination.SETTINGS -> SettingsScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    playerVm = playerVm,
                )

                AppDestination.PLAYLIST -> PlaceholderScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    title = "歌单",
                    note = "占位（不在 guide 需求内，先留架构位）。",
                )

                AppDestination.ABOUT -> AboutScreen(
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
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

@Composable
private fun AboutScreen(modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("关于 NeuralStem", style = MaterialTheme.typography.headlineSmall)
        Text("本地 AI 音轨分离播放器")
        Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("构建类型 ${BuildConfig.BUILD_TYPE}")
        Text("应用 ID ${BuildConfig.APPLICATION_ID}")
    }
}
