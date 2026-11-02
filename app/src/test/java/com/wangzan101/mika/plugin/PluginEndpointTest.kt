package com.wangzan101.mika.plugin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginEndpointTest {
    private val record = PluginRecord("com.example.feishu", "飞书", "1.0.0")

    private fun manifest(
        permissions: String = """["sandbox.run","network"]""",
        annotations: String = """{"readOnlyHint":false,"destructiveHint":false,"openWorldHint":true}""",
    ) = PluginManifest.parse(
        """
        {"schema":1,"id":"com.example.feishu","name":"飞书","version":"1.0.0",
         "permissions":$permissions,
         "tools":[{"name":"send","description":"发消息","script":"scripts/send.py","timeoutSec":30,
                   "maxCostUnits":5,"annotations":$annotations,
                   "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}],
         "skills":[]}
        """.trimIndent(),
    )

    private class FakeRunner(
        private val result: ScriptResult = ScriptResult("ok", exitCode = 0),
        private val error: Exception? = null,
    ) : PluginScriptRunner {
        val calls = mutableListOf<Triple<String, Boolean, JsonObject>>()
        override suspend fun run(pluginId: String, script: String, args: JsonObject, timeoutSec: Int, network: Boolean): ScriptResult {
            calls += Triple(script, network, args)
            error?.let { throw it }
            return result
        }
    }

    @Test fun manifestBecomesMcpDescriptor() {
        val endpoint = PluginEndpoint(record, manifest(), FakeRunner())
        val tool = endpoint.tools().single()
        assertEquals("send", tool.name)
        assertEquals("发消息", tool.description)
        assertEquals(5L, tool.estimatedCostUnits)
        assertEquals(false, tool.annotations.readOnlyHint)
        assertEquals(true, tool.annotations.openWorldHint)
        assertEquals("object", tool.inputSchema["type"]?.toString()?.trim('"'))
    }

    @Test fun callPassesScriptAndNetworkOnlyWhenGranted() = runTest {
        val runner = FakeRunner()
        val args = buildJsonObject { put("text", "hi") }
        PluginEndpoint(record, manifest(), runner, networkAllowed = { true }).call("send", args)
        assertEquals("scripts/send.py", runner.calls.single().first)
        assertTrue(runner.calls.single().second)
        assertEquals(args, runner.calls.single().third)

        val noPermission = FakeRunner()
        PluginEndpoint(record, manifest(permissions = """["sandbox.run"]"""), noPermission, networkAllowed = { true })
            .call("send", args)
        assertFalse(noPermission.calls.single().second)
    }

    @Test fun missingSandboxPermissionRefusesWithoutRunning() = runTest {
        val runner = FakeRunner()
        val result = PluginEndpoint(record, manifest(permissions = "[]"), runner).call("send", buildJsonObject {})
        assertTrue(result.isError)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun unknownToolAndRunnerErrorsBecomeErrorResults() = runTest {
        val endpoint = PluginEndpoint(record, manifest(), FakeRunner())
        assertTrue(endpoint.call("missing", buildJsonObject {}).isError)

        val failed = PluginEndpoint(record, manifest(), FakeRunner(ScriptResult("bad", exitCode = 2)))
        assertTrue(failed.call("send", buildJsonObject {}).isError)

        val threw = PluginEndpoint(record, manifest(), FakeRunner(error = IllegalStateException("boom")))
        val result = threw.call("send", buildJsonObject {})
        assertTrue(result.isError)
        assertTrue(result.text.contains("boom"))
    }

    @Test fun lastJsonLineIsTheResultOtherwiseWholeOutput() = runTest {
        val json = PluginEndpoint(record, manifest(), FakeRunner(ScriptResult("log line\n{\"ok\":true}", exitCode = 0)))
        assertEquals("{\"ok\":true}", json.call("send", buildJsonObject {}).text)
        val plain = PluginEndpoint(record, manifest(), FakeRunner(ScriptResult("line one\nline two", exitCode = 0)))
        assertEquals("line one\nline two", plain.call("send", buildJsonObject {}).text)
    }

    @Test fun emptySchemaGetsAnObjectSchema() {
        val m = PluginManifest.parse(
            """{"schema":1,"id":"com.example.feishu","name":"f","version":"1.0.0",
                "permissions":["sandbox.run"],"tools":[{"name":"ping","script":"scripts/ping.py"}]}""",
        )
        val schema = PluginEndpoint(record, m, FakeRunner()).tools().single().inputSchema
        assertEquals("object", schema["type"]?.toString()?.trim('"'))
    }
}
