#!/usr/bin/env node
/**
 * S8 gate 2 — Android WebP must be lossless (VP8L) and must decode to the same
 * pixels as the PNG export of the same design.
 *
 * Measured caveat: the WebP container carries Chromium's 456-byte sRGB ICC
 * profile, and our libvips decoder colour-manages it to a 1-LSB difference on
 * saturated chroma. The VP8L bitstream itself is exact: stripping the ICC chunk
 * yields RMSE 0 against the Android PNG. This gate reports BOTH numbers so the
 * 0.094 can never be mistaken for codec loss.
 *
 * Also exercises the runtime guard `webpBitstreamKind` that exportAndroid uses,
 * including a negative control (a genuinely lossy WebP must report 'lossy').
 *
 * Writes _research/s8/webp-gate.json
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const sharp = require('sharp')
const esbuild = require('esbuild')

const ROOT = path.resolve(__dirname, '..')
const S8 = path.resolve(ROOT, '..', '_research', 's8')
const ANDROID = path.join(S8, 'android')
const SRC = path.join(S8, 'source', 'canvas.png')

function chunkList (buf) {
  const out = []
  let i = 12
  while (i + 8 <= buf.length) {
    const fourcc = buf.subarray(i, i + 4).toString('latin1')
    const len = buf.readUInt32LE(i + 4)
    out.push({ fourcc, len, offset: i })
    if (fourcc === 'VP8L') break
    i += 8 + len + (len & 1)
  }
  return out
}

async function raw (file) {
  return sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
}

function rmse (a, b) {
  const n = a.info.width * a.info.height
  let se = 0
  let max = 0
  for (let p = 0; p < n; p++) {
    for (const c of [0, 1, 2]) {
      const d = a.data[p * 4 + c] - b.data[p * 4 + c]
      se += d * d
      if (Math.abs(d) > max) max = Math.abs(d)
    }
  }
  return { rmse: Math.sqrt(se / (n * 3)), maxAbs: max }
}

async function main () {
  const result = { generatedAt: new Date().toISOString(), pass: false, errors: [] }

  // 1. Signature/chunk facts on the real Android output.
  const webpFile = path.join(ANDROID, 'android-webp.webp')
  const buf = fs.readFileSync(webpFile)
  const chunks = chunkList(buf)
  const vp8l = chunks.find((c) => c.fourcc === 'VP8L')
  const signature = vp8l ? buf[vp8l.offset + 8] : null
  result.container = {
    file: webpFile,
    bytes: buf.length,
    chunks,
    vp8lSignature: signature === null ? null : `0x${signature.toString(16).padStart(2, '0')}`,
    lossless: Boolean(vp8l) && signature === 0x2f
  }
  if (!result.container.lossless) result.errors.push('Android WebP is not a VP8L bitstream with the 0x2f signature')

  // 2. Pixels: VP8L vs the PNG export of the same canvas.
  const facts = JSON.parse(fs.readFileSync(path.join(S8, 'encoder-facts.json'), 'utf8'))
  const aPng = await raw(path.join(ANDROID, 'android-png.png'))
  const stripped = await raw(path.join(S8, 'android-webp-noicc.webp'))
  const original = await raw(webpFile)
  result.pixels = {
    'webp-noicc vs png (libvips decode)': rmse(stripped, aPng),
    'webp-as-stored vs png (libvips decode)': rmse(original, aPng),
    'chromium roundtrip webp vs canvas': facts.chromiumRoundtrip && facts.chromiumRoundtrip.webp,
    'chromium roundtrip png vs canvas': facts.chromiumRoundtrip && facts.chromiumRoundtrip.png,
    note: 'Chromium decodes its own VP8L blob to RMSE 0 vs the canvas; libvips differs only because it colour-manages the embedded 456-byte ICC (Google sRGB, parametric type 4). The bitstream is exact.'
  }
  if (result.pixels['webp-noicc vs png (libvips decode)'].rmse !== 0) result.errors.push('VP8L pixels differ from PNG after ICC normalisation')
  if (!facts.chromiumRoundtrip || facts.chromiumRoundtrip.webp.rmse !== 0) result.errors.push('Chromium round-trip of the WebP is not pixel-identical to the canvas')
  if (!facts.chromiumRoundtrip || facts.chromiumRoundtrip.png.rmse !== 0) result.errors.push('Chromium round-trip of the PNG is not pixel-identical to the canvas')

  // 3. The runtime guard exportAndroid now uses.
  const bundle = path.join(S8, '_encode-profile.cjs')
  esbuild.buildSync({
    entryPoints: [path.join(ROOT, 'src', 'renderer', 'src', 'core', 'encode-profile.ts')],
    bundle: true,
    platform: 'node',
    format: 'cjs',
    outfile: bundle,
    logLevel: 'silent'
  })
  const policy = require(bundle)
  const lossyFile = path.join(S8, 'lossy-probe.webp')
  await sharp(SRC).webp({ quality: 80 }).toFile(lossyFile)
  const kind = async (f) => policy.webpBitstreamKind(new Blob([fs.readFileSync(f)]))
  result.guard = {
    androidWebp: await kind(webpFile),
    lossyControl: await kind(lossyFile),
    garbage: await policy.webpBitstreamKind(new Blob([Buffer.from('not a webp at all')]))
  }
  if (result.guard.androidWebp !== 'lossless') result.errors.push('guard did not classify the Android WebP as lossless')
  if (result.guard.lossyControl !== 'lossy') result.errors.push('guard failed to classify the lossy control as lossy (negative control must fail)')

  result.pass = result.errors.length === 0
  fs.writeFileSync(path.join(S8, 'webp-gate.json'), JSON.stringify(result, null, 2))
  console.log(`${result.pass ? 'PASS' : 'FAIL'} S8 WebP gate`)
  console.log(JSON.stringify({ container: result.container, pixels: result.pixels, guard: result.guard }, null, 1))
  for (const e of result.errors) console.log('  ERROR', e)
  if (!result.pass) process.exitCode = 1
}

main().catch((e) => { console.error(e && e.stack ? e.stack : e); process.exitCode = 1 })
