/**
 * A2 part 1 — measurement half. Node only (no Electron).
 *
 *   1. encodes every fixture with sharp using the app's desktop settings
 *      (`jpeg({quality, chromaSubsampling:'4:4:4'})`, no mozjpeg, no progressive)
 *   2. parses the SOF/SOS/DQT markers of BOTH encoders' files: subsampling is
 *      read from the bitstream, never assumed
 *   3. SSIM (luma, 8x8 box window via summed-area tables) vs the PNG source
 *   4. chroma-only RMSE in BT.601 YCbCr (Cb and Cr only) + worst pixel
 *   5. picks, per fixture, the 300x200 window with the largest Chromium-q85
 *      chroma error and writes the four 1x tiles that `_a2-crops.cjs` upscales 2x
 *
 * Outputs _research/a2/quality-matrix.json and _research/a2/out/tiles/*.png.
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const sharp = require('sharp')

const A2 = path.resolve(__dirname, '..', '..', '_research', 'a2')
const FIX = path.join(A2, 'fixtures')
const CHROMIUM = path.join(A2, 'out', 'chromium')
const SHARP_DIR = path.join(A2, 'out', 'sharp')
const TILES = path.join(A2, 'out', 'tiles')
const QUALS = [100, 95, 90, 85]
const FIXTURES = ['bars', 'colortext', 'skin', 'detail']
const CROP_W = 200
const CROP_H = 135

/* ------------------------------------------------------------------ markers */

/** Walk the JPEG marker stream: SOF type, component sampling, DQT + scan hashes. */
function parseJpeg(buf) {
  if (buf.readUInt16BE(0) !== 0xffd8) throw new Error('not a JPEG')
  let i = 2
  const out = { sof: null, progressive: false, components: 0, sampling: [], dqtHash: null, scanHash: null, scanBytes: 0, appMarkers: [] }
  const dqt = []
  while (i < buf.length) {
    if (buf[i] !== 0xff) throw new Error(`marker desync at ${i}`)
    let m = buf[i + 1]
    while (m === 0xff) {
      i++
      m = buf[i + 1]
    }
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) {
      i += 2
      continue
    }
    if (m === 0xd9) break
    const len = buf.readUInt16BE(i + 2)
    const seg = buf.subarray(i + 4, i + 2 + len)
    if (m >= 0xe0 && m <= 0xef) out.appMarkers.push(m.toString(16))
    if (m === 0xdb) dqt.push(Buffer.from(seg))
    if (m === 0xc0 || m === 0xc1 || m === 0xc2) {
      out.sof = `FF${m.toString(16).toUpperCase()}`
      out.progressive = m === 0xc2
      out.components = seg[5]
      const comps = []
      for (let c = 0; c < out.components; c++) {
        const off = 6 + c * 3
        const h = seg[off + 1] >> 4
        const v = seg[off + 1] & 0x0f
        comps.push({ id: seg[off], h, v })
      }
      out.sampling = comps
    }
    if (m === 0xda) {
      // Entropy-coded scan runs until the next non-RST, non-stuffed 0xFF marker.
      let j = i + 2 + len
      const start = j
      while (j < buf.length - 1) {
        if (buf[j] === 0xff) {
          const n = buf[j + 1]
          if (n === 0x00 || (n >= 0xd0 && n <= 0xd7)) {
            j += 2
            continue
          }
          break
        }
        j++
      }
      const scan = buf.subarray(start, j)
      out.scanHash = crypto.createHash('sha256').update(scan).digest('hex').slice(0, 32)
      out.scanBytes = scan.length
      break
    }
    i += 2 + len
  }
  out.dqtHash = crypto.createHash('sha256').update(Buffer.concat(dqt)).digest('hex').slice(0, 32)
  if (out.components === 3) {
    const [y, cb, cr] = out.sampling
    if (y.h === 1 && y.v === 1 && cb.h === 1 && cb.v === 1 && cr.h === 1 && cr.v === 1) out.subsampling = '4:4:4'
    else if (y.h === 2 && y.v === 2 && cb.h === 1 && cb.v === 1) out.subsampling = '4:2:0'
    else if (y.h === 2 && y.v === 1 && cb.h === 1 && cb.v === 1) out.subsampling = '4:2:2'
    else out.subsampling = `Y${y.h}x${y.v}/C${cb.h}x${cb.v}`
  } else if (out.components === 1) out.subsampling = '4:0:0 (grey)'
  else out.subsampling = `${out.components} components`
  return out
}

/* ------------------------------------------------------------------ metrics */

function ycbcr(data, n) {
  const y = new Float32Array(n)
  const cb = new Float32Array(n)
  const cr = new Float32Array(n)
  for (let p = 0, i = 0; p < n; p++, i += 3) {
    const r = data[i]
    const g = data[i + 1]
    const b = data[i + 2]
    y[p] = 0.299 * r + 0.587 * g + 0.114 * b
    cb[p] = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
    cr[p] = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
  }
  return { y, cb, cr }
}

/** Summed-area table over a Float64Array (w+1)x(h+1). */
function sat(src, w, h) {
  const t = new Float64Array((w + 1) * (h + 1))
  for (let y = 0; y < h; y++) {
    let rowSum = 0
    for (let x = 0; x < w; x++) {
      rowSum += src[y * w + x]
      t[(y + 1) * (w + 1) + (x + 1)] = t[y * (w + 1) + (x + 1)] + rowSum
    }
  }
  return t
}

function windowMean(t, w, x, y, win) {
  const s = (xx, yy) => t[yy * (w + 1) + xx]
  return (s(x + win, y + win) - s(x, y + win) - s(x + win, y) + s(x, y)) / (win * win)
}

/** SSIM on luma, 8x8 uniform window (Wang's constants, box variant). */
function ssimLuma(a, b, w, h, win = 8) {
  const n = w * h
  const aa = new Float64Array(n)
  const bb = new Float64Array(n)
  const ab = new Float64Array(n)
  for (let i = 0; i < n; i++) {
    aa[i] = a[i] * a[i]
    bb[i] = b[i] * b[i]
    ab[i] = a[i] * b[i]
  }
  const tA = sat(a, w, h)
  const tB = sat(b, w, h)
  const tAA = sat(aa, w, h)
  const tBB = sat(bb, w, h)
  const tAB = sat(ab, w, h)
  const C1 = (0.01 * 255) ** 2
  const C2 = (0.03 * 255) ** 2
  let sum = 0
  let count = 0
  for (let y = 0; y + win <= h; y++) {
    for (let x = 0; x + win <= w; x++) {
      const mA = windowMean(tA, w, x, y, win)
      const mB = windowMean(tB, w, x, y, win)
      const vA = Math.max(0, windowMean(tAA, w, x, y, win) - mA * mA)
      const vB = Math.max(0, windowMean(tBB, w, x, y, win) - mB * mB)
      const cov = windowMean(tAB, w, x, y, win) - mA * mB
      sum += ((2 * mA * mB + C1) * (2 * cov + C2)) / ((mA * mA + mB * mB + C1) * (vA + vB + C2))
      count++
    }
  }
  return sum / count
}

function chromaStats(srcC, decC, w, h) {
  const n = w * h
  let sCb = 0
  let sCr = 0
  let maxDelta = 0
  let worst = { x: 0, y: 0, dCb: 0, dCr: 0 }
  let over10 = 0
  const err = new Float64Array(n)
  for (let p = 0; p < n; p++) {
    const dCb = decC.cb[p] - srcC.cb[p]
    const dCr = decC.cr[p] - srcC.cr[p]
    sCb += dCb * dCb
    sCr += dCr * dCr
    const d = Math.hypot(dCb, dCr)
    err[p] = dCb * dCb + dCr * dCr
    if (d > 10) over10++
    if (d > maxDelta) {
      maxDelta = d
      worst = { x: p % w, y: (p / w) | 0, dCb: +dCb.toFixed(2), dCr: +dCr.toFixed(2) }
    }
  }
  return {
    cbRmse: Math.sqrt(sCb / n),
    crRmse: Math.sqrt(sCr / n),
    chromaRmse: Math.sqrt((sCb + sCr) / (2 * n)),
    maxChromaDelta: maxDelta,
    pixelsOver10Delta: over10 / n,
    worst: { ...worst, delta: +maxDelta.toFixed(2) },
    errMap: err
  }
}

/** 300x200 window with the largest summed squared chroma error (integral image). */
function worstWindow(err, w, h, winW, winH) {
  const t = sat(err, w, h)
  let best = { score: -1, x: 0, y: 0 }
  for (let y = 0; y + winH <= h; y += 2) {
    for (let x = 0; x + winW <= w; x += 2) {
      const s =
        t[(y + winH) * (w + 1) + (x + winW)] - t[y * (w + 1) + (x + winW)] - t[(y + winH) * (w + 1) + x] + t[y * (w + 1) + x]
      if (s > best.score) best = { score: s, x, y }
    }
  }
  return best
}

/** Largest chroma error inside the crop window, with its image coordinates. */
function maxIn(err, w, win) {
  let best = { x: win.x, y: win.y, delta: 0 }
  for (let y = win.y; y < win.y + CROP_H; y++) {
    for (let x = win.x; x < win.x + CROP_W; x++) {
      const e = err[y * w + x]
      if (e > best.delta * best.delta) best = { x, y, delta: Math.sqrt(e) }
    }
  }
  return { ...best, delta: +best.delta.toFixed(2) }
}

/* ---------------------------------------------------------------------- run */

async function decode(file) {
  const { data, info } = await sharp(file).removeAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data, width: info.width, height: info.height, channels: info.channels }
}

async function main() {
  fs.mkdirSync(SHARP_DIR, { recursive: true })
  fs.mkdirSync(TILES, { recursive: true })
  const cells = []
  const sources = {}
  const decs = {}

  for (const fx of FIXTURES) {
    const pngPath = path.join(FIX, `${fx}.png`)
    const src = await decode(pngPath)
    sources[fx] = { width: src.width, height: src.height, bytes: fs.statSync(pngPath).size, pngPath }
    decs[fx] = { src, srcC: ycbcr(src.data, src.width * src.height), cells: {} }

    for (const q of QUALS) {
      // --- sharp (the app's desktop settings, main/index.ts:435) -------------
      const sharpBuf = await sharp(pngPath)
        .jpeg({ quality: q, chromaSubsampling: '4:4:4', mozjpeg: false, progressive: false })
        .toBuffer()
      const sharpPath = path.join(SHARP_DIR, `${fx}-q${q}.jpg`)
      fs.writeFileSync(sharpPath, sharpBuf)

      const chrPath = path.join(CHROMIUM, `${fx}-q${q}.jpg`)
      const chrBuf = fs.readFileSync(chrPath)

      for (const [encoder, buf, file] of [
        ['sharp', sharpBuf, sharpPath],
        ['chromium', chrBuf, chrPath]
      ]) {
        const m = parseJpeg(buf)
        const dec = await decode(file)
        if (dec.width !== src.width || dec.height !== src.height) throw new Error(`size mismatch ${file}`)
        const decC = ycbcr(dec.data, dec.width * dec.height)
        const n = src.width * src.height
        const cs = chromaStats(decs[fx].srcC, decC, src.width, src.height)
        const cell = {
          fixture: fx,
          encoder,
          q,
          bytes: buf.length,
          width: dec.width,
          height: dec.height,
          sof: m.sof,
          progressive: m.progressive,
          components: m.components,
          sampling: m.sampling,
          subsampling: m.subsampling,
          dqtHash: m.dqtHash,
          scanHash: m.scanHash,
          scanBytes: m.scanBytes,
          appMarkers: m.appMarkers,
          ssimLuma: +ssimLuma(decs[fx].srcC.y, decC.y, src.width, src.height).toFixed(6),
          chromaRmse: +cs.chromaRmse.toFixed(4),
          cbRmse: +cs.cbRmse.toFixed(4),
          crRmse: +cs.crRmse.toFixed(4),
          maxChromaDelta: +cs.maxChromaDelta.toFixed(2),
          pixelsOver10Delta: +cs.pixelsOver10Delta.toFixed(5),
          worst: cs.worst,
          n
        }
        cells.push(cell)
        decs[fx].cells[`${encoder}-q${q}`] = { cell, err: cs.errMap, dec: dec.data }
        console.log(
          `  ${fx.padEnd(8)} ${encoder.padEnd(8)} q${String(q).padEnd(3)} ${String(buf.length).padStart(8)}B  ` +
            `${m.subsampling.padEnd(6)} ssim=${cell.ssimLuma.toFixed(5)} chromaRMSE=${cell.chromaRmse.toFixed(3)} maxΔ=${cell.maxChromaDelta}`
        )
      }
    }
  }

  // q100 cross-checks: identical quantisation tables and entropy scan?
  const q100 = {}
  for (const fx of FIXTURES) {
    const s = cells.find((c) => c.fixture === fx && c.encoder === 'sharp' && c.q === 100)
    const c = cells.find((x) => x.fixture === fx && x.encoder === 'chromium' && x.q === 100)
    q100[fx] = { dqtIdentical: s.dqtHash === c.dqtHash, scanIdentical: s.scanHash === c.scanHash, subsampling: c.subsampling }
  }

  // Crop windows: worst Chromium q85 chroma error, 300x200, then 1x tiles.
  const crops = {}
  for (const fx of FIXTURES) {
    const entry = decs[fx]
    const worstCell = entry.cells['chromium-q85']
    const win = worstWindow(worstCell.err, entry.src.width, entry.src.height, CROP_W, CROP_H)
    if (win.score <= 0) {
      // Nothing measurable (flat fixture): fall back to the centre window so the
      // strip still exists and plainly shows "no difference".
      win.x = Math.round((entry.src.width - CROP_W) / 2)
      win.y = Math.round((entry.src.height - CROP_H) / 2)
    }
    const box = { left: win.x, top: win.y, width: CROP_W, height: CROP_H }
    const variants = [
      ['source', path.join(FIX, `${fx}.png`)],
      ['sharp-q95', path.join(SHARP_DIR, `${fx}-q95.jpg`)],
      ['chromium-q95', path.join(CHROMIUM, `${fx}-q95.jpg`)],
      ['chromium-q85', path.join(CHROMIUM, `${fx}-q85.jpg`)]
    ]
    const tiles = []
    for (const [label, file] of variants) {
      const tilePath = path.join(TILES, `${fx}__${label}.png`)
      await sharp(file).extract(box).png({ compressionLevel: 9 }).toFile(tilePath)
      tiles.push({ label, file: tilePath })
    }
    crops[fx] = {
      window: { x: win.x, y: win.y, w: CROP_W, h: CROP_H },
      score: +win.score.toFixed(1),
      worstPixel: {
        sharpQ95: maxIn(entry.cells['sharp-q95'].err, entry.src.width, win),
        chromiumQ95: maxIn(entry.cells['chromium-q95'].err, entry.src.width, win),
        chromiumQ85: maxIn(entry.cells['chromium-q85'].err, entry.src.width, win)
      },
      tiles
    }
    console.log(`  crop ${fx}: window ${win.x},${win.y} ${CROP_W}x${CROP_H} score=${win.score.toFixed(0)}`)
  }

  const chromiumMeta = JSON.parse(fs.readFileSync(path.join(A2, 'out', 'chromium-meta.json'), 'utf8'))
  const matrix = {
    generatedAt: new Date().toISOString(),
    environment: chromiumMeta.versions,
    userAgent: chromiumMeta.ua,
    fixtures: sources,
    method: {
      ssim: 'luma BT.601, 8x8 uniform window SSIM (C1=6.5025, C2=58.5225) vs the PNG source',
      chroma: 'BT.601 YCbCr, RMSE over Cb/Cr only (decoded RGB -> YCbCr, both ends identically)',
      subsampling: 'read from the SOF marker sampling factors, not from the API request',
      sharp: 'sharp(0.35.5) jpeg({quality, chromaSubsampling:"4:4:4", mozjpeg:false, progressive:false}) — main/index.ts:435',
      chromium: 'canvas.toBlob("image/jpeg", q/100) on a canvas with alpha:false, same Chromium build as the app'
    },
    q100CrossCheck: q100,
    cells: cells.map(({ n, ...rest }) => rest),
    crops
  }
  fs.writeFileSync(path.join(A2, 'quality-matrix.json'), JSON.stringify(matrix, null, 2))
  console.log('\nwrote', path.join(A2, 'quality-matrix.json'))
  console.log('q100 cross-check', JSON.stringify(q100))
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
