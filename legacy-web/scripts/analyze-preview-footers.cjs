const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/previews'
const OUT = 'D:/border&watermark/_research/ref-analysis/footers'
fs.mkdirSync(OUT, { recursive: true })
const files = (process.argv.slice(2).length ? process.argv.slice(2) : [
  'new_watermark1.webp', 'watermark1.webp', 'watermark2.webp', 'watermark3.webp', 'watermark4.webp',
  'person1.webp', 'person2.webp', 'card3.webp', 'card4.webp'
])

function footerTop(data, W, H, C) {
  const isWhite = (x, y) => {
    const i = (y * W + x) * C
    return data[i] > 242 && data[i + 1] > 242 && data[i + 2] > 242
  }
  const margin = Math.max(2, Math.round(W * 0.012))
  const rowWhiteEdges = (y) => {
    let n = 0, total = 0
    for (let x = 0; x < margin; x++) {
      total += 2
      if (isWhite(x, y)) n++
      if (isWhite(W - 1 - x, y)) n++
    }
    return n / total
  }
  let top = H - 1
  for (let y = H - 1; y >= 0; y--) {
    if (rowWhiteEdges(y) < 0.98) break
    top = y
  }
  return top
}

function components(data, W, H, C, y0, x0, w, h, threshold = 200) {
  const seen = new Uint8Array(w * h)
  const out = []
  const stack = []
  const dark = (x, y) => {
    const i = ((y0 + y) * W + x0 + x) * C
    const r = data[i], g = data[i + 1], b = data[i + 2]
    return (r < threshold && g < threshold && b < threshold) || (Math.max(r, g, b) - Math.min(r, g, b) > 50 && Math.min(r, g, b) < 220)
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const p = y * w + x
      if (seen[p] || !dark(x, y)) continue
      let minX = x, maxX = x, minY = y, maxY = y, area = 0
      stack.length = 0; stack.push(p); seen[p] = 1
      while (stack.length) {
        const q = stack.pop()
        const qx = q % w, qy = (q - qx) / w
        area++
        if (qx < minX) minX = qx
        if (qx > maxX) maxX = qx
        if (qy < minY) minY = qy
        if (qy > maxY) maxY = qy
        for (const [dx, dy] of [[-1,0],[1,0],[0,-1],[0,1]]) {
          const nx = qx + dx, ny = qy + dy
          if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
          const np = ny * w + nx
          if (seen[np] || !dark(nx, ny)) continue
          seen[np] = 1; stack.push(np)
        }
      }
      if (area >= 8) out.push({ x: minX + x0, y: minY + y0, w: maxX - minX + 1, h: maxY - minY + 1, area })
    }
  }
  return out
}

;(async () => {
  const report = []
  const stackTiles = []
  let ty = 0
  for (const file of files) {
    try {
      const { data, info } = await sharp(path.join(DIR, file)).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
      const W = info.width, H = info.height, C = info.channels
      const top = footerTop(data, W, H, C)
      const fh = H - top
      const comps = components(data, W, H, C, top, 0, W, fh)
      comps.sort((a, b) => a.x - b.x)
      const gap = W * 0.02
      const clusters = []
      for (const c of comps) {
        let cl = clusters.find((g) => c.x <= g.x1 + gap && c.x + c.w >= g.x0 - gap)
        if (!cl) { cl = { x0: c.x, x1: c.x + c.w, y0: c.y, y1: c.y + c.h, comps: 0 }; clusters.push(cl) }
        cl.x0 = Math.min(cl.x0, c.x)
        cl.x1 = Math.max(cl.x1, c.x + c.w)
        cl.y0 = Math.min(cl.y0, c.y)
        cl.y1 = Math.max(cl.y1, c.y + c.h)
        cl.comps++
      }
      const row = {
        file, W, H, footerTop: top, footerH: fh,
        footerPctW: +(fh / W * 100).toFixed(2), footerPctH: +(fh / H * 100).toFixed(2),
        photoH: top, photoRatio: +(W / top).toFixed(3),
        clusters: clusters.map((c) => ({
          x0: c.x0, x1: c.x1, y0: c.y0, y1: c.y1, w: c.x1 - c.x0, h: c.y1 - c.y0,
          x0pct: +(c.x0 / W * 100).toFixed(2), x1pct: +(c.x1 / W * 100).toFixed(2),
          y0rel: +(c.y0 - top).toFixed(1), y1rel: +(c.y1 - top).toFixed(1),
          hPctFooter: +((c.y1 - c.y0) / fh * 100).toFixed(1), comps: c.comps
        }))
      }
      report.push(row)
      const crop = await sharp(path.join(DIR, file)).extract({ left: 0, top, width: W, height: fh }).toBuffer()
      fs.writeFileSync(path.join(OUT, file.replace(/\.[^.]+$/, '') + '-footer.png'), crop)
      const tile = await sharp(crop).resize({ width: 1000, fit: 'inside' }).png().toBuffer()
      const tm = await sharp(tile).metadata()
      stackTiles.push({ input: tile, left: 0, top: ty })
      ty += tm.height + 10
    } catch (e) {
      report.push({ file, error: e.message })
    }
  }
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
  if (stackTiles.length) {
    const maxW = 1000
    await sharp({ create: { width: maxW, height: ty, channels: 3, background: '#222' } }).composite(stackTiles).jpeg({ quality: 90 }).toFile(path.join(OUT, 'sheet.jpg'))
  }
  console.log(JSON.stringify(report, null, 2))
})().catch((e) => { console.error(e); process.exit(1) })
