#!/usr/bin/env python3
"""Build a Termux-style bootstrap zip for this app from the Termux package repo.

Downloads .deb packages (seed list in packages.txt, dependencies resolved
from the repo index), extracts them into a prefix tree, rewrites the
hardcoded Termux prefix in text files to this app's prefix, records
symlinks in SYMLINKS.txt (Termux bootstrap format: target + U+2190 + path)
and writes bootstrap-<arch>.zip with entries rooted at usr/.

Usage: build_bootstrap.py <arch> [<arch>...]   arch: aarch64 | x86_64
Output: bootstrap/dist/bootstrap-<arch>.zip and a copy in
term/src/main/assets/ (git-ignored, consumed by the app build).
"""

import io
import lzma
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO_BASE = "https://packages.termux.dev/apt/termux-main"
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
APP_PREFIX = "/data/data/jackpal.androidterm/files/usr"
ARROW = "←"

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
ASSETS = ROOT.parent / "term" / "src" / "main" / "assets"


def fetch(url):
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "build-bootstrap"})
    with urllib.request.urlopen(req) as resp:
        return resp.read()


def load_index(arch):
    base = f"{REPO_BASE}/dists/stable/main/binary-{arch}"
    try:
        raw = fetch(f"{base}/Packages.xz")
        text = lzma.decompress(raw).decode()
    except Exception:
        text = fetch(f"{base}/Packages").decode()
    packages = {}
    for stanza in text.split("\n\n"):
        fields = {}
        key = None
        for line in stanza.splitlines():
            if line.startswith(" ") and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, value = line.split(":", 1)
                fields[key.strip()] = value.strip()
        if "Package" in fields:
            packages.setdefault(fields["Package"], []).append(fields)
    return packages


def parse_depends(value):
    names = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        alt = part.split("|")[0].strip()
        alt = re.sub(r"\s*\(.*\)\s*$", "", alt).strip()
        if alt:
            names.append(alt)
    return names


def resolve(index, seeds):
    chosen = {}
    queue = list(seeds)
    while queue:
        name = queue.pop()
        if name in chosen:
            continue
        entries = index.get(name)
        if not entries:
            raise SystemExit(f"package not found in repo index: {name}")
        entry = entries[0]
        chosen[name] = entry
        for dep_field in ("Depends", "Pre-Depends"):
            if dep_field in entry:
                queue.extend(parse_depends(entry[dep_field]))
    return chosen


def extract_deb(deb_bytes, dest_root):
    # .deb is an ar archive: parse it minimally to find data.tar.*
    assert deb_bytes[:8] == b"!<arch>\n", "not a deb (ar) file"
    pos = 8
    data = None
    while pos < len(deb_bytes):
        header = deb_bytes[pos:pos + 60]
        name = header[0:16].decode().strip()
        size = int(header[48:58].decode().strip())
        body = deb_bytes[pos + 60:pos + 60 + size]
        if name.startswith("data.tar"):
            data = (name, body)
            break
        pos += 60 + size + (size % 2)
    if data is None:
        raise SystemExit("deb without data.tar")
    name, body = data
    mode = "r:xz" if name.endswith(".xz") else "r:gz" if name.endswith(".gz") else "r:*"
    strip = "data/data/com.termux/files/"
    with tarfile.open(fileobj=io.BytesIO(body), mode=mode) as tar:
        for member in tar.getmembers():
            path = member.name.lstrip("./")
            if not path.startswith(strip):
                continue
            rel = path[len(strip):]
            if not rel:
                continue
            target = dest_root / rel
            if member.issym() or member.islnk():
                (dest_root / ".links").mkdir(exist_ok=True)
                with open(dest_root / ".links" / "list.txt", "a") as fh:
                    fh.write(f"{member.linkname}{ARROW}{rel}\n")
            elif member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isreg():
                target.parent.mkdir(parents=True, exist_ok=True)
                src = tar.extractfile(member)
                with open(target, "wb") as out:
                    shutil.copyfileobj(src, out)
                target.chmod(member.mode & 0o777)


def rewrite_prefix(tree):
    old = TERMUX_PREFIX.encode()
    new = APP_PREFIX.encode()
    changed = 0
    for path in tree.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            data = path.read_bytes()
        except OSError:
            continue
        if old not in data:
            continue
        head = data[:8192]
        if b"\x00" in head:
            continue  # binary: leave alone, paths inside are advisory only
        path.write_bytes(data.replace(old, new))
        changed += 1
    return changed


def build_zip(tree, links_file, out_path):
    symlinks = []
    if links_file.exists():
        for line in links_file.read_text().splitlines():
            if ARROW in line:
                target, rel = line.split(ARROW, 1)
                symlinks.append((target, rel))
    executables = []
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(tree.rglob("*")):
            if path.is_dir():
                continue
            rel = "usr/" + path.relative_to(tree).as_posix()
            info = zipfile.ZipInfo(rel)
            info.external_attr = (path.stat().st_mode & 0o777) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, path.read_bytes())
            if path.stat().st_mode & 0o111:
                executables.append(path.relative_to(tree).as_posix())
        info = zipfile.ZipInfo("usr/EXECUTABLES.txt")
        info.external_attr = 0o644 << 16
        zf.writestr(info, "".join(rel + "\n" for rel in executables))
        payload = "".join(f"{target}{ARROW}{rel}\n" for target, rel in sorted(symlinks, key=lambda s: s[1]))
        info = zipfile.ZipInfo("usr/SYMLINKS.txt")
        info.external_attr = 0o644 << 16
        zf.writestr(info, payload)
    return len(symlinks)


def build(arch):
    print(f"== bootstrap for {arch}")
    index = load_index(arch)
    seeds = [line.strip() for line in (ROOT / "packages.txt").read_text().splitlines()
             if line.strip() and not line.startswith("#")]
    chosen = resolve(index, seeds)
    print(f"  resolved {len(chosen)} packages: {' '.join(sorted(chosen))}")
    work = DIST / f"work-{arch}"
    shutil.rmtree(work, ignore_errors=True)
    tree = work / "usr"
    tree.mkdir(parents=True)
    total = 0
    for name in sorted(chosen):
        entry = chosen[name]
        deb = fetch(f"{REPO_BASE}/{entry['Filename']}")
        total += len(deb)
        extract_deb(deb, work)
    (tree / "tmp").mkdir(exist_ok=True)
    changed = rewrite_prefix(tree)
    print(f"  downloaded {total // (1024 * 1024)} MiB of debs, rewrote prefix in {changed} text files")
    DIST.mkdir(exist_ok=True)
    out = DIST / f"bootstrap-{arch}.zip"
    count = build_zip(tree, work / ".links" / "list.txt", out)
    size = out.stat().st_size
    print(f"  {out.name}: {size // (1024 * 1024)} MiB, {count} symlinks")
    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy(out, ASSETS / out.name)
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    arches = sys.argv[1:] or ["aarch64", "x86_64"]
    for a in arches:
        build(a)
