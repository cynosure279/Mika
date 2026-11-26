package com.wangzan101.mika.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 宿主侧的 Wasm 引擎:经 Binder 调独立进程里的 [WasmService]。
 *
 * - 插件自己的错误(trap、超时、缺导出、输出太大)由服务以 IllegalStateException 报回,只算这次调用失败。
 * - 引擎进程死掉或 Binder 不通(RemoteException)才算引擎故障:断开连接、下次调用自动重连;
 *   连续故障达到 [fuseLimit] 后熔断,本次 App 进程生命周期内不再尝试。
 * - Binder 调用本身不可取消,所以放在单独的协程里等:超过「插件超时 + [graceMs]」还没回来,
 *   就让引擎进程退出(卡住的调用随之以 DeadObjectException 返回)并算一次故障。
 * - 空闲 [idleMs] 后解绑,系统可以回收 :wasm 进程;下次调用再拉起。
 *
 * 同一个 App 进程里应只有一个实例(见 [WasmSupport]),连接与熔断状态才是全局的。
 */
class WasmProcessEngine(
    context: Context,
    private val fuseLimit: Int = 3,
    private val graceMs: Long = 5_000,
    private val idleMs: Long = 60_000,
) : WasmEngine {
    private val appContext = context.applicationContext
    override val name: String = "wasmtime"

    private class Module(val bytes: ByteArray) : LoadedWasmModule
    private class Binding(val service: IWasmRuntime, val connection: ServiceConnection, val binder: IBinder)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var binding: Binding? = null
    private var active = 0
    private var idleJob: Job? = null
    private val failures = AtomicInteger(0)

    @Volatile
    private var fused = false

    override suspend fun load(bytes: ByteArray): LoadedWasmModule = Module(bytes)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun call(
        module: LoadedWasmModule,
        contextJson: String,
        argsJson: String,
        timeoutMs: Long,
        host: IWasmHost?,
    ): WasmCallResult = withContext(Dispatchers.IO) {
        if (fused) {
            return@withContext WasmCallResult("wasm_error: engine unavailable after repeated crashes", isError = true)
        }
        val bytes = (module as? Module)?.bytes
            ?: return@withContext WasmCallResult("wasm_error: module was not loaded by this engine", isError = true)
        val budget = timeoutMs.coerceIn(1, 600_000)
        val service = try {
            acquire()
        } catch (e: Exception) {
            release()
            return@withContext crashed("cannot start wasm engine: ${e.message ?: e.javaClass.simpleName}")
        }
        val pending = scope.async {
            SharedMemory.create("mika-wasm", bytes.size.coerceAtLeast(1)).use { shm ->
                val buffer = shm.mapReadWrite()
                try {
                    buffer.put(bytes)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                shm.setProtect(OsConstants.PROT_READ)
                service.call(shm, contextJson.toByteArray(Charsets.UTF_8), argsJson, budget, WasmtimeEngine.DEFAULT_MEMORY_LIMIT, host)
            }
        }
        // 调用真正结束(包括任务被停、这里已不再等)时才算空闲
        pending.invokeOnCompletion { release() }
        // 服务内已有 epoch 超时;这里再加一道宿主兜底。Binder 调用不可取消,只能在外面等。
        if (withTimeoutOrNull(budget + graceMs) { pending.join() } == null) {
            runCatching { service.exit() }
            disconnect()
            return@withContext crashed("wasm engine did not answer in time")
        }
        when (val e = pending.getCompletionExceptionOrNull()) {
            null -> {
                failures.set(0)
                WasmReply.parse(pending.getCompleted())
            }
            is RemoteException -> {
                disconnect()
                crashed("wasm engine stopped: ${e.message ?: e.javaClass.simpleName}")
            }
            // 服务报回的插件错误(trap、超时、缺导出……):引擎好好的,不算故障
            is IllegalStateException -> {
                failures.set(0)
                WasmCallResult("wasm_error: ${e.message ?: "plugin failed"}", isError = true)
            }
            else -> WasmCallResult("wasm_error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun crashed(message: String): WasmCallResult {
        if (failures.incrementAndGet() >= fuseLimit) fused = true
        return WasmCallResult("wasm_error: $message", isError = true)
    }

    /** 记一次进行中的调用并拿到连接;和 [release] 成对。 */
    private fun acquire(): IWasmRuntime {
        synchronized(lock) {
            active++
            idleJob?.cancel()
            idleJob = null
        }
        return connect()
    }

    private fun release() = synchronized(lock) {
        active--
        if (active == 0 && binding != null) {
            idleJob = scope.launch {
                delay(idleMs)
                synchronized(lock) { if (active == 0) disconnectLocked() }
            }
        }
    }

    private fun connect(): IWasmRuntime = synchronized(lock) {
        val current = binding
        if (current != null && current.binder.isBinderAlive) return current.service
        disconnectLocked()
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

            // 进程死了:连接还登记着,系统会在进程重启后再连上;这里不动它,下一次调用发现 Binder 已死时再解绑重连。
            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val intent = Intent(appContext, WasmService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("bindService returned false")
        }
        if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("wasm service did not connect")
        }
        val bound = binder
        val api = service
        if (bound == null || api == null) {
            runCatching { appContext.unbindService(connection) }
            throw IllegalStateException("wasm service returned no binder")
        }
        Binding(api, connection, bound).also { binding = it }.service
    }

    private fun disconnect() = synchronized(lock) { disconnectLocked() }

    private fun disconnectLocked() {
        binding?.let { runCatching { appContext.unbindService(it.connection) } }
        binding = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
