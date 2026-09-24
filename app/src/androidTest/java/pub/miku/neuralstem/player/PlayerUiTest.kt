package pub.miku.neuralstem.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pub.miku.neuralstem.MainActivity

/**
 * V5 / M2-C — 仪器化测试(Compose 语义查找，非坐标)。
 *
 * M2-C 后主页由 [pub.miku.neuralstem.ui.AppShell] 托管:
 * - 顶栏标题 = 当前目的地("播放器"/"分离"/…)
 * - 左上角 ☰ 打开 Drawer(无需 material-icons)
 * 断言相应调整。
 */
@RunWith(AndroidJUnit4::class)
class PlayerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 默认进入播放器页并显示三大入口按钮() {
        // 注:“播放器”同时出现在顶栏标题与 Drawer 项里（Drawer 始终被组合）,
        // 故用 onAllNodesWithText(...).onFirst() 断言“至少存在一个且可见”。
        composeRule.onAllNodesWithText("播放器").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("导入音频").assertIsDisplayed()
        composeRule.onNodeWithText("播放").assertIsDisplayed()
        composeRule.onNodeWithText("Spike 分离").assertIsDisplayed()
    }

    @Test
    fun 未导入时提示文案且播放按钮不可用() {
        composeRule.onNodeWithText("尚未导入音频").assertIsDisplayed()
        // F0.x:未准备好时播放按钮 disabled（enabled = state.isReady）
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
    }

    @Test
    fun 导入按钮可用() {
        composeRule.onNodeWithText("导入音频").assertIsEnabled()
    }

    @Test
    fun 汉堡按钮打开Drawer且含各导航项() {
        // ☰ 是文本按钮(零依赖替代 material-icons)
        composeRule.onNodeWithText("\u2630").performClick()
        composeRule.waitForIdle()
        for (item in listOf("播放器", "分离", "设置", "歌单", "关于")) {
            composeRule.onAllNodesWithText(item).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun 点击Spike分离切换到分离页() {
        // 语义点击(非坐标)。在 AppShell 内现为「切换 Drawer 页」，不再是启动 Activity。
        composeRule.onNodeWithText("Spike 分离").performClick()
        composeRule.waitForIdle()
        // 分离页有「开始分离」按钮（播放器页没有）→ 用以判定已切换
        composeRule.onNodeWithText("开始分离").assertIsDisplayed()
    }
}
