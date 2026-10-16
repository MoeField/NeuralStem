package pub.miku.neuralstem.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
 * V5 / M2-C / 恢复计划 Step 1 — 仪器化测试（Compose 语义查找，非坐标）。
 *
 * ## 修订历史
 * - Step 1：主页由 [pub.miku.neuralstem.ui.AppShell] 托管（**底部导航栏 5 项**）。
 * - **2026-10-17 修订**：原断言已过时（对应当年 UI），本次对齐**当前实际 UI**：
 *   1. ⑪ 删了播放页顶部「播放/暂停」与「Spike 分离」按钮；
 *   2. 播放页控件（导入/播放）**仅在 `state.hasMedia` 时渲染** → 空态只有 `尚未导入音频`；
 *      导入入口在**「歌单」页**（R2 设计：导入归属选中歌单）；
 *   3. 底栏第 4 项是 `混音`（EQ 与分轨混音合并），不是 `EQ`；
 *   4. 分离页 D-3 重构后**没有「开始分离」按钮**（改为按档位按钮），用 `当前曲目` 作定位锚。
 *
 * > 断言原则：只断言**默认启动态**下稳定可见的文案（避免依赖 benchmark 缓存 / 曲库状态）。
 */
@RunWith(AndroidJUnit4::class)
class PlayerUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 默认进入播放器页并显示空态提示() {
        // 底栏「播放器」项在（启动页 = 播放器）。
        composeRule.onAllNodesWithText("播放器").onFirst().assertIsDisplayed()
        // 空态（未导入）提示。
        composeRule.onNodeWithText("尚未导入音频").assertIsDisplayed()
    }

    @Test
    fun 空态时播放页不显示导入与播放按钮() {
        // ★当前设计：播放页控件包在 `if (state.hasMedia)` 内 → 空态不渲染。
        //   导入入口在「歌单」页（见用例 底部导航栏含五个导航项 / 歌单页可达）。
        composeRule.onAllNodesWithText("导入音频").assertCountEquals(0)
        composeRule.onAllNodesWithText("播放").assertCountEquals(0)
    }

    @Test
    fun 底部导航栏含五个导航项() {
        for (item in listOf("歌单", "播放器", "分离", "混音", "设置")) {
            composeRule.onAllNodesWithText(item).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun 点击歌单导航项切换到歌单页() {
        composeRule.onAllNodesWithText("歌单").onFirst().performClick()
        composeRule.waitForIdle()
        // 歌单页固定含「选择文件夹导入」按钮（pl_import_folder，无条件渲染）。
        composeRule.onNodeWithText("选择文件夹导入").assertIsDisplayed()
    }

    @Test
    fun 点击分离导航项切换到分离页() {
        // 点底栏「分离」→ 分离页（固定渲染「当前曲目」小节，与 benchmark/曲库状态无关）。
        composeRule.onAllNodesWithText("分离").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前曲目").assertIsDisplayed()
    }

    @Test
    fun 点击混音导航项显示混音页() {
        composeRule.onAllNodesWithText("混音").onFirst().performClick()
        composeRule.waitForIdle()
        // 混音页含 EQ 区
        composeRule.onNodeWithText("均衡器 (EQ)").assertIsDisplayed()
    }
}
