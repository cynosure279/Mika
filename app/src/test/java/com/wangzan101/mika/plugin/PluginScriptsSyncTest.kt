package com.wangzan101.mika.plugin

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.workspace.SandboxTree
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginScriptsSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirs(): SandboxDirs = SandboxDirs(
        File(tmp.root, "sandbox").apply { mkdirs() },
        File(tmp.root, "tmp"),
        File(tmp.root, "native"),
        JVM_GATE,
        SandboxTree(),
    )

    private fun packageDir(id: String = "com.example.a", script: String = "print(1)"): File {
        val dir = File(tmp.root, "plugins/$id")
        File(dir, "scripts/lib").mkdirs()
        File(dir, "scripts/send.py").writeText(script)
        File(dir, "scripts/lib/util.py").writeText("x = 1")
        PluginScripts.writeHashes(dir, PluginScripts.collect(dir))
        return dir
    }

    @Test fun syncsScriptsIntoTheSandboxCopy() = runTest {
        val d = dirs()
        val pkg = packageDir()
        PluginScriptsSync.ensure(d, "com.example.a", pkg)
        val target = d.pluginScripts("com.example.a")
        assertEquals("print(1)", File(target, "scripts/send.py").readText())
        assertEquals("x = 1", File(target, "scripts/lib/util.py").readText())
        assertTrue(PluginScriptsSync.same(File(pkg, "scripts"), File(target, "scripts")))
        assertFalse(File(d.plugins, "${target.name}.new").exists())
    }

    @Test fun changedPackageScriptIsRejected() = runTest {
        val d = dirs()
        val pkg = packageDir()
        File(pkg, "scripts/send.py").writeText("print(2)")
        val error = runCatching { PluginScriptsSync.ensure(d, "com.example.a", pkg) }.exceptionOrNull()
        assertTrue(error is PluginScriptException)
        assertEquals(PluginScriptErrors.CHANGED, (error as PluginScriptException).code)
        assertFalse(d.pluginScripts("com.example.a").exists())
    }

    @Test fun replacingAPreviousCopyIsAtomic() = runTest {
        val d = dirs()
        val pkg = packageDir()
        PluginScriptsSync.ensure(d, "com.example.a", pkg)
        File(pkg, "scripts/send.py").writeText("print(3)")
        PluginScripts.writeHashes(pkg, PluginScripts.collect(pkg))
        PluginScriptsSync.ensure(d, "com.example.a", pkg)
        assertEquals("print(3)", File(d.pluginScripts("com.example.a"), "scripts/send.py").readText())
    }
}
