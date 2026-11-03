package com.wangzan101.mika.core.permission

import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCatalogTest {
    private fun tool(name: String, verb: Verb, capability: String? = null) = object : ToolInfo {
        override val name = name
        override val verb = verb
        override val capability = capability
        override val batchable = false
        override val routable = false
        override val label = null
    }

    @Test fun everyKnownToolMapsToAKnownPermission() {
        for (tool in allTools()) {
            val id = PermissionCatalog.permissionFor(tool)
            assertNotNull("${tool.name} (${tool.capability}/${tool.verb}) has no permission", id)
            assertNotNull("${tool.name} -> $id has no spec", PermissionCatalog.spec(id!!))
        }
    }

    @Test fun representativeToolsMapToExpectedPermissions() {
        val expected = mapOf(
            "phone_observe" to PermissionCatalog.PHONE_OBSERVE,
            "phone_tap" to PermissionCatalog.PHONE_ACT,
            "phone_share_text" to PermissionCatalog.PHONE_SHARE,
            "phone_system_command" to PermissionCatalog.PHONE_SYSTEM,
            "phone_list_apps" to PermissionCatalog.PHONE_APPS,
            "phone_set_alarm" to PermissionCatalog.PHONE_ALARM,
            "web_navigate" to PermissionCatalog.WEB_READ,
            "web_script" to PermissionCatalog.WEB_SCRIPT,
            "web_upload" to PermissionCatalog.WEB_TRANSFER,
            "run_command" to PermissionCatalog.SANDBOX_EXEC,
            "write_file" to PermissionCatalog.SANDBOX_FILES,
            "memory_search" to PermissionCatalog.MEMORY_READ,
            "memory_save" to PermissionCatalog.MEMORY_WRITE,
            "schedule_task" to PermissionCatalog.SCHEDULE_WRITE,
            "spawn_agent" to PermissionCatalog.CHILD_SPAWN,
            "settings_set" to PermissionCatalog.SETTINGS_SELF,
        )
        val byName = allTools().associateBy { it.name }
        for ((name, id) in expected) assertEquals(name, id, PermissionCatalog.permissionFor(byName.getValue(name)))
    }

    @Test fun mcpToolsUseTheServerAsResource() {
        val mcp = tool("mcp_demo__send", Verb.Interact, "mcp:demo")
        assertEquals(PermissionCatalog.MCP, PermissionCatalog.permissionFor(mcp))
        assertEquals(PermissionResource.McpServer("demo"), PermissionCatalog.resourceFor(mcp))
    }

    @Test fun unknownCapabilityIsNotAllowedByDefault() {
        assertNull(PermissionCatalog.permissionFor(tool("mystery", Verb.Interact, "unknown")))
    }

    @Test fun observationUsesThePlannedDefaultWithoutChangingExecution() {
        val observe = tool("phone_observe", Verb.Observe, "phone")
        val share = tool("phone_share_text", Verb.ShareText, "phone")
        assertEquals("would_allow", PermissionCatalog.observe(observe)?.decision)
        assertEquals("would_ask", PermissionCatalog.observe(share)?.decision)
        assertEquals("global", PermissionCatalog.observe(share)?.resource?.label())
    }

    @Test fun dispatcherRecordsAnObservationForEveryResolvedCall() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val events = mutableListOf<RunEvent.Permission>()
        dispatcher.permissionObserver = { events += it }

        dispatcher.executeRead(Part.ToolCall("c1", "phone_list_apps", "{}"))
        dispatcher.executeRead(Part.ToolCall("c2", "not_a_tool", "{}"))

        val seen = events.filter { it.tool == "phone_list_apps" }
        assertEquals(1, seen.size)
        assertEquals("phone.apps", seen.single().permission)
        assertEquals("would_allow", seen.single().decision)
        assertEquals("observe", seen.single().scope)
        assertTrue(events.none { it.tool == "not_a_tool" })
    }
}
