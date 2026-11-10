const sharp = require('sharp')
const P = 'D:/border&watermark/framemark/src/renderer/src/assets/previews/'
const FILES = process.argv.slice(2)
if (!FILES.length) FILES.push('whiteborder1.webp', 'person1.webp', 'person2.webp', 'person3.webp', 'person4.webp', 'person5.webp', 'card1.webp', 'card2.webp', 'card3.webp', 'card4.webp')

async function load(file) {
  const { data, info } = await sharp(P + file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data, w: info.width, h: info.height, ch: info.channels }
}
function px(img, x, y) {
  const i = (y * img.w + x) * img.ch
  return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}
function isColor(p) {
  const mx = Math.max(p[0], p[1], p[2]), mn = Math.min(p[0], p[1], p[2])
  return p[3] > 100 && mx - mn > 28
}
function isInk(p, t = 90) {
  return p[3] > 100 && 255 - Math.min(p[0], p[1], p[2]) > t
}
function isWhite(p) {
  return p[3] > 100 && Math.min(p[0], p[1], p[2]) > 218
}
function bbox(img, pred, x0 = 0, y0 = 0, x1 = img.w - 1, y1 = img.h - 1) {
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
// count color pixels per row/col to find photo edges robustly
function photoBounds(img) {
  const rows = new Array(img.h).fill(0)
  const cols = new Array(img.w).fill(0)
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    if (isColor(px(img, x, y))) { rows[y]++; cols[x]++ }
  }
  const minRow = rows.findIndex((v) => v > img.w * 0.1)
  let maxRow = img.h - 1
  while (maxRow > 0 && rows[maxRow] <= img.w * 0.1) maxRow--
  const minCol = cols.findIndex((v) => v > img.h * 0.1)
  let maxCol = img.w - 1
  while (maxCol > 0 && cols[maxCol] <= img.h * 0.1) maxCol--
  return { x: minCol, y: minRow, w: maxCol - minCol + 1, h: maxRow - minRow + 1 }
}
const pctf = (v, d) => ((v / d) * 100).toFixed(2)

;(async () => {
  for (const file of FILES) {
    const img = await load(file)
    const pb = photoBounds(img)
    const S = Math.min(pb.w, pb.h)
    console.log(`\n=== ${file} canvas=${img.w}x${img.h} photo=${pb.x},${pb.y} ${pb.w}x${pb.h} S=${S}`)
    console.log(`  margins L=${pb.x} T=${pb.y} R=${img.w - pb.x - pb.w} B=${img.h - pb.y - pb.h}`)
    console.log(`  margin%/S L=${pctf(pb.x, S)} T=${pctf(pb.y, S)} R=${pctf(img.w - pb.x - pb.w, S)} B=${pctf(img.h - pb.y - pb.h, S)}`)
    console.log(`  photoW/canvas=${pctf(pb.w, img.w)} photoH/canvas=${pctf(pb.h, img.h)}`)
    // text bands under photo (dark text on white) and inside photo (white text)
    const below = bbox(img, (p) => isInk(p, 80), 0, pb.y + pb.h, img.w - 1, img.h - 1)
    console.log('  inkBelowPhoto', JSON.stringify(below))
    if (below) console.log(`    -> w/S=${pctf(below.w, S)} h/S=${pctf(below.h, S)} topGap/S=${pctf(below.y - (pb.y + pb.h), S)} centerY/S=${pctf(below.y + below.h / 2 - (pb.y + pb.h), S)}`)
    const inner = bbox(img, isWhite, pb.x, pb.y + Math.floor(pb.h * 0.55), pb.x + pb.w - 1, pb.y + pb.h - 1)
    console.log('  whiteInsideBottom', JSON.stringify(inner))
    if (inner) console.log(`    -> w/S=${pctf(inner.w, S)} h/S=${pctf(inner.h, S)} bottomGap/S=${pctf(pb.y + pb.h - (inner.y + inner.h), S)}`)
    const top = bbox(img, (p) => isInk(p, 60) || isWhite(p), pb.x, 0, pb.x + pb.w - 1, pb.y - 1)
    console.log('  header ink/white', JSON.stringify(top))
    if (top) console.log(`    -> headerH/S=${pctf(pb.y, S)} elemsH/S=${pctf(top.h, S)}`)
  }
})()
