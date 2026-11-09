package com.wangzan101.mika.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * :wasm 进程经 Binder 回连宿主后的实际逻辑(和 Binder 分开,便于 JVM 测试)。
 * - [http]:联网请求,宿主里已按策略收口;为空时明确报错(未授权时原生侧本就不会调用)。
 * - [settingsSet]:只写 manifest 声明过的键;secret 走 [PluginSettings.secretWrite]。
 */
class WasmHostHandler(
    private val http: PluginHttp? = null,
    private val settings: PluginSettings? = null,
    /** manifest 声明的设置键 → 类型。 */
    private val declarations: Map<String, String> = emptyMap(),
    /** 只记键名与动作,不记值。 */
    private val log: (String) -> Unit = {},
) {
    fun http(requestJson: String): String =
        (http ?: throw IllegalStateException("network is not available")).request(requestJson)

    fun settingsSet(requestJson: String): String {
        val store = settings ?: throw IllegalStateException("settings are not available")
        val obj = Json.parseToJsonElement(requestJson) as? JsonObject
            ?: throw IllegalArgumentException("request is not a JSON object")
        val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing key")
        val type = declarations[key] ?: throw IllegalArgumentException("setting is not declared: $key")
        val value = obj["value"]?.let { element ->
            if (element is JsonNull) null else element.jsonPrimitive.contentOrNull
                ?: throw IllegalArgumentException("value must be a string")
        }
        val saved = if (type == "secret") {
            store.secretWrite(key, value)
        } else {
            runCatching { store.set(key, value) }.isSuccess
        }
        if (!saved) throw IllegalStateException("could not save $key")
        log(if (value == null) "unset $key" else "set $key")
        return """{"ok":true}"""
    }
}

/** 把 [WasmHostHandler] 包成 :wasm 进程能回连的 Binder 接口;只在有可用能力时创建。 */
class WasmHost(private val handler: WasmHostHandler) : IWasmHost.Stub() {
    override fun http(requestJson: String): String = handler.http(requestJson)

    override fun settingsSet(requestJson: String): String = handler.settingsSet(requestJson)
}
