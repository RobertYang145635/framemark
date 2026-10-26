const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
// #57 PATH-LITERALS: test assets are resolved from a single entry point, not hardcoded root paths
const { resolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

async function main() {
  const project = path.resolve(__dirname, '..')
  const shots = 'D:/border&watermark/_research/shots'
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => {
    localStorage.clear()
    indexedDB.deleteDatabase('framemark')
  })
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(1000)
  await page.screenshot({ path: path.join(shots, '10-clean-initial.png') })

  await page.setInputFiles('#framemark-file-input', [
    resolveAsset('P1170883.jpg'),
    resolveAsset('P1181817.jpg'),
    resolveAsset('PYHY5599-已增强-降噪.jpg')
  ])
  await page.waitForSelector('.thumb', { timeout: 60000 })
  await page.waitForTimeout(2500)
  await page.screenshot({ path: path.join(shots, '11-clean-classic.png') })
  console.log('classic', (await page.locator('.stage-title').innerText()).replace(/\n+/g, ' | '))
  console.log('logo?', await page.evaluate(() => Boolean(document.querySelector('.brand-current img, .logo-current img, .inspector-pane img'))))

  const movie = page.locator('.tpl-card', { hasText: '电影 01' }).first()
  await movie.click()
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(shots, '12-clean-movie.png') })
  console.log('movie', (await page.locator('.stage-title').innerText()).replace(/\n+/g, ' | '))
  console.log('movie canvas', await page.evaluate(() => {
    const c = document.querySelector('.canvas-frame canvas')
    return c ? { w: c.width, h: c.height } : null
  }))

  console.log('errors', errors)
  await app.close()
}
main().catch((e) => {
  console.error(e)
  process.exit(1)
})
