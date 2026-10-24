/**
 * FrameMark personal-workflow E2E (W2a).
 *
 * Proves the real editing path end to end:
 *   import 2 EXIF photos -> pick 经典双行 -> edit an advanced param -> add + enable a
 *   transparent watermark -> save a preset -> reload -> re-import -> apply preset ->
 *   configure filename tokens -> batch export JPG/PNG/WebP/TIFF -> inspect files ->
 *   cancel a batch -> preset JSON export/import -> zero pageerror.
 *
 * Run with: npm run test:workflow   (build first: npm run build)
 * Exits non-zero on any failed assertion.
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { _electron as electron } from 'playwright-core'

const require = createRequire(import.meta.url)
const sharp = require('sharp')
const exifr = require('exifr')

const root = path.resolve(import.meta.dirname, '..')
const workspace = path.resolve(root, '..')
const runDir = path.join(workspace, '_research', 'e2e-workflow')
const outDir = path.join(runDir, 'out')
const altDir = path.join(runDir, 'alt-output')
const artDir = path.join(runDir, 'artifacts')

const fixtureA = path.join(workspace, '_research', 'test-assets', 'ratio-study', '4x3.jpg')
const fixtureB = path.join(workspace, '_research', 'test-assets', 'contax-t2.jpg')
const wmPng = path.join(workspace, '_research', 'test-assets', 'wm-white.png')
const wmPngFallback = path.join(workspace, '水印_白色.png')
const bigA = path.join(workspace, '_research', 'test-assets', 'p1181817.jpg')
const bigB = path.join(workspace, '_research', 'test-assets', 'p1170883.jpg')

const OUT_EXT = { jpg: '.jpg', png: '.png', webp: '.webp', tiff: '.tiff' }

let passed = 0
let failed = 0
let appRef = null
const failures = []
const defects = []
const notes = []

function check(name, ok, detail = '') {
  if (ok) {
    passed++
    console.log(`PASS  ${name}`)
  } else {
    failed++
    failures.push({ name, detail })
    console.log(`FAIL  ${name}${detail ? ` :: ${detail}` : ''}`)
  }
  return ok
}

async function cleanOut() {
  fs.mkdirSync(outDir, { recursive: true })
  for (let attempt = 0; attempt < 30; attempt++) {
    const entries = fs.readdirSync(outDir)
    if (!entries.length) return
    for (const f of entries) {
      try {
        fs.rmSync(path.join(outDir, f), { recursive: true, force: true })
      } catch {
        // Windows can hold the freshly-written WebP/TIFF briefly (AV / libvips);
        // retry below instead of failing the run.
      }
    }
    await new Promise((r) => setTimeout(r, attempt < 5 ? 120 : 250))
  }
  const left = fs.readdirSync(outDir)
  if (left.length) throw new Error(`output dir is still locked, leftovers: ${left.join(', ')}`)
}

function listFiles(dir) {
  return fs
    .readdirSync(dir)
    .filter((f) => {
      try {
        return fs.statSync(path.join(dir, f)).isFile()
      } catch {
        return false
      }
    })
    .sort()
}

/** Return per-component sampling factors of the first SOF segment; 1x1 means 4:4:4. */
function jpegSampling(buf) {
  if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) return null
  let i = 2
  while (i < buf.length - 1) {
    if (buf[i] !== 0xff) {
      i++
      continue
    }
    const marker = buf[i + 1]
    if (marker === 0xff) {
      i++
      continue
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      i += 2
      continue
    }
    if (marker === 0xd9) break
    if (i + 4 > buf.length) break
    const len = buf.readUInt16BE(i + 2)
    const isSof =
      (marker >= 0xc0 && marker <= 0xc3) ||
      (marker >= 0xc5 && marker <= 0xc7) ||
      (marker >= 0xc9 && marker <= 0xcb) ||
      (marker >= 0xcd && marker <= 0xcf)
    if (isSof && i + 10 <= buf.length) {
      const n = buf[i + 9]
      const comps = []
      for (let k = 0; k < n; k++) {
        const off = i + 10 + k * 3
        if (off + 2 >= buf.length) break
        comps.push({ id: buf[off], h: buf[off + 1] >> 4, v: buf[off + 1] & 15, tq: buf[off + 2] })
      }
      return { marker: `0x${marker.toString(16)}`, comps }
    }
    i += 2 + len
  }
  return null
}

function samplingLabel(sampling) {
  return sampling?.comps?.map((c) => `${c.h}x${c.v}`).join('/') ?? 'unknown'
}

async function waitExportDone(page, timeout = 300000) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(
    () => {
      const h = document.querySelector('.export-card h3')
      return Boolean(h) && !h.textContent?.includes('正在导出')
    },
    null,
    { timeout }
  )
}

async function closeExportOverlay(page) {
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  else {
    const x = page.locator('.export-card button[title="关闭"]').first()
    if (await x.count()) await x.click()
  }
  await page.waitForSelector('.export-card', { state: 'detached', timeout: 15000 }).catch(() => undefined)
}

async function chooseFormat(page, label) {
  const section = page.locator('.panel-section', { hasText: '导出格式' })
  await section.getByRole('button', { name: label, exact: true }).click()
  await page.waitForFunction(
    (l) => {
      const secs = [...document.querySelectorAll('.panel-section')]
      const sec = secs.find((s) => s.textContent?.includes('导出格式'))
      const active = sec?.querySelector('.segmented button.active')
      return active?.textContent?.trim() === l
    },
    label,
    { timeout: 10000 }
  )
}

async function patchOpenDialog(app, dir) {
  await app.evaluate(async ({ dialog }, target) => {
    if (!globalThis.__fmOrigShowOpenDialog) globalThis.__fmOrigShowOpenDialog = dialog.showOpenDialog
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [target] })
  }, dir)
}

async function restoreOpenDialog(app) {
  await app.evaluate(async ({ dialog }) => {
    if (globalThis.__fmOrigShowOpenDialog) {
      dialog.showOpenDialog = globalThis.__fmOrigShowOpenDialog
      delete globalThis.__fmOrigShowOpenDialog
    }
  })
}

async function idbSummary(page) {
  return page.evaluate(async () => {
    const empty = { count: 0, presets: [] }
    let db
    try {
      db = await new Promise((resolve, reject) => {
        const req = indexedDB.open('framemark', 1)
        req.onupgradeneeded = () => {
          const d = req.result
          if (!d.objectStoreNames.contains('presets')) d.createObjectStore('presets', { keyPath: 'id' })
        }
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => reject(req.error)
      })
    } catch {
      return empty
    }
    let rows = []
    try {
      rows = await new Promise((resolve, reject) => {
        const tx = db.transaction('presets', 'readonly')
        const req = tx.objectStore('presets').getAll()
        req.onsuccess = () => resolve(req.result ?? [])
        req.onerror = () => reject(req.error)
      })
    } catch {
      rows = []
    }
    db.close()
    return {
      count: rows.length,
      presets: rows.map((p) => ({
        id: p.id,
        name: p.name,
        templateId: p.design?.templateId,
        variantId: p.design?.variantId,
        outputDir: p.design?.export?.outputDir,
        wmEnabled: p.design?.watermark?.enabled === true,
        wmTarget: p.design?.watermark?.target,
        wmAsset: p.design?.watermark?.asset
          ? {
              name: p.design.watermark.asset.name,
              width: p.design.watermark.asset.width,
              height: p.design.watermark.asset.height,
              prefix: String(p.design.watermark.asset.dataUrl ?? '').slice(0, 22)
            }
          : null,
        footerTextScale: p.design?.params?.footerTextScale
      }))
    }
  })
}

async function runBatch(page, { label, ext, expectedNames, snapshot, onFiles }) {
  await cleanOut()
  await chooseFormat(page, label)
  await page.locator('.export-actions button', { hasText: '导出全部' }).click()
  await waitExportDone(page)
  const cardText = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  check(`${label}: overlay reports 导出完成`, cardText.includes('导出完成'), cardText)
  check(`${label}: overlay reports 成功 2 张`, cardText.includes('成功 2 张'), cardText)
  check(`${label}: no .export-errors`, (await page.locator('.export-errors').count()) === 0, cardText)
  const files = listFiles(outDir)
  check(`${label}: exactly 2 files`, files.length === 2, files.join(', '))
  check(
    `${label}: extensions ${ext}`,
    files.length === 2 && files.every((f) => f.toLowerCase().endsWith(ext)),
    files.join(', ')
  )
  check(
    `${label}: expected derived names`,
    expectedNames.every((n) => files.includes(n)),
    `expected ${expectedNames.join(' | ')} got ${files.join(' | ')}`
  )
  if (snapshot) {
    const snapDir = path.join(artDir, `exports-${label.toLowerCase()}`)
    fs.rmSync(snapDir, { recursive: true, force: true })
    fs.mkdirSync(snapDir, { recursive: true })
    for (const f of files) fs.copyFileSync(path.join(outDir, f), path.join(snapDir, f))
  }
  if (onFiles) await onFiles(files, cardText)
  await closeExportOverlay(page)
  return files
}

async function main() {
  for (const [p, what] of [
    [fixtureA, 'fixture A'],
    [fixtureB, 'fixture B'],
    [bigA, 'cancel fixture A'],
    [bigB, 'cancel fixture B']
  ]) {
    if (!fs.existsSync(p)) throw new Error(`${what} missing: ${p}`)
  }
  let wmFile = wmPng
  if (!fs.existsSync(wmFile)) {
    wmFile = wmPngFallback
    notes.push(`wm-white.png missing; used ${wmFile}`)
  }
  notes.push('contax-t2.jpg found and used as the second EXIF fixture (no substitution).')

  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(outDir, { recursive: true })
  fs.mkdirSync(altDir, { recursive: true })
  fs.mkdirSync(artDir, { recursive: true })

  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.'],
    cwd: root,
    timeout: 60000
  })
  appRef = app
  const page = await app.firstWindow()
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e?.message ?? e)))
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text())
  })

  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
  check('app shell ready', true)

  // ---------------------------------------------------------------------------
  // 2. Reset storage, seed output dir, reload
  // ---------------------------------------------------------------------------
  console.log('\n--- reset storage ---')
  let idb = null
  for (let attempt = 0; attempt < 3; attempt++) {
    const cleared = await page.evaluate(() => {
      localStorage.clear()
      return localStorage.getItem('framemark.lastDesign.v1') === null
    })
    await page.evaluate(
      () =>
        new Promise((resolve) => {
          let settled = false
          const finish = () => {
            if (!settled) {
              settled = true
              resolve(true)
            }
          }
          const req = indexedDB.deleteDatabase('framemark')
          req.onsuccess = finish
          req.onerror = finish
          req.onblocked = finish
          setTimeout(finish, 1500)
        })
    )
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.waitForTimeout(400)
    idb = await idbSummary(page)
    if (attempt === 0) check('localStorage cleared', cleared)
    if (idb.count === 0) break
  }
  check('IndexedDB presets reset to 0', idb?.count === 0, `count=${idb?.count}`)

  const seed = { templateId: 'brandFooter', params: {}, export: { outputDir: outDir } }
  await page.evaluate((v) => localStorage.setItem('framemark.lastDesign.v1', v), JSON.stringify(seed))
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
  await page.waitForTimeout(300)
  check('seeded lastDesign present', (await page.evaluate(() => localStorage.getItem('framemark.lastDesign.v1'))) !== null)

  // Seeded output dir is visible in the export tab (no native dialog needed).
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  const seededDir = (await page.locator('.output-dir code').textContent())?.trim()
  check('seed outputDir applied', seededDir === outDir, `ui=${seededDir}`)
  await page.locator('.inspector-tabs button', { hasText: '模板' }).click()

  // ---------------------------------------------------------------------------
  // 3. Import two photos with distinct EXIF
  // ---------------------------------------------------------------------------
  console.log('\n--- import photos ---')
  await page.setInputFiles('#framemark-file-input', [fixtureA, fixtureB])
  await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 2, null, { timeout: 180000 })
  const thumbTitles = await page.locator('.thumb-main').evaluateAll((els) => els.map((e) => e.getAttribute('title')))
  check('two thumbnails imported', (await page.locator('.thumb').count()) === 2, thumbTitles.join(', '))
  check('thumb names match fixtures', thumbTitles.includes('4x3.jpg') && thumbTitles.includes('contax-t2.jpg'), thumbTitles.join(', '))

  // ---------------------------------------------------------------------------
  // 4. Pick 经典双行 via template search
  // ---------------------------------------------------------------------------
  console.log('\n--- select template ---')
  const search = page.locator('input[aria-label="搜索模板"]')
  await search.fill('经典双行')
  await page.waitForTimeout(350)
  const card = page.locator('.tpl-card', { hasText: '经典双行' }).first()
  check('search finds exactly one 经典双行 card', (await page.locator('.tpl-card', { hasText: '经典双行' }).count()) === 1)
  await card.scrollIntoViewIfNeeded()
  await card.click()
  await page.waitForTimeout(600)
  const tplTitle = (await page.locator('.current-template h3').innerText()).trim()
  check('current template title is 经典双行', tplTitle === '经典双行', tplTitle)
  check('active card is 经典双行', (await page.locator('.tpl-card.active').innerText()).includes('经典双行'))

  // ---------------------------------------------------------------------------
  // 5. Edit an advanced numeric param (footerTextScale)
  // ---------------------------------------------------------------------------
  console.log('\n--- advanced param ---')
  const advSection = page.locator('.panel-section', { hasText: '全部参数' })
  check('全部参数 section exists', (await advSection.count()) === 1)
  await advSection.locator('button', { hasText: '展开' }).click()
  await page.waitForTimeout(250)
  const scaleInput = page.locator('label[title="footerTextScale"] input')
  check('footerTextScale exposed', (await scaleInput.count()) === 1)
  const scaleBefore = await scaleInput.inputValue()
  await scaleInput.fill('1.4')
  await page.waitForTimeout(300)
  const scaleAfter = await scaleInput.inputValue()
  check('footerTextScale edited in UI', scaleAfter === '1.4' && scaleAfter !== scaleBefore, `${scaleBefore} -> ${scaleAfter}`)
  await page.waitForTimeout(700)
  const persistedScale = await page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('framemark.lastDesign.v1') || '{}')?.params?.footerTextScale
    } catch {
      return undefined
    }
  })
  check('footerTextScale persisted in lastDesign', Number(persistedScale) === 1.4, String(persistedScale))

  // ---------------------------------------------------------------------------
  // 6. Watermark: enable switch, upload transparent PNG, verify preview
  // ---------------------------------------------------------------------------
  console.log('\n--- watermark ---')
  await page.locator('.inspector-tabs button', { hasText: '水印' }).click()
  const wmSwitch = page.locator('.switch-row', { hasText: '启用水印' })
  const wmSwitchState = wmSwitch.locator('.switch')
  check('watermark starts disabled', (await wmSwitchState.getAttribute('aria-checked')) === 'false')
  await wmSwitch.click()
  await page.waitForTimeout(200)
  check('watermark switch enabled by click', (await wmSwitchState.getAttribute('aria-checked')) === 'true')
  const wmInput = page.locator('.inspector-pane input[type="file"]')
  check('watermark file input present', (await wmInput.count()) === 1, `count=${await wmInput.count()}`)
  await wmInput.setInputFiles(wmFile)
  await page.waitForSelector('.wm-asset .wm-preview img', { timeout: 30000 })
  const wmSrc = (await page.locator('.wm-asset .wm-preview img').getAttribute('src')) ?? ''
  const wmInfo = (await page.locator('.wm-asset .wm-info').innerText()).replace(/\n+/g, ' | ')
  check('watermark preview image is a PNG data URL', wmSrc.startsWith('data:image/png'), wmSrc.slice(0, 30))
  check('watermark asset is wm-white.png', wmInfo.includes('wm-white.png'), wmInfo)
  check('watermark dimensions shown 1736x561', wmInfo.includes('1736') && wmInfo.includes('561'), wmInfo)
  check('watermark still enabled after upload', (await wmSwitchState.getAttribute('aria-checked')) === 'true')
  check('watermark tab dot visible', (await page.locator('.inspector-tabs button', { hasText: '水印' }).locator('.tab-dot').count()) === 1)
  await page.screenshot({ path: path.join(artDir, '01-watermark-enabled.png') })

  // ---------------------------------------------------------------------------
  // 7a. Save a preset
  // ---------------------------------------------------------------------------
  console.log('\n--- save preset ---')
  const presetName = `E2E工作流-${Date.now()}`
  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.modal', { timeout: 10000 })
  await page.locator('.preset-save-box input').fill(presetName)
  await page
    .locator('.preset-save-box button', { hasText: '保存当前设计' })
    .click()
  await page.waitForSelector('.modal', { state: 'detached', timeout: 15000 })
  await page.waitForFunction(
    () => document.querySelector('.toast')?.textContent?.includes('已保存') === true,
    null,
    { timeout: 15000 }
  )
  check('preset save toast shown', true)
  idb = await idbSummary(page)
  const savedPreset = idb.presets.find((p) => p.name === presetName)
  check('preset persisted in IndexedDB', Boolean(savedPreset), JSON.stringify(idb.presets.map((p) => p.name)))
  check('preset stores wm-classic variant', savedPreset?.variantId === 'wm-classic', String(savedPreset?.variantId))
  check('preset stores watermark enabled', savedPreset?.wmEnabled === true)
  check('preset stores watermark PNG asset', String(savedPreset?.wmAsset?.prefix ?? '').startsWith('data:image/png;base64'), String(savedPreset?.wmAsset?.prefix))
  check('preset stores edited footerTextScale', Number(savedPreset?.footerTextScale) === 1.4, String(savedPreset?.footerTextScale))
  check(
    'preset does not store machine-specific outputDir',
    !savedPreset?.outputDir,
    String(savedPreset?.outputDir)
  )

  // ---------------------------------------------------------------------------
  // 7b. Reload, re-import photos, apply preset, verify visible restoration
  // ---------------------------------------------------------------------------
  console.log('\n--- reload + apply preset ---')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
  await page.waitForTimeout(500)
  check('photos are not persisted across reload', (await page.locator('.thumb').count()) === 0)
  idb = await idbSummary(page)
  check('preset survives reload in IndexedDB', idb.presets.some((p) => p.name === presetName), JSON.stringify(idb.presets.map((p) => p.name)))

  await page.setInputFiles('#framemark-file-input', [fixtureA, fixtureB])
  await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 2, null, { timeout: 180000 })
  check('photos re-imported after reload', (await page.locator('.thumb').count()) === 2)

  // Move the output dir away from the saved value so we can see whether applying a
  // preset overwrites it (presets embed the whole export block today).
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await patchOpenDialog(app, altDir)
  await page.locator('.output-dir button', { hasText: '更改' }).click()
  await page.waitForFunction((dir) => document.querySelector('.output-dir code')?.textContent?.trim() === dir, altDir, {
    timeout: 15000
  })
  await restoreOpenDialog(app)
  const altDirUi = (await page.locator('.output-dir code').textContent())?.trim()
  check('output dir can be changed before preset apply', altDirUi === altDir, String(altDirUi))

  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.modal', { timeout: 10000 })
  const presetRow = page.locator('.preset-row', { hasText: presetName })
  check('saved preset listed after reload', (await presetRow.count()) === 1, `rows=${await page.locator('.preset-row').count()}`)
  await presetRow.locator('button', { hasText: '应用' }).click()
  await page.waitForSelector('.modal', { state: 'detached', timeout: 15000 })
  await page.waitForFunction(
    (n) => document.querySelector('.toast')?.textContent?.includes(`已应用「${n}」`) === true,
    presetName,
    { timeout: 15000 }
  )
  check('preset apply toast shown', true)

  await page.locator('.inspector-tabs button', { hasText: '水印' }).click()
  await page.waitForSelector('.wm-preview img', { timeout: 15000 })
  const wmSrcAfter = (await page.locator('.wm-preview img').getAttribute('src')) ?? ''
  const wmSwitchAfter = page.locator('.switch-row', { hasText: '启用水印' }).locator('.switch')
  check('preset restores watermark preview', wmSrcAfter.startsWith('data:image/png'), wmSrcAfter.slice(0, 30))
  check('preset restores watermark enabled', (await wmSwitchAfter.getAttribute('aria-checked')) === 'true')
  check(
    'preset restores watermark tab dot',
    (await page.locator('.inspector-tabs button', { hasText: '水印' }).locator('.tab-dot').count()) === 1
  )
  await page.screenshot({ path: path.join(artDir, '02-preset-restored.png') })

  await page.locator('.inspector-tabs button', { hasText: '模板' }).click()
  await page.waitForTimeout(300)
  const tplAfter = (await page.locator('.current-template h3').innerText()).trim()
  check('preset restores 经典双行 template', tplAfter === '经典双行', tplAfter)
  await page.locator('.panel-section', { hasText: '全部参数' }).locator('button', { hasText: '展开' }).click()
  await page.waitForTimeout(250)
  const scaleRestored = await page.locator('label[title="footerTextScale"] input').inputValue()
  check('preset restores edited footerTextScale', scaleRestored === '1.4', scaleRestored)

  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  const dirAfterPreset = (await page.locator('.output-dir code').textContent())?.trim()
  check('preset apply preserves current output dir', dirAfterPreset === altDir, `ui=${dirAfterPreset}`)
  // Put the export dir back on the seeded path for the batches below.
  await patchOpenDialog(app, outDir)
  await page.locator('.output-dir button', { hasText: '更改' }).click()
  await page.waitForFunction((dir) => document.querySelector('.output-dir code')?.textContent?.trim() === dir, outDir, {
    timeout: 15000
  })
  await restoreOpenDialog(app)

  // ---------------------------------------------------------------------------
  // 8. Filename pattern + JPG batch (2 photos)
  // ---------------------------------------------------------------------------
  console.log('\n--- filename pattern ---')
  const pattern = '{index}-{name}-{template}-{make}'
  const patternInput = page.locator('label.field', { hasText: '文件名模板' }).locator('input')
  await patternInput.fill(pattern)
  await page.waitForTimeout(300)
  check('filename pattern applied', (await patternInput.inputValue()) === pattern, await patternInput.inputValue())

  const baseNames = ['001-4x3-经典双行-Panasonic', '002-contax-t2-经典双行-Contax']
  const sources = [
    { name: '4x3.jpg', make: 'Panasonic', model: 'DC-S1', width: 2667, height: 2000 },
    { name: 'contax-t2.jpg', make: 'Contax', model: 'CONTAX T2', width: 3000, height: 2000 }
  ]

  console.log('\n--- batch JPG ---')
  await runBatch(page, {
    label: 'JPG',
    ext: OUT_EXT.jpg,
    expectedNames: baseNames.map((b) => `${b}.jpg`),
    snapshot: true,
    onFiles: async (files) => {
      for (let i = 0; i < sources.length; i++) {
        const src = sources[i]
        const expected = `${baseNames[i]}.jpg`
        const full = path.join(outDir, expected)
        check(`JPG file exists ${expected}`, fs.existsSync(full), files.join(', '))
        if (!fs.existsSync(full)) continue
        const buf = fs.readFileSync(full)
        const meta = await sharp(buf).metadata()
        const exif = await exifr.parse(buf, {
          tiff: true,
          ifd0: true,
          exif: true,
          gps: true,
          interop: true,
          translateValues: true,
          reviveValues: true,
          mergeOutput: true
        })
        check(`JPG ${expected} Make=${src.make}`, exif?.Make === src.make, String(exif?.Make))
        check(`JPG ${expected} Model=${src.model}`, exif?.Model === src.model, String(exif?.Model))
        const orient = exif?.Orientation
        const orientOk = Number(orient) === 1 || /^Horizontal/i.test(String(orient ?? ''))
        check(`JPG ${expected} Orientation=1`, orientOk, String(orient))
        check(
          `JPG ${expected} no GPS`,
          exif?.latitude == null && exif?.longitude == null && exif?.GPSLatitude == null,
          JSON.stringify({ lat: exif?.latitude, lon: exif?.longitude, raw: exif?.GPSLatitude })
        )
        check(`JPG ${expected} width=${src.width}`, meta.width === src.width, `${meta.width}x${meta.height}`)
        check(`JPG ${expected} height grows with footer`, (meta.height ?? 0) > src.height, `${meta.width}x${meta.height}`)
        const sampling = jpegSampling(buf)
        check(
          `JPG ${expected} chroma 4:4:4`,
          Boolean(sampling?.comps?.length) && sampling.comps.every((c) => c.h === 1 && c.v === 1),
          samplingLabel(sampling)
        )
        check(`JPG ${expected} size > 100KB`, fs.statSync(full).size > 100 * 1024, `${fs.statSync(full).size} bytes`)
        if (i === 0) {
          // The white watermark is composited at 90% opacity over dark leaves in
          // the bottom-right of the photo; verify it actually reached the pixels.
          const region = {
            left: Math.floor(src.width * 0.7),
            top: Math.floor(src.height * 0.7),
            width: src.width - Math.floor(src.width * 0.7),
            height: src.height - Math.floor(src.height * 0.7)
          }
          const raw = await sharp(buf).extract(region).raw().toBuffer({ resolveWithObject: true })
          let bright = 0
          for (let p = 0; p < raw.data.length; p += raw.info.channels) {
            const min = Math.min(raw.data[p], raw.data[p + 1], raw.data[p + 2])
            if (min >= 220) bright++
          }
          check(
            `JPG ${expected} watermark rendered in photo`,
            bright >= 500,
            `${bright} bright px in ${raw.info.width}x${raw.info.height} bottom-right region`
          )
        }
      }
      await page.screenshot({ path: path.join(artDir, '03-jpg-export-done.png') })
    }
  })

  // ---------------------------------------------------------------------------
  // 10. PNG / WebP / TIFF batches (desktop)
  // ---------------------------------------------------------------------------
  console.log('\n--- batch PNG ---')
  await runBatch(page, {
    label: 'PNG',
    ext: OUT_EXT.png,
    expectedNames: baseNames.map((b) => `${b}.png`),
    onFiles: async (files) => {
      for (const f of files) {
        const meta = await sharp(fs.readFileSync(path.join(outDir, f))).metadata()
        check(`PNG ${f} decodes as png`, meta.format === 'png', `${meta.format} ${meta.width}x${meta.height}`)
      }
    }
  })

  console.log('\n--- batch WebP ---')
  await runBatch(page, {
    label: 'WebP',
    ext: OUT_EXT.webp,
    expectedNames: baseNames.map((b) => `${b}.webp`),
    onFiles: async (files) => {
      for (const f of files) {
        const meta = await sharp(fs.readFileSync(path.join(outDir, f))).metadata()
        check(`WebP ${f} decodes as webp`, meta.format === 'webp', `${meta.format} ${meta.width}x${meta.height}`)
      }
    }
  })

  console.log('\n--- batch TIFF ---')
  await runBatch(page, {
    label: 'TIFF',
    ext: OUT_EXT.tiff,
    expectedNames: baseNames.map((b) => `${b}.tiff`),
    onFiles: async (files) => {
      for (const f of files) {
        const meta = await sharp(fs.readFileSync(path.join(outDir, f))).metadata()
        check(`TIFF ${f} decodes as tiff`, meta.format === 'tiff', `${meta.format} ${meta.width}x${meta.height}`)
      }
    }
  })

  // ---------------------------------------------------------------------------
  // 11. Cancel one batch (add two large photos so cancellation is deterministic)
  // ---------------------------------------------------------------------------
  console.log('\n--- cancel batch ---')
  await page.setInputFiles('#framemark-file-input', [bigA, bigB])
  await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 4, null, { timeout: 240000 })
  check('two large photos added for cancel test', (await page.locator('.thumb').count()) === 4)
  await chooseFormat(page, 'JPG')
  await cleanOut()
  await page.locator('.export-actions button', { hasText: '导出全部' }).click()
  await page.locator('.export-card button', { hasText: '取消导出' }).click({ timeout: 30000 })
  await waitExportDone(page)
  const cancelText = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  check('cancel overlay reports 已取消', cancelText.includes('已取消'), cancelText)
  check('cancel produces no export errors', (await page.locator('.export-errors').count()) === 0, cancelText)
  check('app shell still alive after cancel', await page.locator('.app-shell').isVisible())
  await page.screenshot({ path: path.join(artDir, '04-cancelled.png') })
  await closeExportOverlay(page)

  // ---------------------------------------------------------------------------
  // 12. Preset JSON export / delete / import roundtrip (no native dialogs)
  // ---------------------------------------------------------------------------
  console.log('\n--- preset JSON roundtrip ---')
  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.modal', { timeout: 10000 })
  await page.locator('.modal-foot button', { hasText: '导出预设' }).click()
  await page.waitForFunction(
    () => document.querySelector('.toast')?.textContent?.includes('预设已保存到') === true,
    null,
    { timeout: 30000 }
  )
  const toastText = (await page.locator('.toast').evaluate((el) => el.textContent ?? '')).trim()
  const exportedPath = toastText.split('预设已保存到').pop()?.trim() ?? ''
  check('preset JSON export reported a path', Boolean(exportedPath), toastText)
  const exportedExists = exportedPath ? fs.existsSync(exportedPath) : false
  check('preset JSON file exists on disk', exportedExists, exportedPath)
  let exportedJson = null
  if (exportedExists) {
    try {
      exportedJson = JSON.parse(fs.readFileSync(exportedPath, 'utf8'))
    } catch (e) {
      exportedJson = null
      notes.push(`preset JSON parse failed: ${String(e)}`)
    }
  }
  check(
    'preset JSON contains saved preset name',
    Array.isArray(exportedJson?.presets) && exportedJson.presets.some((p) => p.name === presetName),
    exportedPath
  )
  check(
    'preset JSON embeds watermark asset',
    Array.isArray(exportedJson?.presets) &&
      exportedJson.presets.some((p) => String(p?.design?.watermark?.asset?.dataUrl ?? '').startsWith('data:image/png')),
    exportedPath
  )
  check(
    'preset JSON omits machine-specific outputDir',
    Array.isArray(exportedJson?.presets) && exportedJson.presets.every((p) => !p?.design?.export?.outputDir),
    exportedPath
  )

  const row = page.locator('.preset-row', { hasText: presetName })
  check('preset row present before JSON import test', (await row.count()) === 1)
  await row.locator('.icon-btn.danger').click()
  await page.waitForFunction(
    (n) => ![...document.querySelectorAll('.preset-row strong')].some((e) => e.textContent?.trim() === n),
    presetName,
    { timeout: 15000 }
  )
  check('preset deleted before re-import', (await page.locator('.preset-row', { hasText: presetName }).count()) === 0)
  await page.locator('.modal input[type="file"]').setInputFiles(exportedPath)
  await page.waitForFunction(
    () => document.querySelector('.toast')?.textContent?.includes('已导入 1 个预设') === true,
    null,
    { timeout: 30000 }
  )
  check('preset JSON import toast shown', true)
  check('preset reappears after JSON import', (await page.locator('.preset-row', { hasText: presetName }).count()) === 1)
  idb = await idbSummary(page)
  check('imported preset persisted in IndexedDB', idb.presets.some((p) => p.name === presetName), JSON.stringify(idb.presets.map((p) => p.name)))
  await page.locator('.modal-foot button', { hasText: '完成' }).click()
  await page.waitForSelector('.modal', { state: 'detached', timeout: 10000 })

  // ---------------------------------------------------------------------------
  // 13. Zero pageerror
  // ---------------------------------------------------------------------------
  await page.waitForTimeout(800)
  check('no pageerror across the whole workflow', pageErrors.length === 0, pageErrors.join(' | '))
  if (consoleErrors.length) {
    notes.push(`console.error entries: ${consoleErrors.slice(0, 5).join(' | ')}`)
  }

  await page.screenshot({ path: path.join(artDir, '05-final-app.png') })
  await app.close()

  console.log('\n=== RESULT ===')
  console.log(
    JSON.stringify(
      {
        assertions: passed + failed,
        passed,
        failed,
        failures,
        defects,
        notes,
        pageErrors,
        consoleErrors,
        artifacts: { runDir, outDir, artDir },
        exportedPresetJson: exportedPath || null
      },
      null,
      2
    )
  )
  process.exitCode = failed > 0 ? 1 : 0
}

try {
  await main()
} catch (error) {
  failed++
  failures.push({ name: 'harness', detail: String(error?.stack ?? error) })
  console.error('FAIL  harness ::', error?.stack ?? error)
  console.log('\n=== RESULT ===')
  console.log(JSON.stringify({ assertions: passed + failed, passed, failed, failures, defects, notes }, null, 2))
  process.exitCode = 1
} finally {
  if (appRef) {
    try {
      await appRef.close()
    } catch {
      // already closed
    }
  }
}
