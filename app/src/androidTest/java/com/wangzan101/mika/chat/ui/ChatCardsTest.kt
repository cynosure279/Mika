package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatCardsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longCardButtonsShareHeightAndRemainClickable() {
        val cancel = "Hủy"
        val approve = "Cho phép tiếp tục tác vụ quan trọng này"
        val calls = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                MikaTheme(dark = false, reduceMotion = true) {
                    Box(Modifier.width(240.dp).testTag("narrow-card")) {
                        NeedCard("Xác nhận", "Nội dung", buttons = {
                            CardButton(cancel, onClick = { calls += "cancel" })
                            CardButton(approve, primary = true, onClick = { calls += "approve" })
                        })
                    }
                }
            }
        }

        val cancelButton = compose.onNodeWithText(cancel).fetchSemanticsNode()
        val approveButton = compose.onNodeWithText(approve).fetchSemanticsNode()
        val cancelBounds = cancelButton.boundsInRoot
        val approveBounds = approveButton.boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(approve, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val approveLayout = layouts.single()
        assertEquals(2, approveLayout.lineCount)
        assertTrue("long action wraps above 42dp: ${approveBounds.height}", approveBounds.height > with(compose.density) { 42.dp.toPx() })
        assertEquals("actions share measured height", approveBounds.height, cancelBounds.height, 1f)
        val card = compose.onNodeWithTag("narrow-card").fetchSemanticsNode().boundsInRoot
        assertTrue(cancelBounds.left >= card.left && approveBounds.right <= card.right)

        compose.onNodeWithText(cancel).performTouchInput { click(center) }
        compose.onNodeWithText(approve).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("cancel", "approve"), calls) }
    }
}
