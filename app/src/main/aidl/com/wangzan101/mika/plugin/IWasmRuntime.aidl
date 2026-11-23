package com.wangzan101.mika.plugin;

import com.wangzan101.mika.plugin.IWasmHost;

// 独立进程(:wasm)里的 Wasm 引擎。宿主传入模块字节与参数 JSON,拿回结果 JSON;
// host 是宿主进程的回调(联网能力),未授权时不传,guest 相应调用返回 -2。
// 出错时抛异常(经 Binder 转成 RemoteException),宿主据此熔断/重连。
interface IWasmRuntime {
    String call(in byte[] wasm, in byte[] contextJson, String argsJson, long timeoutMs, long memoryLimitBytes, in IWasmHost host) = 1;
}
