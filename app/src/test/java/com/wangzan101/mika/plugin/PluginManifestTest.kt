package com.wangzan101.mika.plugin

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {
    private fun json(
        schema: String = "1",
        id: String = "\"com.example.feishu\"",
        name: String = "\"飞书通知\"",
        version: String = "\"1.0.0\"",
        extra: String = "",
    ) = """
        {"schema":$schema,"id":$id,"name":$name,"version":$version,
         "kind":"capability","skills":[{"name":"feishu","path":"skills/feishu","display":"飞书"}]$extra}
    """.trimIndent()

    private fun problems(text: String, app: String = "0.1.33") = PluginManifest.parse(text).validate(app)

    private val tool = """
        {"name":"send","description":"发消息","script":"scripts/send.py","timeoutSec":30,
         "annotations":{"readOnlyHint":false,"destructiveHint":false,"openWorldHint":true},
         "maxCostUnits":5,"inputSchema":{"type":"object","properties":{},"required":[]}}
    """.trimIndent()

    private fun toolManifest(tool: String = this.tool, permissions: String = "[\"sandbox.run\"]", extra: String = "") = """
        {"schema":1,"id":"com.example.feishu","name":"飞书通知","version":"1.0.0","kind":"capability",
         "permissions":$permissions,"tools":[$tool],"skills":[{"name":"feishu","path":"skills/feishu"}]$extra}
    """.trimIndent()

    @Test fun validSkillManifestPasses() {
        val m = PluginManifest.parse(json())
        assertEquals("com.example.feishu", m.id)
        assertEquals(listOf("feishu"), m.skills.map { it.name })
        assertTrue(m.validate("0.1.33").isEmpty())
    }

    @Test fun validToolManifestPasses() {
        val m = PluginManifest.parse(toolManifest())
        assertEquals(listOf("send"), m.tools.map { it.name })
        assertTrue(m.validate("0.1.33").isEmpty())
    }

    @Test fun requiredFieldsAreChecked() {
        val p = problems("{}")
        assertTrue(PluginManifest.ERR_SCHEMA in p)
        assertTrue(PluginManifest.ERR_ID in p)
        assertTrue(PluginManifest.ERR_NAME in p)
        assertTrue(PluginManifest.ERR_VERSION in p)
    }

    @Test fun schemaMustBeExactlyOne() {
        assertTrue(PluginManifest.ERR_SCHEMA in problems(json(schema = "2")))
        assertTrue(PluginManifest.ERR_SCHEMA in problems(json(schema = "0")))
    }

    @Test fun invalidIdRejected() {
        for (id in listOf("\"Feishu\"", "\"com/example\"", "\"../evil\"", "\"com..example\"")) {
            assertTrue(id, PluginManifest.ERR_ID in problems(json(id = id)))
        }
    }

    @Test fun invalidSemanticVersionRejected() {
        for (v in listOf("\"1.0\"", "\"v1.0.0\"", "\"1.0.0.0\"")) {
            assertTrue(v, PluginManifest.ERR_VERSION in problems(json(version = v)))
        }
    }

    @Test fun unknownPermissionsAreRejected() {
        assertTrue(PluginManifest.ERR_PERMISSIONS in problems(json(extra = ",\"permissions\":[\"phone.apps\"]")))
        assertTrue(PluginManifest.ERR_PERMISSIONS in problems(json(extra = ",\"permissions\":[\"future\"]")))
    }

    @Test fun pagesRequirePagePermission() {
        val pages = ",\"pages\":[{\"id\":\"home\",\"entry\":\"pages/index.html\",\"title\":\"飞书\"}]"
        assertTrue(PluginManifest.ERR_PAGES_NEED_PAGE in problems(json(extra = pages)))
        assertTrue(problems(json(extra = pages + ",\"permissions\":[\"page\"]")).isEmpty())
    }

    @Test fun pageFieldsAreValidated() {
        fun bad(entry: String, extra: String = "") = problems(
            json(extra = ",\"permissions\":[\"page\"],\"pages\":[{\"id\":\"home\",\"entry\":\"$entry\"}]$extra"),
        )
        assertTrue(PluginManifest.ERR_PAGE_PATH in bad("../index.html"))
        assertTrue(PluginManifest.ERR_PAGE_PATH in bad("/index.html"))
        assertTrue(PluginManifest.ERR_PAGE_PATH in bad("pages/index.js"))
        assertTrue(PluginManifest.ERR_PAGE in problems(json(extra = ",\"permissions\":[\"page\"],\"pages\":[{\"id\":\"Home\",\"entry\":\"pages/index.html\"}]")))
        assertTrue(PluginManifest.ERR_PAGE in problems(json(extra = ",\"permissions\":[\"page\"],\"pages\":[{\"id\":\"home\",\"entry\":\"pages/index.html\"},{\"id\":\"home\",\"entry\":\"pages/other.html\"}]")))
    }

    @Test fun settingsSupportSecretAndNeedPermission() {
        val webhook = ",\"settings\":[{\"key\":\"webhook\",\"type\":\"string\"}]"
        assertTrue(PluginManifest.ERR_SETTINGS_NEED_PERMISSION in problems(json(extra = webhook)))
        assertTrue(problems(json(extra = webhook + ",\"permissions\":[\"settings.plugin\"]")).isEmpty())
        assertTrue(problems(json(extra = ",\"settings\":[{\"key\":\"webhook\",\"type\":\"secret\"}],\"permissions\":[\"settings.plugin\"]")).isEmpty())
        assertTrue(PluginManifest.ERR_SETTING in problems(json(extra = ",\"settings\":[{\"key\":\"Webhook\",\"type\":\"string\"}],\"permissions\":[\"settings.plugin\"]")))
        assertTrue(PluginManifest.ERR_SETTING in problems(json(extra = ",\"settings\":[{\"key\":\"webhook\",\"type\":\"object\"}],\"permissions\":[\"settings.plugin\"]")))
    }

    @Test fun toolsRequireSandboxRunPermission() {
        assertTrue(PluginManifest.ERR_TOOLS_NEED_SANDBOX in problems(toolManifest(permissions = "[]")))
        assertTrue(PluginManifest.ERR_TOOLS_NEED_SANDBOX in problems(toolManifest(permissions = "[\"network\"]")))
    }

    @Test fun knownPermissionsAreAccepted() {
        assertTrue(problems(toolManifest(permissions = "[\"sandbox.run\",\"workspace.own\",\"network\"]")).isEmpty())
    }

    @Test fun toolFieldsAreValidated() {
        fun bad(t: String, permissions: String = "[\"sandbox.run\"]") = problems(toolManifest(tool = t, permissions = permissions))

        assertTrue(PluginManifest.ERR_TOOL in bad(tool.replace("\"name\":\"send\"", "\"name\":\"Send\"")))
        assertTrue(PluginManifest.ERR_TOOL in bad(tool.replace("\"name\":\"send\"", "\"name\":\"1send\"")))
        assertTrue(PluginManifest.ERR_TOOL_SCRIPT in bad(tool.replace("\"scripts/send.py\"", "\"/abs/send.py\"")))
        assertTrue(PluginManifest.ERR_TOOL_SCRIPT in bad(tool.replace("\"scripts/send.py\"", "\"scripts/../send.py\"")))
        assertTrue(PluginManifest.ERR_TOOL_SCRIPT in bad(tool.replace("\"scripts/send.py\"", "\"send.py\"")))
        assertTrue(PluginManifest.ERR_TOOL_TIMEOUT in bad(tool.replace("\"timeoutSec\":30", "\"timeoutSec\":0")))
        assertTrue(PluginManifest.ERR_TOOL_TIMEOUT in bad(tool.replace("\"timeoutSec\":30", "\"timeoutSec\":601")))
        assertTrue(PluginManifest.ERR_TOOL_SCHEMA in bad(tool.replace("\"type\":\"object\"", "\"type\":\"string\"")))
        assertTrue(PluginManifest.ERR_TOOL_COST in bad(tool.replace("\"maxCostUnits\":5", "\"maxCostUnits\":-1")))
    }

    @Test fun duplicateToolNamesRejected() {
        val two = "[$tool,$tool]"
        val text = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "permissions":["sandbox.run"],"tools":$two}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_TOOL in problems(text))
    }

    @Test fun minAppMustBeCompatibleWithThisApp() {
        assertTrue(PluginManifest.ERR_MIN_APP_TOO_HIGH in problems(json(extra = ",\"minApp\":\"9.0.0\""), app = "0.1.33"))
        assertTrue(problems(json(extra = ",\"minApp\":\"0.1.33\""), app = "0.1.33").isEmpty())
        assertTrue(PluginManifest.ERR_MIN_APP_TOO_HIGH in problems(json(extra = ",\"minApp\":\"0.1.34\""), app = "0.1.33"))
        assertTrue(PluginManifest.ERR_MIN_APP in problems(json(extra = ",\"minApp\":\"nope\""), app = "0.1.33"))
    }

    @Test fun prereleaseIsOlderThanRelease() {
        assertEquals(-1, SemVer.compare("0.1.33-rc1", "0.1.33"))
        assertEquals(1, SemVer.compare("0.1.33", "0.1.32"))
        assertEquals(0, SemVer.compare("0.1.33+build", "0.1.33"))
        assertTrue(SemVer.isValid("0.1.33-rc.1"))
        assertTrue(!SemVer.isValid("0.1.33."))
    }

    @Test fun unknownFieldsAreIgnored() {
        val m = PluginManifest.parse(json(extra = ",\"future\":{\"anything\":true}"))
        assertTrue(m.validate("0.1.33").isEmpty())
    }

    @Test fun malformedJsonThrows() {
        try {
            PluginManifest.parse("{not json")
            throw AssertionError("expected SerializationException")
        } catch (expected: SerializationException) {
        }
    }

    @Test fun skillPathMustStayInsideSkills() {
        val bad = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"../feishu"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL_PATH in problems(bad))
        val mismatch = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"skills/other"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL_PATH in problems(mismatch))
    }

    @Test fun duplicateSkillNamesRejected() {
        val dup = """
            {"schema":1,"id":"com.example.feishu","name":"x","version":"1.0.0",
             "skills":[{"name":"feishu","path":"skills/feishu"},{"name":"feishu","path":"skills/feishu"}]}
        """.trimIndent()
        assertTrue(PluginManifest.ERR_SKILL in problems(dup))
    }
}
