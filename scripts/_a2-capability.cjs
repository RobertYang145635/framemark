/**
 * A2 part 2 — TIFF capability matrix, measured on the real app build.
 *
 * ONE Electron instance (throw-away --user-data-dir, same launch pattern as
 * scripts/e2e.mjs:40-58) runs four probes in this order:
 *
 *   D. photo.jpg  -> export TIFF               (expected: works, verify with sharp)
 *      page reload (state reset)
 *   B. drop photo.tif onto .app-shell          (expected: fails in createImageBitmap)
 *   A. folder import of import/tiff/photo.tif  (expected: works, sharp path)
 *   C. export of that TIFF-sourced photo       (expected: fails at loadFullBitmap)
 *
 * Every failure message is captured from the product, not paraphrased: the
 * toast / .export-errors text for the UI, plus the raw DOMException of the exact
 * call (`createImageBitmap`) that threw. Output: _research/a2/capability-raw.json
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const requireHere = createRequire(__filename)
const { _electron: electron } = requireHere('playwright-core')
const sharp = requireHere('sharp')

const REPO = path.resolve(__dirname, '..')
const A2 = path.resolve(REPO, '..', '_research', 'a2')
const TIF_DIR = path.join(A2, 'import', 'tiff')
const TIF = path.join(TIF_DIR, 'photo.tif')
const JPG = path.join(A2, 'fixtures', 'photo.jpg')
const OUT_DIR = path.join(A2, 'out', 'exports')
const PROFILE = path.join(A2, 'tmp', 'capability-profile')

const results = []
function record(row) {
  results.push(row)
  console.log(`[${row.status}] ${row.id}: ${row.observed}`)
}

/** Minimal TIFF IFD reader: proves LZW (tag 259 = 5) + horizontal predictor (317 = 2). */
function tiffTags(file) {
  const b = fs.readFileSync(file)
  const le = b.toString('ascii', 0, 2) === 'II'
  const u16 = (o) => (le ? b.readUInt16LE(o) : b.readUInt16BE(o))
  const u32 = (o) => (le ? b.readUInt32LE(o) : b.readUInt32BE(o))
  const ifd = u32(4)
  const n = u16(ifd)
  const tags = {}
  for (let i = 0; i < n; i++) {
    const e = ifd + 2 + i * 12
    tags[u16(e)] = u16(e + 8)
  }
  return { width: tags[256], height: tags[257], compression: tags[259], photometric: tags[260], predictor: tags[317] }
}

async function patchOpenDialog(app, target) {
  await app.evaluate(async ({ dialog }, t) => {
    if (!globalThis.__a2Orig) globalThis.__a2Orig = dialog.showOpenDialog
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [t] })
  }, target)
}
async function restoreOpenDialog(app) {
  await app.evaluate(async ({ dialog }) => {
    if (globalThis.__a2Orig) dialog.showOpenDialog = globalThis.__a2Orig
  })
}

async function chooseFormat(page, label) {
  const field = page.locator('.export-dialog label.field', { hasText: '格式' })
  await field.locator('button', { hasText: label, exact: true }).click()
  await page.waitForFunction(
    (l) => {
      const fields = [...document.querySelectorAll('.export-dialog label.field')]
      const sec = fields.find((f) => f.textContent?.includes('格式'))
      return sec?.querySelector('.segmented button.active')?.textContent?.trim() === l
    },
    label,
    { timeout: 10000 }
  )
}

async function setOutDir(app, page, dir) {
  const current = await page.locator('.export-dir-row input').inputValue()
  if (current === dir) return 'already set'
  await patchOpenDialog(app, dir)
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, dir, { timeout: 15000 })
  await restoreOpenDialog(app)
  return 'picked'
}

async function waitCardDone(page) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(() => {
    const h = document.querySelector('.export-card h3')
    return h && h.textContent && !h.textContent.includes('正在导出')
  }, null, { timeout: 180000 })
}

async function cardText(page) {
  return page.evaluate(() => {
    const card = document.querySelector('.export-card')
    if (!card) return null
    return {
      heading: card.querySelector('h3')?.textContent?.trim() ?? '',
      body: [...card.querySelectorAll('p')].map((p) => p.textContent?.trim()).join(' | '),
      errors: [...card.querySelectorAll('.export-errors div')].map((d) => ({
        name: d.querySelector('strong')?.textContent?.trim() ?? '',
        message: d.querySelector('span')?.textContent?.trim() ?? ''
      }))
    }
  })
}

async function closeCard(page) {
  const x = page.locator('.export-card button[title="关闭"]').first()
  if (await x.count()) await x.click({ timeout: 5000 }).catch(() => undefined)
  await page.waitForSelector('.export-card', { state: 'detached', timeout: 15000 }).catch(() => undefined)
}

async function waitToast(page, ms = 15000) {
  const start = Date.now()
  while (Date.now() - start < ms) {
    const t = await toasts(page)
    if (t.length) return t
    await page.waitForTimeout(200)
  }
  return []
}

async function toasts(page) {
  return page.evaluate(() => [...document.querySelectorAll('.toast')].map((t) => t.textContent?.replace(/\s+/g, ' ').trim()))
}

async function main() {
  for (const d of [OUT_DIR, PROFILE]) fs.rmSync(d, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.mkdirSync(PROFILE, { recursive: true })
  if (!fs.existsSync(TIF)) throw new Error(`missing TIFF fixture ${TIF}`)

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: requireHere('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`],
    cwd: REPO,
    env,
    timeout: 60000
  })

  try {
    const page = await app.firstWindow()
    const versions = await app.evaluate(({ app: a }) => ({
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      appVersion: a.getVersion()
    }))
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

    /* ---------------------------------------------------------------- D --- */
    {
      await page.setInputFiles('#framemark-file-input', JPG)
      await page.waitForSelector('.thumb', { timeout: 120000 })
      await page.locator('.topbar-actions .btn-primary').click()
      await page.waitForSelector('.export-dialog', { timeout: 10000 })
      await chooseFormat(page, 'TIFF')
      const dirState = await setOutDir(app, page, OUT_DIR)
      await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
      await waitCardDone(page)
      const card = await cardText(page)
      await closeCard(page)

      const files = fs.readdirSync(OUT_DIR).filter((f) => /\.tiff?$/i.test(f))
      let meta = null
      if (files.length) {
        const m = await sharp(path.join(OUT_DIR, files[0])).metadata()
        meta = {
          file: files[0],
          bytes: fs.statSync(path.join(OUT_DIR, files[0])).size,
          format: m.format,
          width: m.width,
          height: m.height,
          compression: m.compression,
          bitdepth: m.depth,
          predictor: m.predictor ?? null,
          space: m.space,
          hasProfile: m.hasProfile,
          tags: tiffTags(path.join(OUT_DIR, files[0]))
        }
      }
      record({
        id: 'D',
        path: 'desktop export: JPEG-sourced photo -> TIFF',
        probe: 'setInputFiles(fixtures/photo.jpg) -> 导出当前 -> 格式 TIFF -> 开始导出',
        expected: 'works (sharp tiff lzw)',
        observed: files.length
          ? `${files.length} .tiff written; card "${card.heading} ${card.body}"; sharp: ${JSON.stringify(meta)}; outDir ${dirState}`
          : `NO tiff written; card "${card.heading} ${card.body}"`,
        status: files.length === 1 && meta && meta.format === 'tiff' ? 'PASS' : 'FAIL',
        evidence: { card, meta }
      })
    }

    /* ------------------------------------------------------- reset state -- */
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
    await page.waitForTimeout(1200)
    const afterReload = await page.locator('.thumb').count()

    /* ---------------------------------------------------------------- B --- */
    {
      const tifB64 = fs.readFileSync(TIF).toString('base64')
      const probe = await page.evaluate(async (b64) => {
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const file = new File([bytes], 'photo.tif', { type: 'image/tiff' })
        const out = { size: file.size, type: file.type }
        try {
          await createImageBitmap(file, { imageOrientation: 'from-image' })
          out.withOptions = 'ok'
        } catch (e) {
          out.withOptions = `${e.name}: ${e.message}`
        }
        try {
          await createImageBitmap(file)
          out.noOptions = 'ok'
        } catch (e) {
          out.noOptions = `${e.name}: ${e.message}`
        }
        return out
      }, tifB64)

      const dt = await page.evaluateHandle((b64) => {
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const store = new DataTransfer()
        store.items.add(new File([bytes], 'photo.tif', { type: 'image/tiff' }))
        return store
      }, tifB64)
      await page.dispatchEvent('.app-shell', 'drop', { dataTransfer: dt })
      const toastText = await waitToast(page, 20000)
      const thumbs = await page.locator('.thumb').count()

      record({
        id: 'B',
        path: 'desktop drag/drop import of .tif (createImageBitmap)',
        probe: 'DataTransfer drop of photo.tif on .app-shell (App.tsx onDrop -> addFiles -> photoFromFile)',
        expected: 'fails',
        observed:
          `thumbs after drop = ${thumbs}; toast = ${JSON.stringify(toastText)}; ` +
          `createImageBitmap(from-image) = ${probe.withOptions}; createImageBitmap() = ${probe.noOptions}`,
        status: probe.withOptions !== 'ok' && probe.noOptions !== 'ok' ? 'FAIL (as expected)' : 'UNEXPECTED PASS',
        evidence: { probe, toastText, thumbs, thumbsBeforeDrop: afterReload }
      })
    }

    /* ---------------------------------------------------------------- A --- */
    let preview = null
    {
      await patchOpenDialog(app, TIF_DIR)
      await page.locator('.import-secondary button', { hasText: '从文件夹导入' }).click()
      await page.waitForSelector('.thumb', { timeout: 60000 })
      await restoreOpenDialog(app)
      const toastText = await waitToast(page, 8000)
      const thumbImg = await page.evaluate(() => {
        const img = document.querySelector('.thumb img')
        return img ? { naturalWidth: img.naturalWidth, naturalHeight: img.naturalHeight } : null
      })
      // The very IPC the folder path calls for each scanned file (store.addPaths ->
      // previewImage -> main/index.ts image:preview -> sharp).
      preview = await page.evaluate(async (p) => {
        const r = await window.framemark.previewImage(p)
        return { name: r.name, width: r.width, height: r.height, previewWidth: r.previewWidth, previewHeight: r.previewHeight, bytes: r.bytes }
      }, TIF)
      const scan = await page.evaluate((f) => window.framemark.listImages(f), TIF_DIR)
      record({
        id: 'A',
        path: 'desktop folder import of .tif (sharp)',
        probe: '从文件夹导入 (dialog stubbed to _research/a2/import/tiff) -> listImages -> previewImage',
        expected: 'works',
        observed:
          `thumbs=${await page.locator('.thumb').count()}; toast=${JSON.stringify(toastText)}; ` +
          `previewImage dims ${preview.width}x${preview.height} (preview ${preview.previewWidth}x${preview.previewHeight}); ` +
          `thumb img natural ${thumbImg ? `${thumbImg.naturalWidth}x${thumbImg.naturalHeight}` : 'n/a'}; scan files=${JSON.stringify(scan.files)}`,
        status: preview.width === 1000 && preview.height === 700 ? 'PASS' : 'UNEXPECTED',
        evidence: {
          preview: { width: preview.width, height: preview.height, previewWidth: preview.previewWidth, previewHeight: preview.previewHeight, bytes: preview.bytes },
          thumbImg,
          toastText,
          scan
        }
      })
    }

    /* ---------------------------------------------------------------- C --- */
    {
      const before = fs.readdirSync(OUT_DIR)
      // Reproduce loadFullBitmap()'s exact failing call on the exact bytes the
      // app reads for a path-sourced photo: readImage -> Blob -> createImageBitmap.
      const loadFullBitmapProbe = await page.evaluate(async (p) => {
        const r = await window.framemark.readImage(p)
        const bytes = new Uint8Array(r.data)
        try {
          await createImageBitmap(new Blob([bytes]), { imageOrientation: 'from-image' })
          return { bytes: bytes.length, result: 'ok' }
        } catch (e) {
          return { bytes: bytes.length, result: `${e.name}: ${e.message}` }
        }
      }, TIF)
      await page.locator('.topbar-actions .btn-primary').click()
      await page.waitForSelector('.export-dialog', { timeout: 10000 })
      await chooseFormat(page, 'JPG')
      const dirState = await setOutDir(app, page, OUT_DIR)
      await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
      await waitCardDone(page)
      const card = await cardText(page)
      const toastText = await toasts(page)
      const after = fs.readdirSync(OUT_DIR)
      await closeCard(page)
      const newFiles = after.filter((f) => !before.includes(f))
      record({
        id: 'C',
        path: 'desktop export of a TIFF-sourced photo',
        probe: 'folder-imported photo.tif -> 导出当前 -> JPG (outDir set) -> 开始导出',
        expected: 'fails at loadFullBitmap (createImageBitmap on raw TIFF bytes)',
        observed:
          `card "${card.heading} | ${card.body}"; errors=${JSON.stringify(card.errors)}; ` +
          `toast=${JSON.stringify(toastText)}; new files in outDir=${JSON.stringify(newFiles)} (dir ${dirState}); ` +
          `readImage+createImageBitmap probe=${JSON.stringify(loadFullBitmapProbe)}`,
        status: card.errors.length > 0 && newFiles.length === 0 ? 'FAIL (as expected)' : 'UNEXPECTED PASS',
        evidence: { card, toastText, newFiles, loadFullBitmapProbe, sourcePreview: preview && { width: preview.width, height: preview.height } }
      })
    }

    const payload = {
      generatedAt: new Date().toISOString(),
      environment: versions,
      fixtures: {
        tiff: { path: TIF, bytes: fs.statSync(TIF).size, probe: await sharp(TIF).metadata().then((m) => ({ format: m.format, width: m.width, height: m.height, compression: m.compression })) },
        jpeg: { path: JPG, bytes: fs.statSync(JPG).size }
      },
      rows: results
    }
    fs.writeFileSync(path.join(A2, 'capability-raw.json'), JSON.stringify(payload, null, 2))
    console.log('\nwrote', path.join(A2, 'capability-raw.json'))
  } finally {
    await app.close().catch(() => undefined)
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
