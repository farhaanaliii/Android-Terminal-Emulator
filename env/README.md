# Built-in environment: BusyBox

The app ships a small built-in shell environment whose core is BusyBox,
built as part of this project's own NDK/CMake build — there is no
separate build script and no prebuilt binary from anywhere else.

## What is built, and from what

- **BusyBox 1.37.0**, fetched by CMake (ExternalProject in
  `term/src/main/jni/CMakeLists.txt`) from the upstream git repository
  **https://git.busybox.net/busybox** at the pinned tag **`1_37_0`**
  (GitHub mirror, if ever needed: https://github.com/mirror/busybox).
- The fetch is compiled with BusyBox's own Kbuild, driven by CMake with
  a `CROSS_COMPILE` prefix pointing at the NDK clang for the ABI being
  built (x86_64 and arm64-v8a), at API level 26. Host build tools needed
  beyond the NDK and CMake: git, make, a C compiler for Kbuild's host
  tools, and bzip2 (Kbuild's `scripts/mkconfigs` checks for it).
- Configuration: Kbuild `defconfig` plus the committed fragment
  `env/busybox.config.fragment` (PIE, standalone shell, prefer applets),
  applied deterministically by `env/apply_busybox_config.cmake`. The
  exact full configuration this produces is committed as
  `env/busybox-1.37.0.config`.
- The output is a plain executable named `busybox`. `env/package_env.cmake`
  packages it into a per-ABI asset zip (`busybox-x86_64.zip`,
  `busybox-aarch64.zip`) holding `usr/bin/busybox`, the applet symlink
  list `usr/SYMLINKS.txt` and `usr/EXECUTABLES.txt` (the same format the
  bootstrap builder in `bootstrap/` uses), the license text under
  `usr/share/licenses/busybox/`, and — on x86_64 — the NDK sysroot
  headers and API-level libraries under `usr/include` and `usr/lib`.
  The aarch64 sysroot is a follow-up; its zip ships the binary and links.
- On first launch the app extracts the zip into its `files/` directory,
  giving the environment layout `files/usr/{bin,lib,include,tmp}` and
  `files/home`, creates the applet symlinks (sh, ls, cat, ...) in
  `files/usr/bin`, and the BusyBox shell option runs
  `$PREFIX/bin/busybox sh` with `HOME`, `PREFIX`, `PATH`, `TMPDIR` and
  `TERM` set.
- The NDK sysroot libraries are staged in the NDK's own layout,
  `usr/lib/<triple>/<api>/`, not flattened into `usr/lib`. They are
  link-time stubs: a binary whose runtime library search path includes
  the directory holding them would load the stub `libc.so`/`libdl.so`
  and crash before `main`. `usr/lib` itself holds only real runtime
  libraries (OpenSSL's, later the compiler's).

## GNU bash

- **bash 5.3**, from the upstream release tarball
  **https://ftp.gnu.org/gnu/bash/bash-5.3.tar.gz**
  (SHA-256 `0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba`),
  fetched and cross-compiled by CMake (ExternalProject
  `bash_external`) with the NDK clang at API 26, installed as the real
  file `usr/bin/bash`.
- Configure: `--without-bash-malloc --disable-nls` plus the two
  cross-compilation cache answers `bash_cv_dev_fd=standard` (/dev/fd
  works on Android) and `bash_cv_getcwd_malloc=yes`. Android has no
  termcap library, so bash automatically falls back to its bundled
  libtermcap; its bundled readline is used too. No source patches or
  shims were needed. The binary links only against libc/libdl.
- The **Bash shell** setting runs `$PREFIX/bin/bash -l` in `files/home`
  with `HOME`, `PREFIX`, `PATH`, `LD_LIBRARY_PATH`, `TMPDIR`, `LANG`
  and `TERM` set, and takes priority over the BusyBox shell setting.
- License: GNU GPL version 3 — `env/licenses/bash-GPL-3.0.txt`, also
  shipped in the zip under `usr/share/licenses/bash/`.

## OpenSSH clients

- **OpenSSH 10.2p1** (portable), from
  **https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.2p1.tar.gz**
  (SHA-256 `ccc42c0419937959263fa1dbd16dafc18c56b984c03562d2937ce56a60f798b2`),
  cross-compiled by CMake (ExternalProject `openssh_external`).
  Only the client programs are built and shipped — `ssh`, `scp`,
  `sftp`, `ssh-keygen`; there is no sshd (Android cannot provide its
  privilege-separation user or account database).
- Dependencies, built the same way into a per-ABI deps prefix inside
  the build tree: **zlib 1.3.1**
  (https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz,
  SHA-256 `9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23`,
  linked statically) and **OpenSSL 3.5.4**
  (https://www.openssl.org/source/openssl-3.5.4.tar.gz,
  SHA-256 `967311f84955316969bdb1d8d4b983718ef42338639c621ec4c34fddef355e99`).
  OpenSSL's `libcrypto.so`/`libssl.so` ship in `usr/lib`; the
  clients find them through an absolute rpath into the app prefix
  (fixed by the applicationId — `$ORIGIN` does not survive the layers
  of make and shell between CMakeLists.txt and the linker).
- Configure disables the account/session databases Android lacks
  (`--disable-utmp/utmpx/wtmp/wtmpx/lastlog/pututline/pututxline`,
  `--without-shadow`) and points the privilege-separation directory
  at `$PREFIX/var/empty`. The cache answer `ac_cv_func_bzero=yes` is
  preset: bionic's `bzero` is a macro, so configure's link test fails
  for it and the compat fallback would collide.
- Source adjustments (applied to the pristine tarball at build time
  by `env/openssh_patch.cmake`, plus `env/openssh_config_fix.cmake`
  after configure):
  1. `openbsd-compat/getrrsetbyname.c` is replaced by
     `env/openssh_getrrsetbyname.c`, a stub that always reports
     failure: bionic has no `getrrsetbyname` and no BSD resolver
     state to implement it with. Only `VerifyHostKeyDNS` (SSHFP
     lookup) is affected.
  2. `openbsd-compat/explicit_bzero.c`: the volatile-`bzero`-pointer
     indirection is rerouted through `memset`, because bionic's
     `bzero` macro has no address to take.
  3. `config.h`: `HAVE_ATTRIBUTE__SENTINEL__` is defined manually.
     Configure's probe fails on Android and OpenSSH's empty fallback
     macro breaks bionic's own `__sentinel__(1)` uses; clang accepts
     the attribute.
  4. `misc.c` `tilde_expand()`: for the caller's own `~`, `$HOME`
     wins over the passwd entry. Bionic's passwd entry for an app
     uid names `/data` as the home directory, so without this the
     clients would look for keys and known_hosts under `/data/.ssh`
     instead of the app's home. (The user config file path in ssh.c
     still comes from the passwd entry; pass `-F $HOME/.ssh/config`
     if you keep one.)
  5. `hostfile.c`: when the sandbox blocks the hardlink backup of
     known_hosts (`EPERM`/`EACCES` — Android denies hardlink
     creation to apps), the `.old` backup is skipped instead of
     failing the whole host-key update; the atomic replace still
     happens.
- Licenses: OpenSSH's BSD-style LICENCE
  (`env/licenses/openssh-LICENSE.txt`), OpenSSL's Apache-2.0
  (`env/licenses/openssl-Apache-2.0.txt`), zlib's license
  (`env/licenses/zlib-LICENSE.txt`) — all shipped in the zip under
  `usr/share/licenses/{openssh,openssl,zlib}/`.

## License

BusyBox is licensed under the GNU General Public License, version 2.
The full license text is in `env/licenses/busybox-GPL-2.0.txt` and is
also shipped inside the environment zip. The corresponding source for
the binary as built here is the upstream BusyBox git repository above
at tag `1_37_0`, configured with `env/busybox-1.37.0.config`.
