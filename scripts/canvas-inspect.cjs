const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
// #57 PATH-LITERALS: test assets are resolved from a single entry point, not hardcoded root paths
const { resolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

async function main() {
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => localStorage.clear())
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.setInputFiles('#framemark-file-input', [resolveAsset('P1170883.jpg')])
  await page.waitForSelector('.thumb', { timeout: 60000 })
  await page.waitForTimeout(1500)
  await page.locator('.tpl-card', { hasText: '电影 01' }).first().click()
  await page.waitForTimeout(1500)
  const info = await page.evaluate(() => {
    const c = document.querySelector('.canvas-frame canvas')
    if (!c) return null
    return { w: c.width, h: c.height, data: c.toDataURL('image/png') }
  })
  if (!info) throw new Error('no canvas')
  fs.writeFileSync('D:/border&watermark/_research/shots/canvas-movie.png', Buffer.from(info.data.split(',')[1], 'base64'))
  console.log('canvas', info.w, info.h)
  console.log('title val', await page.locator('input').nth(-1).inputValue().catch(() => ''))
  console.log('texts', await page.locator('input').evaluateAll((els) => els.map((e) => e.value)))
  console.log('canvas bounding', await page.locator('.canvas-frame canvas').boundingBox())
  await app.close()
}
main().catch((e) => {
  console.error(e)
  process.exit(1)
})
