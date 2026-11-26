package com.wangzan101.mika.settings

import android.app.Activity
import android.app.ActivityManager

/** Applies the user's recents choice to this activity and persists it as one transaction. */
class RecentsExclusion(
    private val flags: FeatureFlags,
    private val system: System,
) {
    fun interface System {
        fun apply(exclude: Boolean): Boolean
    }

    fun set(on: Boolean): Boolean {
        val old = flags.excludeFromRecents()
        if (!system.apply(on)) return false
        if (flags.setExcludeFromRecents(on)) return true
        system.apply(old)
        // commit() updates memory even when persistent storage fails; restore that value too.
        flags.setExcludeFromRecents(old)
        return false
    }

    /** Reapplies stored state after recreation/resume without writing it again. */
    fun reapply(): Boolean = system.apply(flags.excludeFromRecents())
}

/** Uses only public task APIs and never substitutes another task when this activity's task is absent. */
class ActivityRecentsSystem(private val activity: Activity) : RecentsExclusion.System {
    override fun apply(exclude: Boolean): Boolean = try {
        val manager = activity.getSystemService(ActivityManager::class.java)
        val current = manager.appTasks.firstOrNull { it.taskInfo?.taskId == activity.taskId } ?: return false
        current.setExcludeFromRecents(exclude)
        true
    } catch (_: RuntimeException) {
        false
    }
}
