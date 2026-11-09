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
    ): String
}
