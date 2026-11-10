// Export the 47 original reference templates at 4:3 by variant id.
//
// For every manifest row this renders window.__fmVariants.render(variantId,
// { ratio: '4:3', maxEdge }) and writes <out>/<variantId>.png, plus
// <out>/photo-rects.json with the measured photo rect and canvas size for each
// export. Nothing is matched by card text or DOM class names.
//
// Usage
//   node scripts/audit-original47.cjs [--manifest <path>] [--out <dir>] [--max-edge 1600]
//
// If the manifest is absent a temporary 47-row mapping is derived from
// core/variants.ts in memory; the canonical manifest is never written.
const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const project = path.resolve(__dirname, '..', '..')
const workspace = path.resolve(project, '..')
const previewRoot = path.join(project, 'legacy-web/src/renderer/src/assets/previews')
const variantsFile = path.join(project, 'legacy-web/src/renderer/src/core/variants.ts')
const originalDir = path.join(workspace, '_research/original-47')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1]
  const kv = process.argv.find((a) => a.startsWith(`--${name}=`))
  if (kv) return kv.slice(name.length + 3)
  return fallback
}

const manifestPath = path.resolve(arg('manifest', path.join(originalDir, 'manifest.json')))
const outDir = path.resolve(arg('out', path.join(originalDir, 'exports/4x3')))
const maxEdge = Math.max(320, Math.floor(Number(arg('max-edge', '1600')) || 1600))

function loadManifestRows() {
  if (!fs.existsSync(manifestPath)) return null
  const raw = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  const rows = Array.isArray(raw) ? raw : raw.rows || raw.items || raw.templates || []
  if (!Array.isArray(rows)) throw new Error(`cannot find rows[] in ${manifestPath}`)
  return rows.map((r, i) => ({
    index: i + 1,
    variantId: r.variantId || r.id || r.variant || null,
    refFile: r.refFile || r.ref || r.reference || null,
    refPath: r.refPath || null,
    photoRectRef: r.photoRectRef || r.photoRect || null
  }))
}

// Temporary mapping only: mirrors the V('<id>', '<name>', '<category>',
// '<base>', '<refFile>.webp') rows in variants.ts whose reference preview
// exists on disk (exactly the 47 original templates).
function deriveRows() {
  const src = fs.readFileSync(variantsFile, 'utf8')
  const re = /V\('([^']+)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']+\.(?:webp|png|jpe?g))'/
  const rows = []
  for (const line of src.split('\n')) {
    const m = re.exec(line)
    if (!m) continue
    const refFile = m[5]
    if (!fs.existsSync(path.join(previewRoot, refFile))) continue
    rows.push({
      index: rows.length + 1,
      variantId: m[1],
      refFile,
      refPath: path.join(previewRoot, refFile),
      photoRectRef: null
    })
  }
  return rows
}

function measuredRect(record) {
  if (!record || record === 'unmeasured') return null
  const r = record
  if (typeof r === 'object' && Number.isFinite(r.x) && Number.isFinite(r.y) && Number.isFinite(r.w) && Number.isFinite(r.h)) {
    return { x: r.x, y: r.y, w: r.w, h: r.h }
  }
  return null
}

async function main() {
  const manifestRows = loadManifestRows()
  let rows
  let source
  if (manifestRows) {
    rows = manifestRows
    source = 'manifest'
  } else {
    rows = deriveRows()
    source = 'derived'
    console.log('manifest missing, derived 47-row mapping from variants.ts in memory (canonical manifest not written)')
  }

  fs.mkdirSync(outDir, { recursive: true })

  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const missing = []
  const unmeasured = []
  const rects = {}
  let ok = 0
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 45000 })
    const renderables = await page.evaluate(() => window.__fmVariants.list())
    const renderableIds = new Set(renderables.map((v) => v.id))

    for (const row of rows) {
      const id = row.variantId
      if (!id) {
        missing.push({ id: null, reason: 'row has no variantId' })
        continue
      }
      if (!renderableIds.has(id)) {
        missing.push({ id, reason: 'not exposed by window.__fmVariants' })
        continue
      }
      try {
        const res = await page.evaluate(
          ({ id, maxEdge }) => window.__fmVariants.render(id, { ratio: '4:3', maxEdge }),
          { id, maxEdge }
        )
        if (!res || typeof res.data !== 'string') throw new Error('empty render result')
        const buf = Buffer.from(res.data.replace(/^data:image\/\w+;base64,/, ''), 'base64')
        if (buf.length < 1000) throw new Error(`export too small: ${buf.length} bytes`)
        fs.writeFileSync(path.join(outDir, `${id}.png`), buf)
        const rect = measuredRect(res.photoRect)
        rects[id] = { variantId: id, photoRect: rect, W: res.W, H: res.H }
        if (!rect) unmeasured.push({ id, reason: 'render returned no photoRect' })
        ok++
      } catch (e) {
        missing.push({ id, reason: e.message })
        console.error('FAIL', id, e.message)
      }
    }
  } finally {
    await app.close().catch(() => undefined)
  }

  const refUnmeasured = rows.filter((r) => !measuredRect(r.photoRectRef)).length
  const payload = {
    generatedAt: new Date().toISOString(),
    source,
    manifest: source === 'manifest' ? manifestPath : null,
    ratio: '4:3',
    maxEdge,
    outDir,
    rows: rows.length,
    exported: ok,
    missing: missing.length,
    unmeasured: unmeasured.length,
    refUnmeasured,
    rects
  }
  fs.writeFileSync(path.join(outDir, 'photo-rects.json'), JSON.stringify(payload, null, 2))

  console.log(`rows ${rows.length} / missing ${missing.length} / unmeasured ${unmeasured.length}`)
  console.log(`exported ${ok} at 4:3 (maxEdge ${maxEdge}) -> ${outDir}`)
  console.log(`reference photo rects: unmeasured ${refUnmeasured} (manifest source: ${source})`)
  for (const m of missing) console.error(`MISSING ${m.id || '(no id)'}: ${m.reason}`)
  if (missing.length) process.exitCode = 1
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
