package com.wangzan101.mika.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsExclusionTest {
    @Test
    fun defaultIsIncludedAndChoiceSurvivesReload() {
        val prefs = TestPrefs()
        val system = FakeSystem(true, true)
        val flags = FeatureFlags(prefs)
        val recents = RecentsExclusion(flags, system)

        assertFalse(flags.excludeFromRecents())
        assertTrue(recents.set(true))
        assertTrue(FeatureFlags(prefs).excludeFromRecents())
        assertTrue(recents.set(false))
        assertFalse(FeatureFlags(prefs).excludeFromRecents())
        assertEquals(listOf(true, false), system.calls)
        assertEquals(2, prefs.commits)
    }

    @Test
    fun systemFailureDoesNotPersistOrReportSuccess() {
        val prefs = TestPrefs()
        val system = FakeSystem(false)
        val recents = RecentsExclusion(FeatureFlags(prefs), system)

        assertFalse(recents.set(true))

        assertFalse(FeatureFlags(prefs).excludeFromRecents())
        assertEquals(listOf(true), system.calls)
        assertEquals(0, prefs.commits)
    }

    @Test
    fun preferenceFailureRollsBackSystemChange() {
        val prefs = TestPrefs()
        val flags = FeatureFlags(prefs)
        assertTrue(flags.setExcludeFromRecents(true))
        prefs.commits = 0
        prefs.failCommits = true
        val system = FakeSystem(true, true)
        val recents = RecentsExclusion(flags, system)

        assertFalse(recents.set(false))

        assertTrue(flags.excludeFromRecents())
        assertEquals(listOf(false, true), system.calls)
        assertEquals(2, prefs.commits)
    }

    @Test
    fun reapplyUsesStoredValueWithoutWriting() {
        val prefs = TestPrefs()
        assertTrue(FeatureFlags(prefs).setExcludeFromRecents(true))
        prefs.commits = 0
        val system = FakeSystem(true)
        val recents = RecentsExclusion(FeatureFlags(prefs), system)

        assertTrue(recents.reapply())

        assertEquals(listOf(true), system.calls)
        assertEquals(0, prefs.commits)
        assertTrue(FeatureFlags(prefs).excludeFromRecents())
    }

    private class FakeSystem(vararg results: Boolean) : RecentsExclusion.System {
        private val results = results.toList()
        val calls = mutableListOf<Boolean>()

        override fun apply(exclude: Boolean): Boolean {
            calls += exclude
            return results.getOrElse(calls.lastIndex) { true }
        }
    }

    private class TestPrefs(
        private val delegate: FeatureFlagsCapsuleTest.MemoryPrefs = FeatureFlagsCapsuleTest.MemoryPrefs(),
    ) : SharedPreferences by delegate {
        var failCommits = false
        var commits = 0

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    commits++
                    val committed = editor.commit()
                    return !failCommits && committed
                }

                override fun apply() {
                    commit()
                }
            }
        }
    }
}
