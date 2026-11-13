#!/usr/bin/env python3
"""Appends section 8 (the seven follow-up decisions) to the audit report."""
import io

REPORT = r"E:\FrameMark\work\ui-v2\reports\UI-COPY-AUDIT-20261113.md"

PART1 = """
---

## 8. Follow-up round — the seven NEEDS_OWNER decisions, implemented

The owner reviewed §2.1 the same day and decided every open item. All seven are implemented
below. Six are display mappings; only item 1 touches data, and only the `name` field.

| # | item | decision | scope |
|---|---|---|---|
| 1 | logo version names | fix the data, not the display | `logos.json` x2, 107 renames |
| 2 | duplicated vendor labels | dedupe in the display mapping | 2 call sites + helper |
| 3 | era `既有` | `沿革款` | 1 label |
| 4 | support codes on failure copy | out of the sentence, kept in log + structured failure | 7 render sites |
| 5 | raw `${it.message}` | fixed Chinese sentence, raw text to the log/structured field | 15 sites |
| 6 | engine jargon | user language | 11 strings |
| 7 | `forBrand` -> 未设置 | fix the resolution | 1 snapshot field + 3-step chain |

### 8.1 Item 1 — 107 logo version names (data)

**Scope correction.** F7 said "53 of 347"; that count came from a regex that matched English
artefact *words* (`Black`, `Logo`, `SVG`, the pinyin) but **not** bare numbered stems, so the
machine-generated majority (`Canon 4`, `Motorola 11`, ...) was named in the prose yet left out
of the number. The full artefact set is **107 of 347** — that is what was renamed.

**Rules applied** (only `versions[].name`; `id`, `brandKey`, `assetKeys`, `file`,
`resolvedPath`, `sha256`, `era`, `years`, `status` untouched):

1. a bare catalogue number at the end -> `第 N 款` (`Canon 4` -> `Canon 第 4 款`; 45 names);
2. a trailing ` Black` / ` White` -> `黑色款` / `白色款`, and **collapsed to `字标` when the
   version ships both inks** — the cut row already appends 黑标/白标, so `Canon 黑色款 · 白标`
   would have contradicted itself (30 of the 40 tone names hit this);
3. resolution / marketing suffixes dropped (`SIGMA — Official VI announcement lockup 700x253`
   -> `SIGMA 官方 VI 发布字标`), a file format and the word `NEW` dropped
   (`Nokia NEW Logo SVG` -> `Nokia 新字标`);
4. pinyin / truncation / typo fixed: `Aolinbasi` -> 奥林巴斯, `Caisi` -> 蔡司, `Binde` -> 宾得,
   `Yijia` -> 一加, `Huawe` -> dropped, `Xiao MI` -> 小米, `Loing` -> dropped (`Xiaomi 字标`),
   `Agfacolour` -> `Agfa 彩色字标`, `Phaseone` -> dropped;
5. a generic `Logo` / `wordmark` inside the title -> 字标 (`Tamron Logo` -> `Tamron 字标`).

**Verification of the data edit.**

* Both copies (`ui/.../composeResources/files/data/logos.json` and `engine/data/logos.json`)
  are written from one payload and `cmp` clean — still byte-identical to each other and to
  the engine source, which `InspectorDataRealDataTest` asserts.
* A structural diff against `HEAD` reports **107 changed leaves, every one of them
  `versions[i].name`**, nothing else.
* No new duplicate `(brand, name)` pair was introduced (6 before, 6 after — the 6 are
  pre-existing pairs of distinct versions, e.g. two `Rollei wordmark` rows).
* No artefact token survives in the 347 names: `Black`, `White`, `Logo`, `SVG`, `NEW`,
  `Laika`, `Aolinbasi`, `Caisi`, `Binde`, `Yijia`, `Loing`, `Xiaomi Xiao MI`, `Phaseone`,
  `Agfacolour` -> 0 matches.
* Reproducible: `reports/ui-copy-audit-20261113/rename-logo-versions.py` (`--apply`), full
  before/after list in `reports/ui-copy-audit-20261113/logo-rename-table.md` (107 rows).

Twelve representative rows (the whole table is in that file):

| version id | before | after |
|---|---|---|
| `laika-legacy-2` | Leica Laika2 | Leica 徕卡字样 2 |
| `laika-legacy-long` | Leica Laika Long | Leica 徕卡字样长标 |
| `canon-4` | Canon 4 | Canon 第 4 款 |
| `canon-legacy-black` | Canon Black | Canon 字标 *(both inks -> tone dropped)* |
| `agfa-legacy-black` | Agfa Black | Agfa 字标 *(both inks)* |
| `sigma-2025-lockup` | SIGMA — Official VI announcement lockup 700x253 | SIGMA 官方 VI 发布字标 |
| `nokia-new-logo-svg` | Nokia NEW Logo SVG | Nokia 新字标 |
| `aolinbasi-legacy` | OLYMPUS Aolinbasi | OLYMPUS 奥林巴斯字标 |
| `caisi-legacy` | ZEISS Caisi | ZEISS 蔡司字标 |
| `xiaomi-legacy-loing` | Xiaomi Loing | Xiaomi 字标 |
| `alpa-14940` | Alpa 14940 | ALPA 字标 |
| `agfacolour` | Agfacolour | Agfa 彩色字标 |

Left alone on purpose: the 60-odd *descriptive* English names that are not ingestion
artefacts (`Canon EOS lockup`, `HASSELBLAD wordmark (spaced)`, `Apple wordmark (Garamond)`,
`HONOR ring lockup`). They name a mark rather than leak a review sheet; translating them is a
copy decision the owner has not asked for.
"""

PART2 = """
### 8.2 Item 2 — `OPPO · OPPO 相册` -> `OPPO · 相册`

**Location correction.** §2.1 pointed at `LibraryIndex.kt:295` / `DesktopLibraryState.kt:200`
as the vendor-group label. Those are a different label (the library group header, which
already strips the `"OPPO / "` leaf). The 459 rendered nodes come from the **card line**:
`taxonomy.json` stores `family` as `OPPO 相册` / `小米相机` / `vivo 相机`, and
`phoneCardDescription` (`PhoneBorderCard.kt:238`) joined it with the brand again — the same
pair is reused by the parameter page's 来源 row (`PhoneParameterPages.kt:558`).

* New `library/LibraryLabels.kt` -> `LibraryLabels.vendorSource(brand, family)`: drops the
  repeated leading brand, keeps the distinguishing part, keeps the leaf of the
  firmware-style spelling (`小米 / 徕卡水印` -> `小米 · 徕卡水印`).
* Both call sites use it. Display only — `LibraryCard.brand` / `.family` are untouched.

| before (dump nodes) | after |
|---|---|
| `OPPO · OPPO 相册` (287) | `OPPO · 相册` |
| `小米 · 小米相机` (109) | `小米 · 相机` |
| `vivo · vivo 相机` (43) | `vivo · 相机` |
| `OPPO · OPPO 相机` (20) | `OPPO · 相机` |

**459 rendered nodes -> 0.** `VendorLabelTest` asserts the four shipped pairs and walks every
vendor card in `taxonomy.json` to prove no line repeats its brand.

### 8.3 Item 3 — era `既有` -> `沿革款`

`eraLabel` is display only; the stored `era` stays `current` / `classic` / `historical` /
`legacy`. `LogoVersionSubtitleTest.theFourEraBucketsReadAsFourSteps` pins
现行 / 经典 / 历史 / 沿革款 and asserts `既有` is gone.

### 8.4 Item 4 — support codes out of the user sentence

| site | before | after |
|---|---|---|
| `BatchWorkspaceModel.failureText` | `没有写入权限，导出目录不可写。（FM-E-EXPORT-001）` | `没有写入权限，导出目录不可写。` |
| `ExportDialog` export failure | `导出没有成功（FM-E-EXPORT-001）` | `导出没有成功` + code in `FmLog` |
| `PhoneExportSheet` export failure | `...（FM-E-EXPORT-001）` | sentence only + code in `FmLog` |
| `PhoneShell` notice pill | `FM-E-WM-003 水印应用失败` | `水印应用失败` |
| `PhoneShell` -> settings error row | `FM-E-IO-003 工程修改未保存` | `工程修改未保存` |
| `PhonePresetSheet` preset write error | `写入失败：FM-E-PRESET-001 ...` | `写入失败：...` |
| `BatchScreen` failed row | `失败：FM-E-BATCH-001 ...` | `失败：...` |
| `PhotoBatch` queue / row errors | `FM-E-... ...` | sentence only |
| `ProjectStatusBanner` | `FM-E-IO-003 ...` | sentence only |
| `ProjectPresetBatch` dialog | `FM-E-PRESET-001 ...` | sentence only |
| `BatchRunner` item error | `[FM-E-BATCH-001] java.nio...` | `EngineFailure.userMessage`, else `导出失败，请重试。` |

The code stays available: `StoreError.code` / `BatchJobError.code` are unchanged, and the
producer logs it (`FM_BATCH_ITEM_FAIL` carries `raw=${t.message}`; the export sheet and dialog
log `FM_EXPORT_FAIL` / `FM_DESKTOP_EXPORT_FAIL`). `BatchWorkspaceModel` deliberately does not
log: the row mapper runs every frame, and a new evidence tag in that file would be new debt
for the arch gate (the first attempt failed exactly that gate and was removed).
`DesktopBatchWorkspaceTest` was rewritten to assert the code is **absent from the copy** and
**present in `BatchJobError.code`**.

### 8.5 Item 5 — no raw exception text

| file | before | after |
|---|---|---|
| `PhonePresetSheet` x6 | `读取预设文件失败：java.io.FileNotFoundException: ...` | `读取预设文件失败，请重试。` + raw to `FmLog` (`FM_PRESET_SAVE_FAIL`) |
| `PhoneExportSheet` x2 | `分享没有成功：java.lang.SecurityException...` | `分享没有成功，请重试。` + raw to `FmLog` (`FM_EXPORT_FAIL`) |
| `ExportDialog` | `选择输出目录失败：...` | `选择输出目录失败，请重试。` + raw to `FmLog` (`FM_DESKTOP_EXPORT_FAIL`) |
| `LogoBrandPanel` x2 | `用户 Logo 库读取失败：...` / `删除失败：...` | fixed sentences (no evidence tag in this file, see below) |
| `BatchRunner` item error | `t.message` verbatim | `EngineFailure.userMessage` else `导出失败，请重试。`; `raw=${t.message}` logged |
| `LibraryData` / `InspectorData` | `error = t.message ?: ...` drawn on the error screen | `边框库数据读取失败，请重试。` / `可调参数数据读取失败，请重试。`, plus a new **`diagnostic`** field that keeps the raw text |

`NoticeText.failure(tag, throwable, fallback)` is the shared helper: it logs the throwable
under the caller's existing tag and returns the fixed sentence. `NoticeTextTest` pins it,
including that the sentence never contains exception text and that the log line does.

**Two honest exceptions**, both forced by the evidence-hook ratchet (a `FM_` literal is
matched per file + symbol, and the allowlist may only shrink):

* `LogoBrandPanel.kt`, `LibraryData.kt`, `InspectorData.kt` carry no evidence tag, so a log
  call there would be *new* debt. The raw text goes to a structured diagnostic (the load
  results) or leaves the UI entirely (the user-logo status line).
* `LibraryPreview.kt` (the 14th `${it.message}` site) is unchanged on purpose: its `reason`
  is never rendered — `PreviewState.Failed(reason)` draws `MissingPreview()` — and
  `PreviewDiagnostics` already records it.
"""

PART3 = """
### 8.6 Item 6 — engine vocabulary

| where | before | after |
|---|---|---|
| `NativeRender` x2 | 这张边框未接入桌面引擎，无法导出 | 这个边框暂时还不支持导出，请换一个边框试试 |
| `NativeRender` | 当前引擎不支持 WebP 导出 | 当前版本不支持 WebP 导出 |
| `ProjectHolder` | 当前平台没有桌面引擎 | 桌面渲染组件不可用，请检查安装是否完整 |
| `RegistryInspector.DISABLED_REASON` | 引擎暂不支持 | 当前版本暂不支持 |
| `CuratedPanes` | 引擎按短边 8.5% 兜底 | 渲染按短边 8.5% 兜底 |
| `LogoPanes` | （引擎按版式实现，不是挪位） | （按版式排，不是挪位） |
| `DesktopColumnLayout`, `DesktopStagePane` | 引擎样张 | 示例图 |
| `ExportDialog` | 示例照片（引擎样张） | 示例照片 |
| `BatchScreen` subtitle | 原生导出 · ... | 本机导出 · ... |
| `BatchScreen` empty queue | 按当前照片与库内已接引擎的卡片 | 按当前照片和库里可导出的边框 |
| `DesktopShellDialogs` | 均走原生引擎 | 都用本机渲染 |

`ExportDialog`'s parenthetical was dropped rather than replaced with 示例图 because
「示例照片（示例图）」would repeat itself; the two standalone `引擎样张` labels do read 示例图
exactly as decided.

### 8.7 Item 7 — a set logo no longer reads 未设置

**Root cause (traced, not guessed).** The value the brand picker persists is
`ver:<brandKey>:<versionId>` — e.g. `ver:laika:leica-1932` (474 cut keys in `logos.json`).
`ControlLabels.forBrand` knows ten *legacy* stems (`laika`, `laika_long`, `hasu_long_black`,
...) and answers 未设置 for anything else, so every logo picked with the current picker made
the Brand control's initial value 未设置 — while the design really had a logo.

**Fix.** `InspectorSnapshot.brandNames` is built from `logos.json` in `parse` (brand keys +
every cut key -> brand display name), `InspectorDataSource.brandNameFor()` also resolves the
brand segment of a `ver:` key the catalogue does not list, and the Brand initial resolves
through *catalogue -> legacy table (`knownBrandLabel`, new, null instead of 未设置) ->
未设置*. `ControlLabels.forBrand` keeps its contract for every other caller.

`BrandNameResolutionTest` pins: `ver:*` cuts resolve, a `ver:` key outside the catalogue
resolves by its brand segment, unknown brand segments return null, **every** brandPicker
`currentValue` in the shipped `inspector-meta.json` resolves to a brand, and
`ControlLabels.forBrand("ver:laika:leica-1932") == "未设置"` is kept as the documented trap.

### 8.8 Tests added in this round

| test | file | cases |
|---|---|---|
| `VendorLabelTest` | `desktopTest/.../library/VendorLabelTest.kt` | 4 — the four shipped pairs, the firmware spelling, degraded inputs, and a walk over every vendor card in `taxonomy.json` |
| `NoticeTextTest` | `commonTest/.../product/NoticeTextTest.kt` | 5 — leading/inline code stripping, bare code -> null, ordinary copy untouched, `failure()` returns the sentence *and* logs the raw text |
| `BrandNameResolutionTest` | `desktopTest/.../inspector/BrandNameResolutionTest.kt` | 4 — cut keys, `ver:` fallback, the legacy trap, every `brandPicker` value in the shipped metadata |
| `LogoVersionSubtitleTest` | `desktopTest/.../inspector/LogoVersionSubtitleTest.kt` | +1 (6 total) — the four era labels |
| `DesktopBatchWorkspaceTest` | `commonTest/.../shell/DesktopBatchWorkspaceTest.kt` | 1 rewritten — copy without a code, structured `BatchJobError` with the code |

### 8.9 Verification and what remains

Run in a clean worktree at `63c8c75` + this patch (the main tree is still occupied by the
preview-tier line, §5.1):

```
:composeApp:compileKotlinDesktop        BUILD SUCCESSFUL
:composeApp:compileDebugKotlinAndroid   BUILD SUCCESSFUL
:composeApp:desktopTest                 1 133 tests, 0 failures
```

including `WireFormatFreezeTest`, `EvidenceHookKonsistTest` (the one new-log-tag attempt was
removed, §8.4), `ArchAllowlistHygieneKonsistTest`, `QaSurfaceKonsistTest`,
`SingleSourceKonsistTest`, `PlatformBoundaryKonsistTest`, `ControlRegistryCoverageKonsistTest`
and the ten new/updated cases above.

**What remains for the main tree.** Nothing from this audit: the patch is already applied
there and is independent of the preview-tier files. The only files shared with that line are
`workflow/NativeRender.kt` (my message hunks sit outside its `interactive` changes) and
`desktopTest/.../ExportFailureTest.kt`, whose stub now declares their new
`interactive: Boolean` parameter — so the tree compiles again as soon as the preview-tier line
fixes its own two errors (`NativeStagePreview.kt:176 PREVIEW_MAX_EDGE`,
`DesktopEngineBridge.kt:279 PreviewTier`). Re-run the official command then; nothing has to be
re-applied.
"""

with io.open(REPORT, 'a', encoding='utf-8') as fh:
    fh.write(PART1)
    fh.write(PART2)
    fh.write(PART3)
print("appended")
