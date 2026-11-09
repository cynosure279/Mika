package com.wangzan101.mika.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** 一次 Wasm 工具调用结果;宿主只认 JSON 文本,和脚本路径的 stdout 约定保持一致。 */
data class WasmCallResult(val output: String = "", val isError: Boolean = false)

/** 已编译/校验过的模块;实现自己决定内容(Endive 的 WasmModule、Wasmtime 的 Module 等)。 */
interface LoadedWasmModule

/**
 * 引擎契约:解析/编译可缓存,调用必须新建实例。
 * 实现负责能力注入(文件/网络/日志)与资源限额,宿主不直接暴露任何权限。
 */
interface WasmEngine {
    val name: String

    /** 编译并校验模块;同一份 [bytes] 可能被 [WasmModuleCache] 复用,实现不必自己缓存。 */
    suspend fun load(bytes: ByteArray): LoadedWasmModule

    /** 调用工具入口;args/输出都是 JSON 文本。超时由实现负责(epoch/fuel 或等价机制)。 */
    suspend fun call(module: LoadedWasmModule, argsJson: String, timeoutMs: Long): WasmCallResult
}

/**
 * 已编译模块的 LRU 缓存。键包含引擎名和模块内容哈希:插件更新、引擎升级或换引擎都会自然失效。
 * 按条目数和总字节数双上限淘汰最久未用的模块;实例不缓存,每次调用新建。
 */
class WasmModuleCache(
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 32L * 1024 * 1024,
) {
    private data class Entry(val module: LoadedWasmModule, val bytes: Long)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>() // 插入序;命中时移除再放回,最老的在最前

    suspend fun size(): Int = lock.withLock { entries.size }

    suspend fun getOrLoad(key: String, bytes: ByteArray, load: suspend () -> LoadedWasmModule): LoadedWasmModule =
        lock.withLock {
            entries.remove(key)?.let {
                entries[key] = it // 移到最近使用
                return it.module
            }
            // 加载放在锁内:工具调用本来就是串行为主,避免同一模块被并发编译两次
            val module = load()
            entries[key] = Entry(module, bytes.size.toLong())
            trim()
            module
        }

    private fun trim() {
        var total = entries.values.sumOf { it.bytes }
        while (entries.size > maxEntries || (total > maxBytes && entries.size > 1)) {
            val eldest = entries.keys.first()
            total -= entries.remove(eldest)?.bytes ?: 0
        }
    }
}

/**
 * 插件 Wasm 工具的执行入口:从插件包的 tools 目录读 .wasm,按内容哈希缓存编译产物,交给 [engine] 调用。
 * 路径、超时由调用方(PluginEndpoint)按 manifest 决定;这里只做包内路径校验和错误映射。
 */
class PluginWasmRuntime(
    private val packageDir: (pluginId: String) -> File?,
    private val engine: WasmEngine,
    private val cache: WasmModuleCache = WasmModuleCache(),
    private val log: PluginLog? = null,
) {
    suspend fun call(pluginId: String, tool: PluginToolDecl, args: JsonObject, timeoutMs: Long): WasmCallResult {
        val path = tool.wasm ?: return WasmCallResult("invalid_tool: ${tool.name} has no wasm", isError = true)
        if (!PluginManifest.WASM.matches(path) || ".." in path) {
            return WasmCallResult("invalid_tool_wasm: $path", isError = true)
        }
        val dir = packageDir(pluginId) ?: return WasmCallResult("plugin_error: plugin is not installed", isError = true)
        val file = File(dir, path)
        if (!file.isFile) {
            return WasmCallResult("plugin_error: module is missing; reinstall the plugin", isError = true)
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return WasmCallResult("plugin_error: cannot read module: ${it.message}", isError = true)
        }
        val hash = sha256(bytes)
        val key = "${engine.name}:$hash"
        val started = System.currentTimeMillis()
        return try {
            val module = cache.getOrLoad(key, bytes) { engine.load(bytes) }
            val callStarted = System.currentTimeMillis()
            val result = engine.call(module, args.toString(), timeoutMs.coerceIn(1, 600) * 1000L)
            log?.append(
                "tool",
                "${tool.name} engine=${engine.name} load_ms=${callStarted - started} call_ms=${System.currentTimeMillis() - callStarted} error=${result.isError}",
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log?.append("tool", "${tool.name} wasm error=${e.javaClass.simpleName}")
            WasmCallResult("wasm_error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
