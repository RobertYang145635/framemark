#!/usr/bin/env node
/**
 * Phase 1b-2b-1 render matrix.
 *
 * Drives the TEST-ONLY `window.__fmStock` / `window.__fmVariants` debug APIs in a
 * real Electron renderer and asserts, from decoded pixels, that:
 *
 *   - a version with no cut for the surface paints nothing (identical to
 *     `showLogo: false`), never a re-toned sibling;
 *   - a dual-cut version paints a different mark on light and dark;
 *   - an `ambiguous` light-only cut stays off the dark card;
 *   - the hand-template path honours the same explicit version.
 *
 * PNGs are kept for inspection; the comparison uses a SHA-256 of decoded RGBA
 * because two PNG encodes of the same pixels may carry different metadata.
 *
 * Usage: node scripts/logo-pipeline/render-matrix.cjs
 * Requires `npm run build` first (Electron loads `out/main/index.js`).
 * Exits non-zero on any failed assertion.
 */
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const esbuild = require('esbuild')
const sharp = require('sharp')
const { _electron: electron } = require('playwright-core')

// This shell may inherit ELECTRON_RUN_AS_NODE (set by IDE tooling). Electron
// would then run as plain node and `electron.launch` would never open a window.
delete process.env.ELECTRON_RUN_AS_NODE

const ROOT = 'D:/border&watermark/framemark'
const OUT_DIR = 'D:/border&watermark/_research/logo-versions/_audit/matrix'
const RATIO = '4:3'
const MAX_EDGE = 1600
const MAIN_ENTRY = path.join(ROOT, 'out/main/index.js')

/** Version ids exercised by the stock matrix. */
const STOCK_VERSIONS = [
  { versionId: 'fujifilm-red-symbol', kind: 'light-only' },
  { versionId: 'hasselblad-h-mark', kind: 'dual-cut' },
  { versionId: 'hasselblad-blue', kind: 'light-only' },
  { versionId: 'sigma-2025-lockup', kind: 'ambiguous-light' }
]

const HAND_VARIANT = 'wm-classic'
const HAND_VERSION = 'hasselblad-h-mark'
const HAND_THEMES = { light: 'paper', dark: 'ink' }

if (!fs.existsSync(MAIN_ENTRY)) {
  console.error(`Missing ${MAIN_ENTRY}. Run \`npm run build\` before the matrix.`)
  process.exit(2)
}

/* ------------------------------------------------------------------ *
 * Catalog: bundle the auto-generated TS module once (it has no imports).
 * ------------------------------------------------------------------ */
function loadCatalog() {
  const result = esbuild.buildSync({
    entryPoints: [path.join(ROOT, 'src/renderer/src/core/logo-catalog.ts')],
    bundle: true,
    format: 'cjs',
    platform: 'node',
    target: 'node20',
    write: false,
    logLevel: 'silent'
  })
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fm-matrix-catalog-'))
  const file = path.join(dir, 'catalog.cjs')
  fs.writeFileSync(file, result.outputFiles[0].text)
  try {
    return require(file)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
}

const { LOGO_VERSIONS } = loadCatalog()
const VERSION_BY_ID = new Map(LOGO_VERSIONS.map((v) => [v.id, v]))

function versionOf(id) {
  const version = VERSION_BY_ID.get(id)
  if (!version) throw new Error(`catalog has no version ${id}`)
  return version
}

/** Mirror of `logo-compat.ts:cutFitsSurface()`. */
function cutFits(cut, surface) {
  if (cut.surface !== 'both' && cut.surface !== surface) return false
  return surface === 'dark' ? cut.ink !== 'dark' : cut.ink !== 'light'
}

const STATUS_RANK = { READY: 0, LEGACY: 1, CANDIDATE: 2, UNUSABLE: 3 }
const CONF_RANK = { high: 0, medium: 1, low: 2 }

/** Mirror of `logo-compat.ts:cutForVersionSurface()`. */
function cutForSurface(version, surface) {
  const candidates = version.cuts.filter((c) => c.surface === surface || c.surface === 'both').filter((c) => cutFits(c, surface))
  candidates.sort(
    (a, b) => (STATUS_RANK[a.status] ?? 3) - (STATUS_RANK[b.status] ?? 3) || (CONF_RANK[a.confidence] ?? 2) - (CONF_RANK[b.confidence] ?? 2)
  )
  return candidates[0] ?? null
}

/** A ref carrying the version id the renderer keys on (cut data is surface-neutral). */
function refFor(version, surface) {
  const cut = cutForSurface(version, surface) ?? version.cuts.find((c) => c.id === version.primaryCut) ?? version.cuts[0]
  if (!cut) throw new Error(`version ${version.id} has no cut`)
  return {
    brandKey: version.brandKey,
    versionId: version.id,
    cutId: cut.id,
    assetKey: cut.assetKey,
    file: cut.assetKey,
    ink: cut.ink,
    surface: cut.surface,
    name: version.name
  }
}

/* ------------------------------------------------------------------ *
 * Surface picking, byte-for-byte with `paint.ts` luma.
 * ------------------------------------------------------------------ */
function surfaceFromBackground(value) {
  const raw = String(value ?? '').trim().toLowerCase()
  let r = 0
  let g = 0
  let b = 0
  let a = 1
  if (raw === 'white') {
    r = g = b = 255
  } else if (raw === 'black') {
    r = g = b = 0
  } else {
    const m = /^#([0-9a-f]{6}|[0-9a-f]{8})$/.exec(raw)
    if (!m) return 'dark'
    const hex = m[1]
    if (hex.length === 8) {
      a = parseInt(hex.slice(0, 2), 16) / 255
      r = parseInt(hex.slice(2, 4), 16)
      g = parseInt(hex.slice(4, 6), 16)
      b = parseInt(hex.slice(6, 8), 16)
    } else {
      r = parseInt(hex.slice(0, 2), 16)
      g = parseInt(hex.slice(2, 4), 16)
      b = parseInt(hex.slice(4, 6), 16)
    }
  }
  if (a < 0.4) return 'dark'
  return (r * 0.299 + g * 0.587 + b * 0.114) / 255 > 0.7 ? 'light' : 'dark'
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex')
}

/* ------------------------------------------------------------------ *
 * Render helpers
 * ------------------------------------------------------------------ */
function savePng(name, dataUrl) {
  const m = /^data:image\/png;base64,(.+)$/s.exec(String(dataUrl ?? ''))
  if (!m) throw new Error(`${name}: debug render did not return PNG data`)
  const buf = Buffer.from(m[1], 'base64')
  // Stock ids carry `:` (`xiaomi-classic:17`), illegal in Windows file names —
  // a raw write becomes an NTFS alternate data stream and no PNG appears.
  const safe = name.replace(/[:<>"|?*\\/]/g, '-')
  const file = path.join(OUT_DIR, `${safe}.png`)
  fs.writeFileSync(file, buf)
  return { buf, file }
}

async function pixelFingerprint(pngBuf) {
  const meta = await sharp(pngBuf).metadata()
  const raw = await sharp(pngBuf).ensureAlpha().raw().toBuffer()
  return { sha256: sha256(raw), width: meta.width ?? 0, height: meta.height ?? 0, rawBytes: raw.length }
}

async function main() {
  fs.rmSync(OUT_DIR, { recursive: true, force: true })
  fs.mkdirSync(OUT_DIR, { recursive: true })

  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.'],
    cwd: ROOT,
    timeout: 60000
  })
  let page
  try {
    page = await app.firstWindow()
    const pageErrors = []
    page.on('pageerror', (e) => pageErrors.push(String(e?.message ?? e)))
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

    const stockList = await page.evaluate(() => window.__fmStock.list())
    const candidates = stockList
      .map((s) => ({
        id: s.id,
        vendor: s.vendor,
        brandSlots: s.brandSlots,
        themes: s.themes ?? [],
        themeBackgrounds: s.themeBackgrounds ?? {},
        surfaces: Object.fromEntries((s.themes ?? []).map((t) => [t, surfaceFromBackground(s.themeBackgrounds?.[t])]))
      }))
      .filter((s) => s.brandSlots > 0)

    const chosen = candidates.find(
      (s) => Object.values(s.surfaces).includes('light') && Object.values(s.surfaces).includes('dark')
    )
    if (!chosen) {
      console.error('No stock style with brand slots and both a light and a dark theme.')
      console.error('Candidates:')
      for (const s of candidates) console.error(`  ${s.id} themes=${JSON.stringify(s.themes)}`)
      throw new Error('matrix has no eligible stock style')
    }
    const lightTheme = chosen.themes.find((t) => chosen.surfaces[t] === 'light')
    const darkTheme = chosen.themes.find((t) => chosen.surfaces[t] === 'dark')

    const cases = []
    const assertions = []
    const record = async ({ name, surface, theme, versionId, expectedCut, result }) => {
      const { buf, file } = savePng(name, result.data)
      const fp = await pixelFingerprint(buf)
      const item = {
        case: name,
        path: file,
        surface,
        theme,
        themeBackground: chosen.themeBackgrounds?.[theme] ?? null,
        versionId: versionId || null,
        expectedCut: expectedCut?.id ?? null,
        expectedAssetKey: expectedCut?.assetKey ?? null,
        actualCut: null,
        actualCutSource: 'pixel-inferred (debug API does not expose the painted cut)',
        width: fp.width,
        height: fp.height,
        decode: 'sharp ensureAlpha raw RGBA',
        pixelSha256: fp.sha256,
        sameAsNoLogo: versionId ? null : true,
        pass: versionId ? null : true
      }
      cases.push(item)
      return item
    }

    const renderStock = (theme, showLogo, version) =>
      page.evaluate(
        ({ id, ratio, maxEdge, theme, showLogo, brandVersion }) =>
          window.__fmStock.render(id, { ratio, maxEdge, theme, showLogo, brandVersion }),
        { id: chosen.id, ratio: RATIO, maxEdge: MAX_EDGE, theme, showLogo, brandVersion: version }
      )

    // ------------------------------------------------------------------
    // Baselines: same style/theme with every logo path silenced.
    // ------------------------------------------------------------------
    const noLogo = {}
    for (const surface of ['light', 'dark']) {
      const theme = surface === 'light' ? lightTheme : darkTheme
      const result = await renderStock(theme, false, null)
      noLogo[surface] = await record({
        name: `${chosen.id}__noLogo-${surface}`,
        surface,
        theme,
        versionId: null,
        expectedCut: null,
        result
      })
    }

    // ------------------------------------------------------------------
    // Version matrix on both surfaces.
    // ------------------------------------------------------------------
    const byCase = new Map()
    for (const spec of STOCK_VERSIONS) {
      const version = versionOf(spec.versionId)
      for (const surface of ['light', 'dark']) {
        const theme = surface === 'light' ? lightTheme : darkTheme
        const expectedCut = cutForSurface(version, surface)
        const name = `${chosen.id}__${spec.versionId}-${surface}`
        const result = await renderStock(theme, undefined, refFor(version, surface))
        const item = await record({ name, surface, theme, versionId: version.id, expectedCut, result })
        item.kind = spec.kind
        byCase.set(name, item)
      }
    }

    // ------------------------------------------------------------------
    // Stock assertions. Generic rule: a resolvable cut must paint (differ from
    // noLogo); a version with no cut for the surface must leave it untouched.
    // ------------------------------------------------------------------
    for (const item of cases.filter((c) => c.versionId)) {
      const baseline = noLogo[item.surface]
      item.sameAsNoLogo = item.pixelSha256 === baseline.pixelSha256
      const shouldPaint = Boolean(item.expectedCut)
      item.pass = shouldPaint ? !item.sameAsNoLogo : item.sameAsNoLogo
      if (item.expectedCut && !item.sameAsNoLogo) item.actualCut = item.expectedCut
      assertions.push({
        name: `${item.case}: ${shouldPaint ? 'paints a different mark' : 'byte-identical to noLogo'}`,
        pass: item.pass,
        detail: `sha=${item.pixelSha256.slice(0, 12)} baseline=${baseline.pixelSha256.slice(0, 12)} expectedCut=${item.expectedCut ?? 'none'}`
      })
    }

    const dual = STOCK_VERSIONS.find((v) => v.kind === 'dual-cut')
    if (dual) {
      const l = byCase.get(`${chosen.id}__${dual.versionId}-light`)
      const d = byCase.get(`${chosen.id}__${dual.versionId}-dark`)
      const pass = Boolean(l && d && l.pixelSha256 !== d.pixelSha256)
      assertions.push({
        name: `${dual.versionId}: light and dark cuts differ`,
        pass,
        detail: `${l?.pixelSha256.slice(0, 12)} / ${d?.pixelSha256.slice(0, 12)}`
      })
    }

    // One renderer-level equality duplicated explicitly for the README-level
    // contract: light-only versions are literally the no-logo card on dark.
    for (const spec of STOCK_VERSIONS.filter((v) => v.kind === 'light-only')) {
      const item = byCase.get(`${chosen.id}__${spec.versionId}-dark`)
      const pass = Boolean(item && item.pixelSha256 === noLogo.dark.pixelSha256)
      assertions.push({
        name: `${spec.versionId}: dark render equals noLogo-dark`,
        pass,
        detail: item?.pixelSha256.slice(0, 12)
      })
    }

    // ------------------------------------------------------------------
    // Hand-template path: __fmVariants + autoLogo variant.
    // ------------------------------------------------------------------
    const handVersion = versionOf(HAND_VERSION)
    const handCases = []
    const renderVariant = (theme, showLogo, version) =>
      page.evaluate(
        ({ id, ratio, maxEdge, theme, showLogo, brandVersion }) =>
          window.__fmVariants.render(id, { ratio, maxEdge, theme, showLogo, brandVersion }),
        { id: HAND_VARIANT, ratio: RATIO, maxEdge: MAX_EDGE, theme, showLogo, brandVersion: version }
      )
    const recordHand = async ({ name, surface, theme, versionId, expectedCut, result }) => {
      const { buf, file } = savePng(name, result.data)
      const fp = await pixelFingerprint(buf)
      const item = {
        case: name,
        path: file,
        surface,
        theme,
        versionId: versionId || null,
        expectedCut: expectedCut?.id ?? null,
        expectedAssetKey: expectedCut?.assetKey ?? null,
        actualCut: null,
        actualCutSource: 'pixel-inferred (debug API does not expose the painted cut)',
        width: fp.width,
        height: fp.height,
        decode: 'sharp ensureAlpha raw RGBA',
        pixelSha256: fp.sha256,
        sameAsNoLogo: versionId ? null : true,
        pass: versionId ? null : true
      }
      handCases.push(item)
      return item
    }

    const handNoLogo = {}
    for (const surface of ['light', 'dark']) {
      const theme = HAND_THEMES[surface]
      const result = await renderVariant(theme, false, null)
      handNoLogo[surface] = await recordHand({
        name: `${HAND_VARIANT}__noLogo-${surface}`,
        surface,
        theme,
        versionId: null,
        expectedCut: null,
        result
      })
    }
    const handPainted = {}
    for (const surface of ['light', 'dark']) {
      const theme = HAND_THEMES[surface]
      const expectedCut = cutForSurface(handVersion, surface)
      const result = await renderVariant(theme, undefined, refFor(handVersion, surface))
      handPainted[surface] = await recordHand({
        name: `${HAND_VARIANT}__${HAND_VERSION}-${surface}`,
        surface,
        theme,
        versionId: handVersion.id,
        expectedCut,
        result
      })
    }

    for (const surface of ['light', 'dark']) {
      const item = handPainted[surface]
      const baseline = handNoLogo[surface]
      item.sameAsNoLogo = item.pixelSha256 === baseline.pixelSha256
      item.pass = !item.sameAsNoLogo
      if (item.expectedCut && !item.sameAsNoLogo) item.actualCut = item.expectedCut
      assertions.push({
        name: `${item.case}: version differs from noLogo-${surface}`,
        pass: item.pass,
        detail: `sha=${item.pixelSha256.slice(0, 12)} baseline=${baseline.pixelSha256.slice(0, 12)} expectedCut=${item.expectedCut ?? 'none'}`
      })
    }
    assertions.push({
      name: `${HAND_VARIANT}: ${HAND_VERSION} light and dark differ`,
      pass: handPainted.light.pixelSha256 !== handPainted.dark.pixelSha256,
      detail: `${handPainted.light.pixelSha256.slice(0, 12)} / ${handPainted.dark.pixelSha256.slice(0, 12)}`
    })

    const allCases = [...cases, ...handCases]
    const failedCases = allCases.filter((c) => c.pass === false)
    const failedAssertions = assertions.filter((a) => !a.pass)
    const report = {
      generatedAt: new Date().toISOString(),
      phase: '1b-2b-1',
      app: {
        commit: null,
        style: chosen.id,
        vendor: chosen.vendor,
        brandSlots: chosen.brandSlots,
        lightTheme,
        darkTheme,
        themeBackgrounds: chosen.themeBackgrounds,
        ratio: RATIO,
        maxEdge: MAX_EDGE
      },
      candidateStyles: candidates.map((c) => ({ id: c.id, brandSlots: c.brandSlots, themes: c.themes })),
      comparison: 'SHA-256 of decoded RGBA bytes (sharp .ensureAlpha().raw())',
      cutResolution: 'expected = catalog cutForVersionSurface(); actual is inferred from pixel equality only',
      assertions,
      cases: allCases,
      summary: {
        cases: allCases.length,
        passed: allCases.filter((c) => c.pass === true).length,
        failed: failedCases.length,
        assertions: assertions.length,
        assertionsPassed: assertions.filter((a) => a.pass).length,
        pass: failedCases.length === 0 && failedAssertions.length === 0,
        pageErrors
      }
    }
    fs.writeFileSync(path.join(OUT_DIR, 'report.json'), `${JSON.stringify(report, null, 2)}\n`)

    const md = []
    md.push('# Logo render matrix (Phase 1b-2b-1)')
    md.push('')
    md.push(`- Generated: ${report.generatedAt}`)
    md.push(`- Stock style: \`${chosen.id}\` (${chosen.vendor}), brand slots: ${chosen.brandSlots}`)
    md.push(`- Themes: light=\`${lightTheme}\` ${chosen.themeBackgrounds[lightTheme]}, dark=\`${darkTheme}\` ${chosen.themeBackgrounds[darkTheme]}`)
    md.push(`- Render: ratio ${RATIO}, maxEdge ${MAX_EDGE}`)
    md.push('- Comparison: SHA-256 of decoded RGBA pixels (`sharp.ensureAlpha().raw()`), not whole-file PNG bytes')
    md.push('')
    md.push('| case | surface | version | expected cut | pixel sha256 | same as noLogo | pass |')
    md.push('| --- | --- | --- | --- | --- | --- | --- |')
    for (const c of allCases) {
      md.push(
        `| ${c.case} | ${c.surface} | ${c.versionId ?? '-'} | ${c.expectedCut ?? '-'} | ${c.pixelSha256.slice(0, 12)} | ${c.sameAsNoLogo === null ? '-' : c.sameAsNoLogo ? 'yes' : 'no'} | ${c.pass ? 'PASS' : 'FAIL'} |`
      )
    }
    md.push('')
    md.push(`**${report.summary.passed}/${report.summary.cases} cases, ${report.summary.assertionsPassed}/${report.summary.assertions} assertions passed.**`)
    if (failedCases.length || failedAssertions.length) {
      md.push('')
      md.push('Failures:')
      for (const f of failedAssertions) md.push(`- ${f.name} (${f.detail})`)
    }
    fs.writeFileSync(path.join(OUT_DIR, 'MATRIX.md'), `${md.join('\n')}\n`)

    console.log(`Logo render matrix: ${chosen.id} (light=${lightTheme}, dark=${darkTheme})`)
    for (const a of assertions) console.log(`  ${a.pass ? 'PASS' : 'FAIL'}  ${a.name}  [${a.detail}]`)
    console.log('')
    console.log(`  cases ${report.summary.passed}/${report.summary.cases}, assertions ${report.summary.assertionsPassed}/${report.summary.assertions}`)
    console.log(`  evidence: ${OUT_DIR}`)
    if (pageErrors.length) console.log(`  page errors: ${pageErrors.join(' | ')}`)
    if (failedCases.length || failedAssertions.length || pageErrors.length) {
      process.exitCode = 1
    }
  } finally {
    await app.close().catch(() => undefined)
  }
}

main().catch((err) => {
  console.error(`matrix failed: ${err?.stack ?? err}`)
  process.exit(1)
})
