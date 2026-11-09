//! Mika 的 Wasm 插件引擎：Wasmtime JNI 桥（ABI v2，只读能力）。
//!
//! 工具 ABI（v1 兼容）：
//! - 模块导出 `memory`、`mika_buffer() -> i32`、`mika_buffer_size() -> i32`、
//!   `mika_tool_call(ptr, len) -> i64`；返回值打包为 `(ptr << 32) | len`。
//!
//! 宿主能力（import module `mika`，只读）：
//! - `abi_version() -> i32`（当前为 2）
//! - `log(level, ptr, len)`
//! - `settings_get(key_ptr, key_len, out_ptr, out_cap) -> i32`
//! - `fs_read(path_ptr, path_len, out_ptr, out_cap) -> i32`
//! 权限、设置快照与 workspace 由宿主每次调用经 contextJson 传入，默认拒绝。

use std::collections::{HashMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jlong, jstring, jint};
use jni::JNIEnv;
use sha2::{Digest, Sha256};
use wasmtime::{Caller, Config, Engine, Linker, Module, OptLevel, Store, StoreLimits, StoreLimitsBuilder};

struct State {
    permissions: HashSet<String>,
    settings: HashMap<String, String>,
    secrets: HashMap<String, String>,
    workspace: Option<PathBuf>,
    logs: Vec<String>,
    limits: StoreLimits,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();
static LINKER: OnceLock<Linker<State>> = OnceLock::new();
static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
static TICKER: OnceLock<()> = OnceLock::new();

const CACHE_MODULES: usize = 32;
const LOG_LIMIT: usize = 100;
const LOG_BYTES: usize = 4000;
const FS_LIMIT: usize = 1024 * 1024;

struct Cache {
    map: HashMap<[u8; 32], Module>,
    order: Vec<[u8; 32]>,
}

impl Cache {
    fn new() -> Self {
        Self { map: HashMap::new(), order: Vec::new() }
    }

    fn get(&mut self, key: &[u8; 32]) -> Option<Module> {
        let module = self.map.get(key)?.clone();
        self.order.retain(|k| k != key);
        self.order.push(*key);
        Some(module)
    }

    fn insert(&mut self, key: [u8; 32], module: Module) {
        if !self.map.contains_key(&key) {
            while self.map.len() >= CACHE_MODULES {
                let Some(oldest) = self.order.first().copied() else { break };
                self.order.remove(0);
                self.map.remove(&oldest);
            }
            self.order.push(key);
        }
        self.map.insert(key, module);
    }
}

fn engine() -> &'static Engine {
    ENGINE.get_or_init(|| {
        let mut config = Config::new();
        config.wasm_gc(true);
        config.cranelift_opt_level(OptLevel::Speed);
        config.epoch_interruption(true);
        Engine::new(&config).expect("wasmtime engine")
    })
}

fn ensure_ticker() {
    TICKER.get_or_init(|| {
        std::thread::spawn(|| loop {
            std::thread::sleep(Duration::from_millis(10));
            engine().increment_epoch();
        });
    });
}

fn read_str(caller: &mut Caller<'_, State>, ptr: i32, len: i32) -> Option<String> {
    let memory = caller.get_export("memory")?.into_memory()?;
    let data = memory.data(caller);
    let start = usize::try_from(ptr).ok()?;
    let len = usize::try_from(len).ok()?;
    let end = start.checked_add(len)?;
    let slice = data.get(start..end)?;
    String::from_utf8(slice.to_vec()).ok()
}

fn write_bytes(caller: &mut Caller<'_, State>, ptr: i32, cap: i32, bytes: &[u8]) -> i32 {
    let Some(memory) = caller.get_export("memory").and_then(|e| e.into_memory()) else {
        return -1;
    };
    let start = usize::try_from(ptr).unwrap_or(usize::MAX);
    let cap = usize::try_from(cap).unwrap_or(0);
    if bytes.len() > cap {
        return -4;
    }
    let data = memory.data_mut(caller);
    let Some(end) = start.checked_add(bytes.len()) else { return -1 };
    if end > data.len() {
        return -1;
    }
    data[start..end].copy_from_slice(bytes);
    bytes.len() as i32
}

fn allowed(state: &State, permission: &str) -> bool {
    state.permissions.contains(permission)
}

fn linker() -> &'static Linker<State> {
    LINKER.get_or_init(|| {
        let mut linker = Linker::new(engine());
        linker
            .func_wrap("mika", "abi_version", || 2i32)
            .expect("mika.abi_version");
        linker
            .func_wrap("mika", "log", |mut caller: Caller<'_, State>, level: i32, ptr: i32, len: i32| {
                if caller.data().logs.len() >= LOG_LIMIT {
                    return;
                }
                let Some(mut text) = read_str(&mut caller, ptr, len) else { return };
                if text.len() > LOG_BYTES {
                    text.truncate(LOG_BYTES);
                }
                caller.data_mut().logs.push(format!("[{level}] {text}"));
            })
            .expect("mika.log");
        linker
            .func_wrap(
                "mika",
                "settings_get",
                |mut caller: Caller<'_, State>, key_ptr: i32, key_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                    if !allowed(caller.data(), "settings.plugin") {
                        return -2;
                    }
                    let Some(key) = read_str(&mut caller, key_ptr, key_len) else { return -1 };
                    let value = caller
                        .data()
                        .settings
                        .get(&key)
                        .or_else(|| caller.data().secrets.get(&key))
                        .cloned();
                    match value {
                        Some(v) => write_bytes(&mut caller, out_ptr, out_cap, v.as_bytes()),
                        None => -3,
                    }
                },
            )
            .expect("mika.settings_get");
        linker
            .func_wrap(
                "mika",
                "fs_read",
                |mut caller: Caller<'_, State>, path_ptr: i32, path_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                    if !allowed(caller.data(), "workspace.own") {
                        return -2;
                    }
                    let Some(path) = read_str(&mut caller, path_ptr, path_len) else { return -1 };
                    if !safe_relative(&path) {
                        return -1;
                    }
                    let Some(workspace) = caller.data().workspace.clone() else { return -2 };
                    let Ok(root) = workspace.canonicalize() else { return -5 };
                    let candidate = root.join(&path);
                    let Ok(file) = candidate.canonicalize() else { return -3 };
                    if !file.starts_with(&root) || !file.is_file() {
                        return -1;
                    }
                    let Ok(meta) = std::fs::metadata(&file) else { return -5 };
                    if meta.len() > FS_LIMIT as u64 {
                        return -4;
                    }
                    let Ok(bytes) = std::fs::read(&file) else { return -5 };
                    write_bytes(&mut caller, out_ptr, out_cap, &bytes)
                },
            )
            .expect("mika.fs_read");
        linker
    })
}

fn safe_relative(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.contains('\\') {
        return false;
    }
    !path.split('/').any(|part| part == ".." || part.is_empty() || part == ".")
}

fn module_for(bytes: &[u8]) -> Result<Module, String> {
    let key: [u8; 32] = Sha256::digest(bytes).into();
    let cache = CACHE.get_or_init(|| Mutex::new(Cache::new()));
    let mut cache = cache.lock().map_err(|_| "module cache poisoned".to_string())?;
    if let Some(module) = cache.get(&key) {
        return Ok(module);
    }
    let module = Module::new(engine(), bytes).map_err(|e| format!("compile: {e}"))?;
    cache.insert(key, module.clone());
    Ok(module)
}

fn parse_context(bytes: &[u8], memory_limit: usize) -> State {
    let value: serde_json::Value = serde_json::from_slice(bytes).unwrap_or(serde_json::Value::Null);
    let permissions = value["permissions"]
        .as_array()
        .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    let settings = value["settings"]
        .as_object()
        .map(|o| o.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect())
        .unwrap_or_default();
    let secrets = value["secrets"]
        .as_object()
        .map(|o| o.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect())
        .unwrap_or_default();
    let workspace = value["workspace"].as_str().map(PathBuf::from);
    let limits = StoreLimitsBuilder::new().memory_size(memory_limit).build();
    State { permissions, settings, secrets, workspace, logs: Vec::new(), limits }
}

fn run(bytes: &[u8], context: &[u8], args: &str, timeout_ms: u64, memory_limit: usize) -> Result<String, String> {
    ensure_ticker();
    let module = module_for(bytes)?;
    let mut store = Store::new(engine(), parse_context(context, memory_limit));
    store.limiter(|state| &mut state.limits);
    store.set_epoch_deadline(((timeout_ms + 9) / 10).max(1));

    let instance = linker()
        .instantiate(&mut store, &module)
        .map_err(|e| format!("instantiate: {e}"))?;
    let memory = instance
        .get_memory(&mut store, "memory")
        .ok_or_else(|| "module does not export memory".to_string())?;
    let buffer = instance
        .get_typed_func::<(), i32>(&mut store, "mika_buffer")
        .map_err(|e| format!("missing mika_buffer: {e}"))?;
    let buffer_size = instance
        .get_typed_func::<(), i32>(&mut store, "mika_buffer_size")
        .map_err(|e| format!("missing mika_buffer_size: {e}"))?;
    let call = instance
        .get_typed_func::<(i32, i32), i64>(&mut store, "mika_tool_call")
        .map_err(|e| format!("missing mika_tool_call: {e}"))?;

    let ptr = buffer.call(&mut store, ()).map_err(|e| format!("mika_buffer: {e}"))? as usize;
    let cap = buffer_size.call(&mut store, ()).map_err(|e| format!("mika_buffer_size: {e}"))? as usize;
    let args_bytes = args.as_bytes();
    if args_bytes.len() > cap {
        return Err(format!("arguments too large: {} > {}", args_bytes.len(), cap));
    }
    memory.write(&mut store, ptr, args_bytes).map_err(|e| format!("write args: {e}"))?;

    let packed = call
        .call(&mut store, (ptr as i32, args_bytes.len() as i32))
        .map_err(|e| format!("call: {e}"))? as u64;
    let out_ptr = (packed >> 32) as usize;
    let out_len = (packed & 0xffff_ffff) as usize;
    let data = memory.data(&store);
    let end = out_ptr.checked_add(out_len).ok_or_else(|| "invalid result range".to_string())?;
    if end > data.len() {
        return Err("result range out of bounds".to_string());
    }
    let output = String::from_utf8(data[out_ptr..end].to_vec())
        .map_err(|e| format!("result is not utf-8: {e}"))?;
    let logs = store.data().logs.clone();
    Ok(serde_json::json!({ "output": output, "logs": logs }).to_string())
}

/// Kotlin: `nativeCall(wasm: ByteArray, contextJson: ByteArray, argsJson: String, timeoutMs: Long, memoryLimitBytes: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_wangzan101_mika_plugin_WasmtimeNative_nativeCall<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    wasm: JByteArray<'local>,
    context_json: JByteArray<'local>,
    args_json: JString<'local>,
    timeout_ms: jlong,
    memory_limit: jlong,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let bytes = env.convert_byte_array(&wasm).map_err(|e| format!("read wasm bytes: {e}"))?;
        let context = env
            .convert_byte_array(&context_json)
            .map_err(|e| format!("read context: {e}"))?;
        let args: String = env.get_string(&args_json).map_err(|e| format!("read args: {e}"))?.into();
        let timeout = timeout_ms.max(1) as u64;
        let limit = memory_limit.max(1 << 20) as usize;
        run(&bytes, &context, &args, timeout, limit)
    }));
    match outcome {
        Ok(Ok(output)) => env.new_string(output).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/RuntimeException", message);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "wasm engine panicked");
            std::ptr::null_mut()
        }
    }
}
