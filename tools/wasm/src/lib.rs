//! Mika 的 Wasm 插件引擎：Wasmtime JNI 桥。
//!
//! ABI v1（线性内存）：
//! - 模块导出 `memory`、`mika_buffer() -> i32`、`mika_buffer_size() -> i32`、
//!   `mika_tool_call(ptr, len) -> i64`；
//! - 宿主把参数 JSON 写进 `mika_buffer` 指向的区域，调用工具函数；
//! - 返回值打包为 `(ptr << 32) | len`，指向同一块缓冲区里的结果 JSON。
//!
//! 每次调用新建 Store/Instance；编译产物按内容哈希缓存；epoch 每 10ms 递增一次，
//! 每次调用按超时设置 deadline，死循环会被 trap；内存由 StoreLimits 限制。

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use sha2::{Digest, Sha256};
use wasmtime::{Config, Engine, Instance, Module, OptLevel, Store, StoreLimits, StoreLimitsBuilder};

struct Limits {
    limits: StoreLimits,
}

static ENGINE: OnceLock<Engine> = OnceLock::new();
static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
static TICKER: OnceLock<()> = OnceLock::new();

const CACHE_MODULES: usize = 32;

struct Cache {
    map: HashMap<[u8; 32], Module>,
    order: Vec<[u8; 32]>,
}

impl Cache {
    fn new() -> Self {
        Self {
            map: HashMap::new(),
            order: Vec::new(),
        }
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
                let Some(oldest) = self.order.first().copied() else {
                    break;
                };
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

fn run(bytes: &[u8], args: &str, timeout_ms: u64, memory_limit: usize) -> Result<String, String> {
    ensure_ticker();
    let module = module_for(bytes)?;
    let limits = StoreLimitsBuilder::new().memory_size(memory_limit).build();
    let mut store = Store::new(engine(), Limits { limits });
    store.limiter(|state| &mut state.limits);
    store.set_epoch_deadline(((timeout_ms + 9) / 10).max(1));

    let instance = Instance::new(&mut store, &module, &[]).map_err(|e| format!("instantiate: {e}"))?;
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
    memory
        .write(&mut store, ptr, args_bytes)
        .map_err(|e| format!("write args: {e}"))?;

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
    String::from_utf8(data[out_ptr..end].to_vec()).map_err(|e| format!("result is not utf-8: {e}"))
}

/// Kotlin: `external fun nativeCall(wasm: ByteArray, argsJson: String, timeoutMs: Long, memoryLimitBytes: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_wangzan101_mika_plugin_WasmtimeNative_nativeCall<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    wasm: JByteArray<'local>,
    args_json: JString<'local>,
    timeout_ms: jlong,
    memory_limit: jlong,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        let bytes = env
            .convert_byte_array(&wasm)
            .map_err(|e| format!("read wasm bytes: {e}"))?;
        let args: String = env
            .get_string(&args_json)
            .map_err(|e| format!("read args: {e}"))?
            .into();
        let timeout = timeout_ms.max(1) as u64;
        let limit = memory_limit.max(1 << 20) as usize;
        run(&bytes, &args, timeout, limit)
    }));
    match outcome {
        Ok(Ok(output)) => env
            .new_string(output)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
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
