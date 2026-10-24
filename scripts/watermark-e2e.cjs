const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

async function setSlider(page, label, value) {
  return page.evaluate(({ label, value }) => {
    const row = [...document.querySelectorAll('.slider-row')].find((r) => {
      const t = r.querySelector('.slider-label')?.textContent?.trim() || ''
      return t === label || t.startsWith(label)
    })
    if (!row) throw new Error('slider not found: ' + label)
    const input = row.querySelector('input[type=range]')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(input, String(value))
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
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

async function main() {
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE', m.text()) })
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => { localStorage.clear(); indexedDB.deleteDatabase('framemark') })
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(1200)
  await page.setInputFiles('#framemark-file-input', [
    'D:/border&watermark/_research/test-assets/p1170883.jpg',
    'D:/border&watermark/_research/test-assets/p1181817.jpg',
    'D:/border&watermark/_research/test-assets/vertical.jpg'
  ])
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForTimeout(3000)
  await page.locator('.thumb-main').nth(1).click()
  await page.waitForTimeout(1800)
  await page.locator('.inspector-tabs button', { hasText: '水印' }).click()
  await page.waitForTimeout(500)
  await page.setInputFiles('input[type=file][accept="image/png,image/webp,image/jpeg"]', 'D:/border&watermark/_research/test-assets/wm-white.png')
  await page.waitForTimeout(1800)
  await page.locator('.anchor-grid button[title="右下"]').click()
  await setSlider(page, '宽度', 18)
  await setSlider(page, '不透明度', 0.88)
  await page.waitForTimeout(1500)
  await page.screenshot({ path: 'D:/border&watermark/_research/shots/desktop/watermark-photo.png' })
  console.log('wm info', (await page.locator('.wm-info').innerText()).replace(/\n+/g, ' | '))

  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(400)
  await page.locator('.export-actions button', { hasText: '导出当前' }).click()
  const first = await waitExport(page)
  console.log('first complete', first.includes('导出完成'))
  await page.locator('.export-card button', { hasText: '完成' }).click()
  await page.waitForTimeout(400)

  await page.locator('.inspector-tabs button', { hasText: '水印' }).click()
  await page.waitForTimeout(400)
  await page.locator('.slider-row', { hasText: '平铺整个区域' }).count().catch(() => 0)
  await page.locator('.switch-row', { hasText: '平铺整个区域' }).click().catch(async () => {
    await page.getByText('平铺整个区域', { exact: false }).first().click()
  })
  await setSlider(page, '宽度', 9)
  await setSlider(page, '不透明度', 0.3)
  await setSlider(page, '间距', 5)
  await page.waitForTimeout(1200)
  await page.screenshot({ path: 'D:/border&watermark/_research/shots/desktop/watermark-tile.png' })
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(300)
  await page.locator('.export-actions button', { hasText: '导出当前' }).click()
  const second = await waitExport(page)
  console.log('second complete', second.includes('导出完成'))
  console.log('errors', errors)
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
