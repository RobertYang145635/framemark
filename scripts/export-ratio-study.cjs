const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const outDir = process.argv[3] || 'D:/border&watermark/_research/ours-ratio-study'
const fixtures = [
  'D:/border&watermark/_research/test-assets/ratio-study/3x2.jpg',
  'D:/border&watermark/_research/test-assets/ratio-study/16x9.jpg',
  'D:/border&watermark/_research/test-assets/ratio-study/1x1.jpg',
  'D:/border&watermark/_research/test-assets/ratio-study/4x5.jpg',
  'D:/border&watermark/_research/test-assets/ratio-study/2x3.jpg'
]

async function main() {
  fs.rmSync(outDir, { recursive: true, force: true })
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
  await page.setInputFiles('#framemark-file-input', fixtures)
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForTimeout(2000)
  const variantName = process.argv[2] || '简约单行'; const card = page.locator('.tpl-card', { hasText: variantName }).first()
  await card.click()
  await page.waitForTimeout(1200)
  const skipAuto = /哈苏|蔡司|徕卡|小米/.test(variantName)
  const auto = page.locator('button', { hasText: '自动匹配' }).first()
  if (!skipAuto && await auto.count()) {
    await auto.click()
    await page.waitForTimeout(1500)
  }
  await page.screenshot({ path: 'D:/border&watermark/_research/shots/ratio-simple-editor.png' })
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(300)
  const png = page.locator('.export-panel .segmented button, .inspector-pane .segmented button', { hasText: 'PNG' }).first()
  if (await png.count()) await png.click()
  const change = page.locator('.inspector-pane button', { hasText: '更改' }).first()
  if (await change.count()) { await change.click(); await page.waitForTimeout(700) }
  await page.locator('.export-actions button', { hasText: '导出全部' }).click()
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(() => {
    const h = document.querySelector('.export-card h3')
    return h && !h.textContent?.includes('正在导出')
  }, { timeout: 240000 })
  console.log((await page.locator('.export-card').innerText()).replace(/\n+/g, ' | '))
  await page.screenshot({ path: 'D:/border&watermark/_research/shots/ratio-simple-export.png' })
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  console.log('errors:', errors.slice(0, 10))
  console.log('files:', fs.readdirSync(outDir).map((f) => ({ f, bytes: fs.statSync(path.join(outDir, f)).size })))
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
