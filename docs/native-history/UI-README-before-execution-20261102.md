# ui/ — FrameMark Compose Multiplatform app

> 2026-11-01 修复状态：当前已接真实引擎，180卡；本轮桌面147项测试及Android Kotlin编译通过。首批修复与剩余事项见 `../../_research/audit-20261101/FIXES.md`。下文的U0–U5、175卡与FakeEngine描述为历史阶段记录，不能作为当前完成度或发布依据。

Native UI for desktop (JVM) and Android. **U1** delivered the design tokens and
the phone bottom-nav shell; **U2** replaces the placeholder border library with
the real E1a library: 175 cards / 7 categories from bundled JSON, 175 real
480×360 previews, vendor two-level grouping (OPPO / 小米 / vivo / 无厂商·手写 /
品牌未标注), search, result counts, tap-to-editor identity and a bottom scrim
that closes the U1 “text peeking under the nav” nit. **U3** made the editor
inspector data-driven. **U4** adds the workflow layer: local presets, undo/redo,
an export sheet backed by a placeholder writer, and a batch queue — all local,
no renderer and no engine (the preview stays a static border image).

The desktop keeps the U0 rail + stage scaffold (three-column layout is U5).
No WebView, no engine.


## Toolchain

| Item | Value |
|---|---|
| Kotlin / Compose Multiplatform | 2.2.20 / 1.9.0 |
| AGP | 8.13.0 |
| Gradle | 8.14.3 (`D:\dev\gradle-8.14.3`, invoked directly; no wrapper generated yet) |
| JDK | 21 run, JVM target 17 |
| Android | compileSdk 36, minSdk 26, targetSdk 36 |
| applicationId | `com.framemark.native` (Kotlin package the same) |

`namespace` is `com.framemark` because AGP validates it as a *Java* package name
and `native` is a Java keyword; the applicationId keeps the full
`com.framemark.native`. Activities/manifest entries use fully qualified class
names for the same reason.

## Build & run (Git Bash, toolchain on D:)

```sh
. /d/dev/native-env.sh          # JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME, TEMP/TMP on D:
cd /d/border\&watermark/framemark/ui

# desktop compile + runtime classpath dump (also processes composeResources)
gradle :composeApp:compileKotlinDesktop :composeApp:dumpDesktopClasspath

# launch the desktop window (5 s, screenshot into the given dir)
powershell -ExecutionPolicy Bypass -File tools/run-desktop.ps1 -OutDir D:\border\watermark\framemark\_research\native\u0

# Android debug APK (no install)
gradle :composeApp:assembleDebug

# all-175 preview read+decode check (uses the same path as the UI tiles)
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
  -cp "$(cat composeApp/build/desktop-classpath.txt)" \
  com.framemark.native.verify.LibraryVerifyKt
```

`dumpDesktopClasspath` depends on `desktopProcessResources` and puts
`build/processedResources/desktop/main` on the classpath; without it
`Res.readBytes` cannot see `composeResources/**` when launching with plain
`java -cp` (U2 finding).

The desktop window is launched with `java -cp <classpath> com.framemark.native.MainKt`
instead of `createDistributable`, because CMP's packaging task requires WiX 3.11
from GitHub, which this host cannot reach (see `_research/native/PREP.md` §4).
Packaging is an X3 concern.

## Layout

```
composeApp/src/commonMain/kotlin/com/framemark/native/
  App.kt                     shared root; < 600dp → PhoneShell, else AppShell
  theme/Tokens.kt            palette/type/spacing/radii/elevation/nav/motion tokens
  theme/Theme.kt             MaterialTheme wiring (same roles)
  shell/AppShell.kt          desktop U0 rail + stage scaffold
  shell/PhoneShell.kt        phone tabs, library page, picker/editor/batch placeholders
  shell/BottomNav.kt         floating 4-tab capsule nav + FrameMark glyphs
  shell/NavMotion.kt         slide/stretch/ink/crossfade channels + frame probe
  library/LibraryData.kt     E1a JSON models + suspend loader (Res.readBytes)
  library/Json.kt            minimal JSON reader (no kotlinx-serialization dependency)
  library/LibraryFilters.kt  category/vendor grouping, search, result view
  library/LibraryPreview.kt  lazy webp read/decode + explicit missing-image tile
  library/LibraryPreviewLoader.kt  shared read/decode path (UI + verify entry point)
  library/LibraryScreen.kt   2-column grid, filters, search, headers, empty/error
  workflow/WorkflowPlatform.kt  platform hooks (preset JSON, temp export, sample encoder)
  workflow/DesignSession.kt  control values + undo/redo history (50 steps)
  workflow/Preset.kt         preset model + JSON document
  workflow/PresetStore.kt    local preset list persisted as one JSON file
  workflow/FakeEngine.kt     sample export: placeholder image into the temp folder
  workflow/BatchRunner.kt    batch queue state machine (simulated progress)
  workflow/PresetSheet.kt    secondary preset sheet (save/list/apply/rename/delete)
  workflow/ExportSheet.kt    export sheet (format/quality/long edge/filename/output)
  workflow/BatchScreen.kt    batch queue page (add/remove/start/pause/resume/cancel)
  workflow/UiBits.kt         in-window sheet scaffold + pills/chips/progress
composeApp/src/desktopMain/kotlin/com/framemark/native/
  workflow/DesktopWorkflowPlatform.kt  AWT placeholder encoder, app data dirs
  verify/WorkflowVerify.kt  U4 contract entry point (history/presets/export/batch)
composeApp/src/androidMain/kotlin/com/framemark/native/
  workflow/AndroidWorkflowPlatform.kt  Bitmap placeholder encoder, files/cache dirs
composeApp/src/commonMain/composeResources/files/
  data/taxonomy.json         byte-identical copy of engine/data/taxonomy.json
  data/previews.json         byte-identical copy of engine/data/previews.json
  previews/<cardId>.webp     175 files (480×360), copied from previews.rows[].path
composeApp/src/desktopMain/kotlin/com/framemark/native/
  main.kt                    window/evidence env hooks
  LibraryVerify.kt           all-175 read+decode evidence main (verify subpackage)
composeApp/src/androidMain/... MainActivity + manifest
tools/run-desktop.ps1        launch + screenshot helper (U0)
tools/u1-capture.ps1         U1 desktop/phone capture, tab tour, mid-transition burst
tools/u2-capture.ps1         U2 library capture (top/category/vendor/search/editor/scrim)
tools/u3-capture.ps1         U3 inspector capture (card id + scroll item)
tools/u4-capture.ps1         U4 workflow capture (presets/undo/export/batch hooks)
```

Env hooks for evidence runs (desktop only): `FM_WINDOW=390x844`,
`FM_UNDECORATED=1`, `FM_MOTION_LOG=1` (per-frame motion probe on stdout).
U4 hooks: `FM_OPEN_CARD`, `FM_DEMO_EDITS`, `FM_UNDO`, `FM_AUTO_SAVE_PRESET`,
`FM_OPEN_PRESETS`, `FM_OPEN_EXPORT`, `FM_RUN_EXPORT`, `FM_OPEN_BATCH`,
`FM_DATA_DIR`, `FM_EXPORT_DIR` (see `_research/native/U4.md`).

## Workflow (U4)

- **Presets** live in the editor header (`预设`), i.e. a secondary sheet, not a
  navigation tab. A preset saves the selected border id/name, every control
  value and the export choices as one JSON document:
  desktop `%LOCALAPPDATA%/FrameMarkNative/presets.json`, Android
  `filesDir/presets.json`. List / apply / rename / delete are local only.
- **Undo/redo** records control values in a 50-step local history with 700 ms
  same-control coalescing (typing a name or dragging a slider is one step).
  Toolbar buttons expose disabled states; desktop shortcuts are `Ctrl+Z`,
  `Ctrl+Shift+Z` and `Ctrl+Y`. Switching borders or applying a preset clears
  history. History is per session; it is not persisted.
- **Export** (`导出`) records format (JPG/PNG), quality, long edge, filename
  pattern and the output folder. `FakeEngine` draws a neutral placeholder at the
  requested size and writes it to the temp export folder — it does not render the
  photo or apply the border, and the sheet says 示例导出. The result panel shows
  file name, dimensions, size and path.
- **Batch** is a demo queue: add up to eight sample items, start / pause / resume
  / cancel, remove items, overall and per-item progress. Progress is simulated
  and no file is written.

Verification: `java -cp "$(cat composeApp/build/desktop-classpath.txt)" \
com.framemark.native.verify.WorkflowVerifyKt` — 28 checks (25-step history
undo/redo round-trip, history cap, preset save/reload/rename/delete/apply,
JPG+PNG placeholder dimensions, batch state machine).

Env hooks for evidence runs (desktop only): `FM_WINDOW=390x844`,
`FM_UNDECORATED=1`, `FM_MOTION_LOG=1` (per-frame motion probe on stdout).

Data mapping: `previews.rows[].path` → `files/previews/<cardId>.webp`; the 47
cards whose library `path` differs from `inspectorPath` use `path` (D-010). The
68 `vendor=null` cards form the 无厂商 / 手写 bucket; the 8 `brand=null` cards
that still carry a `vendor` are shown in a separate 品牌未标注 bucket, raw values
preserved (see `_research/native/U2.md`).

Inspector controls are generated from the frozen E1a data joined with
`../contracts/capabilities.schema.json` (U3); the render contract
(`../contracts/render-v1.schema.json`) is not called yet — U4's export is a
placeholder writer, and the preview remains static until the engine lands (X1).

