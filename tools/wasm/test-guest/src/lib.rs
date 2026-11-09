//! ABI v1 的测试 guest:回显参数 JSON。
//! 只用于验证引擎/ABI,不随 APK 分发。

const BUF_SIZE: usize = 65536;
static KEY: &[u8] = b"greeting";

#[link(wasm_import_module = "mika")]
extern "C" {
    fn log(level: i32, ptr: u32, len: u32);
    fn settings_get(key_ptr: u32, key_len: u32, out_ptr: u32, out_cap: u32) -> i32;
    fn fs_write(path_ptr: u32, path_len: u32, data_ptr: u32, data_len: u32) -> i32;
    fn http_request(req_ptr: u32, req_len: u32, out_ptr: u32, out_cap: u32) -> i32;
    fn settings_set(key_ptr: u32, key_len: u32, value_ptr: u32, value_len: u32) -> i32;
}
static mut ARGS: [u8; BUF_SIZE] = [0; BUF_SIZE];
static mut OUT: [u8; BUF_SIZE] = [0; BUF_SIZE];

#[no_mangle]
pub extern "C" fn mika_buffer() -> u32 {
    core::ptr::addr_of!(ARGS) as u32
}

#[no_mangle]
pub extern "C" fn mika_buffer_size() -> u32 {
    BUF_SIZE as u32
}

#[no_mangle]
pub extern "C" fn mika_tool_call(ptr: u32, len: u32) -> u64 {
    let input = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };
    let text = core::str::from_utf8(input).unwrap_or("<invalid utf-8>");
    // 验证 epoch 超时:参数里带 "loop":true 时死循环,宿主应在 timeout 后 trap
    if text.contains("\"loop\":true") {
        loop {
            core::hint::black_box(text.len());
        }
    }
    let out = if text.contains("\"mode\":\"write\"") {
        let path = b"out.txt";
        let data = b"hello from wasm";
        let n = unsafe { fs_write(path.as_ptr() as u32, path.len() as u32, data.as_ptr() as u32, data.len() as u32) };
        format!("{{\"written\":{n}}}")
    } else if text.contains("\"mode\":\"settings\"") {
        let ptr = core::ptr::addr_of_mut!(OUT) as *mut u8;
        let key = KEY.as_ptr() as u32;
        let n = unsafe { settings_get(key, KEY.len() as u32, ptr as u32, BUF_SIZE as u32) };
        if n < 0 { format!("{{\"error\":{n}}}") } else { String::from_utf8_lossy(unsafe { core::slice::from_raw_parts(ptr, n as usize) }).to_string() }
    } else if text.contains("\"mode\":\"log\"") {
        unsafe { log(1, text.as_ptr() as u32, text.len() as u32) };
        format!("{{\"ok\":true,\"logged\":{}}}", text.len())
    } else if text.contains("\"mode\":\"settings_write\"") {
        let key = between(text, "\"key\":\"").unwrap_or("greeting");
        let n = if text.contains("\"delete\":true") {
            unsafe { settings_set(key.as_ptr() as u32, key.len() as u32, 0, 0) }
        } else {
            let value = between(text, "\"value\":\"").unwrap_or("wasm-set");
            unsafe {
                settings_set(key.as_ptr() as u32, key.len() as u32, value.as_ptr() as u32, value.len() as u32)
            }
        };
        format!("{{\"settings_set\":{n}}}")
    } else if text.contains("\"mode\":\"http\"") {
        // 参数里可以带 "url" 指定地址;不带就用 example.com
        let url = between(text, "\"url\":\"").unwrap_or("https://example.com/");
        let request = format!("{{\"method\":\"GET\",\"url\":\"{url}\",\"timeoutMs\":5000}}");
        let ptr = core::ptr::addr_of_mut!(OUT) as *mut u8;
        let n = unsafe {
            http_request(request.as_ptr() as u32, request.len() as u32, ptr as u32, BUF_SIZE as u32)
        };
        if n < 0 {
            format!("{{\"http\":{n}}}")
        } else {
            let response = String::from_utf8_lossy(unsafe { core::slice::from_raw_parts(ptr, n as usize) });
            // http_request 写回的就是响应 JSON,直接嵌进结果
            format!("{{\"http\":{n},\"response\":{response}}}")
        }
    } else {
        format!("{{\"ok\":true,\"len\":{},\"echo\":\"{}\"}}", text.len(), escape(text))
    };
    let bytes = out.as_bytes();
    if bytes.len() > BUF_SIZE {
        return 0;
    }
    unsafe {
        let dst = core::ptr::addr_of_mut!(OUT) as *mut u8;
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), dst, bytes.len());
        ((dst as u64) << 32) | bytes.len() as u64
    }
}

/// 取出 `"prefix":"` 之后、下一个引号之前的内容(测试用,不做完整 JSON 解析)。
fn between<'a>(text: &'a str, prefix: &str) -> Option<&'a str> {
    let start = text.find(prefix)? + prefix.len();
    let rest = &text[start..];
    let end = rest.find('"')?;
    Some(&rest[..end])
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}
