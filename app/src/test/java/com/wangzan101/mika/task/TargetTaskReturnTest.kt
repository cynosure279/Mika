package com.wangzan101.mika.task

import android.view.Display
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.TargetApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TargetTaskReturnTest {
    @get:Rule val texts = ChineseTexts()

    private val target = MutableStateFlow<TargetApp?>(TargetApp("game.app", 10, 12))
    private val tasks = mutableMapOf<Int, Int>()
    private val moves = mutableListOf<Int>()
    private var lookups = 0
    private var launches = 0
    private var disappearOnMove: Int? = null
    private var launchSucceeds = true
    private val shell = object : IMikaShell.Default() {
        override fun displayOfTask(taskId: Int) = tasks[taskId] ?: Display.INVALID_DISPLAY

        override fun findTaskForPackage(packageName: String, userId: Int, displayId: Int): IntArray {
            lookups++
            assertEquals("game.app", packageName)
            assertEquals(12, userId)
            assertEquals(AGENT, displayId)
            val location = tasks.entries.firstOrNull() ?: return intArrayOf()
            return intArrayOf(location.key, location.value)
        }
    }
    private val gate = TaskGate(MutableStateFlow(false), MutableStateFlow(true)) {
        restoreTargetApp(target, shell, AGENT, moveBack = { id ->
            moves += id
            if (id == disappearOnMove) {
                tasks.remove(id)
                false
            } else {
                tasks[id] = AGENT
                true
            }
        }, relaunch = { app ->
            launches++
            assertEquals(TargetApp("game.app", 10, 12), app)
            if (launchSucceeds) {
                tasks[30] = AGENT
                target.value = app.copy(taskId = 30)
                ActionResult.Ok()
            } else {
                ActionResult.Failed("app_not_found")
            }
        })
    }
    private val reported = mutableListOf<TaskState?>()

    @Test
    fun staleTargetIsReplacedByCurrentTaskOnMainDisplay() = runTest {
        tasks[20] = Display.DEFAULT_DISPLAY

        continueAfterMove()

        assertEquals(AGENT, tasks[20])
        assertEquals(TargetApp("game.app", 20, 12), target.value)
        assertEquals(listOf(20), moves)
        assertEquals(0, launches)
        assertFalse(gate.movedAwayPending)
    }

    @Test
    fun rechecksTargetIfItDisappearsBetweenLookupAndMove() = runTest {
        tasks[10] = Display.DEFAULT_DISPLAY
        tasks[20] = Display.DEFAULT_DISPLAY
        disappearOnMove = 10

        continueAfterMove()

        assertEquals(listOf(10, 20), moves)
        assertEquals(TargetApp("game.app", 20, 12), target.value)
        assertEquals(0, launches)
    }

    @Test
    fun goneTargetRelaunchesAndLeavesThePause() = runTest {
        continueAfterMove()

        assertEquals(1, launches)
        assertEquals(AGENT, tasks[30])
        assertEquals(TargetApp("game.app", 30, 12), target.value)
        assertTrue(moves.isEmpty())
        assertFalse(gate.movedAwayPending)
        assertEquals(null, reported.last())
    }

    @Test
    fun failedRelaunchEndsPauseWithActionableFailure() = runTest {
        launchSucceeds = false

        val failure = runCatching { continueAfterMove() }.exceptionOrNull()

        assertTrue(failure is TargetAppRestoreFailed)
        assertEquals(1, launches)
        assertFalse(gate.movedAwayPending)
        assertEquals(null, reported.last())
        withTimeout(1_000) { gate.pass { reported += it } }
        assertEquals(1, launches)
    }

    @Test
    fun validTargetMovesWithoutLookingUpOrLaunchingAnotherTask() = runTest {
        tasks[10] = Display.DEFAULT_DISPLAY

        continueAfterMove()

        assertEquals(AGENT, tasks[10])
        assertEquals(TargetApp("game.app", 10, 12), target.value)
        assertEquals(listOf(10), moves)
        assertEquals(0, lookups)
        assertEquals(0, launches)
    }

    private suspend fun continueAfterMove() {
        gate.onTaskMovedAway()
        gate.resume()
        withTimeout(1_000) { gate.pass { reported += it } }
    }

    private companion object { const val AGENT = 9 }
}
