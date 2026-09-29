package pub.miku.neuralstem.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pub.miku.neuralstem.player.PlayerScreen
import pub.miku.neuralstem.player.PlayerViewModel
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

            // 恢复计划 Step 1：占位（后续 Step 恢复实现）
            AppDestination.PLAYLIST -> PlaceholderScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                title = "歌单",
                note = "待恢复（原为占位；歌单页在后续步骤实现）。",
            )

            AppDestination.EQ -> PlaceholderScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                title = "均衡器 (EQ)",
                note = "待恢复（EQ 面板 + 独立页在后续步骤实现）。",
            )
        }
    }
}

/** 导航图标：用**文本字形**代替 material-icons（项目既定：零新增图标依赖）。 */
private fun glyphFor(dest: AppDestination): String = when (dest) {
    AppDestination.PLAYLIST -> "\u2630"   // ☰
    AppDestination.PLAYER -> "\u25B6"     // ▶
    AppDestination.SEPARATE -> "\u2702"   // ✂
    AppDestination.EQ -> "\u2637"         // ☷
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
