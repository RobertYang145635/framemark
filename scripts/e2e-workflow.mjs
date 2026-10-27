/**
 * FrameMark personal-workflow E2E (W2a) — migrated to the v4 photo-first UI shell.
 *
 * Proves the real editing path end to end on the NEW shell:
 *   import 2 EXIF photos -> BorderLibrary taxonomy (175 cards / 7 categories / vendor
 *   chips) -> pick 经典双行 -> edit a per-style param (布局 · 文字区高度) and watch the
 *   canvas re-render -> add + enable a transparent watermark (anchor / 宽度 / 不透明度)
 *   -> export PNG through the NEW 导出 dialog and prove the watermark reached the pixels
 *   -> filename tokens -> save a preset -> disturb the design -> reload (same isolated
 *   profile) -> re-import -> apply preset -> export JPG/PNG/WebP/TIFF batches ->
 *   cancel a batch -> preset JSON export/import round-trip -> zero pageerror.
 *
 * Run with: node scripts/e2e-workflow.mjs   (out/ must be a current build)
 * Exits non-zero on any failed assertion. Prints `=== RESULT ===` followed by a
 * single-line JSON {assertions,passed,failed,failures,defects,notes} (scripts/final-check.cjs T7).
 *
 * ISOLATION: the app is launched with a throw-away `--user-data-dir` under the run
 * directory so every run behaves like a first launch and never shares the GPU shader
 * cache with another instance.
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
// #57 PATH-LITERALS: the fallback used to be a hardcoded workspace-root absolute path.
// Resolved lazily in main() so module load never throws.
const { resolveAsset: resolveTestAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')
const wmPngFallbackName = '水印_白色.png'
const bigA = path.join(workspace, '_research', 'test-assets', 'p1181817.jpg')
const bigB = path.join(workspace, '_research', 'test-assets', 'p1170883.jpg')

const OUT_EXT = { jpg: '.jpg', png: '.png', webp: '.webp', tiff: '.tiff' }

/** New taxonomy DOM contract: .tpl-card is ALWAYS 175 nodes; 7 category sections. */
const CATEGORIES = [
  { name: '厂商边框', key: 'vendor', count: 99 },
  { name: '文字边框', key: 'text', count: 21 },
  { name: '印象边框', key: 'impression', count: 13 },
  { name: '白边边框', key: 'white', count: 12 },
  { name: '卡片边框', key: 'card', count: 19 },
  { name: '色彩边框', key: 'color', count: 6 },
  { name: '简约边框', key: 'minimal', count: 5 }
]
const TOTAL_CARDS = CATEGORIES.reduce((n, c) => n + c.count, 0)

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

/** Launch with a throw-away profile so every run is a genuine first start. */
async function launchApp(profile) {
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  return electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })
}

async function importPhotos(page, files, expected, timeout = 240000) {
  await page.setInputFiles('#framemark-file-input', files)
  await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, expected, { timeout })
}

/** Hash the live preview canvas so a re-render can be observed without pixels leaving the page. */
async function canvasHash(page) {
  return page.evaluate(() => {
    const canvas = document.querySelector('.canvas-frame canvas')
    if (!canvas) return null
    const url = canvas.toDataURL('image/png')
    let h = 2166136261
    for (let i = 0; i < url.length; i += 7) {
      h ^= url.charCodeAt(i)
      h = Math.imul(h, 16777619)
    }
    return `${url.length}:${h >>> 0}`
  })
}

function formatField(page) {
  return page.locator('.export-dialog label.field', { hasText: '格式' })
}

async function activeFormatLabel(page) {
  const el = formatField(page).locator('.segmented button.active')
  return (await el.count()) ? ((await el.innerText()) ?? '').trim() : ''
}

/** The export dialog replaced the old 导出 tab: pick the format inside `.export-dialog`. */
async function chooseFormat(page, label) {
  await formatField(page).locator('button', { hasText: label, exact: true }).click()
  await page.waitForFunction(
    (l) => {
      const fields = [...document.querySelectorAll('.export-dialog label.field')]
      const sec = fields.find((f) => f.textContent?.includes('格式'))
      const active = sec?.querySelector('.segmented button.active')
      return active?.textContent?.trim() === l
    },
    label,
    { timeout: 10000 }
  )
}

async function openExportDialog(page, via = 'topbar') {
  if (via === 'filmstrip') {
    await page.locator('.filmstrip-actions button', { hasText: '批量导出' }).click()
  } else {
    await page.locator('.topbar-actions .btn-primary').click()
  }
  await page.waitForSelector('.export-dialog', { timeout: 10000 })
}

async function closeExportDialog(page) {
  await page.locator('.export-dialog-foot button', { hasText: '取消' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 10000 })
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

/** Redirect the export dir exactly like the old script: stub the main dialog, click 选择. */
async function pickExportDir(app, page, dir) {
  await patchOpenDialog(app, dir)
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, dir, {
    timeout: 15000
  })
  await restoreOpenDialog(app)
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
        format: p.design?.export?.format,
        filenamePattern: p.design?.export?.filenamePattern,
        wmEnabled: p.design?.watermark?.enabled === true,
        wmTarget: p.design?.watermark?.target,
        wmAnchor: p.design?.watermark?.anchor,
        wmWidthPct: p.design?.watermark?.widthPct,
        wmOpacity: p.design?.watermark?.opacity,
        wmAsset: p.design?.watermark?.asset
          ? {
              name: p.design.watermark.asset.name,
              width: p.design.watermark.asset.width,
              height: p.design.watermark.asset.height,
              prefix: String(p.design.watermark.asset.dataUrl ?? '').slice(0, 22)
            }
          : null,
        footerPct: p.design?.params?.footerPct
      }))
    }
  })
}

async function lastDesign(page) {
  return page.evaluate(() => {
    try {
      return JSON.parse(localStorage.getItem('framemark.lastDesign.v1') || 'null')
    } catch {
      return null
    }
  })
}

async function runBatch(page, { label, ext, expectedNames, snapshot, onFiles }) {
  await cleanOut()
  await openExportDialog(page, 'filmstrip')
  const scopeActive = (
    (await page.locator('.export-dialog label.field', { hasText: '范围' }).locator('.segmented button.active').innerText()) ?? ''
  ).trim()
  check(`${label}: export dialog scope is 全部 N 张`, scopeActive.startsWith('全部'), scopeActive)
  await chooseFormat(page, label)
  if (label === 'JPG') {
    check(
      `${label}: JPG quality slider present`,
      (await page.locator('.export-dialog .slider-row', { hasText: 'JPG 质量' }).count()) === 1
    )
  }
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 10000 })
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

/** EXIF null check for formats that must not carry it. */
async function parseExifSafe(buf) {
  try {
    return (await exifr.parse(buf, {
      tiff: true,
      ifd0: true,
      exif: true,
      gps: true,
      interop: true,
      translateValues: true,
      reviveValues: true,
      mergeOutput: true
    })) ?? null
  } catch {
    return null
  }
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
    wmFile = resolveTestAsset(wmPngFallbackName)
    notes.push(`wm-white.png missing; used ${wmFile}`)
  }
  notes.push('contax-t2.jpg found and used as the second EXIF fixture (no substitution).')

  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(outDir, { recursive: true })
  fs.mkdirSync(altDir, { recursive: true })
  fs.mkdirSync(artDir, { recursive: true })

  const profile = path.join(runDir, 'profile')
  const app = await launchApp(profile)
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
  // 1. Explicit storage reset (determinism even if the profile dir survived), reload
  // ---------------------------------------------------------------------------
  console.log('\n--- reset storage ---')
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
  check('localStorage cleared', cleared)
  const idb0 = await idbSummary(page)
  check('IndexedDB presets reset to 0', idb0.count === 0, `count=${idb0.count}`)

  // ---------------------------------------------------------------------------
  // 2. Photo-first home: ImportScreen with zero photos, no cards in the DOM
  // ---------------------------------------------------------------------------
  console.log('\n--- import photos ---')
  check('ImportScreen shown while there are no photos', (await page.locator('.import-screen').count()) === 1)
  check('no .tpl-card before the first photo', (await page.locator('.tpl-card').count()) === 0)
  await importPhotos(page, [fixtureA, fixtureB], 2)
  const thumbTitles = await page.locator('.thumb-main').evaluateAll((els) => els.map((e) => e.getAttribute('title')))
  check('two thumbnails imported', (await page.locator('.thumb').count()) === 2, thumbTitles.join(', '))
  check(
    'thumb names match fixtures',
    thumbTitles.includes('4x3.jpg') && thumbTitles.includes('contax-t2.jpg'),
    thumbTitles.join(', ')
  )
  check('workspace replaces ImportScreen after import', (await page.locator('.import-screen').count()) === 0)
  check('BorderLibrary rendered in the left sidebar', (await page.locator('.border-library').count()) === 1)

  // second photo can be selected
  await page.locator('.thumb-main').nth(1).click()
  await page.waitForFunction(() => document.querySelectorAll('.thumb')[1]?.classList.contains('active') === true)
  const activeThumb = (await page.locator('.thumb.active .thumb-main').getAttribute('title')) ?? ''
  check('second photo can be selected', activeThumb === 'contax-t2.jpg', activeThumb)
  const stageMeta = (await page.locator('.stage-title .stage-meta').innerText()).trim()
  check('preview stage follows the selected photo', stageMeta.includes('contax-t2.jpg'), stageMeta)

  // ---------------------------------------------------------------------------
  // 3. New taxonomy: 175 cards / 7 categories / vendor chips
  // ---------------------------------------------------------------------------
  console.log('\n--- border taxonomy ---')
  check(`all ${TOTAL_CARDS} .tpl-card nodes stay in the DOM`, (await page.locator('.tpl-card').count()) === TOTAL_CARDS)
  const nav = await page.locator('.border-category-nav button').evaluateAll((els) =>
    els.map((e) => ({
      name: e.querySelector('span')?.textContent?.trim() ?? '',
      count: Number((e.querySelector('em')?.textContent ?? '').trim())
    }))
  )
  check('category nav has 7 buttons', nav.length === 7, JSON.stringify(nav))
  for (const c of CATEGORIES) {
    check(
      `category ${c.name} = ${c.count}`,
      nav.some((v) => v.name === c.name && v.count === c.count),
      JSON.stringify(nav)
    )
  }
  check(
    `text category section holds ${CATEGORIES[1].count} cards in the DOM`,
    (await page.locator('.border-section[data-category="text"] .tpl-card').count()) === CATEGORIES[1].count
  )
  const chips = await page.locator('.border-vendor-chips button').evaluateAll((els) =>
    els.map((e) => ({
      name: (e.childNodes[0]?.textContent ?? '').trim(),
      count: e.querySelector('em') ? Number((e.querySelector('em').textContent ?? '').trim()) : null
    }))
  )
  check('vendor chips: 全部 / OPPO / 小米 / vivo', chips.length === 4, JSON.stringify(chips))
  check(
    'vendor chip OPPO = 47',
    chips.some((c) => c.name === 'OPPO' && c.count === 47),
    JSON.stringify(chips)
  )
  check(
    'vendor chip 小米 = 40',
    chips.some((c) => c.name === '小米' && c.count === 40),
    JSON.stringify(chips)
  )
  check(
    'vendor chip vivo = 12',
    chips.some((c) => c.name === 'vivo' && c.count === 12),
    JSON.stringify(chips)
  )
  await page.locator('.border-vendor-chips button', { hasText: 'OPPO' }).click()
  await page.waitForFunction(
    () =>
      document.querySelectorAll('.border-section[data-category="vendor"] .tpl-card:not(.border-card-hidden)').length === 47
  )
  check('OPPO chip narrows the vendor section to 47 visible cards', true)
  check('all 175 cards remain in the DOM while filtered', (await page.locator('.tpl-card').count()) === TOTAL_CARDS)
  await page.locator('.border-vendor-chips button', { hasText: '全部' }).click()
  await page.waitForFunction(
    () =>
      document.querySelectorAll('.border-section[data-category="vendor"] .tpl-card:not(.border-card-hidden)').length === 24
  )
  check('全部 chip restores the first 8 cards of each vendor group (24 visible, 99 mounted)', true)

  // ---------------------------------------------------------------------------
  // 4. Pick 经典双行 via the border library
  // ---------------------------------------------------------------------------
  console.log('\n--- select template ---')
  const card = page.locator('.tpl-card', { hasText: '经典双行' })
  check('exactly one 经典双行 card in the DOM', (await card.count()) === 1, `count=${await card.count()}`)
  await card.first().scrollIntoViewIfNeeded()
  await card.first().click()
  await page.waitForFunction(() => document.querySelector('.current-template h3')?.textContent?.trim() === '经典双行', null, {
    timeout: 15000
  })
  const tplTitle = (await page.locator('.current-template h3').innerText()).trim()
  check('current template title is 经典双行', tplTitle === '经典双行', tplTitle)
  check('active card is 经典双行', (await page.locator('.tpl-card.active').first().innerText()).includes('经典双行'))
  const tabLabels = await page.locator('.editor-tabs button').evaluateAll((els) =>
    els.map((e) => (e.textContent ?? '').trim())
  )
  check(
    'four editor tabs: Logo / 文字 / 布局 / 主题',
    JSON.stringify(tabLabels) === JSON.stringify(['Logo', '文字', '布局', '主题']),
    tabLabels.join(',')
  )

  // ---------------------------------------------------------------------------
  // 5. Advanced/per-style param: 布局 · 文字区高度 (old 全部参数 footerTextScale is gone)
  // ---------------------------------------------------------------------------
  console.log('\n--- advanced param ---')
  await page.locator('.editor-tabs button', { hasText: '布局' }).click()
  await page.waitForSelector('.editor-pane[data-editor-pane="layout"]', { timeout: 10000 })
  check(
    'layout pane always contains 画幅 .segmented',
    (await page.locator('.editor-pane[data-editor-pane="layout"] .segmented').count()) >= 1
  )
  check(
    'layout pane 画幅 defaults to 原始',
    ((await page.locator('.editor-pane[data-editor-pane="layout"] .segmented button.active').first().innerText()) ?? '').trim() === '原始'
  )
  const footerSlider = page
    .locator('.editor-pane[data-editor-pane="layout"] .slider-row', { hasText: '文字区高度' })
    .locator('input[type="range"]')
  check('文字区高度 slider exposed for 经典双行', (await footerSlider.count()) === 1)
  const sliderBefore = await footerSlider.inputValue()
  // The wm-classic variant declares footerPct 11.1; the browser snaps a range
  // input to its step grid (5 + k*0.5), so accept the snapped neighbours too.
  const sliderBeforeNum = Number(sliderBefore)
  check(
    '文字区高度 starts at the wm-classic declared ~11.1',
    sliderBeforeNum >= 10.5 && sliderBeforeNum <= 11.5,
    sliderBefore
  )
  const hashBefore = await canvasHash(page)
  check('preview canvas hashes before the edit', typeof hashBefore === 'string' && hashBefore.length > 0, String(hashBefore))
  await footerSlider.fill('16')
  await page.waitForFunction(
    () => {
      const input = document.querySelector('.editor-pane[data-editor-pane="layout"] .slider-row input[type="range"]')
      return Boolean(input) && input.value === '16'
    },
    null,
    { timeout: 10000 }
  )
  check('文字区高度 edited in UI', (await footerSlider.inputValue()) === '16')
  await page.waitForFunction(
    (prev) => {
      const canvas = document.querySelector('.canvas-frame canvas')
      if (!canvas) return false
      const url = canvas.toDataURL('image/png')
      let h = 2166136261
      for (let i = 0; i < url.length; i += 7) {
        h ^= url.charCodeAt(i)
        h = Math.imul(h, 16777619)
      }
      return `${url.length}:${h >>> 0}` !== prev
    },
    hashBefore,
    { timeout: 30000 }
  )
  check('canvas re-renders after the param edit', true)
  await page.waitForTimeout(900)
  const persisted = await lastDesign(page)
  check('footerPct persisted in lastDesign', Number(persisted?.params?.footerPct) === 16, String(persisted?.params?.footerPct))

  // theme pane check (hand template keeps .theme-swatch)
  await page.locator('.editor-tabs button', { hasText: '主题' }).click()
  await page.waitForSelector('.editor-pane[data-editor-pane="theme"]', { timeout: 10000 })
  check(
    'theme pane shows hand-template .theme-swatch swatches',
    (await page.locator('.editor-pane[data-editor-pane="theme"] .theme-swatch').count()) === 6
  )
  await page.locator('.editor-tabs button', { hasText: '布局' }).click()
  await page.waitForSelector('.editor-pane[data-editor-pane="layout"]', { timeout: 10000 })

  // ---------------------------------------------------------------------------
  // 6. Watermark: tool switch, upload transparent PNG, anchor + sliders
  // ---------------------------------------------------------------------------
  console.log('\n--- watermark ---')
  await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
  await page.waitForSelector('.inspector-pane input[type="file"]', { state: 'attached', timeout: 10000 })
  const wmSwitch = page.locator('.inspector-pane .switch-row', { hasText: '启用水印' }).locator('.switch')
  check('watermark starts disabled', (await wmSwitch.getAttribute('aria-checked')) === 'false')
  const wmInput = page.locator('.inspector-pane input[type="file"]')
  check('watermark file input present', (await wmInput.count()) === 1, `count=${await wmInput.count()}`)
  await wmInput.setInputFiles(wmFile)
  await page.waitForSelector('.wm-asset .wm-preview img', { timeout: 30000 })
  const wmSrc = (await page.locator('.wm-asset .wm-preview img').getAttribute('src')) ?? ''
  const wmInfo = (await page.locator('.wm-asset .wm-info').innerText()).replace(/\n+/g, ' | ')
  check('watermark preview image is a PNG data URL', wmSrc.startsWith('data:image/png'), wmSrc.slice(0, 30))
  check('watermark asset is wm-white.png', wmInfo.includes('wm-white.png'), wmInfo)
  check('watermark dimensions shown 1736x561', wmInfo.includes('1736') && wmInfo.includes('561'), wmInfo)
  check('watermark enabled by upload', (await wmSwitch.getAttribute('aria-checked')) === 'true')
  check(
    'watermark tool dot visible',
    (await page.locator('.inspector-tool-switch button', { hasText: '水印' }).locator('.tab-dot').count()) === 1
  )
  check('direct-manipulation layer appears over the canvas', (await page.locator('.wm-placement').count()) === 1)
  check('.wm-drag-box present', (await page.locator('.wm-drag-box').count()) === 1)
  check('nine .wm-anchor-dot handles on the layer', (await page.locator('.wm-anchor-dot').count()) === 9)
  check('tile switch 平铺整个区域 present', (await page.locator('.inspector-pane .switch-row', { hasText: '平铺整个区域' }).count()) === 1)
  check('旋转 slider present', (await page.locator('.inspector-pane .slider-row', { hasText: '旋转' }).count()) === 1)
  check('间距 slider present', (await page.locator('.inspector-pane .slider-row', { hasText: '间距' }).count()) === 1)

  await page.locator('.anchor-grid button[title="左下"]').click()
  await page.waitForFunction(() => document.querySelector('.anchor-grid button[title="左下"]')?.classList.contains('active') === true)
  check('anchor can move to 左下', true)
  await page.locator('.anchor-grid button[title="右下"]').click()
  await page.waitForFunction(() => document.querySelector('.anchor-grid button[title="右下"]')?.classList.contains('active') === true)
  check('anchor returns to 右下', true)

  const wmWidth = page.locator('.inspector-pane .slider-row', { hasText: '宽度' }).locator('input[type="range"]')
  await wmWidth.fill('20')
  check('watermark 宽度 set to 20%', (await wmWidth.inputValue()) === '20', await wmWidth.inputValue())
  const wmOpacity = page.locator('.inspector-pane .slider-row', { hasText: '不透明度' }).locator('input[type="range"]')
  await wmOpacity.fill('0.95')
  check('watermark 不透明度 set to 0.95', (await wmOpacity.inputValue()) === '0.95', await wmOpacity.inputValue())
  await page.screenshot({ path: path.join(artDir, '01-watermark-enabled.png') })

  // ---------------------------------------------------------------------------
  // 7. Export the current photo as PNG via the NEW dialog; pixel-prove the watermark
  // ---------------------------------------------------------------------------
  console.log('\n--- export dialog + watermark pixels ---')
  await page.locator('.thumb-main').nth(0).click()
  await page.waitForFunction(() => document.querySelectorAll('.thumb')[0]?.classList.contains('active') === true)
  await openExportDialog(page)
  check('export dialog opened from the topbar', true)
  const scopeTop = (
    (await page.locator('.export-dialog label.field', { hasText: '范围' }).locator('.segmented button.active').innerText()) ?? ''
  ).trim()
  check('dialog defaults to 当前照片 scope', scopeTop === '当前照片', scopeTop)
  await pickExportDir(app, page, outDir)
  check('export dir redirected to the test out dir', (await page.locator('.export-dir-row input').inputValue()) === outDir)
  const edgeChips = await page.locator('.export-long-edge .toggle-chip').evaluateAll((els) =>
    els.map((e) => (e.textContent ?? '').trim())
  )
  check(
    'long-edge chips are 原始尺寸 / 4096 / 3072 / 2048',
    JSON.stringify(edgeChips) === JSON.stringify(['原始尺寸', '4096', '3072', '2048']),
    edgeChips.join(',')
  )
  check(
    'long edge defaults to 原始尺寸',
    ((await page.locator('.export-long-edge .toggle-chip.on').innerText()) ?? '').trim() === '原始尺寸'
  )
  check('移除 GPS switch present', (await page.locator('.export-dialog .switch-row', { hasText: '移除 GPS' }).count()) === 1)
  await chooseFormat(page, 'PNG')
  check('PNG format selected in the dialog', true)
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 10000 })
  await waitExportDone(page)
  const pngCardText = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  check('PNG overlay reports 导出完成', pngCardText.includes('导出完成'), pngCardText)
  check('PNG overlay reports 成功 1 张', pngCardText.includes('成功 1 张'), pngCardText)
  check('PNG no .export-errors', (await page.locator('.export-errors').count()) === 0, pngCardText)
  const wmFiles = listFiles(outDir)
  check('watermark PNG: exactly 1 file', wmFiles.length === 1, wmFiles.join(', '))
  check('watermark PNG: default {name}-{template} name', wmFiles[0] === '4x3-经典双行.png', wmFiles.join(', '))
  if (wmFiles.length === 1 && wmFiles[0] === '4x3-经典双行.png') {
    const wmBuf = fs.readFileSync(path.join(outDir, wmFiles[0]))
    const wmMeta = await sharp(wmBuf).metadata()
    check('watermark PNG decodes as png', wmMeta.format === 'png', `${wmMeta.format} ${wmMeta.width}x${wmMeta.height}`)
    check('watermark PNG width = 2667 (no long-edge limit)', wmMeta.width === 2667, `${wmMeta.width}x${wmMeta.height}`)
    check('watermark PNG height grows with the footer', (wmMeta.height ?? 0) > 2000, `${wmMeta.width}x${wmMeta.height}`)
    check('watermark PNG size > 0', fs.statSync(path.join(outDir, wmFiles[0])).size > 0)
    // White watermark, 20%W at 95% opacity, anchored bottom-right of the photo:
    // count bright pixels in the bottom-right 30% of the photo region.
    const region = {
      left: Math.floor(2667 * 0.7),
      top: Math.floor(2000 * 0.7),
      width: 2667 - Math.floor(2667 * 0.7),
      height: 2000 - Math.floor(2000 * 0.7)
    }
    const raw = await sharp(wmBuf).extract(region).raw().toBuffer({ resolveWithObject: true })
    let bright = 0
    for (let p = 0; p < raw.data.length; p += raw.info.channels) {
      const min = Math.min(raw.data[p], raw.data[p + 1], raw.data[p + 2])
      if (min >= 220) bright++
    }
    check(
      'watermark PNG contains watermark pixels in the bottom-right corner',
      bright >= 500,
      `${bright} bright px in ${raw.info.width}x${raw.info.height} bottom-right region`
    )
  }
  await closeExportOverlay(page)

  // ---------------------------------------------------------------------------
  // 8. Filename tokens + leave the design on WebP before saving the preset
  // ---------------------------------------------------------------------------
  console.log('\n--- filename pattern ---')
  const pattern = '{index}-{name}-{template}-{make}'
  await openExportDialog(page)
  const patternInput = page.locator('.export-dialog label.field', { hasText: '文件名' }).locator('input')
  await patternInput.fill(pattern)
  check('filename pattern applied', (await patternInput.inputValue()) === pattern, await patternInput.inputValue())
  await chooseFormat(page, 'WebP')
  check('format switched to WebP in the dialog', (await activeFormatLabel(page)) === 'WebP')
  await closeExportDialog(page)
  check('export dialog closed via 取消', (await page.locator('.export-dialog').count()) === 0)

  // ---------------------------------------------------------------------------
  // 9. Save a preset
  // ---------------------------------------------------------------------------
  console.log('\n--- save preset ---')
  const presetName = `E2E工作流-${Date.now()}`
  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.preset-save-box', { timeout: 10000 })
  await page.locator('.preset-save-box input').fill(presetName)
  await page.locator('.preset-save-box button', { hasText: '保存当前设计' }).click()
  await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 15000 })
  await page.waitForFunction(
    () => document.querySelector('.toast')?.textContent?.includes('已保存') === true,
    null,
    { timeout: 15000 }
  )
  check('preset save toast shown', true)
  let idb = await idbSummary(page)
  const savedPreset = idb.presets.find((p) => p.name === presetName)
  check('preset persisted in IndexedDB', Boolean(savedPreset), JSON.stringify(idb.presets.map((p) => p.name)))
  check('preset stores wm-classic variant', savedPreset?.variantId === 'wm-classic', String(savedPreset?.variantId))
  check('preset stores edited footerPct 16', Number(savedPreset?.footerPct) === 16, String(savedPreset?.footerPct))
  check('preset stores watermark enabled', savedPreset?.wmEnabled === true)
  check('preset stores watermark anchor 右下', savedPreset?.wmAnchor === 'bottom-right', String(savedPreset?.wmAnchor))
  check('preset stores watermark 宽度 20', Number(savedPreset?.wmWidthPct) === 20, String(savedPreset?.wmWidthPct))
  check('preset stores watermark opacity 0.95', Number(savedPreset?.wmOpacity) === 0.95, String(savedPreset?.wmOpacity))
  check(
    'preset stores watermark PNG asset',
    String(savedPreset?.wmAsset?.prefix ?? '').startsWith('data:image/png;base64'),
    String(savedPreset?.wmAsset?.prefix)
  )
  check('preset stores export format webp', savedPreset?.format === 'webp', String(savedPreset?.format))
  check('preset stores the filename pattern', savedPreset?.filenamePattern === pattern, String(savedPreset?.filenamePattern))
  check('preset does not store machine-specific outputDir', !savedPreset?.outputDir, String(savedPreset?.outputDir))

  // ---------------------------------------------------------------------------
  // 10. Disturb the live design, reload with the same isolated profile
  // ---------------------------------------------------------------------------
  console.log('\n--- disturb + reload ---')
  await openExportDialog(page)
  await chooseFormat(page, 'JPG')
  check('pre-reload live format is JPG', (await activeFormatLabel(page)) === 'JPG')
  await page.locator('.export-dialog label.field', { hasText: '文件名' }).locator('input').fill('e2e-tmp-{name}')
  await closeExportDialog(page)
  await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
  await page.locator('.inspector-pane .switch-row', { hasText: '启用水印' }).click()
  await page.waitForFunction(
    () => document.querySelector('.inspector-pane .switch-row .switch')?.getAttribute('aria-checked') === 'false'
  )
  check('watermark disabled before reload', true)
  check(
    'watermark tool dot gone while disabled',
    (await page.locator('.inspector-tool-switch button', { hasText: '水印' }).locator('.tab-dot').count()) === 0
  )
  await page.locator('.inspector-tool-switch button', { hasText: '边框' }).click()
  await page.locator('.editor-tabs button', { hasText: '布局' }).click()
  const footerSlider2 = page
    .locator('.editor-pane[data-editor-pane="layout"] .slider-row', { hasText: '文字区高度' })
    .locator('input[type="range"]')
  await footerSlider2.fill('9')
  check('footer slider moved to 9 before reload', (await footerSlider2.inputValue()) === '9')
  await page.waitForTimeout(900)
  const preReload = await lastDesign(page)
  check(
    'pre-reload lastDesign has footerPct 9 + disabled watermark',
    Number(preReload?.params?.footerPct) === 9 && preReload?.watermark?.enabled === false,
    JSON.stringify({ footerPct: preReload?.params?.footerPct, wm: preReload?.watermark?.enabled })
  )

  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
  await page.waitForTimeout(500)
  check('photos are not persisted across reload', (await page.locator('.thumb').count()) === 0)
  check('app is back on ImportScreen after reload', (await page.locator('.import-screen').count()) === 1)
  idb = await idbSummary(page)
  check('preset survives reload in IndexedDB', idb.presets.some((p) => p.name === presetName), JSON.stringify(idb.presets.map((p) => p.name)))

  // ---------------------------------------------------------------------------
  // 11. Re-import, move the output dir, apply the preset, verify full restoration
  // ---------------------------------------------------------------------------
  console.log('\n--- reload + apply preset ---')
  await importPhotos(page, [fixtureA, fixtureB], 2)
  check('photos re-imported after reload', (await page.locator('.thumb').count()) === 2)

  await openExportDialog(page)
  check('design keeps pre-apply outputDir', (await page.locator('.export-dir-row input').inputValue()) === outDir)
  check('design keeps pre-apply format JPG', (await activeFormatLabel(page)) === 'JPG')
  check(
    'design keeps pre-apply temp pattern',
    (await page.locator('.export-dialog label.field', { hasText: '文件名' }).locator('input').inputValue()) === 'e2e-tmp-{name}'
  )
  await pickExportDir(app, page, altDir)
  check('output dir can be changed before preset apply', (await page.locator('.export-dir-row input').inputValue()) === altDir)
  await closeExportDialog(page)

  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.preset-save-box', { timeout: 10000 })
  const presetRow = page.locator('.preset-row', { hasText: presetName })
  check('saved preset listed after reload', (await presetRow.count()) === 1, `rows=${await page.locator('.preset-row').count()}`)
  await presetRow.locator('button', { hasText: '应用' }).click()
  await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 15000 })
  await page.waitForFunction(
    (n) => document.querySelector('.toast')?.textContent?.includes(`已应用「${n}」`) === true,
    presetName,
    { timeout: 15000 }
  )
  check('preset apply toast shown', true)

  const tplAfter = (await page.locator('.current-template h3').innerText()).trim()
  check('preset restores 经典双行 template', tplAfter === '经典双行', tplAfter)
  await page.locator('.editor-tabs button', { hasText: '布局' }).click()
  const footerSlider3 = page
    .locator('.editor-pane[data-editor-pane="layout"] .slider-row', { hasText: '文字区高度' })
    .locator('input[type="range"]')
  check('preset restores edited footerPct 16', (await footerSlider3.inputValue()) === '16', await footerSlider3.inputValue())

  await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
  await page.waitForSelector('.wm-preview img', { timeout: 15000 })
  const wmSrcAfter = (await page.locator('.wm-preview img').getAttribute('src')) ?? ''
  const wmSwitchAfter = page.locator('.inspector-pane .switch-row', { hasText: '启用水印' }).locator('.switch')
  check('preset restores watermark preview', wmSrcAfter.startsWith('data:image/png'), wmSrcAfter.slice(0, 30))
  check('preset restores watermark enabled', (await wmSwitchAfter.getAttribute('aria-checked')) === 'true')
  check(
    'preset restores watermark tool dot',
    (await page.locator('.inspector-tool-switch button', { hasText: '水印' }).locator('.tab-dot').count()) === 1
  )
  await page.screenshot({ path: path.join(artDir, '02-preset-restored.png') })

  await openExportDialog(page)
  check('preset restores WebP format', (await activeFormatLabel(page)) === 'WebP')
  check(
    'preset restores filename pattern',
    (await page.locator('.export-dialog label.field', { hasText: '文件名' }).locator('input').inputValue()) === pattern
  )
  check('preset apply preserves current output dir', (await page.locator('.export-dir-row input').inputValue()) === altDir)
  await pickExportDir(app, page, outDir)
  check('output dir back on the seeded path for the batches', (await page.locator('.export-dir-row input').inputValue()) === outDir)
  await closeExportDialog(page)

  // ---------------------------------------------------------------------------
  // 12. JPG / PNG / WebP / TIFF batches through the export dialog
  // ---------------------------------------------------------------------------
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
        const exif = await parseExifSafe(buf)
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
          // The white watermark is composited at 95% opacity over dark leaves in
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

  console.log('\n--- batch PNG ---')
  await runBatch(page, {
    label: 'PNG',
    ext: OUT_EXT.png,
    expectedNames: baseNames.map((b) => `${b}.png`),
    onFiles: async (files) => {
      for (let i = 0; i < files.length; i++) {
        const f = files[i]
        const src = sources.find((s) => f.includes(s.name.replace(/\.[^.]+$/, ''))) ?? sources[0]
        const buf = fs.readFileSync(path.join(outDir, f))
        const meta = await sharp(buf).metadata()
        check(`PNG ${f} decodes as png @${src.width}x`, meta.format === 'png' && meta.width === src.width, `${meta.format} ${meta.width}x${meta.height}`)
        check(`PNG ${f} height grows with footer`, (meta.height ?? 0) > src.height, `${meta.width}x${meta.height}`)
        check(`PNG ${f} size > 0`, fs.statSync(path.join(outDir, f)).size > 0)
        const exif = await parseExifSafe(buf)
        check(`PNG ${f} carries no EXIF`, !exif?.Make && !exif?.Model, JSON.stringify({ make: exif?.Make ?? null }))
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
        const buf = fs.readFileSync(path.join(outDir, f))
        const meta = await sharp(buf).metadata()
        check(`WebP ${f} decodes as webp`, meta.format === 'webp', `${meta.format} ${meta.width}x${meta.height}`)
        check(`WebP ${f} size > 0`, fs.statSync(path.join(outDir, f)).size > 0)
        const exif = await parseExifSafe(buf)
        check(`WebP ${f} carries no EXIF`, !exif?.Make && !exif?.Model, JSON.stringify({ make: exif?.Make ?? null }))
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
        check(`TIFF ${f} size > 0`, fs.statSync(path.join(outDir, f)).size > 0)
      }
    }
  })

  // ---------------------------------------------------------------------------
  // 13. Cancel one batch (add two large photos so cancellation is deterministic)
  // ---------------------------------------------------------------------------
  console.log('\n--- cancel batch ---')
  await importPhotos(page, [bigA, bigB], 4)
  check('two large photos added for cancel test', (await page.locator('.thumb').count()) === 4)
  await cleanOut()
  await openExportDialog(page, 'filmstrip')
  await chooseFormat(page, 'JPG')
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.locator('.export-card button', { hasText: '取消导出' }).click({ timeout: 30000 })
  await waitExportDone(page)
  const cancelText = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  check('cancel overlay reports 已取消', cancelText.includes('已取消'), cancelText)
  check('cancel produces no export errors', (await page.locator('.export-errors').count()) === 0, cancelText)
  check('app shell still alive after cancel', await page.locator('.app-shell').isVisible())
  await page.screenshot({ path: path.join(artDir, '04-cancelled.png') })
  await closeExportOverlay(page)
  check('export overlay closed after cancel', (await page.locator('.export-card').count()) === 0)

  // ---------------------------------------------------------------------------
  // 14. Preset JSON export / delete / import roundtrip (no native dialogs)
  // ---------------------------------------------------------------------------
  console.log('\n--- preset JSON roundtrip ---')
  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.preset-save-box', { timeout: 10000 })
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
  await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 10000 })
  check('preset dialog closed via 完成', true)

  // ---------------------------------------------------------------------------
  // 15. Zero pageerror / console error
  // ---------------------------------------------------------------------------
  await page.waitForTimeout(800)
  check('no pageerror across the whole workflow', pageErrors.length === 0, pageErrors.join(' | '))
  check('no console.error across the whole workflow', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
  if (consoleErrors.length) {
    notes.push(`console.error entries: ${consoleErrors.slice(0, 5).join(' | ')}`)
  }

  await page.screenshot({ path: path.join(artDir, '05-final-app.png') })
  await app.close()

  console.log('\n=== RESULT ===')
  console.log(JSON.stringify({ assertions: passed + failed, passed, failed, failures, defects, notes }))
  process.exitCode = failed > 0 ? 1 : 0
}

try {
  await main()
} catch (error) {
  failed++
  failures.push({ name: 'harness', detail: String(error?.stack ?? error) })
  console.error('FAIL  harness ::', error?.stack ?? error)
  console.log('\n=== RESULT ===')
  console.log(JSON.stringify({ assertions: passed + failed, passed, failed, failures, defects, notes }))
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