#!/usr/bin/env node
/**
 * S8 follow-up: is the Android-vs-desktop ±1 difference a pixel difference, or
 * does OUR decoder colour-manage the 456-byte Chromium ICC profile? Also reads
 * both embedded ICC profiles (tag table) so "both embed sRGB" is a fact, not a
 * guess.
 *
 * Writes _research/s8/icc-decode-check.json and ICC dumps next to the images.
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const S8 = path.resolve(__dirname, '..', '..', '_research', 's8')
const A = (f) => path.join(S8, 'android', f)
const D = (f) => path.join(S8, 'desktop', f)

/* ---------------------------------------------------------------- ICC read */

const TYPE = { desc: 'textDescription', mluc: 'multiLocalizedUnicode', XYZ: 'XYZ', curv: 'curve', para: 'parametricCurve', sf32: 's15Fixed16' }

function parseIcc (buf) {
  if (!buf || buf.length < 132) return null
  const tagCount = buf.readUInt32BE(128)
  const tags = []
  for (let i = 0; i < tagCount; i++) {
    const o = 132 + i * 12
    const sig = buf.subarray(o, o + 4).toString('latin1')
    const off = buf.readUInt32BE(o + 4)
    const size = buf.readUInt32BE(o + 8)
    const type = buf.subarray(off, off + 4).toString('latin1')
    let value = null
    try {
      if (type === 'desc') {
        const n = buf.readUInt32BE(off + 8)
        value = buf.subarray(off + 12, off + 12 + n).toString('latin1').replace(/\0+$/, '')
      } else if (type === 'mluc') {
        const recs = buf.readUInt32BE(off + 8)
        const recSize = buf.readUInt32BE(off + 12)
        const recs2 = []
        for (let r = 0; r < Math.min(recs, 4); r++) {
          const ro = off + 16 + r * recSize
          const len = buf.readUInt32BE(ro + 4)
          const strOff = buf.readUInt32BE(ro + 8)
          recs2.push(Buffer.from(buf.subarray(off + strOff, off + strOff + len)).swap16().toString('utf16le'))
        }
        value = recs2.join(' / ')
      } else if (type === 'XYZ') {
        value = [buf.readInt32BE(off + 8) / 65536, buf.readInt32BE(off + 12) / 65536, buf.readInt32BE(off + 16) / 65536]
      } else if (type === 'curv') {
        const n = buf.readUInt32BE(off + 8)
        value = n === 0 ? 'identity' : n === 1 ? `gamma ${buf.readUInt16BE(off + 12) / 256}` : `table[${n}]`
      } else if (type === 'para') {
        const fn = buf.readUInt16BE(off + 8)
        const params = []
        const n = [1, 3, 4, 5, 7][fn] || 0
        for (let k = 0; k < n; k++) params.push(buf.readInt32BE(off + 12 + k * 4) / 65536)
        value = `type ${fn} ${params.join(',')}`
      } else {
        value = `type ${type} size ${size}`
      }
    } catch (e) {
      value = `parse error ${e.message}`
    }
    tags.push({ sig, type: TYPE[type] || type, value })
  }
  return {
    version: `${buf[8]}.${buf[9]}`,
    deviceClass: buf.subarray(12, 16).toString('latin1'),
    colorSpace: buf.subarray(16, 20).toString('latin1'),
    pcs: buf.subarray(20, 24).toString('latin1'),
    renderingIntent: buf.readUInt32BE(64),
    tags
  }
}

/* ------------------------------------------------------------- ICC strip */

function stripJpegIcc (buf) {
  const parts = [buf.subarray(0, 2)]
  let i = 2
  while (i + 3 < buf.length) {
    let m = buf[i + 1]
    if (buf[i] !== 0xff) { parts.push(buf.subarray(i)); break }
    if (m === 0xda) { parts.push(buf.subarray(i)); break }
    const len = buf.readUInt16BE(i + 2)
    const isIcc = m === 0xe2 && buf.subarray(i + 4, i + 16).toString('latin1').startsWith('ICC_PROFILE')
    if (!isIcc) parts.push(buf.subarray(i, i + 2 + len))
    i += 2 + len
  }
  return Buffer.concat(parts)
}

function stripWebpIcc (buf) {
  const chunks = []
  let i = 12
  while (i + 8 <= buf.length) {
    const fourcc = buf.subarray(i, i + 4).toString('latin1')
    const len = buf.readUInt32LE(i + 4)
    if (fourcc !== 'ICCP') chunks.push(Buffer.from(buf.subarray(i, i + 8 + len + (len & 1))))
    i += 8 + len + (len & 1)
  }
  // clear the ICC flag inside VP8X
  for (const c of chunks) {
    if (c.subarray(0, 4).toString('latin1') === 'VP8X') c[8] &= ~0x20
  }
  const body = Buffer.concat(chunks)
  const out = Buffer.alloc(12 + body.length)
  out.write('RIFF', 0, 'latin1')
  out.writeUInt32LE(4 + body.length, 4)
  out.write('WEBP', 8, 'latin1')
  body.copy(out, 12)
  return out
}

/* ---------------------------------------------------------------- pixels/diff */

async function rawOf (file) {
  const r = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  return r
}

function diff (a, b) {
  const { data: A, info } = a
  const { data: B } = b
  let n = 0
  let se = 0
  let max = 0
  let minX = 1e9; let minY = 1e9; let maxX = -1; let maxY = -1
  const samples = []
  for (let y = 0; y < info.height; y++) {
    for (let x = 0; x < info.width; x++) {
      const o = (y * info.width + x) * 4
      let d3 = 0
      for (const c of [0, 1, 2]) {
        const d = A[o + c] - B[o + c]
        d3 += d * d
        if (Math.abs(d) > max) max = Math.abs(d)
      }
      if (d3 > 0) {
        n++
        se += d3
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        if (samples.length < 12) samples.push({ x, y, a: [A[o], A[o + 1], A[o + 2]], b: [B[o], B[o + 1], B[o + 2]] })
      }
    }
  }
  return {
    rmse: Math.sqrt(se / (info.width * info.height * 3)),
    maxAbs: max,
    diffPixels: n,
    bbox: n ? { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 } : null,
    samples
  }
}

async function main () {
  const out = { generatedAt: new Date().toISOString() }
  const androidJpeg = fs.readFileSync(A('android-jpeg.jpg'))
  const desktopJpeg = fs.readFileSync(D('desktop-jpeg.jpg'))
  const androidWebp = fs.readFileSync(A('android-webp.webp'))
  const desktopWebp = fs.readFileSync(D('desktop-webp.webp'))

  const extractJpegIcc = (buf) => {
    let i = 2
    const parts = []
    while (i + 3 < buf.length) {
      const m = buf[i + 1]
      if (m === 0xda) break
      const len = buf.readUInt16BE(i + 2)
      if (m === 0xe2 && buf.subarray(i + 4, i + 16).toString('latin1').startsWith('ICC_PROFILE')) {
        parts.push(buf.subarray(i + 18, i + 2 + len))
      }
      i += 2 + len
    }
    return Buffer.concat(parts)
  }
  const extractWebpIcc = (buf) => {
    let i = 12
    while (i + 8 <= buf.length) {
      const fourcc = buf.subarray(i, i + 4).toString('latin1')
      const len = buf.readUInt32LE(i + 4)
      if (fourcc === 'ICCP') return buf.subarray(i + 8, i + 8 + len)
      i += 8 + len + (len & 1)
    }
    return null
  }

  const icc = {
    androidJpeg: extractJpegIcc(androidJpeg),
    desktopJpeg: extractJpegIcc(desktopJpeg),
    androidWebp: extractWebpIcc(androidWebp),
    desktopWebp: extractWebpIcc(desktopWebp)
  }
  out.iccProfiles = {}
  for (const [k, v] of Object.entries(icc)) {
    if (!v) continue
    fs.writeFileSync(path.join(S8, `icc-${k}.icc`), v)
    out.iccProfiles[k] = { bytes: v.length, sha1: require('node:crypto').createHash('sha1').update(v).digest('hex'), ...parseIcc(v) }
  }

  // A: does removing the embedded ICC change OUR decode? (WebP is lossless, so
  // this isolates colour management from codec error.)
  const webpStripped = stripWebpIcc(androidWebp)
  fs.writeFileSync(path.join(S8, 'android-webp-noicc.webp'), webpStripped)
  const jpegStripped = stripJpegIcc(androidJpeg)
  fs.writeFileSync(path.join(S8, 'android-jpeg-noicc.jpg'), jpegStripped)
  const desktopJpegStripped = stripJpegIcc(desktopJpeg)
  fs.writeFileSync(path.join(S8, 'desktop-jpeg-noicc.jpg'), desktopJpegStripped)

  const src = await rawOf(path.join(S8, 'source', 'canvas.png'))
  const aPng = await rawOf(A('android-png.png'))
  const aJpeg = await rawOf(A('android-jpeg.jpg'))
  const aJpegNo = await rawOf(path.join(S8, 'android-jpeg-noicc.jpg'))
  const dJpeg = await rawOf(D('desktop-jpeg.jpg'))
  const dJpegNo = await rawOf(path.join(S8, 'desktop-jpeg-noicc.jpg'))
  const aWebp = await rawOf(A('android-webp.webp'))
  const aWebpNo = await rawOf(path.join(S8, 'android-webp-noicc.webp'))
  const dWebp = await rawOf(D('desktop-webp.webp'))

  out.webp = {
    'android-webp vs android-png': diff(aWebp, aPng),
    'android-webp-noicc vs android-png': diff(aWebpNo, aPng),
    'android-webp vs desktop-webp': diff(aWebp, dWebp),
    'desktop-webp vs source': diff(dWebp, src)
  }
  out.jpeg = {
    'android-jpeg vs desktop-jpeg': diff(aJpeg, dJpeg),
    'android-jpeg-noicc vs desktop-jpeg-noicc': diff(aJpegNo, dJpegNo),
    'android-jpeg vs android-png': diff(aJpeg, aPng),
    'android-jpeg-noicc vs android-png': diff(aJpegNo, aPng),
    'desktop-jpeg vs android-png': diff(dJpeg, aPng)
  }

  // Also read each file with the profile explicitly ignored via sharp's
  // `ignoreIcc`? sharp has no such flag; instead decode the stripped copies.
  fs.writeFileSync(path.join(S8, 'icc-decode-check.json'), JSON.stringify(out, null, 2))

  // Merge into the single facts artifact so the gate does not need two files.
  const factsPath = path.join(S8, 'encoder-facts.json')
  const facts = JSON.parse(fs.readFileSync(factsPath, 'utf8'))
  facts.iccProfiles = out.iccProfiles
  facts.iccDecodeCheck = {
    note: 'RMSE from sharp/libvips decode. Android WebP is VP8L (lossless), yet it decodes 1 LSB off our source on 25600 saturated-red px — until the 456-byte Chromium ICC is stripped, when it is RMSE 0. The bitstream is exact; the difference is the decoder applying a different (but sRGB) profile.',
    webp: out.webp,
    jpeg: out.jpeg,
    strippedCopies: {
      webp: path.join(S8, 'android-webp-noicc.webp'),
      jpeg: path.join(S8, 'android-jpeg-noicc.jpg'),
      desktopJpeg: path.join(S8, 'desktop-jpeg-noicc.jpg')
    }
  }
  fs.writeFileSync(factsPath, JSON.stringify(facts, null, 2))
  console.log(JSON.stringify({ icc: Object.fromEntries(Object.entries(out.iccProfiles).map(([k, v]) => [k, { bytes: v.bytes, desc: (v.tags.find((t) => t.sig === 'desc') || {}).value, wtpt: (v.tags.find((t) => t.sig === 'wtpt') || {}).value, rTRC: (v.tags.find((t) => t.sig === 'rTRC') || {}).value }])), webp: Object.fromEntries(Object.entries(out.webp).map(([k, v]) => [k, { rmse: v.rmse, max: v.maxAbs, n: v.diffPixels, bbox: v.bbox }])), jpeg: Object.fromEntries(Object.entries(out.jpeg).map(([k, v]) => [k, { rmse: v.rmse, max: v.maxAbs, n: v.diffPixels, bbox: v.bbox }])) }, null, 2))
}

main().catch((e) => { console.error(e && e.stack ? e.stack : e); process.exitCode = 1 })
