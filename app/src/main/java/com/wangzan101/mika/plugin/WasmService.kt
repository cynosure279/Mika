package com.wangzan101.mika.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 独立进程(:wasm)里的 Wasm 引擎服务。原生运行时崩溃只死这个进程,
 * 宿主进程经 [WasmProcessEngine] 调用,超时/熔断在宿主侧。
 */
class WasmService : Service() {
    private val binder = object : IWasmRuntime.Stub() {
        override fun call(wasm: ByteArray, argsJson: String, timeoutMs: Long, memoryLimitBytes: Long): String {
            try {
                WasmtimeNative.ensureLoaded()
                return WasmtimeNative.nativeCall(wasm, argsJson, timeoutMs, memoryLimitBytes)
            } catch (e: Throwable) {
                // 跨 Binder 只能带 RuntimeException;宿主统一映射为 wasm_error
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
