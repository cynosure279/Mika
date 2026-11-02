package com.wangzan101.mika.plugin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBridgeTest {
    private val manifest = PluginManifest.parse(
        """
        {"schema":1,"id":"com.example.feishu","name":"飞书","version":"1.0.0",
         "permissions":["sandbox.run","settings.plugin","workspace.own","page"],
         "tools":[{"name":"send","description":"发消息","script":"scripts/send.py",
                   "annotations":{"openWorldHint":true}}],
         "settings":[{"key":"webhook","type":"string"}],
         "pages":[{"id":"home","entry":"pages/index.html"}]}
        """.trimIndent(),
    )

    private class FakeHost : PluginBridgeHost {
        val settings = mutableMapOf<String, String>()
        val secrets = mutableMapOf<String, String>()
        val calls = mutableListOf<Pair<String, String>>()
        var closed = false
        override suspend fun settingsGet(key: String) = settings[key]
        override suspend fun settingsSet(key: String, value: String?): String? {
            if (value == null) settings.remove(key) else settings[key] = value
            return value
        }
        override suspend fun secretSet(key: String) = secrets[key] != null
        override suspend fun secretWrite(key: String, value: String?): Boolean {
            if (value == null) secrets.remove(key) else secrets[key] = value
            return true
        }
        override suspend fun callTool(name: String, args: JsonObject) = PluginToolResult("called $name", isError = false)
        override suspend fun workspaceRead(path: String): String? { calls += "read" to path; return "text:$path" }
        override suspend fun workspaceWrite(path: String, text: String): Boolean { calls += "write" to path; return true }
        override suspend fun workspaceList(path: String): List<String> { calls += "list" to path; return listOf("a", "b") }
        override suspend fun runScript(name: String, args: JsonObject) = ScriptResult("ran $name", exitCode = 0)
        override fun close() { closed = true }
    }

    private fun request(id: String = "1", method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("id", id); put("method", method); put("params", params)
    }

    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.content == "true"
    private fun JsonObject.result() = this["result"]!!
    private fun JsonObject.errorCode() = this["error"]!!.jsonPrimitive.content

    @Test fun settingsAreReadAndWrittenByDeclaredKey() = runTest {
        val host = FakeHost()
        val bridge = PluginBridge("com.example.feishu", manifest, host)
        val set = bridge.handle(request(method = "settings.set", params = buildJsonObject {
            put("key", "webhook"); put("value", "https://example.test")
        }))
        assertTrue(set.ok())
        assertEquals("https://example.test", host.settings["webhook"])
        val get = bridge.handle(request(method = "settings.get", params = buildJsonObject { put("key", "webhook") }))
        assertEquals("https://example.test", get.result().jsonPrimitive.content)
        val bad = bridge.handle(request(method = "settings.get", params = buildJsonObject { put("key", "other") }))
        assertEquals("bad_request", bad.errorCode())
    }

    @Test fun toolListAndCallUseTheManifest() = runTest {
        val bridge = PluginBridge("com.example.feishu", manifest, FakeHost())
        val list = bridge.handle(request(method = "tools.list"))
        val tool = list.result().let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject
        assertEquals("send", tool["name"]!!.jsonPrimitive.content)
        assertEquals("external", tool["risk"]!!.jsonPrimitive.content)

        val call = bridge.handle(request(method = "tools.call", params = buildJsonObject {
            put("name", "send"); put("args", buildJsonObject { put("text", "hi") })
        }))
        assertTrue(call.ok())
        assertEquals("called send", call.result().jsonObject["text"]!!.jsonPrimitive.content)
        val unknown = bridge.handle(request(method = "tools.call", params = buildJsonObject { put("name", "nope") }))
        assertEquals("bad_request", unknown.errorCode())
    }

    @Test fun workspacePathsStayInsideThePluginWorkspace() = runTest {
        val host = FakeHost()
        val bridge = PluginBridge("com.example.feishu", manifest, host)
        val read = bridge.handle(request(method = "workspace.read", params = buildJsonObject { put("path", "notes/a.txt") }))
        assertEquals("text:notes/a.txt", read.result().jsonPrimitive.content)
        val escape = bridge.handle(request(method = "workspace.read", params = buildJsonObject { put("path", "../other") }))
        assertEquals("bad_request", escape.errorCode())
        val absolute = bridge.handle(request(method = "workspace.read", params = buildJsonObject { put("path", "/etc/passwd") }))
        assertEquals("bad_request", absolute.errorCode())
        val list = bridge.handle(request(method = "workspace.list"))
        assertEquals(2, (list.result() as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun permissionsAreCheckedPerMethod() = runTest {
        val noPerm = PluginManifest.parse(
            """{"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0"}""",
        )
        val bridge = PluginBridge("com.example.feishu", noPerm, FakeHost())
        for ((method, params) in listOf(
            "settings.get" to buildJsonObject { put("key", "webhook") },
            "tools.call" to buildJsonObject { put("name", "send") },
            "workspace.read" to buildJsonObject { put("path", "a.txt") },
            "runScript" to buildJsonObject { put("name", "scripts/send.py") },
        )) {
            assertEquals(method, "permission_denied", bridge.handle(request(method = method, params = params)).errorCode())
        }
    }

    @Test fun runScriptAndClose() = runTest {
        val host = FakeHost()
        val bridge = PluginBridge("com.example.feishu", manifest, host)
        val result = bridge.handle(request(method = "runScript", params = buildJsonObject { put("name", "scripts/send.py") }))
        assertTrue(result.ok())
        assertEquals("ran scripts/send.py", result.result().jsonObject["stdout"]!!.jsonPrimitive.content)
        val bad = bridge.handle(request(method = "runScript", params = buildJsonObject { put("name", "../send.py") }))
        assertEquals("bad_request", bad.errorCode())
        assertTrue(bridge.handle(request(method = "close")).ok())
        assertTrue(host.closed)
    }

    @Test fun secretsOnlyExposeWhetherTheyAreSet() = runTest {
        val withSecret = PluginManifest.parse(
            """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "permissions":["settings.plugin"],
             "settings":[{"key":"secret","type":"secret"}]}
            """.trimIndent(),
        )
        val host = FakeHost()
        val bridge = PluginBridge("com.example.feishu", withSecret, host)
        val set = bridge.handle(request(method = "settings.set", params = buildJsonObject {
            put("key", "secret"); put("value", "s3cr3t")
        }))
        assertTrue(set.ok())
        val get = bridge.handle(request(method = "settings.get", params = buildJsonObject { put("key", "secret") }))
        assertEquals("true", get.result().jsonObject["set"]!!.jsonPrimitive.content)
        assertFalse(get.result().toString().contains("s3cr3t"))
    }

    @Test fun unknownMethodIsRejected() = runTest {
        val bridge = PluginBridge("com.example.feishu", manifest, FakeHost())
        val result = bridge.handle(request(method = "system.exit"))
        assertEquals("unknown_method", result.errorCode())
        assertFalse(result.ok())
    }
}
