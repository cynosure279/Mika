package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.ModelChoice

/**
 * 一个对话自己的运行设置,存在对话目录的 `<id>.settings.json` 里。现在只有主模型覆盖;
 * 思考档位、技能和 MCP 的对话级限制在后续 PR 里加。
 * [main] 为 null 表示跟随设置里的主模型;别的字段留默认表示跟随设置。
 */
data class ConversationSettings(
    val main: ModelChoice? = null,
)
