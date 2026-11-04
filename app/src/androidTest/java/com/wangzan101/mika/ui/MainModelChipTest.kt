package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 输入框上方的主模型 chip:三种说法、回退和「下一件事生效」;点开的面板能选跟随设置或具体项。 */
@RunWith(AndroidJUnit4::class)
class MainModelChipTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private val model = ModelChoice.Model("cfg-1")
    private val group = ModelChoice.Group("group-1")

    private fun chip(ui: MainModelUi, busy: Boolean = false, onClick: () -> Unit = {}) {
        compose.setContent {
            MikaTheme {
                Column {
                    MainModelChip(ui, busy = busy, onClick = onClick)
                }
            }
        }
    }

    @Test fun showsTheChosenModelAndForwardsClicks() {
        var clicked = 0
        chip(MainModelUi(available = true, override = model, label = "DeepSeek V3"), onClick = { clicked++ })
        compose.onNodeWithText("模型：DeepSeek V3").assertIsDisplayed()
        compose.onNodeWithTag("main-model-chip").performClick()
        compose.runOnIdle { assertEquals(1, clicked) }
    }

    @Test fun showsGroupAndFollowingSettings() {
        chip(MainModelUi(available = true, override = group, label = "主力兜底", group = true))
        compose.onNodeWithText("组：主力兜底").assertIsDisplayed()
    }

    @Test fun followingShowsTheSettingsMainModel() {
        chip(MainModelUi(available = true, label = "DeepSeek V3"))
        compose.onNodeWithText("跟随 · DeepSeek V3").assertIsDisplayed()
    }

    @Test fun unavailableChoiceShowsTheFallbackAndNextTaskNote() {
        chip(MainModelUi(available = true, override = model, label = "DeepSeek V3", fallback = true), busy = true)
        compose.onNodeWithText("已回退：DeepSeek V3").assertIsDisplayed()
        compose.onNodeWithText("下一件事生效").assertIsDisplayed()
    }

    @Test fun sheetPicksFollowSettingsAndCloses() {
        var picked: ModelChoice? = model
        var called = false
        var closed = false
        compose.setContent {
            MikaTheme {
                MainModelSheet(
                    MainModelUi(available = true, override = model, label = "DeepSeek V3", globalLabel = "DeepSeek V3"),
                    onPick = { picked = it; called = true }, onMore = {}, onDismiss = { closed = true },
                )
            }
        }
        compose.onNodeWithText("跟随设置").performClick()
        compose.runOnIdle {
            assertTrue(called)
            assertEquals(null, picked)
            assertTrue(closed)
        }
    }

    @Test fun sheetPicksAGroup() {
        var picked: ModelChoice? = null
        compose.setContent {
            MikaTheme {
                MainModelSheet(
                    MainModelUi(
                        available = true,
                        options = listOf(MainModelOption(group, "主力兜底", group = true, memberCount = 2)),
                    ),
                    onPick = { picked = it }, onMore = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("主力兜底").performClick()
        compose.runOnIdle { assertEquals(group, picked) }
    }
}
