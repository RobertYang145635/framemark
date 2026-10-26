const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
// #57 PATH-LITERALS: test assets are resolved from a single entry point, not hardcoded root paths
const { resolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

const outRoot = 'D:/border&watermark/_research/exports'
const shots = 'D:/border&watermark/_research/shots'

async function waitExport(page, label) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(
    () => {
      const h = document.querySelector('.export-card h3')
      return h && !h.textContent?.includes('正在导出')
    },
    { timeout: 240000 }
  )
  const status = await page.locator('.export-card').innerText()
  console.log(`[${label}]`, status.replace(/\n+/g, ' | '))
  await page.screenshot({ path: path.join(shots, `06-export-${label}.png`) })
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  await page.waitForTimeout(300)
}

async function exportCurrent(page, label) {
  await page.locator('.export-actions button', { hasText: '导出当前' }).click()
  await waitExport(page, label)
}

async function setFormat(page, label) {
  await page.locator('.export-panel .segmented button, .inspector-pane .segmented button', { hasText: label }).first().click()
  await page.waitForTimeout(200)
}

async function main() {
  fs.rmSync(outRoot, { recursive: true, force: true })
  fs.mkdirSync(outRoot, { recursive: true })
  fs.mkdirSync(shots, { recursive: true })
  const project = path.resolve(__dirname, '..')
  const electronPath = require('electron')
  const app = await electron.launch({ executablePath: electronPath, args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(800)
  await app.evaluate(async ({ dialog }, dir) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
  }, outRoot)

  const inputs = [
    resolveAsset('P1170883.jpg'),
    resolveAsset('P1181817.jpg'),
    resolveAsset('PYHY5599-已增强-降噪.jpg')
  ]
  await page.setInputFiles('#framemark-file-input', inputs)
  await page.waitForSelector('.thumb', { timeout: 60000 })
  await page.waitForTimeout(2500)

  // set classic double-line template
  const classic = page.locator('.tpl-card', { hasText: '经典双行' }).first()
  if (await classic.count()) {
    await classic.click()
    await page.waitForTimeout(800)
  }

  // watermark
  await page.locator('.inspector-tabs button', { hasText: '水印' }).click()
  await page.locator('.inspector-pane input[type=file]').setInputFiles(resolveAsset('水印_黑色.png'))
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(shots, '05-watermark.png') })
  console.log('watermark stage:', (await page.locator('.stage-title').innerText()).replace(/\n+/g, ' | '))
  console.log('canvas:', await page.evaluate(() => {
    const c = document.querySelector('.canvas-frame canvas')
    return c ? { w: c.width, h: c.height } : null
  }))

  // export tab
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(300)
  const change = page.locator('.inspector-pane button', { hasText: '更改' }).first()
  if (await change.count()) {
    await change.click()
    await page.waitForTimeout(700)
  }
  console.log('output dir:', await page.locator('.inspector-pane').innerText().catch(() => ''))
  await page.screenshot({ path: path.join(shots, '07-export-panel.png') })

  await exportCurrent(page, 'jpeg')
  await setFormat(page, 'PNG')
  await exportCurrent(page, 'png')
  await setFormat(page, 'WebP')
  await exportCurrent(page, 'webp')
  await setFormat(page, 'TIFF')
  await exportCurrent(page, 'tiff')
  await setFormat(page, 'JPG')
  await page.locator('.export-actions button', { hasText: '导出全部' }).click()
  await waitExport(page, 'batch')

  console.log('errors:', errors.slice(0, 20))
  console.log('files:', fs.readdirSync(outRoot).map((f) => ({ f, bytes: fs.statSync(path.join(outRoot, f)).size })))
  await app.close()
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
