/**
 * S4 — fixtures for the Android foreground-service gates.
 *
 * Small-but-not-tiny JPEGs (1400x933, ~200-400 KB) so a 15-item gate-3 batch and
 * a 40+ item gate-4 batch are realistic without pushing 100 MB through CDP.
 * Deterministic: same seed -> same bytes -> the MediaStore count delta is the
 * only moving part.
 *
 * Usage: node scripts/_s4-make-fixtures.cjs [count]
 */
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const root = path.resolve(__dirname, '..')
const outDir = path.join(root, '..', '_research', 's4', 'fixtures')
const COUNT = Number(process.argv[2] || 48)
const WIDTH = Number(process.argv[3] || 1400)
const HEIGHT = Number(process.argv[4] || 933)
const PREFIX = process.argv[5] || 's4-fixture'
const QUALITY = Number(process.argv[6] || 86)

function svg(i) {
  const hue = (i * 37) % 360
  const hue2 = (hue + 128) % 360
  const band = 60 + (i % 7) * 18
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}">
  <defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="hsl(${hue},42%,24%)"/>
      <stop offset="0.55" stop-color="hsl(${hue},38%,46%)"/>
      <stop offset="1" stop-color="hsl(${hue2},30%,22%)"/>
    </linearGradient>
    <radialGradient id="sun" cx="0.72" cy="0.24" r="0.35">
      <stop offset="0" stop-color="hsl(${hue2},70%,86%)" stop-opacity="0.85"/>
      <stop offset="1" stop-color="hsl(${hue2},70%,86%)" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="${WIDTH}" height="${HEIGHT}" fill="url(#sky)"/>
  <rect width="${WIDTH}" height="${HEIGHT}" fill="url(#sun)"/>
  <path d="M0 ${band * 6} L260 ${band * 3} L430 ${band * 5} L640 ${band * 2.4} L840 ${band * 4.6} L1080 ${band * 2.8} L1400 ${band * 5.4} V${HEIGHT} H0 Z"
        fill="hsl(${hue},28%,13%)" opacity="0.9"/>
  <path d="M0 ${band * 7.2} L300 ${band * 5.6} L560 ${band * 7} L820 ${band * 5.1} L1120 ${band * 6.8} L1400 ${band * 5.9} V${HEIGHT} H0 Z"
        fill="hsl(${hue2},24%,9%)" opacity="0.95"/>
  <circle cx="${120 + (i % 9) * 130}" cy="${140 + (i % 5) * 60}" r="${26 + (i % 4) * 8}" fill="#f8f4ea" opacity="0.8"/>
  <text x="70" y="860" font-family="sans-serif" font-size="54" fill="#f2ede1" opacity="0.9">S4 · ${String(i + 1).padStart(2, '0')}</text>
  <text x="70" y="905" font-family="sans-serif" font-size="28" fill="#d8d2c4" opacity="0.75">${WIDTH} × ${HEIGHT} · foreground gate fixture</text>
</svg>`
}

async function main() {
  fs.mkdirSync(outDir, { recursive: true })
  const made = []
  for (let i = 0; i < COUNT; i++) {
    const file = path.join(outDir, `${PREFIX}-${String(i + 1).padStart(3, '0')}.jpg`)
    await sharp(Buffer.from(svg(i)))
      .jpeg({ quality: QUALITY, mozjpeg: true })
      .toFile(file)
    made.push(file)
  }
  const total = made.reduce((sum, f) => sum + fs.statSync(f).size, 0)
  console.log(`fixtures: ${made.length} files, ${(total / 1024 / 1024).toFixed(2)} MB, dir=${outDir}`)
  console.log(`first=${path.basename(made[0])} last=${path.basename(made[made.length - 1])}`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
