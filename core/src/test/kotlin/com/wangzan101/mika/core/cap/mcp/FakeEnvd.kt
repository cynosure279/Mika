package com.wangzan101.mika.core.cap.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

/**
 * 在 JVM 上模拟 tools/envd 的 MCP 行为：环境、文件、流式 exec。
 * 真实 envd 的协议测试在 tools/envd 的 Go 测试里；这里验证 Mika 客户端和工具分发器这一侧。
 */
class FakeEnvd : Dispatcher() {
    private val envs = mutableMapOf<String, MutableMap<String, String>>()
    private var nextEnv = 1
    val progressTokens = mutableListOf<String>()
    val methods = mutableListOf<String>()
    var execBodyDelayMs = 0L

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "DELETE") return MockResponse().setResponseCode(204)
        val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val id = message["id"] ?: return MockResponse().setResponseCode(202)
        val method = message.getValue("method").jsonPrimitive.content
        methods += method
        return when (method) {
            "initialize" -> rpc(id, buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put("serverInfo", buildJsonObject { put("name", "fake-envd") })
            })
            "tools/list" -> rpc(id, buildJsonObject { put("tools", toolList()) })
            "tools/call" -> toolCall(id, message["params"]?.jsonObject ?: JsonObject(emptyMap()))
            "ping" -> rpc(id, buildJsonObject {})
            else -> rpc(id, buildJsonObject {})
        }
    }

    private fun toolList(): JsonArray = buildJsonArray {
        add(tool("env_create", "Create an environment", props = mapOf("name" to stringProp())))
        add(tool("exec", "Run a command", destructive = true, props = mapOf("env" to stringProp(), "command" to stringProp())))
        add(tool("read_file", "Read a file", readOnly = true, props = mapOf("env" to stringProp(), "path" to stringProp())))
        add(tool("write_file", "Write a file", props = mapOf(
            "env" to stringProp(), "path" to stringProp(), "content" to stringProp(),
        )))
        add(tool("file_link", "Create a file link", props = mapOf(
            "env" to stringProp(), "path" to stringProp(), "direction" to stringProp(),
        )))
    }

    private fun stringProp(): JsonObject = buildJsonObject { put("type", "string") }

    private fun tool(
        name: String,
        description: String,
        readOnly: Boolean = false,
        destructive: Boolean = false,
        props: Map<String, JsonObject> = emptyMap(),
    ) = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { props.forEach { (key, value) -> put(key, value) } })
            put("additionalProperties", false)
        })
        put("annotations", buildJsonObject {
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
        })
    }

    private fun toolCall(id: JsonElement, params: JsonObject): MockResponse {
        val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val token = params["_meta"]?.jsonObject?.get("progressToken")?.jsonPrimitive?.contentOrNull
        if (token != null) progressTokens += token
        return when (name) {
            "env_create" -> {
                val env = "env-${nextEnv++}"
                envs[env] = mutableMapOf()
                rpc(id, toolResult(buildJsonObject {
                    put("ok", true)
                    put("environment", buildJsonObject { put("id", env) })
                }))
            }
            "write_file" -> {
                val env = args["env"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                envs.getOrPut(env) { mutableMapOf() }[path] = content
                rpc(id, toolResult(buildJsonObject {
                    put("ok", true)
                    put("path", path)
                    put("bytes", content.length)
                }))
            }
            "read_file" -> {
                val env = args["env"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val content = envs[env]?.get(path)
                if (content == null) rpc(id, toolError("file_not_found", "no such file: $path"))
                else rpc(id, toolResult(buildJsonObject {
                    put("ok", true)
                    put("path", path)
                    put("bytes", content.length)
                    put("content", content)
                }))
            }
            "exec" -> {
                val result = buildJsonObject {
                    put("ok", true)
                    put("exitCode", 0)
                    put("stdout", "chunk1\nchunk2\n")
                    put("stderr", "")
                    put("truncated", false)
                    put("timedOut", false)
                    put("durationMs", 1)
                }
                if (token == null) rpc(id, toolResult(result)) else streamed(id, token, result)
            }
            "file_link" -> rpc(id, toolResult(buildJsonObject {
                put("ok", true)
                put("url", "https://example.test/files/a.txt")
                put("expiresAt", "2026-11-04T00:00:00Z")
            }))
            else -> rpc(id, toolError("unknown_tool", "unknown tool: $name"))
        }
    }

    private fun rpc(id: JsonElement, result: JsonObject): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString())

    private fun toolResult(result: JsonObject): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", result.toString())
            })
        })
        put("isError", false)
    }

    private fun toolError(code: String, message: String): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", buildJsonObject { put("ok", false); put("error", code); put("message", message) }.toString())
            })
        })
        put("isError", true)
    }

    private fun streamed(id: JsonElement, token: String, result: JsonObject): MockResponse {
        val progress = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            put("params", buildJsonObject {
                put("progressToken", token)
                put("progress", 1)
                put("message", "chunk1")
            })
        }
        val final = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", toolResult(result))
        }
        val response = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: message\ndata: $progress\n\nevent: message\ndata: $final\n\n")
        if (execBodyDelayMs > 0) response.setBodyDelay(execBodyDelayMs, TimeUnit.MILLISECONDS)
        return response
    }
}
