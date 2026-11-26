package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun defaultMediumButtonKeepsOriginalHeightPaddingAndSingleLine() {
        val text = "This default medium button label is deliberately much longer than its bounds"
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                MikaButton(
                    text, onClick = {}, modifier = Modifier.width(160.dp).testTag("default-medium"),
                    size = ButtonSize.Medium,
                )
            }
        }

        val button = compose.onNodeWithTag("default-medium").assertHeightIsEqualTo(42.dp)
            .fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertEquals(14.dp.toPx(), label.left - button.left, 1f)
            assertEquals(14.dp.toPx(), button.right - label.right, 1f)
        }
        assertEquals(1, textLayout(text).lineCount)
        assertTrue(textLayout(text).hasVisualOverflow)
    }

    @Test fun mediumButtonWrapsOnlyWhenExplicitlyEnabled() {
        val labels = listOf(
            "Tiếp tục thực hiện tác vụ quan trọng này ngay bây giờ",
            "Continue this important task right now please",
        )
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                Column {
                    labels.forEachIndexed { index, text ->
                        MikaButton(
                            text, onClick = {}, modifier = Modifier.width(112.dp).testTag("default-$index"),
                            size = ButtonSize.Medium,
                        )
                        MikaButton(
                            text + " wrapped", onClick = {}, modifier = Modifier.width(112.dp).testTag("wrapped-$index"),
                            size = ButtonSize.Medium, maxLines = 2,
                        )
                    }
                }
            }
        }

        labels.forEachIndexed { index, text ->
            val defaultText = text
            val wrappedText = "$text wrapped"
            compose.onNodeWithTag("default-$index").assertHeightIsEqualTo(42.dp)
            assertEquals(1, textLayout(defaultText).lineCount)

            val wrapped = compose.onNodeWithTag("wrapped-$index").fetchSemanticsNode().boundsInRoot
            val wrappedLayout = textLayout(wrappedText)
            assertTrue("multiline button exceeds 42dp: ${wrapped.height}", wrapped.height > with(compose.density) { 42.dp.toPx() })
            assertEquals(2, wrappedLayout.lineCount)
            assertTrue("fixture needs more than two lines", wrappedLayout.hasVisualOverflow)
            assertTrue(wrappedLayout.lineCount <= 2)
        }
    }
}
