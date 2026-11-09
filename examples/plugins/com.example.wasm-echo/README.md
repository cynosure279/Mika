# Wasm 回显示例

用 Wasm(ABI v1)回显工具参数的最小插件包。**只在调试包(arm64)可用**:发布包暂不接受 wasm 插件。

## 目录

```
manifest.json          工具声明 `"wasm": "tools/echo.wasm"`
tools/echo.wasm        编好的 guest(可直接安装;19 KB)
guest/                 示例源码(Rust)
build.py               打 zip
```

## 构建与安装

```bash
cd guest
cargo build --release --target wasm32-unknown-unknown
cp target/wasm32-unknown-unknown/release/mika_wasm_echo.wasm ../tools/echo.wasm
cd ..
python3 build.py        # 产物在 dist/
```

在 App 里:设置 › 高级 › 插件 › 安装插件,选 `dist/com.example.wasm-echo-0.1.0.zip`。模型会看到 `mcp_com_example_wasm_echo__echo` 工具。

## 协议

见 [docs/dev/plugin-wasm-abi.md](../../../docs/dev/plugin-wasm-abi.md)。
