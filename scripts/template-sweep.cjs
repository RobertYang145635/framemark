const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

async function main() {
  const project = path.resolve(__dirname, '..')
  const outDir = 'D:/border&watermark/_research/contact'
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.evaluate(() => { localStorage.clear(); indexedDB.deleteDatabase('framemark') })
  await page.reload()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(800)
  await page.setInputFiles('#framemark-file-input', 'D:/border&watermark/_research/test-assets/p1170883.jpg')
  await page.waitForSelector('.thumb', { timeout: 120000 })
  await page.waitForTimeout(2400)
  const cards = page.locator('.sidebar .tpl-card')
  const total = await cards.count()
  console.log('cards', total)
  const started = Date.now()
  const rows = []
  for (let i = 0; i < total; i++) {
    const card = cards.nth(i)
    const name = await card.locator('.tpl-meta strong').innerText().catch(() => `#${i}`)
    const cat = await card.locator('.tpl-meta em').innerText().catch(() => '')
    let err = ''
    let data = ''
    try {
      await card.evaluate((el) => el.click())
      await page.waitForTimeout(300)
      err = (await page.locator('.render-error').count()) ? await page.locator('.render-error').innerText() : ''
      data = await page.locator('.canvas-frame canvas').evaluate((c) => c.toDataURL('image/jpeg', 0.82)).catch(() => '')
      if (data) fs.writeFileSync(path.join(outDir, `${String(i).padStart(2, '0')}.jpg`), Buffer.from(data.split(',')[1], 'base64'))
    } catch (e) {
      err = e.message
    }
    rows.push({ i, name, cat, err, data: Boolean(data) })
    if (i % 6 === 0) console.log('progress', i, ((Date.now()-started)/1000).toFixed(1)+'s')
    if (err) console.log('ERR', i, name, err)
  }
  fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ errors, rows }, null, 2))
  console.log('rendered', rows.filter((r) => r.data).length, 'of', total, 'pageErrors', errors.length)

  const tileW = 360
  const tileH = 280
  const cols = 6
  const rowsN = Math.ceil(total / cols)
  const sheet = sharp({ create: { width: cols * tileW, height: rowsN * tileH, channels: 3, background: '#111113' } })
  const comps = []
  for (let i = 0; i < total; i++) {
    const file = path.join(outDir, `${String(i).padStart(2, '0')}.jpg`)
    if (fs.existsSync(file)) {
      const buf = await sharp(file).resize({ width: tileW - 16, height: tileH - 58, fit: 'inside', background: '#111113' }).jpeg({ quality: 85 }).toBuffer()
      const meta = await sharp(buf).metadata()
      comps.push({ input: buf, left: (i % cols) * tileW + Math.round((tileW - meta.width) / 2), top: Math.floor(i / cols) * tileH + 8 })
    }
    const label = `${String(i + 1).padStart(2, '0')} ${rows[i].name}`
    const svg = Buffer.from(`<svg width="${tileW}" height="44"><text x="10" y="28" font-family="Microsoft YaHei, sans-serif" font-size="15" fill="#e8e2d6">${label.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</text><text x="${tileW - 10}" y="28" text-anchor="end" font-family="sans-serif" font-size="11" fill="#8a857c">${rows[i].cat}</text></svg>`)
    comps.push({ input: svg, left: (i % cols) * tileW, top: Math.floor(i / cols) * tileH + tileH - 44 })
  }
  await sheet.composite(comps).jpeg({ quality: 88 }).toFile(path.join(outDir, 'all-templates.jpg'))
  fs.writeFileSync(path.join(outDir, 'errors.txt'), rows.filter((r) => r.err).map((r) => `${r.i} ${r.name}: ${r.err}`).join('\n') || 'none')
  console.log('sheet ok')
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
