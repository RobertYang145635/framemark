const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const RAW = 'D:/border&watermark/_research/logos-real/raw'
const OUT = 'D:/border&watermark/framemark/src/renderer/src/assets/logos'
const REPORT = 'D:/border&watermark/_research/logos-real/manifest.json'

const SOURCES = {
  agfa: { file: 'agfa.svg', tones: { black: '#111111', white: '#FFFFFF', red: null } },
  alpa: { file: 'alpa.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  arri: { file: 'arri.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  casio: { file: 'casio.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  contax: { file: 'contax.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  cosina: { file: 'cosina-logo.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  google: { file: 'si-google.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  gopro: { file: 'cand-gopro-2.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  kodak: { file: 'cand-kodak-3.svg', tones: { black: '#111111', white: '#FFFFFF', yellow: '#F2B01E' }, knockoutWhite: true },
  konica: { file: 'konica.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  konica_minolta: { file: 'konica-minolta.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  mamiya: { file: 'mamiya-logo.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  minolta: { file: 'minolta.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  motorola: { file: 'si-motorola.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  nokia: { file: 'si-nokia.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  phaseone: { file: 'phase-one-1.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  polaroid: { file: 'polaroid.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  realme: { file: 'realme-1.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  rollei: { file: 'rollei.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  sigma: { file: 'sigma.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  tamron: { file: 'tamron.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  tokina: { file: 'tokina.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  yashica: { file: 'yashica.svg', tones: { black: '#111111', white: '#FFFFFF' } },
  zenit: { file: 'zenit.svg', tones: { black: '#111111', white: '#FFFFFF' } }
}

const ONLY = (() => {
  const arg = process.argv.find((a) => a.startsWith('--only='))
  if (!arg) return null
  const keys = arg.slice('--only='.length).split(',').map((s) => s.trim()).filter(Boolean)
  return keys.length ? new Set(keys) : null
})()

const WHITE_FILL = /^(?:#fff(?:fff)?|#ffffff|white|rgb\(\s*255\s*,\s*255\s*,\s*255\s*\))$/i

// Page-background elements (full-canvas white rectangles) are not part of the mark. If they stay in
// the SVG, librsvg paints them opaque white, `clearWhiteBackground` cannot reach the enclosed
// counters (flood fill starts at the image border), and `fillRgba` turns every counter opaque.
// Remove only full-canvas white paper; genuine white glyph paths (AGFA's script) are untouched.
function stripPageBackground(svg) {
  const viewBox = svg.match(/viewBox\s*=\s*"([^"]+)"/i)
  const wAttr = svg.match(/\bwidth\s*=\s*"([\d.]+)(?:px)?"/i)
  const hAttr = svg.match(/\bheight\s*=\s*"([\d.]+)(?:px)?"/i)
  let canvasW = wAttr ? parseFloat(wAttr[1]) : null
  let canvasH = hAttr ? parseFloat(hAttr[1]) : null
  if (viewBox) {
    const p = viewBox[1].trim().split(/[\s,]+/).map(Number)
    if (p.length === 4 && Number.isFinite(p[2]) && Number.isFinite(p[3])) {
      canvasW = p[2]
      canvasH = p[3]
    }
  }
  if (!canvasW || !canvasH) return { svg, removed: [], whitePaths: [] }

  const fillOf = (attrs) => {
    const style = attrs.match(/\bstyle\s*=\s*"([^"]*)"/i)
    if (style) {
      const m = style[1].match(/(?:^|;)\s*fill\s*:\s*([^;]+)/i)
      if (m) return m[1].trim().replace(/\s*!important$/i, '')
    }
    const attr = attrs.match(/\bfill\s*=\s*"([^"]*)"/i)
    return attr ? attr[1].trim() : null
  }
  const isWhite = (fill) => fill != null && WHITE_FILL.test(fill)
  const near = (v, target) => Math.abs(v - target) <= Math.max(0.5, target * 0.01)

  const removed = []
  const whitePaths = []
  let out = svg.replace(/<path\b([^>]*?)\/?>/gi, (tag, attrs) => {
    const fill = fillOf(attrs)
    if (!isWhite(fill)) return tag
    const d = attrs.match(/\bd\s*=\s*"([^"]*)"/i)
    if (!d) return tag
    // M0 0 h<canvasW> v<canvasH> H0 [V0] z - relative horiz/vert sides, absolute return
    const m = d[1].replace(/[\s,]+/g, '').match(/^M0,?0h([\d.]+)v([\d.]+)H0(?:V0)?z$/i)
    if (!m) return tag
    if (!near(parseFloat(m[1]), canvasW) || !near(parseFloat(m[2]), canvasH)) return tag
    removed.push({ type: 'path', d: d[1].slice(0, 40), fill })
    return ''
  })
  // White paths that are not the page: content for most brands, but counter knockouts in Kodak
  for (const m of out.matchAll(/<path\b[^>]*?\/?>/gi)) {
    if (isWhite(fillOf(m[0]))) whitePaths.push(m[0])
  }

  out = out.replace(/<rect\b([^>]*?)\/?>/gi, (tag, attrs) => {
    const fill = fillOf(attrs)
    if (!isWhite(fill)) return tag
    const num = (name, dflt) => {
      const m = attrs.match(new RegExp(`\\b${name}\\s*=\\s*"([\\d.]+)(?:px)?"`, 'i'))
      return m ? parseFloat(m[1]) : dflt
    }
    const w = num('width', 0), h = num('height', 0)
    const x = num('x', 0), y = num('y', 0)
    if (!near(w, canvasW) || !near(h, canvasH)) return tag
    if (x > canvasW * 0.01 || y > canvasH * 0.01) return tag
    removed.push({ type: 'rect', w, h, fill })
    return ''
  })
  return { svg: out, removed, whitePaths }
}

// Some vectors draw counters as opaque white shapes on top of solid letters (Kodak: o/d/a bowls).
// For a single-tone export those white shapes are knockouts, not artwork: rasterise them separately
// and subtract their alpha, otherwise `fillRgba` paints them in the tone colour and the counters fill.
function splitWhiteKnockout(svg, whitePaths) {
  if (!whitePaths.length) return null
  // knockout copy keeps only white paths; every non-white path is dropped
  const knockout = svg.replace(/<path\b[^>]*?\/?>/gi, (tag) => (whitePaths.includes(tag) ? tag : ''))
  let main = svg
  for (const tag of whitePaths) main = main.replace(tag, '')
  return { main, knockout }
}

// 扫描件/官网补字标由 import-missing-brand-logos.cjs 生成；本脚本只重出下面 SOURCES 里的矢量源，
// 写 manifest 时会保留已有条目，避免覆盖新增品牌。
const HIDDEN = ['samyang']

function fillRgba(raw, width, height, color) {
  const rgb = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)]
  const out = Buffer.from(raw)
  for (let i = 0; i < out.length; i += 4) {
    out[i] = rgb[0]
    out[i + 1] = rgb[1]
    out[i + 2] = rgb[2]
  }
  return out
}

function clearWhiteBackground(raw, width, height) {
  const out = Buffer.from(raw)
  const seen = new Uint8Array(width * height)
  const queue = new Int32Array(width * height)
  let head = 0
  let tail = 0
  const nearWhite = (idx) => {
    const i = idx * 4
    if (out[i + 3] === 0) return true
    const r = out[i], g = out[i + 1], b = out[i + 2]
    return Math.min(r, g, b) > 240 && Math.max(r, g, b) - Math.min(r, g, b) < 10
  }
  const push = (x, y) => {
    if (x < 0 || y < 0 || x >= width || y >= height) return
    const idx = y * width + x
    if (seen[idx] || !nearWhite(idx)) return
    seen[idx] = 1
    out[idx * 4 + 3] = 0
    queue[tail++] = idx
  }
  for (let x = 0; x < width; x++) { push(x, 0); push(x, height - 1) }
  for (let y = 0; y < height; y++) { push(0, y); push(width - 1, y) }
  while (head < tail) {
    const idx = queue[head++]
    const x = idx % width
    const y = Math.floor(idx / width)
    push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
  }
  return out
}

async function build(key, spec) {
  const src = path.join(RAW, spec.file)
  if (!fs.existsSync(src)) throw new Error(`missing source: ${src}`)
  const xml = fs.readFileSync(src, 'utf8')
  const stripped = stripPageBackground(xml)
  let sourceSvg = stripped.svg
  let knockout = null
  if (spec.knockoutWhite) {
    const split = splitWhiteKnockout(stripped.svg, stripped.whitePaths)
    if (!split) throw new Error(`knockoutWhite set but no white paths found in ${spec.file}`)
    sourceSvg = split.main
    knockout = split.knockout
  }
  const base = await sharp(Buffer.from(sourceSvg), { density: 300 }).png().toBuffer()
  const initial = await sharp(base).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  let rgba = initial.data
  if (knockout) {
    const kb = await sharp(Buffer.from(knockout), { density: 300 }).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
    if (kb.info.width !== initial.info.width || kb.info.height !== initial.info.height) {
      throw new Error(`knockout raster size mismatch for ${spec.file}`)
    }
    rgba = Buffer.from(rgba)
    for (let i = 3; i < rgba.length; i += 4) rgba[i] = Math.max(0, rgba[i] - kb.data[i])
  }
  // Safety net only: the page background is already gone from the SVG, so this must be a no-op
  // for the affected sources. It stays because a few sources may still paint a border-touching paper.
  const cleared = clearWhiteBackground(rgba, initial.info.width, initial.info.height)
  const trimmed = await sharp(cleared, { raw: { width: initial.info.width, height: initial.info.height, channels: 4 } })
    .trim({ threshold: 1 })
    // cubic, not the default lanczos3: lanczos undershoot clips alpha to 0 at hard vector edges and
    // leaves 1 px transparent hairline "holes" along glyph edges (visible on green/dark backgrounds).
    .resize({ width: 1600, height: 800, fit: 'inside', withoutEnlargement: true, kernel: 'cubic' })
    .png()
    .toBuffer()
  const meta = await sharp(trimmed).metadata()
  if (!meta.width || !meta.height) throw new Error('empty render')
  if (!stripped.removed.length) console.warn('WARN no full-canvas page background found in', spec.file)
  const { data, info } = await sharp(trimmed).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const written = []
  for (const [tone, color] of Object.entries(spec.tones)) {
    let pipeline
    if (color) {
      const rgba = fillRgba(data, info.width, info.height, color)
      pipeline = sharp(rgba, { raw: { width: info.width, height: info.height, channels: 4 } })
    } else {
      pipeline = sharp(trimmed)
    }
    const buf = await pipeline
      .extend({ top: 10, bottom: 10, left: 10, right: 10, background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .webp({ quality: 100, lossless: true, alphaQuality: 100 })
      .toBuffer()
    const file = path.join(OUT, `${key}_${tone}.webp`)
    fs.writeFileSync(file, buf)
    written.push({ tone, file, bytes: buf.length })
  }
  return { key, source: spec.file, width: info.width, height: info.height, removedBackground: stripped.removed, tones: written }
}

async function main() {
  const entries = Object.entries(SOURCES).filter(([key]) => !ONLY || ONLY.has(key))
  if (ONLY) {
    const unknown = [...ONLY].filter((k) => !SOURCES[k])
    if (unknown.length) throw new Error(`--only has unknown keys: ${unknown.join(', ')}`)
  }
  fs.mkdirSync(REPORT.replace(/manifest\.json$/, ''), { recursive: true })
  const results = []
  for (const [key, spec] of entries) {
    results.push(await build(key, spec))
    console.log('OK', key, spec.file)
  }
  const existing = fs.existsSync(REPORT) ? JSON.parse(fs.readFileSync(REPORT, 'utf8')) : { logos: [] }
  const merged = new Map((existing.logos ?? []).map((entry) => [entry.key, entry]))
  for (const entry of results) merged.set(entry.key, entry)
  fs.writeFileSync(REPORT, JSON.stringify({ generatedAt: new Date().toISOString(), hidden: HIDDEN, logos: [...merged.values()] }, null, 2))
  console.log('done', results.length, 'hidden', HIDDEN.length, ONLY ? `only=${[...ONLY].join(',')}` : 'all')
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
