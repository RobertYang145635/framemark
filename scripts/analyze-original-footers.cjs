const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const DIR = process.argv[2] || 'D:/border&watermark/_research/original-ratio-study'
const OUT = path.join(DIR, 'analysis')
fs.mkdirSync(OUT, { recursive: true })

const files = fs.readdirSync(DIR).filter((f) => /^light_shadow_border_179278[78]\d+\.jpg$/.test(f)).sort()

function footerTop(data, W, H, C) {
  const isWhite = (x, y) => {
    const i = (y * W + x) * C
    return data[i] > 244 && data[i + 1] > 244 && data[i + 2] > 244
  }
  const margin = Math.max(2, Math.round(W * 0.015))
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

function components(data, W, H, C, y0, x0, w, h, threshold = 190) {
  const seen = new Uint8Array(w * h)
  const out = []
  const stack = []
  const dark = (x, y) => {
    const i = ((y0 + y) * W + x0 + x) * C
    const r = data[i], g = data[i + 1], b = data[i + 2]
    // dark enough, or saturated logo color
    return (r < threshold && g < threshold && b < threshold) || (Math.max(r, g, b) - Math.min(r, g, b) > 40 && Math.min(r, g, b) < 200)
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
      if (area >= 12) out.push({ x: minX + x0, y: minY + y0, w: maxX - minX + 1, h: maxY - minY + 1, area })
    }
  }
  return out
}

;(async () => {
  const report = []
  const tiles = []
  let tileX = 0
  for (const file of files) {
    const { data, info } = await sharp(path.join(DIR, file)).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
    const W = info.width, H = info.height, C = info.channels
    const top = footerTop(data, W, H, C)
    const fh = H - top
    const comps = components(data, W, H, C, top, 0, W, fh)
    comps.sort((a, b) => a.x - b.x)
    // group into x clusters with gaps > 1.5% W
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
    const center = W / 2
    const left = clusters.filter((c) => c.x1 < center - W * 0.05)
    const right = clusters.filter((c) => c.x0 > center + W * 0.05)
    const mid = clusters.filter((c) => !left.includes(c) && !right.includes(c))
    const r = {
      file, W, H, footerTop: top, footerH: fh,
      footerPctW: +(fh / W * 100).toFixed(2),
      footerPctH: +(fh / H * 100).toFixed(2),
      photoH: top,
      photoRatio: +(W / top).toFixed(3),
      clusters: clusters.map((c) => ({
        x0: c.x0, x1: c.x1, y0: c.y0, y1: c.y1, w: c.x1 - c.x0, h: c.y1 - c.y0,
        x0pct: +(c.x0 / W * 100).toFixed(2), x1pct: +(c.x1 / W * 100).toFixed(2),
        y0rel: +(c.y0 - top).toFixed(1), y1rel: +(c.y1 - top).toFixed(1),
        hPctFooter: +((c.y1 - c.y0) / fh * 100).toFixed(1), comps: c.comps
      }))
    }
    report.push(r)
    // footer crop at native size, plus a scaled tile
    const cropH = Math.min(fh, 520)
    const crop = await sharp(path.join(DIR, file)).extract({ left: 0, top, width: W, height: cropH }).toBuffer()
    fs.writeFileSync(path.join(OUT, file.replace('.jpg', '-footer.jpg')), crop)
    const tile = await sharp(crop).resize({ width: 1100, fit: 'inside' }).jpeg({ quality: 88 }).toBuffer()
    const tm = await sharp(tile).metadata()
    tiles.push({ input: tile, left: tileX, top: 0 })
    tileX += tm.width + 16
  }
  fs.writeFileSync(path.join(OUT, 'footer-report.json'), JSON.stringify(report, null, 2))
  // stack footer crops vertically into one sheet for visual inspection
  const maxW = Math.max(...tiles.map((t) => t.left === 0 ? 0 : 0), 0)
  // simpler: vertical stack each at 1100 wide
  const stack = []
  let y = 0
  for (const file of files) {
    const crop = path.join(OUT, file.replace('.jpg', '-footer.jpg'))
    const buf = await sharp(crop).resize({ width: 1100, fit: 'inside' }).jpeg({ quality: 88 }).toBuffer()
    const m = await sharp(buf).metadata()
    stack.push({ input: buf, left: 0, top: y })
    y += m.height + 12
  }
  await sharp({ create: { width: 1100, height: y, channels: 3, background: '#222' } }).composite(stack).jpeg({ quality: 90 }).toFile(path.join(OUT, 'footers-sheet.jpg'))
  console.log(JSON.stringify(report, null, 2))
})().catch((e) => { console.error(e); process.exit(1) })
