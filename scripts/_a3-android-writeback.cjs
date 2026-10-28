/**
 * A3 gate 3 — unsupported-format write-back on the non-desktop branch.
 *
 * The Android renderer and the plain-web renderer share ONE fact in the
 * renderer: `window.framemark` is absent. This gate therefore loads the SAME
 * built renderer into a second BrowserWindow created WITHOUT the preload bridge
 * (exactly the no-`window.framemark` condition the owner asked for), seeds a
 * preset whose `design.export.format` is `tiff`, applies it, and then opens 导出.
 *
 * Expected AFTER the fix:
 *   - the dialog shows JPG (the unsupported option is not rendered at all);
 *   - `design.export.format` is actually `jpeg` (read from the live store via
 *     __fmHistory.design() and from the persisted last-design);
 *   - one short line says TIFF was switched to JPG;
 *   - 开始导出 finishes successfully and the downloaded file is a real JPEG
 *     named `.jpg` (before the fix: no write-back, a `.tiff` download holding
 *     WebP bytes).
 *
 * Usage: node scripts/_a3-android-writeback.cjs --tag=before|after
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const requireHere = createRequire(__filename)
const sharp = requireHere('sharp')
const lib = require('./_a3-lib.cjs')

const tag = (process.argv.find((a) => a.startsWith('--tag=')) || '--tag=after').split('=')[1]
const runRoot = lib.ensureDir(path.join(lib.a3Dir, `run-writeback-${tag}`))
const downloadDir = lib.cleanDir(path.join(runRoot, 'downloads'))
const profile = path.join(runRoot, 'profile')
const source = path.join(lib.workspace, '_research', 'test-assets', 'ratio-study', '4x3.jpg')
const tiffFixture = path.join(lib.ensureDir(path.join(lib.a3Dir, 'fixtures')), 'a3-filter-probe.tif')
const PRESET_ID = 'a3-preset-tiff'

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok: Boolean(ok), detail: String(detail) })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  return ok
}

async function photoFingerprint(file) {
  const meta = await sharp(file).metadata()
  let w = meta.width ?? 0
  let h = meta.height ?? 0
  const o = meta.orientation ?? 1
  if (o >= 5 && o <= 8) [w, h] = [h, w]
  const name = path.basename(file)
  const bytes = fs.statSync(file).size
  return { name, bytes, width: w, height: h, fingerprint: `${name}|${bytes}|${w}x${h}` }
}

async function seedDesignAndPreset(page, design) {  return page.evaluate(
    async ({ design: d, presetId }) => {
      const preset = {
        id: presetId,
        name: 'A3 TIFF 预设',
        design: d,
        createdAt: Date.now(),
        updatedAt: Date.now()
      }
      await new Promise((resolve, reject) => {
        const req = indexedDB.open('framemark', 2)
        req.onupgradeneeded = () => {
          const db = req.result
          if (!db.objectStoreNames.contains('presets')) db.createObjectStore('presets', { keyPath: 'id' })
          if (!db.objectStoreNames.contains('jobs')) {
            const jobs = db.createObjectStore('jobs', { keyPath: 'id' })
            jobs.createIndex('createdAt', 'createdAt')
          }
        }
        req.onsuccess = () => {
          const db = req.result
          const tx = db.transaction('presets', 'readwrite')
          tx.objectStore('presets').put(preset)
          tx.oncomplete = () => {
            db.close()
            resolve()
          }
          tx.onerror = () => reject(tx.error)
        }
        req.onerror = () => reject(req.error)
      })
      localStorage.setItem('framemark.lastDesign.v1', JSON.stringify(d))
      return preset.id
    },
    { design, presetId: PRESET_ID }
  )
}

async function openNoPreloadWindow(app, indexHtml) {
  const windowPromise = app.waitForEvent('window', { timeout: 60000 })
  await app.evaluate(async ({ BrowserWindow }, file) => {
    const w = new BrowserWindow({
      width: 1280,
      height: 900,
      show: true,
      backgroundColor: '#0c0c0d',
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: false }
    })
    await w.loadFile(file)
  }, indexHtml)
  const page = await windowPromise
  page.setDefaultTimeout(90000)
  const pageErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
  return { page, pageErrors }
}

async function main() {
  if (!fs.existsSync(source)) throw new Error(`fixture missing: ${source}`)
  const indexHtml = path.join(lib.root, 'out', 'renderer', 'index.html')
  const { app } = await lib.launch(profile)

  const result = { tag, checks, pageErrors: [], download: null }
  try {
    // A download handler must exist BEFORE the export, otherwise Electron pops a
    // native save dialog and the run hangs.
    await app.evaluate(({ session }, dir) => {
      session.defaultSession.on('will-download', (_e, item) => {
        item.setSavePath(`${dir}/${item.getFilename()}`)
      })
    }, downloadDir)

    const { page, pageErrors } = await openNoPreloadWindow(app, indexHtml)
    result.pageErrors = pageErrors
    console.log('--- no-preload window ready ---')
    check('simulation: window.framemark is absent', await page.evaluate(() => typeof window.framemark === 'undefined'))

    // Import once so a real Design (current schema) can be read from the store.
    await page.setInputFiles('#framemark-file-input', [source])
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 1, null, { timeout: 120000 })
    const base = await page.evaluate(() => window.__fmHistory.design())
    const seeded = JSON.parse(JSON.stringify(base))
    seeded.export = { ...seeded.export, format: 'tiff', outputDir: '' }
    await seedDesignAndPreset(page, seeded)
    await page.reload()
    await page.waitForSelector('.app-shell.ready', { timeout: 60000 })

    // Apply the tiff preset. This is the "preset whose format is tiff" state.
    await page.evaluate((id) => window.__fmHistory.act.applyPreset(id), PRESET_ID)
    await lib.sleep(400)
    const beforeOpen = await page.evaluate(() => window.__fmHistory.design().export.format)
    check('precondition: applied preset really set tiff', beforeOpen === 'tiff', String(beforeOpen))
    result.formatBeforeDialog = beforeOpen

    await page.setInputFiles('#framemark-file-input', [source])
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 1, null, { timeout: 120000 })
    await lib.openExportDialog(page)
    await lib.sleep(300)

    const modalText = (await page.locator('.export-dialog').innerText()).replace(/\n+/g, ' | ')
    const activeLabel = await lib.activeFormatLabel(page)
    const liveFormat = await page.evaluate(() => window.__fmHistory.design().export.format)
    const formatButtons = (
      await page.locator('.export-dialog label.field', { hasText: '格式' }).locator('.segmented button').allInnerTexts()
    ).map((s) => s.trim())
    result.activeFormatLabel = activeLabel
    result.formatAfterDialog = liveFormat
    result.formatButtons = formatButtons
    result.modalText = modalText
    check('dialog shows JPEG', activeLabel === 'JPG', activeLabel)
    check('design format was written back to jpeg', liveFormat === 'jpeg', String(liveFormat))
    check('TIFF is not offered in the format segmented', !formatButtons.includes('TIFF'), formatButtons.join(', '))
    check('one short switch notice is shown', /不支持 TIFF/.test(modalText) && /JPG/.test(modalText), modalText.slice(0, 300))
    await page.screenshot({ path: path.join(runRoot, 'dialog-writeback.png') })

    // Persisted last design must agree, not just the in-memory store.
    await lib.sleep(900)
    const persistedFormat = await page.evaluate(() => {
      try {
        return JSON.parse(localStorage.getItem('framemark.lastDesign.v1')).export.format
      } catch {
        return null
      }
    })
    result.persistedFormat = persistedFormat
    check('persisted design format is jpeg', persistedFormat === 'jpeg', String(persistedFormat))

    // Copy checks that only apply to the non-desktop branch.
    const webpTitle = await page
      .locator('.export-dialog label.field', { hasText: '格式' })
      .locator('button', { hasText: 'WebP' })
      .getAttribute('title')
    result.webpTitle = webpTitle
    check(
      'WebP option is advertised as lossless (VP8L) on this platform',
      /无损（VP8L）/.test(webpTitle || '') && !/高质量（手机）/.test(webpTitle || ''),
      webpTitle || '(no title)'
    )
    const qualityHint = /低于 100 会降低颜色精度（与桌面不同）/
    check('quality hint is hidden at the default 100', !qualityHint.test(await page.locator('.export-dialog').innerText()))
    const range = page.locator('.export-dialog .slider-row input[type="range"]')
    await range.focus()
    for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowLeft')
    await lib.sleep(300)
    result.qualityAfterKeys = await page.evaluate(() => window.__fmHistory.design().export.quality)
    check('quality hint is shown below 100', qualityHint.test(await page.locator('.export-dialog').innerText()), `quality=${result.qualityAfterKeys}`)
    await page.screenshot({ path: path.join(runRoot, 'quality-hint-95.png') })
    for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowRight')
    await lib.sleep(300)
    check('quality hint disappears back at 100', !qualityHint.test(await page.locator('.export-dialog').innerText()))

    // Fix 3: the non-desktop file input / store filter must not offer TIFF.
    const accept = await page.locator('#framemark-file-input').getAttribute('accept')
    result.accept = accept
    check('non-desktop accept attribute does not offer TIFF', !/tiff/i.test(accept || ''), accept || '(none)')
    if (!fs.existsSync(tiffFixture)) {
      await sharp({
        create: { width: 64, height: 48, channels: 3, background: { r: 20, g: 120, b: 200 } }
      })
        .tiff()
        .toFile(tiffFixture)
    }
    await page.setInputFiles('#framemark-file-input', [tiffFixture])
    await lib.sleep(1200)
    const thumbsAfterTiff = await page.locator('.thumb').count()
    check('non-desktop store filter ignores a TIFF picked anyway', thumbsAfterTiff === 1, `thumbs=${thumbsAfterTiff}`)

    await lib.startExport(page).then((run) => {
      result.run = run
      check('export completed without errors', run.errors.length === 0, JSON.stringify(run.errors))
      check('export card reports success', /导出完成/.test(run.title) && !/失败/.test(run.title), `${run.title} :: ${run.body}`)
    })

    // Give the download a moment to reach the handler, then inspect it.
    await lib.sleep(2500)
    const downloaded = lib.listFiles(downloadDir)
    result.download = { dir: downloadDir, files: downloaded }
    check('exactly one download landed', downloaded.length === 1, downloaded.join(', '))
    if (downloaded.length === 1) {
      const file = path.join(downloadDir, downloaded[0])
      const head = fs.readFileSync(file).subarray(0, 12)
      const isJpeg = head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff
      const isRiffWebp = head.subarray(0, 4).toString('ascii') === 'RIFF' && head.subarray(8, 12).toString('ascii') === 'WEBP'
      result.download.file = downloaded[0]
      result.download.magic = [...head].map((b) => b.toString(16).padStart(2, '0')).join(' ')
      result.download.isJpeg = isJpeg
      result.download.isRiffWebp = isRiffWebp
      check('download has a .jpg extension', /\.jpg$/i.test(downloaded[0]), downloaded[0])
      check('download really is JPEG bytes', isJpeg, result.download.magic)
      check('download is not a WebP-in-.tiff', !isRiffWebp && !/\.tiff$/i.test(downloaded[0]), downloaded[0])
    }

    if (tag !== 'before') {
      // Fix 5 probe — the realistic bypass: a task persisted BEFORE the format
      // was written back (e.g. created on desktop with TIFF, or an old record)
      // is resumed on a platform without TIFF. 继续 calls exportBatch with the
      // TASK's design, never through the dialog, so the web branch itself must
      // refuse instead of writing WebP bytes under a .tiff name.
      await lib.closeExportOverlay(page)
      const photoMeta = await photoFingerprint(source)
      const tiffDesign = await page.evaluate(() => {
        const d = window.__fmHistory.design()
        // Keep export.outputDir exactly as the live design has it: init() fills
        // the non-desktop placeholder at boot, and designKey is order/value
        // sensitive, so clearing it here would make the resume lookup miss.
        return JSON.parse(JSON.stringify({ ...d, export: { ...d.export, format: 'tiff' } }))
      })
      const seededTask = await page.evaluate(
        async ({ design, fingerprint, name }) => {
          const foldLong = (value) => {
            if (value.length <= 96) return value
            let h = 0x811c9dc5
            for (let i = 0; i < value.length; i++) {
              h ^= value.charCodeAt(i)
              h = Math.imul(h, 0x01000193)
            }
            return `«${value.length}:${(h >>> 0).toString(36)}»`
          }
          const designKey = (d) => JSON.stringify(d, (_k, v) => (typeof v === 'string' ? foldLong(v) : v))
          const exportDesignKey = (d) => {
            if (!d.brandVersion) return designKey(d)
            const { brandVersion: _derived, ...rest } = d
            return designKey(rest)
          }
          const now = Date.now()
          const task = {
            id: 'a3-guard-task',
            createdAt: now,
            updatedAt: now,
            status: 'paused',
            design,
            designKey: exportDesignKey(design),
            presetName: '',
            outputDir: '',
            photos: [{ key: 'k1', fingerprint, name }],
            order: ['k1'],
            results: {},
            errors: {}
          }
          await new Promise((resolve, reject) => {
            const req = indexedDB.open('framemark', 2)
            req.onupgradeneeded = () => {
              const db = req.result
              if (!db.objectStoreNames.contains('presets')) db.createObjectStore('presets', { keyPath: 'id' })
              if (!db.objectStoreNames.contains('jobs')) {
                const jobs = db.createObjectStore('jobs', { keyPath: 'id' })
                jobs.createIndex('createdAt', 'createdAt')
              }
            }
            req.onsuccess = () => {
              const db = req.result
              const tx = db.transaction('jobs', 'readwrite')
              tx.objectStore('jobs').put(task)
              tx.oncomplete = () => {
                db.close()
                resolve()
              }
              tx.onerror = () => reject(tx.error)
            }
            req.onerror = () => reject(req.error)
          })
          localStorage.setItem('framemark.lastDesign.v1', JSON.stringify(design))
          return { id: task.id, designKey: task.designKey, fingerprint }
        },
        { design: tiffDesign, fingerprint: photoMeta.fingerprint, name: photoMeta.name }
      )
      result.seededTask = seededTask
      await page.reload()
      await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
      await page.setInputFiles('#framemark-file-input', [source])
      await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 1, null, { timeout: 120000 })
      await page.waitForSelector('.resume-notice', { timeout: 30000 })
      const noticeText = (await page.locator('.resume-notice').innerText()).replace(/\n+/g, ' | ')
      result.resumeNotice = noticeText
      check('guard probe: persisted tiff task offers 继续', /继续/.test(noticeText), noticeText)
      const downloadsBefore = lib.listFiles(downloadDir).length
      await page.locator('.resume-notice button', { hasText: '继续' }).click()
      await page.waitForSelector('.export-card', { timeout: 30000 })
      await page.waitForFunction(
        () => {
          const h = document.querySelector('.export-card h3')
          return Boolean(h) && !h.textContent?.includes('正在导出')
        },
        null,
        { timeout: 300000 }
      )
      const guardErrors = (await page.locator('.export-errors > div').allInnerTexts()).map((s) =>
        s.replace(/\n+/g, ' ').trim()
      )
      result.guardErrors = guardErrors
      await lib.sleep(1500)
      const downloadsAfter = lib.listFiles(downloadDir).length
      check(
        'web tiff guard: refused with the explicit policy message',
        /当前平台不支持 TIFF 导出/.test(guardErrors.join(' | ')),
        guardErrors.join(' | ') || '(no error row)'
      )
      check('web tiff guard: no extra download was written', downloadsAfter === downloadsBefore, `${downloadsBefore} -> ${downloadsAfter}`)
    }
    result.pageErrors = pageErrors
    check('no pageerror during the whole flow', pageErrors.length === 0, pageErrors.join(' | '))
  } finally {
    await app.close().catch(() => undefined)
  }

  const failed = checks.filter((c) => !c.ok)
  result.pass = failed.length === 0
  result.failed = failed
  lib.writeJson(path.join(lib.a3Dir, `android-writeback-${tag}.json`), result)
  console.log(`\n=== ${result.pass ? 'PASS' : 'FAIL'} === ${checks.length - failed.length}/${checks.length}`)
  if (failed.length) console.log(failed.map((f) => `- ${f.name}: ${f.detail}`).join('\n'))

  if (tag === 'before') {
    // BEFORE must show the defect: design still tiff, a .tiff download holding WebP.
    const reproduced =
      result.formatAfterDialog === 'tiff' &&
      Boolean(result.download && result.download.files && result.download.files.length === 1 && /\.tiff$/i.test(result.download.files[0])) &&
      Boolean(result.download.isRiffWebp)
    console.log(reproduced ? 'BEFORE defect reproduced as expected' : 'BEFORE defect NOT reproduced')
    process.exit(reproduced ? 0 : 1)
  }
  process.exit(result.pass ? 0 : 1)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
