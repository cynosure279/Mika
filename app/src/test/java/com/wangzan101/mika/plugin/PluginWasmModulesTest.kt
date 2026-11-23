package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginWasmModulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun packageWith(vararg modules: Pair<String, ByteArray>): File = tmp.newFolder("pkg").also { dir ->
        File(dir, "tools").mkdirs()
        modules.forEach { (name, bytes) -> File(dir, "tools/$name").writeBytes(bytes) }
        PluginWasmModules.writeHashes(dir, PluginWasmModules.collect(dir))
    }

    @Test fun hashesAreRecordedAndVerified() {
        val dir = packageWith("a.wasm" to byteArrayOf(1, 2), "b.wasm" to byteArrayOf(3))
        val hashes = PluginWasmModules.hashes(dir)
        assertEquals(setOf("tools/a.wasm", "tools/b.wasm"), hashes.keys)
        assertTrue(PluginWasmModules.verified(dir, "tools/a.wasm")!!.contentEquals(byteArrayOf(1, 2)))
        assertNull(PluginWasmModules.verified(dir, "tools/missing.wasm"))
    }

    @Test fun tamperedModuleIsRejected() {
        val dir = packageWith("a.wasm" to byteArrayOf(1))
        File(dir, "tools/a.wasm").writeBytes(byteArrayOf(9))
        assertNull(PluginWasmModules.verified(dir, "tools/a.wasm"))
    }

    @Test fun pathsOutsideToolsAreIgnored() {
        val dir = tmp.newFolder("pkg2")
        File(dir, "scripts").mkdirs()
        File(dir, "scripts/x.wasm").writeBytes(byteArrayOf(1))
        assertEquals(emptyMap<String, String>(), PluginWasmModules.collect(dir))
    }
}
