# FrameMark UI data bundle (E1a)

Offline data for the native UI (`ui/`) so the border library, inspector, logo
picker and font loader work without the TypeScript engine. Generated from the
frozen source at `8323aac`; the source tree is read-only for this slice.

```
engine/data/
  taxonomy.json      175 cards / 7 categories (ids, names, vendor, family, badge, declaredText, logos)
  controls.json      439 platform controls (3 vendor docs, verbatim) + 45 hand controls (8 panels)
  capabilities.json  per-card live/gated controls joined from _research/s7/control-audit.json
  previews.json      card -> preview file (+sha256, 480x360 webp)
  logos.json         brand/version/cut -> shipped webp (+sha256)
  fonts.json         family/file/face incl. the AvenirNext.ttc trap
  meta.json          source paths + sha256, counts, task-book expectations vs actual
```

Everything is produced by:

```
node engine/tools/build-data.mjs     # regenerate
node engine/tools/check-data.mjs     # self-check (PASS/FAIL/GAP + summary, exit 1 on FAIL)
```

The generator bundles `core/variants.ts`, `core/taxonomy.generated.ts`,
`core/controls/hand.generated.ts`, `core/logo-catalog.ts` and
`core/logo-selection.generated.ts` with esbuild **in memory** and stubs only
`import.meta.glob` (a Vite URL helper). No file under `src/` is written, no npm
package is installed.

## Schema version and paths

Every file carries `schemaVersion: "framemark-<name>/v1"` and `generatedAt`.
Fields are additive-only until a new `/v2` is agreed.

All asset paths are **repo-relative POSIX** strings, e.g.
`src/renderer/src/assets/previews/system/stock-oppo-hassel_style_1.webp`.
The native app must remap the `src/renderer/src/assets/` prefix to its own
asset root; the relative tail (`previews/...`, `logos/...`, `system/...`) is
stable.

## How the UI loads it

Startup (or before the library screen) read the 6 data files once; they are
small (taxonomy 105 KB, controls 800 KB, capabilities 241 KB, previews 102 KB,
logos 356 KB, fonts 25 KB) and immutable.

1. **Library** — `taxonomy.categories` for the 7 tabs (order and Chinese names
   are the source order), `taxonomy.cards` for tiles. Card key = `id`.
   - `category` is the taxonomy key (`vendor`/`text`/...), `categoryName` its
     label. `brand` is the vendor chip shown in the current UI (`OPPO`/`小米`/
     `vivo`, null elsewhere). `vendor` is the lower-case vendor from the
     template variant (null on the 68 hand templates).
   - `badge`, `fidelity`, `autoLogo`, `vendorLogo`, `declaredText` are copied
     verbatim; null means the source had no value.
   - `systemStyle` is the IR style id for the 106 stock cards (joins to
     `controls.sources[].doc.records[].styleId`); null for hand cards, whose
     controls come from `capabilities`.
2. **Preview** — `previews.rows` is keyed by `cardId`; `layer` says whether the
   file is a rendered card (`rendered`, 69) or a vendor screenshot (`system`,
   106). `path` is the library-card image (`VariantThumb` uses
   `renderedPreview ?? preview`). `declaredPreviewPath` / `inspectorPath` keep
   the legacy screenshot still declared by 47 hand cards, which the current
   Inspector prefers (`preview ?? renderedPreview`); a native UI that wants one
   image per card should use `path` and ignore `inspectorPath`.
   `missing` is empty at this revision.
3. **Inspector** —
   - hand cards (69 audited): `capabilities.cards[styleId]` with
     `audited: true`, `effective[]` (live controls) and `gated[]` (no-op).
     Gated controls must be hidden by default but kept in the capability set
     (task-book debt H4).
   - stock cards (106): `capabilities.cards[styleId].audited === false` with
     `effective/gated = null`. This means **not audited**, not "no controls".
     Use `controls.rows` (scope `platform`) for the declared control set; do
     not render an empty inspector.
   - `controls.rows[*].default` / `.domain` are the exact source defaults and
     option lists (labels/options are not normalised); the full source record
     is also embedded under `controls.sources[].doc` for fields not flattened.
   - hand controls: `controls.hand.panels` is the verbatim `HAND_PANELS` array
     (8 panels / 45 controls, with `kind`, `min/max/step`, `options`, `tab`).
4. **Logo picker** — `logos.versions` (268) each carry `selected` (218 chosen in
   `logo-selection.generated.ts`), `brandKey`, `status`, `surface` and `cuts[]`.
   Each cut has `resolvedPath` (null if not shipped) and `sha256`. The
   `logos.files` map is the single hash table for all 338 shipped webp files.
   Version ids match the catalog (`LOGO_VERSION_BY_ID`); cut `assetKey`
   `ver:<brand>:<cut>` matches `logoVersionAssetUrl`.
5. **Fonts** — `fonts.faceRules` is the CSS `@font-face` list (21 rules, family,
   file, weight, style); `fonts.files` adds sha256 and, for the 21 sfnt files,
   the parsed name table (`family/subfamily/fullName/postscript/weightClass`);
   the single woff2 is recorded as a webfont container without name parsing.
   `fonts.uiFamilies` records the family stacks from `system/fonts.ts` and
   `draw.ts`. A native loader must load the per-file TTFs/OTFs, never a system
   fallback.
6. **Provenance** — `meta.json` pins every source path + sha256 and the git
   HEAD; re-run the check after any source change.

## Snapshot (this build)

| item | count |
|---|---|
| cards / categories | 175 / 7 (99 vendor, 21 text, 13 impression, 12 white, 19 card, 6 color, 5 minimal) |
| vendor category brands | OPPO 45 / 小米 42 / vivo 12 |
| cards with a `vendor` value | 107 (oppo 53 / xiaomi 40 / vivo 14); 68 hand cards null |
| platform controls | 439 in 106 records (oppo 130, xiaomi 266, vivo 43) |
| hand controls | 45 in 8 panels |
| control audit | 996 rows = 752 live + 244 no-op, 69 cards |
| previews | 175/175 (106 system + 69 rendered), 480x360 |
| logos | 268 versions (218 selected), 330 cuts, 155 app webp + 183 version webp |
| fonts | 22 files (16 ttf, 4 otf, 1 ttc, 1 woff2), 21 `@font-face` rules |

## Known gaps / discrepancies (recorded, not fixed)

- **vendor 99 vs 107** — the task book's `vendor 99` is the *category*
  cardinality and it matches. 107 cards carry a vendor value because 8 vendor
  styles are filed under text/card/color/minimal. Hand templates have no vendor
  in source, so `vendor` is null (not guessed).
- **brand split** — taxonomy `brand` is OPPO 45 / 小米 42 / vivo 12; the variant
  files say oppo 47 / xiaomi 40 / vivo 12. Exactly two cards disagree:
  `stock-oppo-camerapro-tone_gallery_frame_style_1` and
  `stock-oppo-camerapro-leica_live_moment_style_1` (taxonomy brand 小米,
  `variant.vendor` oppo). Both values are kept.
- **audit scope** — `control-audit.json` covers 69 cards, not 175; 106 cards are
  `audited: false`. Its inventory is the 996-control hand-control superset
  (`_research/hand-controls/controls.hand.json`), not the 439+45 UI inventory.
- **audit path** — the task book says `framemark/_research/s7/control-audit.json`.
  `framemark/_research` is local/gitignored and absent; the hashed file lives at
  `D:/border&watermark/_research/s7/control-audit.json` (generated
  2026-10-28T10:51:29Z, sha256 in `capabilities.json` / `meta.json`).
- **fonts** — `AvenirNext.ttc` has 12 faces; face #0 is **Bold** (700). Chromium
  CSS `src()` only takes face #0, so the `.ttc` is not referenced by any
  `@font-face`; four single-face TTFs extracted from it are used instead
  (#7 Regular, #5 Medium, #2 DemiBold, #0 Bold — verified by name table in
  `fonts.extractionDeclaredByCssComment`). Do not load the `.ttc` by family.
- **font size** — task book says ~17.6 MB; the bundle is 20.56 MB decimal
  (`FZCYSK.TTF` 10.52 MB, `AvenirNext.ttc` 4.99 MB). The quote omits the other
  vendor fonts. Nothing was removed or modified.
- **badge/fidelity/declaredText** — null on 29 / 68 / 28 cards respectively
  (absent in source).
- **unreferenced assets** — 7 `assets/logos` files are not referenced by any
  catalog cut or card `vendorLogo`. All 222 files under `assets/previews/` are accounted for:
  175 card previews + 47 legacy card screenshots declared as fallbacks. Counted,
  not deleted.
- **raw control-id repeats** — 63 unique raw ids across 439 platform controls
  (ids repeat per style) and 35 unique across 45 hand controls (ids repeat per
  panel). `rowId` (`<styleId|panel>::<controlId>`) is the unique key; the raw
  `id` is preserved untouched inside each control object.

## Rules

- Do not hand-edit these files; regenerate from source and re-run the check.
- `meta.json` records the source hashes; a mismatch means the data is stale.
- The check is deliberately allowed to print GAP lines for source-data gaps; a
  FAIL line (or non-zero exit) means the bundle is broken or drifted.
