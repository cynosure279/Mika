package com.wangzan101.mika.settings

import com.wangzan101.mika.chat.ConversationSettings
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.WorkspaceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 文档 `docs/dev/conversation-workspace.md` 里的两份样例 JSON,测试直接解析,避免文档和实现脱节。 */
class WorkspaceSamplesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sample(name: String): String =
        javaClass.classLoader!!.getResource("samples/$name")!!.readText()

    @Test fun conversationSettingsSampleParses() {
        val dir = tmp.newFolder("conversations")
        File(dir, "sample.settings.json").writeText(sample("conversation-settings.json"))
        val settings = ConversationStore(dir).settings("sample")
        assertEquals(
            ConversationSettings(
                main = ModelChoice.Group("group-main"),
                reasoningEffort = "high",
                skills = WorkspaceChoice.Only(setOf("documents", "personal-notes")),
                mcpServers = WorkspaceChoice.All,
            ),
            settings,
        )
    }

    @Test fun workspaceAccessSampleParsesAndResolves() {
        val file = File(tmp.newFolder("settings"), "workspace-access.json")
        file.writeText(sample("workspace-access.json"))
        val access = WorkspaceAccessStore(file).current()
        assertFalse(access.skillsAll)
        assertFalse(access.mcpAll)
        assertEquals(setOf("documents", "research"), access.skills)
        assertEquals(setOf("mcp-browser"), access.mcpServers)
        // 以后新增的项不在 known 里,界面据此标「新」
        assertEquals(setOf("documents", "research", "writing"), access.skillsKnown)
        assertEquals(setOf("mcp-browser", "mcp-db"), access.mcpKnown)
        // 对话跟随全局时的实际集合
        assertEquals(setOf("documents", "research"), access.skillsFor(WorkspaceChoice.FollowGlobal))
        assertEquals(setOf("mcp-browser"), access.mcpFor(WorkspaceChoice.FollowGlobal))
    }
}
