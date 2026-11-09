package com.wangzan101.mika.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机/模拟器上验证独立进程引擎:回显、超时 trap、进程存活。 */
@RunWith(AndroidJUnit4::class)
class WasmProcessEngineTest {
    @Test
    fun echoAndTrapThroughServiceProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val guest = InstrumentationRegistry.getInstrumentation().context.assets
            .open("echo.wasm").use { it.readBytes() }
        val engine = WasmProcessEngine(context)
        val module = engine.load(guest)

        val ok = engine.call(module, "{}", """{"a":1}""", 5_000)
        assertFalse(ok.output, ok.isError)
        assertTrue(ok.output.contains("\"ok\":true"))

        val trap = engine.call(module, "{}", """{"loop":true}""", 1_000)
        assertTrue(trap.isError)

        // 崩溃隔离后宿主仍能继续工作
        val again = engine.call(module, "{}", """{"b":2}""", 5_000)

        // ABI v2 只读能力:settings_get 与 log
        val callContext = """{"permissions":["settings.plugin"],"settings":{"greeting":"hi"},"secrets":{},"workspace":""}"""
        val settings = engine.call(module, callContext, """{"mode":"settings"}""", 5_000)
        assertFalse(settings.output, settings.isError)
        assertTrue(settings.output.contains("hi"))
        val logged = engine.call(module, callContext, """{"mode":"log"}""", 5_000)
        assertFalse(logged.output, logged.isError)
        assertTrue(logged.logs.isNotEmpty())

        // ABI v2 写入:fs_write 只写插件 workspace
        val workspace = java.io.File(context.cacheDir, "wasm-test-workspace").apply { mkdirs() }
        val writeContext = """{"permissions":["workspace.own"],"settings":{},"secrets":{},"workspace":"${workspace.absolutePath}"}"""
        val written = engine.call(module, writeContext, """{"mode":"write"}""", 5_000)
        assertFalse(written.output, written.isError)
        assertTrue(written.output.contains("\"written\":15"))
        val file = java.io.File(workspace, "out.txt")
        assertTrue(file.isFile)
        assertTrue(file.readText() == "hello from wasm")
        workspace.deleteRecursively()
        assertFalse(again.output, again.isError)

        // 网络能力:有权限 + 宿主回调 → guest 拿到响应 JSON
        val httpContext = """{"permissions":["network"],"settings":{},"secrets":{},"workspace":""}"""
        val requests = mutableListOf<String>()
        val host = object : IWasmHost.Stub() {
            override fun http(requestJson: String): String {
                requests += requestJson
                return """{"status":200,"headers":{"X-Test":"yes"},"body":"pong"}"""
            }

            override fun settingsSet(requestJson: String): String = throw IllegalStateException("not available")
        }
        val online = engine.call(module, httpContext, """{"mode":"http"}""", 5_000, host)
        assertFalse(online.output, online.isError)
        assertTrue(online.output.contains("\"status\":200"))
        assertTrue(online.output.contains("pong"))
        assertTrue(requests.single(), requests.single().contains("example.com"))

        // 没有 network 权限:宿主给了回调也拒绝
        val denied = engine.call(module, """{"permissions":[],"settings":{},"secrets":{},"workspace":""}""", """{"mode":"http"}""", 5_000, host)
        assertTrue(denied.output, denied.output.contains("\"http\":-2"))

        // 联网开关关着:宿主不传回调(模拟未授权)
        val offline = engine.call(module, httpContext, """{"mode":"http"}""", 5_000, null)
        assertTrue(offline.output, offline.output.contains("\"http\":-2"))

        // 真实宿主实现 + 本地服务:走完整链路(:wasm → 回调 App 进程 → OkHttp → 回写)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("real-pong"))
            val real = WasmHost(WasmHostHandler(http = PluginHttp(OkHttpClient(), isLocal = { false })))
            val url = server.url("/ping").toString()
            val response = engine.call(module, httpContext, """{"mode":"http","url":"$url"}""", 10_000, real)
            assertFalse(response.output, response.isError)
            assertTrue(response.output, response.output.contains("real-pong"))
            val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(recorded != null && recorded.path == "/ping")
        } finally {
            server.shutdown()
        }

        // 设置写入:只认 manifest 声明;值经宿主回调落盘(secret 同样走宿主)
        val settingsDir = java.io.File(context.cacheDir, "wasm-test-settings").apply { mkdirs() }
        val settingsHost = WasmHost(
            WasmHostHandler(
                settings = PluginSettings(settingsDir),
                declarations = mapOf("greeting" to "string", "flag" to "bool"),
            ),
        )
        val settingsContext =
            """{"permissions":["settings.plugin"],"settings":{},"secrets":{},"settingTypes":{"greeting":"string","flag":"bool"},"workspace":""}"""
        val wrote = engine.call(module, settingsContext, """{"mode":"settings_write","key":"greeting","value":"wasm-set"}""", 5_000, settingsHost)
        assertFalse(wrote.output, wrote.isError)
        assertTrue(wrote.output, wrote.output.contains("\"settings_set\":0"))
        assertTrue(PluginSettings(settingsDir).get("greeting") == "wasm-set")

        // 未声明的键 → -3;bool 值不合法 → -1
        val undeclared = engine.call(module, settingsContext, """{"mode":"settings_write","key":"nope","value":"x"}""", 5_000, settingsHost)
        assertTrue(undeclared.output, undeclared.output.contains("\"settings_set\":-3"))
        val badBool = engine.call(module, settingsContext, """{"mode":"settings_write","key":"flag","value":"maybe"}""", 5_000, settingsHost)
        assertTrue(badBool.output, badBool.output.contains("\"settings_set\":-1"))

        // 没有 settings.plugin 权限、或宿主没给回调 → -2
        val deniedSettings = engine.call(
            module,
            """{"permissions":[],"settings":{},"secrets":{},"workspace":""}""",
            """{"mode":"settings_write","key":"greeting","value":"x"}""",
            5_000,
            settingsHost,
        )
        assertTrue(deniedSettings.output, deniedSettings.output.contains("\"settings_set\":-2"))
        val noHost = engine.call(module, settingsContext, """{"mode":"settings_write","key":"greeting","value":"x"}""", 5_000, null)
        assertTrue(noHost.output, noHost.output.contains("\"settings_set\":-2"))

        // value_len 为 0 表示删除
        val deleted = engine.call(module, settingsContext, """{"mode":"settings_write","key":"greeting","delete":true}""", 5_000, settingsHost)
        assertTrue(deleted.output, deleted.output.contains("\"settings_set\":0"))
        assertTrue(PluginSettings(settingsDir).get("greeting") == null)
        settingsDir.deleteRecursively()
        Unit
    }
}
