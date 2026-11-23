package com.wangzan101.mika.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** 一次 Wasm 工具调用结果;宿主只认 JSON 文本,和脚本路径的 stdout 约定保持一致。 */
data class WasmCallResult(
    val output: String = "",
    val isError: Boolean = false,
    /** guest 通过 mika.log 写的日志(已限流),由调用方落到插件日志。 */
    val logs: List<String> = emptyList(),
)

/** 已编译/校验过的模块;实现自己决定内容(Wasmtime 的 Module 等)。 */
interface LoadedWasmModule

/**
 * 引擎契约:解析/编译可缓存,调用必须新建实例。
 * 实现负责能力注入(文件/网络/日志)与资源限额,宿主不直接暴露任何权限。
 */
interface WasmEngine {
    val name: String

    /** 编译并校验模块;[WasmModuleCache] 会按内容哈希复用。 */
    suspend fun load(bytes: ByteArray): LoadedWasmModule

    /** 调用工具入口;contextJson 是宿主能力上下文(权限/workspace 快照),args/输出都是 JSON 文本。 */
    suspend fun call(
        module: LoadedWasmModule,
        contextJson: String,
        argsJson: String,
        timeoutMs: Long,
        /** 联网能力的宿主回调;未授权时为空,guest 调 `mika.http_request` 得到 -2。 */
        host: IWasmHost? = null,
    ): WasmCallResult
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
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun size(): Int = lock.withLock { entries.size }

    suspend fun getOrLoad(key: String, bytes: ByteArray, load: suspend () -> LoadedWasmModule): LoadedWasmModule =
        lock.withLock {
            entries.remove(key)?.let {
                entries[key] = it
                return it.module
            }
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
 * 路径、超时由调用方按 manifest 决定;这里只做包内路径校验、安装时哈希复核和错误映射。
 * 宿主能力:workspace 文件读写按插件自己的目录授权;联网只认 manifest 声明的 `network`,且要有宿主回调。
 */
class PluginWasmRuntime(
    private val packageDir: (pluginId: String) -> File?,
    private val engine: WasmEngine,
    private val cache: WasmModuleCache = WasmModuleCache(),
    private val log: (pluginId: String, kind: String, message: String) -> Unit = { _, _, _ -> },
    /** 插件自己的可写目录;为空时 file 能力一律拒绝。 */
    private val workspace: (pluginId: String) -> File? = { null },
    /** 有联网权限时给 guest 的宿主回调;为空表示本机没有对应能力实现。 */
    private val hostFactory: ((pluginId: String) -> IWasmHost?)? = null,
) {
    suspend fun call(
        pluginId: String,
        permissions: List<String>,
        wasmPath: String,
        argsJson: String,
        timeoutMs: Long,
    ): WasmCallResult {
        if (!PluginManifest.WASM.matches(wasmPath) || ".." in wasmPath) {
            return WasmCallResult("invalid_tool_wasm: $wasmPath", isError = true)
        }
        val dir = packageDir(pluginId) ?: return WasmCallResult("plugin_error: plugin is not installed", isError = true)
        val bytes = PluginWasmModules.verified(dir, wasmPath)
            ?: return WasmCallResult("plugin_error: module is missing or changed; reinstall the plugin", isError = true)
        val hash = sha256(bytes)
        val key = "${engine.name}:$hash"
        val network = "network" in permissions
        val granted = buildList {
            addAll(permissions)
            if (workspace(pluginId) != null) add("workspace.own")
        }
        val host = if (network) hostFactory?.invoke(pluginId) else null
        val started = System.currentTimeMillis()
        return try {
            val module = cache.getOrLoad(key, bytes) { engine.load(bytes) }
            val context = buildJsonObject {
                put("pluginId", pluginId)
                put("permissions", buildJsonArray { granted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                workspace(pluginId)?.let { put("workspace", it.absolutePath) }
            }.toString()
            val result = engine.call(module, context, argsJson, timeoutMs.coerceIn(1, 600) * 1000L, host)
            result.logs.forEach { log(pluginId, "guest", it) }
            log(pluginId, "tool", "wasm engine=${engine.name} load_ms=${System.currentTimeMillis() - started} error=${result.isError}")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(pluginId, "tool", "wasm error=${e.javaClass.simpleName}")
            WasmCallResult("wasm_error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
