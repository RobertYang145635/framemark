/**
 * A3 gate 4 — no-regression byte identity for the common (JPEG source) paths.
 *
 * Imports ONE existing JPEG fixture through the real folder path, exports it at
 * 原始尺寸 as JPG (q100) and PNG, and records sha256 + dimensions of every
 * output. Run `--tag=before` on the pre-change build and `--tag=after` on the
 * new build with the SAME fixture and the SAME default design; the two JSON
 * files are compared by scripts/_a3-compare-baseline.cjs (or manually).
 *
 * Usage: node scripts/_a3-baseline-encode.cjs --tag=before|after
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { createRequire } = require('node:module')
const requireHere = createRequire(__filename)
const sharp = requireHere('sharp')
const lib = require('./_a3-lib.cjs')

const tag = (process.argv.find((a) => a.startsWith('--tag=')) || '--tag=after').split('=')[1]
const runRoot = lib.ensureDir(path.join(lib.a3Dir, `run-baseline-${tag}`))
const importDir = lib.cleanDir(path.join(runRoot, 'import'))
const outDir = lib.cleanDir(path.join(runRoot, 'out'))
const profile = path.join(runRoot, 'profile')
const source = path.join(lib.workspace, '_research', 'test-assets', 'ratio-study', '4x3.jpg')

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok: Boolean(ok), detail: String(detail) })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  return ok
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
}

async function exportFormat(page, app, label, outDirForRun) {
  await lib.openExportDialog(page)
  await lib.chooseFormat(page, label)
  await lib.ensureOriginalSize(page)
  await lib.pickOutDir(app, page, outDirForRun)
  const run = await lib.startExport(page)
  await lib.closeExportOverlay(page)
  return run
}

async function main() {
  if (!fs.existsSync(source)) throw new Error(`fixture missing: ${source}`)
  fs.copyFileSync(source, path.join(importDir, 'a3-base.jpg'))
  const srcMeta = await sharp(source).metadata()

  const { app, page, pageErrors } = await lib.launch(profile)
  const result = { tag, source: { path: source, width: srcMeta.width, height: srcMeta.height, bytes: fs.statSync(source).size }, checks, outputs: {}, pageErrors }
  try {
    await lib.importFolder(page, app, importDir, 1)
    check('import: one thumbnail', (await page.locator('.thumb').count()) === 1)

    const jpgRun = await exportFormat(page, app, 'JPG', outDir)
    const jpgFiles = lib.listFiles(outDir)
    check('JPG: export completed', jpgRun.errors.length === 0, JSON.stringify(jpgRun.errors))
    check('JPG: exactly one file', jpgFiles.length === 1, jpgFiles.join(', '))
    if (jpgFiles.length === 1) {
      const file = path.join(outDir, jpgFiles[0])
      const meta = await sharp(file).metadata()
      result.outputs.jpeg = {
        file: jpgFiles[0],
        sha256: sha256(file),
        bytes: fs.statSync(file).size,
        width: meta.width,
        height: meta.height,
        format: meta.format
      }
      console.log(`  jpeg ${jpgFiles[0]} ${meta.width}x${meta.height} ${result.outputs.jpeg.bytes} B sha=${result.outputs.jpeg.sha256}`)
    }

    // A design change (format) starts a NEW task; the output folder keeps both.
    const pngRun = await exportFormat(page, app, 'PNG', outDir)
    const files = lib.listFiles(outDir)
    const pngFiles = files.filter((f) => /\.png$/i.test(f))
    check('PNG: export completed', pngRun.errors.length === 0, JSON.stringify(pngRun.errors))
    check('PNG: exactly one file', pngFiles.length === 1, files.join(', '))
    if (pngFiles.length === 1) {
      const file = path.join(outDir, pngFiles[0])
      const meta = await sharp(file).metadata()
      result.outputs.png = {
        file: pngFiles[0],
        sha256: sha256(file),
        bytes: fs.statSync(file).size,
        width: meta.width,
        height: meta.height,
        format: meta.format
      }
      console.log(`  png  ${pngFiles[0]} ${meta.width}x${meta.height} ${result.outputs.png.bytes} B sha=${result.outputs.png.sha256}`)
    }
    result.pageErrors = pageErrors
    check('no pageerror', pageErrors.length === 0, pageErrors.join(' | '))
  } finally {
    await app.close().catch(() => undefined)
  }
  const failed = checks.filter((c) => !c.ok)
  result.pass = failed.length === 0
  result.failed = failed
  lib.writeJson(path.join(lib.a3Dir, `baseline-encode-${tag}.json`), result)
  console.log(`\n=== ${result.pass ? 'PASS' : 'FAIL'} === ${checks.length - failed.length}/${checks.length}`)
  process.exit(result.pass ? 0 : 1)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
