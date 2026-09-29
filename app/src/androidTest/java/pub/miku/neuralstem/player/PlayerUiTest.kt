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
 * V5 / M2-C / **恢复计划 Step 1** — 仪器化测试(Compose 语义查找，非坐标)。
 *
 * Step 1 后主页由 [pub.miku.neuralstem.ui.AppShell] 托管（**底部导航栏 5 项**）：
 * - 导航项：`歌单 · 播放器 · 分离 · EQ · 设置`
 * - 启动页 = 播放器
 * 断言相应调整。
 */
@RunWith(AndroidJUnit4::class)
class PlayerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 默认进入播放器页并显示入口() {
        // 注：导航项文字（如“播放器”）可能多处出现，用 onAllNodesWithText(...).onFirst() 断言“至少存在一个”。
        composeRule.onAllNodesWithText("播放器").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("导入音频").assertIsDisplayed()
        composeRule.onNodeWithText("播放").assertIsDisplayed()
        composeRule.onNodeWithText("Spike 分离").assertIsDisplayed()
    }

    @Test
    fun 未导入时提示文案且播放按钮不可用() {
        composeRule.onNodeWithText("尚未导入音频").assertIsDisplayed()
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
    }

    @Test
    fun 导入按钮可用() {
        composeRule.onNodeWithText("导入音频").assertIsEnabled()
    }

    @Test
    fun 底部导航栏含五个导航项() {
        for (item in listOf("歌单", "播放器", "分离", "EQ", "设置")) {
            composeRule.onAllNodesWithText(item).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun 点击分离导航项切换到分离页() {
        // 点底栏「分离」→ 分离页（有「开始分离」按钮，播放器页没有）
        composeRule.onAllNodesWithText("分离").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("开始分离").assertIsDisplayed()
    }

    @Test
    fun 点击EQ导航项显示占位页() {
        composeRule.onAllNodesWithText("EQ").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("均衡器 (EQ)").assertIsDisplayed()
    }
}
