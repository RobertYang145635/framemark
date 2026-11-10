// Normalized geometry diff between the 47 reference templates and our exports.
//
// Inputs
//   --manifest <path>  default: D:/border&watermark/_research/original-47/manifest.json
//   --ours <dir>       default: D:/border&watermark/_research/original-47/exports/4x3
//   --rects <path>     default: <ours>/photo-rects.json (written by audit-original47.cjs)
//   --out <path>       default: D:/border&watermark/_research/original-47/geometry-diff.json
//   --threshold <n>    default: 2 (% of photo short side S)
//
// Rules
//   * every margin / bbox / slot delta is normalized by the photo short edge S;
//   * only rows with a measured reference photo rect AND a measured export rect
//     can pass/fail; rows without one are reported as "unmeasured";
//   * if the manifest is absent the historical 13-template table is used and the
//     run is labelled "manifest missing, 13-row fallback";
//   * historical export dirs (_research/p0-4x3, p0-white2-fix, p0-person2-fix)
//     are never read; exports always come from the fresh <ours> directory;
//   * an existing report is never overwritten: the new report gets a timestamp
//     suffix instead.
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const project = path.resolve(__dirname, '..', '..')
const workspace = path.resolve(project, '..')
const previewRoot = path.join(project, 'legacy-web/src/renderer/src/assets/previews')
const originalDir = path.join(workspace, '_research/original-47')
const staleReport = path.join(workspace, '_research/preview-analysis/geometry-diff.json')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  if (i >= 0 && process.argv[i + 1]) return process.argv[i + 1]
  const kv = process.argv.find((a) => a.startsWith(`--${name}=`))
  if (kv) return kv.slice(name.length + 3)
  return fallback
}

const manifestArg = arg('manifest', path.join(originalDir, 'manifest.json'))
const manifestPath = path.resolve(manifestArg)
const oursDir = path.resolve(arg('ours', path.join(originalDir, 'exports/4x3')))
const rectsPath = path.resolve(arg('rects', path.join(oursDir, 'photo-rects.json')))
const outArg = path.resolve(arg('out', path.join(originalDir, 'geometry-diff.json')))
const threshold = Number(arg('threshold', '2'))

// ---------------------------------------------------------------------------
// Historical 13-template reference table (used only when the manifest is absent).
// Reference rects are in reference-preview pixels.
// ---------------------------------------------------------------------------
const FALLBACK_13 = [
  { id: 'white-1', file: 'whiteborder1.webp', photo: [31, 25, 962, 721] },
  { id: 'white-2', file: 'whiteborder2.webp', photo: [31, 24, 962, 723] },
  { id: 'white-3', file: 'whiteborder3.webp', photo: [31, 25, 963, 722] },
  { id: 'white-4', file: 'whiteborder4.webp', photo: [16, 13, 992, 746] },
  { id: 'card-1', file: 'card1.webp', photo: [10, 10, 1180, 886] },
  { id: 'card-2', file: 'card2.webp', photo: [9, 8, 1006, 784] },
  { id: 'card-3', file: 'card3.webp', photo: [9, 8, 1006, 793] },
  { id: 'card-4', file: 'card4.webp', photo: [11, 12, 1002, 780] },
  { id: 'person-1', file: 'person1.webp', photo: [30, 84, 965, 726] },
  { id: 'person-2', file: 'person2.webp', photo: [41, 104, 1116, 837] },
  { id: 'person-3', file: 'person3.webp', photo: [0, 0, 1024, 758] },
  { id: 'person-4', file: 'person4.webp', photo: [0, 0, 1024, 782] },
  { id: 'person-5', file: 'person5.webp', photo: [22, 21, 980, 737] }
]

// Slot probes inside / around the photo rect. Same table as the historical
// 13-template run; slots are only measured for these ids.
const JOBS = {
  'white-1': [{ label: 'brand', area: 'photo', rel: [0.3, 0.88, 0.7, 0.995], pred: 'bright' }],
  'white-2': [
    { label: 'brand', area: 'photo', rel: [0.44, 0.885, 0.56, 0.925], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.35, 0.936, 0.65, 0.972], pred: 'brightText' }
  ],
  'white-3': [{ label: 'exif', area: 'photo', rel: [0.2, 0.92, 0.8, 0.995], pred: 'brightText' }],
  'white-4': [],
  'card-1': [{ label: 'content', area: 'footer', pred: 'dark' }],
  'card-2': [{ label: 'content', area: 'footer', pred: 'dark' }],
  'card-3': [{ label: 'content', area: 'footer', pred: 'dark' }],
  'card-4': [{ label: 'content', area: 'footer', pred: 'dark' }],
  'person-1': [
    { label: 'header', area: 'header', pred: 'dark' },
    { label: 'brand', area: 'footer', pred: 'dark' }
  ],
  'person-2': [
    { label: 'header', area: 'header', pred: 'dark' },
    { label: 'exif', area: 'photo', rel: [0.2, 0.93, 0.8, 0.995], pred: 'brightText' },
    { label: 'brand', area: 'footer', pred: 'dark' }
  ],
  'person-3': [
    { label: 'chip', area: 'photo', rel: [0.3, 0.02, 0.7, 0.15], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.2, 0.92, 0.8, 0.995], pred: 'brightText' },
    { label: 'brand', area: 'footer', pred: 'dark' }
  ],
  'person-4': [
    { label: 'chip', area: 'photo', rel: [0.3, 0.02, 0.7, 0.15], pred: 'brightText' },
    { label: 'brand', area: 'photo', rel: [0.2, 0.82, 0.8, 0.9], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.2, 0.91, 0.8, 0.995], pred: 'brightText' }
  ],
  'person-5': [{ label: 'footer', area: 'footer', pred: 'dark' }]
}

function normRect(input) {
  if (!input || input === 'unmeasured') return null
  if (Array.isArray(input) && input.length >= 4) {
    const [x, y, w, h] = input.map(Number)
    return [x, y, w, h].every(Number.isFinite) ? { x, y, w, h } : null
  }
  if (typeof input === 'object') {
    const x = Number(input.x ?? input.left)
    const y = Number(input.y ?? input.top)
    const w = Number(input.w ?? input.width)
    const h = Number(input.h ?? input.height)
    return [x, y, w, h].every(Number.isFinite) ? { x, y, w, h } : null
  }
  return null
}

function normSize(input) {
  if (!input) return null
  if (Array.isArray(input) && input.length >= 2) {
    const [w, h] = input.map(Number)
    return w > 0 && h > 0 ? [w, h] : null
  }
  if (typeof input === 'object') {
    const w = Number(input.w ?? input.width)
    const h = Number(input.h ?? input.height)
    return w > 0 && h > 0 ? [w, h] : null
  }
  return null
}

function loadManifest(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'))
  const rows = Array.isArray(raw) ? raw : raw.rows || raw.items || raw.templates || []
  if (!Array.isArray(rows)) throw new Error(`cannot find rows[] in ${file}`)
  return rows.map((r, i) => ({
    index: i + 1,
    variantId: r.variantId || r.id || r.variant || null,
    refFile: r.refFile || r.ref || r.reference || null,
    refPath: r.refPath || null,
    refCanvas: normSize(r.refCanvas || r.refSize),
    renderedPreview: r.renderedPreview || r.preview || null,
    photoRect: normRect(r.photoRect || r.photoRectRef || r.refPhotoRect),
    category: r.category || r.group || null
  }))
}

function fallbackRows() {
  return FALLBACK_13.map((r, i) => ({
    index: i + 1,
    variantId: r.id,
    refFile: r.file,
    refPath: path.join(previewRoot, r.file),
    refCanvas: null,
    renderedPreview: null,
    photoRect: normRect(r.photo),
    category: 'fallback-13'
  }))
}

function resolveRef(refFile, refPath) {
  const candidates = []
  if (refPath) candidates.push(refPath)
  if (refFile) {
    if (path.isAbsolute(refFile)) candidates.push(refFile)
    candidates.push(
      path.join(workspace, refFile),
      path.join(path.dirname(manifestPath), refFile),
      path.join(path.dirname(manifestPath), 'refs', refFile),
      path.join(previewRoot, refFile),
      path.join(previewRoot, path.basename(refFile))
    )
  }
  for (const candidate of candidates) {
    if (candidate && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return path.resolve(candidate)
  }
  return null
}

function loadRects(file) {
  if (!fs.existsSync(file)) return {}
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'))
  const map = raw.rects || raw.rows || raw
  if (Array.isArray(map)) {
    return Object.fromEntries(map.filter((r) => r && (r.variantId || r.id)).map((r) => [r.variantId || r.id, r]))
  }
  return map && typeof map === 'object' ? map : {}
}

// ---------------------------------------------------------------------------
// Pixel probing for slots (same predicates as the historical run).
// ---------------------------------------------------------------------------
const lum = (r, g, b) => 0.299 * r + 0.7 * g + 0.114 * b
const dark = (r, g, b, a) => a > 60 && lum(r, g, b) < 150
const bright = (r, g, b, a) => a > 60 && Math.min(r, g, b) > 175
const PREDS = { dark, bright }

const imageCache = new Map()
async function loadRaw(file) {
  if (imageCache.has(file)) return imageCache.get(file)
  const { data, info } = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const img = { data, w: info.width, h: info.height, ch: info.channels }
  imageCache.set(file, img)
  return img
}
const px = (img, x, y) => {
  const i = (y * img.w + x) * img.ch
  return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}
function bboxBrightText(img, x0, y0, x1, y1) {
  x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0))
  x1 = Math.min(img.w - 1, Math.ceil(x1)); y1 = Math.min(img.h - 1, Math.ceil(y1))
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    const p = px(img, x, y)
    if (!(p[3] > 60 && Math.min(p[0], p[1], p[2]) > 190)) continue
    let darkNear = false
    for (let dy = -4; dy <= 4 && !darkNear; dy++) for (let dx = -4; dx <= 4; dx++) {
      const xx = x + dx, yy = y + dy
      if (xx < 0 || yy < 0 || xx >= img.w || yy >= img.h) continue
      const q = px(img, xx, yy)
      if (q[3] > 60 && lum(q[0], q[1], q[2]) < 110) { darkNear = true; break }
    }
    if (!darkNear) continue
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}
function bbox(img, x0, y0, x1, y1, pred) {
  x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0))
  x1 = Math.min(img.w - 1, Math.ceil(x1)); y1 = Math.min(img.h - 1, Math.ceil(y1))
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    if (pred(...px(img, x, y))) {
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

function roiPixels(img, photo, job) {
  const [px0, py0, pw, ph] = photo
  if (job.area === 'footer') return [0, py0 + ph + 1, img.w - 1, img.h - 1]
  if (job.area === 'header') return [0, 0, img.w - 1, py0 - 2]
  const [x0, y0, x1, y1] = job.rel
  return [px0 + x0 * pw, py0 + y0 * ph, px0 + x1 * pw, py0 + y1 * ph]
}

function slotMetrics(bboxRect, photo, S) {
  if (!bboxRect) return null
  return {
    wPctS: round((bboxRect.w / S) * 100),
    hPctS: round((bboxRect.h / S) * 100),
    cxPctS: round(((bboxRect.x + bboxRect.w / 2 - (photo[0] + photo[2] / 2)) / S) * 100),
    topGapPctS: round(((bboxRect.y - (photo[1] + photo[3])) / S) * 100),
    bottomGapPctS: round(((photo[1] + photo[3] - (bboxRect.y + bboxRect.h)) / S) * 100)
  }
}

// ---------------------------------------------------------------------------
function marginsPct(rect, canvas) {
  const S = Math.min(rect.w, rect.h)
  return {
    L: ((rect.x / S) * 100),
    T: ((rect.y / S) * 100),
    R: (((canvas[0] - rect.x - rect.w) / S) * 100),
    B: (((canvas[1] - rect.y - rect.h) / S) * 100),
    S
  }
}

function bboxPct(rect, canvas) {
  const S = Math.min(rect.w, rect.h)
  return { x: (rect.x / S) * 100, y: (rect.y / S) * 100, w: (rect.w / S) * 100, h: (rect.h / S) * 100, S }
}

async function imageSize(file) {
  try {
    const meta = await sharp(file).metadata()
    return [meta.width, meta.height]
  } catch {
    return null
  }
}

function round(n, digits = 3) {
  return Number.isFinite(n) ? Number(n.toFixed(digits)) : null
}

function roundObj(obj) {
  const out = {}
  for (const [k, v] of Object.entries(obj)) out[k] = typeof v === 'number' ? round(v) : v
  return out
}

function timestampSuffix() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function pickOutPath(requested) {
  if (requested === staleReport) throw new Error('refusing to overwrite _research/preview-analysis/geometry-diff.json')
  if (!fs.existsSync(requested)) return requested
  const parsed = path.parse(requested)
  return path.join(parsed.dir, `${parsed.name}-${timestampSuffix()}${parsed.ext}`)
}

async function measureSlots(variantId, refFile, refPhoto, refCanvas, oursFile, oursPhoto, oursCanvas) {
  const jobs = JOBS[variantId]
  if (!jobs || !jobs.length) return null
  const [refImg, oursImg] = await Promise.all([loadRaw(refFile), loadRaw(oursFile)])
  const rp = [refPhoto.x, refPhoto.y, refPhoto.w, refPhoto.h]
  const op = [oursPhoto.x, oursPhoto.y, oursPhoto.w, oursPhoto.h]
  const rS = Math.min(rp[2], rp[3])
  const oS = Math.min(op[2], op[3])
  const slots = {}
  let maxAbs = 0
  for (const job of jobs) {
    const rr = roiPixels(refImg, rp, job)
    const oo = roiPixels(oursImg, op, job)
    const predFn = PREDS[job.pred] || job.pred
    const rb = job.pred === 'brightText' ? bboxBrightText(refImg, ...rr) : bbox(refImg, ...rr, predFn)
    const ob = job.pred === 'brightText' ? bboxBrightText(oursImg, ...oo) : bbox(oursImg, ...oo, predFn)
    const refM = slotMetrics(rb, rp, rS)
    const oursM = slotMetrics(ob, op, oS)
    if (!refM || !oursM) {
      slots[job.label] = { ref: refM, ours: oursM, status: 'unmeasured' }
      continue
    }
    const delta = {
      w: refM.wPctS - oursM.wPctS,
      h: refM.hPctS - oursM.hPctS,
      cx: refM.cxPctS - oursM.cxPctS,
      top: refM.topGapPctS - oursM.topGapPctS,
      bottom: refM.bottomGapPctS - oursM.bottomGapPctS
    }
    const slotMax = Math.max(...Object.values(delta).map((v) => Math.abs(v)))
    maxAbs = Math.max(maxAbs, slotMax)
    slots[job.label] = { ref: refM, ours: oursM, deltaPctS: roundObj(delta), maxAbsPctS: round(slotMax) }
  }
  return { slots, maxAbsPctS: round(maxAbs) }
}

async function main() {
  if (!Number.isFinite(threshold) || threshold <= 0) throw new Error(`invalid threshold: ${threshold}`)

  const manifestExists = fs.existsSync(manifestPath)
  const mode = manifestExists ? 'manifest' : 'fallback-13'
  const manifest = manifestExists ? loadManifest(manifestPath) : fallbackRows()
  if (!manifestExists) console.log('manifest missing, 13-row fallback')

  const rects = loadRects(rectsPath)
  const outPath = pickOutPath(outArg)
  const rows = []
  const summary = {
    rows: manifest.length,
    measured: 0,
    pass: 0,
    fail: 0,
    unmeasured: 0,
    missing: 0,
    slotMeasured: 0
  }

  for (const item of manifest) {
    const record = {
      index: item.index,
      variantId: item.variantId,
      category: item.category,
      refFile: item.refFile,
      status: 'missing',
      ref: null,
      ours: null,
      deltaPctS: null,
      slots: null
    }
    rows.push(record)

    if (!item.variantId) {
      record.error = 'manifest row has no variantId'
      summary.missing++
      continue
    }
    const refPath = resolveRef(item.refFile, item.refPath)
    const oursFile = path.join(oursDir, `${item.variantId}.png`)
    if (!refPath) {
      record.error = `reference not found: ${item.refFile || '(empty)'}`
      summary.missing++
      continue
    }
    if (!fs.existsSync(oursFile)) {
      record.error = `our export not found: ${oursFile}`
      summary.missing++
      continue
    }
    record.oursFile = oursFile

    const refCanvas = item.refCanvas || (await imageSize(refPath))
    const oursEntry = rects[item.variantId] || null
    const oursPhoto = normRect(oursEntry && (oursEntry.photoRect || oursEntry.photo))
    const oursCanvas = normSize(oursEntry && [oursEntry.W ?? oursEntry.canvasW, oursEntry.H ?? oursEntry.canvasH]) || (await imageSize(oursFile))
    record.refCanvas = refCanvas
    record.oursCanvas = oursCanvas
    record.ref = item.photoRect ? { photo: [item.photoRect.x, item.photoRect.y, item.photoRect.w, item.photoRect.h] } : null

    if (!item.photoRect || !oursPhoto || !refCanvas || !oursCanvas) {
      record.status = 'unmeasured'
      record.reason = !item.photoRect
        ? 'reference photo rect not measured'
        : !oursPhoto
          ? `missing our photo rect in ${rectsPath}`
          : 'missing canvas size'
      summary.unmeasured++
      continue
    }

    const refMargins = marginsPct(item.photoRect, refCanvas)
    const oursMargins = marginsPct(oursPhoto, oursCanvas)
    const refBbox = bboxPct(item.photoRect, refCanvas)
    const oursBbox = bboxPct(oursPhoto, oursCanvas)
    const marginDelta = Object.fromEntries(['L', 'T', 'R', 'B'].map((k) => [k, oursMargins[k] - refMargins[k]]))
    const bboxDelta = Object.fromEntries(['x', 'y', 'w', 'h'].map((k) => [k, oursBbox[k] - refBbox[k]]))
    const maxAbs = (delta) => Math.max(...Object.values(delta).map((v) => Math.abs(v)))
    const maxAbsMargin = maxAbs(marginDelta)
    const maxAbsBbox = maxAbs(bboxDelta)

    const slotResult = await measureSlots(
      item.variantId,
      refPath,
      item.photoRect,
      refCanvas,
      oursFile,
      oursPhoto,
      oursCanvas
    )
    if (slotResult) {
      record.slots = slotResult.slots
      summary.slotMeasured++
    }
    const maxAbsSlot = slotResult ? slotResult.maxAbsPctS : 0
    const pass = maxAbsMargin <= threshold && maxAbsBbox <= threshold && maxAbsSlot <= threshold

    record.status = pass ? 'pass' : 'fail'
    record.ref = {
      photo: [item.photoRect.x, item.photoRect.y, item.photoRect.w, item.photoRect.h],
      marginsPctS: roundObj(refMargins),
      bboxPctS: roundObj(refBbox)
    }
    record.ours = {
      photo: [oursPhoto.x, oursPhoto.y, oursPhoto.w, oursPhoto.h],
      marginsPctS: roundObj(oursMargins),
      bboxPctS: roundObj(oursBbox)
    }
    record.deltaPctS = {
      ...roundObj(marginDelta),
      bboxX: round(bboxDelta.x),
      bboxY: round(bboxDelta.y),
      bboxW: round(bboxDelta.w),
      bboxH: round(bboxDelta.h),
      maxAbsMargin: round(maxAbsMargin),
      maxAbsBbox: round(maxAbsBbox),
      maxAbsSlot: round(maxAbsSlot)
    }
    record.thresholdPctS = threshold
    summary.measured++
    if (pass) summary.pass++
    else summary.fail++
  }

  const report = {
    generatedAt: new Date().toISOString(),
    mode,
    manifest: manifestExists ? manifestPath : null,
    fallback: manifestExists ? null : '13-template table (manifest missing)',
    oursDir,
    photoRects: rectsPath,
    thresholdPctS: threshold,
    summary,
    rows
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true })
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2))

  console.log(`rows ${summary.rows} / measured ${summary.measured} / pass ${summary.pass} / fail ${summary.fail}`)
  console.log(`unmeasured ${summary.unmeasured} / missing ${summary.missing} / slots ${summary.slotMeasured} / threshold ${threshold}%S`)
  console.log(`report ${outPath}${outPath !== outArg ? ' (timestamped, previous report preserved)' : ''}`)
  for (const r of rows) {
    if (r.status === 'fail' || r.status === 'missing') {
      const detail = r.deltaPctS
        ? `maxMargin ${r.deltaPctS.maxAbsMargin}%S maxBbox ${r.deltaPctS.maxAbsBbox}%S maxSlot ${r.deltaPctS.maxAbsSlot}%S`
        : r.error
      console.log(`${r.status.toUpperCase()} ${r.variantId} ${detail}`)
    }
  }
  if (summary.fail || summary.missing) process.exitCode = 1
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
