# libmika_wasm.so 来源

- 源码:`tools/wasm`(Cargo.lock 固定 wasmtime 49.0.5、jni 0.21、sha2 0.10、serde_json 1)
- 工具链:Rust `1.99.1`(见 `tools/wasm/rust-toolchain.toml`)、NDK `28.2.13676358`、API 30、
  目标 `aarch64-linux-android`
- 构建:`ANDROID_NDK_HOME=<ndk> tools/wasm/build-native.sh`(加 `--check` 只比较不改文件)
- 产物:`app/src/main/jniLibs/arm64-v8a/libmika_wasm.so`
- SHA-256:`3cba76c7a169e5cbe57a848bf467e4563bbd968c09d479297c083e917a332f5f`
- 校验:CI 的 `wasm-native` job 用同一版本 NDK/Rust 重建并比较 SHA-256;构建路径已 remap,
  便于跨机器复现。真机 arm64 验收由使用者完成(见 `docs/dev/plugin-wasm.md`)。
