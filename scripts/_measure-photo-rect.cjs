// Measure photo rect / footer / footer-content bbox for reference previews and our exports.
const sharp = require('sharp')
const path = require('node:path')

const REF = 'D:/border&watermark/framemark/src/renderer/src/assets/previews'
const OURS = 'D:/border&watermark/_research/ours-4x3'

async function load(file) {
  const { data, info } = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data, w: info.width, h: info.height, ch: info.channels }
}
const px = (img, x, y) => {
  const i = (y * img.w + x) * img.ch
  return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}
const notWhite = (p) => p[3] > 16 && Math.min(p[0], p[1], p[2]) < 246
const inkish = (p) => p[3] > 60 && Math.min(p[0], p[1], p[2]) < 235

function bbox(img, x0, y0, x1, y1, pred) {
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    if (pred(px(img, x, y))) {
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

function measure(img) {
  const rows = new Array(img.h).fill(0)
  const cols = new Array(img.w).fill(0)
  for (let y = 0; y < img.h; y++) {
    for (let x = 0; x < img.w; x++) {
      if (notWhite(px(img, x, y))) { rows[y]++; cols[x]++ }
    }
  }
  // photo columns: cover >30% of image height
  let left = 0, right = img.w - 1
  while (left < img.w && cols[left] < img.h * 0.3) left++
  while (right > left && cols[right] < img.h * 0.3) right--
  // photo rows within those columns
  let top = 0, bottom = img.h - 1
  while (top < img.h && rows[top] < (right - left + 1) * 0.3) top++
  while (bottom > top && rows[bottom] < (right - left + 1) * 0.3) bottom--
  const footer = img.h - 1 - bottom
  const content = footer > 4 ? bbox(img, 0, bottom + 1, img.w - 1, img.h - 1, inkish) : null
  return {
    canvas: [img.w, img.h],
    photo: { x: left, y: top, w: right - left + 1, h: bottom - top + 1 },
    margins: { L: left, T: top, R: img.w - 1 - right, B: footer },
    content
  }
}

const pct = (v, d) => (100 * v / d).toFixed(2)

async function main() {
  const which = process.argv[2] || 'refs'
  const files = process.argv.slice(3)
  const base = which === 'ours' ? OURS : REF
  const list = files.length ? files : which === 'ours'
    ? ['4x3-纯白 01.png', '4x3-纯白 02.png', '4x3-纯白 03.png', '4x3-米白 04.png', '4x3-卡片 01.png', '4x3-卡片 02.png', '4x3-卡片 03.png', '4x3-卡片 04.png', '4x3-人物 01.png', '4x3-人物 02.png', '4x3-人物 03.png', '4x3-人物 04.png', '4x3-人物 05.png']
    : ['whiteborder1.webp', 'whiteborder2.webp', 'whiteborder3.webp', 'whiteborder4.webp', 'card1.webp', 'card2.webp', 'card3.webp', 'card4.webp', 'person1.webp', 'person2.webp', 'person3.webp', 'person4.webp', 'person5.webp']
  for (const f of list) {
    const img = await load(path.join(base, f))
    const m = measure(img)
    const S = Math.min(m.photo.w, m.photo.h)
    const c = m.content
    console.log(
      f.padEnd(22),
      `${m.canvas[0]}x${m.canvas[1]}`,
      `photo ${m.photo.x},${m.photo.y} ${m.photo.w}x${m.photo.h} ar=${(m.photo.w / m.photo.h).toFixed(3)}`,
      `margins L${m.margins.L} T${m.margins.T} R${m.margins.R} B${m.margins.B}`,
      `S=${S}`,
      `m%/S ${pct(m.margins.L, S)}/${pct(m.margins.T, S)}/${pct(m.margins.R, S)}/${pct(m.margins.B, S)}`,
      c ? `content x${c.x},y${c.y} ${c.w}x${c.h} centerX%S=${pct(c.x + c.w / 2 - m.photo.x, S)} top%S=${pct(c.y - (m.photo.y + m.photo.h), S)} w%S=${pct(c.w, S)} h%S=${pct(c.h, S)}` : 'content none'
    )
  }
}
main()
