package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Mika 客户端 + 工具分发器对着模拟 envd 跑；真实 envd 的协议测试在 tools/envd 的 Go 测试里。 */
class RemoteEnvironmentSimulationTest {
    private lateinit var server: MockWebServer
    private lateinit var fake: FakeEnvd

    @Before fun setUp() {
        fake = FakeEnvd()
        server = MockWebServer().also {
            it.dispatcher = fake
            it.start()
        }
    }

    @After fun tearDown() { server.shutdown() }

    private suspend fun connect() = HttpMcpEndpoint.connect("envd", server.url("/mcp").toString(), OkHttpClient(), timeoutSec = 5).getOrThrow()

    @Test fun fileLinkIsHandedToTheAppDownloader() = runBlocking {
        val endpoint = connect()
        try {
            var seen: String? = null
            val tools = toolDispatcher(
                FakeScreen(),
                mcpServers = listOf(McpConnection(endpoint)),
                onRemoteFileLink = { url, _, _ -> seen = url; "saved to Downloads" },
            )
            val result = tools.execute(
                Part.ToolCall("c1", "mcp_envd__file_link", """{"env":"env-1","path":"a.txt"}"""),
                ActionPass(),
            )
            assertEquals("https://example.test/files/a.txt", seen)
            assertTrue(result.content["text"].toString().contains("saved to Downloads"))
        } finally {
            endpoint.close()
        }
    }

    @Test fun streamingExecReachesTaskProgressAndResult() = runBlocking {
        val endpoint = connect()
        try {
            val progress = mutableListOf<String>()
            val tools = toolDispatcher(
                FakeScreen(),
                mcpServers = listOf(McpConnection(endpoint)),
                onToolProgress = { progress += it },
            )
            val create = tools.execute(
                Part.ToolCall("c1", "mcp_envd__env_create", """{"name":"box"}"""),
                ActionPass(),
            )
            assertEquals(create.content.toString(), "true", create.content["ok"].toString())

            val exec = tools.execute(
                Part.ToolCall("c2", "mcp_envd__exec", """{"env":"env-1","command":"run"}"""),
                ActionPass(),
            )
            assertEquals("true", exec.content["ok"].toString())
            assertTrue(exec.content["text"].toString().contains("chunk2"))
            assertEquals(listOf("chunk1"), progress)
            assertTrue(fake.progressTokens.isNotEmpty())
            assertTrue(fake.progressTokens.all { it.startsWith("mika-") })
        } finally {
            endpoint.close()
        }
    }

    @Test fun fileToolsRoundTripThroughTheSameConnection() = runBlocking {
        val endpoint = connect()
        try {
            val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            tools.execute(Part.ToolCall("c1", "mcp_envd__env_create", """{"name":"box"}"""), ActionPass())
            val write = tools.execute(
                Part.ToolCall("c2", "mcp_envd__write_file", """{"env":"env-1","path":"a.txt","content":"hello"}"""),
                ActionPass(),
            )
            assertEquals(write.content.toString(), "true", write.content["ok"].toString())
            val read = tools.execute(
                Part.ToolCall("c3", "mcp_envd__read_file", """{"env":"env-1","path":"a.txt"}"""),
                ActionPass(),
            )
            assertEquals("true", read.content["ok"].toString())
            assertTrue(read.content["text"].toString().contains("hello"))
        } finally {
            endpoint.close()
        }
    }

    @Test fun cancellingTheToolCallStopsTheStream() = runBlocking {
        val endpoint = connect()
        try {
            val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
            repeat(3) { server.takeRequest() } // initialize / initialized / tools/list
            tools.execute(Part.ToolCall("c1", "mcp_envd__env_create", """{"name":"box"}"""), ActionPass())
            server.takeRequest() // env_create

            fake.execBodyDelayMs = 5_000
            val job = launch(Dispatchers.Default) {
                tools.execute(Part.ToolCall("c2", "mcp_envd__exec", """{"env":"env-1","command":"run"}"""), ActionPass())
            }
            server.takeRequest(2, TimeUnit.SECONDS)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            endpoint.close()
        }
    }
}
