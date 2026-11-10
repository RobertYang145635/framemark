const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const REF_DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/previews'
const OUT = 'D:/border&watermark/_research/ref-analysis'
fs.mkdirSync(OUT, { recursive: true })

function dist(a, b) {
  return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2])
}

function findRect(data, W, H, C, bg) {
  const mask = new Uint8Array(W * H)
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * C
      mask[y * W + x] = dist([data[i], data[i + 1], data[i + 2]], bg) > 36 ? 1 : 0
    }
  }
  const rowScore = new Float32Array(H)
  const colScore = new Float32Array(W)
  for (let y = 0; y < H; y++) {
    let n = 0
    for (let x = Math.floor(W * 0.12); x < Math.ceil(W * 0.88); x++) n += mask[y * W + x]
    rowScore[y] = n / (W * 0.76)
  }
  for (let x = 0; x < W; x++) {
    let n = 0
    for (let y = Math.floor(H * 0.12); y < Math.ceil(H * 0.88); y++) n += mask[y * W + x]
    colScore[x] = n / (H * 0.76)
  }
  function runs(scores, threshold) {
    const out = []
    let start = -1
    for (let i = 0; i < scores.length; i++) {
      if (scores[i] > threshold) {
        if (start < 0) start = i
      } else if (start >= 0) {
        out.push([start, i - 1])
        start = -1
      }
    }
    if (start >= 0) out.push([start, scores.length - 1])
    return out.sort((a, b) => (b[1] - b[0]) - (a[1] - a[0]))
  }
  const rs = runs(rowScore, 0.42)[0] || [0, H - 1]
  const cs = runs(colScore, 0.42)[0] || [0, W - 1]
  let y0 = rs[0], y1 = rs[1], x0 = cs[0], x1 = cs[1]
  // refine inward: find strong content columns/rows inside the coarse rect
  const innerW = x1 - x0 + 1
  const innerH = y1 - y0 + 1
  const strongX = new Float32Array(innerW)
  const strongY = new Float32Array(innerH)
  for (let y = 0; y < innerH; y++) {
    let n = 0
    for (let x = 0; x < innerW; x++) n += mask[(y0 + y) * W + x0 + x]
    strongY[y] = n / innerW
  }
  for (let x = 0; x < innerW; x++) {
    let n = 0
    for (let y = 0; y < innerH; y++) n += mask[(y0 + y) * W + x0 + x]
    strongX[x] = n / innerH
  }
  // trim 2% edges where border shadow fades
  return { x: x0, y: y0, w: innerW, h: innerH, rowScore, colScore }
}

function components(data, W, H, C, bg, rect) {
  const inRect = (x, y) => x >= rect.x - 1 && x <= rect.x + rect.w && y >= rect.y - 1 && y <= rect.y + rect.h
  const seen = new Uint8Array(W * H)
  const out = []
  const stack = []
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const idx = y * W + x
      if (seen[idx] || inRect(x, y)) continue
      const i = idx * C
      if (dist([data[i], data[i + 1], data[i + 2]], bg) < 45) continue
      let minX = x, maxX = x, minY = y, maxY = y, area = 0
      stack.length = 0
      stack.push(idx)
      seen[idx] = 1
      while (stack.length) {
        const p = stack.pop()
        const px = p % W
        const py = (p - px) / W
        area++
        if (px < minX) minX = px
        if (px > maxX) maxX = px
        if (py < minY) minY = py
        if (py > maxY) maxY = py
        for (const q of [p - 1, p + 1, p - W, p + W]) {
          if (q < 0 || q >= W * H || seen[q]) continue
          const qx = q % W
          const qy = (q - qx) / W
          if (Math.abs(qx - px) + Math.abs(qy - py) !== 1) continue
          if (inRect(qx, qy)) continue
          const qi = q * C
          if (dist([data[qi], data[qi + 1], data[qi + 2]], bg) < 45) continue
          seen[q] = 1
          stack.push(q)
        }
      }
      if (area >= 18) out.push({ x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1, area })
    }
  }
  return out
}

;(async () => {
  const files = fs.readdirSync(REF_DIR).filter((f) => /\.(webp|png|jpe?g)$/i.test(f))
  const report = {}
  for (const file of files) {
    try {
      const { data, info } = await sharp(path.join(REF_DIR, file)).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
      const W = info.width, H = info.height, C = info.channels
      const corners = [[4, 4], [W - 5, 4], [4, H - 5], [W - 5, H - 5]].map(([x, y]) => {
        const i = (y * W + x) * C
        return [data[i], data[i + 1], data[i + 2]]
      })
      const bg = [0, 1, 2].map((k) => Math.round(corners.reduce((s, c) => s + c[k], 0) / 4))
      const rect = findRect(data, W, H, C, bg)
      const comps = components(data, W, H, C, bg, rect).filter((c) => c.w > 3 && c.h > 3)
      // group components into lines by y center proximity
      comps.sort((a, b) => a.y - b.y || a.x - b.x)
      const lines = []
      for (const c of comps) {
        const cy = c.y + c.h / 2
        let line = lines.find((l) => Math.abs(l.cy - cy) < Math.max(4, Math.min(l.h, c.h) * 0.55))
        if (!line) {
          line = { cy, x0: c.x, x1: c.x + c.w, y0: c.y, y1: c.y + c.h, h: c.h, comps: 0 }
          lines.push(line)
        }
        line.x0 = Math.min(line.x0, c.x)
        line.x1 = Math.max(line.x1, c.x + c.w)
        line.y0 = Math.min(line.y0, c.y)
        line.y1 = Math.max(line.y1, c.y + c.h)
        line.cy = (line.y0 + line.y1) / 2
        line.h = line.y1 - line.y0
        line.comps++
      }
      report[file] = {
        W, H, bg, rect,
        rectPct: {
          x: +(rect.x / W * 100).toFixed(2), y: +(rect.y / H * 100).toFixed(2),
          w: +(rect.w / W * 100).toFixed(2), h: +(rect.h / H * 100).toFixed(2),
          top: +(rect.y / H * 100).toFixed(2), bottom: +((H - rect.y - rect.h) / H * 100).toFixed(2),
          left: +(rect.x / W * 100).toFixed(2), right: +((W - rect.x - rect.w) / W * 100).toFixed(2)
        },
        lines: lines.map((l) => ({
          x0: l.x0, x1: l.x1, y0: l.y0, y1: l.y1, h: l.h,
          pct: { x0: +(l.x0 / W * 100).toFixed(2), x1: +(l.x1 / W * 100).toFixed(2), y0: +(l.y0 / H * 100).toFixed(2), y1: +(l.y1 / H * 100).toFixed(2), hh: +(l.h / H * 100).toFixed(2) },
          comps: l.comps
        })).slice(0, 40)
      }
      // annotated composite
      const marks = [
        { input: Buffer.from(`<svg width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg">
          <rect x="${rect.x}" y="${rect.y}" width="${rect.w}" height="${rect.h}" fill="none" stroke="#00ff88" stroke-width="2"/>
          ${lines.map((l) => `<rect x="${l.x0}" y="${l.y0}" width="${l.x1 - l.x0}" height="${l.y1 - l.y0}" fill="none" stroke="#ff3355" stroke-width="1"/>`).join('')}
          <text x="8" y="18" font-family="monospace" font-size="14" fill="#00ff88">${file} ${W}x${H} bg=${bg.join(',')} photo=${rect.x},${rect.y},${rect.w},${rect.h}</text>
        </svg>`), top: 0, left: 0 }
      ]
      await sharp(path.join(REF_DIR, file)).composite(marks).jpeg({ quality: 82 }).toFile(path.join(OUT, file.replace(/\.[^.]+$/, '') + '.jpg'))
    } catch (e) {
      console.error('ERR', file, e.message)
    }
  }
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2))
  console.log('analyzed', Object.keys(report).length)
})().catch((e) => { console.error(e); process.exit(1) })
