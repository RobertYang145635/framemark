const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const DIR = process.argv[2] || 'D:/border&watermark/framemark/src/renderer/src/assets/previews'
const OUT = process.argv[3] || 'D:/border&watermark/_research/preview-analysis'
fs.mkdirSync(OUT, { recursive: true })

const BG_TOL = 12

function near(px, i, r, g, b, tol = BG_TOL) {
  return Math.abs(px[i] - r) <= tol && Math.abs(px[i + 1] - g) <= tol && Math.abs(px[i + 2] - b) <= tol
}

function median(arr) {
  const a = [...arr].sort((x, y) => x - y)
  return a[Math.floor(a.length / 2)]
}

async function analyze(file) {
  const { data, info } = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const { width: W, height: H, channels: C } = info
  const px = data

  const patch = Math.max(2, Math.round(Math.min(W, H) * 0.01))
  const sample = []
  for (let y = 0; y < patch; y++) {
    for (let x = 0; x < patch; x++) {
      for (const [ox, oy] of [[x, y], [W - 1 - x, y], [x, H - 1 - y], [W - 1 - x, H - 1 - y]]) {
        const i = (oy * W + ox) * C
        sample.push([px[i], px[i + 1], px[i + 2]])
      }
    }
  }
  const bg = [median(sample.map((s) => s[0])), median(sample.map((s) => s[1])), median(sample.map((s) => s[2]))]

  const colBg = new Float32Array(W)
  const rowBg = new Float32Array(H)
  for (let x = 0; x < W; x++) {
    let n = 0
    for (let y = 0; y < H; y += Math.max(1, Math.floor(H / 400))) {
      n++
      if (near(px, (y * W + x) * C, bg[0], bg[1], bg[2])) n--
    }
    colBg[x] = n
  }
  for (let y = 0; y < H; y++) {
    let n = 0
    for (let x = 0; x < W; x += Math.max(1, Math.floor(W / 400))) {
      n++
      if (near(px, (y * W + x) * C, bg[0], bg[1], bg[2])) n--
    }
    rowBg[y] = n
  }
  const totalCol = Math.ceil(H / Math.max(1, Math.floor(H / 400)))
  const totalRow = Math.ceil(W / Math.max(1, Math.floor(W / 400)))
  const isBgCol = (x) => colBg[x] >= totalCol * 0.9
  const isBgRow = (y) => rowBg[y] >= totalRow * 0.9

  let left = 0
  while (left < W - 1 && isBgCol(left)) left++
  let right = W - 1
  while (right > 0 && isBgCol(right)) right--
  let top = 0
  while (top < H - 1 && isBgRow(top)) top++
  let bottom = H - 1
  while (bottom > 0 && isBgRow(bottom)) bottom--

  // A footer is a contiguous run of bg-like rows ending at the photo's bottom edge,
  // starting somewhere below the top half.
  let footerTop = null
  if (bottom < H - 1 || top > 0) {
    let y = H - 1
    let run = 0
    while (y >= 0 && (isBgRow(y) || y > bottom)) {
      if (isBgRow(y)) run++
      y--
    }
    if (run > H * 0.02) footerTop = H - run
  }

  // text/logo bbox in the bottom 30%
  const bandTop = Math.round(H * 0.7)
  let bx0 = W, bx1 = 0, by0 = H, by1 = 0, count = 0
  for (let y = bandTop; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * C
      if (!near(px, i, bg[0], bg[1], bg[2], 20)) {
        count++
        if (x < bx0) bx0 = x
        if (x > bx1) bx1 = x
        if (y < by0) by0 = y
        if (y > by1) by1 = y
      }
    }
  }
  const nonBgFrac = 1 - rowBg.reduce((a, b) => a + b, 0) / (H * totalRow)

  const res = {
    file: path.basename(file),
    width: W,
    height: H,
    bg,
    photo: { left, top, right, bottom, w: right - left + 1, h: bottom - top + 1 },
    margins: {
      left: left,
      right: W - 1 - right,
      top,
      bottom: H - 1 - bottom,
      leftPct: +(left / W * 100).toFixed(2),
      rightPct: +((W - 1 - right) / W * 100).toFixed(2),
      topPct: +(top / H * 100).toFixed(2),
      bottomPct: +((H - 1 - bottom) / H * 100).toFixed(2)
    },
    footer: footerTop == null ? null : { top: footerTop, h: H - footerTop, pct: +((H - footerTop) / H * 100).toFixed(2) },
    bottomBandNonBgPct: +((count / ((H - bandTop) * W)) * 100).toFixed(2),
    contentBbox: count ? { x0: bx0, x1: bx1, y0: by0, y1: by1 } : null,
    nonBgFrac: +(nonBgFrac * 100).toFixed(2)
  }
  return { res, px, W, H, C, bg, footerTop, photo: res.photo }
}

async function main() {
  const files = fs.readdirSync(DIR).filter((f) => /\.(webp|png|jpe?g)$/i.test(f) && !f.startsWith('watermark_video')).sort()
  const rows = []
  for (const f of files) {
    try {
      const { res, px, W, H, C, bg, footerTop, photo } = await analyze(path.join(DIR, f))
      rows.push(res)
      // crop bottom 22% for a contact strip
      const bandH = Math.min(H, Math.round(H * 0.22))
      await sharp(px, { raw: { width: W, height: H, channels: C } })
        .extract({ left: 0, top: H - bandH, width: W, height: bandH })
        .png()
        .toFile(path.join(OUT, `${path.parse(f).name}-bottom.png`))
      if (footerTop != null) {
        const fh = H - footerTop
        await sharp(px, { raw: { width: W, height: H, channels: C } })
          .extract({ left: 0, top: footerTop, width: W, height: fh })
          .png()
          .toFile(path.join(OUT, `${path.parse(f).name}-footer.png`))
      }
    } catch (e) {
      rows.push({ file: f, error: String(e) })
    }
  }
  fs.writeFileSync(path.join(OUT, 'previews.json'), JSON.stringify(rows, null, 2))
  for (const r of rows) {
    if (r.error) { console.log(r.file, r.error); continue }
    console.log(
      `${r.file.padEnd(26)} ${r.width}x${r.height} bg=${r.bg.join(',')} photo=${r.photo.left},${r.photo.top},${r.photo.w}x${r.photo.h} margins L${r.margins.leftPct}% R${r.margins.rightPct}% T${r.margins.topPct}% B${r.margins.bottomPct}% footer=${r.footer ? r.footer.h + 'px/' + r.footer.pct + '%' : '-'} bottomInk=${r.bottomBandNonBgPct}%`
    )
  }
}
main().catch((e) => { console.error(e); process.exit(1) })
