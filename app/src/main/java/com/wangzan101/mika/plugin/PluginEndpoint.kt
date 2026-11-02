package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个启用插件暴露给模型的工具。名字、风险和成本都按 manifest 转成现有的 MCP 描述,
 * 实际执行交给 [runner];调用入口不信任插件自称的只读注解(McpConnection 用 Restricted)。
 */
class PluginEndpoint(
    private val record: PluginRecord,
    private val manifest: PluginManifest,
    private val runner: PluginScriptRunner,
    /** 全局联网开关;插件自己的 network 权限还要同时满足。 */
    private val networkAllowed: () -> Boolean = { false },
    private val log: PluginLog? = null,
) : McpEndpoint {
    override val serverId: String = record.id

    override fun tools(): List<McpToolDescriptor> = manifest.tools.map { tool ->
        McpToolDescriptor(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema ?: emptySchema(),
            annotations = McpAnnotations(
                readOnlyHint = tool.annotations["readOnlyHint"] ?: false,
                destructiveHint = tool.annotations["destructiveHint"] ?: false,
                openWorldHint = tool.annotations["openWorldHint"] ?: false,
            ),
            estimatedCostUnits = tool.maxCostUnits?.toLong() ?: 0,
        )
    }

    override suspend fun call(name: String, arguments: JsonObject): McpResult {
        val tool = manifest.tools.firstOrNull { it.name == name }
            ?: return McpResult("unknown plugin tool: $name", isError = true)
        if ("sandbox.run" !in manifest.permissions) {
            return McpResult("permission_denied: sandbox.run", isError = true)
        }
        val script = tool.script ?: return McpResult("invalid_tool: ${tool.name} has no script", isError = true)
        val network = "network" in manifest.permissions && networkAllowed()
        val started = System.currentTimeMillis()
        return try {
            val result = runner.run(record.id, script, arguments, tool.timeoutSec.coerceIn(1, 600), network)
            log?.append(
                "tool",
                "${tool.name} exit=${result.exitCode} timedOut=${result.timedOut} ms=${System.currentTimeMillis() - started}",
            )
            McpResult(
                text = resultText(result.stdout.ifBlank { result.stderr }),
                isError = result.exitCode != 0 || result.timedOut,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.append("tool", "${tool.name} error=${e.javaClass.simpleName}")
            McpResult("plugin_error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private companion object {
        fun emptySchema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", JsonArray(emptyList()))
        }

        /** 脚本约定:最后一行是 JSON 时只把它当结果,否则整段输出。 */
        fun resultText(stdout: String): String {
            val last = stdout.trimEnd().lineSequence().lastOrNull()?.trim().orEmpty()
            if (last.startsWith("{") || last.startsWith("[")) {
                if (runCatching { Json.parseToJsonElement(last) }.isSuccess) return last
            }
            return stdout
        }
    }
}
