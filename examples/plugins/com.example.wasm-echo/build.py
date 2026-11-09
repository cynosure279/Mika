#!/usr/bin/env python3
"""把示例打成可安装的插件 zip。"""
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent
dist = root / "dist"
dist.mkdir(exist_ok=True)
out = dist / "com.example.wasm-echo-0.1.0.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(root / "manifest.json", "manifest.json")
    z.write(root / "tools" / "echo.wasm", "tools/echo.wasm")
print(out)
