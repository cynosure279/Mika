package com.wangzan101.mika.settings

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsExclusionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(ActivityManager::class.java)

    @Test
    fun hideRestoreAndResumeReapplyCurrentTask() = withRestoredMainActivity { scenario, prefs ->
        var taskId = -1
        scenario.onActivity {
            taskId = it.taskId
            assertTrue(it.recentsExclusion.set(false))
        }
        awaitRecents(taskId, true)
        val otherTasks = recentTaskIds() - taskId

        scenario.onActivity { assertTrue(it.recentsExclusion.set(true)) }
        awaitRecents(taskId, false)
        assertTrue(FeatureFlags(prefs).excludeFromRecents())
        assertEquals(otherTasks, recentTaskIds() - taskId)

        scenario.onActivity { assertTrue(it.recentsExclusion.set(false)) }
        awaitRecents(taskId, true)
        assertEquals(otherTasks, recentTaskIds() - taskId)

        scenario.onActivity { assertTrue(it.recentsExclusion.set(true)) }
        awaitRecents(taskId, false)
        scenario.recreate()
        scenario.onActivity { taskId = it.taskId }
        awaitRecents(taskId, false)
        assertTrue(FeatureFlags(prefs).excludeFromRecents())

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitRecents(taskId, false)
        assertEquals(otherTasks, recentTaskIds() - taskId)

        scenario.onActivity { assertTrue(it.recentsExclusion.set(false)) }
        awaitRecents(taskId, true)
        assertFalse(FeatureFlags(prefs).excludeFromRecents())
        assertEquals(otherTasks, recentTaskIds() - taskId)
    }

    @Test
    fun preferenceFailureRollsBackRealCurrentTask() = withRestoredMainActivity { scenario, prefs ->
        var taskId = -1
        scenario.onActivity {
            taskId = it.taskId
            assertTrue(it.recentsExclusion.set(false))
        }
        awaitRecents(taskId, true)
        val otherTasks = recentTaskIds() - taskId
        val failingPrefs = FailNextBooleanCommit(prefs)
        val flags = FeatureFlags(failingPrefs)

        scenario.onActivity {
            val recents = RecentsExclusion(flags, ActivityRecentsSystem(it))
            assertFalse(recents.set(true))
        }

        awaitRecents(taskId, true)
        assertFalse(FeatureFlags(prefs).excludeFromRecents())
        assertFalse(flags.excludeFromRecents())
        assertEquals(otherTasks, recentTaskIds() - taskId)
    }

    @Test
    fun preLaunchExcludedStateIsCapturedBeforeReapply() {
        val prefs = context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
        val hadPreference = prefs.contains(RECENTS_KEY)
        val oldPreference = FeatureFlags(prefs).excludeFromRecents()
        val originalTaskId = mainActivityTaskId()
        val originalSystem = originalTaskId?.let { !isInRecents(it) } ?: false
        try {
            val setup = ActivityScenario.launch(MainActivity::class.java)
            try {
                var taskId = -1
                setup.onActivity {
                    taskId = it.taskId
                    assertTrue(FeatureFlags(prefs).setExcludeFromRecents(false))
                    assertTrue(ActivityRecentsSystem(it).apply(true))
                }
                awaitRecents(taskId, false)
                withRestoredMainActivity(expectedOriginalSystem = true) { _, _ -> }
            } finally {
                setup.close()
            }
        } finally {
            try {
                assertTrue(prefs.edit().putBoolean(RECENTS_KEY, originalSystem).commit())
                val cleanup = ActivityScenario.launch(MainActivity::class.java)
                try {
                    cleanup.onActivity {
                        assertTrue(ActivityRecentsSystem(it).apply(originalSystem))
                    }
                } finally {
                    cleanup.close()
                }
            } finally {
                val restore = prefs.edit()
                if (hadPreference) restore.putBoolean(RECENTS_KEY, oldPreference) else restore.remove(RECENTS_KEY)
                assertTrue(restore.commit())
            }
        }
    }

    @Test
    fun assertionFailureStillRestoresPreferenceAndSystem() {
        val prefs = context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
        val hadPreference = prefs.contains(RECENTS_KEY)
        val oldPreference = FeatureFlags(prefs).excludeFromRecents()

        val failure = assertThrows(AssertionError::class.java) {
            withRestoredMainActivity { scenario, _ ->
                var taskId = -1
                scenario.onActivity {
                    taskId = it.taskId
                    assertTrue(it.recentsExclusion.set(!oldPreference))
                }
                awaitRecents(taskId, oldPreference)
                throw AssertionError("intentional cleanup check")
            }
        }

        assertEquals("intentional cleanup check", failure.message)
        assertEquals(hadPreference, prefs.contains(RECENTS_KEY))
        assertEquals(oldPreference, FeatureFlags(prefs).excludeFromRecents())
    }

    private fun withRestoredMainActivity(
        expectedOriginalSystem: Boolean? = null,
        block: (ActivityScenario<MainActivity>, SharedPreferences) -> Unit,
    ) {
        val prefs = context.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
        val hadPreference = prefs.contains(RECENTS_KEY)
        val oldPreference = FeatureFlags(prefs).excludeFromRecents()
        val originalTaskId = mainActivityTaskId()
        val oldSystem = originalTaskId?.let { !isInRecents(it) } ?: false
        expectedOriginalSystem?.let { assertEquals("pre-launch system state", it, oldSystem) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            try {
                block(scenario, prefs)
            } finally {
                try {
                    var taskId = -1
                    scenario.onActivity {
                        taskId = it.taskId
                        assertTrue(ActivityRecentsSystem(it).apply(oldSystem))
                    }
                    awaitRecents(taskId, !oldSystem)
                } finally {
                    val restore = prefs.edit()
                    if (hadPreference) restore.putBoolean(RECENTS_KEY, oldPreference) else restore.remove(RECENTS_KEY)
                    assertTrue(restore.commit())
                    assertEquals(hadPreference, prefs.contains(RECENTS_KEY))
                    assertEquals(oldPreference, FeatureFlags(prefs).excludeFromRecents())
                }
            }
        } finally {
            scenario.close()
        }
    }

    private fun mainActivityTaskId(): Int? = manager.appTasks.firstOrNull { task ->
        val info = task.taskInfo
        info?.baseActivity?.className == MainActivity::class.java.name ||
            info?.topActivity?.className == MainActivity::class.java.name
    }?.taskInfo?.taskId

    private fun awaitRecents(taskId: Int, expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual = isInRecents(taskId)
        while (actual != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            actual = isInRecents(taskId)
        }
        assertEquals("current task recents visibility", expected, actual)
    }

    private fun isInRecents(taskId: Int): Boolean = taskId in recentTaskIds()

    @Suppress("DEPRECATION")
    private fun recentTaskIds(): Set<Int> = manager.getRecentTasks(50, 0).mapTo(mutableSetOf()) { it.taskId }

    private class FailNextBooleanCommit(
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

    private companion object {
        const val RECENTS_KEY = "exclude_from_recents"
    }
}
