# legacy-web — frozen Electron/Capacitor implementation

This directory is a **frozen historical snapshot** of the original FrameMark
Electron + Capacitor app (React renderer, Electron main/preload, the system-camera
style/controls pipeline that fed it). It is kept only for behaviour and data
comparison against the native product.

It is **not** part of the native product, **not** part of any build, and **not**
verified by any gate. Nothing in `scripts/native/`, `ui/`, `engine/` or `bridge/`
may read it, and it must not be reintroduced as an input to the build.

## What used to be read from here

Exactly one thing: three stock-style control documents.

| Old path (in this directory) | Current, product-owned path |
| --- | --- |
| `src/renderer/src/core/controls/data/controls.oppo.json` | `bridge/desktop/controls-data/controls.oppo.json` |
| `src/renderer/src/core/controls/data/controls.xiaomi.json` | `bridge/desktop/controls-data/controls.xiaomi.json` |
| `src/renderer/src/core/controls/data/controls.vivo.json` | `bridge/desktop/controls-data/controls.vivo.json` |

They moved in the same change that created this directory. The native consumers
are `scripts/native/build.cjs` (hash inputs), `scripts/native/runtime.cjs`
(staged into the desktop runtime as `data/registry/controls.*.json`) and the
desktop registry lookup in
`ui/composeApp/src/desktopMain/kotlin/com/framemark/native/engine/RegistrySource.kt`.
`scripts/native/build.cjs` enforces this with the required gate
`no-legacy-web-dependency`, which fails the run if either native script names
`src/renderer` again or if a control document is missing / unparsable.

## Layout

| Path | Origin |
| --- | --- |
| `src/` | repository `src/` (React renderer, Electron main/preload, mock assets) |
| `capacitor.config.json` | repository root |
| `electron.vite.config.ts` | repository root |
| `scripts/` | the repository `scripts/` one-offs that name `src/renderer/...` (see below) |

## What was deliberately left at the repository root

The move was limited to the web app itself. These root files stay where they are:

- `package.json`, `package-lock.json` — **kept on purpose**: they are live inputs
  to the native build. `scripts/native/build.cjs` (`verifyLogoImageToolchain`)
  and `scripts/native/runtime.cjs` parse both to prove the pinned `sharp`
  dependency (`0.35.5`, registry URL and SHA-512 integrity) matches the isolated
  logo-tools install. Moving them would break the build.
- `tsconfig.json`, `tsconfig.node.json`, `tsconfig.web.json` — the legacy
  TypeScript project files. They are not read by the native build and were left
  untouched; their `include` globs point at the paths this directory used to own
  and are therefore inert. They are historical, like this directory.
- `scripts/` still holds the tooling the **native** build reads
  (`scripts/native/**`, `scripts/system-camera/**`), the harness-side
  `scripts/check-subagent-config.cjs`, and the frozen one-offs that do not name
  `src/renderer`; the ones that did were moved into `legacy-web/scripts/` (see the
  next section).

## Legacy scripts moved here

The scripts that still reached into `src/renderer/...` were frozen historical
generation/analysis helpers, not build steps: nothing in `scripts/native/`,
`ui/`, `engine/`, `bridge/` or the release gate invokes them, and the only document references are
inside the Electron-era sections that `PROJECT-BASELINE.md` itself marks as frozen
2026-10-25/27 history (`PROJECT-BASELINE.md` W0c and `REQUIREMENTS.md` §7); both
are historical completion records, not current guidance, and were left as written.
They were moved with `git mv` so the relative
layout from `scripts/` is preserved:

| Moved file (was `scripts/…`, now `legacy-web/scripts/…`) | What it did |
| --- | --- |
| `_geometry-diff.cjs` | L2 47-set preview geometry diff against the original app |
| `_measure-lines.cjs`, `_measure-photo-rect.cjs`, `_measure-regions.cjs`, `_measure-structure.cjs` | one-off pixel measurements of the original previews |
| `_remap_variants.py` | rewrote `core/variants.ts` variant rows |
| `_s1-history-unit.cjs` | bundled `core/history.ts` for a unit check |
| `analyze-preview-footers.cjs`, `analyze-previews.cjs`, `analyze-references.cjs` | original vs re-rendered preview analysis |
| `apply-mobile-css.cjs` | appended the mobile block to `src/styles.css` |
| `audit-original47.cjs` | 47-set template audit |
| `build-taxonomy.mjs` | generated `core/taxonomy.generated.ts` from the prototype data |
| `build-variant-previews.cjs` | re-rendered variant previews through Electron |
| `check-editor-tabs.mjs` | control → editor-tab partition audit |
| `control-differential.cjs` | control differential runner (Electron harness) |
| `import-missing-brand-logos.cjs`, `import-real-brand-logos.cjs` | brand logo import probes |
| `make-brand-logos.cjs` | placeholder brand-logo sheet generator |
| `logo-pipeline/apply-selection.mjs`, `logo-pipeline/build-catalog.mjs`, `logo-pipeline/build-selection-html.mjs`, `logo-pipeline/check-compat.mjs`, `logo-pipeline/check-resolve.mjs`, `logo-pipeline/render-matrix.cjs`, `logo-pipeline/verify-catalog.mjs`, `logo-pipeline/curation.json` | the logo catalog / selection pipeline and its curation data |

Path constants were rewritten only where the move changed what they resolve to:

- `_geometry-diff.cjs`, `audit-original47.cjs`, `build-taxonomy.mjs`,
  `build-variant-previews.cjs`, `control-differential.cjs` — the base
  (`__dirname/..`) still has to mean the repository root (they also read
  `_research/`, `out/` or use it as the Electron `cwd`), so it became
  `__dirname/../..` and their renderer paths now read
  `legacy-web/src/renderer/...`.
- `apply-mobile-css.cjs` (`src/renderer/src/styles.css`) and `_remap_variants.py`
  (`src/renderer/src/core/variants.ts`) are CWD-relative: they take the
  `legacy-web/` prefix.
- `_s1-history-unit.cjs`, `make-brand-logos.cjs` and `logo-pipeline/*` resolve
  their app root through `__dirname` *inside* this directory, so the base is
  already `legacy-web/` and their existing `src/renderer/...` joins stay correct
  without edits.
- Constant `D:/border&watermark/framemark/...` paths were left as written: they
  name the historical Electron checkout outside this repository, not this tree.

Two deliberate exceptions stay in `scripts/` (both still referenced by the live
root `package.json`, which the native build parses for the pinned `sharp` pin and
therefore must not be edited):

- `scripts/package-win.mjs` — `npm run package:win` in the root manifest invokes
  it. Nothing else calls it; it packages the frozen Electron app, which this
  directory explains has no supported entry point.
- `scripts/system-camera/**` — `package.json`'s `validate:ir` / `previews:stock`
  entries point at it.

## Running it

Do not. There is no supported entry point here: dependencies are not installed,
the toolchain is not pinned, and no CI job builds it. Read the source if you need
historical evidence; do not expect it to run.
