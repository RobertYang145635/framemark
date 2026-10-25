import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { _electron as electron } from 'playwright-core'

const require = createRequire(import.meta.url)
const root = path.resolve(import.meta.dirname, '..')
const outDir = path.resolve(root, '..', '_research', 'e2e-export')
const fixture = path.resolve(root, '..', '_research', 'test-assets', 'ratio-study', '4x3.jpg')

fs.rmSync(outDir, { recursive: true, force: true })
fs.mkdirSync(outDir, { recursive: true })

const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: root })
const page = await app.firstWindow()
const errors = []
page.on('pageerror', (e) => errors.push(e.message))
await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

const total = await page.locator('.tpl-card').count()
assert.equal(total, 164, `expected ===164 cards, got ${total}`)
assert.equal(await page.locator('.tpl-card canvas').count(), 0, 'cards must use static previews')

await page.locator('.gallery-chips button', { hasText: '系统相机' }).click()
await page.waitForTimeout(400)
assert.equal(await page.locator('.tpl-card').count(), 96, 'system camera card count')
assert.equal(await page.locator('.blocked-card').count(), 4, 'blocked group count')

await page.locator('.tpl-card', { hasText: '经典 · 17' }).first().click()
await page.waitForTimeout(500)
assert.equal(await page.locator('.theme-swatch').count(), 2, 'white/black themes')
await page.locator('.theme-swatch', { hasText: '黑底' }).click()
await page.waitForTimeout(300)
assert.equal(await page.locator('.theme-swatch.active em').innerText(), '黑底')

await page.locator('.gallery-subchips button', { hasText: 'OPPO' }).click()
await page.waitForTimeout(300)
const polaroid = page.locator('.tpl-card', { hasText: 'Polaroid · PLD' }).first()
await polaroid.scrollIntoViewIfNeeded()
await polaroid.click()
await page.waitForTimeout(500)

await page.setInputFiles('#framemark-file-input', fixture)
await page.waitForSelector('.thumb', { timeout: 120000 })
await page.waitForTimeout(800)
await app.evaluate(async ({ dialog }, dir) => {
  dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
}, outDir)
await page.locator('.inspector-tabs button', { hasText: '导出' }).click()
await page.waitForTimeout(300)
const png = page.locator('.export-panel .segmented button, .inspector-pane .segmented button', { hasText: 'PNG' }).first()
if (await png.count()) await png.click()
const change = page.locator('.inspector-pane button', { hasText: '更改' }).first()
if (await change.count()) {
  await change.click()
  await page.waitForTimeout(500)
}
await page.locator('.export-actions button', { hasText: '导出全部' }).click()
await page.waitForSelector('.export-card', { timeout: 30000 })
await page.waitForFunction(
  () => {
    const h = document.querySelector('.export-card h3')
    return h && !h.textContent?.includes('正在导出')
  },
  null,
  { timeout: 240000 }
)
const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
assert(status.includes('导出完成'), `export failed: ${status}`)
const files = fs.readdirSync(outDir).filter((f) => f.endsWith('.png'))
assert(files.length >= 1, 'export produced no PNG')
await page.locator('.export-card button', { hasText: '完成' }).first().click().catch(() => undefined)
await page.waitForTimeout(300)

assert.equal(errors.length, 0, `page errors: ${errors.join(' | ')}`)
console.log(JSON.stringify({ total, system: 96, blocked: 4, exported: files, status, errors: [] }, null, 2))
await app.close()
