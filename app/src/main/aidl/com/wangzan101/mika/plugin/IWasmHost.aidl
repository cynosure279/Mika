package com.wangzan101.mika.plugin;

// :wasm 进程反过来经 Binder 请宿主执行网络请求。宿主进程集中做代理、地址白名单、大小与超时限制;
// 请求/响应都是 JSON。出错抛异常,原生侧映射为负的错误码。
interface IWasmHost {
    String http(in String requestJson) = 1;
}
