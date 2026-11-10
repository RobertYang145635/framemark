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
- `scripts/` legacy tooling (the `logo-pipeline/`, `system-camera/` and
  `analyze-*`/`audit-*` one-offs) still names `src/renderer/...` paths. Those
  scripts are historical generation/analysis helpers, not build steps, and were
  not rewritten by this move.

## Running it

Do not. There is no supported entry point here: dependencies are not installed,
the toolchain is not pinned, and no CI job builds it. Read the source if you need
historical evidence; do not expect it to run.
