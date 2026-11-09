package com.wangzan101.mika.plugin

/**
 * Wasmtime 引擎的 JNI 入口(实现在 tools/wasm)。ABI v1:
 * 模块导出 `memory` / `mika_buffer` / `mika_buffer_size` / `mika_tool_call`,
 * 宿主把参数 JSON 写进 guest 缓冲区,结果按 `(ptr << 32) | len` 返回。
 */
object WasmtimeNative {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("mika_wasm")
            loaded = true
        }
    }

    external fun nativeCall(
        wasm: ByteArray,
        contextJson: ByteArray,
        argsJson: String,
        timeoutMs: Long,
        memoryLimitBytes: Long,
        /** 联网能力的宿主回调;未授权时传 null,guest 调 `mika.http_request` 得到 -2。 */
        host: IWasmHost?,
    ): String
}
