package com.wangzan101.mika.plugin;

// 独立进程(:wasm)里的 Wasm 引擎。宿主传入模块字节与参数 JSON,拿回结果 JSON;
// 出错时抛异常(经 Binder 转成 RemoteException),宿主据此熔断/重连。
interface IWasmRuntime {
    String call(in byte[] wasm, String argsJson, long timeoutMs, long memoryLimitBytes) = 1;
}
