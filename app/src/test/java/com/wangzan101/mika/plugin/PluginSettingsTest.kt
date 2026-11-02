package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

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
}
