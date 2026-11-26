package com.wangzan101.mika.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PageScrolls
import com.wangzan101.mika.ui.components.PlainListTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在一处切页显示的页面从子页回来停在原来的位置:和设置页一个结构,切页的一方持有 [PageScrolls],各页从里面取自己的。
 * 列表页的行都带说明:简约列表里说明收到卡片下面当脚注,晚一帧才出来,页面首帧偏矮,停在底部附近时带着的位置会被截短。
 */
@RunWith(AndroidJUnit4::class)
class PageScrollsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private enum class Page { List, Child }

    private var page by mutableStateOf(Page.List)

    @Before fun startOnTheList() {
        page = Page.List
    }

    private fun show(scrolls: @Composable () -> PageScrolls) {
        compose.setContent { Screens(scrolls()) }
    }

    @Composable private fun Screens(scrolls: PageScrolls) {
        PlainListTheme {
            when (page) {
                Page.List -> PageScaffold("列表", onBack = {}, scrollState = scrolls.of(Page.List)) {
                    GroupCard {
                        repeat(ROWS) { i ->
                            key(i) { ListRow(null, "第 $i 行", note = "第 $i 行的说明", onClick = { page = Page.Child }) }
                        }
                    }
                    Text("列表结尾", Modifier.testTag("end"))
                }
                Page.Child -> PageScaffold("子页", onBack = { page = Page.List }) {
                    TextButton(onClick = { page = Page.List }) { Text("返回列表") }
                }
            }
        }
    }

    /** 这一行的屏幕纵坐标。滚出屏幕的行被裁剪后坐标是 0,所以先确认它真在屏幕里,免得两边都是 0 空过。 */
    private fun topOf(row: String): Float =
        compose.awaitOne(row).fetchSemanticsNode().boundsInRoot.top.also { assertTrue("$row 要在屏幕里", it > 0f) }

    private fun roundTrip(row: String) {
        val before = topOf(row)
        compose.awaitOne(row).performClick()
        compose.awaitOne("返回列表").assertIsDisplayed()
        compose.awaitOne("返回列表").performClick()
        compose.waitForIdle()
        assertEquals(before, topOf(row), 1f)
    }

    // 停在最底:返回的首帧卡片脚注还没出来,带着的位置被偏小的上限截短,要等上限长回来再放回去
    @Test fun aPageScrolledToTheBottomComesBackWhereItWas() {
        show { PageScrolls() }
        compose.onNodeWithTag("end").performScrollTo()
        compose.waitForIdle()
        roundTrip("第 ${ROWS - 1} 行")
    }

    @Test fun aPageScrolledToTheMiddleComesBackWhereItWas() {
        show { PageScrolls() }
        compose.awaitOne("第 ${ROWS / 2} 行").performScrollTo()
        compose.waitForIdle()
        roundTrip("第 ${ROWS / 2} 行")
    }

    // 换界面语言、进程被回收后页面重建:位置经 PageScrolls.Saver 存下来,重建后接上。停在底部,重建的首帧同样被截短
    @Test fun positionsComeBackAfterThePageIsRebuilt() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screens(rememberSaveable(saver = PageScrolls.Saver) { PageScrolls() }) }
        compose.onNodeWithTag("end").performScrollTo()
        compose.waitForIdle()
        val row = "第 ${ROWS - 1} 行"
        val before = topOf(row)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(before, topOf(row), 1f)
    }

    private companion object {
        /** 够长,手机竖屏上要滚好几屏。 */
        const val ROWS = 30
    }
}
