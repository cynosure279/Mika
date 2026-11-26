package com.wangzan101.mika.chat

import com.wangzan101.mika.settings.ModelChoice

/**
 * 一个对话自己的运行设置，存在对话目录的 `<id>.settings.json` 里。
 * [main] 为 null 表示跟随全局主模型；对话设置只影响下一次任务。
 */
data class ConversationSettings(
    val main: ModelChoice? = null,
)
