package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.permission.PermissionGate
import com.wangzan101.mika.core.permission.PermissionVerdict
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEnforcementTest {
    private fun gate(denied: Set<String>) = PermissionGate { tool ->
        if (tool.name in denied) PermissionVerdict.Deny("permission_denied", "The user turned this off.")
        else PermissionVerdict.Allow
    }

    @Test fun deniedToolsAreNotOfferedToTheModel() {
        val dispatcher = toolDispatcher(FakeScreen(), permissionGate = gate(setOf("phone_tap")))
        val offered = dispatcher.offeredNow(all = true).map { it.name }
        assertFalse("phone_tap" in offered)
        assertTrue("phone_observe" in offered)
    }

    @Test fun deniedReadIsRefusedBeforeTouchingTheScreen() = runTest {
        var reads = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): com.wangzan101.mika.core.uistate.ScreenSnapshot {
                reads++
                error("must not read")
            }
        }
        val dispatcher = toolDispatcher(screen, permissionGate = gate(setOf("phone_observe")))
        val result = dispatcher.executeRead(Part.ToolCall("c", "phone_observe", "{}"))
        assertEquals("permission_denied", (result.content["error"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(0, reads)
    }

    @Test fun deniedWriteIsRefusedBeforeTheExecutorRunsIt() = runTest {
        var taps = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun tapXY(x: Int, y: Int): ActionResult { taps++; return ActionResult.Ok() }
        }
        val dispatcher = toolDispatcher(screen, permissionGate = gate(setOf("phone_tap")))
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, gate = {},
            recorder = ActionRecorder(RunSink.NONE), autonomous = true)
        val outcome = executor.run(Part.ToolCall("tap", "phone_tap", """{"x":10,"y":10}"""), Origin.Model(1))
        assertTrue("$outcome", outcome is ActionOutcome.Refused)
        assertEquals("permission_denied", (outcome as ActionOutcome.Refused).code)
        assertEquals(0, taps)
    }

    @Test fun askDoesNotBlockInPhase2a() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), permissionGate = PermissionGate { PermissionVerdict.Ask("ask first") })
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, gate = {},
            recorder = ActionRecorder(RunSink.NONE), autonomous = true)
        val outcome = executor.run(Part.ToolCall("tap", "phone_tap", """{"x":10,"y":10}"""), Origin.Model(1))
        assertTrue("$outcome", outcome is ActionOutcome.Ran)
    }

    @Test fun theRunRecordUsesTheRealVerdictWhenAGateIsPresent() = runTest {
        val dispatcher = toolDispatcher(FakeScreen(), permissionGate = gate(setOf("phone_observe")))
        val events = mutableListOf<RunEvent.Permission>()
        dispatcher.permissionObserver = { events += it }
        dispatcher.executeRead(Part.ToolCall("c", "phone_observe", "{}"))
        val denied = events.single { it.tool == "phone_observe" }
        assertEquals("denied", denied.decision)
        assertEquals("phone.observe", denied.permission)
    }
}
