const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
// #57 PATH-LITERALS: test assets are resolved from a single entry point, not hardcoded root paths
const { resolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

async function main() {
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message))
  page.on('console', (m) => console.log('CONSOLE', m.type(), m.text()))
  page.on('crash', () => console.log('PAGE CRASHED'))
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.setInputFiles('#framemark-file-input', [resolveAsset('P1170883.jpg')])
  await page.waitForSelector('.thumb', { timeout: 60000 })
  await page.waitForTimeout(2000)
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(300)
  await page.locator('.inspector-pane .segmented button', { hasText: 'WebP' }).click()
  await page.waitForTimeout(400)
  const state = await page.evaluate(() => ({
    btns: Array.from(document.querySelectorAll('.inspector-pane .segmented button')).map((b) => ({ t: b.textContent, c: b.className })),
    exportDisabled: Boolean(document.querySelector('.export-actions .btn-primary')?.disabled),
    job: document.querySelector('.export-card')?.textContent ?? null
  }))
  console.log('state', JSON.stringify(state))
  await page.locator('.export-actions button', { hasText: '导出当前' }).click()
  console.log('clicked export')
  for (let i = 0; i < 60; i++) {
    await page.waitForTimeout(1000)
    const exists = await page.locator('.export-card').count()
    const text = exists ? (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ') : ''
    if (exists || i === 5) console.log(i, 'card', exists, text)
    if (exists && !text.includes('正在导出')) break
    if (i === 45) {
      await page.screenshot({ path: 'D:/border&watermark/_research/shots/debug-webp.png' })
      console.log('timeout; page visible:', await page.evaluate(() => document.visibilityState))
    }
  }
  console.log('files', fs.readdirSync('D:/border&watermark/_research/exports'))
  await page.screenshot({ path: 'D:/border&watermark/_research/shots/debug-webp-final.png' })
  await app.close()
}
main().catch((e) => {
  console.error('FATAL', e)
  process.exit(1)
})
