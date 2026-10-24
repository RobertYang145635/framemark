const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

const shots = 'D:/border&watermark/_research/shots/verify2'
const outDir = 'C:/Users/YHYTRUMP/Pictures/FrameMark'
fs.mkdirSync(shots, { recursive: true })
const list = () => (fs.existsSync(outDir) ? fs.readdirSync(outDir) : [])

async function design(page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem('framemark.lastDesign.v1') || 'null'))
}
async function clickTemplate(page, name) {
  const card = page.locator('.sidebar .tpl-card').filter({ hasText: name }).first()
  await card.scrollIntoViewIfNeeded()
  await card.click()
  await page.waitForTimeout(900)
}
async function clickTone(page, label) {
  await page.locator('.theme-swatch').filter({ hasText: label }).first().click()
  await page.waitForTimeout(700)
}
async function waitDone(page, timeout = 120000) {
  await page.waitForSelector('.export-card', { timeout: 30000 })
  const t0 = Date.now()
  for (let i = 0; i < timeout / 500; i++) {
    await page.waitForTimeout(500)
    const t = await page.locator('.export-card').innerText().catch(() => '')
    if (t.includes('导出完成') || t.includes('失败')) return { text: t, sec: (Date.now() - t0) / 1000 }
  }
  throw new Error('export timeout')
}
async function exportOne(page) {
  const before = list()
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(400)
  await page.locator('.export-actions button', { hasText: '导出当前' }).click()
  const r = await waitDone(page)
  const added = list().filter((f) => !before.includes(f))
  await page.locator('.export-card button', { hasText: '完成' }).click().catch(() => page.keyboard.press('Escape'))
  await page.locator('.inspector-tabs button', { hasText: '模板' }).click()
  await page.waitForTimeout(400)
  return { ...r, added }
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

  await page.setInputFiles('#framemark-file-input', [
    'D:/border&watermark/_research/test-assets/contax-t2.jpg',
    'D:/border&watermark/_research/test-assets/minolta-x700.jpg',
    'D:/border&watermark/_research/test-assets/zeiss-zx1.jpg',
    'D:/border&watermark/_research/test-assets/honor-magic6.jpg'
  ])
  await page.waitForSelector('.thumb', { timeout: 60000 })
  await page.waitForTimeout(1800)

  // Contax / wm-classic (white footer)
  await page.locator('.thumb-main').nth(0).click()
  await page.waitForTimeout(400)
  await clickTemplate(page, '经典双行')
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(900)
  let d = await design(page)
  console.log('contax wm-classic logo', d?.logo?.name, 'theme', d?.params?.theme)
  await page.screenshot({ path: path.join(shots, '01-contax-wm-classic.png') })
  const jpg = await exportOne(page)
  console.log('export jpg', jpg.sec.toFixed(1) + 's', jpg.added)
  if (jpg.added[0]) {
    const p = path.join(outDir, jpg.added[0])
    const meta = await sharp(p).metadata()
    const ex = await require('exifr').parse(p, { pick: ['Make', 'Model', 'LensModel', 'Orientation', 'ExifImageWidth', 'ExifImageHeight', 'DateTimeOriginal'] })
    console.log('jpg meta', meta.width, meta.height, meta.format, meta.space, ex)
    await sharp(p).extract({ left: 0, top: meta.height - Math.round(meta.height * 0.09), width: meta.width, height: Math.round(meta.height * 0.09) }).toFile(path.join(shots, '02-contax-footer-crop.png'))
  }

  // Dark theme via wm-black
  await clickTemplate(page, '黑底日期')
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(900)
  d = await design(page)
  console.log('contax wm-black logo', d?.logo?.name, 'theme', d?.params?.theme)
  await page.screenshot({ path: path.join(shots, '03-contax-wm-black.png') })

  // Honor dark auto
  await page.locator('.thumb-main').nth(3).click()
  await page.waitForTimeout(500)
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(900)
  d = await design(page)
  console.log('honor wm-black logo', d?.logo?.name)
  await page.screenshot({ path: path.join(shots, '04-honor-wm-black.png') })

  // Minolta manual brand picker: main card black, tone white on dark
  await page.locator('.thumb-main').nth(1).click()
  await page.waitForTimeout(400)
  await page.locator('.inspector-pane button', { hasText: '品牌库' }).first().click()
  await page.waitForSelector('.brand-toolbar')
  await page.locator('.brand-toolbar input').fill('Minolta')
  await page.waitForTimeout(350)
  console.log('minolta cards', await page.locator('.brand-card').count())
  await page.screenshot({ path: path.join(shots, '05-minolta-picker.png') })
  await page.locator('.brand-card .brand-tile').first().click()
  await page.waitForTimeout(700)
  d = await design(page)
  console.log('minolta main card logo', d?.logo?.name)
  await page.locator('.inspector-pane button', { hasText: '品牌库' }).first().click()
  await page.waitForTimeout(300)
  await page.locator('.brand-toolbar input').fill('Minolta')
  await page.waitForTimeout(300)
  const tones = page.locator('.brand-card').first().locator('.brand-tone')
  console.log('minolta tones', await tones.count(), await tones.allInnerTexts())
  const whiteTone = tones.filter({ hasText: '白' }).first()
  if (await whiteTone.count()) {
    await whiteTone.click()
    await page.waitForTimeout(700)
    d = await design(page)
    console.log('minolta white tone logo', d?.logo?.name)
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(shots, '06-minolta-white-on-dark.png') })

  // Zeiss auto
  await page.locator('.thumb-main').nth(2).click()
  await page.waitForTimeout(400)
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(900)
  d = await design(page)
  console.log('zeiss logo (dark theme)', d?.logo?.name)

  // Border controls on white-1
  await page.locator('.thumb-main').nth(0).click()
  await page.waitForTimeout(400)
  await clickTemplate(page, '纯白 01')
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(800)
  const dims = async () => page.evaluate(() => { const c = document.querySelector('.canvas-frame canvas'); return { w: c.width, h: c.height } })
  async function setSlider(label, value) {
    await page.evaluate(({ label, value }) => {
      const row = [...document.querySelectorAll('.slider-row')].find((r) => (r.querySelector('.slider-label')?.textContent || '').trim().startsWith(label))
      if (!row) throw new Error('no slider ' + label)
      const input = row.querySelector('input[type=range]')
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, String(value))
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    }, { label, value })
    await page.waitForTimeout(650)
  }
  console.log('white-1 base', await dims())
  await page.screenshot({ path: path.join(shots, '07-minimal-base.png') })
  await setSlider('边距', 15)
  console.log('margin 15', await dims())
  await page.screenshot({ path: path.join(shots, '08-minimal-margin15.png') })
  await setSlider('边距', 0)
  console.log('margin 0', await dims())
  await setSlider('边距', 5)
  await setSlider('圆角', 10)
  await page.screenshot({ path: path.join(shots, '09-minimal-radius10.png') })
  await setSlider('圆角', 0)
  await setSlider('阴影', 10)
  await page.screenshot({ path: path.join(shots, '10-minimal-shadow10.png') })
  await setSlider('阴影', 0)
  await clickTone(page, '纯黑')
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(700)
  d = await design(page)
  console.log('minimal ink logo', d?.logo?.name, 'theme', d?.params?.theme)
  await page.screenshot({ path: path.join(shots, '11-minimal-ink.png') })

  // Logo position / size on brandFooter
  await clickTemplate(page, '经典双行')
  await clickTone(page, '纯白')
  await page.waitForTimeout(300)
  await page.locator('.inspector-pane button', { hasText: '自动匹配' }).first().click()
  await page.waitForTimeout(700)
  for (const pos of ['左', '中', '右']) {
    await page.locator('.field.compact').filter({ hasText: 'Logo 位置' }).locator('button', { hasText: pos }).first().click()
    await page.waitForTimeout(500)
    await page.screenshot({ path: path.join(shots, `12-footer-logo-${pos === '左' ? 'left' : pos === '右' ? 'right' : 'center'}.png`) })
  }
  await setSlider('Logo 大小', 0)
  await page.screenshot({ path: path.join(shots, '13-footer-logo-min.png') })
  await setSlider('Logo 大小', 10)
  await page.screenshot({ path: path.join(shots, '14-footer-logo-max.png') })
  await setSlider('Logo 大小', 5)

  // Ratio crop
  await page.locator('.field.compact').filter({ hasText: '画幅' }).locator('button', { hasText: '1:1' }).first().click().catch(async () => {
    await page.locator('.inspector-pane button', { hasText: '1:1' }).first().click()
  })
  await page.waitForTimeout(900)
  console.log('ratio 1:1', await dims(), 'warn', await page.locator('.warn-note').count())
  await page.screenshot({ path: path.join(shots, '15-ratio-1-1.png') })
  await page.locator('.inspector-pane button', { hasText: '原始' }).first().click()
  await page.waitForTimeout(600)

  // PNG export with current design (footer + logo + black? ensure white)
  await clickTone(page, '纯白')
  await page.locator('.field.compact').filter({ hasText: 'Logo 位置' }).locator('button', { hasText: '中' }).first().click()
  await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
  await page.waitForTimeout(300)
  await page.locator('.inspector-pane .segmented button', { hasText: 'PNG' }).first().click()
  await page.waitForTimeout(500)
  const png = await exportOne(page)
  console.log('export png', png.sec.toFixed(1) + 's', png.added)
  if (png.added[0]) {
    const p = path.join(outDir, png.added[0])
    const meta = await sharp(p).metadata()
    console.log('png meta', meta.width, meta.height, meta.format, meta.space, meta.hasAlpha)
  }

  console.log('page errors', errors)
  await app.close()
})().catch((e) => { console.error(e); process.exit(1) })
