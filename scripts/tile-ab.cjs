/**
 * S5 — tiling A/B verification gate. GATE ONLY: nothing here implements tiling.
 *
 * The later slice (S6) will paint the export canvas in horizontal bands. This
 * harness is the instrument that must prove "banded output == unbanded output"
 * before that code is trusted. It runs one Electron instance at a time with a
 * throw-away `--user-data-dir`, renders real exports at the fixtures' natural
 * size (longEdge = 0 / 原始尺寸, so no downscale can hide a seam), and compares
 * decoded pixels:
 *
 *   A1  FRAMEMARK_TILE unset        (unbanded, first run)
 *   A2  FRAMEMARK_TILE=0            (unbanded, second run; proves non-'1' is off)
 *   B   FRAMEMARK_TILE=1            at band heights 512 / 1024 / 2048
 *
 * A1 vs A2 is the run-to-run noise floor; every B image must sit inside it.
 * Until S6 lands B is byte-identical to A by construction — that is expected.
 * The gate's value is proved separately: two synthetic corruptions of a real
 * grain-card export must make the exact same comparator go red, and a dropped
 * draw op must make the op-stream comparator go red.
 *
 * WHY THE EXPORT PATH (and not only `window.__fmVariants.render`):
 *   `__fmVariants.render` is backed by the built-in 1600x1067 sample and cannot
 *   consume a fixture or exceed the sample's natural canvas, so it cannot
 *   exercise a 50MP render. The real full-size render lives at `exporter.ts:184`
 *   — the exact call site S6 will tile — so the pixel parity matrix drives the
 *   real export dialog at 原始尺寸. The debug API is still exercised (all 12
 *   cards, A1/A2/B) for the op-stream side channel and for a sample-backed
 *   smoke, including the `__fmStock` IR painter.
 *
 * Usage:
 *   node scripts/tile-ab.cjs                 # full gate (see handoff for the matrix)
 *   node scripts/tile-ab.cjs --smoke         # 3 cards x 1 fixture, A1 + red proofs
 *   node scripts/tile-ab.cjs --skip-memory   # parity + red proofs + op stream only
 *   node scripts/tile-ab.cjs --keep          # keep per-pass export PNGs
 *
 * Artifacts (all under D:\border&watermark\_research\s5\):
 *   fixtures/                 reusable sharp-generated source photos
 *   runs/<pass>/<group>/      profile + batch dir + exports/<card>/*.png
 *   noise-floor.json          A1 vs A2 per-image diff + aggregate
 *   comparator-red.txt        the two pixel corruptions + the dropped-op proof
 *   op-stream.json            normalized op-stream cross-check A1/A2/B
 *   memory-baseline.json      50MP peak working set per card
 *   results.json              every gate verdict, matrix actually run, timings
 *   run.log                   timestamped console log
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { createRequire } = require('node:module')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

const require2 = createRequire(__filename)
const ROOT = path.resolve(__dirname, '..')
const WORKSPACE = path.resolve(ROOT, '..')
const S5 = path.join(WORKSPACE, '_research', 's5')
const FIXTURES = path.join(S5, 'fixtures')
const RUNS = path.join(S5, 'runs')
const PROGRESS = path.join(WORKSPACE, '_research', 'progress', 'S5.md')

const argv = process.argv.slice(2)
const hasFlag = (name) => argv.includes(name)
const argValue = (name, dflt) => {
  const hit = argv.find((a) => a.startsWith(`${name}=`))
  return hit ? hit.slice(name.length + 1) : dflt
}

const SMOKE = hasFlag('--smoke')
const SKIP_MEMORY = hasFlag('--skip-memory')
const SKIP_OPS = hasFlag('--skip-ops')
const KEEP = hasFlag('--keep')

/* ------------------------------------------------------------------ logging */

const LOG = []
function log(msg) {
  const line = `${new Date().toISOString().slice(11, 19)} ${msg}`
  LOG.push(line)
  console.log(line)
}
function progress(msg) {
  fs.mkdirSync(path.dirname(PROGRESS), { recursive: true })
  fs.appendFileSync(PROGRESS, `- ${new Date().toISOString().slice(0, 16).replace('T', ' ')} — ${msg}\n`)
}
function fail(msg) {
  throw new Error(msg)
}

/* ----------------------------------------------------------------- fixtures */

/**
 * Six source photos. Deliberately noise + gradient (not flat colour): a 1px
 * band shift and a re-seeded grain phase must both be visible, and blur /
 * grayscale / overlay painters need real high-frequency content to change.
 * JPEG 4:4:4 keeps chroma detail; generation is deterministic per size so the
 * files are reused across runs (delete _research/s5/fixtures to regenerate).
 */
const FIXTURE_SPECS = [
  { key: 'L', name: 'landscape-3000x2000.jpg', w: 3000, h: 2000 },
  { key: 'P', name: 'portrait-2000x3000.jpg', w: 2000, h: 3000 },
  { key: 'S', name: 'square-2400x2400.jpg', w: 2400, h: 2400 },
  { key: 'W', name: 'strip-6000x600.jpg', w: 6000, h: 600 },
  { key: 'T', name: 'tiny-640x427.jpg', w: 640, h: 427 },
  { key: 'XL', name: 'large-8192x6144.jpg', w: 8192, h: 6144 }
]
const FIXTURE_BY_KEY = Object.fromEntries(FIXTURE_SPECS.map((f) => [f.key, f]))

function clampByte(v) {
  const n = Math.round(v)
  return n < 0 ? 0 : n > 255 ? 255 : n
}

async function makeFixture(spec) {
  const file = path.join(FIXTURES, spec.name)
  if (fs.existsSync(file)) {
    const meta = await sharp(file).metadata()
    if (meta.width === spec.w && meta.height === spec.h) return file
  }
  const { w, h } = spec
  const px = Buffer.allocUnsafe(w * h * 3)
  const xr = new Float32Array(w)
  const yr = new Float32Array(h)
  for (let x = 0; x < w; x++) xr[x] = (x * 128) / w + 24 * Math.cos(x / 53)
  for (let y = 0; y < h; y++) yr[y] = 22 * Math.sin(y / 41) + (y * 96) / h
  let s = (0x9e3779b9 ^ (w * 131 + h * 17)) >>> 0
  let i = 0
  for (let y = 0; y < h; y++) {
    const ry = yr[y]
    for (let x = 0; x < w; x++) {
      s ^= s << 13
      s >>>= 0
      s ^= s >>> 17
      s ^= s << 5
      s >>>= 0
      const n = (s & 255) - 128
      px[i] = clampByte(52 + xr[x] + ry + n * 0.38)
      px[i + 1] = clampByte(66 + ((x + y) * 70) / (w + h) - ry * 0.5 + n * 0.34)
      px[i + 2] = clampByte(74 + 96 * (1 - x / w) + n * 0.3)
      i += 3
    }
  }
  await sharp(px, { raw: { width: w, height: h, channels: 3 } })
    .jpeg({ quality: 92, chromaSubsampling: '4:4:4' })
    .toFile(file)
  const size = fs.statSync(file).size
  log(`fixture ${spec.name}: ${w}x${h} ${(size / 1048576).toFixed(1)} MB`)
  return file
}

async function ensureFixtures() {
  fs.mkdirSync(FIXTURES, { recursive: true })
  const files = {}
  for (const spec of FIXTURE_SPECS) files[spec.key] = await makeFixture(spec)
  return files
}

/* -------------------------------------------------------------------- cards */

/**
 * 12 cards. The first five are the painters S6 must not break:
 *   blur-classic  -> draw.ts:264   ctx.filter = blur(...) (drawBlurredBackdrop)
 *   color-reveal  -> templates.ts:595  ctx.filter = grayscale(1)
 *   glass-liquid  -> templates.ts:3211 ctx.filter = blur(...) on a clipped card
 *   glass-palette -> templates.ts:3466 ctx.filter = blur(...) on a clipped card
 *   fm-film       -> draw.ts:609   grain: createPattern + overlay over (0,0,W,H)
 * The other seven spread layout/logo/text/palette/barcode/IR coverage.
 */
const CARDS = [
  { id: 'blur-classic', risk: true, painter: 'draw.ts:264 blur backdrop' },
  { id: 'color-reveal', risk: true, painter: 'templates.ts:595 grayscale' },
  { id: 'glass-liquid', risk: true, painter: 'templates.ts:3211 glass blur' },
  { id: 'glass-palette', risk: true, painter: 'templates.ts:3466 palette blur' },
  { id: 'fm-film', risk: true, painter: 'draw.ts:609 grain overlay' },
  { id: 'wm-left', risk: false, painter: 'brandFooter + logo' },
  { id: 'white-2', risk: false, painter: 'matteOverlay brand+exif' },
  { id: 'person-2', risk: false, painter: 'profileFrame header' },
  { id: 'glass-card', risk: false, painter: 'glassSideCard tint' },
  { id: 'color-2', risk: false, painter: 'paletteCard + extractPalette' },
  { id: 'ticket-1', risk: false, painter: 'sideTicket barcode' },
  { id: 'stock-xiaomi-classic-22', risk: false, painter: 'systemCamera IR (stock)' }
]
const CARD_BY_ID = Object.fromEntries(CARDS.map((c) => [c.id, c]))

/** Sample-backed styles that only exist through the stock-card API. */
const STOCK_SMOKE_IDS = ['oppo-gallery:hassel_style_1', 'xiaomi-classic:22']

/* ------------------------------------------------------------------ matrix */

/**
 * Reduced card x fixture matrix (the task allows reducing combinations, not
 * fixture sizes): every card gets a landscape + a portrait; the five risky
 * painters additionally get square / 10:1 strip / tiny. 39 exports per pass.
 */
const GROUP_A = {
  name: 'A',
  fixtures: ['L', 'P'],
  cards: CARDS.map((c) => c.id)
}
const GROUP_B = {
  name: 'B',
  fixtures: ['S', 'W', 'T'],
  cards: CARDS.filter((c) => c.risk).map((c) => c.id)
}
const GROUPS = [GROUP_A, GROUP_B]

const PASSES = [
  { name: 'A1', tile: false, envTile: undefined, envCanary: undefined, band: 0 },
  { name: 'A2', tile: false, envTile: '0', envCanary: '0', band: 0 },
  { name: 'B512', tile: true, envTile: '1', envCanary: '0', band: 512 },
  { name: 'B1024', tile: true, envTile: '1', envCanary: '0', band: 1024 },
  { name: 'B2048', tile: true, envTile: '1', envCanary: '0', band: 2048 }
]
const PASS_BY_NAME = Object.fromEntries(PASSES.map((p) => [p.name, p]))

/* ------------------------------------------------------------------- utils */

const MB = (kb) => Number((kb / 1024).toFixed(1))
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

function freshDir(dir) {
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(dir, { recursive: true })
}

function envFor(pass, extra = {}) {
  const env = { ...process.env, ...extra }
  delete env.ELECTRON_RUN_AS_NODE
  if (pass.envTile === undefined) delete env.FRAMEMARK_TILE
  else env.FRAMEMARK_TILE = pass.envTile
  if (pass.envCanary === undefined) delete env.FRAMEMARK_SEAM_CANARY
  else env.FRAMEMARK_SEAM_CANARY = pass.envCanary
  if (pass.band > 0) env.FRAMEMARK_BAND = String(pass.band)
  else delete env.FRAMEMARK_BAND
  return env
}

async function readRaw(file) {
  const { data, info } = await sharp(file)
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
  return { data, width: info.width, height: info.height, channels: info.channels }
}

/** Byte/pixel diff between two decoded images. Only called when hashes differ. */
function diffRaw(a, b) {
  if (a.length !== b.length) return { sizeMismatch: true, diffBytes: -1, diffPixels: -1, maxAbs: -1 }
  let diffBytes = 0
  let diffPixels = 0
  let maxAbs = 0
  let sumAbs = 0
  for (let i = 0; i < a.length; i += 3) {
    const d0 = Math.abs(a[i] - b[i])
    const d1 = Math.abs(a[i + 1] - b[i + 1])
    const d2 = Math.abs(a[i + 2] - b[i + 2])
    if (d0 || d1 || d2) {
      diffPixels++
      diffBytes += (d0 ? 1 : 0) + (d1 ? 1 : 0) + (d2 ? 1 : 0)
      sumAbs += d0 + d1 + d2
      const m = d0 > d1 ? (d0 > d2 ? d0 : d2) : d1 > d2 ? d1 : d2
      if (m > maxAbs) maxAbs = m
    }
  }
  return { sizeMismatch: false, diffBytes, diffPixels, maxAbs, sumAbs }
}

async function compareFiles(a, b) {
  if (a.width !== b.width || a.height !== b.height) {
    return { sizeMismatch: true, a: `${a.width}x${a.height}`, b: `${b.width}x${b.height}`, diffBytes: -1, diffPixels: -1, maxAbs: -1, identical: false }
  }
  if (a.sha === b.sha) return { sizeMismatch: false, identical: true, diffBytes: 0, diffPixels: 0, maxAbs: 0, sumAbs: 0 }
  const [ra, rb] = await Promise.all([readRaw(a.file), readRaw(b.file)])
  const d = diffRaw(ra.data, rb.data)
  return { ...d, identical: d.diffBytes === 0 && !d.sizeMismatch }
}

/**
 * THE gate predicate. Both the real B comparisons and the two synthetic
 * corruptions go through this exact function — a red proof that used a
 * different predicate would prove nothing about the gate.
 */
function judge(metrics, noise) {
  if (metrics.sizeMismatch) return { ok: false, why: 'size-mismatch' }
  const overBytes = metrics.diffBytes > noise.diffBytes
  const overMax = metrics.maxAbs > noise.maxAbs
  return {
    ok: !overBytes && !overMax,
    why: overBytes || overMax ? `diffBytes ${metrics.diffBytes}>${noise.diffBytes} / maxAbs ${metrics.maxAbs}>${noise.maxAbs}` : 'within noise floor'
  }
}

/* ------------------------------------------------------------- export pass */

async function assertFlags(page, pass, label) {
  const expected = { tile: Boolean(pass.tile), seamCanary: false }
  const bridge = await page.evaluate(() => (window.framemark ? JSON.parse(JSON.stringify(window.framemark.flags)) : null))
  const debug = await page.evaluate(() => (window.__fmFlags ? window.__fmFlags.get() : null))
  if (!bridge) fail(`${label}: window.framemark is missing (desktop preload did not expose the bridge)`)
  if (JSON.stringify(bridge) !== JSON.stringify(expected)) {
    fail(`${label}: bridge flags ${JSON.stringify(bridge)} != expected ${JSON.stringify(expected)}`)
  }
  if (JSON.stringify(debug) !== JSON.stringify(expected)) {
    fail(`${label}: __fmFlags.get() ${JSON.stringify(debug)} != expected ${JSON.stringify(expected)}`)
  }
  return { bridge, debug, expected }
}

async function setVariant(page, id) {
  await page.evaluate((variantId) => window.__fmHistory.act.setVariant(variantId), id)
  await page.waitForTimeout(120)
}

async function openExportDialog(page) {
  await page.locator('.topbar-actions .btn-primary').click()
  await page.waitForSelector('.modal .export-dialog', { timeout: 30000 })
}

async function dismissExport(page) {
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  await page.waitForFunction(() => !document.querySelector('.export-card'), null, { timeout: 30000 })
}

/**
 * One card = one real batch export of every imported fixture, at 原始尺寸 and
 * PNG (lossless). The files land in a scratch batch dir, then move to
 * exports/<card>/ so the next card can reuse the same outputDir.
 */
async function exportCard(page, card, fixtureKeys, batchDir, keepDir, timeout) {
  freshDir(batchDir)
  ensureDir(keepDir)
  await setVariant(page, card.id)
  const t0 = Date.now()
  await openExportDialog(page)
  const scope = page.locator('.export-dialog label.field', { hasText: '范围' })
  await scope.locator('button', { hasText: '全部' }).click()
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 30000 })
  await page.waitForSelector('.export-card', { timeout: 60000 })
  await page.waitForFunction(
    () => {
      const h = document.querySelector('.export-card h3')
      return h && !h.textContent.includes('正在导出')
    },
    null,
    { timeout }
  )
  const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  const elapsed = Date.now() - t0
  await dismissExport(page)
  if (/失败/.test(status)) fail(`${card.id}: export failed: ${status}`)
  const files = fs.readdirSync(batchDir).filter((f) => /\.(png|jpg|jpeg|webp|tiff)$/i.test(f))
  if (files.length !== fixtureKeys.length) {
    fail(`${card.id}: expected ${fixtureKeys.length} exports, got ${JSON.stringify(files)} — status: ${status}`)
  }
  const out = []
  for (const key of fixtureKeys) {
    const spec = FIXTURE_BY_KEY[key]
    const stem = spec.name.replace(/\.[^.]+$/, '').toLowerCase()
    const hits = files.filter((f) => f.toLowerCase().startsWith(stem))
    if (hits.length !== 1) fail(`${card.id}/${key}: could not map ${hits.length} files to ${stem} in ${JSON.stringify(files)}`)
    const src = path.join(batchDir, hits[0])
    const dst = path.join(keepDir, `${card.id}--${key}.png`)
    fs.renameSync(src, dst)
    const meta = await sharp(dst).metadata()
    const raw = await readRaw(dst)
    out.push({
      card: card.id,
      fixture: key,
      painter: card.painter,
      file: dst,
      width: meta.width,
      height: meta.height,
      channels: raw.channels,
      bytes: fs.statSync(dst).size,
      sha: sha256(raw.data),
      exportMs: Math.round(elapsed / fixtureKeys.length)
    })
  }
  log(`  ${card.id}: ${fixtureKeys.join('+')} -> ${out.map((o) => `${o.width}x${o.height}`).join(', ')} in ${elapsed}ms`)
  return out
}

/**
 * Runs INSIDE the page (Playwright serializes the body; keep it self-contained).
 * Hooks CanvasRenderingContext2D methods and records a normalized op stream:
 *   - every coordinate is transformed to canvas/device space via getTransform();
 *   - `reset({yOffset})` folds a band's origin back in (S6: pass the band's y);
 *   - payloads are excluded: drawImage records source DIMENSIONS only, text
 *     records its string, styles record colour/gradient ids, never image data.
 * The stream is a side channel for "pixels coincidentally match while an op was
 * dropped", not a second pixel judge.
 */
function installOpRecorderInPage() {
  if (window.__fmOps) return
  const proto = CanvasRenderingContext2D.prototype
  const R = {
    on: false,
    yOffset: 0,
    ops: [],
    limit: 30000,
    truncated: false,
    gnext: 1,
    gid: new WeakMap(),
    reset(opts) {
      this.ops = []
      this.on = true
      this.truncated = false
      this.yOffset = (opts && opts.yOffset) || 0
      this.gnext = 1
    },
    push(op) {
      if (this.ops.length >= this.limit) {
        this.truncated = true
        return
      }
      this.ops.push(op)
    },
    stop() {
      this.on = false
    },
    snap() {
      return { count: this.ops.length, truncated: this.truncated, ops: this.ops }
    }
  }
  window.__fmOps = R
  const round = (n) => Math.round(n * 1000) / 1000
  const tp = (ctx, x, y) => {
    const t = ctx.getTransform()
    return [round(t.a * x + t.c * y + t.e), round(t.b * x + t.d * y + t.f)]
  }
  const pt = (ctx, x, y) => {
    const q = tp(ctx, x, y)
    return [q[0], round(q[1] + R.yOffset)]
  }
  const rc = (ctx, x, y, w, h) => {
    const p = tp(ctx, x, y)
    const q = tp(ctx, x + w, y + h)
    return [p[0], round(p[1] + R.yOffset), round(q[0] - p[0]), round(q[1] - p[1])]
  }
  const fmtStyle = (v) => {
    if (typeof v === 'string') return v
    const g = R.gid.get(v)
    if (g) return '[g' + g + ']'
    if (typeof CanvasPattern !== 'undefined' && v instanceof CanvasPattern) return '[pattern]'
    return '[obj]'
  }
  const paint = (ctx) => ({
    f: fmtStyle(ctx.fillStyle),
    s: fmtStyle(ctx.strokeStyle),
    a: round(ctx.globalAlpha),
    c: ctx.globalCompositeOperation,
    flt: ctx.filter,
    font: ctx.font,
    ls: ctx.letterSpacing || ''
  })
  const wrap = (name, rec) => {
    const orig = proto[name]
    if (typeof orig !== 'function') return
    proto[name] = function (...args) {
      if (R.on) {
        try {
          rec(this, args)
        } catch (e) {
          R.push({ op: 'ERR', m: String(e) })
        }
      }
      return orig.apply(this, args)
    }
  }
  wrap('save', () => R.push({ op: 'save' }))
  wrap('restore', () => R.push({ op: 'restore' }))
  wrap('translate', (_c, a) => R.push({ op: 'translate', a: [round(a[0]), round(a[1])] }))
  wrap('rotate', (_c, a) => R.push({ op: 'rotate', a: round(a[0]) }))
  wrap('scale', (_c, a) => R.push({ op: 'scale', a: [round(a[0]), round(a[1])] }))
  wrap('transform', (_c, a) => R.push({ op: 'transform', a: a.map(round) }))
  wrap('setTransform', (_c, a) => R.push({ op: 'setTransform', a: a.map(round) }))
  wrap('resetTransform', () => R.push({ op: 'resetTransform' }))
  wrap('beginPath', () => R.push({ op: 'beginPath' }))
  wrap('closePath', () => R.push({ op: 'closePath' }))
  wrap('moveTo', (c, a) => R.push({ op: 'moveTo', p: pt(c, a[0], a[1]) }))
  wrap('lineTo', (c, a) => R.push({ op: 'lineTo', p: pt(c, a[0], a[1]) }))
  wrap('bezierCurveTo', (c, a) => R.push({ op: 'bezierCurveTo', p: [pt(c, a[0], a[1]), pt(c, a[2], a[3]), pt(c, a[4], a[5])] }))
  wrap('quadraticCurveTo', (c, a) => R.push({ op: 'quadraticCurveTo', p: [pt(c, a[0], a[1]), pt(c, a[2], a[3])] }))
  wrap('rect', (c, a) => R.push({ op: 'rect', r: rc(c, a[0], a[1], a[2], a[3]) }))
  wrap('arc', (c, a) => {
    const t = c.getTransform()
    R.push({ op: 'arc', p: pt(c, a[0], a[1]), r: round(a[2] * Math.hypot(t.a, t.b)), a0: round(a[3]), a1: round(a[4]) })
  })
  wrap('arcTo', (c, a) => {
    const t = c.getTransform()
    R.push({ op: 'arcTo', p: [pt(c, a[0], a[1]), pt(c, a[2], a[3])], r: round(a[4] * Math.hypot(t.a, t.b)) })
  })
  wrap('ellipse', (c, a) => {
    const t = c.getTransform()
    R.push({ op: 'ellipse', p: pt(c, a[0], a[1]), r: [round(a[2] * Math.hypot(t.a, t.b)), round(a[3] * Math.hypot(t.c, t.d))], rot: round(a[4]) })
  })
  wrap('fill', (c, a) => R.push(Object.assign({ op: 'fill', rule: a[0] || 'nonzero' }, paint(c))))
  wrap('stroke', (c) => R.push(Object.assign({ op: 'stroke' }, paint(c))))
  wrap('clip', (c, a) => R.push(Object.assign({ op: 'clip', rule: a[0] || 'nonzero' }, paint(c))))
  wrap('fillRect', (c, a) => R.push(Object.assign({ op: 'fillRect', r: rc(c, a[0], a[1], a[2], a[3]) }, paint(c))))
  wrap('strokeRect', (c, a) => R.push(Object.assign({ op: 'strokeRect', r: rc(c, a[0], a[1], a[2], a[3]) }, paint(c))))
  wrap('clearRect', (c, a) => R.push({ op: 'clearRect', r: rc(c, a[0], a[1], a[2], a[3]) }))
  wrap('fillText', (c, a) => R.push(Object.assign({ op: 'fillText', t: String(a[0]), p: pt(c, a[1], a[2]), mw: a[3] === undefined ? null : round(a[3]) }, paint(c))))
  wrap('strokeText', (c, a) => R.push(Object.assign({ op: 'strokeText', t: String(a[0]), p: pt(c, a[1], a[2]), mw: a[3] === undefined ? null : round(a[3]) }, paint(c))))
  wrap('setLineDash', (_c, a) => R.push({ op: 'setLineDash', a: (a[0] || []).map(round) }))
  wrap('putImageData', (c, a) => R.push({ op: 'putImageData', p: pt(c, a[1], a[2]), d: [a[3] || 0, a[4] || 0] }))
  wrap('drawImage', (c, a) => {
    const src = a[0]
    const sw = src.naturalWidth || src.width || 0
    const sh = src.naturalHeight || src.height || 0
    let r
    let s = null
    if (a.length >= 9) {
      s = [round(a[1]), round(a[2]), round(a[3]), round(a[4])]
      r = rc(c, a[5], a[6], a[7], a[8])
    } else if (a.length >= 5) {
      r = rc(c, a[1], a[2], a[3], a[4])
    } else {
      r = rc(c, a[1], a[2], sw, sh)
    }
    R.push(Object.assign({ op: 'drawImage', src: [sw, sh], s, r }, paint(c)))
  })
  const origPattern = proto.createPattern
  proto.createPattern = function (...a) {
    const p = origPattern.apply(this, a)
    if (R.on) {
      const src = a[0]
      R.push({ op: 'pattern', img: [src.naturalWidth || src.width || 0, src.naturalHeight || src.height || 0], rep: a[1] })
    }
    return p
  }
  const origLG = proto.createLinearGradient
  proto.createLinearGradient = function (...a) {
    const g = origLG.apply(this, a)
    if (R.on) {
      const id = R.gnext++
      R.gid.set(g, id)
      R.push({ op: 'linearGradient', g: id, a: a.map(round) })
    }
    return g
  }
  const origRG = proto.createRadialGradient
  proto.createRadialGradient = function (...a) {
    const g = origRG.apply(this, a)
    if (R.on) {
      const id = R.gnext++
      R.gid.set(g, id)
      R.push({ op: 'radialGradient', g: id, a: a.map(round) })
    }
    return g
  }
  const protoG = CanvasGradient.prototype
  const origStop = protoG.addColorStop
  protoG.addColorStop = function (offset, color) {
    if (R.on) {
      const id = R.gid.get(this)
      if (id) R.push({ op: 'colorStop', g: id, o: round(offset), c: String(color) })
    }
    return origStop.call(this, offset, color)
  }
}

/**
 * Sample-backed debug renders (all 12 cards + 2 stock styles) with the op
 * recorder on. This is the fast side channel, not the 50MP proof.
 */
async function debugRenders(page, passName, withOps) {
  if (!withOps) return []
  await page.evaluate(installOpRecorderInPage)
  // `__fmVariants` deliberately excludes 系统相机 cards, so the stock IR painter
  // is exercised through `__fmStock.render` instead (the exact stock-card API).
  const ids = CARDS.filter((c) => !c.id.startsWith('stock-')).map((c) => ({ api: 'variants', id: c.id }))
  for (const id of STOCK_SMOKE_IDS) ids.push({ api: 'stock', id })
  const out = []
  for (const item of ids) {
    const r = await page.evaluate(
      async ({ api, id }) => {
        window.__fmOps.reset({ yOffset: 0 })
        const res =
          api === 'stock'
            ? await window.__fmStock.render(id, { ratio: '3000:2000', maxEdge: 3000 })
            : await window.__fmVariants.render(id, { ratio: '3000:2000', maxEdge: 3000 })
        window.__fmOps.stop()
        const snap = window.__fmOps.snap()
        let sha = ''
        try {
          const buf = new TextEncoder().encode(res.data)
          const digest = await crypto.subtle.digest('SHA-256', buf)
          sha = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('')
        } catch {
          // insecure context fallback: length + FNV-1a of the data URL
          let h = 0x811c9dc5
          for (let i = 0; i < res.data.length; i++) {
            h ^= res.data.charCodeAt(i)
            h = Math.imul(h, 0x01000193) >>> 0
          }
          sha = `weak:${res.data.length}:${h.toString(16)}`
        }
        return { sha, W: res.W, H: res.H, ops: snap }
      },
      item
    )
    const opsJson = JSON.stringify(r.ops.ops)
    const entry = {
      api: item.api,
      id: item.id,
      W: r.W,
      H: r.H,
      pngSha: r.sha,
      opCount: r.ops.count,
      opTruncated: r.ops.truncated,
      opsSha: sha256(opsJson)
    }
    if (passName === 'A1' || passName === 'A2') entry.ops = r.ops.ops
    out.push(entry)
  }
  log(`  debug renders: ${out.length} cards (${out[0]?.W}x${out[0]?.H} sample-backed)`)
  return out
}

/**
 * One pass x one group: launch, assert flags, import fixtures, export each card
 * once, then (group A only) do the sample-backed debug renders.
 */
async function runExportPass(pass, group, fixtureFiles, opts = {}) {
  const runDir = path.join(RUNS, pass.name, group.name)
  const profile = path.join(runDir, 'profile')
  const batchDir = path.join(runDir, 'batch')
  const exportsDir = path.join(runDir, 'exports')
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  ensureDir(profile)
  ensureDir(batchDir)
  ensureDir(exportsDir)

  const env = envFor(pass)
  const app = await electron.launch({
    executablePath: require2('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: ROOT,
    env,
    timeout: 90000
  })
  const startedAt = Date.now()
  const pageErrors = []
  try {
    const page = await app.firstWindow()
    page.on('pageerror', (e) => pageErrors.push(e.message))
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
    const flags = await assertFlags(page, pass, `${pass.name}/${group.name}`)

    // The main-process directory dialog is stubbed just like scripts/e2e.mjs does.
    await app.evaluate(
      async ({ dialog }, dir) => {
        dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
      },
      batchDir
    )

    const files = group.fixtures.map((k) => fixtureFiles[k])
    const tImport = Date.now()
    await page.setInputFiles('#framemark-file-input', files)
    await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, files.length, { timeout: 600000 })
    log(`${pass.name}/${group.name}: imported ${files.length} photos in ${Date.now() - tImport}ms`)

    // One-time export settings: PNG (lossless), original size, isolated dir.
    await setVariant(page, group.cards[0])
    await openExportDialog(page)
    await page.locator('.export-dir-row button').click()
    await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, batchDir, { timeout: 30000 })
    await page.locator('.export-dialog label.field', { hasText: '格式' }).locator('button', { hasText: 'PNG', exact: true }).click()
    await page.waitForFunction(() => {
      const fields = [...document.querySelectorAll('.export-dialog label.field')]
      const f = fields.find((x) => x.textContent.includes('格式'))
      return f?.querySelector('.segmented button.active')?.textContent?.trim() === 'PNG'
    }, null, { timeout: 15000 })
    await page.locator('.export-dialog .toggle-chip', { hasText: '原始尺寸' }).click()
    await page.locator('.export-dialog-foot button', { hasText: '取消' }).click()
    await page.waitForSelector('.modal .export-dialog', { state: 'detached', timeout: 15000 })

    const images = []
    for (const card of group.cards.map((id) => CARD_BY_ID[id])) {
      const out = await exportCard(
        page,
        card,
        group.fixtures,
        batchDir,
        path.join(exportsDir, card.id),
        600000
      )
      images.push(...out)
    }

    const debug = group.name === 'A' && !SKIP_OPS ? await debugRenders(page, pass.name, true) : []
    if (pageErrors.length) fail(`${pass.name}/${group.name}: page errors: ${pageErrors.join(' | ')}`)
    const index = {
      pass: pass.name,
      group: group.name,
      env: { FRAMEMARK_TILE: env.FRAMEMARK_TILE ?? null, FRAMEMARK_SEAM_CANARY: env.FRAMEMARK_SEAM_CANARY ?? null, FRAMEMARK_BAND: env.FRAMEMARK_BAND ?? null },
      flags,
      fixtures: group.fixtures,
      cards: group.cards,
      startedAt,
      elapsedMs: Date.now() - startedAt,
      images,
      debug
    }
    fs.writeFileSync(path.join(runDir, 'index.json'), JSON.stringify(index, null, 1))
    return index
  } finally {
    await app.close().catch(() => undefined)
  }
}

/* ------------------------------------------------------------ comparisons */

function loadIndex(pass, group) {
  const file = path.join(RUNS, pass, group, 'index.json')
  if (!fs.existsSync(file)) fail(`missing index ${file}`)
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function keyOf(img) {
  return `${img.card}\u0000${img.fixture}`
}

async function comparePassPair(indexA, indexB, label) {
  const mapB = new Map(indexB.images.map((i) => [keyOf(i), i]))
  const rows = []
  for (const imgA of indexA.images) {
    const imgB = mapB.get(keyOf(imgA))
    if (!imgB) fail(`${label}: no counterpart for ${imgA.card}/${imgA.fixture}`)
    const m = await compareFiles(imgA, imgB)
    rows.push({ card: imgA.card, fixture: imgA.fixture, ...m, a: imgA.file, b: imgB.file, w: imgA.width, h: imgA.height })
  }
  return rows
}

function noiseOf(rows) {
  const maxAbs = rows.reduce((a, r) => Math.max(a, r.maxAbs), 0)
  const diffBytes = rows.reduce((a, r) => Math.max(a, r.diffBytes), 0)
  const diffPixels = rows.reduce((a, r) => Math.max(a, r.diffPixels), 0)
  return { maxAbs, diffBytes, diffPixels, identicalImages: rows.filter((r) => r.identical).length, images: rows.length }
}

/* -------------------------------------------------------------- red proofs */

function shiftBandH(raw, w, h, y0, y1, dx) {
  const out = Buffer.from(raw)
  const rowBytes = w * 3
  for (let y = y0; y < Math.min(y1, h); y++) {
    const row = y * rowBytes
    for (let x = 0; x < w; x++) {
      const sx = (x - dx + w) % w
      out[row + x * 3] = raw[row + sx * 3]
      out[row + x * 3 + 1] = raw[row + sx * 3 + 1]
      out[row + x * 3 + 2] = raw[row + sx * 3 + 2]
    }
  }
  return out
}

function shiftBandV(raw, w, h, y0, y1, dy) {
  const out = Buffer.from(raw)
  const rowBytes = w * 3
  for (let y = y0; y < Math.min(y1, h); y++) {
    const sy = Math.max(0, Math.min(h - 1, y + dy))
    raw.copy(out, y * rowBytes, sy * rowBytes, sy * rowBytes + rowBytes)
  }
  return out
}

async function writePng(raw, w, h, file) {
  await sharp(raw, { raw: { width: w, height: h, channels: 3 } }).png({ compressionLevel: 3 }).toFile(file)
}

async function runRedProofs(indices, noise) {
  // Pick the grain card: the pixel whose phase the canary is supposed to break.
  const a1 = indices.A1.A
  const grain = a1.images.find((i) => i.card === 'fm-film' && i.fixture === 'L')
  if (!grain) fail('red proof: fm-film/L export missing from A1')
  const { data, width: w, height: h } = await readRaw(grain.file)
  if (h <= 1024) fail(`red proof: grain image too short (${w}x${h})`)
  const evidenceDir = path.join(S5, 'evidence')
  ensureDir(evidenceDir)
  fs.copyFileSync(grain.file, path.join(evidenceDir, 'grain-A1.png'))

  const y0 = 512
  const y1 = 1024
  const rows = []
  const cases = [
    { name: 'horizontal 1px shift of rows 512..1024', raw: shiftBandH(data, w, h, y0, y1, 1), png: 'grain-corrupt-h.png' },
    { name: 'vertical 1px shift of rows 512..1024 (grain-phase proxy)', raw: shiftBandV(data, w, h, y0, y1, 1), png: 'grain-corrupt-v.png' }
  ]
  for (const c of cases) {
    const d = diffRaw(data, c.raw)
    const verdict = judge(d, noise)
    await writePng(c.raw, w, h, path.join(evidenceDir, c.png))
    rows.push({
      name: c.name,
      width: w,
      height: h,
      band: [y0, y1],
      diffBytes: d.diffBytes,
      diffPixels: d.diffPixels,
      maxAbs: d.maxAbs,
      comparatorOk: verdict.ok,
      why: verdict.why,
      caught: verdict.ok === false,
      png: path.join(evidenceDir, c.png)
    })
  }

  // Op-stream red proof: drop the grain overlay fillRect from a real op stream.
  let opRow = null
  const filmOps = a1.debug.find((d) => d.api === 'variants' && d.id === 'fm-film')
  if (filmOps && Array.isArray(filmOps.ops)) {
    const ops = filmOps.ops
    const overlayIdx = ops.findIndex((o) => o.op === 'fillRect' && o.c === 'overlay')
    const dropIdx = overlayIdx >= 0 ? overlayIdx : ops.length - 1
    const dropped = ops.filter((_, i) => i !== dropIdx)
    const cmp = compareOps(ops, dropped, 0)
    opRow = {
      card: 'fm-film',
      droppedIndex: dropIdx,
      droppedOp: ops[dropIdx] ?? null,
      originalCount: ops.length,
      droppedCount: dropped.length,
      comparatorOk: cmp.ok,
      caught: cmp.ok === false,
      detail: cmp
    }
  }

  const lines = []
  lines.push('S5 comparator red proof (synthetic corruptions of a real A1 export)')
  lines.push(`generated: ${new Date().toISOString()}`)
  lines.push(`image: fm-film / landscape-3000x2000 — ${w}x${h}`)
  lines.push(`source: ${grain.file}`)
  lines.push(`copies: ${path.join(evidenceDir, 'grain-A1.png')} (original) vs the two grain-corrupt-*.png files`)
  lines.push(`noise floor used by the gate: maxAbs=${noise.maxAbs} diffBytes=${noise.diffBytes} (A1 vs A2)`)
  lines.push('')
  for (const r of rows) {
    lines.push(`[${r.caught ? 'CAUGHT' : 'MISSED'}] ${r.name}`)
    lines.push(`  band rows ${r.band[0]}..${r.band[1]}  diffBytes=${r.diffBytes} diffPixels=${r.diffPixels} maxAbs=${r.maxAbs}`)
    lines.push(`  gate predicate -> ok=${r.comparatorOk} (${r.why})`)
    lines.push(`  evidence: ${r.png}`)
  }
  lines.push('')
  if (opRow) {
    lines.push(`[${opRow.caught ? 'CAUGHT' : 'MISSED'}] op-stream: dropped op #${opRow.droppedIndex} (${opRow.droppedOp?.op ?? 'n/a'})`)
    lines.push(`  ${opRow.originalCount} -> ${opRow.droppedCount} ops; comparator -> ok=${opRow.comparatorOk}`)
    if (opRow.detail?.reason) lines.push(`  reason=${opRow.detail.reason} index=${opRow.detail.index}`)
  } else {
    lines.push('[SKIPPED] op-stream red proof (no A1 debug ops recorded)')
  }
  const caughtPixels = rows.filter((r) => r.caught).length
  const allCaught = caughtPixels === rows.length && (!opRow || opRow.caught)
  lines.push('')
  lines.push(`RESULT: ${caughtPixels}/${rows.length} pixel corruptions caught${opRow ? `, ${opRow.caught ? 1 : 0}/1 op corruptions caught` : ''} — ${allCaught ? 'PASS' : 'FAIL'}`)
  fs.writeFileSync(path.join(S5, 'comparator-red.txt'), lines.join('\n') + '\n')
  log(`red proofs: ${caughtPixels}/${rows.length} pixel, ${opRow ? (opRow.caught ? 1 : 0) : 'n/a'}/1 op`)
  return { rows, opRow, allCaught, noise }
}

/* ------------------------------------------------------------ op streams */

function foldOp(op, dy) {
  if (!dy) return op
  const o = { ...op }
  if (Array.isArray(o.p)) o.p = [o.p[0], Math.round((o.p[1] + dy) * 1000) / 1000]
  if (Array.isArray(o.r)) o.r = [o.r[0], Math.round((o.r[1] + dy) * 1000) / 1000, o.r[2], o.r[3]]
  return o
}

function compareOps(a, b, foldY = 0) {
  if (a.length !== b.length) return { ok: false, reason: 'op-count', a: a.length, b: b.length, index: Math.min(a.length, b.length) }
  for (let i = 0; i < a.length; i++) {
    const x = JSON.stringify(foldOp(a[i], 0))
    const y = JSON.stringify(foldOp(b[i], foldY))
    if (x !== y) return { ok: false, reason: 'op-mismatch', index: i, a: a[i], b: foldOp(b[i], foldY) }
  }
  return { ok: true, reason: 'identical', count: a.length }
}

function compareOpPasses(indices) {
  const rows = []
  const a1 = indices.A1.A.debug
  const a2 = indices.A2.A.debug
  const b = indices.B512.A.debug
  const byKey = (arr) => new Map(arr.map((d) => [`${d.api}:${d.id}`, d]))
  const m1 = byKey(a1)
  const m2 = byKey(a2)
  const mb = byKey(b)
  for (const [key, d1] of m1) {
    const d2 = m2.get(key)
    const db = mb.get(key)
    const cmp2 = d2 && Array.isArray(d1.ops) && Array.isArray(d2.ops) ? compareOps(d1.ops, d2.ops, 0) : { ok: false, reason: 'missing' }
    const okB = Boolean(db && db.opCount === d1.opCount && db.opsSha === d1.opsSha && db.pngSha === d1.pngSha)
    rows.push({
      key,
      W: d1.W,
      H: d1.H,
      opCount: d1.opCount,
      opCountA2: d2?.opCount ?? -1,
      opCountB: db?.opCount ?? -1,
      pngShaA1: d1.pngSha,
      pngShaA2: d2?.pngSha ?? null,
      pngShaB: db?.pngSha ?? null,
      a1VsA2: cmp2.ok,
      a1VsA2Detail: cmp2.ok ? undefined : cmp2,
      bMatchesA1: okB
    })
  }
  const allOk = rows.length > 0 && rows.every((r) => r.a1VsA2 && r.bMatchesA1)
  return { rows, allOk, foldNote: 'S6 banded renders must call __fmOps.reset({yOffset: bandY}); foldY is then 0' }
}

/* -------------------------------------------------------------- XL + memory */

async function runMemoryCase(cardId, pass, fixtureFile, runName) {
  const runDir = path.join(RUNS, runName)
  const profile = path.join(runDir, 'profile')
  const outDir = path.join(runDir, 'exports')
  freshDir(runDir)
  ensureDir(profile)
  ensureDir(outDir)

  const env = envFor(pass)
  const app = await electron.launch({
    executablePath: require2('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: ROOT,
    env,
    timeout: 90000
  })
  try {
    const page = await app.firstWindow()
    const pageErrors = []
    page.on('pageerror', (e) => pageErrors.push(e.message))
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
    await assertFlags(page, pass, runName)
    await app.evaluate(async ({ dialog }, dir) => {
      dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
    }, outDir)
    const tImport = Date.now()
    await page.setInputFiles('#framemark-file-input', fixtureFile)
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 1, null, { timeout: 600000 })
    const importMs = Date.now() - tImport

    await setVariant(page, cardId)
    await openExportDialog(page)
    await page.locator('.export-dir-row button').click()
    await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, outDir, { timeout: 30000 })
    await page.locator('.export-dialog label.field', { hasText: '格式' }).locator('button', { hasText: 'PNG', exact: true }).click()
    await page.waitForFunction(() => {
      const fields = [...document.querySelectorAll('.export-dialog label.field')]
      const f = fields.find((x) => x.textContent.includes('格式'))
      return f?.querySelector('.segmented button.active')?.textContent?.trim() === 'PNG'
    }, null, { timeout: 15000 })
    await page.locator('.export-dialog .toggle-chip', { hasText: '原始尺寸' }).click()

    const baseline =
      (await app.evaluate(({ app: eapp }) =>
        eapp.getAppMetrics().map((p) => ({ type: p.type, pid: p.pid, ws: p.memory.workingSetSize, peak: p.memory.peakWorkingSetSize }))
      )).find((p) => p.type === 'Tab') || { ws: 0, peak: 0, pid: -1 }

    let sampling = true
    const series = []
    const t0 = Date.now()
    const poll = (async () => {
      while (sampling) {
        try {
          const m = await app.evaluate(({ app: eapp }) =>
            eapp.getAppMetrics().map((p) => ({ type: p.type, pid: p.pid, ws: p.memory.workingSetSize, peak: p.memory.peakWorkingSetSize }))
          )
          const tab = m.find((p) => p.type === 'Tab')
          if (tab) series.push({ t: Date.now() - t0, ws: tab.ws, peak: tab.peak })
        } catch {
          /* app may be mid-teardown */
        }
        await sleep(120)
      }
    })()

    await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
    await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 30000 })
    await page.waitForSelector('.export-card', { timeout: 60000 })
    await page.waitForFunction(
      () => {
        const h = document.querySelector('.export-card h3')
        return h && !h.textContent.includes('正在导出')
      },
      null,
      { timeout: 1200000 }
    )
    sampling = false
    await poll
    const elapsed = Date.now() - t0
    const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
    await dismissExport(page)

    const files = fs.readdirSync(outDir).filter((f) => f.toLowerCase().endsWith('.png'))
    if (files.length !== 1) fail(`${runName}: expected 1 png, got ${JSON.stringify(files)} — ${status}`)
    const file = path.join(outDir, files[0])
    const meta = await sharp(file).metadata()
    const raw = await readRaw(file)
    const peakSample = series.reduce((a, s) => (s.ws > a.ws ? s : a), { ws: 0, t: 0 })
    const peakReported = series.reduce((a, s) => Math.max(a, s.peak || 0), 0)
    const result = {
      run: runName,
      pass: pass.name,
      env: { FRAMEMARK_TILE: env.FRAMEMARK_TILE ?? null, FRAMEMARK_BAND: env.FRAMEMARK_BAND ?? null },
      card: cardId,
      fixture: path.basename(fixtureFile),
      canvas: `${meta.width}x${meta.height}`,
      width: meta.width,
      height: meta.height,
      megapixels: Number(((meta.width * meta.height) / 1e6).toFixed(1)),
      bytes: fs.statSync(file).size,
      sha: sha256(raw.data),
      file,
      status,
      importMs,
      exportMs: elapsed,
      samples: series.length,
      baselineWSMB: MB(baseline.ws),
      peakWSMB: MB(peakSample.ws),
      deltaMB: MB(peakSample.ws - baseline.ws),
      peakReportedMB: MB(peakReported),
      baselinePid: baseline.pid,
      pageErrors,
      seriesTail: series.slice(-5).map((s) => MB(s.ws))
    }
    fs.writeFileSync(path.join(runDir, 'case.json'), JSON.stringify(result, null, 1))
    log(`mem ${runName}: ${result.canvas} (${result.megapixels}MP) peak ${result.peakWSMB}MB (+${result.deltaMB}MB) in ${(elapsed / 1000).toFixed(1)}s`)
    return result
  } finally {
    await app.close().catch(() => undefined)
  }
}

/* ---------------------------------------------------------------- plumbing */

/**
 * One extra launch with BOTH env vars set to '1'. Proves `seamCanary` is wired
 * too (every A/B render pass asserts tile only) and that the preload bridge
 * copies the main-process read, not its own `process.env`.
 */
async function checkPlumbing(fixtureFiles) {
  const runDir = path.join(RUNS, 'plumbing')
  const profile = path.join(runDir, 'profile')
  freshDir(runDir)
  ensureDir(profile)
  const env = { ...process.env, FRAMEMARK_TILE: '1', FRAMEMARK_SEAM_CANARY: '1' }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require2('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: ROOT,
    env,
    timeout: 90000
  })
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
    const bridge = await page.evaluate(() => (window.framemark ? JSON.parse(JSON.stringify(window.framemark.flags)) : null))
    const debug = await page.evaluate(() => (window.__fmFlags ? window.__fmFlags.get() : null))
    const expect = { tile: true, seamCanary: true }
    if (JSON.stringify(bridge) !== JSON.stringify(expect)) fail(`plumbing bridge ${JSON.stringify(bridge)} != ${JSON.stringify(expect)}`)
    if (JSON.stringify(debug) !== JSON.stringify(expect)) fail(`plumbing __fmFlags ${JSON.stringify(debug)} != ${JSON.stringify(expect)}`)
    log(`plumbing: env 1/1 -> bridge ${JSON.stringify(bridge)} -> __fmFlags ${JSON.stringify(debug)}`)
    return { ok: true, bridge, debug }
  } finally {
    await app.close().catch(() => undefined)
  }
}

/* -------------------------------------------------------------------- main */

async function main() {
  const tStart = Date.now()
  ensureDir(S5)
  ensureDir(RUNS)
  log(`S5 tile-ab gate — ${SMOKE ? 'SMOKE' : 'FULL'}${SKIP_MEMORY ? ' (no memory)' : ''}`)

  const fixtureFiles = await ensureFixtures()
  progress(`fixtures ready: ${FIXTURE_SPECS.map((f) => `${f.w}x${f.h}`).join(', ')} under _research/s5/fixtures`)

  const plumbing = await checkPlumbing(fixtureFiles)
  progress(`flag plumbing green: FRAMEMARK_TILE/SEAM_CANARY=1 -> window.framemark.flags + __fmFlags both true; desktop only (Android has no bridge)`)

  const passes = SMOKE ? [PASS_BY_NAME.A1] : PASSES
  const groups = SMOKE
    ? [{ name: 'A', fixtures: ['L'], cards: ['blur-classic', 'fm-film', 'stock-xiaomi-classic-22'] }]
    : GROUPS

  const indices = {}
  const timings = {}
  for (const pass of passes) {
    indices[pass.name] = {}
    for (const group of groups) {
      const t0 = Date.now()
      indices[pass.name][group.name] = await runExportPass(pass, group, fixtureFiles)
      timings[`${pass.name}/${group.name}`] = Date.now() - t0
      progress(`pass ${pass.name}/${group.name}: ${indices[pass.name][group.name].images.length} images in ${((Date.now() - t0) / 1000).toFixed(1)}s`)
    }
  }

  let noise = { maxAbs: 0, diffBytes: 0, diffPixels: 0, identicalImages: 0, images: 0 }
  let noiseRows = []
  let bandRows = []
  let bandVerdicts = []
  if (!SMOKE) {
    noiseRows = await comparePassPair(indices.A1.A, indices.A2.A, 'A1vsA2/A')
    const noiseRowsB = await comparePassPair(indices.A1.B, indices.A2.B, 'A1vsA2/B')
    noise = noiseOf([...noiseRows, ...noiseRowsB])
    const noiseFile = {
      generatedAt: new Date().toISOString(),
      definition: 'per-pixel diff between two unbanded runs (A1 env unset, A2 env=0), same fixtures/profile shape',
      aggregate: noise,
      perImage: [...noiseRows, ...noiseRowsB].map((r) => ({
        card: r.card,
        fixture: r.fixture,
        width: r.w,
        height: r.h,
        identical: r.identical,
        diffBytes: r.diffBytes,
        diffPixels: r.diffPixels,
        maxAbs: r.maxAbs
      }))
    }
    fs.writeFileSync(path.join(S5, 'noise-floor.json'), JSON.stringify(noiseFile, null, 1))
    progress(`noise floor measured: ${noise.identicalImages}/${noise.images} images byte-identical, maxAbs=${noise.maxAbs}, diffBytes=${noise.diffBytes}`)

    for (const pass of passes.filter((p) => p.tile)) {
      const rowsA = await comparePassPair(indices.A1.A, indices[pass.name].A, `${pass.name}/A`)
      const rowsB = await comparePassPair(indices.A1.B, indices[pass.name].B, `${pass.name}/B`)
      const rows = [...rowsA, ...rowsB]
      const verdicts = rows.map((r) => {
        const v = judge(r, noise)
        return { card: r.card, fixture: r.fixture, band: pass.band, ok: v.ok, why: v.why, diffBytes: r.diffBytes, maxAbs: r.maxAbs }
      })
      bandRows.push(...rows.map((r) => ({ ...r, band: pass.band })))
      bandVerdicts.push({ pass: pass.name, band: pass.band, verdicts, ok: verdicts.every((v) => v.ok) })
      progress(`${pass.name} (band ${pass.band}): ${verdicts.filter((v) => v.ok).length}/${verdicts.length} within noise floor`)
    }
  }

  const red = await runRedProofs(indices, noise)
  progress(`red proofs: ${red.rows.filter((r) => r.caught).length}/${red.rows.length} synthetic pixel corruptions caught by the gate predicate; op-stream ${red.opRow?.caught ? 'caught' : 'n/a'}`)

  let opStream = null
  if (!SMOKE) {
    opStream = compareOpPasses(indices)
    fs.writeFileSync(path.join(S5, 'op-stream.json'), JSON.stringify(opStream.rows.map(({ a1VsA2Detail, ...r }) => r), null, 1))
    progress(`op stream: ${opStream.rows.length} cards, A1==A2 ${opStream.rows.filter((r) => r.a1VsA2).length}/${opStream.rows.length}, B512==A1 ${opStream.rows.filter((r) => r.bMatchesA1).length}/${opStream.rows.length}`)
  }

  let memory = null
  let xlParity = null
  if (!SMOKE && !SKIP_MEMORY) {
    const xl = fixtureFiles.XL
    const memCases = []
    const t0 = Date.now()
    const blurA1 = await runMemoryCase('blur-classic', PASS_BY_NAME.A1, xl, 'xl-blur-A1')
    memCases.push(blurA1)
    const blurA2 = await runMemoryCase('blur-classic', PASS_BY_NAME.A2, xl, 'xl-blur-A2')
    memCases.push(blurA2)
    const blurB = await runMemoryCase('blur-classic', PASS_BY_NAME.B1024, xl, 'xl-blur-B1024')
    memCases.push(blurB)
    const filmA1 = await runMemoryCase('fm-film', PASS_BY_NAME.A1, xl, 'xl-film-A1')
    memCases.push(filmA1)
    const [d1, d2, d3] = await Promise.all([
      compareFiles(blurA1, blurA2),
      compareFiles(blurA1, blurB)
    ])
    xlParity = {
      card: 'blur-classic',
      fixture: 'large-8192x6144.jpg',
      a1VsA2: d1,
      a1VsB1024: d2,
      judgeA2: judge(d1, noise),
      judgeB: judge(d2, noise)
    }
    memory = {
      generatedAt: new Date().toISOString(),
      fixture: 'large-8192x6144.jpg (8192x6144 = 50.3MP)',
      method: 'app.getAppMetrics() every 120ms during a real PNG export at 原始尺寸 (scripts/_mem-probe.cjs:39-46 sampler)',
      cases: memCases.map(({ seriesTail, ...rest }) => rest),
      elapsedMs: Date.now() - t0
    }
    fs.writeFileSync(path.join(S5, 'memory-baseline.json'), JSON.stringify(memory, null, 1))
    progress(`50MP: blur-classic peak ${blurA1.peakWSMB}MB (+${blurA1.deltaMB}MB), fm-film peak ${filmA1.peakWSMB}MB; A1==A2=${d1.identical}, A1==B1024=${d2.identical}`)
  }

  const elapsed = Date.now() - tStart
  const checks = {
    plumbing: plumbing.ok,
    fixtures: Object.keys(fixtureFiles).length === FIXTURE_SPECS.length,
    noiseMeasured: SMOKE ? true : noise.images > 0,
    bandsWithinNoise: SMOKE ? true : bandVerdicts.every((b) => b.ok),
    redProofs: red.allCaught,
    opStream: SMOKE ? true : Boolean(opStream?.allOk),
    xlParity: SMOKE || SKIP_MEMORY ? true : Boolean(xlParity?.judgeA2.ok && xlParity?.judgeB.ok),
    memoryRecorded: SMOKE || SKIP_MEMORY ? true : Boolean(memory?.cases?.length >= 4)
  }
  const allOk = Object.values(checks).every(Boolean)

  const results = {
    generatedAt: new Date().toISOString(),
    smoke: SMOKE,
    totalMs: elapsed,
    checks,
    allOk,
    matrix: {
      passes: passes.map((p) => ({ name: p.name, tile: p.tile, band: p.band, envTile: p.envTile ?? null })),
      groups: groups.map((g) => ({ name: g.name, fixtures: g.fixtures, cards: g.cards })),
      exportsPerPass: SMOKE ? 3 : GROUP_A.cards.length * GROUP_A.fixtures.length + GROUP_B.cards.length * GROUP_B.fixtures.length,
      skipped: SMOKE
        ? ['everything except smoke']
        : [
            'per-card x every-fixture combinations; only the matrix above is run',
            'stock styles beyond stock-xiaomi-classic-22 (exported at L/P) are only exercised through __fmStock in memory'
          ]
    },
    noise,
    bands: bandVerdicts.map((b) => ({ pass: b.pass, band: b.band, ok: b.ok, failures: b.verdicts.filter((v) => !v.ok) })),
    red: { pixel: red.rows, op: red.opRow, allCaught: red.allCaught },
    opStream: opStream ? { allOk: opStream.allOk, rows: opStream.rows } : null,
    xlParity,
    memory: memory ? { cases: memory.cases, elapsedMs: memory.elapsedMs } : null,
    timings,
    checks
  }
  fs.writeFileSync(path.join(S5, 'results.json'), JSON.stringify(results, null, 1))
  fs.writeFileSync(path.join(S5, 'run.log'), LOG.join('\n') + '\n')

  if (!KEEP) {
    // Keep the JSON evidence + the grain copies; drop the bulky PNGs, the
    // per-pass Electron profiles and the 50MP originals (all regenerable).
    for (const pass of passes) {
      for (const group of groups) {
        for (const leaf of ['exports', 'profile', 'batch']) {
          fs.rmSync(path.join(RUNS, pass.name, group.name, leaf), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
        }
      }
    }
    fs.rmSync(path.join(RUNS, 'plumbing', 'profile'), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    for (const name of ['xl-blur-A1', 'xl-blur-A2', 'xl-blur-B1024', 'xl-film-A1']) {
      fs.rmSync(path.join(RUNS, name), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
    }
  }

  console.log('\n==== S5 RESULT ====')
  console.log(JSON.stringify({ checks, allOk, noise: { maxAbs: noise.maxAbs, diffBytes: noise.diffBytes }, bands: results.bands.map((b) => `${b.pass}:${b.ok ? 'PASS' : 'FAIL'}`), red: results.red.allCaught, op: results.opStream?.allOk ?? 'skipped', xl: results.xlParity ? { a2: results.xlParity.judgeA2.ok, b: results.xlParity.judgeB.ok } : 'skipped', totalMin: Number((elapsed / 60000).toFixed(1)) }, null, 1))
  if (!allOk) process.exitCode = 1
}

main().catch((e) => {
  console.error('S5 FAIL', e)
  try {
    fs.mkdirSync(S5, { recursive: true })
    fs.writeFileSync(path.join(S5, 'run.log'), LOG.join('\n') + `\nFAIL ${e.stack || e.message}\n`)
    progress(`FAILED: ${e.message}`)
  } catch {
    /* ignore */
  }
  process.exit(1)
})
