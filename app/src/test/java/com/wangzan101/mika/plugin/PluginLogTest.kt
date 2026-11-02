package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun appendsAndClearsWithoutGrowingForever() {
        val log = PluginLog(tmp.root)
        log.append("tool", "send exit=0 ms=12")
        assertTrue(log.read().contains("send exit=0"))
        log.clear()
        assertEquals("", log.read())
    }

    @Test fun neverStoresArgumentsOrSecretsByItself() {
        val log = PluginLog(tmp.root)
        log.append("tool", "send exit=0 timedOut=false ms=3")
        val text = log.read()
        assertTrue(!text.contains("webhook"))
        assertTrue(!text.contains("secret"))
    }
}
