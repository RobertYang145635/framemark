const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const project = path.resolve(__dirname, '..')
const outDir = process.argv[2] || 'D:/border&watermark/_research/ratio-matrix'
const names = (process.argv[3] || [
  '纯白 01', '纯白 02', '纯白 03', '米白 04',
  '卡片 01', '卡片 02', '卡片 03', '卡片 04',
  '人物 01', '人物 02', '人物 03', '人物 04', '人物 05'
].join('|')).split('|').map((s) => s.trim()).filter(Boolean)
const fixtures = ['3x2', '16x9', '1x1', '4x5', '2x3'].map((r) =>
  `D:/border&watermark/_research/test-assets/ratio-study/${r}.jpg`
)

async function waitExport(page) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(() => {
    const h = document.querySelector('.export-card h3')
    return h && !h.textContent?.includes('正在导出')
  }, null, { timeout: 240000 })
  const text = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  await page.waitForTimeout(300)
  return text
}

async function pickVariant(page, name) {
  const category = /^纯白|^米白/.test(name) ? '留白' : /^卡片/.test(name) ? '卡片' : '人物'
  await page.locator('.inspector-tabs button', { hasText: '模板' }).click()
  await page.locator('.current-template-actions button', { hasText: '更换' }).click()
  const modal = page.locator('.template-modal')
  await modal.waitFor({ timeout: 20000 })
  await modal.locator('.gallery-chips button', { hasText: category }).click()
  await page.waitForTimeout(200)
  const card = modal.locator('.tpl-card', { hasText: name }).first()
  await card.waitFor({ timeout: 20000 })
  await card.click()
  await page.waitForTimeout(800)
}

async function main() {
  fs.rmSync(outDir, { recursive: true, force: true })
  fs.mkdirSync(outDir, { recursive: true })
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
  await page.waitForTimeout(1500)

  const results = []
  for (const name of names) {
    await pickVariant(page, name)
    await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
    await page.waitForTimeout(300)
    const png = page.locator('.export-panel .segmented button, .inspector-pane .segmented button', { hasText: 'PNG' }).first()
    if (await png.count()) await png.click()
    const change = page.locator('.inspector-pane button', { hasText: '更改' }).first()
    if (await change.count()) { await change.click(); await page.waitForTimeout(500) }
    await page.locator('.export-actions button', { hasText: '导出全部' }).click()
    const status = await waitExport(page)
    results.push({ name, status })
    console.log(name, '->', status)
  }
  console.log('errors', errors.slice(0, 10))
  const files = fs.readdirSync(outDir).filter((f) => /\.(png|jpg|webp|tif|tiff)$/i.test(f))
  console.log('files', files.length)
  await app.close()
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
