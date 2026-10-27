/**
 * FrameMark logo catalog builder — Phase 1a.
 *
 * Reads the read-only research corpus (catalog.json, render-index2.json, extras.json),
 * the existing app logo baseline (src/renderer/src/assets/logos) and the manual
 * curation file, then writes:
 *   - src/renderer/src/core/logo-catalog.ts            (generated data + helpers)
 *   - src/renderer/src/core/logo-version-assets.ts      (static resolver)
 *   - src/renderer/src/assets/logo-versions/**          (new 512px WebP cuts)
 *   - _research/logo-versions/_audit/{logo-catalog-audit.json,AUDIT.md,review.html}
 *
 * Idempotent: output ids are derived from stable source names, and the generated
 * asset directory is rebuilt from scratch on every run.
 */
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const APP_ROOT = path.resolve(__dirname, '../..')
const RESEARCH = path.resolve('D:/border&watermark/_research/logo-versions')
const APP_LOGOS = path.join(APP_ROOT, 'src/renderer/src/assets/logos')
const OUT_ASSETS = path.join(APP_ROOT, 'src/renderer/src/assets/logo-versions')
const CORE = path.join(APP_ROOT, 'src/renderer/src/core')
const AUDIT_DIR = path.join(RESEARCH, '_audit')
const CATALOG_JSON = path.join(RESEARCH, 'catalog.json')
const RENDER_JSON = path.join(RESEARCH, '_scratch/render-index2.json')
const EXTRAS_JSON = path.join(RESEARCH, '_scratch/extras.json')
const CURATION_JSON = path.join(__dirname, 'curation.json')

const LONG_EDGE = 512
const MAX_FILE_BYTES = 120 * 1024
const MAX_TOTAL_BYTES = 8 * 1024 * 1024
const SHAPE_HAMMING = 4
const ASPECT_TOLERANCE = 0.02
const COLOR_DISTANCE = 45

const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'))
const catalog = readJson(CATALOG_JSON)
const renders = readJson(RENDER_JSON)
const extras = readJson(EXTRAS_JSON)
const curation = readJson(CURATION_JSON)

const extraByFile = new Map(extras.map((e) => [e.file, e]))
const renderByPreview = new Map(renders.map((r) => [r.preview, r]))
const renderByFile = new Map(renders.map((r) => [r.file, r]))

for (const r of renders) {
  if (!fs.existsSync(path.join(RESEARCH, r.preview))) throw new Error(`missing preview ${r.preview}`)
  if (!fs.existsSync(path.join(RESEARCH, r.file))) throw new Error(`missing download ${r.file}`)
}
for (const e of extras) {
  if (!renderByFile.has(e.file)) throw new Error(`extra without render candidate: ${e.file}`)
}
const appFileNames = fs
  .readdirSync(APP_LOGOS)
  .filter((f) => f.toLowerCase().endsWith('.webp'))
  .sort()
if (appFileNames.length !== 153) throw new Error(`expected 153 app logo files, got ${appFileNames.length}`)

// ---------------------------------------------------------------------------
// Brand definitions
// ---------------------------------------------------------------------------
const brandDefs = catalog.brands.map((b) => ({
  key: b.key,
  label: b.label,
  cn: b.cn,
  group: b.group,
  lifecycle: b.lifecycle,
  canonicalVersion: b.canonicalVersion,
  note: b.note || ''
}))
brandDefs.push({
  key: 'lumo',
  label: 'LUMO',
  cn: 'LUMO',
  group: 'camera',
  lifecycle: 'uncertain',
  canonicalVersion: '',
  note: 'App-only asset, no catalog row.'
})
const brandByKey = new Map(brandDefs.map((b) => [b.key, b]))

const appBrandAlias = {
  hassel_h: 'hasu',
  hassel_wordmark: 'hasu',
  hasu_long: 'hasu',
  xiaomi_loing: 'xiaomi',
  canon_eos: 'canon',
  huawe_black: 'huawei',
  laika2: 'laika',
  laika_long: 'laika',
  caisi: 'caisi',
  zeiss: 'caisi',
  lumo_black: 'lumo',
  lumo_white: 'lumo'
}
const appPrefixes = [...Object.keys(appBrandAlias), ...brandDefs.map((b) => b.key)].sort(
  (a, b) => b.length - a.length
)

function brandKeyForAppStem(stem) {
  if (appBrandAlias[stem]) return appBrandAlias[stem]
  const prefix = appPrefixes.find((p) => stem === p || stem.startsWith(`${p}_`))
  return prefix ? appBrandAlias[prefix] || prefix : null
}

// ---------------------------------------------------------------------------
// Image analysis
// ---------------------------------------------------------------------------
const analysisCache = new Map()

function srgbLum(r, g, b) {
  const f = (v) => {
    const x = v / 255
    return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}

function hash64FromRegion(data, info, box, channel) {
  const vals = []
  for (let gy = 0; gy < 8; gy++) {
    for (let gx = 0; gx < 9; gx++) {
      const x = Math.min(info.width - 1, Math.max(0, Math.floor(box.x + ((gx + 0.5) / 9) * box.w)))
      const y = Math.min(info.height - 1, Math.max(0, Math.floor(box.y + ((gy + 0.5) / 8) * box.h)))
      const i = (y * info.width + x) * 4
      vals.push(channel === 'alpha' ? data[i + 3] : srgbLum(data[i], data[i + 1], data[i + 2]) * 255)
    }
  }
  let bits = 0n
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      if (vals[y * 9 + x] > vals[y * 9 + x + 1]) bits |= 1n << BigInt(y * 8 + x)
    }
  }
  return bits.toString(16).padStart(16, '0')
}

function alphaMask32(data, info, box) {
  let bits = 0n
  for (let gy = 0; gy < 32; gy++) {
    for (let gx = 0; gx < 32; gx++) {
      const x = Math.min(info.width - 1, Math.max(0, Math.floor(box.x + ((gx + 0.5) / 32) * box.w)))
      const y = Math.min(info.height - 1, Math.max(0, Math.floor(box.y + ((gy + 0.5) / 32) * box.h)))
      if (data[(y * info.width + x) * 4 + 3] > 64) bits |= 1n << BigInt(gy * 32 + gx)
    }
  }
  return bits.toString(16)
}

function maskIoU(a, b) {
  const x = BigInt(`0x${a}`)
  const y = BigInt(`0x${b}`)
  const union = x | y
  if (!union) return 0
  const pc = (v) => {
    let n = 0
    while (v) {
      n += Number(v & 1n)
      v >>= 1n
    }
    return n
  }
  return pc(x & y) / pc(union)
}

function hammingHex(a, b) {
  let x = BigInt(`0x${a}`) ^ BigInt(`0x${b}`)
  let n = 0
  while (x) {
    n += Number(x & 1n)
    x >>= 1n
  }
  return n
}

function percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.round((sorted.length - 1) * p)))
  return sorted[idx]
}

async function analyzeImage(absPath) {
  if (analysisCache.has(absPath)) return analysisCache.get(absPath)
  const promise = (async () => {
    const meta = await sharp(absPath, { failOn: 'none' }).metadata()
    const width = meta.width
    const height = meta.height
    const hasAlpha = Boolean(meta.hasAlpha)

    const { data, info } = await sharp(absPath, { failOn: 'none' })
      .resize(256, 256, { fit: 'fill' })
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true })
    const total = info.width * info.height

    const pix = (x, y) => {
      const i = (y * info.width + x) * 4
      return { r: data[i], g: data[i + 1], b: data[i + 2], a: data[i + 3] }
    }
    const corners = [pix(0, 0), pix(info.width - 1, 0), pix(0, info.height - 1), pix(info.width - 1, info.height - 1)]
    const pageWhite = corners.every(
      (c) => c.a > 200 && srgbLum(c.r, c.g, c.b) > 0.9 && Math.max(c.r, c.g, c.b) - Math.min(c.r, c.g, c.b) < 24
    )

    let inkCount = 0
    let opaqueCount = 0
    let satInk = 0
    let darkInk = 0
    let lightInk = 0
    let maxSat = 0
    let sumR = 0
    let sumG = 0
    let sumB = 0
    let minX = info.width
    let minY = info.height
    let maxX = -1
    let maxY = -1
    const lums = []

    for (let y = 0; y < info.height; y++) {
      for (let x = 0; x < info.width; x++) {
        const i = (y * info.width + x) * 4
        const a = data[i + 3]
        if (a <= 8) continue
        opaqueCount++
        const r = data[i]
        const g = data[i + 1]
        const b = data[i + 2]
        const lum = srgbLum(r, g, b)
        const mx = Math.max(r, g, b)
        const mn = Math.min(r, g, b)
        const sat = mx === 0 ? 0 : (mx - mn) / mx
        if (pageWhite && lum > 0.92 && sat < 0.08) continue
        inkCount++
        lums.push(lum)
        if (sat > maxSat) maxSat = sat
        if (sat >= 0.35) satInk++
        if (lum < 0.5) darkInk++
        if (lum > 0.75) lightInk++
        sumR += r
        sumG += g
        sumB += b
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
      }
    }

    lums.sort((a, b) => a - b)
    const bboxW = maxX >= minX ? maxX - minX + 1 : 0
    const bboxH = maxY >= minY ? maxY - minY + 1 : 0
    const bboxArea = bboxW * bboxH
    const bboxCoverage = bboxArea > 0 ? inkCount / bboxArea : 0

    // bbox light / white coverage (for baked plates)
    let bboxLight = 0
    let bboxWhite = 0
    if (bboxArea > 0) {
      for (let y = minY; y <= maxY; y++) {
        for (let x = minX; x <= maxX; x++) {
          const i = (y * info.width + x) * 4
          if (data[i + 3] <= 8) continue
          const lum = srgbLum(data[i], data[i + 1], data[i + 2])
          const mx = Math.max(data[i], data[i + 1], data[i + 2])
          const mn = Math.min(data[i], data[i + 1], data[i + 2])
          const sat = mx === 0 ? 0 : (mx - mn) / mx
          if (lum > 0.75) bboxLight++
          if (lum > 0.9 && sat < 0.1) bboxWhite++
        }
      }
    }

    const inkRatio = inkCount / total
    const opaqueRatio = opaqueCount / total
    const satRatio = inkCount ? satInk / inkCount : 0
    const darkRatio = inkCount ? darkInk / inkCount : 0
    const lightRatio = inkCount ? lightInk / inkCount : 0
    const bboxLightRatio = bboxArea ? bboxLight / bboxArea : 0
    const bboxWhiteRatio = bboxArea ? bboxWhite / bboxArea : 0
    const medianLum = percentile(lums, 0.5)
    const p10Lum = percentile(lums, 0.1)
    const p90Lum = percentile(lums, 0.9)
    const meanRgb = inkCount
      ? { r: Math.round(sumR / inkCount), g: Math.round(sumG / inkCount), b: Math.round(sumB / inkCount) }
      : { r: 0, g: 0, b: 0 }
    const isPlate = bboxCoverage >= 0.72 && bboxArea >= total * 0.03

    let inkClass = 'ambiguous'
    if (inkCount >= 24 && inkRatio >= 0.0002) {
      if (satRatio >= 0.02 && maxSat >= 0.35) inkClass = 'color'
      else if (medianLum < 0.5) inkClass = 'dark'
      else if (medianLum > 0.75) inkClass = 'light'
    }

    const hasDark = darkRatio >= 0.015
    const hasLight = lightRatio >= 0.015
    let surface = 'none'
    if (inkCount < 24 || inkRatio < 0.0002) {
      surface = 'none'
    } else if (pageWhite) {
      surface = 'light'
    } else if (isPlate) {
      if (bboxWhiteRatio > 0.35) surface = 'both'
      else if (hasDark && hasLight) surface = 'both'
      else if (hasDark) surface = 'light'
      else if (hasLight) surface = 'dark'
      else if (inkClass === 'color') surface = medianLum < 0.6 ? 'light' : medianLum > 0.8 ? 'dark' : 'none'
    } else if (hasDark && hasLight) {
      surface = darkRatio >= lightRatio ? 'light' : 'dark'
    } else if (hasDark) {
      surface = 'light'
    } else if (hasLight) {
      surface = 'dark'
    } else if (inkClass === 'color') {
      surface = medianLum < 0.55 ? 'light' : medianLum > 0.8 ? 'dark' : 'none'
    }

    const bbox = {
      x: minX,
      y: minY,
      w: Math.max(1, bboxW),
      h: Math.max(1, bboxH)
    }
    const dhashAlpha = hash64FromRegion(data, info, bbox, 'alpha')
    const dhashLuma = hash64FromRegion(data, info, bbox, 'luma')
    const alphaMask = alphaMask32(data, info, bbox)

    const norm = await sharp(absPath, { failOn: 'none' })
      .resize(128, 128, { fit: 'fill' })
      .ensureAlpha()
      .raw()
      .toBuffer()
    const sha = crypto.createHash('sha256').update(norm).digest('hex')

    const metrics = {
      width,
      height,
      hasAlpha,
      pageWhite,
      bbox,
      bboxAspect: +((bboxW / Math.max(1, bboxH)) * (width / Math.max(1, height))).toFixed(4),
      opaqueCount,
      inkCount,
      inkRatio: +inkRatio.toFixed(5),
      opaqueRatio: +opaqueRatio.toFixed(5),
      medianLum: +medianLum.toFixed(4),
      p10Lum: +p10Lum.toFixed(4),
      p90Lum: +p90Lum.toFixed(4),
      maxSat: +maxSat.toFixed(4),
      satRatio: +satRatio.toFixed(4),
      darkRatio: +darkRatio.toFixed(4),
      lightRatio: +lightRatio.toFixed(4),
      bboxCoverage: +bboxCoverage.toFixed(4),
      bboxWhiteRatio: +bboxWhiteRatio.toFixed(4),
      bboxLightRatio: +bboxLightRatio.toFixed(4),
      isPlate,
      meanRgb,
      inkClass,
      surface,
      previewSurface: surface === 'dark' ? 'dark' : 'light'
    }
    return { metrics, dhashAlpha, dhashLuma, alphaMask, sha }
  })()
  analysisCache.set(absPath, promise)
  return promise
}

async function mapPool(items, limit, fn) {
  const out = new Array(items.length)
  let next = 0
  async function worker() {
    for (;;) {
      const i = next++
      if (i >= items.length) return
      out[i] = await fn(items[i], i)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
  return out
}

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------
const slug = (s) =>
  String(s)
    .toLowerCase()
    .replace(/\.(svg|png|jpe?g|webp)$/i, '')
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^[-_]+|[-_]+$/g, '')

const idSafe = (s) => slug(s).replace(/_+/g, '_') || 'cut'
const cutIdSafe = (s) =>
  String(s)
    .toLowerCase()
    .replace(/\.(svg|png|jpe?g|webp)$/i, '')
    .replace(/[^a-z0-9_-]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '') || 'cut'

const titleCase = (s) =>
  s
    .split(/[\s_-]+/)
    .filter(Boolean)
    .map((w) => (w.length <= 3 && /^[a-z]+$/.test(w) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1)))
    .join(' ')

function humanName(cand) {
  const brand = brandByKey.get(cand.brandKey)
  if (cand.extra && cand.extra.note) {
    const note = cand.extra.note.charAt(0).toUpperCase() + cand.extra.note.slice(1)
    return note.toLowerCase().includes(brand.label.toLowerCase()) ? note : `${brand.label} — ${note}`
  }
  const stem = cand.stem.replace(/^(official|logo)[-_]/i, '')
  const words = titleCase(stem)
  return words.toLowerCase().includes(brand.label.toLowerCase()) ? words : `${brand.label} ${words}`
}

function toneRank(stem) {
  if (/_black$/.test(stem)) return 0
  if (/_white$/.test(stem)) return 1
  if (/_red$/.test(stem)) return 2
  if (/_yellow$/.test(stem)) return 3
  if (/_blue$/.test(stem)) return 4
  if (/_green$/.test(stem)) return 5
  return 6
}

function toneFromStem(stem) {
  const m = stem.match(/_(black|white|red|yellow|blue|green)$/)
  return m ? m[1] : null
}

function relAspectDiff(a, b) {
  return Math.abs(a - b) / Math.max(a, b)
}

function rgbDistance(a, b) {
  return Math.sqrt((a.r - b.r) ** 2 + (a.g - b.g) ** 2 + (a.b - b.b) ** 2)
}

// ---------------------------------------------------------------------------
// Candidate collection
// ---------------------------------------------------------------------------
const candidates = []
const candByFile = new Map()
const candByPreview = new Map()
const candByApp = new Map()

function makeCandidate({ path: cpath, previewPath, brandKey, stem, name, origin, isApp, extra }) {
  const cand = {
    path: cpath,
    previewPath,
    brandKey,
    stem,
    name,
    origin,
    isApp,
    extra,
    disposition: null,
    mappedVersionId: undefined,
    mappedCutId: undefined,
    note: '',
    assigned: false,
    analysis: null,
    absPath: isApp ? path.join(APP_LOGOS, `${stem}.webp`) : path.join(RESEARCH, previewPath || cpath)
  }
  candidates.push(cand)
  if (isApp) candByApp.set(stem, cand)
  else {
    candByFile.set(cpath, cand)
    if (previewPath) candByPreview.set(previewPath, cand)
  }
  return cand
}

for (const r of renders) {
  const extra = extraByFile.get(r.file)
  makeCandidate({
    path: r.file,
    previewPath: r.preview,
    brandKey: r.brand,
    stem: r.name.replace(/\.(svg|png|jpe?g|webp)$/i, ''),
    name: r.name,
    origin: extra ? 'extra' : 'candidate',
    isApp: false,
    extra
  })
}
for (const f of appFileNames) {
  const stem = f.replace(/\.webp$/i, '')
  const brandKey = stem === 'logo' || stem === 'logo_empty' ? 'app' : brandKeyForAppStem(stem)
  makeCandidate({
    path: `src/renderer/src/assets/logos/${f}`,
    previewPath: undefined,
    brandKey: brandKey || 'app',
    stem,
    name: f,
    origin: 'app-legacy',
    isApp: true
  })
}

await mapPool(candidates, 8, async (cand) => {
  cand.analysis = await analyzeImage(cand.absPath)
  cand.metrics = cand.analysis.metrics
  cand.inkClass = cand.metrics.inkClass
  cand.surface = cand.metrics.surface
  cand.dhash = cand.metrics.isPlate ? cand.analysis.dhashLuma : cand.analysis.dhashAlpha
  cand.sha = cand.analysis.sha
  cand.longEdge = Math.max(cand.metrics.width, cand.metrics.height)
})

// ---------------------------------------------------------------------------
// Version/cut model
// ---------------------------------------------------------------------------
const versions = []
const versionById = new Map()
const usedVersionIds = new Set()
const cutIdsByBrand = new Map()
const metricsByCutKey = new Map()

function uniqueVersionId(base) {
  const clean = idSafe(base)
  if (!usedVersionIds.has(clean)) {
    usedVersionIds.add(clean)
    return clean
  }
  let i = 2
  while (usedVersionIds.has(`${clean}-${i}`)) i++
  const id = `${clean}-${i}`
  usedVersionIds.add(id)
  return id
}

function uniqueCutId(brandKey, base) {
  if (!cutIdsByBrand.has(brandKey)) cutIdsByBrand.set(brandKey, new Set())
  const set = cutIdsByBrand.get(brandKey)
  const clean = cutIdSafe(base)
  if (!set.has(clean)) {
    set.add(clean)
    return clean
  }
  let i = 2
  while (set.has(`${clean}-${i}`)) i++
  const id = `${clean}-${i}`
  set.add(id)
  return id
}

function createVersion(spec) {
  const id = spec.id && !versionById.has(spec.id) ? spec.id : uniqueVersionId(spec.id || `${spec.brandKey}-version`)
  const v = {
    id,
    brandKey: spec.brandKey,
    name: spec.name || `${brandByKey.get(spec.brandKey)?.label || spec.brandKey} logo`,
    era: spec.era || 'legacy',
    years: spec.years || '—',
    kind: spec.kind,
    tones: spec.tones || [],
    cuts: [],
    primaryCut: spec.primaryCut || '',
    aliases: spec.aliases || [],
    source: spec.source,
    confidence: spec.confidence || 'low',
    status: spec.status || 'CANDIDATE',
    surface: 'none',
    _preferredPrimary: spec.primaryCut || '',
    _gapName: spec._gapName || ''
  }
  usedVersionIds.add(id)
  versions.push(v)
  versionById.set(id, v)
  return v
}

function sourceFromCatalog(src, origin = 'catalog') {
  if (!src) return { origin }
  return {
    url: src.url || undefined,
    format: src.format || undefined,
    license: src.license || undefined,
    retrievedAt: src.retrievedAt || undefined,
    httpStatus: src.httpStatus || undefined,
    origin
  }
}

function cutSourceForCandidate(cand) {
  if (cand.isApp) return { origin: 'app-legacy', format: 'WebP' }
  const catalogVersion = catalog.brands
    .find((b) => b.key === cand.brandKey)
    ?.versions.find((v) => v.localPreview === cand.previewPath)
  if (catalogVersion) return sourceFromCatalog(catalogVersion.source, 'catalog')
  if (cand.extra) {
    return { url: cand.extra.url, format: cand.extra.format, origin: 'extra' }
  }
  return { origin: 'candidate' }
}

function cutStatusForVersion(v) {
  if (v.status === 'READY') return 'READY'
  if (v.status === 'LEGACY') return 'LEGACY'
  if (v.status === 'GAP') return 'LEGACY'
  return 'CANDIDATE'
}

function addCut(v, cand, spec = {}) {
  const metrics = cand.metrics
  const cutId = spec.id ? uniqueCutIdIfNeeded(v.brandKey, spec.id) : uniqueCutId(v.brandKey, cand.stem)
  let ink = spec.ink || metrics.inkClass
  let surface = spec.surface || metrics.surface
  if (!spec.surface) {
    // App tone assets are authoritative for black/white cuts.
    const tone = toneFromStem(cand.stem)
    if (tone === 'black' && (ink === 'dark' || ink === 'ambiguous')) {
      ink = 'dark'
      surface = 'light'
    } else if (tone === 'white' && (ink === 'light' || ink === 'ambiguous' || ink === 'color')) {
      ink = 'light'
      surface = 'dark'
    }
  }
  const previewSurface = spec.previewSurface || (surface === 'dark' ? 'dark' : 'light')
  const file = cand.isApp ? `${cand.stem}.webp` : cand.previewPath || cand.path
  const assetKey = cand.isApp ? cand.stem : `ver:${v.brandKey}:${cutId}`
  const cut = {
    id: cutId,
    assetKey,
    file,
    ink,
    surface,
    previewSurface,
    width: metrics.width,
    height: metrics.height,
    aliases: spec.aliases ? [...spec.aliases] : [],
    source: spec.source || cutSourceForCandidate(cand),
    confidence: spec.confidence || v.confidence,
    status: spec.status || cutStatusForVersion(v),
    _metrics: metrics,
    _cand: cand,
    _isApp: cand.isApp,
    _emit: !cand.isApp,
    _srcAbs: cand.absPath,
    _previewPath: cand.previewPath,
    _longEdge: cand.longEdge
  }
  v.cuts.push(cut)
  metricsByCutKey.set(`${v.brandKey}/${cutId}`, metrics)
  cand.assigned = true
  cand.mappedVersionId = v.id
  cand.mappedCutId = cut.id
  cand.disposition = spec.disposition || 'primary'
  return cut
}

function uniqueCutIdIfNeeded(brandKey, preferred) {
  const set = cutIdsByBrand.get(brandKey) || new Set()
  if (!set.has(preferred)) {
    if (!cutIdsByBrand.has(brandKey)) cutIdsByBrand.set(brandKey, set)
    set.add(preferred)
    return preferred
  }
  return uniqueCutId(brandKey, preferred)
}

function markAlias(cand, v, cut, note = '') {
  if (cand.assigned) return
  cand.assigned = true
  cand.mappedVersionId = v.id
  cand.mappedCutId = cut.id
  const samePixels =
    cand.sha === cut._cand.sha ||
    (cand.dhash === cut._cand.dhash && rgbDistance(cand.metrics.meanRgb, cut._metrics.meanRgb) < 8)
  cand.disposition = samePixels ? 'duplicate' : 'alias'
  if (note) cand.note = note
  const ref = cand.isApp ? cand.stem : cand.previewPath || cand.path
  if (!cut.aliases.includes(ref)) cut.aliases.push(ref)
}

// ---------------------------------------------------------------------------
// 1) Seed catalog versions (non-curated brands)
// ---------------------------------------------------------------------------
const curatedBrands = new Set(Object.keys(curation.brands || {}))

for (const brand of catalog.brands) {
  const brandMeta = brandByKey.get(brand.key)
  if (curatedBrands.has(brand.key)) {
    // Still register catalog versions so curation can reuse their ids/metadata.
    for (const cv of brand.versions) {
      if (!versionById.has(cv.id)) {
        createVersion({
          id: cv.id,
          brandKey: brand.key,
          name: cv.name,
          era: cv.era,
          years: cv.years,
          kind: cv.kind,
          tones: cv.tones,
          confidence: cv.confidence === 'none' ? 'low' : cv.confidence,
          status: cv.status,
          source: sourceFromCatalog(cv.source),
          _gapName: cv.name
        })
      }
    }
    continue
  }
  for (const cv of brand.versions) {
    const v = createVersion({
      id: cv.id,
      brandKey: brand.key,
      name: cv.name,
      era: cv.era,
      years: cv.years,
      kind: cv.kind,
      tones: cv.tones,
      confidence: cv.confidence === 'none' ? 'low' : cv.confidence,
      status: cv.status,
      source: sourceFromCatalog(cv.source),
      _gapName: cv.name
    })
    if (cv.status === 'GAP' || !cv.localPreview) continue
    const cand = candByPreview.get(cv.localPreview)
    if (!cand) throw new Error(`catalog preview not in render index: ${brand.key}/${cv.id} ${cv.localPreview}`)
    addCut(v, cand, {
      id: v.id,
      status: 'READY',
      confidence: v.confidence
    })
    const toneHints = {
      'dark-ink': 'dark',
      'light-ink': 'light',
      color: 'color'
    }
    const hint = toneHints[cv.mode]
    const cut = v.cuts[0]
    if (hint && cut.ink === 'ambiguous') cut.ink = hint
    if (cut.surface === 'none' && hint) cut.surface = hint === 'light' ? 'dark' : hint === 'dark' ? 'light' : 'light'
    if (cut.surface === 'none') cut.previewSurface = 'light'
    cut.previewSurface = cut.surface === 'dark' ? 'dark' : 'light'
  }
}

// ---------------------------------------------------------------------------
// 2) Curation (fushi / hasu)
// ---------------------------------------------------------------------------
function resolveCurationRef(ref) {
  if (!ref) return null
  if (ref.startsWith('app:')) {
    const stem = ref.slice(4)
    const cand = candByApp.get(stem)
    if (!cand) throw new Error(`curation app asset not found: ${stem}`)
    return cand
  }
  if (candByPreview.has(ref)) return candByPreview.get(ref)
  if (candByFile.has(ref)) return candByFile.get(ref)
  if (candByApp.has(ref)) return candByApp.get(ref)
  throw new Error(`curation ref not found: ${ref}`)
}

for (const [brandKey, spec] of Object.entries(curation.brands || {})) {
  for (const vs of spec.versions || []) {
    let v = versionById.get(vs.id)
    if (!v) {
      v = createVersion({
        id: vs.id,
        brandKey,
        name: vs.name,
        era: vs.era,
        years: vs.years,
        kind: vs.kind,
        tones: vs.tones,
        confidence: vs.confidence,
        status: vs.status
      })
    } else {
      v.name = vs.name || v.name
      v.era = vs.era || v.era
      v.years = vs.years || v.years
      v.kind = vs.kind || v.kind
      v.tones = vs.tones || v.tones
      v.confidence = vs.confidence || v.confidence
      v.status = vs.status || v.status
      v.aliases = vs.aliases ? [...vs.aliases] : v.aliases
      v._preferredPrimary = vs.primaryCut || v._preferredPrimary
    }
    v.cuts = []
    for (const cs of vs.cuts || []) {
      const cand = resolveCurationRef(cs.app ? `app:${cs.app}` : cs.file)
      if (!cand) throw new Error(`curation cut has no candidate: ${vs.id}/${cs.id}`)
      const cut = addCut(v, cand, {
        id: cs.id,
        ink: cs.ink,
        surface: cs.surface,
        previewSurface: cs.previewSurface,
        status: cutStatusForVersion(v),
        confidence: vs.confidence || v.confidence,
        disposition: 'primary'
      })
      if (cs.aliases) {
        for (const aliasRef of cs.aliases) {
          const aliasCand = resolveCurationRef(aliasRef)
          if (aliasCand.assigned && aliasCand.mappedCutId !== cut.id) {
            throw new Error(`curation alias already assigned: ${aliasRef}`)
          }
          if (!aliasCand.assigned) markAlias(aliasCand, v, cut, `curation alias of ${cut.id}`)
        }
        // Keep normalized refs in the cut alias list.
        for (const aliasRef of cs.aliases) {
          const normalized = aliasRef.startsWith('app:') ? aliasRef.slice(4) : aliasRef
          if (!cut.aliases.includes(normalized)) cut.aliases.push(normalized)
        }
      }
    }
  }
  for (const u of spec.unusable || []) {
    const cand = resolveCurationRef(u.file)
    if (cand) {
      cand.assigned = true
      cand.disposition = 'unusable'
      cand.note = u.note || 'unusable'
    }
  }
}

// Catalog versions in curated brands that curation did not cover would now be empty.
for (const brand of catalog.brands) {
  if (!curatedBrands.has(brand.key)) continue
  for (const cv of brand.versions) {
    const v = versionById.get(cv.id)
    if (v && v.cuts.length === 0 && cv.status === 'READY') {
      // Fall back to the catalog preview rather than silently dropping the row.
      const cand = candByPreview.get(cv.localPreview)
      if (cand && !cand.assigned) {
        addCut(v, cand, { id: v.id, status: 'READY' })
        v.status = 'READY'
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 3) Match / create
// ---------------------------------------------------------------------------
function toneClassOfStem(stem) {
  const tone = toneFromStem(stem)
  if (tone === 'black') return 'dark'
  if (tone === 'white') return 'light'
  return null
}

function shapeMatch(cand, cut, tolerance = ASPECT_TOLERANCE) {
  if (!cut._metrics) return false
  const a = cand.metrics.bboxAspect || cand.metrics.width / cand.metrics.height
  const b = cut._metrics.bboxAspect || cut.width / cut.height
  if (relAspectDiff(a, b) > tolerance) return false
  const alphaUsable = (m, hash) => !m.isPlate || BigInt(`0x${hash}`) !== 0n
  if (alphaUsable(cand.metrics, cand.analysis.dhashAlpha) && alphaUsable(cut._metrics, cut._cand.analysis.dhashAlpha)) {
    if (hammingHex(cand.analysis.dhashAlpha, cut._cand.analysis.dhashAlpha) <= SHAPE_HAMMING) return true
    if (maskIoU(cand.analysis.alphaMask, cut._cand.analysis.alphaMask) >= 0.9) return true
  }
  return hammingHex(cand.analysis.dhashLuma, cut._cand.analysis.dhashLuma) <= SHAPE_HAMMING
}

function colorRelation(cand, cut) {
  // Explicit _black/_white app assets are authoritative tone cuts even when a
  // coloured accent (e.g. LUMO's green O, MEIZU's blue glyph) affects metrics.
  const a = toneClassOfStem(cand.stem) || cand.inkClass
  const b = toneClassOfStem(cut._cand.stem) || cut.ink
  if (a === 'ambiguous' || b === 'ambiguous') return null
  if (a === 'color' && b === 'color') {
    return rgbDistance(cand.metrics.meanRgb, cut._metrics.meanRgb) <= COLOR_DISTANCE ? 'same' : null
  }
  if (a === b) return 'same'
  if ((a === 'dark' && b === 'light') || (a === 'light' && b === 'dark')) return 'tone'
  return null
}

function findByShape(cand) {
  const brandVersions = versions.filter((v) => v.brandKey === cand.brandKey)
  for (const v of brandVersions) {
    for (const cut of v.cuts) {
      const candTone = toneClassOfStem(cand.stem)
      const cutTone = toneClassOfStem(cut._cand.stem)
      const explicitTonePair = Boolean(candTone && cutTone && candTone !== cutTone)
      if (explicitTonePair) {
        // Rule 3: a black/white app pair of the same brand is one version when
        // the trimmed aspect matches, even if a baked plate defeats dHash.
        const ta = cand.metrics.bboxAspect || cand.metrics.width / cand.metrics.height
        const tb = cut._metrics?.bboxAspect || cut.width / cut.height
        if (relAspectDiff(ta, tb) <= 0.03) return { version: v, cut, relation: 'tone' }
        continue
      }
      if (shapeMatch(cand, cut)) {
        const rel = colorRelation(cand, cut)
        if (rel) return { version: v, cut, relation: rel }
      }
    }
  }
  return null
}

function gapVersionFor(brandKey) {
  return versions.find((v) => v.brandKey === brandKey && v.cuts.length === 0 && v.status === 'GAP')
}

function createAutoVersion(cand, status, era = 'legacy') {
  const brand = brandByKey.get(cand.brandKey)
  const gap = gapVersionFor(cand.brandKey)
  let id
  if (gap) {
    id = gap.id
  } else if (cand.isApp) {
    const base = cand.stem
      .replace(new RegExp(`^${cand.brandKey}[_-]?`, 'i'), '')
      .replace(/_(black|white|red|yellow|blue|green)$/, '')
    id = uniqueVersionId(base ? `${cand.brandKey}-legacy-${base}` : `${cand.brandKey}-legacy`)
  } else {
    id = uniqueVersionId(cand.stem)
  }
  const v = gap || createVersion({ id, brandKey: cand.brandKey, status })
  if (gap) {
    v.aliases = [gap._gapName || gap.name].filter(Boolean)
  }
  v.name = humanName(cand)
  v.era = era
  v.years = '—'
  v.confidence = cand.origin === 'extra' ? 'medium' : cand.isApp ? 'medium' : 'low'
  v.status = status
  if (!v.source && !cand.isApp) v.source = cutSourceForCandidate(cand)
  v._preferredPrimary = ''
  addCut(v, cand, {
    id: cand.isApp ? cand.stem : v.id,
    status: status === 'LEGACY' ? 'LEGACY' : 'CANDIDATE',
    confidence: v.confidence,
    disposition: 'primary'
  })
  return v
}

function attachToneCut(v, cand) {
  const cut = addCut(v, cand, {
    id: cand.isApp ? cand.stem : uniqueCutId(cand.brandKey, cand.stem),
    status: cutStatusForVersion(v),
    confidence: v.confidence,
    disposition: 'merged'
  })
  // White/black tone rule overrides fuzzy analysis.
  const tone = toneFromStem(cand.stem)
  if (tone === 'white') {
    cut.ink = cut.ink === 'color' ? 'color' : 'light'
    cut.surface = 'dark'
    cut.previewSurface = 'dark'
  } else if (tone === 'black') {
    cut.ink = cut.ink === 'color' ? 'color' : 'dark'
    cut.surface = 'light'
    cut.previewSurface = 'light'
  }
  cand.note = `merged as second tone cut of ${v.id}`
  return cut
}

function promoteAppCut(v, cut, cand) {
  const oldCand = cut._cand
  if (oldCand && oldCand !== cand) {
    const ref = oldCand.isApp ? oldCand.stem : oldCand.previewPath || oldCand.path
    if (!cut.aliases.includes(ref)) cut.aliases.push(ref)
    oldCand.assigned = true
    oldCand.mappedVersionId = v.id
    oldCand.mappedCutId = cut.id
    oldCand.disposition = 'alias'
    oldCand.note = `superseded by app baseline ${cand.stem}`
  }
  cut.assetKey = cand.stem
  cut.file = `${cand.stem}.webp`
  cut.width = cand.metrics.width
  cut.height = cand.metrics.height
  cut.source = { origin: 'app-legacy', format: 'WebP' }
  cut._cand = cand
  cut._isApp = true
  cut._emit = false
  cut._srcAbs = cand.absPath
  cut._previewPath = undefined
  cut._longEdge = cand.longEdge
  cand.assigned = true
  cand.mappedVersionId = v.id
  cand.mappedCutId = cut.id
  cand.disposition = 'primary'
  cand.note = `existing app baseline used in place for ${v.id}/${cut.id}`
}

function handleMatched(cand, match, { allowPromote = true } = {}) {
  const { version: v, cut, relation } = match
  if (relation === 'tone') {
    attachToneCut(v, cand)
    return
  }
  if (allowPromote && cand.isApp && cut._emit && !cut._isApp && cand.longEdge >= 256) {
    promoteAppCut(v, cut, cand)
    return
  }
  markAlias(cand, v, cut, `same shape as ${v.id}/${cut.id}`)
}

// Extras first, then remaining renders (stable source order), then app assets.
const extraCands = candidates.filter((c) => !c.isApp && c.extra)
const renderCands = candidates.filter((c) => !c.isApp && !c.extra)
const appCands = candidates
  .filter((c) => c.isApp)
  .sort((a, b) => toneRank(a.stem) - toneRank(b.stem) || a.stem.localeCompare(b.stem))

for (const cand of [...extraCands, ...renderCands]) {
  if (cand.assigned) continue
  if (cand.brandKey === 'app') continue
  const match = findByShape(cand)
  if (match) {
    handleMatched(cand, match)
  } else {
    createAutoVersion(cand, 'CANDIDATE', 'legacy')
  }
}

for (const cand of appCands) {
  if (cand.assigned) continue
  if (cand.brandKey === 'app') {
    cand.assigned = true
    cand.disposition = 'unusable'
    cand.note = 'app chrome / empty placeholder, not a brand logo'
    continue
  }
  if (!brandByKey.has(cand.brandKey)) {
    cand.assigned = true
    cand.disposition = 'unusable'
    cand.note = `no brand registered for ${cand.stem}`
    continue
  }
  const match = findByShape(cand)
  if (match) {
    handleMatched(cand, match)
  } else {
    createAutoVersion(cand, 'LEGACY', 'legacy')
  }
}

// Any render candidate still unassigned (e.g. curated brand leftovers) gets a version.
for (const cand of candidates) {
  if (cand.assigned) continue
  if (cand.brandKey === 'app') {
    cand.assigned = true
    cand.disposition = 'unusable'
    cand.note = 'app chrome / empty placeholder'
    continue
  }
  createAutoVersion(cand, cand.isApp ? 'LEGACY' : 'CANDIDATE', cand.isApp ? 'legacy' : 'legacy')
}

// Post-pass: a black/white app pair that landed in two versions (usually because
// one side was already grouped with a catalog cut) is one version. Move the
// sibling tone cut into the higher-priority version and retire the shell version.
function mergeSplitTonePairs() {
  const groups = new Map()
  for (const v of versions) {
    for (const cut of v.cuts) {
      const m = cut._cand.stem.match(/^(.*)_(black|white)$/)
      if (!m) continue
      const key = `${v.brandKey}/${m[1]}`
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push({ version: v, cut, tone: m[2] })
    }
  }
  const retired = new Set()
  for (const entries of groups.values()) {
    const black = entries.find((e) => e.tone === 'black')
    const white = entries.find((e) => e.tone === 'white')
    if (!black || !white) continue
    if (black.version === white.version) continue
    const rank = (v) => (v.status === 'READY' ? 0 : v.status === 'LEGACY' ? 1 : 2)
    const target = rank(black.version) <= rank(white.version) ? black.version : white.version
    const source = target === black.version ? white.version : black.version
    const moved = target === black.version ? white : black
    const ta = black.cut._metrics.bboxAspect || black.cut.width / black.cut.height
    const tb = white.cut._metrics.bboxAspect || white.cut.width / white.cut.height
    if (relAspectDiff(ta, tb) > 0.045) continue
    if (source.cuts.length !== 1) continue
    target.cuts.push(moved.cut)
    moved.cut.status = target.status === 'READY' ? 'READY' : target.status === 'LEGACY' ? 'LEGACY' : 'CANDIDATE'
    moved.cut._cand.disposition = 'merged'
    moved.cut._cand.mappedVersionId = target.id
    moved.cut._cand.mappedCutId = moved.cut.id
    moved.cut._cand.note = `black/white tone pair merged into ${target.id}`
    target.aliases.push(source.id, source.name)
    retired.add(source)
    versionById.delete(source.id)
  }
  if (retired.size) {
    for (let i = versions.length - 1; i >= 0; i--) {
      if (retired.has(versions[i])) versions.splice(i, 1)
    }
  }
}
mergeSplitTonePairs()

// ---------------------------------------------------------------------------
// 4) Finalize versions
// ---------------------------------------------------------------------------
function computeSurface(v) {
  if (!v.cuts.length) return 'none'
  if (v.cuts.some((c) => c.surface === 'both')) return 'both'
  const light = v.cuts.some((c) => c.surface === 'light')
  const dark = v.cuts.some((c) => c.surface === 'dark')
  if (light && dark) return 'both'
  if (light) return 'light'
  if (dark) return 'dark'
  return 'none'
}

function statusRank(s) {
  return s === 'READY' ? 0 : s === 'LEGACY' ? 1 : s === 'CANDIDATE' ? 2 : 3
}

function finalize() {
  for (const v of versions) {
    v.surface = computeSurface(v)
    if (!v.cuts.length) {
      v.status = 'GAP'
      v.primaryCut = ''
      v.tones = v.tones && v.tones.length ? v.tones : ['unknown']
      continue
    }
    if (v.cuts.some((c) => c.status === 'READY')) v.status = 'READY'
    else if (v.cuts.some((c) => c.status === 'LEGACY')) v.status = 'LEGACY'
    else v.status = 'CANDIDATE'

    const preferred = v._preferredPrimary
    const target = v.surface === 'dark' ? 'dark' : 'light'
    const usable = v.cuts.filter((c) => c.surface === target || c.surface === 'both')
    const pool = usable.length ? usable : v.cuts
    const confRank = (c) => (c === 'high' ? 0 : c === 'medium' ? 1 : 2)
    pool.sort((a, b) => statusRank(a.status) - statusRank(b.status) || confRank(a.confidence) - confRank(b.confidence))
    let primary = preferred ? v.cuts.find((c) => c.id === preferred) : pool[0]
    if (!primary) primary = pool[0]
    // Never pick a white/light-ink cut as primary for a light-surface version.
    if (v.surface === 'light' && primary.surface === 'dark') {
      primary = v.cuts.find((c) => c.surface === 'light' || c.surface === 'both') || v.cuts[0]
    }
    if (v.surface === 'dark' && primary.surface === 'light') {
      primary = v.cuts.find((c) => c.surface === 'dark' || c.surface === 'both') || v.cuts[0]
    }
    v.primaryCut = primary.id
    if (!v.tones || !v.tones.length) {
      v.tones = [
        ...new Set(
          v.cuts.map((c) => (c.ink === 'dark' ? 'black' : c.ink === 'light' ? 'white' : c.ink === 'color' ? 'color' : 'unknown'))
        )
      ]
    }
  }
}
finalize()

// Sort brands/versions deterministically for output.
const brandOrder = new Map(brandDefs.map((b, i) => [b.key, i]))
versions.sort((a, b) => (brandOrder.get(a.brandKey) ?? 999) - (brandOrder.get(b.brandKey) ?? 999) || a.id.localeCompare(b.id))

// Validate unique ids / asset keys.
const seenVersion = new Set()
const seenCutKey = new Set()
for (const v of versions) {
  if (seenVersion.has(v.id)) throw new Error(`duplicate version id ${v.id}`)
  seenVersion.add(v.id)
  for (const c of v.cuts) {
    const key = `${v.brandKey}/${c.id}`
    if (seenCutKey.has(key)) throw new Error(`duplicate cut id ${key}`)
    seenCutKey.add(key)
  }
}

// ---------------------------------------------------------------------------
// 5) Emit assets
// ---------------------------------------------------------------------------
fs.rmSync(OUT_ASSETS, { recursive: true, force: true })
fs.mkdirSync(OUT_ASSETS, { recursive: true })

const emitted = []
const emittedByCut = new Map()

async function encodeCut(cut, v, quality, longEdge) {
  const outDir = path.join(OUT_ASSETS, v.brandKey)
  fs.mkdirSync(outDir, { recursive: true })
  const outPath = path.join(outDir, `${cut.id}.webp`)
  let pipeline = sharp(cut._srcAbs, { failOn: 'none' })
  const m = cut._metrics
  if (m.hasAlpha && !m.pageWhite) {
    try {
      pipeline = pipeline.trim({ background: { r: 0, g: 0, b: 0, alpha: 0 }, threshold: 1 })
    } catch {
      // keep original bounds if trim fails
    }
  }
  pipeline = pipeline.resize({ width: longEdge, height: longEdge, fit: 'inside', withoutEnlargement: true })
  await pipeline.webp({ quality, alphaQuality: 90, effort: 5 }).toFile(outPath)
  const stat = fs.statSync(outPath)
  return { path: outPath, bytes: stat.size }
}

for (const v of versions) {
  for (const cut of v.cuts) {
    if (!cut._emit || !cut.assetKey.startsWith('ver:')) continue
    let quality = 85
    let longEdge = LONG_EDGE
    let result = await encodeCut(cut, v, quality, longEdge)
    while (result.bytes > MAX_FILE_BYTES && (quality > 60 || longEdge > 384)) {
      if (quality > 60) quality -= 10
      else longEdge -= 64
      result = await encodeCut(cut, v, quality, longEdge)
    }
    emitted.push({
      brandKey: v.brandKey,
      versionId: v.id,
      cutId: cut.id,
      status: v.status,
      path: path.relative(APP_ROOT, result.path).replace(/\\/g, '/'),
      bytes: result.bytes,
      quality,
      longEdge
    })
    emittedByCut.set(`${v.brandKey}/${cut.id}`, result.path)
  }
}

const emittedBytes = emitted.reduce((n, a) => n + a.bytes, 0)
const maxEmittedBytes = emitted.reduce((n, a) => Math.max(n, a.bytes), 0)
if (emittedBytes > MAX_TOTAL_BYTES) {
  throw new Error(`asset budget exceeded: ${emittedBytes} bytes > ${MAX_TOTAL_BYTES}`)
}
for (const a of emitted) {
  if (a.bytes > MAX_FILE_BYTES) throw new Error(`per-file budget exceeded: ${a.path} ${a.bytes}`)
}

// ---------------------------------------------------------------------------
// 6) Generate TS
// ---------------------------------------------------------------------------
function publicVersion(v) {
  return {
    id: v.id,
    brandKey: v.brandKey,
    name: v.name,
    era: v.era,
    years: v.years,
    ...(v.kind ? { kind: v.kind } : {}),
    tones: v.tones,
    cuts: v.cuts.map((c) => ({
      id: c.id,
      assetKey: c.assetKey,
      file: c.file,
      ink: c.ink,
      surface: c.surface,
      previewSurface: c.previewSurface,
      width: c.width,
      height: c.height,
      aliases: [...new Set(c.aliases)],
      source: c.source,
      confidence: c.confidence,
      status: c.status
    })),
    primaryCut: v.primaryCut,
    aliases: [...new Set(v.aliases)],
    ...(v.source ? { source: v.source } : {}),
    confidence: v.confidence,
    status: v.status,
    surface: v.surface
  }
}

const publicVersions = versions.map(publicVersion)
const publicBrands = brandDefs.map((b) => {
  const canonical =
    b.canonicalVersion ||
    versions.find((v) => v.brandKey === b.key && v.status === 'READY')?.id ||
    versions.find((v) => v.brandKey === b.key)?.id ||
    ''
  return {
    key: b.key,
    label: b.label,
    cn: b.cn,
    group: b.group,
    lifecycle: b.lifecycle,
    canonicalVersion: canonical,
    ...(b.note ? { note: b.note } : {})
  }
})
const publicCandidates = candidates.map((c) => ({
  path: c.path,
  ...(c.previewPath ? { previewPath: c.previewPath } : {}),
  brandKey: c.brandKey,
  hash: c.sha,
  dhash: c.dhash,
  ...(c.mappedVersionId ? { mappedVersionId: c.mappedVersionId } : {}),
  ...(c.mappedCutId ? { mappedCutId: c.mappedCutId } : {}),
  disposition: c.disposition || 'unusable',
  ...(c.note ? { note: c.note } : {})
}))

const tsHeader = `// AUTO-GENERATED by scripts/logo-pipeline/build-catalog.mjs — do not edit by hand.
// Phase 1a logo version catalog. Source of truth: _research/logo-versions/_audit/logo-catalog-audit.json

export type LogoInk = 'dark' | 'light' | 'color' | 'ambiguous'
export type LogoSurface = 'light' | 'dark' | 'both' | 'none'
export type LogoStatus = 'READY' | 'GAP' | 'LEGACY' | 'CANDIDATE' | 'UNUSABLE'
export type LogoOrigin = 'catalog' | 'extra' | 'app-legacy' | 'candidate'

export interface LogoSource {
  url?: string
  format?: string
  license?: string
  retrievedAt?: string
  httpStatus?: number
  origin: LogoOrigin
}

export interface LogoCut {
  /** Stable cut id, unique within a brand (e.g. 'fushi_black', 'fujifilm-3'). */
  id: string
  /** Key resolvable to a URL: an existing app file stem or \`ver:<brandKey>:<cutId>\`. */
  assetKey: string
  /** Source path relative to _research/logo-versions, or the app asset file name. */
  file: string
  ink: LogoInk
  surface: LogoSurface
  /** Best preview background for this cut. */
  previewSurface: 'light' | 'dark'
  width: number
  height: number
  /** Other file stems / candidate paths that dedupe to this cut. */
  aliases: string[]
  source: LogoSource
  confidence: 'high' | 'medium' | 'low'
  status: LogoStatus
}

export interface LogoVersion {
  id: string
  brandKey: string
  name: string
  era: 'current' | 'classic' | 'historical' | 'legacy'
  years: string
  kind?: string
  tones: string[]
  /** One or more tone cuts of the same design. */
  cuts: LogoCut[]
  primaryCut: string
  aliases: string[]
  source?: LogoSource
  confidence: 'high' | 'medium' | 'low'
  status: 'READY' | 'GAP' | 'LEGACY' | 'CANDIDATE'
  /** Union of cut surfaces: 'both' when black and white cuts exist. */
  surface: LogoSurface
}

export interface LogoBrand {
  key: string
  label: string
  cn: string
  group: string
  lifecycle: string
  canonicalVersion: string
  note?: string
}

export interface LogoCandidate {
  /** downloads/... for rendered candidates, src/renderer/src/assets/logos/... for app files. */
  path: string
  previewPath?: string
  brandKey: string
  hash: string
  dhash: string
  mappedVersionId?: string
  mappedCutId?: string
  disposition: 'primary' | 'alias' | 'duplicate' | 'unusable' | 'merged'
  note?: string
}

`

const tsData =
  `\nexport const LOGO_BRANDS: LogoBrand[] = ${JSON.stringify(publicBrands, null, 2)}\n\n` +
  `export const LOGO_VERSIONS: LogoVersion[] = ${JSON.stringify(publicVersions, null, 2)}\n\n` +
  `export const LOGO_VERSION_BY_ID: Record<string, LogoVersion> = Object.fromEntries(\n  LOGO_VERSIONS.map((v) => [v.id, v])\n)\n\n` +
  `export const LOGO_CUT_BY_ASSET_KEY: Record<string, { version: LogoVersion; cut: LogoCut }> = (() => {\n` +
  `  const out: Record<string, { version: LogoVersion; cut: LogoCut }> = {}\n` +
  `  for (const version of LOGO_VERSIONS) {\n` +
  `    for (const cut of version.cuts) {\n` +
  `      out[cut.assetKey] = { version, cut }\n` +
  `      for (const alias of cut.aliases) {\n` +
  `        if (!alias.includes('/') && !out[alias]) out[alias] = { version, cut }\n` +
  `      }\n` +
  `    }\n` +
  `  }\n` +
  `  return out\n` +
  `})()\n\n` +
  `export const LOGO_CANDIDATES: LogoCandidate[] = ${JSON.stringify(publicCandidates, null, 2)}\n\n` +
  `export function versionForAssetKey(assetKey: string): LogoVersion | null {\n` +
  `  return LOGO_CUT_BY_ASSET_KEY[assetKey]?.version ?? null\n` +
  `}\n\n` +
  `export function cutForAssetKey(assetKey: string): LogoCut | null {\n` +
  `  return LOGO_CUT_BY_ASSET_KEY[assetKey]?.cut ?? null\n` +
  `}\n\n` +
  `export function cutsForSurface(version: LogoVersion, surface: 'light' | 'dark'): LogoCut[] {\n` +
  `  return version.cuts.filter((cut) => cut.surface === surface || cut.surface === 'both')\n` +
  `}\n\n` +
  `export function bestCutForSurface(version: LogoVersion, surface: 'light' | 'dark'): LogoCut | null {\n` +
  `  const cuts = cutsForSurface(version, surface)\n` +
  `  if (!cuts.length) return null\n` +
  `  const rank = (s: LogoCut['status']): number => (s === 'READY' ? 0 : s === 'LEGACY' ? 1 : s === 'CANDIDATE' ? 2 : 3)\n` +
  `  const conf = (c: LogoCut['confidence']): number => (c === 'high' ? 0 : c === 'medium' ? 1 : 2)\n` +
  `  return [...cuts].sort((a, b) => rank(a.status) - rank(b.status) || conf(a.confidence) - conf(b.confidence))[0]\n` +
  `}\n`

const tsPath = path.join(CORE, 'logo-catalog.ts')
fs.writeFileSync(tsPath, tsHeader + tsData, 'utf8')

const resolver = `// AUTO-GENERATED by scripts/logo-pipeline/build-catalog.mjs
import { LOGO_FILES } from './logo-files'

const modules = import.meta.glob('../assets/logo-versions/**/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>

const VERSION_URLS: Record<string, string> = {}
for (const [filePath, url] of Object.entries(modules)) {
  const match = filePath.match(/logo-versions\\/([^/]+)\\/([^/]+)\\.webp$/)
  if (match) VERSION_URLS[\`ver:\${match[1]}:\${match[2]}\`] = url
}

/**
 * Resolve a catalog asset key to a URL.
 * - \`ver:<brandKey>:<cutId>\` -> generated logo-versions asset
 * - existing app file stem (e.g. \`fushi_black\`) -> LOGO_FILES from core/brands.ts
 */
export function logoVersionAssetUrl(assetKey: string): string | null {
  if (assetKey.startsWith('ver:')) return VERSION_URLS[assetKey] ?? null
  return LOGO_FILES[assetKey] ?? null
}

export function hasLogoVersionAsset(assetKey: string): boolean {
  return assetKey.startsWith('ver:') ? Boolean(VERSION_URLS[assetKey]) : Boolean(LOGO_FILES[assetKey])
}
`
fs.writeFileSync(path.join(CORE, 'logo-version-assets.ts'), resolver, 'utf8')

// ---------------------------------------------------------------------------
// 7) Audit
// ---------------------------------------------------------------------------
fs.mkdirSync(AUDIT_DIR, { recursive: true })

const countBy = (arr, fn) => {
  const out = {}
  for (const item of arr) {
    const k = fn(item)
    out[k] = (out[k] || 0) + 1
  }
  return out
}

const cutList = versions.flatMap((v) => v.cuts.map((c) => ({ version: v, cut: c })))
const metricsForCut = (v, c) => metricsByCutKey.get(`${v.brandKey}/${c.id}`)
const ambiguous = cutList
  .filter(({ cut }) => cut.ink === 'ambiguous' || cut.surface === 'none')
  .map(({ version, cut }) => ({
    brandKey: version.brandKey,
    versionId: version.id,
    cutId: cut.id,
    ink: cut.ink,
    surface: cut.surface,
    confidence: cut.confidence,
    file: cut.file,
    assetKey: cut.assetKey,
    metrics: metricsForCut(version, cut)
  }))
const lowConfidence = cutList
  .filter(({ cut }) => cut.confidence === 'low' && cut.ink !== 'ambiguous' && cut.surface !== 'none')
  .map(({ version, cut }) => ({ brandKey: version.brandKey, versionId: version.id, cutId: cut.id }))
const unresolved = publicCandidates.filter((c) => !c.mappedVersionId)
const gapResolved = versions
  .filter((v) => brandByKey.get(v.brandKey) && catalog.brands.find((b) => b.key === v.brandKey)?.versions.some((cv) => cv.id === v.id && cv.status === 'GAP'))
  .map((v) => ({ id: v.id, status: v.status, cuts: v.cuts.length }))
const fujiVersions = versions.filter((v) => v.brandKey === 'fushi')
const hasuVersions = versions.filter((v) => v.brandKey === 'hasu')

const audit = {
  generatedAt: new Date().toISOString(),
  source: {
    catalog: CATALOG_JSON.replace(/\\/g, '/'),
    renderIndex: RENDER_JSON.replace(/\\/g, '/'),
    extras: EXTRAS_JSON.replace(/\\/g, '/'),
    curation: CURATION_JSON.replace(/\\/g, '/'),
    appLogos: APP_LOGOS.replace(/\\/g, '/')
  },
  totals: {
    brands: publicBrands.length,
    catalogBrands: catalog.brands.length,
    versions: versions.length,
    versionsWithCuts: versions.filter((v) => v.cuts.length > 0).length,
    cuts: cutList.length,
    candidates: publicCandidates.length,
    renderCandidates: renders.length,
    extras: extras.length,
    appLogoFiles: appFileNames.length,
    emittedAssets: emitted.length
  },
  counts: {
    status: countBy(versions, (v) => v.status),
    era: countBy(versions, (v) => v.era),
    surface: countBy(versions, (v) => v.surface),
    confidence: countBy(versions, (v) => v.confidence),
    ink: countBy(cutList, ({ cut }) => cut.ink),
    cutSurface: countBy(cutList, ({ cut }) => cut.surface),
    candidateDisposition: countBy(publicCandidates, (c) => c.disposition)
  },
  gaps: { catalog: catalog.counts?.gaps ?? 2, resolved: gapResolved },
  assets: {
    emitted,
    totalBytes: emittedBytes,
    maxBytes: maxEmittedBytes,
    maxFileBudgetBytes: MAX_FILE_BYTES,
    totalBudgetBytes: MAX_TOTAL_BYTES,
    existingLogoBytes: appFileNames.reduce((n, f) => n + fs.statSync(path.join(APP_LOGOS, f)).size, 0)
  },
  brands: publicBrands.map((b) => {
    const bv = versions.filter((v) => v.brandKey === b.key)
    return {
      ...b,
      versionCount: bv.length,
      cutCount: bv.reduce((n, v) => n + v.cuts.length, 0),
      versions: bv.map(publicVersion)
    }
  }),
  versions: publicVersions.map((v) => ({
    ...v,
    cuts: v.cuts.map((c) => ({
      ...c,
      metrics: metricsByCutKey.get(`${v.brandKey}/${c.id}`)
    }))
  })),
  candidates: publicCandidates.map((c) => {
    const src = candidates.find((x) => x.path === c.path)
    return {
      ...c,
      origin: src?.origin,
      isApp: src?.isApp,
      assigned: src?.assigned,
      ink: src?.inkClass,
      surface: src?.surface
    }
  }),
  fujifilm: fujiVersions.map(publicVersion),
  hasselblad: hasuVersions.map(publicVersion),
  issues: {
    ambiguousOrNone: ambiguous,
    lowConfidenceCuts: lowConfidence,
    unresolvedCandidates: unresolved,
    zeroCutVersions: versions.filter((v) => v.cuts.length === 0).map((v) => v.id),
    missingAppMappings: appFileNames
      .map((f) => f.replace(/\.webp$/, ''))
      .filter((stem) => !publicCandidates.some((c) => c.path.endsWith(`/${stem}.webp`)))
  }
}

fs.writeFileSync(path.join(AUDIT_DIR, 'logo-catalog-audit.json'), JSON.stringify(audit, null, 2), 'utf8')

function cutRow(v, c) {
  const url = c.assetKey.startsWith('ver:')
    ? `<img src="../../../framemark/src/renderer/src/assets/logo-versions/${v.brandKey}/${c.id}.webp" />`
    : `<img src="../../../framemark/src/renderer/src/assets/logos/${c.assetKey}.webp" />`
  return `| ${v.id} | ${v.name} | ${v.era} | ${v.years} | ${v.status} | ${v.surface} | ${c.id} | ${c.ink} | ${c.surface} | ${c.assetKey.startsWith('ver:') ? 'NEW' : 'LOCKED'} | \`${c.file}\` |`
}

const md = `# Logo catalog audit (Phase 1a)

Generated by \`scripts/logo-pipeline/build-catalog.mjs\` at ${audit.generatedAt}.

## Totals

- Brands: **${audit.totals.brands}** (${audit.totals.catalogBrands} catalog + 1 app-only LUMO)
- Versions: **${audit.totals.versions}** (${audit.totals.versionsWithCuts} with cuts)
- Cuts: **${audit.totals.cuts}**
- Candidates: **${audit.totals.candidates}** (render ${audit.totals.renderCandidates} + app logo files ${audit.totals.appLogoFiles})
- Extras folded into candidates: **${audit.totals.extras}**
- New 512px WebP assets: **${audit.totals.emittedAssets}** (${(emittedBytes / 1024 / 1024).toFixed(2)} MB)

## Counts

### Versions by status
${Object.entries(audit.counts.status).map(([k, n]) => `- ${k}: ${n}`).join('\n')}

### Versions by era
${Object.entries(audit.counts.era).map(([k, n]) => `- ${k}: ${n}`).join('\n')}

### Versions by surface
${Object.entries(audit.counts.surface).map(([k, n]) => `- ${k}: ${n}`).join('\n')}

### Versions by confidence
${Object.entries(audit.counts.confidence).map(([k, n]) => `- ${k}: ${n}`).join('\n')}

### Cuts by ink / surface
${Object.entries(audit.counts.ink).map(([k, n]) => `- ink ${k}: ${n}`).join('\n')}
${Object.entries(audit.counts.cutSurface).map(([k, n]) => `- surface ${k}: ${n}`).join('\n')}

### Candidates by disposition
${Object.entries(audit.counts.candidateDisposition).map(([k, n]) => `- ${k}: ${n}`).join('\n')}

## GAP resolution

Catalog gaps: ${audit.gaps.catalog}. Resolved using existing app/candidate assets:
${gapResolved.length ? gapResolved.map((g) => `- ${g.id}: ${g.status}, ${g.cuts} cut(s)`).join('\n') : '- none'}

## Brands

| Brand | Key | Group | Lifecycle | Versions | Cuts |
|---|---|---|---|---|---|
${audit.brands.map((b) => `| ${b.label} | ${b.key} | ${b.group} | ${b.lifecycle} | ${b.versionCount} | ${b.cutCount} |`).join('\n')}

## Fujifilm (fushi)

${fujiVersions.length} versions / ${fujiVersions.reduce((n, v) => n + v.cuts.length, 0)} cuts.

| Version | Name | Era | Years | Status | Surface | Cut | Ink | Cut surface | Asset | File |
|---|---|---|---|---|---|---|---|---|---|---|
${fujiVersions.flatMap((v) => v.cuts.map((c) => cutRow(v, c))).join('\n')}

## Hasselblad (hasu)

${hasuVersions.length} versions / ${hasuVersions.reduce((n, v) => n + v.cuts.length, 0)} cuts.

| Version | Name | Era | Years | Status | Surface | Cut | Ink | Cut surface | Asset | File |
|---|---|---|---|---|---|---|---|---|---|---|
${hasuVersions.flatMap((v) => v.cuts.map((c) => cutRow(v, c))).join('\n')}

## Cut classification for manual review

Cuts with \`ink=ambiguous\` or \`surface=none\`: ${ambiguous.length}

${ambiguous.length ? ambiguous.map((a) => `- \`${a.brandKey}/${a.cutId}\` — ink ${a.ink}, surface ${a.surface}, conf ${a.confidence}, file \`${a.file}\``).join('\n') : '- none'}

Additional low-confidence cuts (all uncurated CANDIDATE cuts are low confidence by design): ${lowConfidence.length}. See \`issues.lowConfidenceCuts\` in the JSON audit.

## Unresolved candidate mappings

${unresolved.length ? unresolved.map((c) => `- \`${c.path}\` (${c.brandKey}) — ${c.disposition}${c.note ? `: ${c.note}` : ''}`).join('\n') : '- none'}

## Candidate dispositions

| Candidate | Brand | Disposition | Version | Cut | Note |
|---|---|---|---|---|---|
${publicCandidates.map((c) => `| \`${c.path}\`${c.previewPath ? `<br>${c.previewPath}` : ''} | ${c.brandKey} | ${c.disposition} | ${c.mappedVersionId || '—'} | ${c.mappedCutId || '—'} | ${c.note || ''} |`).join('\n')}

## Asset budget

- New assets: ${emitted.length}, total **${(emittedBytes / 1024).toFixed(1)} KB** of ${(MAX_TOTAL_BYTES / 1024 / 1024).toFixed(0)} MB.
- Largest new asset: **${(maxEmittedBytes / 1024).toFixed(1)} KB** (limit ${MAX_FILE_BYTES / 1024} KB).
- Existing app baseline: ${(audit.assets.existingLogoBytes / 1024 / 1024).toFixed(2)} MB across ${appFileNames.length} files (untouched, referenced in place).
`
fs.writeFileSync(path.join(AUDIT_DIR, 'AUDIT.md'), md, 'utf8')

// review.html
function esc(s) {
  return String(s).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]))
}
function imgFor(v, c) {
  if (c.assetKey.startsWith('ver:')) {
    return `../../../framemark/src/renderer/src/assets/logo-versions/${v.brandKey}/${c.id}.webp`
  }
  return `../../../framemark/src/renderer/src/assets/logos/${c.assetKey}.webp`
}
const htmlCards = brandDefs
  .map((b) => {
    const bv = versions.filter((v) => v.brandKey === b.key)
    if (!bv.length) return ''
    return `<section class="brand"><h2>${esc(b.label)} <small>${esc(b.key)}</small></h2><div class="grid">${bv
      .map((v) => {
        const cuts = v.cuts
          .map((c) => {
            const locked = !c.assetKey.startsWith('ver:')
            const bg = c.previewSurface
            return `<figure class="shot ${bg}">
              <div class="imgwrap"><img loading="lazy" src="${imgFor(v, c)}" alt="${esc(c.id)}"></div>
              <figcaption><b>${esc(c.id)}</b> · ink ${esc(c.ink)} · surface ${esc(c.surface)}${locked ? ' · <span class="lock">LOCKED</span>' : ''}<br><code>${esc(c.file)}</code></figcaption>
            </figure>`
          })
          .join('')
        return `<article class="card">
          <header>
            <h3>${esc(v.name)}</h3>
            <div class="meta">${esc(v.id)} · ${esc(v.era)} · ${esc(v.years)} · ${esc(v.status)} · surface ${esc(v.surface)} · primary ${esc(v.primaryCut)}</div>
          </header>
          <div class="cuts">${cuts}</div>
        </article>`
      })
      .join('')}</div></section>`
  })
  .join('')

const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>FrameMark logo catalog review</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 24px 28px 60px; font: 13px/1.45 "Segoe UI", system-ui, sans-serif; background: #f6f5f2; color: #17181a; }
  h1 { font-size: 22px; margin: 0 0 6px; }
  .lede { color: #5d6067; margin-bottom: 22px; }
  .stats { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 26px; }
  .stat { background: #fff; border: 1px solid #dddcd6; border-radius: 8px; padding: 8px 12px; }
  .stat b { display: block; font-size: 17px; }
  section.brand { margin: 0 0 30px; }
  section.brand h2 { font-size: 16px; margin: 0 0 10px; border-bottom: 1px solid #d9d7d0; padding-bottom: 6px; }
  section.brand h2 small { font-weight: 400; color: #8a8d93; margin-left: 8px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
  .card { background: #fff; border: 1px solid #dddcd6; border-radius: 10px; overflow: hidden; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
  .card header { padding: 10px 12px 8px; border-bottom: 1px solid #eeece7; }
  .card h3 { margin: 0; font-size: 13px; }
  .meta { color: #777b83; font-size: 11px; margin-top: 4px; }
  .cuts { display: grid; }
  .shot { margin: 0; border-top: 1px solid #eeece7; }
  .shot:first-child { border-top: 0; }
  .imgwrap { height: 122px; display: flex; align-items: center; justify-content: center; padding: 12px; }
  .imgwrap img { max-width: 100%; max-height: 100%; object-fit: contain; }
  .light .imgwrap { background: #f4f3ef; }
  .dark .imgwrap { background: #141517; }
  figcaption { padding: 7px 12px 9px; font-size: 10.5px; color: #6d7178; border-top: 1px solid rgba(0,0,0,.05); }
  figcaption code { font-size: 10px; color: #8c9099; }
  .lock { color: #9a5b00; font-weight: 700; letter-spacing: .04em; }
  .note { margin: 0 0 20px; padding: 10px 12px; background: #fff8e8; border: 1px solid #ecd9a8; border-radius: 8px; color: #5a4a20; }
</style></head>
<body>
<h1>FrameMark logo catalog review</h1>
<div class="lede">Phase 1a visual review. Every card shows all tone cuts on each cut's required preview background. <b>LOCKED</b> means the existing app asset is referenced in place and was not re-encoded.</div>
<div class="stats">
  <div class="stat"><b>${publicBrands.length}</b>brands</div>
  <div class="stat"><b>${versions.length}</b>versions</div>
  <div class="stat"><b>${cutList.length}</b>cuts</div>
  <div class="stat"><b>${publicCandidates.length}</b>candidates</div>
  <div class="stat"><b>${emitted.length}</b>new assets</div>
</div>
<div class="note">Generated ${esc(audit.generatedAt)} · assets are referenced relatively from _research/logo-versions/_audit/.</div>
${htmlCards}
</body></html>
`
fs.writeFileSync(path.join(AUDIT_DIR, 'review.html'), html, 'utf8')

// ---------------------------------------------------------------------------
// 8) Summary
// ---------------------------------------------------------------------------
console.log('Logo catalog built')
console.log(`  brands:   ${publicBrands.length}`)
console.log(`  versions: ${versions.length} (${versions.filter((v) => v.cuts.length).length} with cuts)`)
console.log(`  cuts:     ${cutList.length}`)
console.log(`  candidates: ${publicCandidates.length}`)
console.log(`  emitted:  ${emitted.length} files, ${(emittedBytes / 1024 / 1024).toFixed(2)} MB, max ${(maxEmittedBytes / 1024).toFixed(1)} KB`)
console.log(`  counts:   ${JSON.stringify(audit.counts.status)}`)
console.log(`  fushi:    ${fujiVersions.length} versions / ${fujiVersions.reduce((n, v) => n + v.cuts.length, 0)} cuts`)
console.log(`  hasu:     ${hasuVersions.length} versions / ${hasuVersions.reduce((n, v) => n + v.cuts.length, 0)} cuts`)
console.log(`  ambiguous/none: ${ambiguous.length}`)
console.log(`  unresolved: ${unresolved.length}`)
