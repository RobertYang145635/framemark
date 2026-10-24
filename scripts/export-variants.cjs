const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

// Usage: node scripts/export-variants.cjs "variant1|variant2" fixture.jpg outDir
const names = (process.argv[2] || '哈苏 · 白框').split('|').map((s) => s.trim()).filter(Boolean)
const fixture = process.argv[3] || 'D:/border&watermark/_research/test-assets/ratio-study/2x3.jpg'
const outDir = process.argv[4] || 'D:/border&watermark/_research/ours-variant-check'

async function main() {
  fs.mkdirSync(outDir, { recursive: true })
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`) })
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => { localStorage.clear(); indexedDB.deleteDatabase('framemark') })
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await app.evaluate(async ({ dialog }, dir) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
  }, outDir)
  await page.setInputFiles('#framemark-file-input', [fixture])
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForTimeout(1200)

  for (const name of names) {
    const card = page.locator('.tpl-card', { hasText: name }).first()
    await card.scrollIntoViewIfNeeded()
    await card.click()
    await page.waitForTimeout(900)
    await page.screenshot({ path: path.join(outDir, `ui-${name.replace(/[\\/:*?"<>| ]/g, '_')}.png`) })
    await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
    await page.waitForTimeout(250)
    const png = page.locator('.export-panel .segmented button, .inspector-pane .segmented button', { hasText: 'PNG' }).first()
    if (await png.count()) await png.click()
    const change = page.locator('.inspector-pane button', { hasText: '更改' }).first()
    if (await change.count()) { await change.click(); await page.waitForTimeout(600) }
    await page.locator('.export-actions button', { hasText: '导出全部' }).click()
    await page.waitForSelector('.export-card', { timeout: 30000 })
    await page.waitForFunction(() => {
      const h = document.querySelector('.export-card h3')
      return h && !h.textContent?.includes('正在导出')
    }, { timeout: 240000 })
    console.log(name, '->', (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | '))
    const done = page.locator('.export-card button', { hasText: '完成' }).first()
    if (await done.count()) await done.click()
    await page.waitForTimeout(400)
  }
  console.log('errors:', errors.slice(0, 10))
  console.log('files:', fs.readdirSync(outDir).filter((f) => !f.startsWith('ui-')).map((f) => ({ f, bytes: fs.statSync(path.join(outDir, f)).size })))
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
