package com.wangzan101.mika.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wasmtime 引擎实现。模块缓存由原生侧按内容哈希管理(解析/编译一次);
 * 每次调用新建 Store/Instance,内存上限与超时由原生侧执行。
 */
class WasmtimeEngine(
    private val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT,
) : WasmEngine {
    init {
        WasmtimeNative.ensureLoaded()
    }

    override val name: String = "wasmtime"

    private class Module(val bytes: ByteArray) : LoadedWasmModule

    override suspend fun load(bytes: ByteArray): LoadedWasmModule = Module(bytes)

    override suspend fun call(module: LoadedWasmModule, argsJson: String, timeoutMs: Long): WasmCallResult =
        withContext(Dispatchers.IO) {
            val bytes = (module as? Module)?.bytes
                ?: return@withContext WasmCallResult("wasm_error: module was not loaded by wasmtime", isError = true)
            try {
                WasmCallResult(WasmtimeNative.nativeCall(bytes, argsJson, timeoutMs, memoryLimitBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                WasmCallResult("wasm_error: ${e.message ?: e.javaClass.simpleName}", isError = true)
            }
        }

    companion object {
        /** 单实例内存上限;插件工具是胶水逻辑,64 MiB 足够,超出视为插件错误。 */
        const val DEFAULT_MEMORY_LIMIT: Long = 64L * 1024 * 1024
    }
}

/** 本机是否带可用的 Wasm 引擎(原生库随 APK 的 arm64 目录分发)。 */
object WasmSupport {
    @Volatile
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    val available: Boolean
        // 独立进程隔离已就绪;发布包等真机验收后再打开(BuildConfig.WASM_PLUGINS)
        get() = com.wangzan101.mika.BuildConfig.WASM_PLUGINS &&
            appContext != null &&
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    fun runtime(packageRoot: java.io.File): PluginWasmRuntime? {
        val context = appContext ?: return null
        if (!available) return null
        return PluginWasmRuntime({ id -> java.io.File(packageRoot, id) }, WasmProcessEngine(context))
    }
}
