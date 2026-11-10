// Temporary measurement helper: report bounding boxes of non-background content
// inside named rectangular regions of original previews.
const sharp = require('sharp')

const P = 'D:/border&watermark/framemark/src/renderer/src/assets/previews/'

async function load(file) {
  const img = sharp(P + file).ensureAlpha()
  const { data, info } = await img.raw().toBuffer({ resolveWithObject: true })
  return { data, w: info.width, h: info.height, ch: info.channels }
}

function bbox(img, x0, y0, x1, y1, pred) {
  const { data, w, ch } = img
  let minX = Infinity, minY = Infinity, maxX = -1, maxY = -1
  for (let y = y0; y < y1; y++) {
    for (let x = x0; x < x1; x++) {
      const i = (y * w + x) * ch
      if (pred(data[i], data[i + 1], data[i + 2], data[i + 3])) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
      }
    }
  }
  if (maxX < 0) return null
  return { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

const notBg = (thr) => (r, g, b, a) => a > 32 && (Math.abs(r - g) > thr || Math.abs(g - b) > thr || r < 235 || g < 235 || b < 235)
const whiteish = (r, g, b, a) => a > 60 && r > 225 && g > 225 && b > 225
const darkish = (r, g, b, a) => a > 60 && r < 130 && g < 130 && b < 130

;(async () => {
  const reports = []
  const add = (name, label, img, region, pred) => {
    const b = bbox(img, ...region, pred)
    if (!b) { console.log(`${name.padEnd(14)} ${label.padEnd(12)} none`); return }
    console.log(
      `${name.padEnd(14)} ${label.padEnd(12)} x=${b.x} y=${b.y} w=${b.w} h=${b.h}` +
      `  bottom=${b.y + b.h - 1}/${img.h} right=${b.x + b.w - 1}/${img.w}` +
      `  wPct=${((b.w / img.w) * 100).toFixed(2)} hPct=${((b.h / img.h) * 100).toFixed(2)}`
    )
    reports.push({ name, label, ...b })
  }

  // whiteborder1: matte margin + photo + wordmark
  {
    const img = await load('whiteborder1.webp')
    add('whiteborder1', 'photo', img, [0, 0, img.w, img.h], notBg(12))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(12))
    if (pb) add('whiteborder1', 'brand', img, [pb.x, pb.y + pb.h - 90, pb.x + pb.w, pb.y + pb.h], whiteish)
  }
  // person1: photo + footer brand
  {
    const img = await load('person1.webp')
    add('person1', 'photo', img, [0, 0, img.w, img.h], notBg(12))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(12))
    if (pb) add('person1', 'brand', img, [0, pb.y + pb.h, img.w, img.h], darkish)
  }
  // person2: header + photo + footer
  {
    const img = await load('person2.webp')
    add('person2', 'photo', img, [0, 0, img.w, img.h], notBg(14))
    add('person2', 'header', img, [0, 0, img.w, 120], darkish)
    add('person2', 'footer', img, [0, img.h - 140, img.w, img.h], darkish)
  }
  // person3: photo + inner white exif + footer brand
  {
    const img = await load('person3.webp')
    add('person3', 'photo', img, [0, 0, img.w, img.h], notBg(12))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(12))
    if (pb) add('person3', 'innerText', img, [pb.x, pb.y + pb.h - 90, pb.x + pb.w, pb.y + pb.h], whiteish)
    if (pb) add('person3', 'brand', img, [0, pb.y + pb.h, img.w, img.h], darkish)
  }
  // person4: photo + inner white brand/exif + chip
  {
    const img = await load('person4.webp')
    add('person4', 'photo', img, [0, 0, img.w, img.h], notBg(12))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(12))
    if (pb) add('person4', 'brand', img, [pb.x, pb.y + pb.h - 120, pb.x + pb.w, pb.y + pb.h], whiteish)
    if (pb) add('person4', 'chip', img, [0, 0, img.w, pb.y + 70], darkish)
  }
  // person5: avatar + footer text
  {
    const img = await load('person5.webp')
    add('person5', 'photo', img, [0, 0, img.w, img.h], notBg(12))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(12))
    if (pb) {
      add('person5', 'avatar', img, [0, pb.y + pb.h, 120, img.h], notBg(10))
      add('person5', 'footText', img, [110, pb.y + pb.h, img.w, img.h], darkish)
    }
  }
  // card1 / card2 logo footer
  for (const name of ['card1', 'card2', 'card3', 'card4']) {
    const img = await load(name + '.webp')
    add(name, 'photo', img, [0, 0, img.w, img.h], notBg(10))
    const pb = bbox(img, 0, 0, img.w, img.h, notBg(10))
    if (pb) add(name, 'footer', img, [0, pb.y + pb.h, img.w, img.h], darkish)
  }
  console.log('---json---')
  console.log(JSON.stringify(reports))
})()
