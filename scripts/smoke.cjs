const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

async function main() {
  const project = path.resolve(__dirname, '..')
  const electronPath = require('electron')
  const outDir = path.resolve('D:/border&watermark/_research/shots')
  fs.mkdirSync(outDir, { recursive: true })
  const app = await electron.launch({
    executablePath: electronPath,
    args: ['.'],
    cwd: project
  })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(1500)
  console.log('framemark api:', await page.evaluate(() => Boolean(window.framemark)))
  await page.screenshot({ path: path.join(outDir, '01-initial.png') })
  const inputs = [
    'D:/border&watermark/P1170883.jpg',
    'D:/border&watermark/P1181817.jpg',
    'D:/border&watermark/PYHY5599-已增强-降噪.jpg'
  ].filter((p) => fs.existsSync(p))
  if (inputs.length) {
    await page.setInputFiles('#framemark-file-input', inputs)
    await page.waitForSelector('.thumb', { timeout: 30000 })
    await page.waitForTimeout(3000)
    await page.screenshot({ path: path.join(outDir, '02-with-photos.png') })
    const thumbs = await page.locator('.thumb').count()
    console.log('thumbnails:', thumbs)
    console.log('stage text:', await page.locator('.stage-title').innerText().catch(() => ''))
    console.log('canvas size:', await page.evaluate(() => {
      const c = document.querySelector('.canvas-frame canvas')
      return c ? { w: c.width, h: c.height } : null
    }))
    // try a couple of templates
    for (const name of ['白边 01', '色卡 01', '液态玻璃', '电影 01']) {
      const card = page.locator('.tpl-card', { hasText: name }).first()
      if (await card.count()) {
        await card.click()
        await page.waitForTimeout(900)
      }
    }
    await page.screenshot({ path: path.join(outDir, '03-template.png') })
    // open watermark tab and export tab for visual check
    await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
    await page.waitForTimeout(400)
    await page.screenshot({ path: path.join(outDir, '04-export-tab.png') })
  }
  console.log('errors:', errors.slice(0, 20))
  await app.close()
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
