package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.cap.mcp.McpResultStore
import java.io.File
import java.util.UUID

/** 插件工具的长结果存到 `files/plugins/<id>/results/`,返回引用;预览和截断由 McpClientTool 管。 */
class PluginResultStore(private val pluginsDir: File) : McpResultStore {
    override fun save(serverId: String, toolName: String, content: String): String {
        val dir = File(File(pluginsDir, serverId), "results").apply { mkdirs() }
        val safeTool = toolName.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifBlank { "tool" }
        val name = "${System.currentTimeMillis()}-$safeTool-${UUID.randomUUID()}.txt"
        File(dir, name).writeText(content)
        return "plugins/$serverId/results/$name"
    }
}
