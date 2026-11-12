# Native build entry point

`build-native.cmd` resolves `scripts/native/build.cjs` relative to this checkout.
It does not call scripts under `_research` or rely on the checkout's parent path.

## Toolchain

Exact versions live in [toolchain.json](toolchain.json). Paths are supplied by
environment variables so another checkout can use its own SDK locations:

| Tool | Version | Path variable |
|---|---:|---|
| Node.js | 24.15.0 | `FM_NODE_EXE` (optional; otherwise `PATH`) |
| Azul Zulu JDK | 21.0.8 | `FM_JAVA_HOME` or `JAVA_HOME` |
| Gradle | 8.14.3 | `FM_GRADLE_HOME` or `GRADLE_HOME` |
| Android SDK | compile SDK 36 | `FM_ANDROID_SDK_ROOT`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME` |
| Android NDK | 26.3.11579264 | under the selected SDK root |
| Rust | 1.99.1 (checked on each run) | `FM_CARGO_EXE`, `FM_RUSTC_EXE`, `FM_RUSTUP_EXE`; `RUSTUP_HOME` and `CARGO_HOME` |
| MSVC | x64 host tools | `FM_VSDEVCMD` (required for both Windows and Android Rust builds) |

The Windows runtime packager uses `sharp` only to decode the WebP logos already
packaged in Compose resources. It does not need the Electron/WebView dependency
tree. The checked-in `logo-tools/package.json` and `package-lock.json` pin
`sharp` 0.35.5; the build preflight checks that this registry URL and integrity
match the repository lock and that the installed module resolves from the
isolated prefix. `runtime.cjs` refuses to fall back to a checkout or global
`node_modules` directory.

Prepare the small tool install in a directory outside the source checkout on
the project data drive, then build in the same PowerShell session:

```powershell
$env:FM_NATIVE_SHARP_PREFIX = 'E:\FrameMark\execution-external-agent-20261103\tool-cache\native-logo-tools'
.\scripts\native\prepare-logo-tools.ps1
.\build-native.cmd
```

The installer copies only the minimal package manifest and lock, runs `npm ci`
for that package, keeps its npm cache under the selected prefix, and writes an
install provenance JSON there. The packager records the sharp version, registry
integrity, lock hash, and installed package hash in runtime provenance. Logo
resources and their source hashes are checked again when staging each runtime.

The two Skia archives also have pinned SHA-256 values. Set
`FM_SKIA_WINDOWS_ARCHIVE` and `FM_SKIA_ANDROID_ARCHIVE` to local copies of those
archives. The Cargo build uses repository Cargo.lock files and writes Cargo
targets, staged static libraries, logs, and JSON reports under
`_research/acceptance-dual-20261101/execution-20261102/build-release/`. Set
`FM_BUILD_ARTIFACT_ROOT` to override that output root with another absolute path.
Use a short output path on Windows: the source build of libwebp invokes MSVC,
whose object-file path can hit the traditional 260-character limit. The isolated
build driver disables Kotlin incremental compilation only when the source and
generated-output directories have different Windows drive roots; the full
compiler still runs. This avoids a reproduced incremental-cache path-conversion
failure without changing product code or compiler versions.

The current driver builds all three Windows executables (`e2b`, `e4hand`, and
`e5-worker`) from their repository Cargo sources. `e4hand` reuses the isolated
desktop Cargo target so the shared Skia dependency can be reused; `e5-worker`
uses a separate target. Every executable records its own crate source/lock hash
and binary hash in `desktop-engines-provenance.json`.

After those builds, `runtime.cjs` stages an isolated QA runtime from the
repository-owned Android `x1ir.zip`, `bridge/desktop/cards-175.json`, and the
current registry sources. The three stock-style control documents are
product-owned data at `bridge/desktop/controls-data/controls.{oppo,xiaomi,vivo}.json`;
the required `no-legacy-web-dependency` gate fails the run if either this build
script or `runtime.cjs` names the retired web-app tree again, or if a control
document is missing or unparsable. It derives the actual card totals from the manifest,
builds the decoded IR asset cache, includes the fonts, ICC profile, hand data,
registry and manifest, checks packaged text/JSON for developer paths, and smoke
renders an IR and hand card from the runtime data directory. The runtime,
smoke outputs, and file-level SHA report are stored under the configured
artifact root. The packager does not use binaries or bundle contents from
`_research` as inputs.

This runtime staging proves the resources and source-built engines in that QA
run can work together. It does not, by itself, prove that the complete native
project can be rebuilt from a clean Git checkout: all required product sources
and build inputs must first be present in the chosen Git baseline.

## Commands

```powershell
.\build-native.cmd --dry-run
.\build-native.cmd
.\build-native.cmd --skip-gradle
.\build-native.cmd --release
.\build-native.cmd --release --release-qa
.\build-native.cmd --package
.\build-native.cmd --package --sign
.\build-native.cmd --package --debug-apk
```

`--skip-engine` skips only the `e2b` source build. It cannot stage a complete
desktop runtime and blocks release acceptance; it is intended only for a
partial diagnostic run.

`--skip-android-native` is for hash-checked debug builds. A release must build
the three Android static libraries from their checked-in Cargo sources in the
same run. The existing `.a` files have recorded hashes, but those hashes and
the older hand-crate target fingerprints do not prove the `.a` source. Rust
1.99.1 is selected for new source builds because it matches the local compiler
and old hand-crate fingerprints; the driver checks the exact `rustc`/`cargo`
versions and installed Windows/Android targets before building.

The build driver uses `.qa20261102` for debug APKs by default. Override it with
`FM_QA_SUFFIX` when the QA package id must be unique. `--release --release-qa`
adds `-PframemarkQaReleaseSuffix=.qa20261102release`, producing a `BuildConfig.DEBUG=false`
APK with a distinct application id and the Android debug signing key. That APK
is for local device validation only and is not publishable. A normal release
build has no QA suffix and remains unsigned unless the separate production
signing option is supplied.

`--package` turns the run into the shipped artifacts instead of only build
evidence. After the existing steps it runs `:composeApp:desktopTest` through the
same isolated Gradle invocation, deletes the desktop class output and rebuilds
`:composeApp:desktopJar` with `-PfmQaSurface=false`, asserts the product jar and
the staged class tree contain zero `com/framemark/native/(verify|qaenv)` entries
and no class name/package mentioning `verify`/`qaenv`/`devtools`, then stages the
jlink runtime plus this run's `bin`/`data`/`bridge` and writes
`FrameMark-<versionName>-win64-portable.zip`, `FrameMark-<versionName>-arm64.apk`
and `SHA256SUMS.txt` under `<artifact root>/runs/<runId>/deliverables/`. It also
launches the packaged `FrameMark.bat` once (window handle + screenshot + clean
process exit) and re-hashes the shipped files. The label comes from
`version.properties` and the commit from `git rev-parse --short HEAD`; the
hand-run packaging recipe outside the repository is no longer needed. Packaging
requires a PASSing `desktop-runtime` step and source-built Android libraries, so
`--skip-engine` and `--skip-android-native` cannot produce a package in the same
run.

`--package` builds and ships the **release** variant: the Gradle invocation adds
`:composeApp:assembleRelease` and passes
`-PframemarkQaReleaseSuffix=<the debug build's suffix>`, so the delivered app id
stays `com.framemark.native<qaSuffix>` (installable over the debug QA build). The
delivered APK is gated before it is copied and must never be
`application-debuggable`. Without `--sign` the release variant keeps the Android
debug key on purpose, so the artifact is stamped unmistakably: `SHA256SUMS.txt`
starts with `QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE` and the report
classifies it `LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE` (the QA-suffixed app id is
not the product id). Pass `--package --sign` with the `FM_SIGNING_*` variables
(the certificate fingerprint is checked against `FM_SIGNING_CERT_SHA256`) for a
production-signed package. A production-intent run (`--release` without
`--release-qa`, or `--sign`) fails when the delivered APK is unsigned or carries
the Android debug certificate.

`--package --debug-apk` is the explicit escape hatch: it produces
`FrameMark-<versionName>-arm64-DEBUGQA.apk` from `composeApp-debug.apk`, keeps it
out of `SHA256SUMS.txt` (its size and hash go into `DEBUG-QA-APK.txt`) and marks
the report `LOCAL_QA_DEBUG_APK_NOT_PUBLISHABLE`. Because the APK is
application-debuggable, `package-apk-class` fails and the command exits nonzero:
the file is a local QA artifact, never a release.

A failed run never leaves publishable artifacts: if any required step or gate is
not `PASS` before packaging, the ZIP, the release APK and `SHA256SUMS.txt` are
not written; if a post-packaging gate (launcher smoke, checksum re-verification)
fails, they are removed again. Either way the run writes
`<artifact root>/runs/<runId>/deliverables/RELEASE-STATUS.json` with the failed
gate ids, and the command exits nonzero. The only artifact a failed run keeps is
the `-DEBUGQA.apk` plus its sidecar, because it is explicitly not publishable.

Android CMake and Gradle restrict packaging to `arm64-v8a`; CMake fails if any
of the IR, hand-renderer, or export static libraries is absent. QA Intent
extras in `MainActivity` are ignored when `BuildConfig.DEBUG=false`; the exported
MAIN/LAUNCHER activity remains available in release builds.

Any required build step or gate reported `FAIL` or `SKIP` makes the command exit
nonzero. `--skip-gates` is recorded as a skipped required gate and therefore
cannot produce a successful acceptance result. The entry point builds only; it
does not install or deploy the app.
