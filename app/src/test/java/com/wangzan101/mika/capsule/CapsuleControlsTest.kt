package com.wangzan101.mika.capsule

import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CapsuleControlsTest {
    @get:Rule val texts = ChineseTexts()

    private fun tucked(kind: CapsuleKind = CapsuleKind.Idle, pill: Boolean = false, needs: Int = 0,
        expanded: Boolean = false, dragging: Boolean = false, talking: Boolean = false, note: Boolean = false,
        idle: Long = CapsuleFade.IDLE_MS, enabled: Boolean = true) =
        CapsuleEdge.hidden(enabled, kind, pill, needs, expanded, dragging, talking, note, idle)

    @Test fun draggedBallTucksOnlyAfterTheIdleDelay() {
        assertFalse(tucked(enabled = false))
        assertFalse(tucked(idle = CapsuleFade.IDLE_MS - 1))
        assertTrue(tucked())
        assertTrue(tucked(kind = CapsuleKind.Running))
    }

    @Test fun requestsSpeechCardsAndDraggingKeepTheBallVisible() {
        for (kind in CapsuleKind.entries.filter { it.needsYou }) assertFalse(tucked(kind = kind))
        assertFalse(tucked(kind = CapsuleKind.HandedOver))
        assertFalse(tucked(pill = true))
        assertFalse(tucked(needs = 1))
        assertFalse(tucked(expanded = true))
        assertFalse(tucked(dragging = true))
        assertFalse(tucked(talking = true))
        assertFalse(tucked(note = true))
    }

    @Test fun halfCircleTouchesEachSafeEdgeWithoutNegativeWindowCoordinates() {
        val area = DockArea.of(com.wangzan101.mika.cap.phone.a11y.OverlayArea(1000, 2000, insetLeft = 30, insetRight = 10), 12, 8, 8)
        assertEquals(30, CapsuleEdge.offset(DockSide.Left, area, 8))
        assertEquals(10, CapsuleEdge.offset(DockSide.Right, area, 8))
        val plain = DockArea.of(com.wangzan101.mika.cap.phone.a11y.OverlayArea(1000, 2000), 12, 8, 8)
        assertEquals(0, CapsuleEdge.offset(DockSide.Left, plain, 8))
        assertEquals(0, CapsuleEdge.offset(DockSide.Right, plain, 8))
    }

    @Test fun fullscreenNeedsHiddenStatusBarAndAFullWidthForegroundApp() {
        val full = CapsuleAppBounds(0, 0, 1080, 2400)
        assertTrue(CapsuleFullscreen.matches(1080, 2400, false, full))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, true, full))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, null, full))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, false, null))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, false, CapsuleAppBounds(0, 0, 540, 2400)))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, false, CapsuleAppBounds(0, 0, 1080, 1200)))
        assertFalse(CapsuleFullscreen.matches(1080, 2400, false, CapsuleAppBounds(0, 90, 1080, 2400)))
        assertTrue(CapsuleFullscreen.matches(1080, 2400, false, CapsuleAppBounds(0, 0, 1080, 2300), 100))
    }

    @Test fun fullscreenAllowsLandscapeNavigationBarsAndDisplayCutouts() {
        val landscape = CapsuleAppBounds(0, 0, 2300, 1080)
        assertFalse(CapsuleFullscreen.matches(2400, 1080, false, landscape))
        assertTrue(CapsuleFullscreen.matches(2400, 1080, false, landscape, rightInset = 100))
        val cutout = CapsuleAppBounds(80, 0, 2400, 1080)
        assertTrue(CapsuleFullscreen.matches(2400, 1080, false, cutout, leftInset = 80))
        assertFalse(CapsuleFullscreen.matches(2400, 1080, true, cutout, leftInset = 80))
    }

    @Test fun fullscreenHideAndExitRestoreTheExistingModel() {
        val model = CapsuleModel()
        val input = CapsuleInputs(emptyList(), CapsuleMode.Always, false, true, true)
        assertTrue(model.update(input, 0) is CapsuleView.Shown)
        assertEquals(CapsuleView.Hidden, model.update(input.copy(fullscreen = true), 1))
        assertTrue(model.update(input, 2) is CapsuleView.Shown)
        assertEquals(CapsuleView.Hidden, model.update(input.copy(mode = CapsuleMode.Off), 3))
    }

    @Test fun tileCanTurnOffButDoesNotEnableWithoutAccessibilityOrAfterQuit() {
        assertEquals(CapsuleTileAction.TurnOff, CapsuleTilePolicy.action(CapsuleMode.Always, false, true))
        assertEquals(CapsuleTileAction.OpenApp, CapsuleTilePolicy.action(CapsuleMode.Off, false, false))
        assertEquals(CapsuleTileAction.OpenApp, CapsuleTilePolicy.action(CapsuleMode.Off, true, true))
        assertEquals(CapsuleTileAction.TurnOn, CapsuleTilePolicy.action(CapsuleMode.Off, true, false))
    }

    @Test fun enablingCapsuleRequestsAccessibilityOnlyWhenItIsMissing() {
        assertFalse(CapsuleSetup.needsAccessibility(CapsuleMode.Off, false))
        assertFalse(CapsuleSetup.needsAccessibility(CapsuleMode.Always, true))
        assertTrue(CapsuleSetup.needsAccessibility(CapsuleMode.Always, false))
        assertTrue(CapsuleSetup.needsAccessibility(CapsuleMode.DuringTasks, false))
    }

    @Test fun edgeAutoHideDoesNotWakeOnAppearOrSameStateUnlessAttentionNeeded() {
        val idle = CapsuleLook(CapsuleKind.Idle, pill = false, count = 0, needCount = 0)
        val running = CapsuleLook(CapsuleKind.Running, pill = false, count = 1, needCount = 0)
        val runningTwo = CapsuleLook(CapsuleKind.Running, pill = false, count = 2, needCount = 0)
        val notice = CapsuleLook(CapsuleKind.Notice, pill = false, count = 2, needCount = 0)
        val noticeOne = CapsuleLook(CapsuleKind.Notice, pill = false, count = 1, needCount = 0)
        val confirm = CapsuleLook(CapsuleKind.NeedConfirm, pill = true, count = 1, needCount = 1)

        // With auto-hide at edge enabled, reappearing from hidden stays tucked unless a non-idle state changed.
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = null, current = idle))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = idle, current = idle))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = running, current = idle))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = running, current = running))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = notice, current = notice))

        // New non-idle states appearing while hidden still wake briefly / show immediately.
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = null, current = running))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = idle, current = running))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = running, current = runningTwo))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = idle, current = noticeOne))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = noticeOne, current = notice))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = idle, current = confirm))

        // With auto-hide at edge disabled, reappearing from hidden always wakes as before.
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = false, visible = false, previous = null, current = idle))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = false, visible = false, previous = idle, current = idle))

        // While already visible, only look changes wake.
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = true, previous = idle, current = idle))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = true, previous = idle, current = running))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = false, visible = true, previous = idle, current = idle))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = false, visible = true, previous = idle, current = running))
    }

    @Test fun anotherRunWithTheSameLookStillWakesTheTuckedBall() {
        val runA = CapsuleLook.of(CapsuleView.Shown(CapsuleKind.Running, "a", count = 1, runKey = "run-a"))
        val runAnextStep = CapsuleLook.of(CapsuleView.Shown(CapsuleKind.Running, "a, next step", count = 1, runKey = "run-a"))
        val runB = CapsuleLook.of(CapsuleView.Shown(CapsuleKind.Running, "b", count = 1, runKey = "run-b"))

        // Same run, only the line changed: stays tucked, whether it was hidden or visible.
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = runA, current = runAnextStep))
        assertFalse(CapsuleEdge.shouldWake(hideAtEdge = true, visible = true, previous = runA, current = runAnextStep))
        // Run A ended and run B started while the capsule was hidden: show it.
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = false, previous = runA, current = runB))
        assertTrue(CapsuleEdge.shouldWake(hideAtEdge = true, visible = true, previous = runA, current = runB))
    }
}
