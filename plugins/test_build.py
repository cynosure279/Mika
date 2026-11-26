"""plugins/build.py 打出的包要能装进 App。

App 的安装器(PluginInstaller.checkName)把含反斜杠或冒号的条目判成不安全路径,整包拒装;
而 Windows 上 os.path.relpath 返回的正是反斜杠。另外 __pycache__/.pyc 和 Finder 的
.DS_Store 不是安装包的内容,却会被 os.walk 一起带进去。

跑法:python3 -m unittest discover -s plugins -v
"""
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build  # noqa: E402


def make_plugin(root: str, name: str = "com.probe.demo", version: str = "1.0.0") -> str:
    """造一个带脚本、字节码缓存和 Finder 记录的最小插件。"""
    src = os.path.join(root, name)
    os.makedirs(os.path.join(src, "scripts"))
    os.makedirs(os.path.join(src, "__pycache__"))
    manifest = '{"schema":1,"id":"%s","name":"probe","version":"%s","kind":"capability"}\n' % (name, version)
    with open(os.path.join(src, "manifest.json"), "w", encoding="utf-8") as f:
        f.write(manifest)
    with open(os.path.join(src, "scripts", "run.sh"), "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\necho hi\n")
    with open(os.path.join(src, "__pycache__", "run.cpython-312.pyc"), "wb") as f:
        f.write(b"\x00")
    with open(os.path.join(src, ".DS_Store"), "wb") as f:
        f.write(b"\x00")
    return src


class EntryNameTest(unittest.TestCase):
    def test_windows_relative_path_becomes_forward_slashes(self):
        self.assertEqual("scripts/rewrite_prompt.sh", build.entry_name("scripts\\rewrite_prompt.sh"))

    def test_posix_relative_path_is_left_alone(self):
        self.assertEqual("scripts/rewrite_prompt.sh", build.entry_name("scripts/rewrite_prompt.sh"))


class PackageTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name
        make_plugin(self.root)
        module_root, module_dist = build.ROOT, build.DIST
        build.ROOT = self.root
        build.DIST = os.path.join(self.root, "dist")
        self.addCleanup(self._restore, module_root, module_dist)

    @staticmethod
    def _restore(module_root, module_dist):
        build.ROOT, build.DIST = module_root, module_dist

    def _names(self):
        zips = [f for f in os.listdir(build.DIST) if f.endswith(".zip")]
        self.assertEqual(1, len(zips), zips)
        with zipfile.ZipFile(os.path.join(build.DIST, zips[0])) as zf:
            return zf.namelist()

    def test_manifest_sits_at_the_zip_root(self):
        self.assertEqual(0, build.build())
        names = self._names()
        self.assertIn("manifest.json", names)
        self.assertIn("scripts/run.sh", names)

    def test_cache_and_finder_files_stay_out_of_the_package(self):
        self.assertEqual(0, build.build())
        names = self._names()
        left_overs = [n for n in names if "__pycache__" in n or n.endswith(".pyc") or n == ".DS_Store"]
        self.assertEqual([], left_overs)

    def test_windows_separators_never_reach_the_zip(self):
        # 安装器拒收含反斜杠的条目,而 Windows 上 os.path.relpath 给的就是反斜杠。
        # 这里把它打桩成 Windows 的返回值,让真正的打包路径自己走一遍。
        real = build.os.path.relpath

        def windows_relpath(path, start):
            return real(path, start).replace("/", "\\")

        build.os.path.relpath = windows_relpath
        self.addCleanup(setattr, build.os.path, "relpath", real)
        self.assertEqual(0, build.build())
        names = self._names()
        self.assertEqual([], [n for n in names if "\\" in n])
        self.assertIn("scripts/run.sh", names)

    def test_every_entry_is_a_relative_slash_path(self):
        # checkName 拒收绝对路径、反斜杠、冒号和 `..` 段
        self.assertEqual(0, build.build())
        for name in self._names():
            self.assertNotIn("\\", name)
            self.assertNotIn(":", name)
            self.assertFalse(name.startswith("/"), name)
            self.assertNotIn("..", name.split("/"))


if __name__ == "__main__":
    unittest.main()
