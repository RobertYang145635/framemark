/**
 * FrameMark quick visual smoke + screenshots — migrated to the v4 photo-first shell.
 *
 * Flow: boot -> screenshot the import screen (`.import-screen`) -> import fixtures ->
 * screenshot the workspace -> click a few cards from the REAL taxonomy (names are read
 * back from `.tpl-card` via allInnerTexts(), only existing cards are clicked) -> open the
 * export dialog from the topbar and screenshot it -> print thumbs count / cards total /
 * stage values / errors list (must be empty).
 *
 * Screenshots land in D:/border&watermark/_research/shots.
 *
 * ISOLATION: throw-away `--user-data-dir` under the run dir (fresh profile per run).
 *
 * Exits non-zero when any pageerror/console.error was seen or a core count is off.
 */
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')
// #57 PATH-LITERALS: test assets are resolved from a single entry point, not hardcoded root paths
const { tryResolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

async function main() {
  const project = path.resolve(__dirname, '..')
  const workspace = path.resolve(project, '..')
  const electronPath = require('electron')
  const outDir = path.resolve('D:/border&watermark/_research/shots')
  const runDir = path.join(workspace, '_research', 'smoke-run')
  const profile = path.join(runDir, 'profile')
  fs.mkdirSync(outDir, { recursive: true })
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: electronPath,
    args: ['.', `--user-data-dir=${profile}`],
    cwd: project,
    env,
    timeout: 60000
  })

  const failures = []
  try {
    const page = await app.firstWindow()
    const errors = []
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => {
      if (m.type() === 'error') errors.push(`console: ${m.text()}`)
    })
    await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
    await page.waitForSelector('.import-screen', { timeout: 20000 })
    await page.waitForTimeout(800)
    console.log('framemark api:', await page.evaluate(() => Boolean(window.framemark)))
    await page.screenshot({ path: path.join(outDir, '01-import-screen.png') })

    // tolerates a missing asset exactly like the old `.filter(fs.existsSync)` did
    const inputs = ['P1170883.jpg', 'P1181817.jpg', 'PYHY5599-已增强-降噪.jpg']
      .map((n) => tryResolveAsset(n))
      .filter((p) => p && fs.existsSync(p))
    if (!inputs.length) failures.push('no smoke fixtures resolved under _research/test-assets')

    let thumbs = 0
    let cardsTotal = 0
    let clicked = []
    if (inputs.length) {
      await page.setInputFiles('#framemark-file-input', inputs)
      await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, inputs.length, { timeout: 120000 })
      await page.waitForSelector('.workspace', { timeout: 30000 })
      await page.waitForFunction(() => document.querySelectorAll('.tpl-card').length === 175, null, { timeout: 30000 })
      await page.waitForTimeout(1500)
      await page.screenshot({ path: path.join(outDir, '02-workspace.png') })
      thumbs = await page.locator('.thumb').count()
      cardsTotal = await page.locator('.tpl-card').count()
      console.log('thumbnails:', thumbs)
      console.log('cards total:', cardsTotal)
      console.log('stage title:', (await page.locator('.stage-title strong').innerText()).trim())
      console.log('stage meta:', (await page.locator('.stage-meta').innerText()).trim())
      console.log('canvas size:', await page.evaluate(() => {
        const c = document.querySelector('.canvas-frame canvas')
        return c ? { w: c.width, h: c.height } : null
      }))

      // click a few cards from the REAL taxonomy — read the card list, skip what is absent
      const names = (await page.locator('.tpl-card').allInnerTexts()).map((t) => t.trim())
      for (const name of ['经典双行', '哈苏 · 1', '经典 · 17', '纯白 01', '色卡 01']) {
        if (!names.includes(name)) {
          console.log(`SKIP: card "${name}" not in the taxonomy card list`)
          continue
        }
        const card = page.locator('.tpl-card', { hasText: name }).first()
        await card.scrollIntoViewIfNeeded()
        await card.click()
        await page.waitForFunction(
          (n) => document.querySelector('.current-template h3')?.textContent?.trim() === n,
          name,
          { timeout: 15000 }
        )
        await page.waitForTimeout(900)
        clicked.push(name)
      }
      console.log('clicked cards:', clicked.join(' | ') || '(none)')
      await page.screenshot({ path: path.join(outDir, '03-template.png') })

      // open the export dialog from the topbar for a visual check, then cancel out
      await page.locator('.topbar-actions .btn-primary').click()
      await page.waitForSelector('.export-dialog', { timeout: 10000 })
      await page.waitForTimeout(500)
      await page.screenshot({ path: path.join(outDir, '04-export-dialog.png') })
      await page.locator('.export-dialog-foot button', { hasText: '取消' }).click()
      await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 10000 })
    }

    console.log('errors:', errors.slice(0, 20))
    if (thumbs !== inputs.length) failures.push(`thumbs ${thumbs} !== fixtures ${inputs.length}`)
    if (cardsTotal !== 175) failures.push(`cards total ${cardsTotal} !== 175`)
    if (!clicked.length) failures.push('no taxonomy card could be clicked')
    if (errors.length) failures.push(`${errors.length} page/console error(s)`)
  } finally {
    await app.close().catch(() => undefined)
  }

  if (failures.length) {
    console.error('smoke FAILED:', failures.join(' | '))
    process.exit(1)
  }
  console.log('smoke OK →', outDir)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})