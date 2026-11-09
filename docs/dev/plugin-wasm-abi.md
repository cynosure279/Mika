# 插件 Wasm ABI v1

适用于随 App 分发的 Wasm 引擎(Wasmtime)。当前只在**调试包**、**arm64 设备**启用(`BuildConfig.WASM_PLUGINS`);发布包安装器会拒绝 wasm 插件。

## 已实现的能力(ABI v2 只读)

import module `mika`:`abi_version()`、`log(level,ptr,len)`、`settings_get(key_ptr,key_len,out_ptr,out_cap)`、`fs_read(path_ptr,path_len,out_ptr,out_cap)`。权限与设置快照由宿主每次调用传入上下文;未授权返回 `-2`。`fs_read` 只读插件自己的 workspace。写入与网络能力尚未实现。

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
- 模块默认 **0 个 import**:没有文件、网络、进程权限。以后的能力(日志、HTTP、插件设置)会以宿主显式注入的 import 提供,并在文档里列出白名单。
- 引擎跑在独立进程 `:wasm`;原生崩溃只死该进程,宿主会重连,连续 3 次失败熔断。

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

- `tools/wasm/test-guest`:回显 + 可选死循环(`"loop":true`)用于验证超时。
- `app/src/androidTest/.../WasmProcessEngineTest`:在设备上验证回显、trap、trap 后继续调用。
