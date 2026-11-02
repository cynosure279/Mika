package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginScriptsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun packageDir(scripts: Map<String, String>): File {
        val dir = tmp.newFolder("plugin")
        for ((rel, text) in scripts) {
            File(dir, rel).apply { parentFile.mkdirs() }.writeText(text)
        }
        return dir
    }

    @Test fun collectRecordsEveryScriptAndVerifiedOnlyAcceptsMatchingBytes() {
        val dir = packageDir(mapOf("scripts/send.py" to "print(1)", "scripts/lib/util.py" to "x = 1"))
        val hashes = PluginScripts.collect(dir)
        assertEquals(setOf("scripts/send.py", "scripts/lib/util.py"), hashes.keys)
        PluginScripts.writeHashes(dir, hashes)
        assertEquals("print(1)", PluginScripts.verified(dir, "scripts/send.py")?.toString(Charsets.UTF_8))
        assertNull(PluginScripts.verified(dir, "scripts/missing.py"))
        assertNull(PluginScripts.verified(dir, "../send.py"))
    }

    @Test fun changedScriptIsRejected() {
        val dir = packageDir(mapOf("scripts/send.py" to "print(1)"))
        PluginScripts.writeHashes(dir, PluginScripts.collect(dir))
        File(dir, "scripts/send.py").writeText("print(2)")
        assertNull(PluginScripts.verified(dir, "scripts/send.py"))
    }

    @Test fun copyTreeKeepsNestedPathsAndSameComparesContent() {
        val source = File(tmp.root, "source/scripts").apply { mkdirs() }
        File(source, "lib").mkdirs()
        File(source, "send.py").writeText("print(1)")
        File(source, "lib/util.py").writeText("x = 1")
        val target = File(tmp.root, "target/scripts")
        assertEquals(2, PluginScriptsSync.copyTree(source, target))
        assertEquals("print(1)", File(target, "send.py").readText())
        assertEquals("x = 1", File(target, "lib/util.py").readText())
        assertTrue(PluginScriptsSync.same(source, target))
        File(target, "send.py").writeText("print(2)")
        assertFalse(PluginScriptsSync.same(source, target))
    }

    @Test fun emptyScriptsDirectoryIsIgnored() {
        val dir = packageDir(emptyMap())
        assertTrue(PluginScripts.collect(dir).isEmpty())
        assertTrue(PluginScripts.hashes(dir).isEmpty())
    }
}
