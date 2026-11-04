package com.wangzan101.mika.core.permission

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private fun context(
        origin: RunOrigin = RunOrigin.Ui,
        attendance: Attendance = Attendance.Present,
        conversation: String = "c1",
    ) = PermissionContext(RunId("r1"), conversation, RunProvenance(origin, attendance))

    private fun request(
        permission: PermissionId = PermissionCatalog.PHONE_SHARE,
        resource: PermissionResource = PermissionResource.Global,
        origin: RunOrigin = RunOrigin.Ui,
        attendance: Attendance = Attendance.Present,
    ) = PermissionRequest(permission, resource, context(origin, attendance))

    private fun grant(
        permission: PermissionId = PermissionCatalog.PHONE_SHARE,
        resource: PermissionResource = PermissionResource.Global,
        access: Access = Access.Allow,
        scope: GrantScope = GrantScope.Global,
        expiresAtMs: Long? = null,
    ) = Grant(permission, resource, access, scope, expiresAtMs)

    @Test fun catalogDefaultsApplyWithoutGrants() {
        assertEquals(PermissionVerdict.Allow, PermissionPolicy.evaluate(request(PermissionCatalog.PHONE_OBSERVE), PermissionGrants.NONE))
        assertEquals(PermissionVerdict.Ask("Share text to one app"), PermissionPolicy.evaluate(request(), PermissionGrants.NONE))
    }

    @Test fun askIsDeniedWhenNobodyCanAnswer() {
        for (origin in listOf(RunOrigin.Channel, RunOrigin.Trigger, RunOrigin.Mcp, RunOrigin.Child)) {
            val verdict = PermissionPolicy.evaluate(request(origin = origin), PermissionGrants.NONE)
            assertEquals(origin.name, "permission_unattended", (verdict as PermissionVerdict.Deny).code)
        }
        assertEquals("permission_unattended",
            (PermissionPolicy.evaluate(request(attendance = Attendance.Unattended), PermissionGrants.NONE) as PermissionVerdict.Deny).code)
        assertEquals("permission_unattended",
            (PermissionPolicy.evaluate(request(), PermissionGrants.NONE, autonomous = true) as PermissionVerdict.Deny).code)
    }

    @Test fun askStaysAskWhenTheCallerIsNotEnforcingItYet() {
        val verdict = PermissionPolicy.evaluate(
            request(origin = RunOrigin.Trigger, attendance = Attendance.Unattended),
            PermissionGrants.NONE,
            downgradeAsk = false,
        )
        assertEquals(PermissionVerdict.Ask("Share text to one app"), verdict)
    }

    @Test fun aMoreSpecificGrantWins() {
        val globalAsk = grant(access = Access.Ask, scope = GrantScope.Global)
        val conversationAllow = grant(access = Access.Allow, scope = GrantScope.Conversation)
        assertEquals(PermissionVerdict.Allow, PermissionPolicy.evaluate(request(), PermissionGrants(global = listOf(globalAsk), conversation = listOf(conversationAllow))))

        val runAllow = grant(access = Access.Allow, scope = GrantScope.Run)
        val conversationAsk = grant(access = Access.Ask, scope = GrantScope.Conversation)
        assertEquals(PermissionVerdict.Allow, PermissionPolicy.evaluate(request(), PermissionGrants(conversation = listOf(conversationAsk), run = listOf(runAllow))))
    }

    @Test fun explicitDenyBeatsEveryAllow() {
        val globalDeny = grant(access = Access.Deny, scope = GrantScope.Global)
        val runAllow = grant(access = Access.Allow, scope = GrantScope.Run)
        val verdict = PermissionPolicy.evaluate(request(), PermissionGrants(global = listOf(globalDeny), run = listOf(runAllow)))
        assertEquals("permission_denied", (verdict as PermissionVerdict.Deny).code)
    }

    @Test fun askWinsWithinTheSameLayer() {
        val allow = grant(access = Access.Allow, scope = GrantScope.Conversation)
        val ask = grant(access = Access.Ask, scope = GrantScope.Conversation)
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            PermissionPolicy.evaluate(request(), PermissionGrants(conversation = listOf(allow, ask))))
    }

    @Test fun expiredGrantsAreIgnored() {
        val expired = grant(access = Access.Allow, expiresAtMs = 100)
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            PermissionPolicy.evaluate(request(), PermissionGrants(global = listOf(expired)), nowMs = 100))
    }

    @Test fun aOnceGrantAllowsJustThisRequest() {
        val once = grant(access = Access.Allow, scope = GrantScope.Once)
        assertEquals(PermissionVerdict.Allow, PermissionPolicy.evaluate(request(), PermissionGrants.NONE, once = once))
    }

    @Test fun aPackageGrantOnlyMatchesThatPackage() {
        val wechat = PermissionResource.AppPackage("com.tencent.mm")
        val grant = grant(resource = wechat, access = Access.Allow)
        assertEquals(PermissionVerdict.Allow,
            PermissionPolicy.evaluate(request(resource = wechat), PermissionGrants(global = listOf(grant))))
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            PermissionPolicy.evaluate(request(resource = PermissionResource.AppPackage("com.other")), PermissionGrants(global = listOf(grant))))
    }

    @Test fun unknownPermissionFailsClosed() {
        val verdict = PermissionPolicy.evaluate(request(PermissionId("not.a.permission")), PermissionGrants.NONE)
        assertTrue(verdict is PermissionVerdict.Deny)
        assertEquals("permission_unknown", (verdict as PermissionVerdict.Deny).code)
    }
}
