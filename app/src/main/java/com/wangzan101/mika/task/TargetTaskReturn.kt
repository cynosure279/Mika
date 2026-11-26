package com.wangzan101.mika.task

import android.view.Display
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.TargetApp
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow

/** 返回 false 时任务仍在但挪不动，可以重试；恢复不了时把原因交给 Agent。 */
internal suspend fun restoreTargetApp(
    target: MutableStateFlow<TargetApp?>,
    shell: IMikaShell,
    agentDisplay: Int,
    fromMainOnly: Boolean = false,
    moveBack: suspend (Int) -> Boolean,
    relaunch: suspend (TargetApp) -> ActionResult,
): Boolean {
    val app = target.value ?: return true
    if (app.taskId >= 0) {
        val display = shell.displayOfTask(app.taskId)
        if (display >= 0) {
            if (display == agentDisplay || (fromMainOnly && display != Display.DEFAULT_DISPLAY)) return true
            if (moveBack(app.taskId)) return true
            // App 可能恰好在查询与挪动之间重启。只在旧任务确实消失时按包名找。
            val after = shell.displayOfTask(app.taskId)
            if (after >= 0) return after == agentDisplay
        }
    }

    val replacement = shell.findTaskForPackage(app.packageName, app.userId, agentDisplay)
        ?: throw TargetAppRestoreFailed(app.packageName, "Couldn't look up the current task for ${app.packageName}.")
    if (replacement.isNotEmpty()) {
        val (id, display) = replacement
        if (display != agentDisplay) {
            if (display != Display.DEFAULT_DISPLAY) throw TargetAppRestoreFailed(
                app.packageName, "${app.packageName} is open on another display. Wait for it to become available or choose another way to continue.",
            )
            if (!moveBack(id)) return false
        }
        target.compareAndSet(app, app.copy(taskId = id))
        return true
    }

    val result = relaunch(app)
    val opened = target.value
    if (result is ActionResult.Ok && opened != null && opened.packageName == app.packageName &&
        (app.userId < 0 || opened.userId == app.userId) && opened.taskId >= 0 &&
        shell.displayOfTask(opened.taskId) == agentDisplay
    ) return true
    val reason = (result as? ActionResult.Failed)?.reason?.let { " ($it)" }.orEmpty()
    throw TargetAppRestoreFailed(
        app.packageName, "Couldn't reopen ${app.packageName} on Mika's screen$reason. Open it before continuing or choose another way.",
    )
}
