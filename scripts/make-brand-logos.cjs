const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

if (!process.argv.includes('--force-placeholders')) {
  console.error('This script generates Arial placeholder wordmarks and would overwrite the real logo assets.')
  console.error('Use scripts/import-real-brand-logos.cjs instead. Pass --force-placeholders only for an explicit fallback build.')
  process.exit(1)
}

const outDir = path.resolve(__dirname, '../src/renderer/src/assets/logos')
const brands = [
  { key: 'contax', text: 'CONTAX', cn: '康泰时', group: 'camera', font: 'Arial', weight: 700, size: 160, spacing: 22 },
  { key: 'minolta', text: 'MINOLTA', cn: '美能达', group: 'camera', font: 'Arial', weight: 700, size: 160, spacing: 8 },
  { key: 'konica', text: 'KONICA', cn: '柯尼卡', group: 'camera', font: 'Arial', weight: 700, size: 160, spacing: 16 },
  { key: 'konica_minolta', text: 'KONICA MINOLTA', cn: '柯尼卡美能达', group: 'camera', font: 'Arial', weight: 700, size: 112, spacing: 8 },
  { key: 'yashica', text: 'YASHICA', cn: '雅西卡', group: 'camera', font: 'Arial', weight: 600, size: 150, spacing: 18 },
  { key: 'rollei', text: 'ROLLEI', cn: '禄来', group: 'camera', font: 'Georgia', weight: 700, size: 160, spacing: 10 },
  { key: 'voigtlander', text: 'Voigtländer', cn: '福伦达', group: 'camera', font: 'Georgia', weight: 600, size: 130, spacing: 1, italic: true },
  { key: 'mamiya', text: 'MAMIYA', cn: '玛米亚', group: 'camera', font: 'Arial', weight: 600, size: 150, spacing: 18 },
  { key: 'bronica', text: 'BRONICA', cn: '勃朗尼卡', group: 'camera', font: 'Arial', weight: 500, size: 150, spacing: 18 },
  { key: 'kodak', text: 'Kodak', cn: '柯达', group: 'camera', font: 'Arial', weight: 800, size: 170, spacing: 4, color: '#F2B01E' },
  { key: 'polaroid', text: 'POLAROID', cn: '宝丽来', group: 'camera', font: 'Arial', weight: 700, size: 140, spacing: 16 },
  { key: 'agfa', text: 'AGFA', cn: '爱克发', group: 'camera', font: 'Arial', weight: 800, size: 180, spacing: 22, color: '#E30613' },
  { key: 'praktica', text: 'PRAKTICA', cn: '百佳', group: 'camera', font: 'Arial', weight: 700, size: 140, spacing: 12 },
  { key: 'exakta', text: 'EXAKTA', cn: '爱克山泰', group: 'camera', font: 'Arial', weight: 600, size: 160, spacing: 18 },
  { key: 'minox', text: 'MINOX', cn: '美乐时', group: 'camera', font: 'Arial', weight: 700, size: 160, spacing: 20 },
  { key: 'sinar', text: 'SINAR', cn: '仙娜', group: 'camera', font: 'Arial', weight: 600, size: 160, spacing: 22 },
  { key: 'linhof', text: 'LINHOF', cn: '林哈夫', group: 'camera', font: 'Georgia', weight: 700, size: 150, spacing: 12 },
  { key: 'alpa', text: 'ALPA', cn: '阿尔帕', group: 'camera', font: 'Arial', weight: 800, size: 180, spacing: 26 },
  { key: 'zenit', text: 'ZENIT', cn: '泽尼特', group: 'camera', font: 'Arial', weight: 700, size: 170, spacing: 20 },
  { key: 'lomo', text: 'LOMO', cn: '乐魔', group: 'camera', font: 'Arial', weight: 800, size: 180, spacing: 26 },
  { key: 'seagull', text: 'SEAGULL', cn: '海鸥', group: 'camera', font: 'Arial', weight: 600, size: 150, spacing: 14 },
  { key: 'casio', text: 'CASIO', cn: '卡西欧', group: 'camera', font: 'Arial', weight: 700, size: 160, spacing: 18 },
  { key: 'cosina', text: 'COSINA', cn: '确善能', group: 'camera', font: 'Arial', weight: 600, size: 150, spacing: 18 },
  { key: 'horseman', text: 'HORSEMAN', cn: '骑士', group: 'camera', font: 'Arial', weight: 600, size: 130, spacing: 12 },
  { key: 'graflex', text: 'GRAFLEX', cn: '格拉菲', group: 'camera', font: 'Arial', weight: 600, size: 140, spacing: 14 },
  { key: 'sigma', text: 'SIGMA', cn: '适马', group: 'camera', font: 'Arial', weight: 800, size: 160, spacing: 14 },
  { key: 'tamron', text: 'TAMRON', cn: '腾龙', group: 'camera', font: 'Arial', weight: 700, size: 150, spacing: 14 },
  { key: 'tokina', text: 'TOKINA', cn: '图丽', group: 'camera', font: 'Arial', weight: 600, size: 150, spacing: 18 },
  { key: 'samyang', text: 'SAMYANG', cn: '森养', group: 'camera', font: 'Arial', weight: 600, size: 140, spacing: 16 },
  { key: 'phaseone', text: 'PHASE ONE', cn: '飞思', group: 'camera', font: 'Arial', weight: 600, size: 140, spacing: 10 },
  { key: 'gopro', text: 'GoPro', cn: 'GoPro', group: 'camera', font: 'Arial', weight: 800, size: 170, spacing: 0, italic: true },
  { key: 'arri', text: 'ARRI', cn: '阿莱', group: 'camera', font: 'Arial', weight: 700, size: 180, spacing: 26 },
  { key: 'red', text: 'RED', cn: 'RED', group: 'camera', font: 'Arial', weight: 900, size: 200, spacing: 34, color: '#E4002B' },
  { key: 'nokia', text: 'NOKIA', cn: '诺基亚', group: 'phone', font: 'Arial', weight: 800, size: 160, spacing: 10 },
  { key: 'motorola', text: 'motorola', cn: '摩托罗拉', group: 'phone', font: 'Arial', weight: 700, size: 150, spacing: 2 },
  { key: 'google', text: 'Google Pixel', cn: '谷歌 Pixel', group: 'phone', font: 'Arial', weight: 600, size: 140, spacing: 0 },
  { key: 'realme', text: 'realme', cn: '真我', group: 'phone', font: 'Arial', weight: 800, size: 170, spacing: 1 },
  { key: 'nothing', text: 'Nothing', cn: 'Nothing', group: 'phone', font: 'Arial', weight: 600, size: 160, spacing: 3 },
  { key: 'nubia', text: 'nubia', cn: '努比亚', group: 'phone', font: 'Arial', weight: 700, size: 170, spacing: 3 }
]

function svgFor(b, fill) {
  const style = `font-family="${b.font}, Arial, sans-serif" font-weight="${b.weight}" font-size="${b.size}" letter-spacing="${b.spacing}" ${b.italic ? 'font-style="italic"' : ''}`
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="400"><text x="800" y="200" text-anchor="middle" dominant-baseline="central" ${style} fill="${fill}">${b.text}</text></svg>`
}

async function render(b, colorName, fill) {
  const trimmed = await sharp(Buffer.from(svgFor(b, fill))).trim().png().toBuffer()
  const meta = await sharp(trimmed).metadata()
  const pad = 10
  const padded = await sharp(trimmed).extend({ top: pad, bottom: pad, left: pad, right: pad, background: { r: 0, g: 0, b: 0, alpha: 0 } }).webp({ quality: 92, lossless: true }).toBuffer()
  fs.writeFileSync(path.join(outDir, `${b.key}_${colorName}.webp`), padded)
  return { key: b.key, colorName, width: (meta.width || 0) + pad * 2, height: (meta.height || 0) + pad * 2 }
}

async function main() {
  const rendered = []
  for (const b of brands) {
    rendered.push(await render(b, 'black', '#111111'))
    rendered.push(await render(b, 'white', '#FFFFFF'))
    if (b.key === 'kodak') rendered.push(await render(b, 'yellow', '#F2B01E'))
    if (b.key === 'agfa') rendered.push(await render(b, 'red', '#E30613'))
    if (b.key === 'red') rendered.push(await render(b, 'red', '#E4002B'))
  }
  fs.writeFileSync(path.join(outDir, '..', '..', '..', '..', '_brand_render_report.json'), JSON.stringify(rendered, null, 2))

  const tileW = 300
  const tileH = 130
  const cols = 4
  const sheet = sharp({ create: { width: cols * tileW, height: Math.ceil(rendered.length / cols) * tileH, channels: 3, background: '#ffffff' } })
  const comps = []
  for (let i = 0; i < rendered.length; i++) {
    const r = rendered[i]
    const file = path.join(outDir, `${r.key}_${r.colorName}.webp`)
    const bg = r.colorName === 'white' ? '#141416' : '#f5f1e8'
    const logo = await sharp(file).resize({ width: tileW - 28, height: tileH - 30, fit: 'inside' }).png().toBuffer()
    const m = await sharp(logo).metadata()
    const cellX = (i % cols) * tileW
    const cellY = Math.floor(i / cols) * tileH
    comps.push({ input: { create: { width: tileW, height: tileH, channels: 3, background: bg } }, left: cellX, top: cellY })
    comps.push({ input: logo, left: cellX + Math.round((tileW - (m.width || 0)) / 2), top: cellY + Math.round((tileH - 30 - (m.height || 0)) / 2) + 4 })
    const label = Buffer.from(`<svg width="${tileW}" height="22"><text x="8" y="16" font-family="Arial" font-size="11" fill="${r.colorName === 'white' ? '#ddd' : '#555'}">${r.key} · ${r.colorName}</text></svg>`)
    comps.push({ input: label, left: cellX, top: cellY + tileH - 22 })
  }
  await sheet.composite(comps).jpeg({ quality: 90 }).toFile('D:/border&watermark/_research/docs/new-brand-logos.jpg')
  console.log('rendered', rendered.length)
}
main().catch((e) => { console.error(e); process.exit(1) })
