# Android-Terminal-Emulator — Modernization Analysis

Date: 2026-11-16 · Branch: `modernization` (from `master` @ 18ac677c) · Scope: analysis only, no code changes.

## TL;DR

The build toolchain was already bumped (AGP 9.3.2 / Gradle 9.5.0 / Java 21 / compileSdk 34),
so this is **not** a from-scratch rescue. The outdated layer is the app code and manifests:
framework `android.preference` + `PreferenceActivity`, framework ActionBar, pre-scoped-storage
file access, no runtime permission requests anywhere, no `POST_NOTIFICATIONS`, manifest
`package=` attributes alongside Gradle namespaces, and dead pre-API-21 compatibility shims.
Native code is in comparatively good shape: CMake (not ndk-build) and both `.so`s already
link with `-Wl,-z,max-page-size=16384` (16 KB page ready).

## 1. Build system — current state

| Item | Value | Where |
|---|---|---|
| AGP | 9.3.2 | root `build.gradle` classpath |
| Gradle wrapper | 9.5.0-bin | `gradle/wrapper/gradle-wrapper.properties` (jar present) |
| Repositories | `google()`, `mavenCentral()` (no jcenter) | root `build.gradle` |
| compileSdk | 34 (all 6 modules) | every module `build.gradle` |
| minSdk | 21 (all modules) | every module `build.gradle` |
| targetSdk | 34 (all modules, incl. libraries — libraries should not set targetSdk) | every module `build.gradle` |
| Java | source/target 21 (all modules) | `compileOptions` |
| NDK | pinned 27.1.12297006 via `NDK_VERSION` in `gradle.properties` (term, libtermexec) | |
| AndroidX | `android.useAndroidX=true`, `android.enableJetifier=true` (jetifier is unnecessary — no legacy support deps remain — and is removed in newer AGP), `android.nonTransitiveRClass=true` | `gradle.properties` |
| Dependencies | almost none: `androidx.annotation:annotation:1.7.0` (libtermexec) + project deps; no support library, no Kotlin | |
| Signing | no signingConfig anywhere; release builds are `minifyEnabled false` | |
| ProGuard | build files reference `proguard-rules.txt`, but `term/` contains no such file (only `libtermexec/proguard-rules.pro` exists, and libtermexec references the `.txt` name too) — release build landmine | term, emulatorview, libtermexec, samples |

Modules in `settings.gradle`: `:term` (app), `:emulatorview` (library), `:libtermexec` (library, AIDL + JNI), `:samples:intents`, `:samples:pathbroadcasts`, `:samples:telnet` (apps).

App version lives only in the manifest (`versionCode 71`, `versionName 1.0.70`); `term/build.gradle` defaultConfig has no versionCode/versionName. Library manifests also carry stale versionCode/versionName.

## 2. SDK levels in manifests

No `uses-sdk` in any shipped manifest (levels come from Gradle). All 6 manifests still declare
`package="..."` even though every module also sets `namespace` in Gradle — the manifest
attribute is deprecated/ignored with namespace and should be deleted.
Exception: legacy `tests/emulatorview-test/AndroidManifest.xml` has `uses-sdk minSdk 3 / targetSdk 11`.

## 3. Manifests (term app, 150 lines)

- `android:exported` is set on all intent-filter components (12 `exported` attributes repo-wide) — targetSdk 31+ requirement already met.
- Permissions declared: INTERNET, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE (both obsolete under scoped storage; WRITE is a no-op for targetSdk 30+), ACCESS_SUPERUSER (non-standard, Play-incompatible), WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE.
- **Missing: `POST_NOTIFICATIONS`** — the TermService foreground-service notification cannot be shown on Android 13+ (targetSdk 33+) without it, and it is never requested at runtime either.
- TermService: `foregroundServiceType="specialUse"` + the required special-use subtype meta-data are already present — good for targetSdk 34.
- Custom permissions (`RUN_SCRIPT`, `APPEND_TO_PATH`, `PREPEND_TO_PATH`) use `android.permission-group.SYSTEM_TOOLS` and `protectionLevel="dangerous"`.
- `configChanges="keyboard|keyboardHidden|orientation"` — missing `screenSize|smallestScreenSize|screenLayout|density`, so rotation/resizing still recreates activities on modern devices; also conflicts with targetSdk 35/36 large-screen behaviour changes.
- Theme parent is `@android:style/Theme.Light` (framework theme, `term/src/main/res/values/styles.xml`).
- Samsung multiwindow meta-data (`com.sec.android.support.multiwindow`) is dead weight.

## 4. Java code — 68 files, all Java, no Kotlin

Counts are files containing at least one hit, from grep over `*.java`:

| Category | Files | Notes / examples |
|---|---|---|
| Framework preferences: `android.preference.*`, `PreferenceActivity` | 7 | `TermPreferences extends PreferenceActivity` (deprecated since API 29); `PreferenceManager` used in Term, TermService, RemoteInterface, AddShortcut, FSNavigator, ShortcutEncryption. Migrate to AndroidX Preference library. |
| Framework ActionBar (`android.app.ActionBar`) | 6 | Replace with AppCompat/Material toolbar or drop with a custom view. Ties into the framework `Theme.Light`. |
| Scoped-storage violations | 2 + manifest | `Environment.getExternalStorageDirectory()` in `shortcuts/AddShortcut.java:98`, `shortcuts/FSNavigator.java:60`; READ/WRITE_EXTERNAL_STORAGE in the manifest; **zero** `requestPermissions`/`checkSelfPermission` calls in the whole repo, so all runtime-permission-gated features silently fail on modern Android. |
| Dead compat shims | 4+ | `emulatorview/.../compat/AndroidCompat.java` and `term/.../compat/AndroidCompat.java` parse `Build.VERSION.SDK` (String field, deprecated in API 29); `ClipboardManagerCompat` factory stack wraps `android.text.ClipboardManager` (pre-API 11 API) — all pointless at minSdk 21. Version checks like `SDK_INT >= 12` and `@TargetApi(HONEYCOMB_MR1)` in `libtermexec/TermExec.java`, `GenericTermSession.java:224`. |
| Deprecated `new Handler()` (implicit Looper) | 7 hits | Use `new Handler(Looper.getMainLooper())` or executors. |
| WifiLock legacy mode | 1 | `Term.java:379-380`: `WifiManager.WIFI_MODE_FULL` (deprecated API 29); WifiLock itself is deprecated in favour of other mechanisms and needs review. |
| `startActivityForResult`/`onActivityResult` | 4 files | Migrate to Activity Result API when touching those flows. |
| Foreground service | 2 files, 5 `startForeground(` hits | `TermService` already branches by SDK (O, M, UPSIDE_DOWN_CAKE) and creates a NotificationChannel — structurally OK; only the notification permission is missing. |
| `PendingIntent` | 2 hits, with FLAG_IMMUTABLE/FLAG_MUTABLE present | OK for targetSdk 31+. |
| Process execution | 3 files | `Runtime.exec`/`ProcessBuilder` plus JNI fork/exec — expected for a terminal; recently hardened on master. |
| AsyncTask | 0 | None — good. |
| Support library imports | 0 | Fully off `android.support`; only `androidx.annotation.NonNull` is imported anywhere (1 file). Jetifier can go. |
| Notification.Builder (framework) | 1 file | Move to `NotificationCompat` if appcompat is adopted, or keep framework builder + channel (already done). |

## 5. libtermexec / term JNI-NDK

- Build method: **CMake** (`src/main/jni/CMakeLists.txt` in both `term` and `libtermexec`), `cmake_minimum_required 3.22.1`. No ndk-build/Android.mk.
- Sources: term = `common.cpp`, `termExec.cpp`, `fileCompat.cpp` → `libjackpal-androidterm5.so`; libtermexec = `process.cpp` → `libjackpal-termexec2.so`. One AIDL interface: `libtermexec/src/main/aidl/.../v1/ITerminal.aidl` (`aidl true` build feature).
- ABIs: **no `abiFilters` anywhere** — builds whatever the NDK default set is. Should be pinned explicitly to `arm64-v8a`, `armeabi-v7a`, `x86_64` (and drop 32-bit x86/armeabi-v7a decision made deliberately, see plan).
- 16 KB page size: **already handled** — both CMakeLists pass `-Wl,-z,max-page-size=16384`. This is the main targetSdk 35 native blocker and it's done.
- Remaining native risks for targetSdk 35/36: none structural found; re-verify ELF alignment (`llvm-readelf -l` LOAD alignment 2^14) on the built .so files once a build runs, and keep NDK pin in step with AGP 9 requirements.

## 6. Tests

- `tests/` at repo root is **not wired into Gradle** (not in settings.gradle): `tests/emulatorview-test` (3 JUnit/AndroidTestCase-era classes: InputConnectionTest, ModifierKeyTest, TermKeyListenerTest) plus raw fixture dirs (controlSequences, fullWidthText, issue145, issue149, wideChars). Its manifest still says minSdk 3 / targetSdk 11.
- Only in-module test: `libtermexec/src/androidTest/.../ApplicationTest.java` — the old template `ApplicationTestCase`, effectively a stub.
- No unit tests (`src/test`) anywhere. No CI config in repo.

## 7. Build attempt on this VM — not run, and why

JDK 21 exists (`~/workspace/tools/jdk-21.0.13+9`) and an Android SDK exists
(`~/workspace/android-sdk`), but it does **not** match the project: SDK has platforms
android-35/36 only (project wants compileSdk 34), NDK 27.0.12077973 (project pins
27.1.12297006), and no Gradle 9.5.0 distribution is cached. A build would require
downloading the Gradle distribution, AGP deps, platform 34 and NDK 27.1 — out of scope
for this analysis run (no downloads). First build should happen after Phase 0 below,
with the SDK numbers aligned.

## 8. Proposed targets

| Knob | Now | Proposed | Why |
|---|---|---|---|
| compileSdk | 34 | **36** | Current platform; AGP 9.3 targets it; unlocks current lint/API checks. |
| targetSdk | 34 | **35 first, then 36** | 35 is a small step (edge-to-edge enforcement is the big one for this app); 36 adds large-screen/orientation restrictions that fight this app's configChanges model — do it as its own step. Play requires 35+ for updates in 2026. |
| minSdk | 21 | **26** | Android 8.0 floor drops every pre-Oreo shim (notification channels, FGS start rules become unconditional), covers ~95%+ of active devices, and a terminal's user base skews new. Staying at 21 is defensible but keeps the compat code alive; 26 is the sweet spot. (24 is the conservative alternative.) |
| Java | 21 | **stay 21** | Already current; AGP 9 + JDK 21 toolchain is coherent. No Kotlin migration in this project phase — 68 Java files work; revisit only if new UI is written. |
| NDK | 27.1 pinned | keep pinned, bump with AGP | Already fine. |

## 9. Phased plan (by risk, low → high)

**Phase 0 — build hygiene (no behaviour change).** Align SDK/NDK on the build machine, get a green `assembleDebug` baseline + record it. Remove manifest `package=` attributes and library versionCode/versionName; move app version into `term/build.gradle`; remove library `targetSdk` declarations; delete `android.enableJetifier`; fix the proguard-rules.txt/.pro mismatch; pin `abiFilters`. Wire `tests/emulatorview-test` into `emulatorview` as androidTest or formally drop it.

**Phase 1 — permissions & storage (targetSdk 34 correctness).** Add `POST_NOTIFICATIONS` + runtime request in TermService flow; add a runtime-permission helper and request storage access properly or remove READ/WRITE_EXTERNAL_STORAGE; replace `Environment.getExternalStorageDirectory()` in AddShortcut/FSNavigator with SAF (`ACTION_OPEN_DOCUMENT_TREE`) or app-specific dirs; drop ACCESS_SUPERUSER.

**Phase 2 — dead code & deprecated framework APIs.** Delete both AndroidCompat shims and the ClipboardManagerCompat stack (minSdk makes them identity functions); raise/remove all `SDK_INT >= 12`-era branches; `new Handler()` → explicit Looper; WifiLock mode review; `startActivityForResult` → Activity Result API in the 4 affected files.

**Phase 3 — preferences & UI shell.** Migrate TermPreferences from `PreferenceActivity`/`android.preference` to AndroidX Preference (`PreferenceFragmentCompat`), keeping the same SharedPreferences file/keys so existing installs keep settings; replace framework ActionBar + `Theme.Light` with a Material 3 / AppCompat theme. This is the biggest user-visible change — screenshot-compare before/after.

**Phase 4 — targetSdk 35.** Edge-to-edge enforcement: window insets handling in Term/EmulatorView (keyboard + IME insets with `adjustResize`), verify FGS specialUse still passes Play policy, re-check 16 KB alignment on built .so files.

**Phase 5 — targetSdk 36.** Deal with large-screen orientation/resizability restrictions (the `configChanges` model), predictive back (`onBackPressed` is currently not overridden — verify), then bump.

**Phase 6 — samples.** Modernize or drop `samples/*` (they still use framework everything); lowest value, do last or delete from settings.gradle.
