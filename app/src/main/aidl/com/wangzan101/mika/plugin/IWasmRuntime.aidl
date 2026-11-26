package com.wangzan101.mika.plugin;

import android.os.SharedMemory;
import com.wangzan101.mika.plugin.IWasmHost;

// 独立进程(:wasm)里的 Wasm 引擎。宿主传入模块字节与参数 JSON,拿回结果 JSON;
// host 是宿主进程的回调(联网能力),未授权时不传,guest 相应调用返回 -2。
// 模块经共享内存传(不受 Binder 单次事务约 1 MB 的上限);引擎报错抛 IllegalStateException,
// 宿主把它当插件错误;RemoteException/DeadObjectException 才算引擎进程出了问题,宿主据此重连/熔断。
interface IWasmRuntime {
    String call(in SharedMemory wasm, in byte[] contextJson, String argsJson, long timeoutMs, long memoryLimitBytes, in IWasmHost host) = 1;
    // 宿主兜底超时后让引擎进程自己退出(原生调用卡住时,Binder 线程池里的另一个线程照样能收到)。
    oneway void exit() = 2;
}
