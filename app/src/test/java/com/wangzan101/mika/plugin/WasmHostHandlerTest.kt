package com.wangzan101.mika.plugin

import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WasmHostHandlerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private val declarations = mapOf("greeting" to "string", "token" to "secret")
    private val logs = mutableListOf<String>()

    private fun handler(cipher: KeyCipher? = null) = WasmHostHandler(
        settings = PluginSettings(tmp.root, cipher),
        declarations = declarations,
        log = { logs += it },
    )

    private fun failure(block: () -> Unit): String = try {
        block()
        fail("expected a failure")
        ""
    } catch (e: RuntimeException) {
        e.message.orEmpty()
    }

    @Test fun writesAndDeletesDeclaredSettings() {
        val h = handler()
        assertEquals("""{"ok":true}""", h.settingsSet("""{"key":"greeting","value":"hi"}"""))
        assertEquals("hi", PluginSettings(tmp.root).get("greeting"))
        h.settingsSet("""{"key":"greeting","value":null}""")
        assertNull(PluginSettings(tmp.root).get("greeting"))
        assertEquals(listOf("set greeting", "unset greeting"), logs)
    }

    @Test fun writesSecretsWithoutLoggingValues() {
        val h = handler(ReverseCipher())
        h.settingsSet("""{"key":"token","value":"s3cr3t"}""")
        assertEquals(mapOf("token" to "s3cr3t"), PluginSettings(tmp.root, ReverseCipher()).secretEnv())
        assertTrue(logs.none { it.contains("s3cr3t") })
        assertFalse(File(tmp.root, "secrets.bin").readText().contains("s3cr3t"))
    }

    @Test fun refusesUndeclaredKeysAndSecretsWithoutCipher() {
        assertTrue(failure { handler().settingsSet("""{"key":"nope","value":"x"}""") }.contains("not declared"))
        assertTrue(failure { handler().settingsSet("""{"key":"token","value":"x"}""") }.contains("could not save"))
        assertTrue(failure { handler().settingsSet("""{"value":"x"}""") }.contains("missing key"))
    }

    @Test fun missingCapabilitiesFailClearly() {
        val h = WasmHostHandler()
        assertTrue(failure { h.settingsSet("""{"key":"greeting","value":"x"}""") }.contains("settings"))
        assertTrue(failure { h.http("{}") }.contains("network"))
    }
}
