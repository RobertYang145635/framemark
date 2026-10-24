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
  kodak: { file: 'cand-kodak-3.svg', tones: { black: '#111111', white: '#FFFFFF', yellow: '#F2B01E' } },
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
  const base = await sharp(src, { density: 300 }).png().toBuffer()
  const initial = await sharp(base).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const cleared = clearWhiteBackground(initial.data, initial.info.width, initial.info.height)
  const trimmed = await sharp(cleared, { raw: { width: initial.info.width, height: initial.info.height, channels: 4 } })
    .trim({ threshold: 1 })
    .resize({ width: 1600, height: 800, fit: 'inside', withoutEnlargement: true })
    .png()
    .toBuffer()
  const meta = await sharp(trimmed).metadata()
  if (!meta.width || !meta.height) throw new Error('empty render')
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
  return { key, source: spec.file, width: info.width, height: info.height, tones: written }
}

async function main() {
  fs.mkdirSync(REPORT.replace(/manifest\.json$/, ''), { recursive: true })
  const results = []
  for (const [key, spec] of Object.entries(SOURCES)) {
    results.push(await build(key, spec))
    console.log('OK', key, spec.file)
  }
  const existing = fs.existsSync(REPORT) ? JSON.parse(fs.readFileSync(REPORT, 'utf8')) : { logos: [] }
  const merged = new Map((existing.logos ?? []).map((entry) => [entry.key, entry]))
  for (const entry of results) merged.set(entry.key, entry)
  fs.writeFileSync(REPORT, JSON.stringify({ generatedAt: new Date().toISOString(), hidden: HIDDEN, logos: [...merged.values()] }, null, 2))
  console.log('done', results.length, 'hidden', HIDDEN.length)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
