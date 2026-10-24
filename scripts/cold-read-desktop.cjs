const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
const outDir = 'C:/Users/YHYTRUMP/Pictures/FrameMark'
const shots = 'D:/border&watermark/_research/shots/coldread'
fs.mkdirSync(shots, { recursive: true })
const list = () => (fs.existsSync(outDir) ? fs.readdirSync(outDir) : [])

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

async function design(page) {
  await page.waitForTimeout(800)
  return page.evaluate(() => JSON.parse(localStorage.getItem('framemark.lastDesign.v1') || 'null'))
}

async function waitDone(page, timeout = 180000) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  const t0 = Date.now()
  for (let i = 0; i < timeout / 500; i++) {
    await page.waitForTimeout(500)
    const t = await page.locator('.export-card').innerText().catch(() => '')
    if (t.includes('导出完成') || t.includes('失败')) return { text: t, sec: (Date.now() - t0) / 1000 }
  }
  throw new Error('export timeout')
}

;(async () => {
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: path.resolve('.') })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => { localStorage.clear(); indexedDB.deleteDatabase('framemark') })
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(700)
  console.log('empty-stage', await page.locator('.sample-banner').isVisible(), 'thumbs', await page.locator('.thumb').count())

  await page.setInputFiles('#framemark-file-input', [
    'D:/border&watermark/_research/test-assets/contax-t2.jpg',
    'D:/border&watermark/_research/test-assets/minolta-x700.jpg',
    'D:/border&watermark/_research/test-assets/honor-magic6.jpg',
    'D:/border&watermark/_research/test-assets/zeiss-zx1.jpg'
  ])
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForTimeout(2200)
  console.log('photos', await page.locator('.thumb').count())

  const autoResults = []
  for (let i = 0; i < 4; i++) {
    await page.locator('.thumb-main').nth(i).click()
    await page.waitForTimeout(500)
    await page.locator('.inspector-pane .section-head button', { hasText: '自动匹配' }).first().click()
    await page.waitForTimeout(900)
    const d = await design(page)
    autoResults.push({ i, name: d?.logo?.name, meta: await page.locator('.stage-meta').innerText().catch(() => '') })
  }
  console.log('auto logos', autoResults)

  await page.locator('.thumb-main').nth(0).click()
  await page.waitForTimeout(400)
  await page.locator('.inspector-pane button', { hasText: '品牌库' }).first().click()
  await page.waitForSelector('.brand-toolbar')
  await page.locator('.brand-toolbar input').fill('Contax')
  await page.waitForTimeout(400)
  console.log('contax search cards', await page.locator('.brand-card').count(), 'tones', await page.locator('.brand-card').first().locator('.brand-tone').count())
  await page.screenshot({ path: path.join(shots, 'brand-picker-contax.png') })
  await page.locator('.brand-card .brand-tile').first().click()
  await page.waitForTimeout(700)
  console.log('contax chosen', (await design(page))?.logo?.name)

  await page.locator('.inspector-pane button', { hasText: '品牌库' }).first().click()
  await page.waitForTimeout(400)
  await page.locator('.brand-toolbar input').fill('Voigtländer')
  await page.waitForTimeout(300)
  console.log('voigt cards', await page.locator('.brand-card').count())
  await page.locator('.brand-card .brand-tone').filter({ hasText: '白' }).first().click()
  await page.waitForTimeout(700)
  console.log('voigt chosen', (await design(page))?.logo?.name)

  await page.locator('.sidebar .tpl-card').filter({ hasText: '纯白 01' }).first().click()
  await page.waitForTimeout(900)
  const before = await page.evaluate(() => { const c = document.querySelector('.canvas-frame canvas'); return { w: c.width, h: c.height } })
  await setSlider(page, '边距', 15)
  await page.waitForTimeout(700)
  const after = await page.evaluate(() => { const c = document.querySelector('.canvas-frame canvas'); return { w: c.width, h: c.height } })
  console.log('margin canvas', before, '->', after, 'params', { margin: (await design(page))?.params?.marginPct })
  await setSlider(page, '圆角', 10)
  await setSlider(page, '阴影', 6)
  await page.waitForTimeout(700)
  await page.screenshot({ path: path.join(shots, 'border-controls.png') })
  console.log('radius/shadow params', { radius: (await design(page))?.params?.radiusPct, shadow: (await design(page))?.params?.shadow })

  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForSelector('.preset-save-box input')
  await page.locator('.preset-save-box input').fill('冷读测试预设')
  await page.locator('.preset-save-box button', { hasText: '保存当前设计' }).click()
  await page.waitForTimeout(900)
  await page.locator('.topbar-actions button', { hasText: '预设' }).click()
  await page.waitForTimeout(300)
  console.log('preset rows', await page.locator('.preset-row').count())
  await page.locator('.modal button', { hasText: '完成' }).click()

  const beforeFiles = list()
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(400)
  await page.locator('.export-actions button', { hasText: '导出全部' }).click()
  const exp = await waitDone(page)
  console.log('batch', exp.sec.toFixed(1) + 's', exp.text.replace(/\n+/g, ' | '))
  const newFiles = list().filter((f) => !beforeFiles.includes(f))
  console.log('new files', newFiles)
  await page.screenshot({ path: path.join(shots, 'export-complete.png') })

  await app.close()
  const app2 = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: path.resolve('.') })
  const page2 = await app2.firstWindow()
  await page2.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page2.waitForTimeout(1200)
  console.log('restart design', await page2.evaluate(() => { const d = JSON.parse(localStorage.getItem('framemark.lastDesign.v1') || 'null'); return d ? { variantId: d.variantId, margin: d.params?.marginPct, logo: d.logo?.name, theme: d.params?.theme } : null }))
  await app2.close()
  console.log('page errors', errors)
})().catch((e) => { console.error(e); process.exit(1) })
