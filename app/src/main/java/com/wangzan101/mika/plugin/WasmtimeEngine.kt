package com.wangzan101.mika.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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

    override suspend fun call(
        module: LoadedWasmModule,
        contextJson: String,
        argsJson: String,
        timeoutMs: Long,
        host: IWasmHost?,
    ): WasmCallResult =
        withContext(Dispatchers.IO) {
            val bytes = (module as? Module)?.bytes
                ?: return@withContext WasmCallResult("wasm_error: module was not loaded by wasmtime", isError = true)
            try {
                WasmReply.parse(
                    WasmtimeNative.nativeCall(
                        bytes,
                        contextJson.toByteArray(Charsets.UTF_8),
                        argsJson,
                        timeoutMs,
                        memoryLimitBytes,
                        host,
                    ),
                )
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

/** 原生侧返回的信封:`{"output": "...", "logs": ["..."]}`。 */
internal object WasmReply {
    fun parse(json: String): WasmCallResult = runCatching {
        val obj = Json.parseToJsonElement(json) as JsonObject
        val output = (obj["output"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val logs = (obj["logs"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        WasmCallResult(output, logs = logs)
    }.getOrElse { WasmCallResult(json) }
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

    /** 插件网络能力用的客户端:和 Mika 自己的连接一样走设置里的代理,本机/局域网直连。 */
    private val http: okhttp3.OkHttpClient by lazy {
        com.wangzan101.mika.core.net.NetProxy.shared.client(
            okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    fun runtime(packageRoot: java.io.File): PluginWasmRuntime? {
        val context = appContext ?: return null
        if (!available) return null
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(context)
        return PluginWasmRuntime(
            packageDir = { id -> java.io.File(packageRoot, id) },
            engine = WasmProcessEngine(context),
            context = { id, permissions ->
                val settings = PluginSettings(
                    java.io.File(packageRoot, id),
                    com.wangzan101.mika.settings.KeystoreKeyCipher(pluginKeyAlias(id)),
                )
                buildJsonObject {
                    put("pluginId", id)
                    put("permissions", buildJsonArray { permissions.forEach { add(it) } })
                    put("settings", buildJsonObject { settings.all().forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                    put("secrets", buildJsonObject { settings.secretEnv().forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                    put("workspace", dirs.pluginWorkspace(id).absolutePath)
                }.toString()
            },
            hostFactory = { id ->
                val log = PluginLog(java.io.File(java.io.File(packageRoot, id), "logs"))
                WasmHttpHost(PluginHttp(http, log = { line -> log.append("http", line) }))
            },
        )
    }
}
