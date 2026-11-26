package com.wangzan101.mika.plugin

import android.content.Context
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.cap.web.DownloadAddressPolicy
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.tool.WasmInvocation
import com.wangzan101.mika.core.tool.WasmRunner
import com.wangzan101.mika.core.tool.WasmCallResult as CoreWasmResult
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App 侧的 Wasm 运行时入口:引擎跑在独立进程 `:wasm`,宿主经 [WasmProcessEngine] 调用。
 * 原生库只随 arm64 分发;调试包默认启用,发布包等真机验收通过后再打开(见 docs/dev/plugin-wasm.md)。
 */
object WasmSupport {
    @Volatile
    private var appContext: Context? = null

    /** 清单里 [WasmService] 的进程名后缀(`android:process=":wasm"`)。 */
    private const val ENGINE_PROCESS_SUFFIX = ":wasm"

    /** [processName] 是不是引擎进程(`<包名>:wasm`);App 初始化据此跳过主进程才做的事。 */
    fun isEngineProcess(processName: String?): Boolean = processName?.endsWith(ENGINE_PROCESS_SUFFIX) == true

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val available: Boolean
        get() = BuildConfig.WASM_PLUGINS &&
            appContext != null &&
            android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /**
     * 插件网络能力用的客户端:和 Mika 自己的连接一样走设置里的代理。
     * 域名解析到本机/局域网/保留地址的一律拒绝([DownloadAddressPolicy.guardedDns]),挡住 DNS 换绑绕过地址检查。
     */
    private val http: OkHttpClient by lazy {
        NetProxy.shared.client(
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).dns(DownloadAddressPolicy.guardedDns()),
        )
    }

    /** 整个 App 进程共用一个运行时:到 :wasm 的连接、熔断状态和模块缓存都是全局的,不随每次任务重建。 */
    @Volatile
    private var shared: AppWasmRunner? = null

    /** 任务装配用的 Wasm 引擎入口;不可用时为 null,Wasm 工具会干净地报 wasm_unavailable。 */
    fun runner(context: Context): WasmRunner? {
        if (!available) return null
        shared?.let { return it }
        return synchronized(this) { shared ?: AppWasmRunner(context.applicationContext).also { shared = it } }
    }

    private fun runtime(context: Context): PluginWasmRuntime {
        val root = File(context.filesDir, "plugins")
        return PluginWasmRuntime(
            packageDir = { id -> File(root, id) },
            engine = WasmProcessEngine(context),
            log = { id, kind, message -> PluginLog(File(File(root, id), "logs")).append(kind, message) },
            workspace = { id -> File(File(root, id), "workspace").apply { mkdirs() } },
            hostFactory = { id ->
                val log = PluginLog(File(File(root, id), "logs"))
                WasmHost(WasmHostHandler(PluginHttp(http, log = { line -> log.append("http", line) }), log = { line -> log.append("settings", line) }))
            },
        )
    }

    private class AppWasmRunner(context: Context) : WasmRunner {
        private val wasm = runtime(context)

        override suspend fun call(invocation: WasmInvocation, argsJson: String): CoreWasmResult {
            val result = wasm.call(
                pluginId = invocation.pluginId,
                permissions = invocation.permissions,
                wasmPath = invocation.modulePath,
                argsJson = argsJson,
                timeoutSec = (invocation.timeoutMs + 999) / 1000L,
            )
            return if (result.isError) CoreWasmResult(error = result.output) else CoreWasmResult(output = result.output)
        }
    }
}
