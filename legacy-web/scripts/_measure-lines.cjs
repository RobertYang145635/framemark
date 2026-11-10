const sharp = require('sharp')
const P = 'D:/border&watermark/framemark/src/renderer/src/assets/previews/'

async function load(file) {
  const { data, info } = await sharp(P + file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data, w: info.width, h: info.height, ch: info.channels }
}
function px(img, x, y) {
  const i = (y * img.w + x) * img.ch
  return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}
const isInk = (p, t = 40) => p[3] > 40 && (255 - Math.min(p[0], p[1], p[2]) > t)

function runsOnRow(img, y, t = 40) {
  const runs = []
  let start = null
  for (let x = 0; x < img.w; x++) {
    const ink = isInk(px(img, x, y), t)
    if (ink && start === null) start = x
    if (!ink && start !== null) { runs.push([start, x - 1]); start = null }
  }
  if (start !== null) runs.push([start, img.w - 1])
  return runs.filter((r) => r[1] - r[0] >= 0)
}
function runsOnCol(img, x, t = 40) {
  const runs = []
  let start = null
  for (let y = 0; y < img.h; y++) {
    const ink = isInk(px(img, x, y), t)
    if (ink && start === null) start = y
    if (!ink && start !== null) { runs.push([start, y - 1]); start = null }
  }
  if (start !== null) runs.push([start, img.h - 1])
  return runs
}
function bbox(img, x0, y0, x1, y1, t = 40) {
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    if (isInk(px(img, x, y), t)) {
      if (x < minX) minX = x; if (x > maxX) maxX = x
      if (y < minY) minY = y; if (y > maxY) maxY = y
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

;(async () => {
  const cases = [
    { file: 'person1.webp', rows: [10, 40, 100, 400, 700, 770, 800, 840], cols: [512, 100], regions: [['brand', 300, 760, 740, 840], ['header', 300, 0, 760, 70]] },
    { file: 'person2.webp', rows: [60, 130, 400, 900], cols: [600], regions: [['header', 0, 0, 1200, 130], ['footer', 0, 900, 1200, 1044]] },
    { file: 'person3.webp', rows: [40, 100, 400, 700, 780, 820], cols: [512], regions: [['chip', 300, 0, 724, 90], ['exif', 300, 700, 724, 810], ['brand', 300, 810, 724, 843]] },
    { file: 'person4.webp', rows: [30, 70, 100, 400, 650, 700, 760], cols: [512], regions: [['chip', 300, 0, 724, 90], ['brand', 300, 640, 724, 720], ['exif', 300, 700, 724, 780]] },
    { file: 'person5.webp', rows: [30, 100, 400, 780], cols: [60, 300], regions: [['avatar', 0, 810, 110, 831], ['footText', 110, 810, 1024, 831]] },
    { file: 'whiteborder1.webp', rows: [29, 30, 100, 400, 705, 745, 746, 747], cols: [33, 512, 1020], regions: [['brand', 300, 660, 724, 745]] },
    { file: 'card1.webp', rows: [500, 995, 1000, 1020], cols: [600], regions: [['footer', 0, 999, 1200, 1026]] },
    { file: 'card2.webp', rows: [400, 856, 860, 875], cols: [512], regions: [['footer', 0, 856, 1024, 876]] },
    { file: 'card3.webp', rows: [400, 845, 850, 875], cols: [512], regions: [['footer', 0, 845, 1024, 876]] },
    { file: 'card4.webp', rows: [400, 855, 860, 875], cols: [512], regions: [['footer', 0, 855, 1024, 876]] }
  ]
  for (const c of cases) {
    const img = await load(c.file)
    console.log('===', c.file, img.w + 'x' + img.h)
    for (const y of c.rows) console.log(' row', y, JSON.stringify(runsOnRow(img, y).slice(0, 8)))
    for (const x of c.cols) console.log(' col', x, JSON.stringify(runsOnCol(img, x).slice(0, 8)))
    for (const r of c.regions) console.log(' bbox', r[0], JSON.stringify(bbox(img, r[1], r[2], r[3], r[4])))
  }
})()
