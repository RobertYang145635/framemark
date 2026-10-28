/**
 * A3 gate 1 + gate 2 — TIFF source round trip and TIFF drop failure.
 *
 * GATE 1 (round trip, desktop): a sharp-generated 1000x700 .tif is imported
 * through the real folder path (dialog IPC -> image:preview -> photoFromPath),
 * exported at 原始尺寸 as JPG and as TIFF. Both outputs must exist, open with
 * sharp and keep the source width / at least the source height.
 *   BEFORE the fix: the export fails with
 *   `InvalidStateError: The source image could not be decoded.` and 0 files are
 *   written, because core/photos.ts loadFullBitmap re-decodes the TIFF bytes
 *   with createImageBitmap.
 *
 * GATE 2 (drag/drop): the same .tif dropped on the app shell must fail
 * gracefully — no pageerror, no thumbnail, one readable 「N 张照片读取失败」
 * toast (the user-facing counter), i.e. the file is counted as failed.
 *
 * Usage: node scripts/_a3-tiff-roundtrip.cjs --tag=before|after
 * Writes _research/a3/tiff-roundtrip-<tag>.json (+ screenshot).
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const requireHere = createRequire(__filename)
const sharp = requireHere('sharp')
const lib = require('./_a3-lib.cjs')

const tag = (process.argv.find((a) => a.startsWith('--tag=')) || '--tag=after').split('=')[1]
const runRoot = lib.ensureDir(path.join(lib.a3Dir, `run-tiff-${tag}`))
const fixtures = lib.ensureDir(path.join(lib.a3Dir, 'fixtures'))
const importDir = lib.cleanDir(path.join(runRoot, 'import'))
const outDir = lib.cleanDir(path.join(runRoot, 'out-jpg-tiff'))
const profile = path.join(runRoot, 'profile')

const tiffPath = path.join(fixtures, 'a3-photo.tif')

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok: Boolean(ok), detail: String(detail) })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  return ok
}

async function makeFixture() {
  // A deterministic 1000x700 RGB gradient/checker: enough structure that a
  // wrong decode could not accidentally match, small enough to stay fast.
  const w = 1000
  const h = 700
  const raw = Buffer.alloc(w * h * 3)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3
      const block = ((x >> 5) + (y >> 5)) % 2 === 0 ? 40 : 0
      raw[i] = Math.min(255, Math.round((x / (w - 1)) * 255) + block)
      raw[i + 1] = Math.min(255, Math.round((y / (h - 1)) * 255) + block)
      raw[i + 2] = Math.min(255, 80 + Math.round(((x + y) / (w + h)) * 120))
    }
  }
  await sharp(raw, { raw: { width: w, height: h, channels: 3 } })
    .toColourspace('srgb')
    .tiff({ compression: 'lzw', predictor: 'horizontal' })
    .toFile(tiffPath)
  const meta = await sharp(tiffPath).metadata()
  return { path: tiffPath, bytes: fs.statSync(tiffPath).size, width: meta.width, height: meta.height, format: meta.format }
}

async function dropTiff(page) {
  const b64 = fs.readFileSync(tiffPath).toString('base64')
  await page.evaluate(
    ({ b64: data, name }) => {
      const bin = atob(data)
      const arr = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i)
      const file = new File([arr], name, { type: 'image/tiff' })
      const dt = new DataTransfer()
      dt.items.add(file)
      const shell = document.querySelector('.app-shell')
      for (const type of ['dragenter', 'dragover', 'drop']) {
        shell.dispatchEvent(new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: dt }))
      }
    },
    { b64, name: path.basename(tiffPath) }
  )
}

async function main() {
  const fixture = await makeFixture()
  console.log(`fixture ${fixture.width}x${fixture.height} ${fixture.format} ${fixture.bytes} B`)
  fs.copyFileSync(tiffPath, path.join(importDir, path.basename(tiffPath)))

  const { app, page, pageErrors, consoleErrors } = await lib.launch(profile)
  const result = {
    tag,
    fixture,
    gates: {},
    checks,
    pageErrors,
    consoleErrors,
    screenshots: []
  }
  try {
    // ---------------- GATE 2: drag/drop must fail gracefully ----------------
    console.log('\n--- GATE 2: drop .tif ---')
    await dropTiff(page)
    await lib.sleep(2500)
    const thumbsAfterDrop = await page.locator('.thumb').count()
    const toast = await lib.toastText(page)
    await page.screenshot({ path: path.join(runRoot, 'drop-tiff.png') })
    result.screenshots.push(path.join(runRoot, 'drop-tiff.png'))
    check('drop: no thumbnail was created', thumbsAfterDrop === 0, `thumbs=${thumbsAfterDrop}`)
    check('drop: toast is a readable failure count', /读取失败/.test(toast), toast || '(no toast)')
    check('drop: no pageerror (failure was caught)', pageErrors.length === 0, pageErrors.join(' | '))
    check('drop: no stack-trace-looking text in UI', !/at\s+\w+\s+\(|<anonymous>|InvalidStateError/.test(toast), toast)
    result.gates.drop = { thumbsAfterDrop, toast, pageErrors: [...pageErrors] }

    // ---------------- GATE 1: folder import -> export -----------------------
    console.log('\n--- GATE 1: folder import .tif -> export ---')
    const accept = await page.getAttribute('#framemark-file-input', 'accept')
    result.desktopAccept = accept
    check('desktop accept still offers TIFF', /image\/tiff/i.test(accept || ''), accept || '(none)')
    await lib.importFolder(page, app, importDir, 1)
    check('folder import: one thumbnail', (await page.locator('.thumb').count()) === 1)

    await lib.openExportDialog(page)
    check('dialog: JPG is the active format', (await lib.activeFormatLabel(page)) === 'JPG')
    await lib.ensureOriginalSize(page)
    await lib.pickOutDir(app, page, outDir)
    const jpgRun = await lib.startExport(page)
    const jpgFiles = lib.listFiles(outDir)
    result.gates.jpg = { ...jpgRun, files: jpgFiles }
    check('JPG export: completed without errors', jpgRun.errors.length === 0, JSON.stringify(jpgRun.errors))
    check('JPG export: exactly one file written', jpgFiles.length === 1, jpgFiles.join(', ') || '(none)')
    if (jpgFiles.length === 1) {
      const meta = await sharp(path.join(outDir, jpgFiles[0])).metadata()
      result.gates.jpg.meta = { format: meta.format, width: meta.width, height: meta.height }
      check('JPG output opens with sharp', meta.format === 'jpeg', String(meta.format))
      check('JPG output keeps source width', meta.width === fixture.width, `${meta.width} vs ${fixture.width}`)
      check(
        'JPG output height >= source height',
        (meta.height ?? 0) >= fixture.height,
        `${meta.width}x${meta.height}`
      )
    }
    await lib.closeExportOverlay(page)

    // Round trip means the same source also survives a TIFF destination.
    await lib.openExportDialog(page)
    await lib.chooseFormat(page, 'TIFF')
    await lib.ensureOriginalSize(page)
    await lib.pickOutDir(app, page, outDir)
    const tiffRun = await lib.startExport(page)
    const allFiles = lib.listFiles(outDir)
    const tiffFiles = allFiles.filter((f) => /\.tiff$/i.test(f))
    result.gates.tiff = { ...tiffRun, files: tiffFiles, allFiles }
    check('TIFF export: completed without errors', tiffRun.errors.length === 0, JSON.stringify(tiffRun.errors))
    check('TIFF export: exactly one .tiff written', tiffFiles.length === 1, allFiles.join(', '))
    if (tiffFiles.length === 1) {
      const meta = await sharp(path.join(outDir, tiffFiles[0])).metadata()
      result.gates.tiff.meta = { format: meta.format, width: meta.width, height: meta.height }
      check('TIFF output opens with sharp', meta.format === 'tiff', String(meta.format))
      check('TIFF output keeps source width', meta.width === fixture.width, `${meta.width} vs ${fixture.width}`)
      check(
        'TIFF output height >= source height',
        (meta.height ?? 0) >= fixture.height,
        `${meta.width}x${meta.height}`
      )
    }
    await lib.closeExportOverlay(page)
  } finally {
    await app.close().catch(() => undefined)
  }

  result.pageErrors = pageErrors
  result.consoleErrors = consoleErrors
  result.gates.pageErrors = pageErrors.length
  const failed = checks.filter((c) => !c.ok)
  result.pass = failed.length === 0
  result.failed = failed
  lib.writeJson(path.join(lib.a3Dir, `tiff-roundtrip-${tag}.json`), result)
  console.log(`\n=== ${result.pass ? 'PASS' : 'FAIL'} === ${checks.length - failed.length}/${checks.length} checks`)
  if (failed.length) console.log(failed.map((f) => `- ${f.name}: ${f.detail}`).join('\n'))

  if (tag === 'before') {
    // The BEFORE run is expected to reproduce the two known failures. Exit 0
    // only when it actually did (otherwise the harness is not exercising them).
    const reproduced =
      failed.some((f) => f.name === 'JPG export: exactly one file written') &&
      checks.some((c) => c.name === 'drop: toast is a readable failure count' && c.ok)
    console.log(reproduced ? 'BEFORE failure reproduced as expected' : 'BEFORE failure NOT reproduced')
    process.exit(reproduced ? 0 : 1)
  }
  process.exit(result.pass ? 0 : 1)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
