import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { _electron as electron } from 'playwright-core'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const shotDir = path.resolve(root, '..', '_research', 'logos-real', 'scans')
fs.mkdirSync(shotDir, { recursive: true })

const brands = [
  ['Voigtländer', 'voigtlander'],
  ['BRONICA', 'bronica'],
  ['PRAKTICA', 'praktica'],
  ['EXAKTA', 'exakta'],
  ['MINOX', 'minox'],
  ['SINAR', 'sinar'],
  ['LINHOF', 'linhof'],
  ['LOMO', 'lomo'],
  ['SEAGULL', 'seagull'],
  ['HORSEMAN', 'horseman'],
  ['GRAFLEX', 'graflex'],
  ['RED', 'red'],
  ['Nothing', 'nothing'],
  ['nubia', 'nubia']
]

const app = await electron.launch({ executablePath: process.env.FM_EXE || require('electron'), args: process.env.FM_EXE ? [] : ['.'], cwd: root })
const page = await app.firstWindow()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))
await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

const openPicker = async () => {
  await page.locator('button', { hasText: '品牌库' }).first().click()
  await page.waitForSelector('.brand-grid', { timeout: 15000 })
  await page.waitForTimeout(400)
}

await openPicker()
const total = await page.locator('.brand-card').count()
const note = await page.locator('.modal-note').innerText()
await page.screenshot({ path: path.join(shotDir, 'brand-picker-all.png'), fullPage: false })

const results = []
for (const [label, key] of brands) {
  const q = page.locator('.brand-toolbar input')
  await q.fill(label)
  await page.waitForTimeout(350)
  const card = page.locator('.brand-card', { hasText: label }).first()
  const count = await page.locator('.brand-card').count()
  if (count === 0) {
    results.push({ key, label, ok: false, reason: 'no card' })
    continue
  }
  const img = card.locator('.brand-logo-box img')
  const loaded = await img.evaluate((el) => el.complete && el.naturalWidth > 0).catch(() => false)
  const src = await img.getAttribute('src')
  results.push({ key, label, ok: loaded, count, naturalWidth: await img.evaluate((el) => el.naturalWidth).catch(() => 0), src: src?.split('/').pop() })
}
await page.locator('.brand-toolbar input').fill('')
await page.waitForTimeout(300)
await page.locator('.brand-card', { hasText: 'Voigtländer' }).first().locator('.brand-tile').click()
await page.waitForTimeout(800)
const applied = await page.locator('.logo-current img').evaluate((el) => ({ ok: el.complete && el.naturalWidth > 0, w: el.naturalWidth, h: el.naturalHeight, name: el.alt })).catch(() => null)
await page.screenshot({ path: path.join(shotDir, 'brand-applied.png'), fullPage: false })

console.log(JSON.stringify({ total, note, results, applied, errors }, null, 2))
await app.close()
