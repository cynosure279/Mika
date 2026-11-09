package com.wangzan101.mika.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主侧的 Wasm 引擎:经 Binder 调独立进程里的 [WasmService]。
 * 原生崩溃只让 :wasm 进程死掉,这里断开连接、下次调用自动重连;
 * 连续失败达到 [fuseLimit] 后熔断,本次进程生命周期内不再尝试。
 */
class WasmProcessEngine(
    context: Context,
    private val fuseLimit: Int = 3,
) : WasmEngine {
    private val appContext = context.applicationContext
    override val name: String = "wasmtime"

    private class Module(val bytes: ByteArray) : LoadedWasmModule
    private class Binding(val service: IWasmRuntime, val connection: ServiceConnection, val binder: IBinder)

    private val lock = Any()
    private var binding: Binding? = null
    private val failures = AtomicInteger(0)

    @Volatile
    private var fused = false

    override suspend fun load(bytes: ByteArray): LoadedWasmModule = Module(bytes)

    override suspend fun call(
        module: LoadedWasmModule,
        contextJson: String,
        argsJson: String,
        timeoutMs: Long,
        host: IWasmHost?,
    ): WasmCallResult =
        withContext(Dispatchers.IO) {
            if (fused) {
                return@withContext WasmCallResult("wasm_error: engine unavailable after repeated crashes", isError = true)
            }
            val bytes = (module as? Module)?.bytes
                ?: return@withContext WasmCallResult("wasm_error: module was not loaded by this engine", isError = true)
            val service = try {
                connect()
            } catch (e: Exception) {
                return@withContext fail("cannot start wasm engine: ${e.message ?: e.javaClass.simpleName}")
            }
            try {
                // 服务内已有 epoch 超时;这里再加一道宿主兜底(多给 5 秒)
                val output = withTimeout(timeoutMs.coerceIn(1, 600_000) + 5_000) {
                    service.call(
                        bytes,
                        contextJson.toByteArray(Charsets.UTF_8),
                        argsJson,
                        timeoutMs,
                        WasmtimeEngine.DEFAULT_MEMORY_LIMIT,
                        host,
                    )
                }
                failures.set(0)
                WasmReply.parse(output)
            } catch (e: TimeoutCancellationException) {
                disconnect()
                fail("wasm engine did not answer in time")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disconnect()
                fail("wasm engine crashed: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun fail(message: String): WasmCallResult {
        if (failures.incrementAndGet() >= fuseLimit) fused = true
        return WasmCallResult("wasm_error: $message", isError = true)
    }

    private fun connect(): IWasmRuntime = synchronized(lock) {
        val current = binding
        if (current != null && current.binder.isBinderAlive) return current.service
        if (current != null) {
            runCatching { appContext.unbindService(current.connection) }
            binding = null
        }
        val latch = CountDownLatch(1)
        var service: IWasmRuntime? = null
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                if (b != null) {
                    binder = b
                    service = IWasmRuntime.Stub.asInterface(b)
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { binding = null }
            }
        }
        val intent = Intent(appContext, WasmService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("bindService returned false")
        }
        if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("wasm service did not connect")
        }
        val bound = binder ?: run {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("wasm service returned no binder")
        }
        val api = service ?: run {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("wasm service returned no interface")
        }
        Binding(api, connection, bound).also { binding = it }.service
    }

    private fun disconnect() = synchronized(lock) {
        binding?.let { runCatching { appContext.unbindService(it.connection) } }
        binding = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
