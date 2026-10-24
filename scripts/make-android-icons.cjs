const sharp = require('sharp')
const path = require('node:path')
const fs = require('node:fs')

const res = path.resolve(__dirname, '../android/app/src/main/res')
const gold = '#D8B477'

function markSvg(size, { bg = false, round = false, safe = 0.66 } = {}) {
  const s = size
  const margin = s * (1 - safe) / 2
  const w = s - margin * 2
  const corner = w * 0.24
  const stroke = Math.max(2, s * 0.022)
  const inset = w * 0.31
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="0 0 ${s} ${s}">
    ${bg ? (round ? `<circle cx="${s / 2}" cy="${s / 2}" r="${s / 2}" fill="#0C0C0D"/>` : `<rect width="${s}" height="${s}" rx="${s * 0.235}" fill="#0C0C0D"/>`) : ''}
    <g fill="none" stroke="${gold}" stroke-width="${stroke}" stroke-linecap="round" opacity="0.96">
      <path d="M ${margin} ${margin + corner} V ${margin} H ${margin + corner}"/>
      <path d="M ${margin + w - corner} ${margin} H ${margin + w} V ${margin + corner}"/>
      <path d="M ${margin + w} ${margin + w - corner} V ${margin + w} H ${margin + w - corner}"/>
      <path d="M ${margin + corner} ${margin + w} H ${margin} V ${margin + w - corner}"/>
    </g>
    <rect x="${margin + inset}" y="${margin + inset}" width="${w - inset * 2}" height="${w - inset * 2}" rx="${w * 0.055}" fill="none" stroke="${gold}" stroke-width="${Math.max(1.4, stroke * 0.55)}" opacity="0.38"/>
  </svg>`
}

async function main() {
  const legacy = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 }
  const foreground = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 }
  for (const [d, size] of Object.entries(legacy)) {
    const dir = path.join(res, `mipmap-${d}`)
    await sharp(Buffer.from(markSvg(size * 4, { bg: true }))).resize(size, size).png().toFile(path.join(dir, 'ic_launcher.png'))
    await sharp(Buffer.from(markSvg(size * 4, { bg: true, round: true }))).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_round.png'))
  }
  for (const [d, size] of Object.entries(foreground)) {
    const dir = path.join(res, `mipmap-${d}`)
    await sharp(Buffer.from(markSvg(size * 4, { safe: 0.56 }))).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_foreground.png'))
  }
  const splash = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="2732" height="2732">
    <rect width="2732" height="2732" fill="#0C0C0D"/>
    ${markSvg(2732, { safe: 0.34, bg: false })}
    <text x="1366" y="1870" text-anchor="middle" font-family="Arial, Helvetica, sans-serif" font-size="92" letter-spacing="26" fill="#D8B477" opacity="0.82">FRAMEMARK</text>
  </svg>`)
  fs.writeFileSync(path.join(res, 'drawable/splash.png'), await sharp(splash).resize(1366, 1366).png().toBuffer())
  for (const dir of ['drawable-port-hdpi','drawable-port-mdpi','drawable-port-xhdpi','drawable-port-xxhdpi','drawable-port-xxxhdpi','drawable-land-hdpi','drawable-land-mdpi','drawable-land-xhdpi','drawable-land-xxhdpi','drawable-land-xxxhdpi']) {
    fs.writeFileSync(path.join(res, dir, 'splash.png'), await sharp(splash).resize(1366, 1366).png().toBuffer())
  }
}
main().catch((e) => { console.error(e); process.exit(1) })
