package com.wangzan101.mika.plugin;

// :wasm 进程反过来经 Binder 请宿主做事。宿主进程集中做代理、地址白名单、大小与超时限制,
// 以及插件设置的落盘(secret 用 Keystore 加密)。请求/响应都是 JSON。出错抛异常,原生侧映射为负的错误码。
interface IWasmHost {
    String http(in String requestJson) = 1;
    // 写插件设置/secret:请求 {"key":"...","value":"..."|null};返回 {"ok":true};出错抛异常。
    String settingsSet(in String requestJson) = 2;
}
