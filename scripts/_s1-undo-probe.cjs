/**
 * S1 gate — undo/redo over DESIGN FIELDS (one Electron instance, throw-away profile).
 *
 * What it proves
 *   1. two photos import + a stock template (经典 · 17) is applied;
 *   2. a scripted 30-step run mixes 10 rapid setParam('marginPct'), 5 text edits,
 *      1 REAL UI theme click (the async-absorption case), 2 ratio changes,
 *      1 logo change and 1 preset apply;
 *   3. __fmHistory.size() equals the number of discrete user actions
 *      (11 green / 12 red — the theme click must contribute exactly 1 entry);
 *   4. undoing to the bottom restores the design captured before step 1
 *      (JSON deep-equal) and redo returns to the final design JSON;
 *   5. switching template clears history (size() === 0);
 *   6. no page/console errors.
 *
 * RED run: with the async logo reconciliation NOT absorbed, the theme click
 * pushes 2 entries (`参数 theme` + `Logo 适配`) and the size/theme gates fail.
 *
 * Usage: node scripts/_s1-undo-probe.cjs
 * Exit code 0 = green, 1 = at least one FAIL (stdout is tee'd by the caller into
 * _research/s1/probe-green.txt / probe-red.txt).
 */
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const runDir = path.join(root, '..', '_research', 's1', 'run')
const profile = path.join(runDir, 'profile')
const fixture = path.join(root, '..', '_research', 'test-assets', 'ratio-study', '4x3.jpg')

const TEMPLATE_1 = '经典 · 17'
/** Catalog version with a LIGHT cut (dji-2016) and a DARK cut (dji_white), both app-local. */
const LOGO_VERSION = 'dji-2016'
/** Discrete user actions in step 2 (see the justification block printed below). */
const EXPECTED_SIZE = 11

const failures = []
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  if (!ok) failures.push(name)
}

let stepNo = 0
function step(kind, text) {
  stepNo += 1
  console.log(`STEP ${String(stepNo).padStart(2, '0')}  [${kind}]  ${text}`)
}

;(async () => {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  const second = path.join(runDir, 'second.jpg')
  fs.copyFileSync(fixture, second)

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })

  const size = () => page.evaluate(() => window.__fmHistory.size())
  const labels = () => page.evaluate(() => window.__fmHistory.labels())
  const designJson = () => page.evaluate(() => JSON.stringify(window.__fmHistory.design()))
  const brandCut = () =>
    page.evaluate(() => {
      const b = window.__fmHistory.design().brandVersion
      return b ? `${b.versionId}/${b.cutId}/${b.surface}` : null
    })

  /** Poll the live design until two consecutive reads agree (async writes landed). */
  async function waitDesignStable(gap = 450, tries = 10) {
    let last = await designJson()
    for (let i = 0; i < tries; i++) {
      await page.waitForTimeout(gap)
      const now = await designJson()
      if (now === last) return now
      last = now
    }
    return last
  }

  try {
    // ---------------------------------------------------------------- setup
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.setInputFiles('#framemark-file-input', [fixture, second])
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 2, null, { timeout: 120000 })
    await page.waitForFunction(() => document.querySelectorAll('.tpl-card').length === 175, null, { timeout: 30000 })

    const card = page.locator('.tpl-card', { hasText: TEMPLATE_1 }).first()
    await card.scrollIntoViewIfNeeded()
    await card.click()
    await page.waitForFunction(
      (name) => document.querySelector('.current-template h3')?.textContent?.trim() === name,
      TEMPLATE_1,
      { timeout: 15000 }
    )
    // The template switch also kicks off an async vendor/auto logo write; let it land.
    const baseline = await waitDesignStable()
    const setupSize = await size()
    check('setup  template switch clears history', setupSize === 0, `size=${setupSize}`)
    console.log(`SETUP  baseline design captured (${baseline.length} chars JSON)`)

    // ------------------------------------------------- step 1-10: margin burst
    const burstStart = Date.now()
    const stamps = []
    for (let i = 0; i < 10; i++) {
      await page.evaluate((v) => window.__fmHistory.act.setParam('marginPct', v), 6 + i * 0.5)
      stamps.push(Date.now())
      step('action', `setParam('marginPct', ${6 + i * 0.5})`)
      if (i < 9) await page.waitForTimeout(20)
    }
    const burstMs = Date.now() - burstStart
    let maxGap = 0
    for (let i = 1; i < stamps.length; i++) maxGap = Math.max(maxGap, stamps[i] - stamps[i - 1])
    console.log(`      burst=${burstMs} ms, max gap between writes=${maxGap} ms (coalesce window 600 ms)`)

    step('probe', 'read history size after the burst')
    const afterBurst = await size()
    check('burst  10 marginPct writes coalesce into 1 step', afterBurst === 1, `size=${afterBurst}`)
    console.log(`      labels=${JSON.stringify(await labels())}`)

    step('nav', 'open the 主题 editor tab (real UI)')
    await page.locator('.editor-tabs button', { hasText: '主题' }).click()
    await page.waitForSelector('.editor-pane[data-editor-pane="theme"]', { timeout: 10000 })

    // ------------------------------------------------- step 13-17: 5 text edits
    for (let i = 1; i <= 5; i++) {
      const patch = {}
      for (let k = 1; k <= i; k++) patch[`probe#${k}`] = `line ${k}`
      await page.evaluate((p) => window.__fmHistory.act.setText({ slotValues: p }), patch)
      step('action', `setText({ slotValues }) #${i}  (spaced 700 ms > coalesce window)`)
      if (i < 5) await page.waitForTimeout(700)
    }
    step('probe', 'read history size after 5 text edits')
    const afterText = await size()
    check('text   5 spaced text edits are 5 steps', afterText === 6, `size=${afterText}`)
    console.log(`      labels=${JSON.stringify(await labels())}`)

    // ------------------------------------------------- step 19: 1 logo change
    await page.evaluate((id) => window.__fmHistory.act.setLogoVersion(id), LOGO_VERSION)
    step('action', `setLogoVersion('${LOGO_VERSION}')  (async: awaits logoAsset)`)
    step('probe', 'read size + brandVersion; save a preset for the late apply')
    const afterLogo = await size()
    const logoCutBeforeTheme = await brandCut()
    check('logo   logo change is 1 step', afterLogo === 7, `size=${afterLogo}`)
    check(
      'logo   picked the LIGHT cut (white theme)',
      logoCutBeforeTheme === `${LOGO_VERSION}/dji-2016/light`,
      `brandVersion=${logoCutBeforeTheme}`
    )
    const presetId = await page.evaluate(() => window.__fmHistory.act.savePreset('S1 probe preset'))
    check('setup  preset saved for the apply step', typeof presetId === 'string' && presetId.length > 0, `id=${presetId}`)

    // --------------------------------------- step 21: REAL UI theme click (async)
    const beforeTheme = await size()
    step('action', 'click the 深色虚化 theme swatch in the REAL UI (async absorption case)')
    await page.locator('.fmk-swatch', { hasText: '深色虚化' }).click()
    await waitDesignStable()
    step('probe', 'read size right after the theme click settled')
    const afterTheme = await size()
    const themeDelta = afterTheme - beforeTheme
    const themeLabels = (await labels()).slice(beforeTheme)
    check(
      'theme  theme click contributes exactly 1 entry',
      themeDelta === 1,
      `delta=${themeDelta} newLabels=${JSON.stringify(themeLabels)}`
    )
    step('probe', 'verify the asynchronous logo reconciliation actually ran')
    const logoCutAfterTheme = await brandCut()
    check(
      'theme  async logo follow-up landed (dark cut) — otherwise red/green is vacuous',
      logoCutAfterTheme === `${LOGO_VERSION}/dji_white/dark`,
      `brandVersion=${logoCutAfterTheme}`
    )

    // ------------------------------------------------- step 24-25: 2 ratio changes
    await page.evaluate(() => window.__fmHistory.act.setRatio('1:1'))
    step('action', "setRatio('1:1')")
    await page.waitForTimeout(700)
    await page.evaluate(() => window.__fmHistory.act.setRatio('4:5'))
    step('action', "setRatio('4:5')  (700 ms after the first, so not coalesced)")
    step('probe', 'read history size after the two ratio changes')
    const afterRatio = await size()
    check('ratio  2 spaced ratio changes are 2 steps', afterRatio === afterTheme + 2, `size=${afterRatio} (after theme ${afterTheme})`)

    // ------------------------------------------------- step 27: 1 preset apply
    await page.evaluate((id) => window.__fmHistory.act.applyPreset(id), presetId)
    step('action', 'applyPreset(saved preset) — restores the pre-theme design')
    step('probe', 'read history size after the preset apply')
    await waitDesignStable()
    const finalSize = await size()
    check('preset preset apply is 1 step', finalSize === afterRatio + 1, `size=${finalSize} (after ratio ${afterRatio})`)
    step('probe', 'read the labels of every recorded step')
    const finalLabels = await labels()
    console.log(`      labels=${JSON.stringify(finalLabels)}`)
    step('probe', 'capture the final design JSON')
    const finalJson = await designJson()

    // ------------------------------------------------------ expected-count gate
    console.log('')
    console.log('Expected discrete steps = 11, justified line by line:')
    console.log('   +1  10 × setParam(marginPct): same key param:marginPct, every gap <600 ms, no gesture -> merge')
    console.log('   +5  5 × setText({slotValues}): same key text:slotValues but each gap is 700 ms > 600 ms -> 5 steps')
    console.log('   +1  1 × setLogoVersion: one awaited write, key logo, nothing else inside -> 1 step')
    console.log('   +1  1 × theme click: setParam(theme) pushes 1; the async logo reconcile is absorbed -> 1 (RED: 2)')
    console.log('   +2  2 × setRatio: same key ratio, 700 ms apart -> 2 steps')
    console.log('   +1  1 × applyPreset -> 1 step')
    console.log('   = 11 (green)  /  12 (red, the theme click double-counts)')
    check('steps  the scripted pre-undo run is 30 logged steps', stepNo === 30, `steps=${stepNo}`)
    check(
      'size   history size equals the expected discrete user actions',
      finalSize === EXPECTED_SIZE,
      `expected=${EXPECTED_SIZE} got=${finalSize}`
    )

    // ---------------------------------------------------- undo all -> bottom
    let undone = 0
    for (;;) {
      const before = await designJson()
      const moved = await page.evaluate(() => {
        const h = window.__fmHistory
        const n = h.size()
        h.undo()
        return n > 0
      })
      if (!moved) break
      undone += 1
      const after = await designJson()
      if (after === before) {
        check('undo   every undo actually changes the design', false, `undo #${undone} left the design identical`)
        break
      }
      if (undone > 60) break
    }
    const atBottom = await designJson()
    check('undo   undo count matches the recorded steps', undone === finalSize, `undone=${undone} recorded=${finalSize}`)
    check(
      'undo   bottom design is JSON-identical to the pre-step-1 design',
      atBottom === baseline,
      `bottom=${atBottom.length} chars baseline=${baseline.length} chars`
    )
    check('undo   history is empty at the bottom', (await size()) === 0, `size=${await size()}`)

    // ---------------------------------------------------- redo all -> final
    let redone = 0
    for (;;) {
      const moved = await page.evaluate(() => {
        const h = window.__fmHistory
        const n = h.size()
        h.redo()
        return h.size() !== n
      })
      if (!moved) break
      redone += 1
      if (redone > 60) break
    }
    const redoneJson = await designJson()
    check('redo   redo count matches the recorded steps', redone === finalSize, `redone=${redone} recorded=${finalSize}`)
    check(
      'redo   final design is JSON-identical to the final captured design',
      redoneJson === finalJson,
      `redo=${redoneJson.length} chars final=${finalJson.length} chars`
    )

    // ------------------------------------- template switch clears the history
    const switchedTo = await page.evaluate(() => {
      const current = document.querySelector('.current-template h3')?.textContent?.trim() ?? ''
      const el = [...document.querySelectorAll('.tpl-card')].find(
        (c) => !c.classList.contains('border-card-hidden') && !c.classList.contains('active') && c.title && c.title !== current
      )
      if (!el) return null
      el.click()
      return el.title
    })
    check('switch found a visible, different template card to click', typeof switchedTo === 'string' && switchedTo.length > 0, `card=${switchedTo}`)
    await page.waitForFunction(
      (name) => document.querySelector('.current-template h3')?.textContent?.trim() === name,
      switchedTo,
      { timeout: 15000 }
    )
    await waitDesignStable()
    const afterSwitch = await size()
    check('switch template switch clears history (size 0)', afterSwitch === 0, `size=${afterSwitch}`)
    const undoNoop = await page.evaluate(() => {
      const h = window.__fmHistory
      const before = JSON.stringify(h.design())
      h.undo()
      h.redo()
      return JSON.stringify(h.design()) === before
    })
    check('switch undo/redo after a template switch is a no-op', undoNoop === true)

    // ------------------------------------------------------------- errors
    check('errors no page/console errors', errors.length === 0, errors.length ? errors.join(' | ') : '')
  } catch (e) {
    failures.push(`exception: ${e && e.message ? e.message : e}`)
    console.error('FAIL', e)
  } finally {
    console.log('')
    console.log(
      `${failures.length === 0 ? 'GREEN' : 'RED'}  steps=${stepNo}  failures=${failures.length}${failures.length ? ` -> ${failures.join(' | ')}` : ''}`
    )
    await app.close()
  }

  if (failures.length) process.exit(1)
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
