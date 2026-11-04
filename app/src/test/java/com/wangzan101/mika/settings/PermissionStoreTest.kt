package com.wangzan101.mika.settings

import com.wangzan101.mika.core.permission.Access
import com.wangzan101.mika.core.permission.Grant
import com.wangzan101.mika.core.permission.GrantScope
import com.wangzan101.mika.core.permission.GrantSource
import com.wangzan101.mika.core.permission.PermissionCatalog
import com.wangzan101.mika.core.permission.PermissionContext
import com.wangzan101.mika.core.permission.PermissionRequest
import com.wangzan101.mika.core.permission.PermissionResource
import com.wangzan101.mika.core.permission.PermissionVerdict
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = java.io.File(temp.root, "permissions.json")
    private fun context(conversation: String = "c1") = PermissionContext(RunId("r1"), conversation)
    private fun request(permission: com.wangzan101.mika.core.permission.PermissionId = PermissionCatalog.PHONE_SHARE) =
        PermissionRequest(permission, context = context())

    @Test fun aNewStoreHasNoGrantsAndUsesCatalogDefaults() {
        val store = PermissionStore(file())
        assertTrue(store.state.value.available)
        assertTrue(store.state.value.global.isEmpty())
        assertEquals(PermissionVerdict.Allow, store.evaluate(request(PermissionCatalog.PHONE_OBSERVE)))
        assertEquals(PermissionVerdict.Ask("Share text to one app"), store.evaluate(request()))
    }

    @Test fun globalGrantsPersistAtomically() {
        val store = PermissionStore(file())
        store.addGlobal(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Allow, scope = GrantScope.Global))
        assertEquals(PermissionVerdict.Allow, store.evaluate(request()))

        val reopened = PermissionStore(file())
        assertEquals(PermissionVerdict.Allow, reopened.evaluate(request()))
        assertEquals(GrantScope.Global, reopened.state.value.global.single().scope)
    }

    @Test fun conversationGrantsDoNotLeak() {
        val store = PermissionStore(file())
        store.addConversation("c1", Grant(PermissionCatalog.PHONE_SHARE, access = Access.Allow, scope = GrantScope.Conversation))
        assertEquals(PermissionVerdict.Allow, store.evaluate(request()))
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            store.evaluate(PermissionRequest(PermissionCatalog.PHONE_SHARE, context = context("c2"))))
    }

    @Test fun runGrantsLiveOnlyInMemory() {
        val store = PermissionStore(file())
        store.setRun("r1", listOf(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Allow, scope = GrantScope.Run)))
        assertEquals(PermissionVerdict.Allow, store.evaluate(request()))
        store.clearRun("r1")
        assertEquals(PermissionVerdict.Ask("Share text to one app"), store.evaluate(request()))
        assertTrue(PermissionStore(file()).state.value.global.isEmpty())
    }

    @Test fun aDenyStillWinsOverARunGrant() {
        val store = PermissionStore(file())
        store.addGlobal(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Deny, scope = GrantScope.Global))
        store.setRun("r1", listOf(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Allow, scope = GrantScope.Run)))
        assertEquals("permission_denied", (store.evaluate(request()) as PermissionVerdict.Deny).code)
    }

    @Test fun aCorruptFileFailsClosedAndCanBeReplaced() {
        file().writeText("{bad")
        val store = PermissionStore(file())
        assertFalse(store.state.value.available)
        assertEquals(PermissionVerdict.Ask("Share text to one app"), store.evaluate(request()))
        store.addGlobal(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Allow, scope = GrantScope.Global))
        assertTrue(store.state.value.available)
        assertEquals(PermissionVerdict.Allow, store.evaluate(request()))
    }

    @Test fun legacySwitchesAreSyncedAndStaleValuesDisappear() {
        val store = PermissionStore(file())
        val legacy = PermissionMigration.Legacy(
            blockedApps = setOf("com.bank"),
            shareAllowedApps = setOf("com.wechat"),
            sandboxNetwork = true,
            selfConfig = false,
            enabledMcpServers = listOf("server-1"),
        )
        assertTrue(store.syncLegacy(legacy))
        val grants = store.state.value.global
        assertTrue(grants.any { it.permission == PermissionCatalog.PHONE_APP && it.resource == PermissionResource.AppPackage("com.bank") && it.access == Access.Deny })
        assertTrue(grants.any { it.permission == PermissionCatalog.PHONE_SHARE && it.resource == PermissionResource.AppPackage("com.wechat") && it.access == Access.Allow })
        assertTrue(grants.any { it.permission == PermissionCatalog.SANDBOX_NETWORK && it.access == Access.Allow })
        assertTrue(grants.any { it.permission == PermissionCatalog.SETTINGS_SELF && it.access == Access.Ask })
        assertTrue(grants.any { it.permission == PermissionCatalog.MCP && it.resource == PermissionResource.McpServer("server-1") })
        assertTrue(grants.all { it.source == GrantSource.MigratedLegacy })

        // 用户改过的授权保留;旧开关后来解除时,过期的迁移授权消失
        store.addGlobal(Grant(PermissionCatalog.PHONE_SHARE, access = Access.Deny, scope = GrantScope.Global))
        store.syncLegacy(legacy.copy(blockedApps = emptySet(), enabledMcpServers = emptyList()))
        val synced = store.state.value.global
        assertFalse(synced.any { it.permission == PermissionCatalog.PHONE_APP && it.resource == PermissionResource.AppPackage("com.bank") })
        assertFalse(synced.any { it.permission == PermissionCatalog.MCP })
        assertEquals(Access.Deny, synced.first { it.permission == PermissionCatalog.PHONE_SHARE && it.resource == PermissionResource.Global }.access)
        assertEquals(Access.Deny, PermissionStore(file()).state.value.global.first { it.permission == PermissionCatalog.PHONE_SHARE && it.resource == PermissionResource.Global }.access)
    }

    @Test fun resourceLabelsRoundTrip() {
        for (resource in listOf(
            PermissionResource.Global,
            PermissionResource.AppPackage("com.x"),
            PermissionResource.Directory("/workspace"),
            PermissionResource.McpServer("s1"),
            PermissionResource.Plugin("p1"),
            PermissionResource.RemoteEnv("e1"),
        )) {
            assertEquals(resource, permissionResourceOf(resource.label()))
        }
        assertNull(permissionResourceOf("unknown:resource"))
    }

    @Test fun storedGrantsWithMissingEnumFieldsUseTheDocumentedDefaults() {
        file().writeText("""{"version":1,"grants":[{"permission":"phone.share","access":"Allow"}]}""")
        val store = PermissionStore(file())
        assertTrue(store.state.value.available)
        val grant = store.state.value.global.single()
        assertEquals(GrantScope.Conversation, grant.scope)
        assertEquals(GrantSource.User, grant.source)
        assertEquals(PermissionResource.Global, grant.resource)
    }

    @Test fun anUnknownResourceFailsClosedInsteadOfWideningToGlobal() {
        file().writeText("""{"version":1,"grants":[{"permission":"phone.share","resource":"bogus","access":"Allow"}]}""")
        assertFalse(PermissionStore(file()).state.value.available)
    }

    private fun tool(name: String, verb: com.wangzan101.mika.core.tool.Verb, capability: String? = null) =
        object : com.wangzan101.mika.core.tool.ToolInfo {
            override val name = name
            override val verb = verb
            override val capability = capability
            override val batchable = false
            override val routable = false
            override val label = null
        }

    @Test fun theGateOnlyClosesExplicitDeniesAndFailsClosedForUnknownTools() {
        val store = PermissionStore(file())
        store.addGlobal(Grant(PermissionCatalog.PHONE_OBSERVE, access = Access.Deny, scope = GrantScope.Global))
        val gate = store.gateFor(context())
        val denied = gate.verdict(tool("phone_observe", com.wangzan101.mika.core.tool.Verb.Observe, "phone"))
        assertEquals("permission_denied", (denied as PermissionVerdict.Deny).code)
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            gate.verdict(tool("phone_share_text", com.wangzan101.mika.core.tool.Verb.ShareText, "phone")))
        assertEquals("permission_unknown",
            (gate.verdict(tool("mystery", com.wangzan101.mika.core.tool.Verb.Interact, "unknown")) as PermissionVerdict.Deny).code)
    }

    @Test fun theGateKeepsAskOpenWhenNobodyCanAnswerInPhase2a() {
        val store = PermissionStore(file())
        val unattended = PermissionContext(
            RunId("r1"), "c1", RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
        )
        assertEquals(PermissionVerdict.Ask("Share text to one app"),
            store.gateFor(unattended).verdict(tool("phone_share_text", com.wangzan101.mika.core.tool.Verb.ShareText, "phone")))
    }
}
