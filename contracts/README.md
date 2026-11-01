# FrameMark contracts v1 (C1)

Two frozen contracts plus their fixtures and a zero-dependency validator.

| File | Purpose |
|---|---|
| `render-v1.schema.json` | `FrameMarkRender v1`: request/response between UI and engine (task book 3.3) |
| `capabilities.schema.json` | Per-card inspector description; the UI generates every section/control from this |
| `fixtures/` | 10 valid request/response pairs, 1 deliberately invalid request, 1 capabilities sample |
| `validate.mjs` | Structural + cross-fixture validator (no npm packages) |

## 1. Freeze rules

1. **Append-only.** New *optional* properties may be added to either schema. No
   renames, no type changes, no removals, no new required properties, no tightened
   ranges — those are breaking changes and need `schemaVersion: 2` plus both sides
   (UI + engine) shipping together.
2. **Version gate.** Every request and response carries `schemaVersion: 1`.
   A document with any other value is rejected by the validator and must be
   rejected by the engine and the UI.
3. **Consumer order.** UI writes the request; engine validates it against this
   schema before rendering; engine writes the response; UI validates it before
   drawing. A contract violation is a bug, not a fallback path.
4. **Fixtures are the executable part of the contract.** Any schema change must
   land with fixtures that exercise it. `node contracts/validate.mjs` must stay
   green (and must still reject `fixtures/invalid.request.json`).

## 2. Image payload rule (paths, not bytes)

- The source photo travels as `photo.path` + intrinsic `photo.w/h`.
  A 50 MP photo is ~150-300 MB raw; embedding it in JSON would defeat the
  ≤600 MB export budget before decoding even starts. `photo.bytes`,
  `photo.data`, base64 payloads and similar keys are rejected outright
  (`additionalProperties: false`).
- The rendered image travels as `image.path` + `image.w/h`.
- The only inline payload is `image.rgba`: base64 of raw RGBA8888 rows,
  decoded length exactly `w*h*4`, allowed only when `max(w,h) <= 512` (small
  previews and thumbnails). Anything larger returns a path.

## 3. Geometry and band rule

- `photoRect` and every `textSlots[]` entry are in **the returned image's pixel
  space** (origin top-left, fractional values allowed).
- `mode: "preview"` carries `maxEdge` (longest edge of the returned image) and
  must not carry `band`. The returned image edge must be `<= maxEdge`.
- `mode: "export"` must not carry `maxEdge`; `band: {y,h}` requests one horizontal
  strip of the full-size output. When a band is present the response `image` is
  exactly that strip and all geometry is **band-local** (add `band.y` to obtain
  canvas space). Negative `y`/`x` are legal (photo bleed).
- `textSlots[].id` must be one of the design's text slot names; ids are unique
  within a response.

## 4. FakeEngine expectations (until X1)

- FakeEngine returns a golden/preview image path plus a truthful `photoRect` and
  `textSlots`, and sets `engine: "fake"`. `ms` is the measured fake-engine time,
  not a real render time.
- It must never claim `engine: "native"`, and the UI must visibly mark fake output
  (static preview) — a static golden image is not "live parameter rendering".
- Every fixture in `fixtures/` is authored to the contract by hand (no engine
  involved), so `engine: "fake"` is the honest value there too.
- X1 replaces the implementation only; request/response shapes must not change.

## 5. Capabilities: UI is generated, labels are copy

- One card per `styleId`; sections and controls are rendered by generic
  widgets keyed on `control.type` (`slider`, `toggle`, `segmented`, `select`,
  `color`, `text`, `number`, `stepper`).
- `label` (and option `label`) is user-visible copy. It must never be a schema
  key, a field id or a raw enum value; the validator rejects labels such as
  `default`, `frameInset`, `min` (rule: schemas keys + `snake_case`/`dotted`/
  `$`-prefixed/`camelCase` identifiers).
- `gated: true` = the capability exists but is disabled by the backend today
  (244 such controls). Default presentation: hidden; may only surface behind an
  explicit capability switch. `visible: false` = intentionally omitted for this
  card. `gated: true` + `visible: true` is contradictory and rejected.
- Range controls must be renderable without further data: `slider`/`number`/
  `stepper` require `default`, `min`, `max` (`slider` also `step`);
  `segmented`/`select` require a non-empty `options` list with human labels.
- No per-card Compose code. If a card needs a control that does not fit the
  eight types, the schema grows a type (append-only) — the UI stays generic.

## 6. Validator

```
node contracts/validate.mjs                        # gate: valid must pass, invalid must fail
node contracts/validate.mjs --no-expected-invalid  # everything must pass (shows the seeded defect as red)
node contracts/validate.mjs --fixtures <dir>       # run against a copy (regression experiments)
```

Exit 0 = green, 1 = red. It checks the JSON Schema subset used here
(`allOf`/`anyOf`/`oneOf`/`not`/`if-then-else`, local `$ref`, types, enums,
ranges, patterns) plus the cross-document rules that a schema cannot express:
pair completeness, preview/export geometry, band-local strip height, slot-id
membership, `rgba` byte length, duplicate ids, and the label-copy rule.

Evidence for the red-then-green run (2026-10-30):
`_research/native/u0-c1/validate-red.txt` (seeded defect reported, exit 1) and
`_research/native/u0-c1/validate-green.txt` (gate, exit 0).

## 7. Overlay (v1 additive, 2026-10-30)

A render request may carry one optional `overlay` object: a user watermark
(transparent PNG/WebP) that is independent of the border/design.

```json
{
  "overlay": {
    "path": "D:/photos/watermark/crown.png",
    "rect": { "x": 0.62, "y": 0.74, "w": 0.3, "h": 0.18 },
    "opacity": 0.6
  }
}
```

- `path` — file on disk, same rule as `photo.path` (never inline bytes).
- `rect` — normalised to the **photo draw box** (the response `photoRect`),
  `0..1` on both axes. The overlay is *contained* inside it and centred; it is
  never stretched. Parts outside the photo box are clipped.
- `opacity` — `0..1`, multiplies the image alpha.
- **Z-order**: above the photo layer, below every border/text/logo the template
  paints afterwards. A full-bleed tint the template draws after the photo (for
  example the overlay template gradient) stays above the watermark.
- **Errors**: a missing or undecodable `path` is a hard error on the engine
  side (non-zero exit + message). It must never degrade to an empty layer.
- Absent `overlay` = exactly the previous output (byte-identical).

Fixtures: **`fixtures/` stays exactly as frozen** — 10 valid request/response
pairs plus the one `invalid.request.json`; nothing was added, removed or edited
for this field. The overlay samples live outside the frozen set, in
`_research/native/overlay/fixtures/`:
`overlay-valid.request.json` (valid) and `invalid.overlay.request.json`
(rect.x 1.2 / opacity 1.4, must be rejected). They are checked against a scratch
copy of the frozen fixtures (`validate.mjs --fixtures <copy>`), so the gate on
`framemark/contracts/fixtures` keeps its original 22 files / 10 pairs / 1
expected-invalid.

Evidence: `_research/native/overlay/validate-green.txt` (frozen gate, exit 0),
`_research/native/overlay/validate-overlay-samples.txt` (samples on a copy,
exit 0) and `...-red.txt` (exit 1). Engine evidence:
`_research/native/OVERLAY-API.md`.
