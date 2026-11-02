package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.cap.mcp.mcpApprovalRisk
import com.wangzan101.mika.core.action.ActionRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PluginToolResult(val text: String, val isError: Boolean = false)

/** 页面桥真正做事的一端;Activity 用沙箱/设置实现,JVM 测试用 fake。 */
interface PluginBridgeHost {
    suspend fun settingsGet(key: String): String?
    suspend fun settingsSet(key: String, value: String?): String?
    suspend fun secretSet(key: String): Boolean
    suspend fun secretWrite(key: String, value: String?): Boolean
    suspend fun callTool(name: String, args: JsonObject): PluginToolResult
    suspend fun workspaceRead(path: String): String?
    suspend fun workspaceWrite(path: String, text: String): Boolean
    suspend fun workspaceList(path: String): List<String>
    suspend fun runScript(name: String, args: JsonObject): ScriptResult
    fun close()
}

/**
 * 插件页面的白名单 RPC。只认 manifest 里的设置键/工具/权限;路径规范化后必须留在
 * 插件自己的工作区;secret 在 P2 不返回明文(也没有 secret 类型)。
 */
class PluginBridge(
    private val pluginId: String,
    private val manifest: PluginManifest,
    private val host: PluginBridgeHost,
) {
    suspend fun handle(request: JsonObject): JsonObject {
        val id: JsonElement = request["id"] ?: JsonNull
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, "bad_request", "method is required")
        val params = (request["params"] as? JsonObject) ?: JsonObject(emptyMap())
        return try {
            val result = when (method) {
                "settings.get" -> settingsGet(params)
                "settings.set" -> settingsSet(params)
                "tools.list" -> toolsList()
                "tools.call" -> toolsCall(params)
                "workspace.read" -> workspaceRead(params)
                "workspace.write" -> workspaceWrite(params)
                "workspace.list" -> workspaceList(params)
                "runScript" -> runScript(params)
                "close" -> { host.close(); JsonNull }
                else -> return error(id, "unknown_method", "unknown method: $method")
            }
            ok(id, result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Denied) {
            error(id, "permission_denied", e.message.orEmpty())
        } catch (e: BadRequest) {
            error(id, "bad_request", e.message.orEmpty())
        } catch (e: Internal) {
            error(id, "internal_error", e.message.orEmpty())
        } catch (e: Exception) {
            error(id, "internal_error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun has(permission: String) = permission in manifest.permissions

    private fun declaredSetting(key: String?): PluginSettingDecl? =
        manifest.settings.firstOrNull { it.key == key }

    private suspend fun settingsGet(params: JsonObject): JsonElement {
        if (!has("settings.plugin")) throw Denied("settings.plugin")
        val key = params["key"]?.jsonPrimitive?.contentOrNull
            ?: throw BadRequest("key is required")
        val setting = declaredSetting(key) ?: throw BadRequest("setting is not declared: $key")
        // secret 只告诉页面有没有设置,不返回明文
        if (setting.type == "secret") return buildJsonObject { put("set", host.secretSet(key)) }
        return host.settingsGet(key)?.let(::JsonPrimitive) ?: JsonNull
    }

    private suspend fun settingsSet(params: JsonObject): JsonElement {
        if (!has("settings.plugin")) throw Denied("settings.plugin")
        val key = params["key"]?.jsonPrimitive?.contentOrNull
            ?: throw BadRequest("key is required")
        val setting = declaredSetting(key) ?: throw BadRequest("setting is not declared: $key")
        val value = params["value"]
        val text = when {
            value == null || value is JsonNull -> null
            value is JsonPrimitive -> value.content
            else -> throw BadRequest("value must be a string, number or boolean")
        }
        if (text != null && text.length > MAX_TEXT) throw BadRequest("value is too large")
        if (setting.type == "secret") {
            if (!host.secretWrite(key, text)) throw Internal("could not save the secret")
            return buildJsonObject { put("set", text != null) }
        }
        return host.settingsSet(key, text)?.let(::JsonPrimitive) ?: JsonNull
    }

    private fun toolsList(): JsonElement = JsonArray(manifest.tools.map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("risk", riskOf(tool))
        }
    })

    private suspend fun toolsCall(params: JsonObject): JsonElement {
        if (!has("sandbox.run")) throw Denied("sandbox.run")
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: throw BadRequest("name is required")
        manifest.tools.firstOrNull { it.name == name } ?: throw BadRequest("tool is not declared: $name")
        val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = host.callTool(name, args)
        return buildJsonObject { put("ok", !result.isError); put("text", result.text) }
    }

    private suspend fun workspaceRead(params: JsonObject): JsonElement {
        if (!has("workspace.own")) throw Denied("workspace.own")
        val path = workspacePath(params["path"]?.jsonPrimitive?.contentOrNull) ?: throw BadRequest("invalid path")
        if (path.isEmpty()) throw BadRequest("path is required")
        val text = host.workspaceRead(path) ?: return JsonNull
        if (text.length > MAX_TEXT) throw BadRequest("file is too large")
        return JsonPrimitive(text)
    }

    private suspend fun workspaceWrite(params: JsonObject): JsonElement {
        if (!has("workspace.own")) throw Denied("workspace.own")
        val path = workspacePath(params["path"]?.jsonPrimitive?.contentOrNull) ?: throw BadRequest("invalid path")
        if (path.isEmpty()) throw BadRequest("path is required")
        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: throw BadRequest("text is required")
        if (text.length > MAX_TEXT) throw BadRequest("text is too large")
        if (!host.workspaceWrite(path, text)) throw Internal("could not write the file")
        return JsonPrimitive(true)
    }

    private suspend fun workspaceList(params: JsonObject): JsonElement {
        if (!has("workspace.own")) throw Denied("workspace.own")
        val path = workspacePath(params["path"]?.jsonPrimitive?.contentOrNull) ?: throw BadRequest("invalid path")
        return JsonArray(host.workspaceList(path).map(::JsonPrimitive))
    }

    private suspend fun runScript(params: JsonObject): JsonElement {
        if (!has("sandbox.run")) throw Denied("sandbox.run")
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: throw BadRequest("name is required")
        if (!PluginManifest.SCRIPT.matches(name) || ".." in name) throw BadRequest("invalid script")
        val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())
        val result = host.runScript(name, args)
        return buildJsonObject {
            put("ok", result.exitCode == 0 && !result.timedOut)
            put("stdout", result.stdout)
            put("exitCode", result.exitCode)
            put("timedOut", result.timedOut)
        }
    }

    /** 页面工作区路径:相对路径、`/` 分隔、不许 `..`;空串表示根(只用于 list)。 */
    internal fun workspacePath(raw: String?): String? {
        if (raw == null || raw.isBlank()) return ""
        if (raw.startsWith("/") || raw.contains('\\') || '\u0000' in raw) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun riskOf(tool: PluginToolDecl): String {
        val risk = mcpApprovalRisk(
            McpAnnotations(
                readOnlyHint = tool.annotations["readOnlyHint"],
                destructiveHint = tool.annotations["destructiveHint"],
                openWorldHint = tool.annotations["openWorldHint"],
            ),
            McpTrust.Restricted,
        )
        return when (risk.level) {
            ActionRisk.Read -> "read"
            ActionRisk.Change -> "write"
            else -> "external"
        }
    }

    private fun ok(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("id", id); put("ok", true); put("result", result)
    }

    private fun error(id: JsonElement, code: String, message: String) = buildJsonObject {
        put("id", id); put("ok", false); put("error", code); put("message", message)
    }

    private class Denied(permission: String) : Exception("permission_denied: $permission")
    private class BadRequest(message: String) : Exception(message)
    private class Internal(message: String) : Exception(message)

    private companion object {
        const val MAX_TEXT = 64 * 1024
    }
}
