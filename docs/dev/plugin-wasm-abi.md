# 插件 Wasm ABI v2

适用于随 App 分发的 Wasmtime 引擎。当前只在**调试包、arm64**启用(`BuildConfig.WASM_PLUGINS`);
发布包在真机验收通过后打开(见 [plugin-wasm.md](plugin-wasm.md))。

## 能力

import module `mika`:

| import | 说明 |
| --- | --- |
| `abi_version() -> i32` | 当前为 2;能力增量不加版本号 |
| `log(level, ptr, len)` | 写插件日志(宿主限流) |
| `fs_read(path_ptr, path_len, out_ptr, out_cap) -> i32` | 读插件自己的 workspace,路径相对且不含 `..` |
| `fs_write(path_ptr, path_len, data_ptr, data_len) -> i32` | 写插件自己的 workspace;1 MB 上限 |
| `http_request(req_ptr, req_len, out_ptr, out_cap) -> i32` | 在 App 进程执行一次 HTTP 请求,需要 manifest 声明 `network` |
| `settings_get(...)` / `settings_set(...)` | 保留在 ABI 中,但当前宿主不授予 `settings.plugin`;调用返回 `-2`。插件设置是 P1b 之后的能力。 |

错误码:`-1` 参数/地址不合法,`-2` 未授权或宿主没有对应回调,`-3` 找不到,
`-4` 超过大小限制,`-5` 宿主/网络出错。

## 权限与 workspace

- 工具声明 `wasm` 时 manifest 必须有 `run-wasm`;没有时安装器拒绝(`wasm_permission_missing`)。
- `network` 是可选的第二个权限;没有它时 `http_request` 返回 -2,宿主也不会回连。
- `fs_read`/`fs_write` 不需要额外声明:每个插件只有自己的
  `files/plugins/<id>/workspace`,由宿主在调用上下文里给出;guests 不能访问其他目录。

## 网络

请求和响应都是 JSON,写请求/读响应共用 guest 线性内存:

```json
{"method":"GET","url":"https://...","headers":{"k":"v"},"body":"...","timeoutMs":5000}
{"status":200,"headers":{"k":"v"},"body":"..."}
```

- 只认 `GET`/`POST`/`HEAD`;`body` 只允许 POST。
- 只认 http(s),不允许 URL 内账号密码;本机、局域网与保留地址拒绝,域名解析后的地址再查一遍(和网页下载同一条规则,`DownloadAddressPolicy`)。
- 宿主跟着重定向,最多 5 跳,每跳重新校验;请求/响应正文各 1 MB,请求头有上限。
- `timeoutMs` 默认 10 秒、最多 30 秒,并截到本次工具调用剩余预算。
- 日志只记方法、域名、状态码与字节数,不记 header 与正文。

## 模块要求

| 导出 | 类型 | 说明 |
| --- | --- | --- |
| `memory` | memory | 线性内存 |
| `mika_buffer` | `() -> i32` | 参数/结果共用缓冲区指针 |
| `mika_buffer_size` | `() -> i32` | 缓冲区容量 |
| `mika_tool_call` | `(i32, i32) -> i64` | 参数指针、长度;返回 `(结果指针 << 32) \| 结果长度` |

宿主把参数 JSON 写进 `mika_buffer`,调用 `mika_tool_call`,按返回值读回结果 JSON。
结果约定与脚本一致:一段 JSON;`{"ok":false,"error":"..."}` 表示干净的失败。

## 资源与隔离

- 模块编译产物按内容 SHA-256 缓存(最多 32 个,总字节上限 32 MiB);每次调用新建实例。
- 安装时把 `tools/` 下模块的 SHA-256 记进包目录 `wasm.sha256`;运行前复核,模块被换过就拒绝。
- 单实例内存上限 64 MiB;超时由 manifest `timeoutSec` 决定,死循环会被 trap。
- 引擎在独立进程 `:wasm`;原生崩溃只死该进程,宿主重连。插件自己的错误(trap、超时、缺导出)只算这次调用失败;
  引擎进程死掉、Binder 不通或宿主兜底超时(插件超时 + 5 秒)才算引擎故障,连续 3 次熔断到 App 进程重启。
- `:wasm` 和 App 同一个 UID,不是 Android 的 isolatedProcess;能限住插件的是 Wasm 沙箱本身(只能调上面这些 import),
  不是进程权限。详见 [plugin-wasm.md](plugin-wasm.md)「隔离边界」。
- 工具结果 JSON 最多 128K 字符;模块经共享内存交给 `:wasm`,不受 Binder 单次事务大小限制。
- 网络回调是反向 Binder(`IWasmHost.aidl`),由 `:wasm` 回连 App 进程执行;未授权时不提供回调。

## 最小示例(Rust)

```rust
const BUF_SIZE: usize = 65536;
static mut ARGS: [u8; BUF_SIZE] = [0; BUF_SIZE];
static mut OUT: [u8; BUF_SIZE] = [0; BUF_SIZE];

#[no_mangle] pub extern "C" fn mika_buffer() -> u32 { std::ptr::addr_of!(ARGS) as u32 }
#[no_mangle] pub extern "C" fn mika_buffer_size() -> u32 { BUF_SIZE as u32 }

#[no_mangle]
pub extern "C" fn mika_tool_call(ptr: u32, len: u32) -> u64 {
    let input = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
    let out = format!("{{\"ok\":true,\"echo\":{}}}", String::from_utf8_lossy(input));
    let bytes = out.as_bytes();
    unsafe {
        let dst = core::ptr::addr_of_mut!(OUT) as *mut u8;
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), dst, bytes.len());
        ((dst as u64) << 32) | bytes.len() as u64
    }
}
```

完整可安装示例:[`plugins/examples/com.example.wasm-echo`](../../plugins/examples/com.example.wasm-echo/)。

## 验证

- 引擎契约与哈希/LRU:JVM `PluginWasmRuntimeTest`、`PluginScriptToolTest`(假引擎)。
- 网络策略:`PluginHttpTest`(MockWebServer);宿主回调:`WasmHostHandlerTest`。
- 独立进程:`app/src/androidTest/.../WasmProcessEngineTest`(真机/模拟器;回显、trap、
  workspace 写入、联网授权与拒绝)。该测试需要 arm64 设备。
