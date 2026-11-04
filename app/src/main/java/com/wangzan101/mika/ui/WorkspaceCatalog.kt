package com.wangzan101.mika.ui

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.SKILL_KINDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 工作区里可选的一项:[id] 是存进设置的名字/编号,[label] 给人看。 */
data class WorkspaceItem(val id: String, val label: String)

/** 设置页和对话「工具范围」列技能 / MCP 用的名字;没有这一项时对应列表为空。 */
interface WorkspaceCatalog {
    /** MCP server 列表变了(增删);对话页据此重读清单。 */
    val changes: Flow<Unit>

    fun skills(): List<WorkspaceItem>
    fun mcpServers(): List<WorkspaceItem>
}

/** 内置技能 + 个人技能(个人同名覆盖内置)和 MCP server 的名字。 */
class AppWorkspaceCatalog(context: Context) : WorkspaceCatalog {
    private val app = context.applicationContext as MikaApp

    override val changes: Flow<Unit> = app.mcpServers.servers.map { }

    override fun skills(): List<WorkspaceItem> =
        GuideCatalog.withPersonal(GuideCatalog.loadBundled(), app.memory.skills()).items
            .filter { it.kind in SKILL_KINDS }
            .distinctBy { it.name }
            .sortedBy { it.display.lowercase() }
            .map { WorkspaceItem(it.name, it.display.ifBlank { it.name }) }

    override fun mcpServers(): List<WorkspaceItem> =
        app.mcpServers.servers.value.sortedBy { it.name.lowercase() }.map { WorkspaceItem(it.id, it.name) }
}
