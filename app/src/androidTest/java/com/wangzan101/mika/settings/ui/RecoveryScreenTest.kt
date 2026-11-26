package com.wangzan101.mika.settings.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.RecentsExclusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failedToggleKeepsOldSwitchAndShowsError() {
        val attempts = mutableListOf<Boolean>()
        compose.setContent {
            RecentsExclusionSection(excluded = false, onSet = { attempts += it; false })
        }

        compose.onNodeWithTag("recents-exclusion").assertIsOff()
        compose.onNodeWithTag("recents-exclusion").performClick()
        compose.waitForIdle()

        assertEquals(listOf(true), attempts)
        compose.onNodeWithTag("recents-exclusion").assertIsOff()
        compose.onNodeWithText(compose.activity.getString(R.string.rec_exclude_recents_failed)).assertIsDisplayed()
    }

    @Test
    fun failedCommitKeepsOldPreferenceAndSwitchAfterRecomposition() {
        val prefs = compose.activity.getSharedPreferences("recents_exclusion_ui_failure_test", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        val flags = FeatureFlags(CommitThenFail(prefs))
        val systemCalls = mutableListOf<Boolean>()
        val recents = RecentsExclusion(flags) { systemCalls += it; true }
        try {
            compose.setContent {
                var excluded by remember { mutableStateOf(flags.excludeFromRecents()) }
                RecentsExclusionSection(
                    excluded = excluded,
                    onSet = { want ->
                        val result = recents.set(want)
                        excluded = flags.excludeFromRecents()
                        result
                    },
                )
            }

            compose.onNodeWithTag("recents-exclusion").performClick()
            compose.waitForIdle()

            assertFalse(flags.excludeFromRecents())
            assertEquals(listOf(true, false), systemCalls)
            compose.onNodeWithTag("recents-exclusion").assertIsOff()
            compose.onNodeWithText(compose.activity.getString(R.string.rec_exclude_recents_failed)).assertIsDisplayed()
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun successfulToggleUpdatesSwitchWithoutError() {
        compose.setContent {
            RecentsExclusionSection(excluded = false, onSet = { true })
        }

        compose.onNodeWithTag("recents-exclusion").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("recents-exclusion").assertIsOn()
        compose.onNodeWithText(compose.activity.getString(R.string.rec_exclude_recents_failed)).assertDoesNotExist()
    }

    /** Matches SharedPreferences.commit(): memory changes even when persistence reports failure. */
    private class CommitThenFail(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    editor.commit()
                    return false
                }
            }
        }
    }
}
