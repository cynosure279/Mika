//! ABI v1 的测试 guest:回显参数 JSON。
//! 只用于验证引擎/ABI,不随 APK 分发。

const BUF_SIZE: usize = 65536;
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
    let out = format!(
        "{{\"ok\":true,\"len\":{},\"echo\":\"{}\"}}",
        text.len(),
        escape(text)
    );
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
