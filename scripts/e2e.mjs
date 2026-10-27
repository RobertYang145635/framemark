/**
 * FrameMark e2e repo gate (read by scripts/final-check.cjs T6) — migrated to the v4
 * photo-first UI shell (2026-10-27).
 *
 * Covers, in order:
 *   1. photo-first boot: no photos -> `.import-screen`, ZERO `.tpl-card`; after import
 *      the workspace replaces it (`.thumb` + `.workspace`).
 *   2. taxonomy: 175 `.tpl-card` in the DOM, ZERO `.tpl-card canvas` (cards are static
 *      previews), 7 `.border-category-nav` buttons with the exact 厂商/文字/印象/白边/
 *      卡片/色彩/简约 counts, vendor chips 全部 / OPPO 47 / 小米 40 / vivo 12 and the
 *      OPPO chip narrowing the vendor section to 47 visible cards (the tail stays in the
 *      DOM with `.border-card-hidden`, the 175-node contract is not violated).
 *   3. stock theme on 经典 · 17: the 主题 tab is the per-style themeSwatches control ONLY
 *      (0 generic `.theme-swatches`, 1 `.fmk-swatches` entry, 4 `.fmk-swatch` labelled
 *      白底/黑底/浅色虚化/深色虚化); clicking 深色虚化 marks it active.
 *   4. Logo tab on 经典 · 17: `.logo-row` (LogoSection) present, ZERO `.editor-pane
 *      .fmk-select` (the duplicate per-style brand picker is folded into LogoSection).
 *   5. export through the NEW dialog with an isolated output dir (main-process dialog is
 *      stubbed, exactly like the old script did): 导出当前 -> 选择 -> PNG -> 开始导出 ->
 *      `.export-card` 导出完成 -> exactly one non-trivial `.png` in the out dir.
 *
 * ISOLATION: throw-away `--user-data-dir` under the run dir so every run is a genuine
 * first start and never shares a GPU shader cache with another instance.
 *
 * Prints one JSON object at the end with `"total": 175, "categories": 7 ... "errors": []`
 * (final-check.cjs T6 regex). Exits non-zero on any failed assertion.
 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { _electron as electron } from 'playwright-core'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const workspace = path.resolve(root, '..')
const runDir = path.join(workspace, '_research', 'e2e')
const outDir = path.join(runDir, 'exports')
const profile = path.join(runDir, 'profile')
const fixture = path.join(workspace, '_research', 'test-assets', 'ratio-study', '4x3.jpg')

assert.ok(fs.existsSync(fixture), `fixture missing: ${fixture}`)
fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
fs.mkdirSync(outDir, { recursive: true })
fs.mkdirSync(profile, { recursive: true })

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE

const app = await electron.launch({
  executablePath: require('electron'),
  args: ['.', `--user-data-dir=${profile}`],
  cwd: root,
  env,
  timeout: 60000
})

try {
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

  // ---------------------------------------------------------------------------
  // 1. Photo-first boot: ImportScreen while there are no photos
  // ---------------------------------------------------------------------------
  assert.equal(await page.locator('.import-screen').count(), 1, 'no-photo boot must show .import-screen')
  assert.equal(await page.locator('.tpl-card').count(), 0, 'no .tpl-card before the first photo')

  await page.setInputFiles('#framemark-file-input', fixture)
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForSelector('.workspace', { timeout: 30000 })
  assert.equal(await page.locator('.import-screen').count(), 0, 'import screen must be replaced by the workspace')
  assert.equal(await page.locator('.thumb').count(), 1, 'exactly one imported thumbnail')
  console.log('PASS  photo-first boot: .import-screen -> import -> .workspace + .thumb')

  // ---------------------------------------------------------------------------
  // 2. Taxonomy: 175 static cards / 7 categories / vendor chips
  // ---------------------------------------------------------------------------
  await page.waitForFunction(() => document.querySelectorAll('.tpl-card').length === 175, null, { timeout: 30000 })
  const total = await page.locator('.tpl-card').count()
  assert.equal(total, 175, `expected 175 .tpl-card, got ${total}`)
  assert.equal(await page.locator('.tpl-card canvas').count(), 0, 'cards must use static previews (no canvas)')

  const EXPECTED_CATEGORIES = [
    { name: '厂商边框', count: 99 },
    { name: '文字边框', count: 21 },
    { name: '印象边框', count: 13 },
    { name: '白边边框', count: 12 },
    { name: '卡片边框', count: 19 },
    { name: '色彩边框', count: 6 },
    { name: '简约边框', count: 5 }
  ]
  const nav = await page.locator('.border-category-nav button').evaluateAll((els) =>
    els.map((e) => ({
      name: e.querySelector('span')?.textContent?.trim() ?? '',
      count: Number((e.querySelector('em')?.textContent ?? '').trim())
    }))
  )
  assert.equal(nav.length, 7, `expected 7 category buttons, got ${nav.length}`)
  for (const c of EXPECTED_CATEGORIES) {
    assert.ok(
      nav.some((v) => v.name === c.name && v.count === c.count),
      `category ${c.name} ${c.count} missing in ${JSON.stringify(nav)}`
    )
  }
  const categories = nav.length
  console.log('PASS  category nav:', JSON.stringify(nav))

  const chips = await page.locator('.border-vendor-chips button').evaluateAll((els) =>
    els.map((e) => ({
      name: (e.childNodes[0]?.textContent ?? '').trim(),
      count: e.querySelector('em') ? Number((e.querySelector('em')?.textContent ?? '').trim()) : null
    }))
  )
  assert.equal(chips.length, 4, `expected 4 vendor chips, got ${JSON.stringify(chips)}`)
  assert.deepEqual(chips.map((c) => c.name), ['全部', 'OPPO', '小米', 'vivo'], JSON.stringify(chips))
  assert.equal(chips.find((c) => c.name === 'OPPO')?.count, 47, JSON.stringify(chips))
  assert.equal(chips.find((c) => c.name === '小米')?.count, 40, JSON.stringify(chips))
  assert.equal(chips.find((c) => c.name === 'vivo')?.count, 12, JSON.stringify(chips))
  console.log('PASS  vendor chips:', JSON.stringify(chips))

  // The chip filters the vendor section only; hidden cards stay in the DOM (175 contract).
  await page.locator('.border-vendor-chips button', { hasText: 'OPPO' }).click()
  await page.waitForFunction(
    () => document.querySelectorAll('.border-section[data-category="vendor"] .tpl-card:not(.border-card-hidden)').length === 47,
    null,
    { timeout: 15000 }
  )
  const vendorVisible = await page.evaluate(
    () => document.querySelectorAll('.border-section[data-category="vendor"] .tpl-card:not(.border-card-hidden)').length
  )
  assert.equal(vendorVisible, 47, `OPPO chip must leave 47 vendor .tpl-card, got ${vendorVisible}`)
  assert.equal(await page.locator('.tpl-card').count(), 175, 'filtering must not remove cards from the DOM')
  console.log('PASS  OPPO chip: vendor section .tpl-card visible = 47 (175 stay mounted)')

  await page.locator('.border-vendor-chips button', { hasText: '全部' }).click()
  await page.waitForFunction(
    () => document.querySelectorAll('.border-section[data-category="vendor"] .tpl-card:not(.border-card-hidden)').length === 99,
    null,
    { timeout: 15000 }
  )

  // ---------------------------------------------------------------------------
  // 3. Stock theme on 经典 · 17: 主题 tab = per-style themeSwatches only
  // ---------------------------------------------------------------------------
  const card = page.locator('.tpl-card', { hasText: '经典 · 17' }).first()
  assert.equal(await page.locator('.tpl-card', { hasText: '经典 · 17' }).count(), 1, 'unique 经典 · 17 card')
  await card.scrollIntoViewIfNeeded()
  await card.click()
  await page.waitForFunction(
    () => document.querySelector('.current-template h3')?.textContent?.trim() === '经典 · 17',
    null,
    { timeout: 15000 }
  )

  await page.locator('.editor-tabs button', { hasText: '主题' }).click()
  await page.waitForSelector('.editor-pane[data-editor-pane="theme"]', { timeout: 10000 })
  const themePane = page.locator('.editor-pane[data-editor-pane="theme"]')
  assert.equal(await page.locator('.editor-pane .theme-swatches').count(), 0, 'generic theme swatches must be gone on a stock theme')
  assert.equal(await themePane.locator('.fmk-swatches').count(), 1, 'exactly one theme control entry')
  const swatchCount = await themePane.locator('.fmk-swatch').count()
  assert.equal(swatchCount, 4, `expected 4 .fmk-swatch, got ${swatchCount}`)
  const swatchLabels = await themePane.locator('.fmk-swatch span').evaluateAll((els) => els.map((e) => (e.textContent ?? '').trim()))
  assert.deepEqual(swatchLabels, ['白底', '黑底', '浅色虚化', '深色虚化'], JSON.stringify(swatchLabels))
  await themePane.locator('.fmk-swatch', { hasText: '深色虚化' }).click()
  await page.waitForFunction(
    () => document.querySelector('.editor-pane[data-editor-pane="theme"] .fmk-swatch.active span')?.textContent?.trim() === '深色虚化',
    null,
    { timeout: 10000 }
  )
  const activeTheme = (await themePane.locator('.fmk-swatch.active span').innerText()).trim()
  assert.equal(activeTheme, '深色虚化', `active swatch label = ${activeTheme}`)
  console.log('PASS  stock theme: 0 .theme-swatches, 1 .fmk-swatches, 4 .fmk-swatch', JSON.stringify(swatchLabels))

  // ---------------------------------------------------------------------------
  // 4. Logo tab on 经典 · 17: LogoSection, no duplicate per-style brand picker
  // ---------------------------------------------------------------------------
  await page.locator('.editor-tabs button', { hasText: 'Logo' }).click()
  await page.waitForSelector('.editor-pane[data-editor-pane="logo"]', { timeout: 10000 })
  const logoPane = page.locator('.editor-pane[data-editor-pane="logo"]')
  assert.equal(await logoPane.locator('.logo-row').count(), 1, 'logo tab must show .logo-row (LogoSection)')
  assert.equal(await page.locator('.editor-pane .fmk-select').count(), 0, 'no duplicate per-style brand picker (.fmk-select)')
  console.log('PASS  logo tab: .logo-row present, 0 .editor-pane .fmk-select')

  // ---------------------------------------------------------------------------
  // 5. Export the current photo as PNG through the NEW dialog (isolated out dir)
  // ---------------------------------------------------------------------------
  fs.rmSync(outDir, { recursive: true, force: true })
  fs.mkdirSync(outDir, { recursive: true })
  await app.evaluate(async ({ dialog }, dir) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
  }, outDir)

  await page.locator('.topbar-actions .btn-primary').click()
  await page.waitForSelector('.modal .export-dialog', { timeout: 10000 })
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, outDir, { timeout: 15000 })
  const formatField = page.locator('.export-dialog label.field', { hasText: '格式' })
  await formatField.locator('button', { hasText: 'PNG', exact: true }).click()
  await page.waitForFunction(() => {
    const fields = [...document.querySelectorAll('.export-dialog label.field')]
    const sec = fields.find((f) => f.textContent?.includes('格式'))
    return sec?.querySelector('.segmented button.active')?.textContent?.trim() === 'PNG'
  }, null, { timeout: 10000 })
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 15000 })
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(() => {
    const h = document.querySelector('.export-card h3')
    return h && !h.textContent?.includes('正在导出')
  }, null, { timeout: 240000 })
  const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  assert.ok(status.includes('导出完成'), `export failed: ${status}`)
  const exported = fs.readdirSync(outDir).filter((f) => f.toLowerCase().endsWith('.png'))
  assert.equal(exported.length, 1, `expected exactly one exported .png, got ${JSON.stringify(exported)}`)
  const exportedSize = fs.statSync(path.join(outDir, exported[0])).size
  assert.ok(exportedSize > 20 * 1024, `exported PNG is trivially small: ${exportedSize} bytes`)
  console.log(`PASS  export PNG: ${exported[0]} (${exportedSize} bytes) — ${status}`)

  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  await page.waitForTimeout(300)

  assert.equal(errors.length, 0, `page/console errors: ${errors.join(' | ')}`)

  console.log(
    JSON.stringify(
      {
        total,
        categories,
        nav,
        vendorChips: chips,
        vendorVisible,
        themeSwatches: 0,
        fmkSwatches: swatchCount,
        exported,
        exportedSize,
        exportStatus: status,
        errors: []
      },
      null,
      2
    )
  )
} finally {
  await app.close().catch(() => undefined)
}