package com.wangzan101.mika.plugin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginWasmRuntimeTest {
    private class Engine(private val fail: Boolean = false) : WasmEngine {
        override val name = "fake"
        val loads = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        val hosts = mutableListOf<IWasmHost?>()

        override suspend fun load(bytes: ByteArray): LoadedWasmModule {
            loads += bytes.joinToString(",")
            return object : LoadedWasmModule {}
        }

        override suspend fun call(module: LoadedWasmModule, contextJson: String, argsJson: String, timeoutMs: Long, host: IWasmHost?): WasmCallResult {
            contexts += contextJson
            hosts += host
            return if (fail) throw IllegalStateException("boom") else WasmCallResult("""{"ok":true}""")
        }
    }

    private fun temp(): File = Files.createTempDirectory("wasm-rt").toFile()

    private fun dirWith(name: String, bytes: ByteArray): File = temp().also {
        File(it, "tools").mkdirs()
        File(it, "tools/$name").writeBytes(bytes)
        PluginWasmModules.writeHashes(it, PluginWasmModules.collect(it))
    }

    private val tool = PluginToolDecl(name = "ping", wasm = "tools/ping.wasm")

    @Test fun missingModuleFailsCleanly() = runTest {
        val dir = temp()
        val r = PluginWasmRuntime({ dir }, Engine()).call("p", emptyList(), tool, buildJsonObject {}, 5)
        assertTrue(r.isError)
        assertTrue(r.output.contains("missing or changed"))
        dir.deleteRecursively()
    }

    @Test fun invalidPathRejectedBeforeReading() = runTest {
        val r = PluginWasmRuntime({ File("/nonexistent") }, Engine())
            .call("p", emptyList(), PluginToolDecl(name = "x", wasm = "../x.wasm"), buildJsonObject {}, 5)
        assertTrue(r.isError)
        assertTrue(r.output.contains("invalid_tool_wasm"))
    }

    @Test fun moduleIsCachedByContent() = runTest {
        val dir = dirWith("ping.wasm", byteArrayOf(1, 2, 3))
        val engine = Engine()
        val runtime = PluginWasmRuntime({ dir }, engine)
        runtime.call("p", emptyList(), tool, buildJsonObject {}, 5)
        runtime.call("p", emptyList(), tool, buildJsonObject {}, 5)
        assertEquals(1, engine.loads.size)

        // 插件更新后内容哈希变化,必须重新编译(安装器会同时更新哈希清单)
        File(dir, "tools/ping.wasm").writeBytes(byteArrayOf(4, 5, 6))
        PluginWasmModules.writeHashes(dir, PluginWasmModules.collect(dir))
        runtime.call("p", emptyList(), tool, buildJsonObject {}, 5)
        assertEquals(2, engine.loads.size)
        dir.deleteRecursively()
    }

    @Test fun tamperedModuleIsRejected() = runTest {
        val dir = dirWith("ping.wasm", byteArrayOf(1))
        File(dir, "tools/ping.wasm").writeBytes(byteArrayOf(9))
        val r = PluginWasmRuntime({ dir }, Engine()).call("p", emptyList(), tool, buildJsonObject {}, 5)
        assertTrue(r.isError)
        assertTrue(r.output.contains("changed"))
        dir.deleteRecursively()
    }

    @Test fun engineErrorsBecomeErrorResults() = runTest {
        val dir = dirWith("ping.wasm", byteArrayOf(1))
        val r = PluginWasmRuntime({ dir }, Engine(fail = true)).call("p", emptyList(), tool, buildJsonObject {}, 5)
        assertTrue(r.isError)
        assertTrue(r.output.contains("wasm_error"))
        dir.deleteRecursively()
    }

    @Test fun lruEvictsOldestModule() = runTest {
        val dir = dirWith("a.wasm", byteArrayOf(1))
        File(dir, "tools/b.wasm").writeBytes(byteArrayOf(2))
        PluginWasmModules.writeHashes(dir, PluginWasmModules.collect(dir))
        val engine = Engine()
        val runtime = PluginWasmRuntime({ dir }, engine, WasmModuleCache(maxEntries = 1, maxBytes = 1024))
        runtime.call("p", emptyList(), PluginToolDecl(name = "a", wasm = "tools/a.wasm"), buildJsonObject {}, 5)
        runtime.call("p", emptyList(), PluginToolDecl(name = "b", wasm = "tools/b.wasm"), buildJsonObject {}, 5)
        runtime.call("p", emptyList(), PluginToolDecl(name = "a", wasm = "tools/a.wasm"), buildJsonObject {}, 5)
        assertEquals(3, engine.loads.size)
        dir.deleteRecursively()
    }

    @Test fun networkPermissionAndHostOnlyWhenGranted() = runTest {
        val dir = dirWith("ping.wasm", byteArrayOf(1))
        val engine = Engine()
        var hosts = 0
        val runtime = PluginWasmRuntime(
            { dir },
            engine,
            context = { _, permissions -> permissions.joinToString(",") },
            hostFactory = { hosts++; null },
        )
        runtime.call("p", listOf("network", "workspace.own"), tool, buildJsonObject {}, 5)
        assertEquals(0, hosts)
        assertEquals("workspace.own", engine.contexts.last())

        runtime.call("p", listOf("network", "workspace.own"), tool, buildJsonObject {}, 5, network = true)
        assertEquals(1, hosts)
        assertEquals("network,workspace.own", engine.contexts.last())
        dir.deleteRecursively()
    }
}
