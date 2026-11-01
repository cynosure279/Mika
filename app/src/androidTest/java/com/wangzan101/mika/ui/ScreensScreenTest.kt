package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「正在运行的虚拟屏」那一页:空态、一块屏一行、点开小画面(只用假数据,不建真屏)。 */
@RunWith(AndroidJUnit4::class)
class ScreensScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(rows: List<ScreenRow>) = compose.setContent {
        MikaTheme {
            ScreensContent(rows, onBack = { calls += "back" }, onOpenConversation = { calls += "open $it" }, onRefresh = { calls += "refresh" })
        }
    }

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "conv-$key", "打开设置看一下电池", displayId, TaskState.Running(1, "点了一下"), foreground = "设置")

    @Test fun emptyPageSaysNoTaskIsRunning() {
        show(emptyList())
        compose.onNodeWithText(ScreensCopy.EMPTY).assertIsDisplayed()
    }

    /** 一块屏一行:屏号、目标、状态都在;点那一行回到它所属的对话,右上角能重问前景 App。 */
    @Test fun oneRowPerScreenOpensItsConversation() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithText(ScreensCopy.screen(12)).assertIsDisplayed()
        compose.onNodeWithText(ScreensCopy.screen(-1)).assertIsDisplayed()
        compose.onNodeWithText("打开设置看一下电池").assertExists()
        compose.onNodeWithText("正在执行").assertExists()
        compose.onNodeWithText("设置").assertExists()
        compose.onNodeWithTag("screens-refresh").performClick()
        assertEquals(listOf("refresh"), calls)
        compose.onNodeWithText(ScreensCopy.screen(12)).performClick()
        assertEquals(listOf("refresh", "open conv-k12"), calls)
    }

    /** 还没建屏的那块没有「看画面」:没有画面可看,不给一个点了没反应的入口。 */
    @Test fun aScreenThatIsNotBuiltYetHasNoPreview() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithTag("screens-preview-queued").assertDoesNotExist()
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().assertIsDisplayed()
    }

    /** 点一下展开、再点收起:按钮上的说法跟着换。 */
    @Test fun previewToggles() {
        show(listOf(row(12)))
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().performClick()
        compose.onNodeWithText(ScreensCopy.HIDE_PREVIEW).assertExists()
        compose.onNodeWithTag("screens-preview-k12").performClick()
        compose.onNodeWithText(ScreensCopy.PREVIEW).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
