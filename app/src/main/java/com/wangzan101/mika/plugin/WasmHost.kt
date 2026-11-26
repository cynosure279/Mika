package com.wangzan101.mika.plugin

/**
 * :wasm 进程经 Binder 回连宿主后的实际逻辑(和 Binder 分开,便于 JVM 测试)。
 * 当前运行时提供联网能力;插件设置/secret 属于 P1b 之后的能力,这里明确拒绝,不假装支持。
 */
class WasmHostHandler(
    private val http: PluginHttp? = null,
    /** 只记方法、域名、状态码;不记 header 与正文。 */
    private val log: (String) -> Unit = {},
) {
    fun http(requestJson: String): String =
        (http ?: throw IllegalStateException("network is not available")).request(requestJson)

    fun settingsSet(@Suppress("UNUSED_PARAMETER") requestJson: String): String {
        log("settings rejected: plugin settings are not supported")
        throw IllegalStateException("plugin settings are not supported by this runtime")
    }
}

/** 把 [WasmHostHandler] 包成 :wasm 进程能回连的 Binder 接口;只在有可用能力时创建。 */
class WasmHost(private val handler: WasmHostHandler) : IWasmHost.Stub() {
    override fun http(requestJson: String): String = binderSafe { handler.http(requestJson) }

    override fun settingsSet(requestJson: String): String = binderSafe { handler.settingsSet(requestJson) }

    /**
     * Binder 只能把少数几种异常带回调用方(IllegalStateException 是其中之一);
     * 别的 RuntimeException(如 [PluginHttp.HttpFailure])会在 App 进程的 Binder 线程上变成未捕获异常,这里统一换掉。
     */
    private inline fun binderSafe(block: () -> String): String = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(e.message ?: e.javaClass.simpleName)
    }
}
