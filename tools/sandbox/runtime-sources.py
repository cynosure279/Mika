#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Collect the corresponding source for every copyleft package in the bundled runtime (for the GPL source pack).

Usage:
  runtime-sources.py OUT_DIR [--only ORIGIN ...]

Reads app/src/main/assets/runtime/packages.json (written by build-runtime.py). For each package whose license names
GPL, LGPL or MPL, takes its Alpine build recipe at the exact aports commit recorded in the package
(APKBUILD and local patches), plus every upstream source file listed in the recipe's sha512sums, and checks each
file against that SHA-512. Writes OUT_DIR/<origin>-<version>/ and OUT_DIR/SOURCES.txt (file, SHA-512, where from).

The runtime also bakes pip wheels into site-packages, and packages.json lists apk packages only, so a copyleft
wheel is collected here as well: runtime/pip-requires.lock records each wheel's license, and for every one that
names GPL, LGPL or MPL its source distribution at the same version comes from PyPI, checked against the SHA-256
PyPI publishes for it. A wheel is the installed layout and the sdist of the same version is its corresponding
source, so the pair is one release rather than whatever is current when the pack is built. Those land in
OUT_DIR/<package>-<version>/.

The recipe comes from the aports mirror on GitHub; upstream files come from Alpine's distfiles mirror. PyPI is
reached for the wheels.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "packages.json")
PIP_LOCK = os.path.join(ROOT, "tools", "sandbox", "runtime", "pip-requires.lock")
RECIPES = "https://raw.githubusercontent.com/alpinelinux/aports"
PYPI = "https://pypi.org/pypi"
COPYLEFT = re.compile(r"\b(L?GPL|MPL)")


def get(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def sha512sums(apkbuild):
    m = re.search(r'^sha512sums="(.*?)"', apkbuild, re.S | re.M)
    if not m:
        sys.exit(f"runtime-sources: no sha512sums in APKBUILD")
    out = []
    for line in m.group(1).strip().splitlines():
        parts = line.split()
        if len(parts) == 2:
            out.append((parts[1], parts[0]))
    return out


def sdist(pkg, version):
    """The source distribution PyPI publishes for exactly this version, with PyPI's own SHA-256.

    Pinned to the same version as the wheel on purpose: the shipped runtime is one release, and the source
    that corresponds to it is that release's, not the current one.
    """
    try:
        meta = json.loads(get(f"{PYPI}/{pkg}/{version}/json"))
    except urllib.error.HTTPError as e:
        sys.exit(f"runtime-sources: {pkg} {version}: PyPI does not publish that release ({e.code})")
    for f in meta.get("urls", []):
        if f.get("packagetype") == "sdist":
            return f["filename"], f["url"], f["digests"]["sha256"]
    sys.exit(f"runtime-sources: {pkg} {version} has no source distribution on PyPI; the runtime ships it as a "
             f"wheel, so its corresponding source cannot be collected")


def collect_wheels(out, only, index):
    """Collect the source of every copyleft wheel the runtime bakes into site-packages.

    build-runtime.py unpacks these into site-packages, so they are part of what the APK ships, but
    packages.json lists apk packages only and the collection above walks packages.json -- a copyleft wheel
    was therefore never collected and nothing said so.
    """
    lock = json.load(open(PIP_LOCK, encoding="utf-8"))
    if lock.get("spec") != "mika.pip-requires/v1":
        sys.exit(f"runtime-sources: {PIP_LOCK}: unknown spec {lock.get('spec')}")
    for w in lock.get("wheels", []):
        if not COPYLEFT.search(w.get("license", "")):
            continue
        if only and w["pkg"] not in only:
            continue
        name, url, want = sdist(w["pkg"], w["version"])
        data = get(url)
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            sys.exit(f"runtime-sources: {name}: SHA-256 {got} differs from the {want} PyPI publishes")
        d = os.path.join(out, f"{w['pkg']}-{w['version']}")
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, name), "wb").write(data)
        index.append(f"{w['pkg']}-{w['version']}/{name}  sha256:{want}  {url}")
        index.append(f"# {w['pkg']}: baked into site-packages as {w['filename']} ({w['license']}), "
                     f"source of the same version")
        print(f"{w['pkg']}-{w['version']}: ok (wheel, {w['license']})")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out")
    ap.add_argument("--only", nargs="*")
    a = ap.parse_args()
    m = json.load(open(MANIFEST, encoding="utf-8"))
    branch = "v" + ".".join(m["alpine"].split(".")[:2])
    distfiles = f"https://distfiles.alpinelinux.org/distfiles/{branch}"
    recipes = {}
    for p in m["packages"]:
        if COPYLEFT.search(p["license"]):
            recipes.setdefault((p["repo"], p["origin"], p["commit"]), []).append(p)
    index = []
    for (repo, origin, commit), pkgs in sorted(recipes.items(), key=lambda kv: kv[0][1]):
        if a.only and origin not in a.only:
            continue
        version = pkgs[0]["version"]
        d = os.path.join(a.out, f"{origin}-{version}")
        os.makedirs(d, exist_ok=True)
        base = f"{RECIPES}/{commit}/{repo}/{origin}"
        apkbuild = get(f"{base}/APKBUILD")
        open(os.path.join(d, "APKBUILD"), "wb").write(apkbuild)
        index.append(f"{origin}-{version}/APKBUILD  {hashlib.sha512(apkbuild).hexdigest()}  {base}/APKBUILD")
        for name, want in sha512sums(apkbuild.decode()):
            data, src = None, None
            for url in (f"{base}/{name}", f"{distfiles}/{name}"):
                try:
                    data, src = get(url), url
                except urllib.error.HTTPError:
                    continue
                if hashlib.sha512(data).hexdigest() == want:
                    break
                data = None
            if data is None:
                sys.exit(f"runtime-sources: {origin}: {name} not found with SHA-512 {want}")
            open(os.path.join(d, name), "wb").write(data)
            index.append(f"{origin}-{version}/{name}  {want}  {src}")
        names = ", ".join(sorted(f"{p['name']} {p['version']} ({p['license']})" for p in pkgs))
        index.append(f"# {origin}: aports {repo}/{origin} at {commit}; built packages: {names}")
        print(f"{origin}-{version}: ok")
    collect_wheels(a.out, a.only, index)
    open(os.path.join(a.out, "SOURCES.txt"), "w", encoding="utf-8").write("\n".join(index) + "\n")


if __name__ == "__main__":
    main()
