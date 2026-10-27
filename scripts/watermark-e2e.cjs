/**
 * FrameMark watermark placement + export E2E — migrated to the v4 photo-first shell.
 *
 * Two phases (same coverage as before):
 *   phase 1 photo anchor: import 3 photos -> select the 2nd -> 水印 tool -> upload a
 *     transparent PNG -> direct-manipulation layer checks (NEW: `.wm-placement`,
 *     `.wm-drag-box`, 9 `.wm-anchor-dot`; the 右下 dot is CLICKED, never dragged) ->
 *     `.anchor-grid` 右下 + sliders 宽度/不透明度/旋转 -> screenshot -> export JPG
 *     through the NEW 导出 dialog (topbar 导出当前 -> 开始导出 -> `.export-card` 导出完成).
 *   phase 2 tile: switch 平铺整个区域 -> sliders 宽度/不透明度/间距/旋转 -> screenshot ->
 *     export again through the same dialog.
 *
 * Screenshots stay under D:/border&watermark/_research/shots/desktop/.
 * ISOLATION: throw-away `--user-data-dir` under the run dir (fresh profile per run).
 * Exits non-zero on any failed phase or any pageerror/console.error.
 */
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const PROJECT = path.resolve(__dirname, '..')
const SHOTS = 'D:/border&watermark/_research/shots/desktop'
const WM_WHITE = 'D:/border&watermark/_research/test-assets/wm-white.png'

async function setSlider(page, label, value) {
  return page.evaluate(({ label, value }) => {
    const rows = [...document.querySelectorAll('.inspector-pane .slider-row')]
    const row = rows.find((r) => {
      const t = r.querySelector('.slider-label')?.textContent?.trim() || ''
      return t === label || t.startsWith(label)
    })
    if (!row) throw new Error('slider not found: ' + label)
    const input = row.querySelector('input[type=range]')
    if (!input || input.disabled) throw new Error('slider disabled: ' + label)
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(input, String(value))
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    return input.value
  }, { label, value })
}

async function waitExport(page, timeout = 180000) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  const start = Date.now()
  let last = ''
  while (Date.now() - start < timeout) {
    await page.waitForTimeout(800)
    const txt = await page.locator('.export-card').innerText().catch(() => '')
    if (txt !== last) { console.log(((Date.now() - start) / 1000).toFixed(1) + 's', txt.replace(/\n+/g, ' | ')); last = txt }
    if (txt.includes('导出完成') || txt.includes('失败')) return txt
  }
  throw new Error('export timeout')
}

/** Export the current photo through the NEW dialog: topbar 导出当前 -> 开始导出. */
async function exportCurrent(page) {
  await page.locator('.topbar-actions .btn-primary').click()
  await page.waitForSelector('.export-dialog', { timeout: 10000 })
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 15000 })
  const text = await waitExport(page)
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  await page.waitForSelector('.export-card', { state: 'detached', timeout: 15000 }).catch(() => undefined)
  return text
}

async function main() {
  const failures = []
  if (!fs.existsSync(WM_WHITE)) throw new Error('missing watermark fixture: ' + WM_WHITE)
  fs.mkdirSync(SHOTS, { recursive: true })
  const runDir = path.join(PROJECT, '..', '_research', 'watermark-run')
  const profile = path.join(runDir, 'profile')
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: PROJECT,
    env,
    timeout: 60000
  })

  try {
    const page = await app.firstWindow()
    const errors = []
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`) })
    await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
    await page.waitForSelector('.import-screen', { timeout: 20000 })

    await page.setInputFiles('#framemark-file-input', [
      'D:/border&watermark/_research/test-assets/p1170883.jpg',
      'D:/border&watermark/_research/test-assets/p1181817.jpg',
      'D:/border&watermark/_research/test-assets/vertical.jpg'
    ])
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 3, null, { timeout: 180000 })
    await page.waitForTimeout(1500)
    await page.locator('.thumb-main').nth(1).click()
    await page.waitForFunction(() => document.querySelectorAll('.thumb')[1]?.classList.contains('active') === true)

    // ---- phase 1: photo anchor ----
    await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
    await page.waitForSelector('.inspector-pane input[type=file]', { state: 'attached', timeout: 10000 })
    await page.setInputFiles('.inspector-pane input[type=file]', WM_WHITE)
    await page.waitForSelector('.wm-asset .wm-preview img', { timeout: 30000 })
    await page.waitForSelector('.wm-drag-box', { timeout: 30000 })

    // NEW: direct-manipulation layer over the preview canvas
    const placement = await page.locator('.wm-placement').count()
    const dragBoxes = await page.locator('.wm-drag-box').count()
    const dots = await page.locator('.wm-anchor-dot').count()
    console.log('wm placement layer:', { placement, dragBoxes, dots })
    if (placement !== 1) failures.push(`.wm-placement count ${placement} !== 1`)
    if (dragBoxes !== 1) failures.push(`.wm-drag-box count ${dragBoxes} !== 1`)
    if (dots !== 9) failures.push(`.wm-anchor-dot count ${dots} !== 9`)

    // Click the 右下 dot on the layer (after moving away to 左上 so the flip is real).
    // The drag box can sit over the bottom-right dot, so the 左上 hop also keeps the
    // click deterministic without ever dragging with the mouse.
    await page.locator('.wm-anchor-dot[title="左上对齐"]').click()
    await page.waitForFunction(() => document.querySelector('.wm-anchor-dot[title="左上对齐"]')?.classList.contains('active') === true)
    await page.locator('.wm-anchor-dot[title="右下对齐"]').click()
    await page.waitForFunction(() => document.querySelector('.wm-anchor-dot[title="右下对齐"]')?.classList.contains('active') === true)
    const activeDots = await page.locator('.wm-anchor-dot.active').count()
    const activeDotTitle = await page.locator('.wm-anchor-dot.active').getAttribute('title')
    console.log('wm active dot:', activeDotTitle, 'active count:', activeDots)
    if (activeDotTitle !== '右下对齐' || activeDots !== 1) failures.push(`右下 dot not active after click: ${activeDotTitle} x${activeDots}`)

    await page.locator('.anchor-grid button[title="右下"]').click()
    await page.waitForFunction(() => document.querySelector('.anchor-grid button[title="右下"]')?.classList.contains('active') === true)
    for (const label of ['宽度', '不透明度', '旋转']) {
      const present = await page.locator('.inspector-pane .slider-row', { hasText: label }).count()
      if (!present) failures.push(`slider ${label} missing`)
    }
    await setSlider(page, '宽度', 18)
    await setSlider(page, '不透明度', 0.88)
    await setSlider(page, '旋转', -12)
    await page.waitForTimeout(1500)
    await page.screenshot({ path: path.join(SHOTS, 'watermark-photo.png') })
    console.log('wm info', (await page.locator('.wm-info').innerText()).replace(/\n+/g, ' | '))

    const first = await exportCurrent(page)
    const firstOk = first.includes('导出完成')
    console.log('first complete', firstOk)
    if (!firstOk) failures.push(`first export not complete: ${first.replace(/\n+/g, ' | ')}`)

    // ---- phase 2: tile ----
    await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
    await page.waitForSelector('.inspector-pane .switch-row', { timeout: 10000 })
    const tileRow = page.locator('.inspector-pane .switch-row', { hasText: '平铺整个区域' })
    if (!(await tileRow.count())) failures.push('平铺整个区域 switch missing')
    await tileRow.click()
    await page.waitForFunction(
      () => {
        const row = [...document.querySelectorAll('.inspector-pane .switch-row')].find((r) => r.textContent?.includes('平铺整个区域'))
        return row?.querySelector('.switch')?.getAttribute('aria-checked') === 'true'
      },
      null,
      { timeout: 10000 }
    )
    // tile mode keeps the placement layer but hides the per-instance drag box + dots
    await page.waitForFunction(() => document.querySelectorAll('.wm-drag-box').length === 0, null, { timeout: 10000 })
    const tileLayer = await page.locator('.wm-placement').count()
    const tileDrag = await page.locator('.wm-drag-box').count()
    const tileDots = await page.locator('.wm-anchor-dot').count()
    console.log('tile placement layer:', { placement: tileLayer, dragBoxes: tileDrag, dots: tileDots })
    if (tileLayer !== 1 || tileDrag !== 0 || tileDots !== 0) failures.push(`tile layer state wrong: ${tileLayer}/${tileDrag}/${tileDots}`)

    const gapPresent = await page.locator('.inspector-pane .slider-row', { hasText: '间距' }).count()
    if (!gapPresent) failures.push('slider 间距 missing')
    await setSlider(page, '宽度', 9)
    await setSlider(page, '不透明度', 0.3)
    await setSlider(page, '间距', 5)
    await setSlider(page, '旋转', 0)
    await page.waitForTimeout(1200)
    await page.screenshot({ path: path.join(SHOTS, 'watermark-tile.png') })

    const second = await exportCurrent(page)
    const secondOk = second.includes('导出完成')
    console.log('second complete', secondOk)
    if (!secondOk) failures.push(`second export not complete: ${second.replace(/\n+/g, ' | ')}`)

    console.log('errors', errors)
    if (errors.length) failures.push(`${errors.length} page/console error(s): ${errors.slice(0, 3).join(' | ')}`)
  } finally {
    await app.close().catch(() => undefined)
  }

  if (failures.length) {
    console.error('watermark-e2e FAILED:', failures.join(' | '))
    process.exit(1)
  }
  console.log('watermark-e2e OK')
}

main().catch((e) => { console.error(e); process.exit(1) })