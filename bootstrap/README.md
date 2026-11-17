# Built-in environment bootstrap

The app ships a Termux-style environment (bash, clang, ssh, coreutils, …)
that is extracted on first launch into the app's private files directory:

- prefix: `files/usr` (binaries in `usr/bin`, libraries in `usr/lib`)
- home: `files/home`
- session env: `PATH=$PREFIX/bin:/system/bin`, `LD_LIBRARY_PATH=$PREFIX/lib`,
  `TERMINFO=$PREFIX/share/terminfo`, `TMPDIR=$PREFIX/tmp`, `TERM=xterm-256color`

## How the bootstrap is made

`build_bootstrap.py` assembles the bootstrap from the official Termux
package repository (https://packages.termux.dev/apt/termux-main), the same
source Termux's own `generate-bootstraps` uses:

1. The seed packages in `packages.txt` are resolved against the repo index
   (dependencies included, per architecture).
2. Each `.deb` is downloaded and its data tarball extracted; the tree is
   re-rooted from Termux's prefix to this app's prefix
   (`/data/data/jackpal.androidterm/files/usr`) and that prefix string is
   rewritten in text files (scripts, pkg-config files, configs). Binaries
   find their libraries through `LD_LIBRARY_PATH`, which the app sets.
3. Symlinks are not stored in the zip; they are recorded in
   `usr/SYMLINKS.txt` (Termux format: `target←path`, one per line) and
   recreated by the app during extraction.

Packages are Termux's published builds — this project does not
cross-compile them itself.

## Building

```sh
python3 build_bootstrap.py x86_64    # emulator (pixel34)
python3 build_bootstrap.py aarch64   # real phones
```

Output: `bootstrap/dist/bootstrap-<arch>.zip`, copied to
`term/src/main/assets/` for the app build. The zips are far over 50 MB
(the clang/LLVM set dominates), so they are **not** committed to git —
`term/src/main/assets/bootstrap-*.zip` is git-ignored and the zips are a
build artifact produced by this script. A build without them still works:
the app then falls back to `/system/bin/sh`.
