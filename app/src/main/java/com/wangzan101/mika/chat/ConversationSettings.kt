package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.ModelChoice

/**
 * 一个对话对工作区可用项的覆盖:跟随全局列表、全部可用,或只用集合里的项。
 * 默认跟随全局;`All` 是完整挂载;`Only(empty)` 是明确全关。
 */
sealed interface WorkspaceChoice {
    data object FollowGlobal : WorkspaceChoice
    data object All : WorkspaceChoice
    data class Only(val ids: Set<String>) : WorkspaceChoice
}

/**
 * 一个对话自己的运行设置,存在对话目录的 `<id>.settings.json` 里。
 * [main] 为 null 表示跟随设置里的主模型;[reasoningEffort] 为 null 表示跟随模型/用途的默认档位;
 * 技能和 MCP 默认跟随全局允许列表。所有字段留默认时旁文件会被删掉。
 */
data class ConversationSettings(
    val main: ModelChoice? = null,
    val reasoningEffort: String? = null,
    val skills: WorkspaceChoice = WorkspaceChoice.FollowGlobal,
    val mcpServers: WorkspaceChoice = WorkspaceChoice.FollowGlobal,
)
