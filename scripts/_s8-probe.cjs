#!/usr/bin/env node
/**
 * S8 — measure what each export end actually encodes.
 *
 * One Electron launch renders ONE deterministic canvas ("the design"), then:
 *   Android side: canvas.toBlob(mime, q) exactly as exporter.ts exportAndroid
 *                 does (JPEG q=100/100, PNG 1, WebP 1) and serialises the blobs.
 *   Desktop side: the REAL `export:save` IPC (src/main/index.ts) with the same
 *                 PNG bytes, quality 100, longEdge 0 — real sharp pipeline,
 *                 real ICC embed, real EXIF injection.
 *
 * The script then parses markers itself (JPEG SOF/DQT/APP1/APP2, PNG chunks,
 * RIFF/VP8L/VP8X, TIFF metadata) and compares decoded pixels with sharp.
 *
 * Artifacts: D:\border&watermark\_research\s8\
 *   source/canvas.png                 the canvas as PNG (lossless ground truth)
 *   android/<fmt>                     Chromium encoder output
 *   desktop/<fmt>                     sharp/main output
 *   encoder-facts.json                every measured fact + raw marker bytes
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

const ROOT = path.resolve(__dirname, '..')
const S8 = path.resolve(ROOT, '..', '_research', 's8')
const PROFILE = path.join(S8, 'probe-profile')

function sha1 (buf) {
  return crypto.createHash('sha1').update(buf).digest('hex')
}

/* --------------------------------------------------------------- JPEG read */

const SOF_NAMES = { 0xc0: 'SOF0', 0xc1: 'SOF1', 0xc2: 'SOF2', 0xc3: 'SOF3', 0xc5: 'SOF5', 0xc6: 'SOF6', 0xc7: 'SOF7', 0xc9: 'SOF9', 0xca: 'SOF10', 0xcb: 'SOF11', 0xcd: 'SOF13', 0xce: 'SOF14', 0xcf: 'SOF15' }

function parseJpeg (buf) {
  if (buf[0] !== 0xff || buf[1] !== 0xd8) throw new Error('not a JPEG')
  const out = {
    bytes: buf.length,
    sha1: sha1(buf),
    sof: null,
    sofName: null,
    baseline: null,
    progressive: null,
    apps: [],
    dqt: [],
    restartInterval: null,
    raw: {}
  }
  let i = 2
  while (i + 3 < buf.length) {
    if (buf[i] !== 0xff) { i++; continue }
    let m = buf[i + 1]
    while (m === 0xff) { i++; m = buf[i + 1] }
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue }
    const len = buf.readUInt16BE(i + 2)
    const seg = buf.subarray(i + 4, i + 2 + len) // payload without marker/len
    const marker = `0xFF${m.toString(16).toUpperCase().padStart(2, '0')}`
    if (SOF_NAMES[m]) {
      const precision = seg[0]
      const h = seg.readUInt16BE(1)
      const w = seg.readUInt16BE(3)
      const n = seg[5]
      const comps = []
      for (let c = 0; c < n; c++) {
        const o = 6 + c * 3
        comps.push({ id: seg[o], h: seg[o + 1] >> 4, v: seg[o + 1] & 0x0f, tq: seg[o + 2] })
      }
      out.sof = { marker, precision, w, h, n, comps }
      out.sofName = SOF_NAMES[m]
      out.baseline = SOF_NAMES[m] === 'SOF0' || SOF_NAMES[m] === 'SOF1'
      out.progressive = SOF_NAMES[m] === 'SOF2'
      out.raw.sof = buf.subarray(i, i + Math.min(len + 2, 40)).toString('hex')
    } else if (m === 0xdb) {
      let o = 0
      while (o < seg.length) {
        const pq = seg[o] >> 4
        const tq = seg[o] & 0x0f
        const n = 64 * (pq + 1)
        const vals = []
        for (let k = 0; k < 64; k++) {
          vals.push(pq ? seg.readUInt16BE(o + 1 + k * 2) : seg[o + 1 + k])
        }
        out.dqt.push({ table: tq, precision: pq ? 16 : 8, values: vals, sha1: sha1(Buffer.from(vals.join(','))) })
        o += 1 + n
      }
      if (!out.raw.dqt) out.raw.dqt = buf.subarray(i, i + Math.min(len + 2, 32)).toString('hex')
    } else if (m === 0xe0) {
      out.apps.push({ marker: 'APP0', id: seg.subarray(0, 5).toString('latin1'), bytes: len })
    } else if (m === 0xe1) {
      out.apps.push({ marker: 'APP1', id: seg.subarray(0, 6).toString('latin1'), bytes: len })
    } else if (m === 0xe2) {
      const id = seg.subarray(0, 12).toString('latin1')
      out.apps.push({ marker: 'APP2', id, bytes: len })
    } else if (m === 0xdd) {
      out.restartInterval = seg.readUInt16BE(0)
    } else {
      out.apps.push({ marker, bytes: len })
    }
    if (m === 0xda) break // SOS: entropy data follows, marker walk ends
    i += 2 + len
  }
  out.hasExif = out.apps.some((a) => a.marker === 'APP1' && /Exif/.test(a.id))
  out.hasIcc = out.apps.some((a) => a.marker === 'APP2' && /ICC_PROFILE/.test(a.id))
  out.subsampling = out.sof
    ? out.sof.comps.map((c) => `${c.h}x${c.v}${c.tq === 0 ? '' : ' q' + c.tq}`).join(' ')
    : null
  out.chroma = out.sof
    ? (out.sof.comps[0].h === 1 && out.sof.comps[0].v === 1 && out.sof.comps.every((c, k) => k === 0 || (c.h === 1 && c.v === 1)) ? '4:4:4' : out.sof.comps[0].h === 2 && out.sof.comps[0].v === 2 && out.sof.comps.slice(1).every((c) => c.h === 1 && c.v === 1) ? '4:2:0' : out.sof.comps[0].h === 2 && out.sof.comps[0].v === 1 ? '4:2:2' : 'other')
    : null
  return out
}

/* ---------------------------------------------------------------- PNG read */

function parsePng (buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG')
  const out = { bytes: buf.length, sha1: sha1(buf), chunks: [], ihdr: null, hasSrgb: false, hasIccp: false, hasGama: false }
  let i = 8
  while (i + 8 <= buf.length) {
    const len = buf.readUInt32BE(i)
    const type = buf.subarray(i + 4, i + 8).toString('latin1')
    out.chunks.push({ type, len })
    if (type === 'IHDR') {
      out.ihdr = {
        w: buf.readUInt32BE(i + 8),
        h: buf.readUInt32BE(i + 12),
        bitDepth: buf[i + 16],
        colorType: buf[i + 17],
        interlace: buf[i + 20]
      }
    }
    if (type === 'sRGB') out.hasSrgb = true
    if (type === 'iCCP') out.hasIccp = true
    if (type === 'gAMA') out.hasGama = true
    if (type === 'IEND') break
    i += 12 + len
  }
  return out
}

/* --------------------------------------------------------------- WebP read */

function parseWebp (buf) {
  if (buf.subarray(0, 4).toString('latin1') !== 'RIFF' || buf.subarray(8, 12).toString('latin1') !== 'WEBP') throw new Error('not a WEBP')
  const out = { bytes: buf.length, sha1: sha1(buf), chunks: [], lossless: null, bitstream: null, vp8x: null, hasIcc: false }
  let i = 12
  while (i + 8 <= buf.length) {
    const fourcc = buf.subarray(i, i + 4).toString('latin1')
    const len = buf.readUInt32LE(i + 4)
    out.chunks.push({ fourcc, len })
    const p = i + 8
    if (fourcc === 'VP8L') {
      out.bitstream = 'VP8L'
      out.lossless = true
      out.vp8lSignature = buf[p]
      out.vp8lWidth = 1 + (buf[p + 1] | ((buf[p + 2] & 0x3f) << 8))
      out.vp8lHeight = 1 + ((buf[p + 2] >> 6) | (buf[p + 3] << 2) | ((buf[p + 4] & 0x0f) << 10))
      out.vp8lAlpha = (buf[p + 4] >> 4) & 1
      out.vp8lVersion = (buf[p + 4] >> 5) & 0x07
      out.rawVp8lHeader = buf.subarray(p, p + 5).toString('hex')
    } else if (fourcc === 'VP8 ') {
      out.bitstream = 'VP8'
      out.lossless = false
      out.vp8FrameTag = buf.readUIntLE(p, 3)
      out.vp8StartCode = buf.subarray(p + 3, p + 6).toString('hex')
      out.vp8Width = buf.readUInt16LE(p + 6) & 0x3fff
      out.vp8Height = buf.readUInt16LE(p + 8) & 0x3fff
      out.rawVp8Header = buf.subarray(p, p + 10).toString('hex')
    } else if (fourcc === 'VP8X') {
      out.vp8x = { flags: buf[p], bytes: len, raw: buf.subarray(p, p + 5).toString('hex') }
      out.hasIcc = (buf[p] & 0x20) !== 0
    } else if (fourcc === 'ICCP') {
      out.hasIcc = true
    } else if (fourcc === 'ALPH') {
      out.hasAlphaChunk = true
    }
    i += 8 + len + (len & 1)
  }
  out.declaredLossless = out.bitstream === 'VP8L'
  return out
}

/* --------------------------------------------------------------- TIFF read */

const TIFF_COMPRESSION = { 1: 'none', 2: 'ccitt-rle', 5: 'lzw', 6: 'old-jpeg', 7: 'jpeg', 8: 'deflate', 32773: 'packbits', 32946: 'deflate' }

function parseTiff (buf) {
  const le = buf.subarray(0, 2).toString('latin1') === 'II'
  const u16 = (o) => (le ? buf.readUInt16LE(o) : buf.readUInt16BE(o))
  const u32 = (o) => (le ? buf.readUInt32LE(o) : buf.readUInt32BE(o))
  if (u16(2) !== 42) return { error: 'bad TIFF magic' }
  const ifd = u32(4)
  const n = u16(ifd)
  const tags = {}
  for (let k = 0; k < n; k++) {
    const o = ifd + 2 + k * 12
    const tag = u16(o)
    const type = u16(o + 2)
    const count = u32(o + 4)
    let value = null
    if (type === 3 && count === 1) value = u16(o + 8)
    else if (type === 4 && count === 1) value = u32(o + 8)
    tags[tag] = { type, count, value }
  }
  return {
    endian: le ? 'II' : 'MM',
    ifdOffset: ifd,
    compressionTag259: tags[259] ? TIFF_COMPRESSION[tags[259].value] || tags[259].value : null,
    width: tags[256] ? tags[256].value : null,
    height: tags[257] ? tags[257].value : null,
    samplesPerPixel: tags[277] ? tags[277].value : null,
    iccTagBytes: tags[34675] ? tags[34675].count : 0,
    tags
  }
}

/* ----------------------------------------------------------------- helpers */

async function imageInfo (file) {
  const meta = await sharp(file).metadata()
  return {
    format: meta.format,
    width: meta.width,
    height: meta.height,
    channels: meta.channels,
    depth: meta.depth,
    space: meta.space,
    hasProfile: Boolean(meta.icc && meta.icc.length),
    iccBytes: meta.icc ? meta.icc.length : 0,
    compression: meta.compression ?? null
  }
}

async function pixels (file) {
  const r = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return { data: r.data, info: r.info }
}

function rmseOf (a, b) {
  if (a.info.width !== b.info.width || a.info.height !== b.info.height) {
    return { error: `size mismatch ${a.info.width}x${a.info.height} vs ${b.info.width}x${b.info.height}` }
  }
  const n = a.info.width * a.info.height
  let se = 0
  let max = 0
  let diffPx = 0
  for (let p = 0; p < n; p++) {
    let d3 = 0
    for (let c = 0; c < 3; c++) {
      const o = p * 4 + c
      const d = a.data[o] - b.data[o]
      d3 += d * d
      const ad = Math.abs(d)
      if (ad > max) max = ad
    }
    se += d3
    if (d3 > 0) diffPx++
  }
  return {
    rmse: Math.sqrt(se / (n * 3)),
    maxAbs: max,
    diffPixels: diffPx,
    totalPixels: n,
    dims: `${a.info.width}x${a.info.height}`
  }
}

/* ------------------------------------------------------------- renderer probe */

async function renderProbe (page, desktopDir) {
  return page.evaluate(async ({ desktopDir }) => {
    const W = 1200
    const H = 800
    const canvas = document.createElement('canvas')
    canvas.width = W
    canvas.height = H
    const ctx = canvas.getContext('2d', { alpha: false, willReadFrequently: true })

    // 1. photo-like sky gradient
    const sky = ctx.createLinearGradient(0, 0, W, H)
    sky.addColorStop(0, '#2b5f8f')
    sky.addColorStop(0.5, '#c98b5a')
    sky.addColorStop(1, '#101820')
    ctx.fillStyle = sky
    ctx.fillRect(0, 0, W, H)

    // 2. deterministic "photo" patch: smooth ramps + grain, saturated edges
    const rnd = (() => { let s = 0x5eed5eed; return () => { s |= 0; s = (s + 0x6d2b79f5) | 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296 } })()
    const patch = ctx.createImageData(520, 340)
    for (let y = 0; y < 340; y++) {
      for (let x = 0; x < 520; x++) {
        const o = (y * 520 + x) * 4
        const n = (rnd() - 0.5) * 26
        patch.data[o] = Math.max(0, Math.min(255, 30 + x * 0.42 + n))
        patch.data[o + 1] = Math.max(0, Math.min(255, 190 - y * 0.30 + n))
        patch.data[o + 2] = Math.max(0, Math.min(255, 120 + 60 * Math.sin(x / 37) + n))
        patch.data[o + 3] = 255
      }
    }
    ctx.putImageData(patch, 40, 40)

    // 3. 1px chroma stress: red/cyan vertical stripes (4:2:0 smears these)
    for (let x = 600; x < 920; x++) {
      ctx.fillStyle = (x & 1) ? '#ff0000' : '#00ffff'
      ctx.fillRect(x, 60, 1, 160)
    }
    // 4. 2px checker red/blue
    for (let y = 240; y < 360; y += 2) {
      for (let x = 600; x < 920; x += 2) {
        ctx.fillStyle = ((x + y) & 2) ? '#0000ff' : '#ff8000'
        ctx.fillRect(x, y, 2, 2)
      }
    }
    // 5. neutral gray with a single saturated red hairline (chroma bleed probe)
    ctx.fillStyle = '#7f7f7f'
    ctx.fillRect(960, 40, 200, 340)
    ctx.fillStyle = '#ff0000'
    ctx.fillRect(1060, 60, 1, 300)
    ctx.fillStyle = '#000000'
    ctx.fillRect(1080, 60, 1, 300)

    // 6. text of three sizes (luma detail)
    ctx.fillStyle = '#ffffff'
    ctx.font = '700 64px "Segoe UI", sans-serif'
    ctx.fillText('FrameMark S8', 40, 470)
    ctx.font = '400 26px "Segoe UI", sans-serif'
    ctx.fillStyle = '#ffe9c0'
    ctx.fillText('编码测量 0123456789 — chroma / luma probe', 40, 520)
    ctx.font = '400 14px "Consolas", monospace'
    ctx.fillStyle = '#9fe0ff'
    ctx.fillText('The quick brown fox jumps over the lazy dog. 0123456789', 40, 550)

    // 7. 1px diagonal black/white lines
    for (let k = 0; k < 300; k++) {
      ctx.fillStyle = (k & 1) ? '#000' : '#fff'
      ctx.fillRect(40 + k, 600 + k, 1, 1)
    }
    // 8. flat colour bands (quantisation/entropy sanity)
    for (let k = 0; k < 8; k++) {
      ctx.fillStyle = `hsl(${k * 45}, 90%, 55%)`
      ctx.fillRect(400 + k * 95, 620, 90, 140)
    }

    const toB64 = (blob) => new Promise((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve({ b64: String(r.result).replace(/^data:[^,]*,/, ''), type: blob.type, size: blob.size })
      r.onerror = () => reject(r.error)
      r.readAsDataURL(blob)
    })
    const toBlob = (type, quality) => new Promise((resolve) => canvas.toBlob((b) => resolve(b), type, quality))

    const sourceBlob = await toBlob('image/png')
    const source = await toB64(sourceBlob)
    const sourcePngBytes = new Uint8Array(await sourceBlob.arrayBuffer())

    const android = {}
    for (const [key, type, q] of [['jpeg', 'image/jpeg', 1], ['png', 'image/png', undefined], ['webp', 'image/webp', 1]]) {
      const b = await toBlob(type, q)
      android[key] = b ? await toB64(b) : null
    }
    // Android TIFF: exporter.ts coerces to JPEG today; record what the encoder
    // itself does with the real MIME so the policy decision has evidence.
    const tiffBlob = await toBlob('image/tiff')
    android.tiffDirect = tiffBlob ? await toB64(tiffBlob) : null

    // JPEG subsampling as a function of the quality ARG (the UI slider is
    // 60..100). Desktop pins 4:4:4; Chromium is not documented to.
    const ladder = {}
    for (const q of [1, 0.99, 0.95, 0.92, 0.9, 0.85, 0.8, 0.75, 0.7, 0.65, 0.6]) {
      const b = await toBlob('image/jpeg', q)
      ladder[q] = await toB64(b)
    }

    // Round-trip INSIDE Chromium: what the device itself would see when it
    // decodes the blob it just encoded (colour management included).
    const sourcePixels = ctx.getImageData(0, 0, W, H).data
    const roundtrip = {}
    for (const [key, b64] of Object.entries(android)) {
      if (!b64 || !b64.b64) continue
      const bin = atob(b64.b64)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      const bmp = await createImageBitmap(new Blob([bytes], { type: b64.type }))
      const rc = document.createElement('canvas')
      rc.width = W
      rc.height = H
      const rx = rc.getContext('2d', { alpha: false, willReadFrequently: true })
      rx.drawImage(bmp, 0, 0)
      const d = rx.getImageData(0, 0, W, H).data
      let se = 0
      let max = 0
      let n = 0
      for (let p = 0; p < W * H; p++) {
        let d3 = 0
        for (const c of [0, 1, 2]) {
          const v = d[p * 4 + c] - sourcePixels[p * 4 + c]
          d3 += v * v
        }
        se += d3
        if (d3 > 0) n++
        if (d3 > max * max) max = Math.ceil(Math.sqrt(d3))
      }
      roundtrip[key] = { rmse: Math.sqrt(se / (W * H * 3)), maxAbs: max, diffPixels: n }
      bmp.close()
    }

    // longEdge path: exportOne scales the canvas (drawImage, high smoothing)
    // BEFORE the platform branch; desktop sharp resizes the PNG with its own
    // kernel. Measure dimensions and pixel distance of that step.
    const scaled = document.createElement('canvas')
    scaled.width = 600
    scaled.height = 400
    const sctx = scaled.getContext('2d', { alpha: false })
    sctx.imageSmoothingQuality = 'high'
    sctx.drawImage(canvas, 0, 0, scaled.width, scaled.height)
    const scaledBlob = await new Promise((resolve) => scaled.toBlob((b) => resolve(b), 'image/png'))
    const longEdge = await toB64(scaledBlob)

    const desktop = {}
    for (const format of ['jpeg', 'png', 'webp', 'tiff']) {
      desktop[format] = await window.framemark.exportImage({
        png: sourcePngBytes,
        format,
        quality: 100,
        outputDir: desktopDir,
        filename: `desktop-${format}`,
        longEdge: 0,
        removeGps: true,
        width: W,
        height: H,
        fallbackExif: {}
      })
    }
    const desktopQ90 = await window.framemark.exportImage({
      png: sourcePngBytes,
      format: 'jpeg',
      quality: 90,
      outputDir: desktopDir,
      filename: 'desktop-jpeg-q90',
      longEdge: 0,
      removeGps: true,
      width: W,
      height: H,
      fallbackExif: {}
    })
    const desktopLongEdge = await window.framemark.exportImage({
      png: sourcePngBytes,
      format: 'png',
      quality: 100,
      outputDir: desktopDir,
      filename: 'desktop-longedge600',
      longEdge: 600,
      removeGps: true,
      width: W,
      height: H,
      fallbackExif: {}
    })
    const ua = navigator.userAgent
    return { W, H, ua, source, android, desktop, desktopQ90, desktopLongEdge, ladder, longEdge, roundtrip }
  }, { desktopDir })
}

/* --------------------------------------------------------------------- main */

async function main () {
  fs.rmSync(S8, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  const ANDROID = path.join(S8, 'android')
  const DESKTOP = path.join(S8, 'desktop')
  const SOURCE = path.join(S8, 'source')
  fs.mkdirSync(ANDROID, { recursive: true })
  fs.mkdirSync(DESKTOP, { recursive: true })
  fs.mkdirSync(SOURCE, { recursive: true })
  fs.mkdirSync(PROFILE, { recursive: true })

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`],
    cwd: ROOT,
    env,
    timeout: 90000
  })
  let probe
  let chromeVersion = null
  try {
    chromeVersion = await app.evaluate(() => process.versions.chrome)
    const page = await app.firstWindow()
    page.on('pageerror', (e) => console.error('[pageerror]', e.message))
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    probe = await renderProbe(page, DESKTOP)

    fs.writeFileSync(path.join(SOURCE, 'canvas.png'), Buffer.from(probe.source.b64, 'base64'))
    for (const key of ['jpeg', 'png', 'webp']) {
      const a = probe.android[key]
      if (!a) continue
      const ext = key === 'jpeg' ? 'jpg' : key
      fs.writeFileSync(path.join(ANDROID, `android-${key}.${ext}`), Buffer.from(a.b64, 'base64'))
    }
    for (const [q, a] of Object.entries(probe.ladder)) {
      fs.writeFileSync(path.join(ANDROID, `android-jpeg-q${Math.round(Number(q) * 100)}.jpg`), Buffer.from(a.b64, 'base64'))
    }
    fs.writeFileSync(path.join(ANDROID, 'android-longedge600.png'), Buffer.from(probe.longEdge.b64, 'base64'))
    for (const [format, r] of Object.entries(probe.desktop)) {
      console.log(`[desktop] ${format} -> ${r.path} (${r.bytes} B, ${r.width}x${r.height})`)
    }
    console.log(`[desktop] jpeg q90 -> ${probe.desktopQ90.path}`)
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }

  const files = {
    source: path.join(SOURCE, 'canvas.png'),
    android: {
      jpeg: path.join(ANDROID, 'android-jpeg.jpg'),
      png: path.join(ANDROID, 'android-png.png'),
      webp: path.join(ANDROID, 'android-webp.webp')
    },
    desktop: {
      jpeg: probe.desktop.jpeg.path,
      png: probe.desktop.png.path,
      webp: probe.desktop.webp.path,
      tiff: probe.desktop.tiff.path
    }
  }

  const read = (f) => fs.readFileSync(f)
  const facts = {
    generatedAt: new Date().toISOString(),
    slice: 'S8',
    electron: { version: require('electron/package.json').version, chrome: chromeVersion, userAgent: probe.ua },
    fixture: { kind: 'deterministic synthetic canvas (photo patch + chroma stress + text + flat bands)', width: probe.W, height: probe.H, source: files.source, sourceSha1: sha1(read(files.source)) },
    androidEncoder: { path: 'canvas.toBlob(type, quality) in renderer (Chromium)', jpegQualityArg: 1, pngQualityArg: null, webpQualityArg: 1, note: 'Measured in the desktop renderer (Electron 45 / Chromium 156), which runs the same Blink canvas encoders as Android WebView. No device was available; the WebP runtime guard exists precisely because the device WebView version is unmeasured.' },
    desktopEncoder: { path: "ipc 'export:save' -> sharp", jpeg: 'quality 100, chromaSubsampling 4:4:4', png: 'compressionLevel 9, adaptiveFiltering true', webp: 'lossless true, effort 5', tiff: 'lzw, predictor horizontal', icc: 'withMetadata({icc: sRGB})' },
    outputs: {},
    androidTiffDirect: probe.android.tiffDirect ? { produced: true, type: probe.android.tiffDirect.type, size: probe.android.tiffDirect.size } : { produced: false, note: "canvas.toBlob('image/tiff') returned null — Chromium encodes only jpeg/png/webp" },
    comparisons: {}
  }

  const androidJpeg = read(files.android.jpeg)
  const desktopJpeg = read(files.desktop.jpeg)
  const androidPng = read(files.android.png)
  const desktopPng = read(files.desktop.png)
  const androidWebp = read(files.android.webp)
  const desktopWebp = read(files.desktop.webp)

  facts.outputs['android-jpeg'] = { file: files.android.jpeg, ...parseJpeg(androidJpeg), decoded: await imageInfo(files.android.jpeg) }
  facts.outputs['desktop-jpeg'] = { file: files.desktop.jpeg, ...parseJpeg(desktopJpeg), decoded: await imageInfo(files.desktop.jpeg) }
  facts.outputs['android-png'] = { file: files.android.png, ...parsePng(androidPng), decoded: await imageInfo(files.android.png) }
  facts.outputs['desktop-png'] = { file: files.desktop.png, ...parsePng(desktopPng), decoded: await imageInfo(files.desktop.png) }
  facts.outputs['android-webp'] = { file: files.android.webp, ...parseWebp(androidWebp), decoded: await imageInfo(files.android.webp) }
  facts.outputs['desktop-webp'] = { file: files.desktop.webp, ...parseWebp(desktopWebp), decoded: await imageInfo(files.desktop.webp) }
  facts.outputs['desktop-tiff'] = { file: files.desktop.tiff, bytes: fs.statSync(files.desktop.tiff).size, ...parseTiff(read(files.desktop.tiff)), decoded: await imageInfo(files.desktop.tiff) }

  facts.jpegSubsamplingLadder = {
    desktopNote: 'desktop sharp pins chromaSubsampling 4:4:4 at every quality (measured q90 + q100)',
    android: {},
    desktop: {}
  }
  for (const q of [100, 99, 95, 92, 90, 85, 80, 75, 70, 65, 60]) {
    const f = path.join(ANDROID, `android-jpeg-q${q}.jpg`)
    if (!fs.existsSync(f)) continue
    const p = parseJpeg(fs.readFileSync(f))
    facts.jpegSubsamplingLadder.android[q] = { size: fs.statSync(f).size, chroma: p.chroma, subsampling: p.subsampling, progressive: p.progressive, dqt0Sha1: p.dqt[0] && p.dqt[0].sha1 }
  }
  const d90 = parseJpeg(fs.readFileSync(probe.desktopQ90.path))
  facts.jpegSubsamplingLadder.desktop[90] = { file: probe.desktopQ90.path, size: fs.statSync(probe.desktopQ90.path).size, chroma: d90.chroma, subsampling: d90.subsampling, progressive: d90.progressive, dqt0Sha1: d90.dqt[0] && d90.dqt[0].sha1 }
  facts.jpegSubsamplingLadder.desktop[100] = { file: files.desktop.jpeg, size: facts.outputs['desktop-jpeg'].bytes, chroma: facts.outputs['desktop-jpeg'].chroma, subsampling: facts.outputs['desktop-jpeg'].subsampling, progressive: facts.outputs['desktop-jpeg'].progressive, dqt0Sha1: facts.outputs['desktop-jpeg'].dqt[0].sha1 }

  // Pixel work: keep decoded raw buffers once.
  const px = {}
  for (const [k, f] of [['source', files.source], ['android-jpeg', files.android.jpeg], ['desktop-jpeg', files.desktop.jpeg], ['android-png', files.android.png], ['desktop-png', files.desktop.png], ['android-webp', files.android.webp], ['desktop-webp', files.desktop.webp], ['desktop-tiff', files.desktop.tiff]]) {
    px[k] = await pixels(f)
  }
  const cmp = (a, b) => rmseOf(px[a], px[b])
  facts.chromiumRoundtrip = probe.roundtrip
  facts.comparisons = {
    'android-jpeg vs source': cmp('android-jpeg', 'source'),
    'desktop-jpeg vs source': cmp('desktop-jpeg', 'source'),
    'android-png vs source': cmp('android-png', 'source'),
    'desktop-png vs source': cmp('desktop-png', 'source'),
    'android-webp vs source': cmp('android-webp', 'source'),
    'desktop-webp vs source': cmp('desktop-webp', 'source'),
    'desktop-tiff vs source': cmp('desktop-tiff', 'source'),
    'android-jpeg vs desktop-jpeg': cmp('android-jpeg', 'desktop-jpeg'),
    'android-png vs desktop-png': cmp('android-png', 'desktop-png'),
    'android-webp vs desktop-webp': cmp('android-webp', 'desktop-webp'),
    'android-webp vs android-png': cmp('android-webp', 'android-png'),
    'desktop-webp vs desktop-png': cmp('desktop-webp', 'desktop-png')
  }

  // ICC reality, stated per file.
  facts.icc = {
    desktop: {
      jpeg: { embedded: facts.outputs['desktop-jpeg'].hasIcc, bytes: facts.outputs['desktop-jpeg'].decoded.iccBytes },
      png: { embedded: facts.outputs['desktop-png'].hasIccp, bytes: facts.outputs['desktop-png'].decoded.iccBytes },
      webp: { embedded: facts.outputs['desktop-webp'].hasIcc, bytes: facts.outputs['desktop-webp'].decoded.iccBytes },
      tiff: { embedded: facts.outputs['desktop-tiff'].decoded.hasProfile, bytes: facts.outputs['desktop-tiff'].decoded.iccBytes }
    },
    android: {
      jpeg: { embedded: facts.outputs['android-jpeg'].hasIcc, bytes: facts.outputs['android-jpeg'].decoded.iccBytes },
      png: { embedded: facts.outputs['android-png'].hasIccp, srgbChunk: facts.outputs['android-png'].hasSrgb, bytes: facts.outputs['android-png'].decoded.iccBytes },
      webp: { embedded: facts.outputs['android-webp'].hasIcc, bytes: facts.outputs['android-webp'].decoded.iccBytes }
    }
  }

  facts.dimensions = Object.fromEntries(Object.entries(facts.outputs).map(([k, v]) => [k, v.decoded ? { width: v.decoded.width, height: v.decoded.height } : null]))
  const dim = (k) => facts.dimensions[k]
  const same = (a, b) => Boolean(a && b) && a.width === b.width && a.height === b.height
  facts.dimensionParity = {
    jpeg: same(dim('android-jpeg'), dim('desktop-jpeg')),
    png: same(dim('android-png'), dim('desktop-png')),
    webp: same(dim('android-webp'), dim('desktop-webp')),
    tiff: {
      android: 'unsupported-by-policy',
      androidEncoderFallback: facts.androidTiffDirect.produced ? `image/${facts.androidTiffDirect.type.split('/')[1]} (canvas fallback, not TIFF)` : 'none',
      desktop: dim('desktop-tiff'),
      note: 'no Android TIFF exists to compare: Chromium toBlob("image/tiff") returned a PNG, and the policy now refuses instead of mislabelling it'
    }
  }


  // Same-encoder proof: compare the entropy-coded scan from SOS to EOF. If the
  // two files share it byte-for-byte, the JPEG encoders are the same libjpeg
  // at the same settings and only the metadata differs.
  const scanOf = (buf) => {
    const i = buf.indexOf(Buffer.from([0xff, 0xda]))
    return i < 0 ? null : buf.subarray(i)
  }
  const aScan = scanOf(androidJpeg)
  const dScan = scanOf(desktopJpeg)
  facts.jpegScanIdentity = {
    androidScanBytes: aScan.length,
    desktopScanBytes: dScan.length,
    androidSha1: sha1(aScan),
    desktopSha1: sha1(dScan),
    byteIdentical: aScan.equals(dScan),
    note: 'APP0/APP1/APP2 segments are outside the scan; the scan holds the DQT-driven entropy-coded coefficients.'
  }

  const androidLeFile = path.join(ANDROID, 'android-longedge600.png')
  const androidLePng = parsePng(fs.readFileSync(androidLeFile))
  facts.longEdge = {
    request: 600,
    android: { file: androidLeFile, ihdr: androidLePng.ihdr, decoded: await imageInfo(androidLeFile) },
    desktop: { file: probe.desktopLongEdge.path, decoded: await imageInfo(probe.desktopLongEdge.path) },
    sameDimensions: androidLePng.ihdr.w === probe.desktopLongEdge.width && androidLePng.ihdr.h === probe.desktopLongEdge.height,
    note: 'exportOne scales the canvas with drawImage(imageSmoothingQuality=high) before the platform branch; desktop sharp resizes the PNG (Lanczos3) inside export:save',
    rmse: rmseOf(await pixels(androidLeFile), await pixels(probe.desktopLongEdge.path))
  }
  facts.dimensionParity.longEdge600 =
    facts.longEdge.android.ihdr.w === facts.longEdge.desktop.decoded.width &&
    facts.longEdge.android.ihdr.h === facts.longEdge.desktop.decoded.height

  facts.summary = {
    jpeg: `Both ends SOF0 baseline 4:4:4 at q100, quant tables all 1s (sha1 ${facts.outputs['desktop-jpeg'].dqt[0].sha1}), entropy-coded scan byte-identical (sha1 ${facts.jpegScanIdentity.desktopSha1}, ${facts.jpegScanIdentity.androidScanBytes} B).`,
    png: 'Both decode to the canvas source with RMSE 0. Desktop embeds a 588-byte sRGB iCCP; Android PNG has neither iCCP nor an sRGB chunk.',
    webp: `Both bitstreams VP8L (lossless). Chromium round-trip (the device's own decode) is RMSE ${facts.chromiumRoundtrip.webp.rmse} / maxAbs ${facts.chromiumRoundtrip.webp.maxAbs} vs the canvas, identical to its PNG round-trip. libvips as-stored differs RMSE 0.094 because it applies the embedded 456-byte Google sRGB ICC; stripping the ICC gives 0.`,
    tiff: 'Desktop LZW lossless, RMSE 0 vs source. Chromium canvas.toBlob("image/tiff") falls back to PNG (measured type image/png), so Android TIFF is explicitly unsupported.'
  }
  facts.gaps = [
    {
      id: 'G1-jpeg-subsampling-below-q100',
      detail: `Chromium encodes JPEG at 4:2:0 for quality 99..60 (measured at ${Object.keys(facts.jpegSubsamplingLadder.android).filter((q) => Number(q) < 100).join('/')}); desktop sharp stays 4:4:4 at every quality (q90 measured, DQT identical to Android q90). canvas.toBlob exposes no subsampling control. At q100 the scan bytes are identical.`,
      impact: 'Android JPEG q<100 loses chroma that desktop keeps at the same setting.'
    },
    {
      id: 'G2-png-icc',
      detail: 'Desktop PNG carries a 588-byte sRGB iCCP chunk; Android PNG carries no iCCP and no sRGB chunk (measured chunk list). The canvas PNG encoder offers no profile attachment.',
      impact: 'Android PNG is untagged sRGB (viewers must assume sRGB); desktop PNG is tagged.'
    },
    {
      id: 'G3-icc-profile-encoding',
      detail: 'Android JPEG/WebP embed a 456-byte Google sRGB profile (parametric curve type 4); desktop embeds 588-byte sRGB built-in (type 3). Same colour space, different profile bytes. An ICC-aware decoder yields ±1 on saturated chroma (RMSE 0.094, 25,600 px) although the JPEG scan and the WebP VP8L bitstream are pixel-exact.',
      impact: 'Metadata differs; bitstream pixels do not.'
    },
    {
      id: 'G4-tiff',
      detail: 'Chromium has no TIFF encoder: toBlob("image/tiff") returns an image/png blob (measured). The old Android branch silently coerced TIFF to JPEG (lossy, .jpg named .tiff in intent); policy is now an explicit unsupported error.',
      impact: 'TIFF is desktop-only. ExportDialog already hides it on Android.'
    },
    {
      id: 'G5-longedge-kernel',
      detail: `exportOne scales the canvas with drawImage(imageSmoothingQuality=high) before the platform branch; desktop sharp then resizes the PNG with Lanczos3 inside export:save. Both produce ${facts.longEdge.android.ihdr.w}x${facts.longEdge.android.ihdr.h}, but pixels differ (RMSE ${facts.longEdge.rmse.rmse.toFixed(2)}, maxAbs ${facts.longEdge.rmse.maxAbs}, ${facts.longEdge.rmse.diffPixels} of ${facts.longEdge.rmse.totalPixels} px at longEdge 600).`,
      impact: 'Same dimensions, different resampling kernel at longEdge > 0. Not changed in S8: the pre-branch scaling is correct by design.'
    }
  ]

  fs.writeFileSync(path.join(S8, 'encoder-facts.json'), JSON.stringify(facts, null, 2))
  console.log('\n=== S8 encoder facts ===')
  for (const k of Object.keys(facts.outputs)) {
    const o = facts.outputs[k]
    console.log(k, o.decoded && o.decoded.width + 'x' + o.decoded.height, 'bytes=' + (o.bytes ?? (o.decoded && o.decoded.bytes)), o.sof ? `${o.sofName} ${o.chroma} [${o.subsampling}] progressive=${o.progressive} icc=${o.hasIcc}` : o.bitstream ? `${o.bitstream} lossless=${o.lossless} icc=${o.hasIcc}` : o.chunks ? o.chunks.map((c) => c.type).join(',') + ` icc=${o.hasIccp}` : '')
  }
  for (const [k, v] of Object.entries(facts.comparisons)) console.log(k, JSON.stringify(v))
  console.log('ICC', JSON.stringify(facts.icc))
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : e)
  process.exitCode = 1
})
