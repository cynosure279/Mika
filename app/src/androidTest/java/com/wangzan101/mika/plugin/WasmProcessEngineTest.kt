package com.wangzan101.mika.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机/模拟器上验证独立进程引擎:回显、超时 trap、进程存活。 */
@RunWith(AndroidJUnit4::class)
class WasmProcessEngineTest {
    @Test
    fun echoAndTrapThroughServiceProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val guest = InstrumentationRegistry.getInstrumentation().context.assets
            .open("echo.wasm").use { it.readBytes() }
        val engine = WasmProcessEngine(context)
        val module = engine.load(guest)

        val ok = engine.call(module, "{}", """{"a":1}""", 5_000)
        assertFalse(ok.output, ok.isError)
        assertTrue(ok.output.contains("\"ok\":true"))

        val trap = engine.call(module, "{}", """{"loop":true}""", 1_000)
        assertTrue(trap.isError)

        // 崩溃隔离后宿主仍能继续工作
        val again = engine.call(module, "{}", """{"b":2}""", 5_000)

        // ABI v2 只读能力:settings_get 与 log
        val callContext = """{"permissions":["settings.plugin"],"settings":{"greeting":"hi"},"secrets":{},"workspace":""}"""
        val settings = engine.call(module, callContext, """{"mode":"settings"}""", 5_000)
        assertFalse(settings.output, settings.isError)
        assertTrue(settings.output.contains("hi"))
        val logged = engine.call(module, callContext, """{"mode":"log"}""", 5_000)
        assertFalse(logged.output, logged.isError)
        assertTrue(logged.logs.isNotEmpty())

        // ABI v2 写入:fs_write 只写插件 workspace
        val workspace = java.io.File(context.cacheDir, "wasm-test-workspace").apply { mkdirs() }
        val writeContext = """{"permissions":["workspace.own"],"settings":{},"secrets":{},"workspace":"${workspace.absolutePath}"}"""
        val written = engine.call(module, writeContext, """{"mode":"write"}""", 5_000)
        assertFalse(written.output, written.isError)
        assertTrue(written.output.contains("\"written\":15"))
        val file = java.io.File(workspace, "out.txt")
        assertTrue(file.isFile)
        assertTrue(file.readText() == "hello from wasm")
        workspace.deleteRecursively()
        assertFalse(again.output, again.isError)
    }
}
