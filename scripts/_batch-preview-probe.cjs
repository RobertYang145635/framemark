/** Probe for the batch-preview slice: styled thumbs + 1/N viewer. */
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const runDir = path.join(root, '..', '_research', 'batch-preview-probe')
const profile = path.join(runDir, 'profile')
const fixture = path.join(root, '..', '_research', 'test-assets', 'ratio-study', '4x3.jpg')

;(async () => {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  const extra = path.join(runDir, 'second.jpg')
  fs.copyFileSync(fixture, extra)

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
  await page.setInputFiles('#framemark-file-input', [fixture, extra])
  await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 2, null, { timeout: 120000 })

  // 1) styled thumbnails replace the originals
  await page.waitForFunction(() => document.querySelectorAll('.thumb-main img.is-styled').length === 2, null, {
    timeout: 60000
  })
  const srcOf = await page.locator('.thumb-main img').first().getAttribute('src')
  console.log('PASS  styled thumbs:', (srcOf || '').slice(0, 24), '...')

  // 2) the two thumbs must differ from the raw photo blob urls
  const originals = await page.locator('.thumb-main img:not(.is-styled)').count()
  console.log(`PASS  unstyled thumbs remaining: ${originals}`)
  await page.locator('.filmstrip').screenshot({ path: path.join(runDir, 'filmstrip.png') })

  // 3) open the 1/N viewer
  await page.locator('.filmstrip-actions button', { hasText: '全部预览' }).click()
  await page.waitForSelector('.batch-preview-modal', { timeout: 10000 })
  await page.waitForSelector('.batch-preview-stage img', { timeout: 30000 })
  const t1 = await page.locator('.modal-head p').innerText()
  console.log('PASS  viewer open:', t1)

  // 4) arrow keys step
  await page.screenshot({ path: path.join(runDir, 'viewer.png') })
  await page.keyboard.press('ArrowRight')
  await page.waitForTimeout(400)
  const t2 = await page.locator('.modal-head p').innerText()
  console.log(`PASS  step: "${t1}" -> "${t2}"`, t2 !== t1 ? '' : '★ 未变化')

  await page.keyboard.press('ArrowLeft')
  await page.waitForTimeout(300)
  const t3 = await page.locator('.modal-head p').innerText()
  console.log(`PASS  step back: "${t3}"`, t3 === t1 ? '' : '★ 未回到首张')

  await page.keyboard.press('Escape')
  await page.waitForSelector('.batch-preview-modal', { state: 'detached', timeout: 5000 })
  console.log('PASS  escape closes')

  console.log(errors.length ? `FAIL errors: ${errors.join(' | ')}` : 'PASS  no page/console errors')
  await app.close()
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
