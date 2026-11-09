package com.wangzan101.mika.plugin

/**
 * 把宿主进程里的 [PluginHttp] 包成 :wasm 进程能经 Binder 回连的接口。
 * 只在联网授权满足时创建;guest 每次调 `mika.http_request` 都会落到这里同步执行一次请求。
 */
class WasmHttpHost(private val handler: PluginHttp) : IWasmHost.Stub() {
    override fun http(requestJson: String): String = handler.request(requestJson)
}
