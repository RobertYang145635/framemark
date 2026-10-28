/**
 * A2 part 1 — deterministic fixtures for the JPEG-chroma study.
 *
 * Writes ONLY into D:/border&watermark/_research/a2/. Product code is untouched.
 *
 * Four 1200x800 PNGs, the reference pixels every encoder is measured against:
 *   bars      saturated red/blue/yellow hard vertical edges + sharp-stepped
 *             colour ramp + 24px chroma checkerboard + black/white reference
 *   colortext small coloured text on white (the 4:2:0 worst case)
 *   skin      smooth skin-tone gradient with soft highlights
 *   detail    per-pixel random colour blurred 0.5px (seeded PRNG)
 *
 * All four are produced by sharp. Text is rendered through sharp's pango `text`
 * input: SVG <text> in this build renders NOTHING (measured: 0 non-white pixels,
 * even with a FONTCONFIG_FILE override), so the fontconfig cache is pointed at
 * C:/Windows/Fonts before sharp is loaded and the text comes from pango.
 *
 * Also emits the TIFF capability fixtures:
 *   fixtures/photo.tif    1000x700 LZW (deliberately NOT 1200x800, so imported
 *                         dimensions cannot be confused with a study fixture)
 *   fixtures/photo.jpg    1200x800 q92 (the "normal JPEG-sourced photo")
 */
const fs = require('node:fs')
const path = require('node:path')

const A2 = path.resolve(__dirname, '..', '..', '_research', 'a2')
const FIX = path.join(A2, 'fixtures')
const IMPORT_TIF = path.join(A2, 'import', 'tiff')
const TMP = path.join(A2, 'tmp')

// pango resolves fonts through fontconfig; without this it finds none and every
// text run renders empty. Set BEFORE sharp (and thus libvips) is loaded.
fs.mkdirSync(TMP, { recursive: true })
const FONTCONF = path.join(TMP, 'fonts.conf')
fs.writeFileSync(
  FONTCONF,
  '<?xml version="1.0"?>\n<fontconfig>\n  <dir>C:\\Windows\\Fonts</dir>\n  <cachedir>C:\\Windows\\Temp\\fc-cache-a2</cachedir>\n</fontconfig>\n'
)
process.env.FONTCONFIG_FILE = FONTCONF

const sharp = require('sharp')

const W = 1200
const H = 800

function hslToHex(h, s, l) {
  const a = (s / 100) * Math.min(l / 100, 1 - l / 100)
  const f = (n) => {
    const k = (n + h / 30) % 12
    const c = l / 100 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(255 * c).toString(16).padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

function mulberry32(seed) {
  let a = seed >>> 0
  return function () {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function barsSvg() {
  const parts = []
  // Top 300px: hard vertical edges between saturated primaries.
  const stripes = ['#ff0000', '#0000ff', '#ffe600', '#ffffff', '#00ff00', '#ff00ff']
  for (let i = 0; i < 12; i++) {
    parts.push(`<rect x="${i * 100}" y="0" width="100" height="300" fill="${stripes[i % stripes.length]}"/>`)
  }
  // Middle 220px: a sharp-edged (stepped) fully saturated colour ramp.
  const steps = 24
  const sw = W / steps
  for (let i = 0; i < steps; i++) {
    parts.push(
      `<rect x="${(i * sw).toFixed(2)}" y="300" width="${(sw + 0.5).toFixed(2)}" height="220" fill="${hslToHex((i * 360) / steps, 100, 50)}"/>`
    )
  }
  // Bottom 220px: 24px colour checkerboard (worst case for 2x2 chroma blocks)
  // and a black/white reference band along the very bottom.
  for (let y = 520; y < 760; y += 24) {
    for (let x = 0; x < W; x += 24) {
      const on = ((x / 24 + y / 24) | 0) % 2 === 0
      parts.push(`<rect x="${x}" y="${y}" width="24" height="24" fill="${on ? '#ff2200' : '#0040ff'}"/>`)
    }
  }
  for (let i = 0; i < 16; i++) {
    parts.push(`<rect x="${i * 75}" y="760" width="75" height="40" fill="${i % 2 ? '#000000' : '#ffffff'}"/>`)
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">${parts.join('')}</svg>`
}

function skinSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
  <defs>
    <linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#f6d6bd"/>
      <stop offset="0.45" stop-color="#e5b491"/>
      <stop offset="0.75" stop-color="#c98f68"/>
      <stop offset="1" stop-color="#8d5a3c"/>
    </linearGradient>
    <radialGradient id="hi" cx="0.36" cy="0.34" r="0.32">
      <stop offset="0" stop-color="#fff4ea" stop-opacity="0.85"/>
      <stop offset="0.55" stop-color="#ffe9d8" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#ffe9d8" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="hi2" cx="0.72" cy="0.66" r="0.26">
      <stop offset="0" stop-color="#fff8f0" stop-opacity="0.55"/>
      <stop offset="1" stop-color="#fff8f0" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="sh" cx="0.85" cy="0.22" r="0.4">
      <stop offset="0" stop-color="#5a3520" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#5a3520" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#base)"/>
  <rect width="${W}" height="${H}" fill="url(#sh)"/>
  <rect width="${W}" height="${H}" fill="url(#hi)"/>
  <rect width="${W}" height="${H}" fill="url(#hi2)"/>
</svg>`
}

async function makeDetail() {
  const buf = Buffer.alloc(W * H * 3)
  const rnd = mulberry32(20261028)
  for (let i = 0; i < W * H; i++) {
    const h = rnd() * 360
    const s = 0.55 + rnd() * 0.45
    const v = 0.45 + rnd() * 0.55
    const c = v * s
    const xx = c * (1 - Math.abs(((h / 60) % 2) - 1))
    const m = v - c
    const seg = Math.floor(h / 60) % 6
    const rgb = [
      [c, xx, 0],
      [xx, c, 0],
      [0, c, xx],
      [0, xx, c],
      [xx, 0, c],
      [c, 0, xx]
    ][seg]
    buf[i * 3] = Math.round((rgb[0] + m) * 255)
    buf[i * 3 + 1] = Math.round((rgb[1] + m) * 255)
    buf[i * 3 + 2] = Math.round((rgb[2] + m) * 255)
  }
  return sharp(buf, { raw: { width: W, height: H, channels: 3 } }).blur(0.5).png({ compressionLevel: 9 }).toBuffer()
}

/** One pango line -> RGBA PNG buffer. */
async function pangoLine(text, colour, fontDesc, dpi = 72) {
  const markup = `<span foreground="${colour}" font_desc="${fontDesc}">${text}</span>`
  const buf = await sharp({ text: { text: markup, dpi, rgba: true } }).png().toBuffer()
  const m = await sharp(buf).metadata()
  return { buf, width: m.width ?? 0, height: m.height ?? 0 }
}

async function makeColortext() {
  const items = []
  let overflow = 0
  const put = async (text, colour, fontDesc, x, y) => {
    const line = await pangoLine(text, colour, fontDesc)
    if (line.width > W - x - 10) overflow++
    items.push({ input: line.buf, left: x, top: y })
    return line
  }

  await put('Framemark 色彩边缘测试 Coloured Text 0123456789', '#c00000', 'Arial Bold 34', 40, 34)
  await put('The quick brown fox jumps over the lazy dog.', '#0033cc', 'Arial 18', 40, 92)
  await put('the quick brown fox jumps over the lazy dog', '#008a3a', 'Arial 14', 40, 122)
  await put('THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG', '#8a6d00', 'Arial 11', 40, 146)
  await put('0123456789 AB1234567 — 红色小字 · 蓝色小字 · 绿色小字', '#a0006e', 'Microsoft YaHei 12', 40, 166)

  const blocks = [
    ['#c00000', 236],
    ['#0033cc', 356],
    ['#007a3a', 476],
    ['#7a00b0', 596]
  ]
  for (const [colour, y0] of blocks) {
    for (let i = 0; i < 4; i++) {
      await put(
        `Line ${i + 1} — 4:2:0 chroma bleed must show here: aaa iii mmm www 0123456789`,
        colour,
        `Arial ${11 + i * 2}`,
        40,
        y0 + i * 25
      )
    }
  }

  // Dense block of very small, tightly spaced coloured text (worst case).
  for (let r = 0; r < 7; r++) {
    for (let k = 0; k < 3; k++) {
      await put('abcdefghijklmnop 0123456789 IIIIII WWWWWW', ['#d00000', '#0022bb', '#006b2a'][k], 'Arial 10', 700, 262 + r * 16 + k * 4)
    }
  }

  const png = await sharp({ create: { width: W, height: H, channels: 3, background: '#ffffff' } })
    .composite(items)
    .flatten({ background: '#ffffff' })
    .removeAlpha()
    .png({ compressionLevel: 9 })
    .toBuffer()
  if (overflow) throw new Error(`${overflow} text runs overflow the canvas width`)
  return png
}

async function main() {
  fs.mkdirSync(FIX, { recursive: true })
  fs.mkdirSync(IMPORT_TIF, { recursive: true })

  const written = {}
  const emit = async (name, buf) => {
    const p = path.join(FIX, name)
    fs.writeFileSync(p, buf)
    const m = await sharp(buf).metadata()
    written[name] = { bytes: buf.length, width: m.width, height: m.height, format: m.format, channels: m.channels }
    console.log(`  ${name} ${m.width}x${m.height} ${m.format} ${buf.length} bytes`)
  }

  console.log('fixtures ->', FIX)
  await emit('bars.png', await sharp(Buffer.from(barsSvg())).flatten({ background: '#ffffff' }).removeAlpha().png({ compressionLevel: 9 }).toBuffer())
  await emit('colortext.png', await makeColortext())
  await emit('skin.png', await sharp(Buffer.from(skinSvg())).flatten({ background: '#ffffff' }).removeAlpha().png({ compressionLevel: 9 }).toBuffer())
  await emit('detail.png', await makeDetail())

  // Capability fixtures. photo.tif is 1000x700 so its dimensions are unique.
  const tif = await sharp(path.join(FIX, 'skin.png'))
    .resize({ width: 1000, height: 700, fit: 'cover', position: 'centre' })
    .tiff({ compression: 'lzw', predictor: 'horizontal' })
    .toBuffer()
  await emit('photo.tif', tif)
  fs.writeFileSync(path.join(IMPORT_TIF, 'photo.tif'), tif)

  const jpg = await sharp(path.join(FIX, 'detail.png')).jpeg({ quality: 92, chromaSubsampling: '4:4:4' }).toBuffer()
  await emit('photo.jpg', jpg)

  fs.writeFileSync(path.join(A2, 'out', 'fixtures.json'), JSON.stringify({ generatedAt: new Date().toISOString(), files: written }, null, 2))
  console.log(JSON.stringify(written, null, 2))
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
