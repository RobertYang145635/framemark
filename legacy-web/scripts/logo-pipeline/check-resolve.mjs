/**
 * Phase 1b-2a verification for the renderer threading of Logo Library versions.
 *
 * Bundles `core/system/assets.ts` (which pulls in the generated catalog, the
 * legacy brand registry and `logo-compat`) with esbuild, stubbing Vite's
 * `import.meta.glob` and the `ver:` URL module (both are build-time only), then
 * drives the PURE `styleBrandFile()` resolver:
 *
 *   - a version with only a light cut returns null on dark, its assetKey on light;
 *   - a two-tone version returns the light cut for light, the dark cut for dark;
 *   - legacy `_black` pick on dark → null, `_white` pick on light → null
 *     (compatible counterparts still resolve);
 *   - a `vendorLogo` comes back unchanged on both surfaces;
 *   - an explicit version that cannot fit does NOT fall through to
 *     pick/vendorLogo/autoLogo;
 *   - `surfaceFromBackground` uses the same BT.601 luma cut as `paint.ts`.
 *
 * Prints a compact report; exits non-zero on any failure.
 */
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const APP_ROOT = path.resolve(__dirname, '../..')

/** The `ver:` URL registry is a Vite-only module; resolution is not under test. */
const stubVersionAssets = {
  name: 'stub-logo-version-assets',
  setup(b) {
    b.onResolve({ filter: /logo-version-assets$/ }, () => ({ path: 'logo-version-assets-stub', namespace: 'fm-stub' }))
    b.onLoad({ filter: /.*/, namespace: 'fm-stub' }, (args) => {
      if (args.path !== 'logo-version-assets-stub') return null
      return {
        loader: 'js',
        contents: [
          'export function logoVersionAssetUrl() { return null }',
          'export function hasLogoVersionAsset() { return false }'
        ].join('\n')
      }
    })
  }
}

/**
 * `logo-files.ts` fills its registry from `import.meta.glob`, which node cannot
 * evaluate. Rebuild the same KEYS from the real folder so `BRANDS[].files`
 * (filtered by `LOGO_FILES[f]`) is populated for the risk report. The URLs are
 * stubs: nothing here decodes a bitmap.
 */
const LOGO_DIR = path.join(APP_ROOT, 'src/renderer/src/assets/logos')
const logoStems = fs
  .readdirSync(LOGO_DIR)
  .filter((f) => f.toLowerCase().endsWith('.webp'))
  .map((f) => f.replace(/\.webp$/i, ''))
  .filter((n) => n !== 'logo' && n !== 'logo_empty')
const stubLogoFiles = {
  name: 'stub-logo-files',
  setup(b) {
    b.onResolve({ filter: /(^|\/)logo-files$/ }, () => ({ path: 'logo-files-stub', namespace: 'fm-stub' }))
    b.onLoad({ filter: /.*/, namespace: 'fm-stub' }, (args) => {
      if (args.path !== 'logo-files-stub') return null
      return {
        loader: 'js',
        contents: `export const LOGO_FILES = { ${logoStems
          .map((n) => `${JSON.stringify(n)}: "stub://${n}"`)
          .join(', ')} }`
      }
    })
  }
}

const result = await build({
  stdin: {
    contents: [
      `export { styleBrandFile, chooseBrandSlot } from './src/renderer/src/core/system/assets'`,
      `export { surfaceFromBackground, cutFitsSurface, cutForVersionSurface } from './src/renderer/src/core/logo-compat'`,
      `export { LOGO_VERSIONS } from './src/renderer/src/core/logo-catalog'`,
      `export { BRANDS, pickVariant, logoCutForFile } from './src/renderer/src/core/brands'`,
      `export { STOCK_STYLE_LIST } from './src/renderer/src/core/system/styles'`
    ].join('\n'),
    resolveDir: APP_ROOT,
    sourcefile: 'check-resolve-entry.ts',
    loader: 'ts'
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  write: false,
  logLevel: 'silent',
  plugins: [stubVersionAssets, stubLogoFiles],
  // `import.meta.glob()` is a Vite transform. At import time in node it would
  // throw, so both the asset maps become empty records.
  define: { 'import.meta.glob': '__fmGlob' },
  banner: { js: 'const __fmGlob = () => ({})' }
})

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'framemark-resolve-check-'))
const tmpFile = path.join(tmpDir, 'logo-resolve-check.mjs')
fs.writeFileSync(tmpFile, result.outputFiles[0].text)
const mod = await import(pathToFileURL(tmpFile).href)

const {
  LOGO_VERSIONS,
  styleBrandFile,
  surfaceFromBackground,
  cutFitsSurface,
  cutForVersionSurface,
  BRANDS,
  pickVariant,
  logoCutForFile,
  STOCK_STYLE_LIST
} = mod

const failures = []
function check(name, ok, detail = '') {
  if (!ok) failures.push(`${name}${detail ? `: ${detail}` : ''}`)
}

function refFor(version) {
  const cut = version.cuts[0]
  return {
    brandKey: version.brandKey,
    versionId: version.id,
    cutId: version.primaryCut ?? cut.id,
    assetKey: version.primaryCut ?? cut.assetKey,
    file: version.primaryCut ?? cut.assetKey,
    ink: cut.ink,
    surface: cut.surface,
    name: version.name
  }
}

// 1) Light-only versions: null on dark, assetKey on light.
const lightOnly = LOGO_VERSIONS.filter(
  (v) => v.cuts.length > 0 && v.cuts.every((c) => c.surface === 'light')
)
let lightOnlyChecked = 0
let lightOnlyBad = 0
for (const version of lightOnly) {
  lightOnlyChecked++
  const ref = refFor(version)
  const onDark = styleBrandFile({ version: ref }, {}, 'dark')
  const onLight = styleBrandFile({ version: ref }, {}, 'light')
  const wantLight = cutForVersionSurface(version, 'light')
  if (onDark !== null) {
    lightOnlyBad++
    check(`light-only version hidden on dark (${version.id})`, false, String(onDark))
  }
  if (!wantLight || onLight !== wantLight.assetKey) {
    lightOnlyBad++
    check(`light-only version resolves on light (${version.id})`, false, `${onLight} != ${wantLight?.assetKey}`)
  }
}
check('light-only versions: null on dark, cut on light', lightOnlyChecked > 0 && lightOnlyBad === 0, `${lightOnlyBad}/${lightOnlyChecked}`)

// 2) Two-tone versions: light cut for light, dark cut for dark.
const twoTone = LOGO_VERSIONS.filter((v) => {
  const light = v.cuts.filter((c) => c.surface === 'light')
  const dark = v.cuts.filter((c) => c.surface === 'dark')
  return light.length >= 1 && dark.length >= 1 && v.cuts.every((c) => c.surface !== 'both')
})
let twoToneChecked = 0
let twoToneBad = 0
for (const version of twoTone) {
  twoToneChecked++
  const ref = refFor(version)
  const onLight = styleBrandFile({ version: ref }, {}, 'light')
  const onDark = styleBrandFile({ version: ref }, {}, 'dark')
  const wantLight = cutForVersionSurface(version, 'light')
  const wantDark = cutForVersionSurface(version, 'dark')
  const lightCut = version.cuts.find((c) => c.assetKey === onLight)
  const darkCut = version.cuts.find((c) => c.assetKey === onDark)
  if (!wantLight || !wantDark || onLight !== wantLight.assetKey || onDark !== wantDark.assetKey) {
    twoToneBad++
    check(`two-tone version resolves per surface (${version.id})`, false, `${onLight}/${onDark}`)
    continue
  }
  if (!lightCut || lightCut.surface !== 'light' || !cutFitsSurface(lightCut, 'light')) {
    twoToneBad++
    check(`light pick is the light cut (${version.id})`, false, `${onLight} ${lightCut?.surface}`)
  }
  if (!darkCut || darkCut.surface !== 'dark' || !cutFitsSurface(darkCut, 'dark')) {
    twoToneBad++
    check(`dark pick is the dark cut (${version.id})`, false, `${onDark} ${darkCut?.surface}`)
  }
  if (onLight === onDark) {
    twoToneBad++
    check(`two-tone picks differ (${version.id})`, false, onLight)
  }
}
check('two-tone versions: light cut on light, dark cut on dark', twoToneChecked > 0 && twoToneBad === 0, `${twoToneBad}/${twoToneChecked}`)

// 3) Legacy picks cannot cross tones.
const blackOnDark = styleBrandFile({ pick: 'fushi_black' }, {}, 'dark')
const blackOnLight = styleBrandFile({ pick: 'fushi_black' }, {}, 'light')
const whiteOnLight = styleBrandFile({ pick: 'fushi_white' }, {}, 'light')
const whiteOnDark = styleBrandFile({ pick: 'fushi_white' }, {}, 'dark')
const blackCut = LOGO_VERSIONS.flatMap((v) => v.cuts).find((c) => c.assetKey === 'fushi_black')
const whiteCut = LOGO_VERSIONS.flatMap((v) => v.cuts).find((c) => c.assetKey === 'fushi_white')
check('legacy _black on dark → null', blackCut && blackOnDark === null, String(blackOnDark))
check('legacy _black on light resolves', blackCut && blackOnLight === blackCut.assetKey, String(blackOnLight))
check('legacy _white on light → null', whiteCut && whiteOnLight === null, String(whiteOnLight))
check('legacy _white on dark resolves', whiteCut && whiteOnDark === whiteCut.assetKey, String(whiteOnDark))

// 3b) Extension / logo- prefix normalisation keeps the same verdict.
const prefixed = styleBrandFile({ pick: 'logo-fushi_black.webp' }, {}, 'dark')
check('legacy prefixed/ext black on dark → null', prefixed === null, String(prefixed))

// 3c) Un-catalogued hand-dropped picks pass through untouched.
const dropped = styleBrandFile({ pick: 'my-hand-logo' }, {}, 'dark')
check('uncatalogued pick passes through', dropped === 'my-hand-logo', String(dropped))

// 4) vendorLogo is fixed art: returned as-is on both surfaces.
for (const surface of ['light', 'dark']) {
  const v = styleBrandFile({ vendorLogo: 'laika' }, {}, surface)
  check(`vendorLogo unchanged on ${surface}`, v === 'laika', String(v))
}

// 5) An incompatible version never falls through to pick/vendorLogo/autoLogo.
let noFallthroughChecked = 0
let noFallthroughBad = 0
for (const version of lightOnly.slice(0, 200)) {
  noFallthroughChecked++
  const ref = refFor(version)
  const onDark = styleBrandFile(
    { version: ref, pick: 'fushi_black', vendorLogo: 'laika', autoLogo: true },
    { make: 'FUJIFILM', model: 'X-T5' },
    'dark'
  )
  if (onDark !== null) {
    noFallthroughBad++
    check(`no fallthrough on dark (${version.id})`, false, String(onDark))
  }
}
check('incompatible version does not fall through', noFallthroughChecked > 0 && noFallthroughBad === 0, `${noFallthroughBad}/${noFallthroughChecked}`)

// 5b) Unknown versionId ⇒ null, not legacy fallback.
const ghost = styleBrandFile(
  { version: { ...refFor(lightOnly[0]), versionId: 'no-such-version' }, pick: 'laika', vendorLogo: 'laika', autoLogo: true },
  { make: 'FUJIFILM', model: 'X-T5' },
  'light'
)
check('unknown versionId → null (no fallback)', ghost === null, String(ghost))

// 6) Auto-logo: compatible brands still resolve; incompatible ones return null.
let autoChecked = 0
let autoBad = 0
const autoBrands = [
  ['FUJIFILM', 'X-T5'],
  ['Canon', 'EOS R5'],
  ['NIKON CORPORATION', 'Z8'],
  ['SONY', 'ILCE-7M4'],
  ['Panasonic', 'DC-S1'],
  ['Hasselblad', 'X2D']
]
for (const [make, model] of autoBrands) {
  for (const surface of ['light', 'dark']) {
    autoChecked++
    const file = styleBrandFile({ autoLogo: true }, { make, model }, surface)
    if (!file) continue
    const cut = LOGO_VERSIONS.flatMap((v) => v.cuts).find((c) => c.assetKey === file)
    if (cut && !cutFitsSurface(cut, surface)) {
      autoBad++
      check(`autoLogo compatible on ${surface}`, false, `${make} → ${file}`)
    }
  }
}
check('autoLogo never returns a cut for the wrong surface', autoBad === 0, `${autoBad}/${autoChecked}`)

// 7) surfaceFromBackground matches paint.ts' BT.601 luma rule (not WCAG).
const lumaCases = [
  ['#ffffff', 'light'],
  ['#f6f3ec', 'light'],
  ['#b3b3b3', 'light'], // 179/255 = 0.702 > 0.7
  ['#b2b2b2', 'dark'], // 178/255 = 0.698 <= 0.7
  ['#000000', 'dark'],
  ['#767676', 'dark'], // WCAG luminance is 0.184 → both rules say dark
  ['#949494', 'dark'], // 148/255 = 0.580 ; WCAG rel-luminance 0.285 → same
  ['#00FFFFFF', 'dark'], // ARGB, alpha 0 → dark in paint.ts too
  ['#FF9F7F85', 'dark'], // ARGB opaque, luma 0.538 → dark (RRGGBBAA would say light)
  ['#FFFFFFFF', 'light'], // ARGB opaque white
  ['rgba(255,255,255,0.2)', 'dark'],
  ['rgba(255,255,255,0.168)', 'dark'] // the one rgba stock theme background
]
let thresholdBad = 0
for (const [input, expected] of lumaCases) {
  const got = surfaceFromBackground(input)
  if (got !== expected) {
    thresholdBad++
    check(`surfaceFromBackground(${input})`, false, `${got} != ${expected}`)
  }
}
check('surfaceFromBackground uses the paint.ts luma cut', thresholdBad === 0, `${thresholdBad} bad`)

/**
 * Byte-for-byte mirror of `paint.ts:parseSolid() + lightSurface()`: returns
 * `true` when the painter calls the card DARK (`brandDark = !lightSurface`).
 */
function paintBrandDark(value) {
  const s = String(value ?? '').trim().toLowerCase()
  let c = null
  if (s === 'white') c = { r: 255, g: 255, b: 255, a: 1 }
  else if (s === 'black') c = { r: 0, g: 0, b: 0, a: 1 }
  else {
    const m = /^#([0-9a-f]{6}|[0-9a-f]{8})$/.exec(s)
    if (m) {
      const hex = m[1]
      c =
        hex.length === 8
          ? {
              r: parseInt(hex.slice(2, 4), 16),
              g: parseInt(hex.slice(4, 6), 16),
              b: parseInt(hex.slice(6, 8), 16),
              a: parseInt(hex.slice(0, 2), 16) / 255
            }
          : {
              r: parseInt(hex.slice(0, 2), 16),
              g: parseInt(hex.slice(2, 4), 16),
              b: parseInt(hex.slice(4, 6), 16),
              a: 1
            }
    }
  }
  if (!c || c.a < 0.4) return true
  return (c.r * 0.299 + c.g * 0.587 + c.b * 0.114) / 255 <= 0.7
}

// 7b) Every stock style × theme must agree with the painter's own surface call.
let themeBgs = 0
let themeMismatch = 0
const styleList = STOCK_STYLE_LIST ?? []
for (const style of styleList) {
  const themes = style.themes?.length ? style.themes : [{ id: '__default', background: style.background }]
  for (const theme of themes) {
    themeBgs++
    const bg = theme.background ?? style.background
    const designSurface = surfaceFromBackground(bg)
    const painterSurface = paintBrandDark(bg) ? 'dark' : 'light'
    if (designSurface !== painterSurface) {
      themeMismatch++
      check(`surface agrees with paint.ts (${style.id}/${theme.id})`, false, `${designSurface} != ${painterSurface} (${bg})`)
    }
  }
}
check(
  'surfaceForDesign agrees with paint.ts brandDark on every stock theme',
  themeBgs > 0 && themeMismatch === 0,
  `${themeMismatch}/${themeBgs}`
)

// 8) Default-render risk report (informational, never a failure): where the old
//    legacy tone pick is now REJECTED, and which brands lose every slot
//    candidate on a surface. These are exactly the renders 1b-2b must diff.
const risk = { autoRejected: [], slotEmpty: { light: [], dark: [] } }
for (const brand of BRANDS) {
  for (const surface of ['light', 'dark']) {
    const legacy = pickVariant(brand.key, surface === 'dark' ? 'dark' : 'light')
    if (legacy) {
      const cut = logoCutForFile(legacy)
      if (cut && !cutFitsSurface(cut, surface)) {
        risk.autoRejected.push(`${brand.key}/${surface}=${legacy}`)
      }
    }
    const survives = brand.files.filter((f) => {
      const cut = logoCutForFile(f)
      if (cut) return cutFitsSurface(cut, surface)
      return !(surface === 'dark' ? /_black$/i.test(f) : /_white$/i.test(f))
    })
    if (!survives.length) risk.slotEmpty[surface].push(brand.key)
  }
}
console.log('')
console.log('  Default-render risk (legacy tone pick rejected by the surface rule):')
console.log(`    autoLogo legacy pick incompatible: ${risk.autoRejected.length}/${BRANDS.length * 2} brand×surface`)
if (risk.autoRejected.length) console.log(`      e.g. ${risk.autoRejected.slice(0, 6).join(', ')}`)
console.log(`    brands with no slot candidate: light ${risk.slotEmpty.light.length}, dark ${risk.slotEmpty.dark.length}`)
if (risk.slotEmpty.dark.length) console.log(`      dark: ${risk.slotEmpty.dark.slice(0, 8).join(', ')}`)
if (risk.slotEmpty.light.length) console.log(`      light: ${risk.slotEmpty.light.slice(0, 8).join(', ')}`)

fs.rmSync(tmpDir, { recursive: true, force: true })

const cutCount = LOGO_VERSIONS.reduce((n, v) => n + v.cuts.length, 0)
console.log('Logo render-resolution verification (Phase 1b-2a)')
console.log(`  catalog: ${LOGO_VERSIONS.length} versions, ${cutCount} cuts`)
console.log(`  light-only versions checked: ${lightOnlyChecked}`)
console.log(`  two-tone versions checked: ${twoToneChecked}`)
console.log(`  no-fallthrough cases: ${noFallthroughChecked}`)
console.log(`  autoLogo make/model × surface cases: ${autoChecked}`)
console.log(`  legacy pick cases: 6, vendorLogo cases: 2, luma cases: ${lumaCases.length}`)
console.log(`  stock theme backgrounds cross-checked against paint.ts: ${themeBgs}`)
console.log('')
if (failures.length) {
  console.error(`${failures.length} resolution failure(s)`)
  for (const f of failures.slice(0, 20)) console.error(`  FAIL  ${f}`)
  process.exit(1)
}
console.log('ALL RESOLUTION CHECKS PASSED')
