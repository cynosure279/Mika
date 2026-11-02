package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.settings.KeyCipher
import java.io.File

class PluginSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    @Test fun writesAndReadsNonSecretValuesAtomically() {
        val settings = PluginSettings(tmp.root)
        settings.set("webhook", "https://example.test")
        settings.set("default_chat", "team")
        settings.set("default_chat", null)
        assertEquals("https://example.test", PluginSettings(tmp.root).get("webhook"))
        assertNull(PluginSettings(tmp.root).get("default_chat"))
        assertEquals(mapOf("webhook" to "https://example.test"), settings.all())
    }

    @Test fun brokenFileReadsAsEmpty() {
        val file = java.io.File(tmp.root, "settings.json")
        file.writeText("{not json")
        assertEquals(emptyMap<String, String>(), PluginSettings(tmp.root).all())
    }

    @Test fun secretsAreEncryptedAndOnlyExposedToScripts() {
        val settings = PluginSettings(tmp.root, ReverseCipher())
        assertTrue(settings.secretWrite("secret", "s3cr3t"))
        assertTrue(PluginSettings(tmp.root, ReverseCipher()).secretSet("secret"))
        assertEquals(mapOf("secret" to "s3cr3t"), PluginSettings(tmp.root, ReverseCipher()).secretEnv())
        assertFalse(File(tmp.root, "secrets.bin").readText().contains("s3cr3t"))
        assertTrue(settings.secretWrite("secret", null))
        assertFalse(PluginSettings(tmp.root, ReverseCipher()).secretSet("secret"))
    }

    @Test fun secretsAreUnavailableWithoutCipher() {
        val settings = PluginSettings(tmp.root)
        assertFalse(settings.secretWrite("secret", "x"))
        assertFalse(settings.secretSet("secret"))
    }
}
