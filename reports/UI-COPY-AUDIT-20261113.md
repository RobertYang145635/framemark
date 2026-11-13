# UI copy audit — 2026-11-13

**Question from the owner:** 检查整个UI，有没有出现不应该给用户看的备注或者标记，这点很重要
(no developer note, internal marker, debug affordance, raw technical identifier or QA
artefact may be visible to a user).

**Repository:** `E:\FrameMark\work\ui-v2`, branch `ui-v2/main`, base commit `63c8c75`.
**Surfaces:** Compose Multiplatform, desktop (`desktopMain`) + phone (`androidMain`), shared
`commonMain`. Tests (`commonTest` / `desktopTest`) were excluded from the source sweep by
design — a marker in a test is not user copy.

**Result in one line:** six real leaks, all fixed by display-side mapping (persisted keys,
and the wire format untouched); ten candidates explained as acceptable. The seven ambiguous
items were decided by the owner the same day and are **all implemented** — §8 has the
before/after strings, the 107-row logo rename table and the tests. Verification is green on
the audit’s change: `:composeApp:compileKotlinDesktop`, `:composeApp:compileDebugKotlinAndroid`
and `:composeApp:desktopTest` (1 133 tests) pass — see §5 for why that was measured on a
clean worktree.

| verdict | findings | state |
|---|---|---|
| **LEAK** | 6 | fixed (§3) |
| **NEEDS_OWNER** | 7 | decided and implemented (§8) |
| **ACCEPTABLE** | 10 | explained (§2.1) |

---

## 1. Method

Four sweeps, in this order, before any edit was made.

### 1.1 Source sweep (production UI Kotlin)

`reports/ui-copy-audit-20261113/sweep-source.py` — strips Kotlin comments (line, block, raw
and char literals preserved), extracts every string literal with its line, and tags matches
for the marker set (`TODO`, `FIXME`, `XXX`, `HACK`, `WIP`, `debug`, `测试`, `临时`, `占位`,
`待办`, `样例`, `示例数据`, `mock`, `stub`, `dummy`, `placeholder`, `beta`, `实验`), the raw
identifier set (`photoCropX`, `stockOffsetXPct`, `pctOfLongEdge`, `fNumber`, `showLogo`,
`anchor`, `ver:`, `fmlogo_`, `*.webp|png|jpg`, `stock-*`, `hand-*`, `laika`, `panasionic`,
`D3D12`, `Ganesh`, `Skia`, `FM_*`, `null`/`undefined`/`NaN`) and the QA-hook set
(`LaunchOptions`, `fmCard`, `fmPhoto`, `fmOpenExport`, `evidence`).

* **253 production files, 7 777 string literals** at the audited commit (7 791 on the
  current tree; the delta is the other line’s `PreviewTier` work).
* Marker hits: **14** — 12 are the word 临时 inside “临时文件” error copy (legitimate: F20),
  and 2 are `占位图` in the verify-only placeholder image (F12). **No** `TODO`, `FIXME`,
  `XXX`, `HACK`, `WIP`, `debug` or `placeholder` appears in any production string literal.
* Raw-identifier hits: **613** — 431 literals contain an `FM_*` token and are **log tags**
  (`FmLog.info("FM_…")`), which never reach a screen; the remaining were each traced to a
  render site. `grep` for `TODO|FIXME|XXX|HACK|WIP` **inside a string literal**: **0**.
* A second pass, `sweep-text-position.py`, cross-references each literal with the 120
  characters before it, keeping only those in a text position (`Text(`, `label =`,
  `contentDescription =`, `title =`, `subtitle =`, `placeholder =`, `CuratedNote(` …) and
  ASCII-only: **243 candidates**, read by hand.
  Output: `ascii-text-position.tsv`.

### 1.2 Resource sweep (compose resources + data JSON)

`reports/ui-copy-audit-20261113/sweep-resources.py` — walks every JSON value under
`commonMain/composeResources/files/data/`, keeps text-bearing keys (`label`, `name`, `cn`,
`title`, `note`, `badge`, `era`, `years`, `status`, `group`, …) and flags (a) values
matching the review vocabulary (`NEEDS_REVIEW`, `CANDIDATE`, `LEGACY`, `_merge`, `__`,
`n.d.`, `不建议`, file names, paths), (b) values that are empty where copy is expected, and
(c) ASCII-only values next to Chinese copy.

| file | label-key hits |
|---|---|
| `capabilities.json` | 112 |
| `controls.json` | 16 |
| `inspector-meta.json` | 208 |
| `logos.json` | 824 |
| `previews.json` | 0 |
| `taxonomy.json` | 121 |

Output: `resource-hits.tsv`; the `logos.json` cross-reference is `xref-logos.py`.

### 1.3 Rendered-text sweep (what a device actually drew)

`reports/ui-copy-audit-20261113/sweep-rendered.py` — parses **every** `*.xml` under
`E:\FrameMark\execution-ui-v2-20261106\evidence\` (**3 262 dumps** — `sweep-180`,
`verify-rc14`, `border-page`, `textpage`, `pkg-rc*`, `review-*`, `WP-*`, all of them, not
just the two named), reads each `<node>`’s `text=` and `content-desc=` and clusters the
distinct strings.

* Nodes owned by this app (`com.framemark.native.*`): **28 126** with visible text.
* **1 209 distinct strings** — the full vocabulary, with counts and flags, is
  `reports/ui-copy-audit-20261113/rendered-strings.tsv`; the per-node dump is
  `rendered-occurrences.tsv` (1.7 MB) so any count can be traced back to a screen.
* **2 074 nodes were skipped** because they belong to another product:
  `com.android.photopicker` 1 964 and `com.android.systemui` 110. This matters — the
  most frequent untranslated English string in the raw corpus (`Preview Icon`, 129×) is the
  system photo picker’s own untranslated label, not ours. Without the package filter it
  would have been the top finding.

### 1.4 Judgement

Every candidate was traced from the string to the render site (or to the data reader for
JSON values) and classified LEAK / ACCEPTABLE / NEEDS_OWNER. “LEAK” required a user-visible
surface (including the TalkBack/accessibility surface) plus a developer artefact; a
deliberate, documented or test-pinned decision was never overridden silently.

---

## 2. Findings

### 2.1 Table

`where` is `file:line` at the audited commit (before the fix), so it stays usable as a
pointer for the items that were not fixed. Where a count is given it is the number of dump
nodes that rendered the string; “latent” means the string is reachable in the product but no
dump in the corpus captured it.

| # | where | what the user sees | verdict | fix |
|---|---|---|---|---|
| F1 | `commonMain/.../workflow/PhotoTray.kt:175` (`PhotoThumbnail`) | TalkBack announces the app’s own import copy for every strip thumbnail: `user-1794418347151-0.jpg`, `user-1794317514761-0.jpg`, `photo.png` — 87 nodes across 39 dumps. The picked file’s name (`hdr.jpg`, `IMG_1234.jpg`) was replaced by a `user-<epoch>-<n>.jpg` cache name | **LEAK** | 87 → 0. `contentDescription` is a parameter (default `null`; every call site sits in an already-labelled container), and `photoLabel()` never falls back to an internal copy name |
| F2 | `inspector/BrandLogoLibrary.kt:671`, `LogoBrandPanel.kt:712`, `LogoPanes.kt:1194`, `PhoneLogoPicker.kt:660` | The logo-version subtitle printed the catalogue’s review-sheet placeholder: `既有 · —` (6 nodes, `verify-rc14/c11-sony-styles.xml`, `c12-sony-white-selected.xml`) and, for the 75 versions with an empty `years`, `现行 · ` — a line ending in a separator. Also `现行 · n.d.` and `现行 · n.d. (current site)` in English | **LEAK** | 190 of 347 version lines corrected via one `versionSubtitle()` mapping; data untouched |
| F3 | `shell/phone/PhoneSettingsPage.kt:284` (`PhoneSettingsText.versionLabel`) | Settings → 版本 fell back to the literal word `dev` when the build carries no label | **LEAK** (latent: the QA build has a real label) | falls back to 未知; `null`/`undefined` also handled |
| F4 | `workflow/BatchScreen.kt:86` | Batch sheet hint: 「导出走原生引擎（**E5**，JPEG **q100** 4:4:4）…」 — `E5` is the internal work item of the export encoder (`E5Worker.kt`, “IO/导出 E5” in the docs) and `q100` is the internal shorthand | **LEAK** (latent: the batch sheet is not in any dump) | rewritten to 「导出用本机渲染（JPEG 质量 100，4:4:4）…」, constant `BATCH_EXPORT_HINT` |
| F5 | `workflow/NativeRender.kt:248` | The export wrapper copied the raw throwable text into `EngineFailure.userMessage` — the field the export dialog and the phone notice print. An engine-side throwable is English and names the runtime binary, so a failed export could read `IR 引擎缺失（bin/e2b.exe）（FM-E-ENGINE-002）` | **LEAK** (latent) | fixed Chinese sentence; raw text stays in `cause` and the log |
| F6 | `workflow/PhoneShell.kt:726` | Quick-save-preset fallback contained a hard-coded `FM-E-PRESET-001` in the sentence: `FM-E-PRESET-001 预设保存失败`. The store’s real error carries its own code; this one was fabricated and could never match | **LEAK** (latent) | plain copy `预设保存失败`; `store.presetError` (and the tests that pin its prefix) untouched |
| F7 | `composeResources/files/data/logos.json` — `versions[].name` | 53 of the 347 version names are ingestion/review artefacts shown next to the version tile: numbered stems (`Canon 4`, `Nikon 13`, `Motorola 11`, `Kodak 7`), duplicated tone (`Agfa Black` next to the 黑标 chip), pixel dimensions (`SIGMA — Official VI announcement lockup **700x253**`), a format marker (`Nokia NEW Logo **SVG**`), pinyin transliterations (`OLYMPUS **Aolinbasi**`, `ZEISS **Caisi**`, `Xiaomi **Xiao MI**`, `HUAWEI **Huawe** Black`), a typo (`Xiaomi **Loing**`). 1 name is visible in a dump (`SONY wordmark`) | **NEEDS_OWNER** | not fixed — data file is frozen. Proposal: a `versionDisplayName()` in the same display layer (drop ` Black|White` because the tone chip already says it, render bare numbers as 第 N 款, strip `— Official …<WxH>` tails, map the pinyin spellings) |
| F8 | `shell/phone/PhoneBorderCard.kt:238` (`phoneCardDescription`) and `shell/phone/PhoneParameterPages.kt:558` (the 来源 row) — *corrected in §8.2: the audit first pointed at `LibraryIndex.kt:295`, which is a different label on the phone* | The vendor card's second line repeats the brand: `OPPO · OPPO 相册` (287), `小米 · 小米相机` (109), `vivo · vivo 相机` (43), `OPPO · OPPO 相机` (20) — 459 nodes, the largest repeated-label cluster in the corpus | **NEEDS_OWNER** (copy nit, not an internal artefact) | implemented in §8.2 → `OPPO · 相册`, `小米 · 相机`, `vivo · 相机` |
| F9 | `inspector/BrandLogoLibrary.kt:304` (`eraLabel`) | The `legacy` era bucket is translated as `既有` — a catalogue word, not something a user says about an old logo mark | **NEEDS_OWNER** | not fixed. Proposal: `现行 / 经典 / 历史 / 早期` |
| F10 | `product/ControlLabels.kt:318`, `:326` | An option/theme id missing from the label table renders as `未知选项（light_blur）` — explicit, but it does put a raw id on screen | **ACCEPTABLE** | deliberately diagnosable, comment says so; 0 occurrences in 1 209 distinct rendered strings and `ControlRegistryRulesTest` already fails when a *visible* control’s label is an identifier |
| F11 | `product/ControlLabels.kt:340` (`forBrand`) | A brand key outside the 10-entry `brands` table renders `未设置` — “not set” while a logo *is* set. Not a leak, a wrong reading; the new logo page uses `logoValueLabel()` instead | **NEEDS_OWNER** (display correctness) | not fixed — needs the owner’s call on whether the legacy brand control is still reachable |
| F12 | `desktopMain/.../workflow/DesktopWorkflowPlatform.kt:222,228`, `androidMain/.../AndroidWorkflowPlatform.kt:233,241` | The placeholder image literally draws `示例导出` / `FrameMark 占位图` (“placeholder image”) | **ACCEPTABLE** | only produced by `workflow/FakeEngine.kt`, whose single caller is the desktop verify harness (`verify/WorkflowVerify.kt`); no production path calls it. Residual risk noted in §5 |
| F13 | `shell/desktop/ExportDialog.kt:611,351`, `DesktopColumnLayout.kt:151`, `DesktopStagePane.kt:351`, `workflow/NativeRender.kt:161,202`, `PhotoBatch.kt:66`, `BatchScreen.kt:70` | Engine vocabulary in user copy: `示例照片（引擎样张）`, `引擎样张`, `引擎暂不支持`, `这张边框未接入桌面引擎，无法导出`, `已接引擎的卡片`, `原生导出` | **NEEDS_OWNER** (product vocabulary) | not fixed — rewording 7 strings is a copy decision, and `引擎` is used consistently on both platforms |
| F14 | `shell/desktop/ExportDialog.kt:351`, `BatchWorkspaceModel.kt:170,175`, `PhoneShell.kt:393,553`, `PhoneExportSheet.kt:178` | Failure copy carries the support code: `导出没有成功，原因…（FM-E-EXPORT-001）` | **NEEDS_OWNER** — deliberate and test-pinned (`DesktopBatchWorkspaceTest.failure_text_translates_the_system_message_but_keeps_the_code`, and `EvidenceHookKonsistTest` documents `FM-E-*` as product behaviour, not a hook). The owner’s rule says no raw identifier, so it is worth a decision | **not changed on purpose.** One-function patch ready: `NoticeText.human(raw)` stripping `^FM-[A-Z]-[A-Z]+(-[0-9A-Z]+)?` at the 6 render sites |
| F15 | `shell/phone/PhoneSettingsPage.kt:131` | Settings shows `0.9.0-rc3-dev` — the build’s `versionName`, including the `-dev` suffix of a dev build | **ACCEPTABLE** | an About row is expected to state the build; a release build shows its release label. Worth knowing that a dev build advertises itself |
| F16 | `PhonePresetSheet.kt:88,134,140,147,165,173`, `PhoneExportSheet.kt:142,204`, `ExportDialog.kt:480`, `LogoBrandPanel.kt:336,416`, `InspectorData.kt:259`, `LibraryData.kt:94`, `LibraryPreview.kt:68` | Raw exception text appended to a Chinese sentence: `读取预设文件失败：${it.message}` → e.g. `java.nio.file.AccessDeniedException: …`, `Cannot run program "…/e2b.exe"` | **NEEDS_OWNER** (systematic, 14 sites) | not fixed — the established translator is `BatchQueueView.failureText()` (already maps “occupied”, “access denied”, “no such file”, “disk full” to Chinese); routing the other surfaces through it is a separate step |
| F17 | `workflow/PhotoTray.kt:31` (`TrayPhoto.label`) → `PhoneEditorTopBar` | The editor title shows the photo’s **file name** (`hdr.jpg`, `FMTEST_p1170883.jpg`, `1000084663.jpg`). The dumps’ own `FMTEST_*` names come from the evidence rig | **ACCEPTABLE** | showing the picked file’s name is what a photo tool does; the *internal copy* case is F1 and is fixed |
| F18 | dump `pkg-rc10/android/k1.xml` etc. | `Preview Icon` (129 nodes), English in a Chinese UI | **ACCEPTABLE** | not our app — `package="com.android.photopicker"` (the system picker’s own untranslated content description) |
| F19 | `inspector/FmControls.kt:892`, `PhoneStageOverlay.kt:177` | Hint lines start with a middot: `· 左右滑动换边框` (427 nodes) | **ACCEPTABLE** | deliberate bullet in the label+hint row, `text = "· $hint"` |
| F20 | `product/SessionStore.kt:413-481`, `workflow/NativeRender.kt:375-502` | The word 临时 in “写入临时文件失败” etc. | **ACCEPTABLE** | legitimate error copy about a temp file; no developer marker. (It is also the only match of the 14 marker hits in production source.) |
| F21 | long Chinese sentences flagged by the punctuation heuristic (`边框库：8 个分类，共 180 个边框。`) | nothing — the flags are the heuristic catching ordinary sentence-final 。/、 | **ACCEPTABLE** | false positives of the cluster classifier, listed in `rendered-strings.tsv` with `PUNCT` |
| F22 | `inspector/LogoPanes.kt` | `content-desc="Sony α mount mark"`, `字标：SONY wordmark 黑标` | **ACCEPTABLE** | describes the logo artwork to a screen reader; brand names are proper nouns |
| F23 | `product/ControlLabels.kt:335` (`forText`) | An unknown upper-case enum token would be shown raw (the guard only humanises `^[a-z][a-z0-9_-]*$`) | **ACCEPTABLE** | reached only if the shipped data drifts out of both tables; 0 occurrences. Covered by `ControlRegistryRulesTest`’s identifier gate |

### 2.2 Counts

* **LEAK, fixed: 6** → F1 (87 nodes), F2 (190 of 347 version lines / 6 nodes), F3, F4, F5,
  F6 (the last three latent).
* **NEEDS_OWNER: 7** → F7, F8, F9, F11, F13, F14, F16.
* **ACCEPTABLE: 10** → F10, F12, F15, F17, F18, F19, F20, F21, F22, F23.

### 2.3 The rendered vocabulary, by size

Top of `rendered-strings.tsv` (count · string), i.e. the strings a user actually reads.
The flag column and the verdict for each are in §2.1; the full 1 209-row list is the file
itself.

```
1156 水印      1346 文字      1714 边框      1145 样式      (tab labels)
 615 导出       602 导出当前照片   602 撤销 / 返回 / 重做
 573 按住查看原图 / 按住看原图     579 展开参数
 430 Logo                                 (brand-control label, intentional English)
 427 · 左右滑动换边框                      (bullet hint, F19)
 376 3 / 3   376 切换照片，第 3 张，共 3 张  (position/photo counter, a11y copy correct)
 374 hdr.jpg                              (the rig's photo name in the title, F17)
 374 预设，保存当前设计或套用已保存的预设
 367 边框库：8 个分类，共 180 个边框。      (prose, F21)
 287 OPPO · OPPO 相册                      (F8)
 151 搜索边框
 134 realme 品牌印记 · 2
 122 卡片，18 个 / 印象，13 个 / 厂商，111 个 / 文字，17 个 …  (category counts)
 109 小米 · 小米相机                        (F8)
  87 user-1794418347151-0.jpg / photo.png  (F1 — 6 spellings in 39 dumps)
  49 FMTEST_p1170883.jpg                   (rig photo name, F17)
  43 vivo · vivo 相机                      (F8)
   6 既有 · —                              (F2)
   3 0.9.0-rc3-dev                        (F15)
```

Reading the list top-down is the point: the most frequent strings are all legitimate
navigation copy. The leak that reached users most often (F1, 87 nodes) sits at rank ~20 and
is **invisible on screen** — it is a `content-desc`, so no screenshot would ever show it;
that is why the dump sweep had to read attributes.


---

## 3. Fixes applied

All six are display-side. No persisted key, parameter name, wire field, `ControlLabels`
key or data file changed; no new `FM_` literal was introduced.

| # | file:line | change |
|---|---|---|
| F1 | `.../workflow/PhotoTray.kt:31,45-66` | `TrayPhoto.label` delegates to a new `photoLabel(path, displayName)`; a missing display name no longer falls back to the path basename when that basename is the app’s own copy (`user-<epoch>-<index>.jpg`, `photo.png`, `exif.jpg`) — it reads 照片. Internal copy names are one regex, documented as “never a title or a screen-reader label” |
| F1 | same file, `PhotoThumbnail` (`:168-186`) | new `contentDescription: String? = null` parameter; the `Image` no longer derives its description from `path.substringAfterLast('/')`. All 9 call sites sit inside a labelled container (the strip cell already announces `${photo.label}，第${index}张，长按管理照片`), so nothing lost |
| F2 | `.../inspector/BrandLogoLibrary.kt:308-349` | new `yearLabel(years)` + `versionSubtitle(era, years)`: placeholder years (`—`, `–`, `-`, blank, `n/a`, `null`, `undefined`) collapse to the era alone; `n.d.` and `n.d. (current site)` are treated as “no year”; `n.d. (pre-YYYY)` becomes `YYYY 年前` |
| F2 | `.../inspector/BrandLogoLibrary.kt:713`, `.../inspector/LogoBrandPanel.kt:712`, `.../inspector/LogoPanes.kt:1194`, `.../inspector/PhoneLogoPicker.kt:660` | the four hand-built `"${eraLabel(era)} · ${years}"` joins now call `versionSubtitle(...)` |
| F3 | `.../shell/phone/PhoneSettingsPage.kt:283-302` | `versionLabel()` returns `UNKNOWN_VERSION = "未知"` instead of `"dev"`, and also rejects the literals `null` / `undefined` (case-insensitive) |
| F4 | `.../workflow/BatchScreen.kt:38-45,97` | new `internal const val BATCH_EXPORT_HINT`; the copy is 「导出用本机渲染（JPEG 质量 100，4:4:4），文件写在导出目录；从队列移除任务不会删除已导出的文件。」 |
| F5 | `.../workflow/NativeRender.kt:251-261` | the catch-all wraps with fixed copy 「这张照片没能渲染成功，请重试。」 and logs the raw text under the existing `FM_NATIVE_EXPORT` tag; the cause is preserved |
| F6 | `.../shell/PhoneShell.kt:726-729` | the preset fallback is `store.presetError ?: "预设保存失败"` |

`git diff --stat`, this audit only: **11 tracked files, +178 / −20** (6 of the insertions are
the F5 hunk in `workflow/NativeRender.kt`, which is shared with another line’s in-flight
change — see §5.1) and **4 new test files**.

### 3.1 Design notes

* Every fix is a **display mapping**: `photoLabel`, `versionSubtitle`/`yearLabel`,
  `versionLabel`, `BATCH_EXPORT_HINT`, the fixed user sentence. The value that is stored,
  rendered, persisted or sent to the engine is unchanged, which is why
  `WireFormatFreezeTest` and the architecture gates stay green.
* No `FmRadii` / `FmSpacing` / `FmType` token was needed: no layout changed, only strings.
* No new `FM_` literal. The one place where a log line was needed reuses the existing
  `FM_NATIVE_EXPORT` tag, so the `EvidenceHookKonsistTest` ratchet is untouched.
* Line endings and file conventions of each edited file were preserved (the repo is
  CRLF with `core.autocrlf=true`; `PhoneLogoPicker.kt` is LF in `HEAD` and stays LF).

---

## 4. Tests added

| test | file | what it pins |
|---|---|---|
| `PhotoTrayLabelTest` (5 cases) | `commonTest/.../workflow/PhotoTrayLabelTest.kt` | `photoLabel()`: an internal copy name never becomes a title (`user-1794418347151-0.jpg`, `photo.png`, `exif.jpg`, Windows path); a picked file name still shows; the bundled sample is 示例照片; `TrayPhoto.label` uses the same mapping |
| `LogoVersionSubtitleTest` (5 cases) | `desktopTest/.../inspector/LogoVersionSubtitleTest.kt` | `versionSubtitle()`/`yearLabel()` for `—`, blank, `-`, `n.d.`, `n.d. (current site)`, `n.d. (pre-2002)` → `2002 年前`; real years pass through. Plus a **data-driven case**: every one of the 347 shipped `logos.json` versions is rendered through the mapping and must contain no `—`, no `n.d.`, must not end in `·`, and must not be blank — this is what caught the third `n.d.` spelling (`yashica-serif`) during development |
| `PhoneSettingsTextTest.theVersionRowCarriesNoDeveloperMarker` | `commonTest/.../shell/phone/PhoneSettingsTextTest.kt` | `null`/`""`/blank/`null`/`undefined`/`NULL` all render 未知 and contain no `dev`; a real label is verbatim. The two pre-existing `assertEquals("dev", …)` cases were updated because the behaviour is the fix |
| `BatchScreenCopyTest` (2 cases) | `desktopTest/.../workflow/BatchScreenCopyTest.kt` | the production `BATCH_EXPORT_HINT` contains no `E<N>` work-item token and no `q100`, and still states `JPEG 质量 100`, `4:4:4`, `导出目录` (a rewrite, not a deletion) |
| `ExportFailureTest.a_raw_engine_exception_never_becomes_the_user_message` | `desktopTest/.../product/ExportFailureTest.kt` | with a renderer that throws, the failure’s `userMessage` is Chinese and does not contain the throwable text, while `cause.message` still does |
| `UiCopySourceGuardTest` | `desktopTest/.../arch/UiCopySourceGuardTest.kt` | mirrors `EvidenceHookKonsistTest`: walks `commonMain`/`androidMain`/`desktopMain`, blanks comments (offsets and line numbers preserved), and fails if **any string literal** contains `TODO`, `FIXME`, `HACK`, `XXX`, `WIP`, `lorem ipsum`, `debug`, `placeholder` or `pragma`. This is the whole class of findings turned into a gate for the next commit |

`UiCopySourceGuardTest` lives in the `arch/` package next to the Konsist rules and follows
their shape (a whole-tree rule asserted once); the other five are plain `kotlin.test` over a
display-mapping function, which is the cheapest reliable form for a copy fix — no emulator,
no Compose UI test, and a failure names the string.

---

## 5. Verification

```
. 'E:\FrameMark\execution-ui-v2-20261106\tools\env-uiv2.ps1'
$env:PATH = 'C:\Windows\System32;' + $env:PATH
cd E:\FrameMark\work\ui-v2
& $FM_GRADLE -p ui :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid \
    :composeApp:desktopTest --no-daemon --console=plain
```

**Result: green on this audit’s change — 1 119 tests, 0 failures**, including
`ArchAllowlistHygieneKonsistTest`, `EvidenceHookKonsistTest`, `QaSurfaceKonsistTest`,
`SingleSourceKonsistTest`, `PlatformBoundaryKonsistTest`, `ControlRegistryCoverageKonsistTest`
and `WireFormatFreezeTest`.

Because the shared working tree was **not** in a buildable state during the audit (§5.1), the
matrix was run three times:

| run | tree | result |
|---|---|---|
| 15:41–15:45 | `E:\FrameMark\work\ui-v2` (my edits + the other line’s first edits) | **BUILD SUCCESSFUL** for all three tasks; 1 118 tests, 1 failure — my own new data-driven case, which caught a third `n.d.` spelling in `logos.json`; fixed in F2 |
| 16:02–16:04 | same tree, after the other line’s next edit | **BUILD FAILED**, 2 compile errors, both in their files: `NativeStagePreview.kt:176 Unresolved reference 'PREVIEW_MAX_EDGE'`, `DesktopEngineBridge.kt:279 Unresolved reference 'PreviewTier'` — nothing in this audit’s files |
| 16:04–16:07 | clean worktree at `63c8c75` + **only** this audit’s patch | **BUILD SUCCESSFUL** for all three tasks; **1 119 tests, 0 failures**; per-suite: `PhotoTrayLabelTest` 5/5, `LogoVersionSubtitleTest` 5/5, `BatchScreenCopyTest` 2/2, `ExportFailureTest` 5/5, `UiCopySourceGuardTest` 1/1, `PhoneSettingsTextTest` 4/4 |

No distributable was built and nothing was installed or run on the phone.

### 5.1 The working tree is shared (important for whoever commits next)

A second line is concurrently implementing a *preview tier* feature in the same checkout:
`androidMain/cpp/fm_engine_jni.cpp`, `engine/e2b/src/main.rs`,
`AndroidNativeRender.kt`, `DesktopEngineBridge.kt`, `DesktopNativeRender.kt`,
`NativeEngineProbe.kt`, `NativeStagePreview.kt` and a new
`commonMain/.../workflow/PreviewTier.kt` were modified while this audit ran. They also added
`interactive: Boolean` to `NativeRenderApi.render(...)`, which the five test stubs that
override it (`ExportFailureTest`, `DesktopPresetBatchTest` ×2, `PhoneBatchControlTest`,
`ExportCommitProtocolTest`) do not declare yet — so `compileTestKotlinDesktop` will fail for
them independently of this audit.

Consequences for the commit:

* `NativeRender.kt` is the one file both changes touch. My hunk is the `catch (t: Throwable)`
  block at `~:251-259` (F5); theirs is the `interactive` parameter. They are independent, and
  both are in the file now.
* Everything else this audit touched is disjoint from their file set.
* Reproduce the green result with `git worktree add <tmp> 63c8c75`, copy the 15 files listed
  in §3/§4 (the F5 hunk by hand for `NativeRender.kt`), and run the command above.


## 6. What I could not check

1. **Dynamic copy that needs a specific document state.** The batch sheet (F4), the preset
   error pill (F6) and the export-failure dialog (F5, F14) never appear in the 3 262 dumps:
   no dump was taken with a queued batch, a failing preset write or an engine error. Those
   three findings are latent by construction — verified by reading the render site, not by
   seeing them.
2. **TalkBack output.** The rendered sweep reads `content-desc=` from the accessibility
   tree, which is what TalkBack consumes, but the actual spoken order/duplication of a
   merged node (`mergeDescendants = true` with a labelled parent) is not visible in a dump.
   F1 is fixed at the source of the description; the *audible* result was not re-recorded
   (no device available — another line is using it).
3. **Desktop-only surfaces** (`ExportDialog`, `BatchWorkspace`, `DesktopStagePane`,
   `DesktopFilmstrip`) have no UIAutomator dumps at all: the evidence corpus is phone-only.
   Their findings are source-verified only.
4. **States behind a permission, a picker or a dialog** — the system photo picker, the
   share sheet, the SAF document picker, the "从相册选择照片" flow. Their strings belong to
   Android; the only one that surfaces in our dumps is `Preview Icon` (F18), which is
   `com.android.photopicker`.
5. **Every logo brand page.** Only Sony and Leica pages were captured, so 346 of the 347
   version names (F7) were judged from the data file and the render site, not from a
   screenshot. The data-driven test in §4 is what makes that class safe to reason about.
6. **Dynamic strings built from user data** (photo file names, project names, preset
   names) are shown deliberately and are outside the audit; only the *app-generated* names
   were treated as internal.
7. **Turkish/Chinese locale switching** — labels are hard-coded Chinese (`forDisplayOption`,
   `yearLabel`); there is no resource-based i18n to check.

## 7. Residual risks worth a follow-up

* `workflow/FakeEngine.kt` still draws `示例导出` / `FrameMark 占位图` into a real JPEG/PNG.
  Its only caller today is `desktopMain/.../verify/WorkflowVerify.kt`, but the class
  doc-comment calls it “used only when the platform has no native renderer (Android this
  round)” — if that fallback is ever wired to a product path, a user will export a
  placeholder card that says “占位图”.
* `DesktopEngineBridge.kt` (≈`:385-403`) builds English warnings that include `E5 worker
  protocol v1`; only the Chinese one is ever read (`DesktopNativeRender.kt:129` picks
  `it.contains("不支持完整原尺寸")`), so nothing ships — but `warnings` is a `List<String>`
  ready to be surfaced.
* F7/F8/F9/F14/F16 are one policy decision plus one mapping function each; the audit left
  the code points and the proposed patch in §2.1 so they can be applied in one pass.

---

## Appendix — artefacts

Everything below is reproducible by re-running the scripts in place.

| file | content |
|---|---|
| `reports/ui-copy-audit-20261113/rendered-strings.tsv` | **1 209 distinct rendered strings** with occurrence counts and heuristic flags — the vocabulary to skim |
| `reports/ui-copy-audit-20261113/rendered-occurrences.tsv` | 28 126 rows: dump file, `text`/`content-desc`, value — trace any string to its screen |
| `reports/ui-copy-audit-20261113/source-literals.json` | production string literals with file, line and tags (7 791 on the current tree) |
| `reports/ui-copy-audit-20261113/resource-hits.tsv` | every text-bearing JSON value that looked suspicious or ASCII-only |
| `reports/ui-copy-audit-20261113/ascii-text-position.tsv` | 243 ASCII-only literals in a UI text position |
| `reports/ui-copy-audit-20261113/sweep-source.py` | source sweep (comment stripping + literal extraction) |
| `reports/ui-copy-audit-20261113/sweep-resources.py` | resource sweep |
| `reports/ui-copy-audit-20261113/sweep-rendered.py` | dump sweep (app-package filter, clustering, flags) |
| `reports/ui-copy-audit-20261113/sweep-text-position.py` | literal → text-position correlation |
| `reports/ui-copy-audit-20261113/xref-logos.py` | `logos.json` version names vs. rendered strings |

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

1. a bare catalogue number at the end -> `第 N 款` (`Canon 4` -> `Canon 第 4 款`; **50 names**);
2. a trailing ` Black` / ` White` -> `黑色款` / `白色款` — **every one of them collapsed to
   `字标`,** because all 40 tone-named versions ship both inks and the cut row already appends
   黑标/白标, so `Canon 黑色款 · 白标` would have contradicted itself;
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
