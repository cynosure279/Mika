package com.wangzan101.mika.cap

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.cap.mcp.HttpMcpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpProgress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备/模拟器上的 MCP 流式端到端：真实的 Android OkHttp 连接本机 MockWebServer，
 * 走 initialize / tools/list / 带 progress 的 tools/call。没有设备时由 `compileDebugAndroidTestKotlin` 编译验证。
 */
@RunWith(AndroidJUnit4::class)
class RemoteEnvironmentDeviceTest {
    @Test fun streamingMcpCallWorksOnDevice() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE") return MockResponse().setResponseCode(204)
                val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val id = message["id"] ?: return MockResponse().setResponseCode(202)
                val method = message.getValue("method").jsonPrimitive.content
                if (method == "tools/call") {
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"p\",\"progress\":1,\"message\":\"chunk1\"}}\n\n" +
                            "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}\n\n",
                    )
                }
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-03-26","serverInfo":{"name":"device-test"}}"""
                    "tools/list" -> """{"tools":[{"name":"exec","description":"run","inputSchema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]},"annotations":{"readOnlyHint":false,"destructiveHint":true}}]}"""
                    else -> "{}"
                }
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
        server.start()
        try {
            val endpoint = HttpMcpEndpoint.connect(
                "device", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 10,
            ).getOrThrow()
            try {
                val progress = mutableListOf<McpProgress>()
                val result = endpoint.call("exec", buildJsonObject { put("command", "echo hi") }, onProgress = { progress += it })
                assertEquals("done", result.text)
                assertEquals(listOf("chunk1"), progress.map { it.message })
            } finally {
                endpoint.close()
            }
        } finally {
            server.shutdown()
        }
    }
}
