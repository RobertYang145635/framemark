/**
 * Phase 1a verification for the generated logo catalog.
 * Bundles src/renderer/src/core/logo-catalog.ts with esbuild and asserts the
 * source-coverage / asset-resolution / surface invariants.
 */
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'
import sharp from 'sharp'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const APP_ROOT = path.resolve(__dirname, '../..')
const RESEARCH = 'D:/border&watermark/_research/logo-versions'
const CATALOG_TS = path.join(APP_ROOT, 'src/renderer/src/core/logo-catalog.ts')
const VERSION_ASSETS = path.join(APP_ROOT, 'src/renderer/src/assets/logo-versions')
const APP_LOGOS = path.join(APP_ROOT, 'src/renderer/src/assets/logos')

const renders = JSON.parse(fs.readFileSync(path.join(RESEARCH, '_scratch/render-index2.json'), 'utf8'))
const extras = JSON.parse(fs.readFileSync(path.join(RESEARCH, '_scratch/extras.json'), 'utf8'))
const audit = JSON.parse(fs.readFileSync(path.join(RESEARCH, '_audit/logo-catalog-audit.json'), 'utf8'))
const appFiles = fs.readdirSync(APP_LOGOS).filter((f) => f.endsWith('.webp')).sort()

const result = await build({
  entryPoints: [CATALOG_TS],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
  logLevel: 'silent'
})
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'framemark-logo-verify-'))
const tmpFile = path.join(tmpDir, 'logo-catalog.mjs')
fs.writeFileSync(tmpFile, result.outputFiles[0].text)
const mod = await import(pathToFileURL(tmpFile).href)

const {
  LOGO_BRANDS,
  LOGO_VERSIONS,
  LOGO_VERSION_BY_ID,
  LOGO_CUT_BY_ASSET_KEY,
  LOGO_CANDIDATES
} = mod

const failures = []
const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  if (!ok) failures.push(`${name}${detail ? `: ${detail}` : ''}`)
}

// 1) Source coverage
const renderMissing = renders.filter(
  (r) => !LOGO_CANDIDATES.some((c) => c.path === r.file || c.previewPath === r.preview)
)
check('render-index2 paths covered', renderMissing.length === 0, `${renderMissing.length} missing`)
const extraMissing = extras.filter((e) => !LOGO_CANDIDATES.some((c) => c.path === e.file))
check('extras covered', extraMissing.length === 0, `${extraMissing.length} missing`)
const appMissing = appFiles.filter(
  (f) => !LOGO_CANDIDATES.some((c) => c.path === `src/renderer/src/assets/logos/${f}`)
)
check('153 app logo files registered as candidates', appMissing.length === 0, `${appMissing.length} missing`)

// 2) Every cut asset resolves
const cutAssetMissing = []
const appCutKeys = new Set()
for (const v of LOGO_VERSIONS) {
  for (const c of v.cuts) {
    if (c.assetKey.startsWith('ver:')) {
      const p = path.join(VERSION_ASSETS, v.brandKey, `${c.id}.webp`)
      if (!fs.existsSync(p)) cutAssetMissing.push(`${v.brandKey}/${c.id}`)
    } else {
      const p = path.join(APP_LOGOS, `${c.assetKey}.webp`)
      if (!fs.existsSync(p)) cutAssetMissing.push(`${v.brandKey}/${c.id} -> ${c.assetKey}`)
      appCutKeys.add(c.assetKey)
    }
  }
}
check('every cut asset resolves', cutAssetMissing.length === 0, cutAssetMissing.slice(0, 5).join(', '))

// 3) Every app file (except placeholders) resolves through a cut or alias
const unresolvedApp = []
for (const f of appFiles) {
  const stem = f.replace(/\.webp$/, '')
  if (stem === 'logo' || stem === 'logo_empty') continue
  if (!LOGO_CUT_BY_ASSET_KEY[stem] && !appCutKeys.has(stem)) unresolvedApp.push(stem)
}
check('all app baseline stems resolve to a cut/alias', unresolvedApp.length === 0, unresolvedApp.join(', '))

// 4) Fujifilm / Hasselblad minimums
const fushiUsable = LOGO_VERSIONS.filter(
  (v) => v.brandKey === 'fushi' && v.cuts.length > 0 && v.status !== 'GAP'
)
const hasuUsable = LOGO_VERSIONS.filter(
  (v) => v.brandKey === 'hasu' && v.cuts.length > 0 && v.status !== 'GAP'
)
check('Fujifilm >= 8 usable versions', fushiUsable.length >= 8, `${fushiUsable.length}`)
check('Hasselblad >= 4 usable versions', hasuUsable.length >= 4, `${hasuUsable.length}`)

// 5) Primary cut surface compatibility
const badPrimary = []
for (const v of LOGO_VERSIONS) {
  if (!v.cuts.length) continue
  const p = v.cuts.find((c) => c.id === v.primaryCut)
  if (!p) {
    badPrimary.push(`${v.id}: missing primary ${v.primaryCut}`)
    continue
  }
  if (v.surface === 'light' && (p.surface === 'dark' || p.ink === 'light')) {
    badPrimary.push(`${v.id}: light version primary ${p.id} is ${p.ink}/${p.surface}`)
  }
  if (v.surface === 'dark' && (p.surface === 'light' || p.ink === 'dark')) {
    badPrimary.push(`${v.id}: dark version primary ${p.id} is ${p.ink}/${p.surface}`)
  }
}
check('primary cut respects version surface', badPrimary.length === 0, badPrimary.slice(0, 5).join('; '))

// 6) id uniqueness / primary presence
const ids = new Set()
let dupIds = 0
for (const v of LOGO_VERSIONS) {
  if (ids.has(v.id)) dupIds++
  ids.add(v.id)
}
check('version ids unique', dupIds === 0, `${dupIds} duplicates`)
check('version count matches audit', LOGO_VERSIONS.length === audit.totals.versions, `${LOGO_VERSIONS.length} vs ${audit.totals.versions}`)
check('cut count matches audit', LOGO_VERSIONS.reduce((n, v) => n + v.cuts.length, 0) === audit.totals.cuts)
check('candidate count matches audit', LOGO_CANDIDATES.length === audit.totals.candidates)

// 7) Budget / dimensions
const emitted = []
for (const b of fs.readdirSync(VERSION_ASSETS)) {
  const dir = path.join(VERSION_ASSETS, b)
  if (!fs.statSync(dir).isDirectory()) continue
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.webp'))) {
    const p = path.join(dir, f)
    emitted.push({ path: p, bytes: fs.statSync(p).size })
  }
}
let totalBytes = 0
let maxBytes = 0
const badDims = []
for (const a of emitted) {
  totalBytes += a.bytes
  maxBytes = Math.max(maxBytes, a.bytes)
  const m = await sharp(a.path).metadata()
  if (Math.max(m.width, m.height) > 512) badDims.push(`${a.path} ${m.width}x${m.height}`)
}
check('new assets <= 512px long edge', badDims.length === 0, badDims.slice(0, 3).join(', '))
check('new asset total <= 8MB', totalBytes <= 8 * 1024 * 1024, `${(totalBytes / 1024 / 1024).toFixed(2)} MB`)
check('each new asset <= 120KB', maxBytes <= 120 * 1024, `${(maxBytes / 1024).toFixed(1)} KB`)
check('emitted file count matches audit', emitted.length === audit.totals.emittedAssets, `${emitted.length} vs ${audit.totals.emittedAssets}`)

// 8) Export consistency
check('LOGO_VERSION_BY_ID complete', Object.keys(LOGO_VERSION_BY_ID).length === LOGO_VERSIONS.length)
check('LOGO_BRANDS complete', LOGO_BRANDS.length === 63, `${LOGO_BRANDS.length}`)
const badMap = LOGO_VERSIONS.filter((v) => v.cuts.some((c) => LOGO_CUT_BY_ASSET_KEY[c.assetKey]?.cut.id !== c.id))
check('LOGO_CUT_BY_ASSET_KEY covers all cut assetKeys', badMap.length === 0)
check('unresolved candidates limited to unusable placeholders', audit.issues.unresolvedCandidates.every((c) => c.disposition === 'unusable'))

fs.rmSync(tmpDir, { recursive: true, force: true })

const statusCounts = {}
for (const v of LOGO_VERSIONS) statusCounts[v.status] = (statusCounts[v.status] || 0) + 1
const dispositionCounts = {}
for (const c of LOGO_CANDIDATES) dispositionCounts[c.disposition] = (dispositionCounts[c.disposition] || 0) + 1

console.log('Logo catalog verification')
console.log(`  brands: ${LOGO_BRANDS.length}, versions: ${LOGO_VERSIONS.length}, cuts: ${LOGO_VERSIONS.reduce((n, v) => n + v.cuts.length, 0)}, candidates: ${LOGO_CANDIDATES.length}`)
console.log(`  status: ${JSON.stringify(statusCounts)}`)
console.log(`  dispositions: ${JSON.stringify(dispositionCounts)}`)
console.log(`  render coverage: ${renders.length - renderMissing.length}/${renders.length}`)
console.log(`  extras coverage: ${extras.length - extraMissing.length}/${extras.length}`)
console.log(`  app coverage: ${appFiles.length - appMissing.length}/${appFiles.length}`)
console.log(`  Fujifilm usable: ${fushiUsable.length}, Hasselblad usable: ${hasuUsable.length}`)
console.log(`  assets: ${emitted.length} files, ${(totalBytes / 1024 / 1024).toFixed(2)} MB, max ${(maxBytes / 1024).toFixed(1)} KB`)
console.log(`  ambiguous/none cuts: ${audit.issues.ambiguousOrNone.length}`)
console.log('')
for (const c of checks) console.log(`  ${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.detail ? ` (${c.detail})` : ''}`)
if (failures.length) {
  console.error(`\n${failures.length} verification failure(s)`)
  process.exit(1)
}
console.log('\nALL CHECKS PASSED')
