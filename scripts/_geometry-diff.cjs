// Normalized geometry diff between reference previews and our 4:3 exports.
const sharp = require('sharp')
const path = require('node:path')

const REF_DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/previews'
const OUR_DIR = 'D:/border&watermark/_research/p0-4x3'

// photo rect in reference preview (measured from hard edges/gradients)
const REF = {
  'white-1': { file: 'whiteborder1.webp', photo: [31, 25, 962, 721] },
  'white-2': { file: 'whiteborder2.webp', photo: [31, 24, 962, 723] },
  'white-3': { file: 'whiteborder3.webp', photo: [31, 25, 963, 722] },
  'white-4': { file: 'whiteborder4.webp', photo: [16, 13, 992, 746] },
  'card-1': { file: 'card1.webp', photo: [10, 10, 1180, 886] },
  'card-2': { file: 'card2.webp', photo: [9, 8, 1006, 784] },
  'card-3': { file: 'card3.webp', photo: [9, 8, 1006, 793] },
  'card-4': { file: 'card4.webp', photo: [11, 12, 1002, 780] },
  'person-1': { file: 'person1.webp', photo: [30, 84, 965, 726] },
  'person-2': { file: 'person2.webp', photo: [41, 104, 1116, 837] },
  'person-3': { file: 'person3.webp', photo: [0, 0, 1024, 758] },
  'person-4': { file: 'person4.webp', photo: [0, 0, 1024, 782] },
  'person-5': { file: 'person5.webp', photo: [22, 21, 980, 737] }
}
const OURS = {
  'white-1': { file: '4x3-纯白 01.png', photo: [86, 69, 2667, 2000] },
  'white-2': { dir: 'D:/border&watermark/_research/p0-white2-fix', file: '4x3-纯白 02.png', photo: [86, 66, 2667, 2000] },
  'white-3': { file: '4x3-纯白 03.png', photo: [86, 69, 2667, 2000] },
  'white-4': { file: '4x3-米白 04.png', photo: [43, 35, 2667, 2000] },
  'card-1': { file: '4x3-卡片 01.png', photo: [23, 23, 2667, 2000] },
  'card-2': { file: '4x3-卡片 02.png', photo: [22, 22, 2667, 2000] },
  'card-3': { file: '4x3-卡片 03.png', photo: [23, 23, 2667, 2000] },
  'card-4': { file: '4x3-卡片 04.png', photo: [28, 28, 2667, 2000] },
  'person-1': { file: '4x3-人物 01.png', photo: [83, 231, 2667, 2000] },
  'person-2': { file: '4x3-人物 02.png', photo: [98, 249, 2667, 2000] },
  'person-3': { file: '4x3-人物 03.png', photo: [0, 0, 2667, 2000] },
  'person-4': { file: '4x3-人物 04.png', photo: [0, 0, 2667, 2000] },
  'person-5': { file: '4x3-人物 05.png', photo: [60, 60, 2667, 2000] }
}

const lum = (r, g, b) => 0.299 * r + 0.7 * g + 0.114 * b
const dark = (r, g, b, a) => a > 60 && lum(r, g, b) < 150
const bright = (r, g, b, a) => a > 60 && Math.min(r, g, b) > 175
const notWhite = (r, g, b, a) => a > 30 && Math.min(r, g, b) < 240

async function load(dir, file) {
  const { data, info } = await sharp(path.join(dir, file)).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data, w: info.width, h: info.height, ch: info.channels }
}
const px = (img, x, y) => {
  const i = (y * img.w + x) * img.ch
  return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3]]
}
function bboxBrightText(img, x0, y0, x1, y1) {
  x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0))
  x1 = Math.min(img.w - 1, Math.ceil(x1)); y1 = Math.min(img.h - 1, Math.ceil(y1))
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    const p = px(img, x, y)
    if (!(p[3] > 60 && Math.min(p[0], p[1], p[2]) > 190)) continue
    let darkNear = false
    for (let dy = -4; dy <= 4 && !darkNear; dy++) for (let dx = -4; dx <= 4; dx++) {
      const xx = x + dx, yy = y + dy
      if (xx < 0 || yy < 0 || xx >= img.w || yy >= img.h) continue
      const q = px(img, xx, yy)
      if (q[3] > 60 && lum(q[0], q[1], q[2]) < 110) { darkNear = true; break }
    }
    if (!darkNear) continue
    if (x < minX) minX = x
    if (x > maxX) maxX = x
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

function bbox(img, x0, y0, x1, y1, pred) {
  x0 = Math.max(0, Math.floor(x0)); y0 = Math.max(0, Math.floor(y0))
  x1 = Math.min(img.w - 1, Math.ceil(x1)); y1 = Math.min(img.h - 1, Math.ceil(y1))
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    if (pred(...px(img, x, y))) {
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

// area: 'footer' (below photo), 'header' (above photo), 'photo' (rel fractions inside photo)
const JOBS = {
  'white-1': [{ label: 'brand', area: 'photo', rel: [0.3, 0.88, 0.7, 0.995], pred: bright }],
  'white-2': [
    { label: 'brand', area: 'photo', rel: [0.44, 0.885, 0.56, 0.925], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.35, 0.936, 0.65, 0.972], pred: 'brightText' }
  ],
  'white-3': [{ label: 'exif', area: 'photo', rel: [0.2, 0.92, 0.8, 0.995], pred: 'brightText' }],
  'white-4': [],
  'card-1': [{ label: 'content', area: 'footer', pred: dark }],
  'card-2': [{ label: 'content', area: 'footer', pred: dark }],
  'card-3': [{ label: 'content', area: 'footer', pred: dark }],
  'card-4': [{ label: 'content', area: 'footer', pred: dark }],
  'person-1': [
    { label: 'header', area: 'header', pred: dark },
    { label: 'brand', area: 'footer', pred: dark }
  ],
  'person-2': [
    { label: 'header', area: 'header', pred: dark },
    { label: 'exif', area: 'photo', rel: [0.2, 0.93, 0.8, 0.995], pred: 'brightText' },
    { label: 'brand', area: 'footer', pred: dark }
  ],
  'person-3': [
    { label: 'chip', area: 'photo', rel: [0.3, 0.02, 0.7, 0.15], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.2, 0.92, 0.8, 0.995], pred: 'brightText' },
    { label: 'brand', area: 'footer', pred: dark }
  ],
  'person-4': [
    { label: 'chip', area: 'photo', rel: [0.3, 0.02, 0.7, 0.15], pred: 'brightText' },
    { label: 'brand', area: 'photo', rel: [0.2, 0.82, 0.8, 0.9], pred: 'brightText' },
    { label: 'exif', area: 'photo', rel: [0.2, 0.91, 0.8, 0.995], pred: 'brightText' }
  ],
  'person-5': [{ label: 'footer', area: 'footer', pred: dark }]
}

function roiPixels(img, photo, job) {
  const [px0, py0, pw, ph] = photo
  if (job.area === 'footer') return [0, py0 + ph + 1, img.w - 1, img.h - 1]
  if (job.area === 'header') return [0, 0, img.w - 1, py0 - 2]
  const [x0, y0, x1, y1] = job.rel
  return [px0 + x0 * pw, py0 + y0 * ph, px0 + x1 * pw, py0 + y1 * ph]
}

async function main() {
  const rows = []
  for (const id of Object.keys(REF)) {
    const r = REF[id], o = OURS[id]
    const rImg = await load(REF_DIR, r.file)
    const oImg = await load(o.dir ?? OUR_DIR, o.file)
    const rp = r.photo, op = o.photo
    const rS = Math.min(rp[2], rp[3]), oS = Math.min(op[2], op[3])
    const line = { id, ref: {}, ours: {}, diffPctS: {} }
    line.ref.canvas = [rImg.w, rImg.h]
    line.ours.canvas = [oImg.w, oImg.h]
    line.ref.photo = rp; line.ours.photo = op
    line.ref.marginsPctS = {
      L: (rp[0] / rS) * 100, T: (rp[1] / rS) * 100,
      R: ((rImg.w - rp[0] - rp[2]) / rS) * 100, B: ((rImg.h - rp[1] - rp[3]) / rS) * 100
    }
    line.ours.marginsPctS = {
      L: (op[0] / oS) * 100, T: (op[1] / oS) * 100,
      R: ((oImg.w - op[0] - op[2]) / oS) * 100, B: ((oImg.h - op[1] - op[3]) / oS) * 100
    }
    line.marginErrPx1024 = Object.fromEntries(Object.keys(line.ref.marginsPctS).map((k) => [
      k, ((line.ref.marginsPctS[k] - line.ours.marginsPctS[k]) / 100) * 1024
    ]))
    for (const job of JOBS[id]) {
      const rr = roiPixels(rImg, rp, job)
      const oo = roiPixels(oImg, op, job)
      const rb = job.pred === 'brightText' ? bboxBrightText(rImg, ...rr) : bbox(rImg, ...rr, job.pred)
      const ob = job.pred === 'brightText' ? bboxBrightText(oImg, ...oo) : bbox(oImg, ...oo, job.pred)
      const conv = (b, photo, S) => b ? {
        wPctS: (b.w / S) * 100, hPctS: (b.h / S) * 100,
        cxPctS: ((b.x + b.w / 2 - (photo[0] + photo[2] / 2)) / S) * 100,
        topGapPctS: ((b.y - (photo[1] + photo[3])) / S) * 100,
        bottomGapPctS: ((photo[1] + photo[3] - (b.y + b.h)) / S) * 100
      } : null
      line.ref[job.label] = conv(rb, rp, rS)
      line.ours[job.label] = conv(ob, op, oS)
      if (line.ref[job.label] && line.ours[job.label]) {
        line.diffPctS[job.label] = {
          w: line.ref[job.label].wPctS - line.ours[job.label].wPctS,
          h: line.ref[job.label].hPctS - line.ours[job.label].hPctS,
          cx: line.ref[job.label].cxPctS - line.ours[job.label].cxPctS,
          top: line.ref[job.label].topGapPctS - line.ours[job.label].topGapPctS
        }
      }
    }
    rows.push(line)
  }
  for (const l of rows) {
    console.log('\n===', l.id, 'S ref', Math.min(l.ref.photo[2], l.ref.photo[3]), 'ours', Math.min(l.ours.photo[2], l.ours.photo[3]))
    console.log('  margins ref  L% T% R% B%', Object.values(l.ref.marginsPctS).map((v) => v.toFixed(2)).join(' '))
    console.log('  margins ours L% T% R% B%', Object.values(l.ours.marginsPctS).map((v) => v.toFixed(2)).join(' '))
    console.log('  margin err px@1024', Object.values(l.marginErrPx1024).map((v) => v.toFixed(2)).join(' '))
    for (const k of Object.keys(l.diffPctS)) {
      const r = l.ref[k], o = l.ours[k], d = l.diffPctS[k]
      console.log(
        `  ${k}: ref w% ${r.wPctS.toFixed(2)} h% ${r.hPctS.toFixed(2)} cx% ${r.cxPctS.toFixed(2)} top% ${r.topGapPctS.toFixed(2)}`,
        `| ours w% ${o.wPctS.toFixed(2)} h% ${o.hPctS.toFixed(2)} cx% ${o.cxPctS.toFixed(2)} top% ${o.topGapPctS.toFixed(2)}`,
        `| diff w ${d.w.toFixed(2)} h ${d.h.toFixed(2)} cx ${d.cx.toFixed(2)} top ${d.top.toFixed(2)}`
      )
    }
  }
  require('node:fs').writeFileSync('D:/border&watermark/_research/preview-analysis/geometry-diff.json', JSON.stringify(rows, null, 2))
}
main().catch((e) => { console.error(e); process.exit(1) })
