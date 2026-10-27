/**
 * Phase 1b-1 verification for `logo-compat.ts`.
 *
 * Bundles the compat layer + generated catalog with esbuild and asserts the
 * surface invariants the store now relies on:
 *   - every `refForVersion()` is either null or a cut that fits the surface
 *     (surface light/dark/both, ink never dark-on-dark or light-on-light);
 *   - a light-only version never yields a cut for the dark surface (and vice
 *     versa);
 *   - fallback / auto picks never cross brands;
 *   - legacy file stems still resolve to a cut;
 *   - background parsing follows the conservative rules.
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

const result = await build({
  stdin: {
    contents: [
      `export * from './src/renderer/src/core/logo-compat'`,
      `export { LOGO_VERSIONS, LOGO_BRANDS } from './src/renderer/src/core/logo-catalog'`
    ].join('\n'),
    resolveDir: APP_ROOT,
    sourcefile: 'check-compat-entry.ts',
    loader: 'ts'
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  write: false,
  logLevel: 'silent'
})

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'framemark-compat-check-'))
const tmpFile = path.join(tmpDir, 'logo-compat-check.mjs')
fs.writeFileSync(tmpFile, result.outputFiles[0].text)
const mod = await import(pathToFileURL(tmpFile).href)

const {
  LOGO_VERSIONS,
  LOGO_BRANDS,
  refForVersion,
  refFromAssetKey,
  versionCompatible,
  cutFitsSurface,
  cutKeyForLegacyFile,
  fallbackVersionForSurface,
  autoLogoVersionForBrand,
  surfaceFromBackground
} = mod

const SURFACES = ['light', 'dark']
const failures = []
function check(name, ok, detail = '') {
  if (!ok) failures.push(`${name}${detail ? `: ${detail}` : ''}`)
}

// 1) refForVersion surface/ink integrity, both surfaces.
const refCounts = { light: 0, dark: 0 }
const nullCounts = { light: 0, dark: 0 }
let badRef = 0
for (const version of LOGO_VERSIONS) {
  for (const surface of SURFACES) {
    const ref = refForVersion(version, surface)
    if (!ref) {
      nullCounts[surface]++
      continue
    }
    refCounts[surface]++
    const cut = version.cuts.find((c) => c.id === ref.cutId)
    if (!cut) {
      badRef++
      check(`ref cut exists (${version.id}/${surface})`, false, ref.cutId)
      continue
    }
    const fits =
      cut.surface === surface ||
      cut.surface === 'both'
    if (!fits || !cutFitsSurface(cut, surface)) {
      badRef++
      check(
        `ref fits ${surface} (${version.id})`,
        false,
        `${cut.id} ink=${cut.ink} cutSurface=${cut.surface}`
      )
    }
    if (surface === 'dark' && cut.ink === 'dark') {
      badRef++
      check(`no dark ink on dark (${version.id})`, false, cut.id)
    }
    if (surface === 'light' && cut.ink === 'light') {
      badRef++
      check(`no light ink on light (${version.id})`, false, cut.id)
    }
    if (ref.assetKey !== cut.assetKey || ref.file !== ref.assetKey) {
      badRef++
      check(`ref keys agree (${version.id})`, false, `${ref.assetKey}/${ref.file}/${cut.assetKey}`)
    }
    if (ref.versionId !== version.id || ref.brandKey !== version.brandKey) {
      badRef++
      check(`ref identity (${version.id})`, false)
    }
    if (ref.surface !== cut.surface) {
      badRef++
      check(`ref surface mirrors cut (${version.id})`, false)
    }
  }
}
check('refForVersion surface/ink integrity', badRef === 0, `${badRef} bad refs`)

// 2) Single-surface versions never resolve on the opposite surface.
let crossSurface = 0
for (const version of LOGO_VERSIONS) {
  if (version.surface === 'light') {
    const ref = refForVersion(version, 'dark')
    if (ref) {
      crossSurface++
      check(`light-only version hidden on dark (${version.id})`, false, `${ref.cutId}/${ref.surface}`)
    }
  }
  if (version.surface === 'dark') {
    const ref = refForVersion(version, 'light')
    if (ref) {
      crossSurface++
      check(`dark-only version hidden on light (${version.id})`, false, `${ref.cutId}/${ref.surface}`)
    }
  }
}
check('no light-only -> dark cut (and vice versa)', crossSurface === 0, `${crossSurface} leaks`)

// 3) Catalogue-wide compatible counts.
const counts = { light: 0, dark: 0, both: 0, neither: 0 }
for (const version of LOGO_VERSIONS) {
  const light = versionCompatible(version, 'light')
  const dark = versionCompatible(version, 'dark')
  if (light) counts.light++
  if (dark) counts.dark++
  if (light && dark) counts.both++
  if (!light && !dark) counts.neither++
}
check('every version has a surface', counts.neither === 0, `${counts.neither} neither`)

// 4) Fujifilm / Hasselblad per surface (READY/LEGACY only).
function brandSurfaceStats(brandKey) {
  const versions = LOGO_VERSIONS.filter((v) => v.brandKey === brandKey)
  const usable = versions.filter((v) => v.status === 'READY' || v.status === 'LEGACY')
  return {
    versions: versions.length,
    light: usable.filter((v) => versionCompatible(v, 'light')).length,
    dark: usable.filter((v) => versionCompatible(v, 'dark')).length,
    both: usable.filter((v) => versionCompatible(v, 'light') && versionCompatible(v, 'dark')).length
  }
}
const fushi = brandSurfaceStats('fushi')
const hasu = brandSurfaceStats('hasu')
check('Fujifilm has light versions', fushi.light > 0)
check('Fujifilm has dark versions', fushi.dark > 0)
check('Hasselblad has light versions', hasu.light > 0)
check('Hasselblad has dark versions', hasu.dark > 0)

// 5) Legacy stems resolve to their catalog cut.
let badLegacy = 0
for (const version of LOGO_VERSIONS) {
  for (const cut of version.cuts) {
    const hit = cutKeyForLegacyFile(cut.assetKey)
    if (!hit || hit.id !== cut.id) {
      badLegacy++
      check(`legacy stem resolves (${cut.assetKey})`, false, hit?.id ?? 'null')
    }
    const withExt = cutKeyForLegacyFile(`${cut.assetKey}.webp`)
    if (!withExt || withExt.id !== cut.id) {
      badLegacy++
      check(`legacy stem .webp resolves (${cut.assetKey})`, false, withExt?.id ?? 'null')
    }
  }
}
check('all cut asset keys resolve as legacy files', badLegacy === 0, `${badLegacy} bad`)

// 6) refFromAssetKey / fallback never cross brands or surfaces.
let badLookup = 0
for (const version of LOGO_VERSIONS) {
  for (const surface of SURFACES) {
    const ref = refFromAssetKey(version.primaryCut, surface)
    const primary = version.cuts.find((c) => c.id === version.primaryCut)
    if (ref && primary && (ref.brandKey !== version.brandKey || !ref.versionId)) {
      badLookup++
      check(`refFromAssetKey stays in brand (${version.id})`, false)
    }
    const fallback = fallbackVersionForSurface(version, surface)
    if (fallback) {
      if (fallback.brandKey !== version.brandKey) {
        badLookup++
        check(`fallback same brand (${version.id})`, false, fallback.id)
      }
      if ((fallback.kind ?? '') !== (version.kind ?? '')) {
        badLookup++
        check(`fallback same kind (${version.id})`, false, fallback.id)
      }
      if (fallback.id === version.id) {
        badLookup++
        check(`fallback is a different version (${version.id})`, false)
      }
      if (!versionCompatible(fallback, surface)) {
        badLookup++
        check(`fallback compatible (${version.id}/${surface})`, false, fallback.id)
      }
      if (!refForVersion(fallback, surface)) {
        badLookup++
        check(`fallback yields a ref (${version.id}/${surface})`, false, fallback.id)
      }
      if (fallback.status !== 'READY' && fallback.status !== 'LEGACY') {
        badLookup++
        check(`fallback READY/LEGACY (${version.id})`, false, fallback.status)
      }
    }
  }
}
check('refFromAssetKey / fallback safety', badLookup === 0, `${badLookup} bad`)

// 7) autoLogoVersionForBrand: each brand only returns its own compatible versions.
let badAuto = 0
let autoCovered = 0
for (const brand of LOGO_BRANDS) {
  for (const surface of SURFACES) {
    const version = autoLogoVersionForBrand(brand.key, surface)
    if (!version) continue
    autoCovered++
    if (version.brandKey !== brand.key || !versionCompatible(version, surface)) {
      badAuto++
      check(`auto pick safe (${brand.key}/${surface})`, false, `${version.id}`)
    }
  }
}
check('autoLogoVersionForBrand stays in brand + surface', badAuto === 0, `${badAuto} bad`)

// 8) conservative background parsing
const bgCases = [
  ['#fff', 'light'],
  ['#ffffff', 'light'],
  ['#f6f3ec', 'light'],
  ['rgb(246, 243, 236)', 'light'],
  ['rgba(255 255 255 / 0.9)', 'light'],
  ['white', 'light'],
  ['#000', 'dark'],
  ['black', 'dark'],
  ['#08090a', 'dark'],
  ['#111214', 'dark'],
  ['rgba(255,255,255,0.2)', 'dark'],
  ['rgba(0,0,0,0.6)', 'dark'],
  ['not-a-colour', 'dark'],
  ['', 'dark'],
  ['#FF141414', 'dark'],
  ['#FFFFFFFF', 'light']
]
let badBg = 0
for (const [input, expected] of bgCases) {
  const got = surfaceFromBackground(input)
  if (got !== expected) {
    badBg++
    check(`surfaceFromBackground(${JSON.stringify(input)})`, false, `${got} != ${expected}`)
  }
}
check('conservative background parsing', badBg === 0, `${badBg} bad`)

fs.rmSync(tmpDir, { recursive: true, force: true })

const total = LOGO_VERSIONS.length
const cuts = LOGO_VERSIONS.reduce((n, v) => n + v.cuts.length, 0)
console.log('Logo compatibility verification (Phase 1b-1)')
console.log(`  brands: ${LOGO_BRANDS.length}, versions: ${total}, cuts: ${cuts}`)
console.log(
  `  compatible: light ${counts.light}, dark ${counts.dark}, both ${counts.both}, neither ${counts.neither}`
)
console.log(
  `  refs: light ${refCounts.light} (null ${nullCounts.light}), dark ${refCounts.dark} (null ${nullCounts.dark})`
)
console.log(`  Fujifilm usable: ${fushi.versions} versions, light ${fushi.light}, dark ${fushi.dark}, both ${fushi.both}`)
console.log(`  Hasselblad usable: ${hasu.versions} versions, light ${hasu.light}, dark ${hasu.dark}, both ${hasu.both}`)
console.log(`  auto picks resolved: ${autoCovered}/${LOGO_BRANDS.length * 2}`)
console.log('')
if (failures.length) {
  console.error(`${failures.length} compatibility failure(s)`)
  for (const f of failures.slice(0, 20)) console.error(`  FAIL  ${f}`)
  process.exit(1)
}
console.log('ALL COMPATIBILITY CHECKS PASSED')
