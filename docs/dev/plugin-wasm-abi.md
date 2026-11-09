# 插件 Wasm ABI v1

适用于随 App 分发的 Wasm 引擎(Wasmtime)。当前只在**调试包**、**arm64 设备**启用(`BuildConfig.WASM_PLUGINS`);发布包安装器会拒绝 wasm 插件。

## 已实现的能力(ABI v2)

import module `mika`:

| import | 说明 |
| --- | --- |
| `abi_version() -> i32` | 当前为 2;能力是增量,数字不随能力增加而变 |
| `log(level, ptr, len)` | 写插件日志(宿主限流) |
| `settings_get(key_ptr, key_len, out_ptr, out_cap) -> i32` | 读插件设置/secret,需要 `settings.plugin` |
| `fs_read(path, len, out, cap) -> i32`、`fs_write(path, len, data, len)` | 只作用于插件自己的 workspace,需要 `workspace.own`;路径校验、写入 1 MB 上限 |
| `http_request(req_ptr, req_len, out_ptr, out_cap) -> i32` | 在 App 进程执行一次 HTTP 请求,需要 `network`;见下 |
| `settings_set(key_ptr, key_len, value_ptr, value_len) -> i32` | 写插件设置/secret,需要 `settings.plugin`;只认 manifest 声明过的键,`value_len` 为 0 表示删除;见下 |

能力错误码统一为负数:`-1` 参数或地址不合法,`-2` 未授权(或宿主没有提供对应回调),`-3` 未声明/找不到,`-4` 超过大小限制,`-5` 宿主/网络出错。权限与设置快照由宿主每次调用传入上下文,默认拒绝。

## 插件设置(`settings_set`)

- 键必须是 manifest `settings` 里声明过的(`^[a-z][a-z0-9_]{0,63}$`),未声明返回 `-3`。
- 值按声明的类型校验:`string`/`secret` 任意 UTF-8 文本(≤ 64 KB),`bool` 只认 `true`/`false`,`number` 必须是有限数字;不合法返回 `-1`。
- `value_len` 为 0 表示删除这一项。普通设置写 `settings.json`;`secret` 由 App 进程用 Keystore 加密写入 `secrets.bin`,明文不出宿主进程。
- 落盘经反向 Binder(`IWasmHost.settingsSet`)在 App 进程完成;日志只记键名与 set/unset,不记值。
- guest 读设置仍用 `settings_get`;secret 的明文每次调用由宿主放进上下文(供脚本/工具使用),不写日志。

## 网络(`http_request`)

请求与响应都是 JSON,写请求/读响应都用同一块 guest 线性内存:

```json
// 请求
{"method":"GET","url":"https://...","headers":{"k":"v"},"body":"...","timeoutMs":5000}
// 响应
{"status":200,"headers":{"k":"v"},"body":"..."}
```

- 方法只认 `GET`/`POST`/`HEAD`;`body` 只允许 `POST`。
- 地址只认 `http`/`https`,不允许 URL 里带账号密码,不允许本机与私网地址(`localhost`、`127.0.0.0/8`、`10/8`、`172.16/12`、`192.168/16`、链路本地、IPv6 私有段等,规则见 `core/net/LocalHosts.kt`)。
- 重定向由宿主跟着走,最多 5 跳,每一跳重新校验地址;303 与 301/302 的 POST 改为 GET。
- 请求/响应正文各 1 MB;请求头最多 32 个、合计 16 KB,`Host`/`Content-Length`/`Connection` 等由 OkHttp 管的头不允许自己给。
- `timeoutMs` 默认 10 秒,最多 30 秒,并且不会超过本次工具调用剩下的时间(宿主会把 `timeoutMs` 截到剩余预算)。
- 联网要求同时满足:manifest 声明 `network`、用户对插件打开联网、全局联网开关打开。任一不满足时宿主不提供回调,`http_request` 返回 `-2`。
- 请求在 App 进程的 Binder 线程里同步执行,走 Mika 自己的网络代理与证书;日志只记方法、域名、状态码和字节数,不记 header 与正文。
- 死锁约束:宿主发起引擎调用的协程在 IO 线程,回调内部不再同步调用 `:wasm`。

## 模块要求

模块必须导出:

| 导出 | 类型 | 说明 |
| --- | --- | --- |
| `memory` | memory | 线性内存 |
| `mika_buffer` | `() -> i32` | 参数/结果共用的缓冲区指针 |
| `mika_buffer_size` | `() -> i32` | 缓冲区容量 |
| `mika_tool_call` | `(i32, i32) -> i64` | 工具入口:参数指针、长度;返回 `(结果指针 << 32) \| 结果长度` |

调用流程:宿主把参数 JSON 按 UTF-8 写入 `mika_buffer`(不超过 `mika_buffer_size`),调用 `mika_tool_call`,按返回值从同一块内存读回结果 JSON。结果约定与脚本路径一致:一段 JSON 文本。

## 资源与安全

- 每次调用新建实例;模块编译产物按内容 SHA-256 缓存(最多 32 个)。
- 安装时把 `tools/` 下模块的 SHA-256 记进包目录的 `wasm.sha256`,运行前复核;模块被换过就拒绝执行,需要重装。
- 单实例内存上限 64 MiB;调用超时由插件 manifest 的 `timeoutSec`(1–600)决定,死循环会被 trap。
- 模块只能 import 上表列出的 `mika` 能力;没有进程权限,文件与网络都要宿主逐次授权。以后的能力同样以显式 import 提供并列入白名单。
- 引擎跑在独立进程 `:wasm`;原生崩溃只死该进程,宿主会重连,连续 3 次失败熔断。网络回调是反向 Binder(`IWasmHost`),由 `:wasm` 回连 App 进程执行。

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
    let text = core::str::from_utf8(input).unwrap_or("");
    let out = format!("{{\"ok\":true,\"echo\":\"{}\"}}", text);
    let bytes = out.as_bytes();
    unsafe {
        let dst = core::ptr::addr_of_mut!(OUT) as *mut u8;
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), dst, bytes.len());
        ((dst as u64) << 32) | bytes.len() as u64
    }
}
```

完整可安装示例:[`examples/plugins/com.example.wasm-echo`](../../examples/plugins/com.example.wasm-echo/)。构建:`cargo build --release --target wasm32-unknown-unknown`,再用 `build.py` 打 zip。

## 验证

- `tools/wasm/test-guest`:回显 + 可选死循环(`"loop":true`)用于验证超时;`"mode":"settings"`/`"settings_write"`/`"write"`/`"log"`/`"http"` 分别验证设置读、设置写(参数可带 `"key"`/`"value"`/`"delete":true`)、写文件、日志与网络(参数里可用 `"url"` 指定地址,默认 `https://example.com/`)。
- `app/src/androidTest/.../WasmProcessEngineTest`:在设备上验证回显、trap、trap 后继续调用;联网成功、无权限 `-2`、未给回调 `-2` 与真实宿主实现对本地服务的完整回环;设置写入成功、删除、未声明 `-3`、类型不合法 `-1`、无权限 `-2`。
- `app/src/test/.../PluginHttpTest`:网络请求的策略、限额、重定向与超时(本机 MockWebServer);`WasmHostHandlerTest` 覆盖设置写入/删除、secret 加密与不记值。
