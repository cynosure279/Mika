package com.wangzan101.mika.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory

/**
 * 独立进程(:wasm)里的 Wasm 引擎服务。原生运行时崩溃只死这个进程,
 * 宿主进程经 [WasmProcessEngine] 调用,超时/熔断在宿主侧。
 * 这个进程和 App 同一个 UID(不是 isolatedProcess),隔离边界见 docs/dev/plugin-wasm.md「隔离边界」。
 */
class WasmService : Service() {
    private val binder = object : IWasmRuntime.Stub() {
        override fun call(
            wasm: SharedMemory,
            contextJson: ByteArray,
            argsJson: String,
            timeoutMs: Long,
            memoryLimitBytes: Long,
            host: IWasmHost?,
        ): String {
            try {
                val bytes = wasm.use { shm ->
                    val buffer = shm.mapReadOnly()
                    try {
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } finally {
                        SharedMemory.unmap(buffer)
                    }
                }
                WasmtimeNative.ensureLoaded()
                val output = WasmtimeNative.nativeCall(bytes, contextJson, argsJson, timeoutMs, memoryLimitBytes, host)
                // 回复也走 Binder 事务;太大会在回传时失败、宿主分不清原因,这里先干净地报插件错误
                if (output.length > MAX_REPLY_CHARS) throw IllegalStateException("tool output is too large")
                return output
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                // 跨 Binder 只认少数几种异常;其余的会在 Binder 线程上变成未捕获异常,统一转成 IllegalStateException
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
        }

        override fun exit() {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        /** 结果 JSON 的上限(字符);和宿主 HTTP 正文上限同一量级,远低于 Binder 事务缓冲。 */
        const val MAX_REPLY_CHARS = 128 * 1024
    }
}
