#!/usr/bin/env python3
"""把 plugins/ 下的每个插件打成可安装的 zip(manifest.json 在 zip 根,skills/scripts/docs 原样带入)。"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "dist")

# 不算安装包内容的:Python 的字节码缓存,和 Finder 在目录里留的记录。
SKIP_DIRS = ("__pycache__",)
SKIP_FILES = (".DS_Store",)


def entry_name(rel: str) -> str:
    """相对路径转成 zip 条目名,一律 `/`。

    Windows 上 os.path.relpath 返回反斜杠,而 App 的安装器把含反斜杠(或冒号)的条目判成
    不安全路径、整个包拒装(PluginInstaller.checkName),所以在写 zip 之前就换掉。
    """
    return rel.replace("\\", "/")


def plugin_entries(src: str):
    """逐个给出 (zip 条目名, 磁盘路径);__pycache__、.pyc 和 .DS_Store 不进去。"""
    for dirpath, dirnames, filenames in os.walk(src):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for fn in sorted(filenames):
            if fn.endswith(".pyc") or fn in SKIP_FILES:
                continue
            full = os.path.join(dirpath, fn)
            yield entry_name(os.path.relpath(full, src)), full


def build() -> int:
    os.makedirs(DIST, exist_ok=True)
    plugins = [d for d in os.listdir(ROOT)
               if os.path.isdir(os.path.join(ROOT, d)) and not d.startswith(".") and d not in ("dist", "examples")]
    for p in sorted(plugins):
        src = os.path.join(ROOT, p)
        manifest_path = os.path.join(src, "manifest.json")
        if not os.path.isfile(manifest_path):
            print(f"skip {p}: no manifest.json", file=sys.stderr)
            continue
        with open(manifest_path, encoding="utf-8") as f:
            m = json.load(f)
        out = os.path.join(DIST, f"{m['id']}-{m['version']}.zip")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
            for arcname, full in plugin_entries(src):
                zf.write(full, arcname)
        print(f"built {os.path.relpath(out, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(build())
