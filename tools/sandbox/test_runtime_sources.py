"""Tests for the GPL source pack generator; the network is faked, so nothing is downloaded."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("runtime-sources.py")
spec = importlib.util.spec_from_file_location("runtime_sources", SCRIPT)
runtime_sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_sources)

SDIST = b"pretend this is the fpdf2 2.9.0 sdist"
SDIST_URL = "https://example.invalid/fpdf2-2.9.0.tar.gz"


def wheel(pkg, version, license):
    return {"pkg": pkg, "version": version, "filename": f"{pkg}-{version}-py3-none-any.whl",
            "sha256": "0" * 64, "url": f"https://example.invalid/{pkg}-{version}.whl", "license": license}


class WheelsTest(unittest.TestCase):
    """The runtime bakes pip wheels into site-packages; a copyleft one owes the pack its source."""

    def collect(self, wheels, only=None, sha256=None, files=None, lock_spec="mika.pip-requires/v1"):
        """Run the wheel collection over a temporary lock; return (exit code, stdout, index, files, stderr)."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        out = Path(directory.name)
        lock = out / "pip-requires.lock"
        lock.write_text(json.dumps({"spec": lock_spec, "wheels": wheels}), encoding="utf-8")
        want = hashlib.sha256(SDIST).hexdigest() if sha256 is None else sha256
        entries = files if files is not None else [
            {"filename": "fpdf2-2.9.0.tar.gz", "packagetype": "sdist", "url": SDIST_URL,
             "digests": {"sha256": want}},
            {"filename": "fpdf2-2.9.0-py3-none-any.whl", "packagetype": "bdist_wheel",
             "url": "https://example.invalid/fpdf2-2.9.0-py3-none-any.whl", "digests": {"sha256": "1" * 64}},
        ]
        self.asked = []

        def get(url):
            self.asked.append(url)
            if url.startswith(runtime_sources.PYPI):
                return json.dumps({"urls": entries}).encode()
            return SDIST

        index, out_text, err = [], io.StringIO(), io.StringIO()
        code = 0
        with patch.object(runtime_sources, "PIP_LOCK", str(lock)), \
                patch.object(runtime_sources, "get", get), \
                patch.object(runtime_sources, "PYPI", "/pypi"), \
                contextlib.redirect_stdout(out_text), contextlib.redirect_stderr(err):
            try:
                runtime_sources.collect_wheels(str(out), only, index)
            except SystemExit as stop:
                if isinstance(stop.code, int):
                    code = stop.code
                else:
                    # sys.exit("message") exits 1 and the interpreter prints the message to stderr;
                    # in-process neither happens, so the helper does both to keep the reading honest.
                    code, _ = 1, err.write(f"{stop.code}\n")
        listing = sorted(str(p.relative_to(out)) for p in out.rglob("*") if p.is_file())
        return code, out_text.getvalue(), index, listing, err.getvalue()

    def test_a_copyleft_wheel_contributes_its_source(self):
        # fpdf2 2.9.0 declares LGPL-3.0-only in its own METADATA and ships inside the runtime, so a pack
        # that stops at packages.json leaves it out without saying anything.
        code, out, index, listing, err = self.collect([wheel("fpdf2", "2.9.0", "LGPL-3.0-only")])
        self.assertEqual(0, code, err)
        self.assertIn("fpdf2-2.9.0/fpdf2-2.9.0.tar.gz", listing)
        self.assertTrue(any("fpdf2-2.9.0.tar.gz" in line for line in index), index)
        self.assertIn("LGPL-3.0-only", " ".join(index))
        self.assertIn("fpdf2-2.9.0", out)

    def test_a_permissive_wheel_is_not_collected(self):
        # MIT, BSD and Apache wheels need no corresponding source in the pack, so this must stay quiet.
        code, out, index, listing, err = self.collect([
            wheel("requests", "2.34.2", "Apache Software License"),
            wheel("openpyxl", "3.1.5", "MIT License"),
            wheel("lxml", "6.1.3", "BSD-3-Clause"),
        ])
        self.assertEqual(0, code, err)
        self.assertEqual([], index)
        self.assertNotIn("requests-2.34.2", listing)
        self.assertEqual("", out)
        self.assertEqual([], self.asked)

    def test_the_sdist_of_the_wheel_version_is_the_one_collected(self):
        # The pair has to be one release: the sdist of a newer version would be source the APK does not ship.
        self.collect([wheel("fpdf2", "2.9.0", "LGPL-3.0-only")])
        self.assertEqual(["/pypi/fpdf2/2.9.0/json", SDIST_URL], self.asked)

    def test_the_wheel_is_never_mistaken_for_the_source(self):
        # PyPI lists the wheel in the same "urls" array; only the sdist is the corresponding source.
        code, out, index, listing, err = self.collect([wheel("fpdf2", "2.9.0", "LGPL-3.0-only")])
        self.assertEqual(0, code, err)
        self.assertNotIn("fpdf2-2.9.0/fpdf2-2.9.0-py3-none-any.whl", listing)
        self.assertNotIn("example.invalid/fpdf2-2.9.0-py3-none-any.whl", " ".join(index))

    def test_a_source_that_misses_pypis_checksum_fails(self):
        code, _, _, listing, err = self.collect([wheel("fpdf2", "2.9.0", "LGPL-3.0-only")], sha256="2" * 64)
        self.assertEqual(1, code)
        self.assertIn("fpdf2-2.9.0.tar.gz", err)
        self.assertIn("SHA-256", err)
        self.assertNotIn("fpdf2-2.9.0/fpdf2-2.9.0.tar.gz", listing)

    def test_a_copyleft_wheel_with_no_sdist_fails_instead_of_shipping_no_source(self):
        code, _, _, _, err = self.collect(
            [wheel("fpdf2", "2.9.0", "LGPL-3.0-only")],
            files=[{"filename": "fpdf2-2.9.0-py3-none-any.whl", "packagetype": "bdist_wheel",
                    "url": "https://example.invalid/fpdf2-2.9.0-py3-none-any.whl",
                    "digests": {"sha256": "1" * 64}}])
        self.assertEqual(1, code)
        self.assertIn("no source distribution", err)

    def test_only_limits_the_wheels_collected(self):
        code, out, index, listing, err = self.collect(
            [wheel("fpdf2", "2.9.0", "LGPL-3.0-only")], only=["requests"])
        self.assertEqual(0, code, err)
        self.assertEqual([], index)
        self.assertEqual("", out)
        self.assertEqual([], self.asked)

    def test_an_unknown_lock_spec_is_a_failure(self):
        code, _, _, _, err = self.collect([wheel("fpdf2", "2.9.0", "LGPL-3.0-only")],
                                          lock_spec="mika.pip-requires/v0")
        self.assertEqual(1, code)
        self.assertIn("unknown spec", err)

    def test_the_committed_wheels_are_all_classified(self):
        # The collection decides from the lock, so a wheel with no license recorded would be skipped in
        # silence; that is why build-runtime.py requires the field.
        with open(runtime_sources.PIP_LOCK, encoding="utf-8") as f:
            lock = json.load(f)
        self.assertEqual("mika.pip-requires/v1", lock["spec"])
        self.assertTrue(lock["wheels"])
        for w in lock["wheels"]:
            self.assertTrue(w.get("license"), w["pkg"])
        copyleft = [w["pkg"] for w in lock["wheels"] if runtime_sources.COPYLEFT.search(w["license"])]
        self.assertIn("fpdf2", copyleft)


if __name__ == "__main__":
    unittest.main()
