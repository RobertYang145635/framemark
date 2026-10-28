#!/usr/bin/env node
/**
 * S8 policy gate: the renderer (core/encode-profile.ts) and the main process
 * (src/main/index.ts) describe the same encode policy in two separate bundles.
 * This script parses both S8-POLICY blocks and fails if any shared value drifts,
 * or if either side stops binding its encoder calls to the block.
 *
 * Usage: node scripts/_s8-policy-check.cjs
 * Writes: D:\border&watermark\_research\s8\policy-check.json
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '..')
const S8 = path.resolve(ROOT, '..', '_research', 's8')
const RENDERER = path.join(ROOT, 'src', 'renderer', 'src', 'core', 'encode-profile.ts')
const MAIN = path.join(ROOT, 'src', 'main', 'index.ts')
const EXPORTER = path.join(ROOT, 'src', 'renderer', 'src', 'core', 'exporter.ts')

const SHARED = ['quality', 'subsampling', 'subsamplingAndroid', 'lossless', 'icc', 'iccAndroid']

function blockOf (file) {
  const text = fs.readFileSync(file, 'utf8')
  const m = text.match(/\/\* S8-POLICY-BEGIN \*\/([\s\S]*?)\/\* S8-POLICY-END \*\//)
  if (!m) throw new Error(`${file}: S8-POLICY markers missing`)
  const body = m[1]
  const start = body.indexOf('{')
  const end = body.lastIndexOf('}')
  if (start < 0 || end <= start) throw new Error(`${file}: no object literal between markers`)
  const literal = body.slice(start, end + 1)
  // The block is deliberately plain JS data: safe to evaluate here.
  return { value: new Function(`return (${literal})`)(), literal }
}

function main () {
  const result = { generatedAt: new Date().toISOString(), pass: false, mismatches: [], checks: [] }
  const rendererBlock = blockOf(RENDERER)
  const mainBlock = blockOf(MAIN)

  const rFormats = rendererBlock.value.formats
  const mFormats = mainBlock.value
  const rKeys = Object.keys(rFormats)
  const mKeys = Object.keys(mFormats)
  if (rKeys.join(',') !== mKeys.join(',')) result.mismatches.push(`format keys differ: renderer [${rKeys}] vs main [${mKeys}]`)

  const compared = {}
  for (const fmt of rKeys) {
    if (!mFormats[fmt]) continue
    const row = {}
    for (const field of SHARED) {
      const r = rFormats[fmt][field]
      const m = mFormats[fmt][field]
      row[field] = r
      if (JSON.stringify(r) !== JSON.stringify(m)) {
        result.mismatches.push(`${fmt}.${field}: renderer=${JSON.stringify(r)} main=${JSON.stringify(m)}`)
      }
    }
    compared[fmt] = row
  }
  result.compared = compared

  // The values must be traceable to one place: the renderer policy must be the
  // only place that decides Android behaviour, and the main block must actually
  // feed the sharp calls (not sit there as dead documentation).
  const exporterText = fs.readFileSync(EXPORTER, 'utf8')
  const mainText = fs.readFileSync(MAIN, 'utf8')
  const bindings = [
    ['renderer exports the policy', RENDERER, /export const ENCODE_PROFILE = \{/],
    ['exportAndroid reads the policy', EXPORTER, /ENCODE_PROFILE\.formats\[design\.export\.format\]/],
    ['exportAndroid refuses unsupported formats', EXPORTER, /rule\.android === 'unsupported'/],
    ['exportAndroid enforces the lossless WebP guard', EXPORTER, /webpBitstreamKind\(blob\)/],
    ['exportAndroid consumes EXIF/GPS policy', EXPORTER, /ENCODE_PROFILE\.gps\.removeByDefault/],
    ['main jpeg reads the block', MAIN, /chromaSubsampling: EXPORT_ENCODE\.jpeg\.subsampling/],
    ['main png reads the block', MAIN, /compressionLevel: EXPORT_ENCODE\.png\.compressionLevel/],
    ['main webp reads the block', MAIN, /lossless: EXPORT_ENCODE\.webp\.lossless/],
    ['main tiff reads the block', MAIN, /compression: EXPORT_ENCODE\.tiff\.compression/]
  ]
  for (const [name, file, re] of bindings) {
    const text = file === MAIN ? mainText : file === EXPORTER ? exporterText : fs.readFileSync(file, 'utf8')
    const ok = re.test(text)
    result.checks.push({ name, ok })
    if (!ok) result.mismatches.push(`binding missing: ${name}`)
  }

  // Explicit policy decisions that the measurement forced.
  if (rFormats.tiff.android !== 'unsupported') result.mismatches.push('tiff must be android:unsupported (measured: toBlob returns a PNG fallback)')
  if (rFormats.webp.androidQuality !== 1 || rFormats.webp.lossless !== true) result.mismatches.push('webp must be lossless with androidQuality 1 (measured VP8L)')
  if (rFormats.png.iccAndroid !== 'absent') result.mismatches.push('png.iccAndroid must record the measured absence of an ICC profile')

  result.pass = result.mismatches.length === 0
  fs.mkdirSync(S8, { recursive: true })
  fs.writeFileSync(path.join(S8, 'policy-check.json'), JSON.stringify(result, null, 2))
  console.log(`${result.pass ? 'PASS' : 'FAIL'} S8 policy check — ${result.checks.filter((c) => c.ok).length}/${result.checks.length} bindings, ${SHARED.length} fields x ${rKeys.length} formats`)
  for (const m of result.mismatches) console.log(`  MISMATCH ${m}`)
  if (!result.pass) process.exitCode = 1
}

main()
