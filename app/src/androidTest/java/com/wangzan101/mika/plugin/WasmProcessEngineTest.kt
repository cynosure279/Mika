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

        val ok = engine.call(module, """{"a":1}""", 5_000)
        assertFalse(ok.output, ok.isError)
        assertTrue(ok.output.contains("\"ok\":true"))

        val trap = engine.call(module, """{"loop":true}""", 1_000)
        assertTrue(trap.isError)

        // 崩溃隔离后宿主仍能继续工作
        val again = engine.call(module, """{"b":2}""", 5_000)
        assertFalse(again.output, again.isError)
    }
}
