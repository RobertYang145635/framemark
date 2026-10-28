/**
 * S2 gate — undo/redo UI (one Electron instance, throw-away profile).
 *
 * Proves, in order:
 *   1. KEYBOARD: body focused, Ctrl+Z drops one history step and repaints the
 *      preview canvas; Ctrl+Shift+Z puts it back; Ctrl+Y redoes too.
 *   2. INPUT IMMUNITY: a focused Inspector text field keeps its native undo —
 *      Ctrl+Z there must not change the history size.
 *   3. GESTURE COLLAPSE: a single pointer drag of a range slider lasting >1.2 s
 *      with >600 ms pauses (so the 600 ms coalescing window alone cannot merge
 *      it) becomes EXACTLY 1 entry; a second, different slider adds one more.
 *   4. RESET UNDOABLE: the Inspector 「重置」 button adds 1 entry and one Ctrl+Z
 *      restores the pre-reset params JSON byte-for-byte.
 *   5. NO REGRESSIONS: S1 undo probe still GREEN, batch-preview probe still
 *      passes (run sequentially AFTER this app closes — one instance at a time).
 *
 * RED run: build with GESTURE_SEAL = false in App.tsx. The drag then splits at
 * every >600 ms pause (several entries), gate 3 fails, everything else passes.
 *
 * Usage: node scripts/_s2-undo-ui-probe.cjs   (exit 0 = green)
 * Output is captured by the caller into _research/s2/probe-{red,green}.txt.
 */
const path = require('node:path')
const fs = require('node:fs')
const { spawnSync } = require('node:child_process')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const research = path.join(root, '..', '_research')
const runDir = path.join(research, 's2', 'run')
const profile = path.join(runDir, 'profile')
const fixture = path.join(research, 'test-assets', 'ratio-study', '4x3.jpg')

const STOCK_TEMPLATE = '经典 · 17'
const HAND_TEMPLATE = '杂志编辑'
/** 30 moves over ~4.8 s with five 650 ms holds inside one mouse-down. */
const MOVES_PER_BURST = 5
const BURSTS = 6
const MOVE_GAP_MS = 40
const BURST_PAUSE_MS = 650

/** Local iteration switch only; the saved green evidence runs gate 5 too. */
const SKIP_REGRESSIONS = process.argv.includes('--skip-regressions')

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
  if (!fs.existsSync(fixture)) throw new Error(`fixture missing: ${fixture}`)

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
  const paramsJson = () => page.evaluate(() => JSON.stringify(window.__fmHistory.design().params))

  /** Coarse 48×48 fingerprint of the visible preview canvas. */
  const canvasSig = () =>
    page.evaluate(() => {
      const c = document.querySelector('canvas[aria-label="模板预览"]')
      if (!(c instanceof HTMLCanvasElement)) return null
      const out = document.createElement('canvas')
      out.width = 48
      out.height = 48
      const ctx = out.getContext('2d')
      if (!ctx) return null
      ctx.drawImage(c, 0, 0, 48, 48)
      const d = ctx.getImageData(0, 0, 48, 48).data
      let h = 0
      for (let i = 0; i < d.length; i += 4) h = (h * 31 + d[i] * 3 + d[i + 1] * 5 + d[i + 2] * 7) | 0
      return `${c.width}x${c.height}:${h}`
    })

  async function waitUntil(fn, timeout = 5000, interval = 60) {
    const start = Date.now()
    for (;;) {
      const v = await fn()
      if (v) return v
      if (Date.now() - start > timeout) return false
      await page.waitForTimeout(interval)
    }
  }

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

  async function blurActive() {
    return page.evaluate(() => {
      const a = document.activeElement
      if (a instanceof HTMLElement) a.blur()
      return document.activeElement ? document.activeElement.tagName : 'NONE'
    })
  }

  /** One mouse-down, N bursts of moves with >600 ms holds in between, one up. */
  async function dragSlider(locator, label) {
    await locator.scrollIntoViewIfNeeded()
    const box = await locator.boundingBox()
    if (!box) throw new Error(`no bounding box for slider ${label}`)
    const y = box.y + box.height / 2
    const x0 = box.x + box.width * 0.3
    const x1 = box.x + box.width * 0.75
    const started = Date.now()
    await page.mouse.move(x0, y)
    await page.mouse.down()
    let moves = 0
    for (let burst = 0; burst < BURSTS; burst++) {
      for (let i = 1; i <= MOVES_PER_BURST; i++) {
        const t = (burst * MOVES_PER_BURST + i) / (BURSTS * MOVES_PER_BURST)
        await page.mouse.move(x0 + (x1 - x0) * t, y)
        moves += 1
        await page.waitForTimeout(MOVE_GAP_MS)
      }
      if (burst < BURSTS - 1) await page.waitForTimeout(BURST_PAUSE_MS)
    }
    await page.mouse.up()
    const elapsed = Date.now() - started
    await page.waitForTimeout(350)
    return { moves, elapsed }
  }

  try {
    // ---------------------------------------------------------------- setup
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.setInputFiles('#framemark-file-input', [fixture])
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 1, null, { timeout: 120000 })
    await page.waitForFunction(() => document.querySelectorAll('.tpl-card').length === 175, null, { timeout: 30000 })

    step('setup', `apply stock template ${STOCK_TEMPLATE}`)
    const card = page.locator('.tpl-card', { hasText: STOCK_TEMPLATE }).first()
    await card.scrollIntoViewIfNeeded()
    await card.click()
    await page.waitForFunction(
      (name) => document.querySelector('.current-template h3')?.textContent?.trim() === name,
      STOCK_TEMPLATE,
      { timeout: 15000 }
    )
    // The switch also kicks off an async vendor/auto logo write; let it land.
    await waitDesignStable()
    check('setup  template switch clears history', (await size()) === 0, `size=${await size()}`)

    // ------------------------------------------------------ gate 1: keyboard
    step('gate 1', 'focus the body, then Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y')
    const baseSig = await canvasSig()
    check('key    preview canvas present', typeof baseSig === 'string' && baseSig.length > 0, `sig=${baseSig}`)
    await page.evaluate(() => window.__fmHistory.act.setRatio('1:1'))
    const ratioPainted = await waitUntil(async () => (await canvasSig()) !== baseSig, 6000)
    const modifiedSig = await canvasSig()
    check('key    setup ratio change repainted the preview', Boolean(ratioPainted), `sig=${modifiedSig}`)
    check('key    setup recorded exactly 1 step', (await size()) === 1, `size=${await size()}`)

    const focusTag = await blurActive()
    check('key    no input focused (body owns focus)', focusTag === 'BODY', `active=${focusTag}`)

    await page.keyboard.press('Control+z')
    const sizeDown = await waitUntil(async () => (await size()) === 0, 4000)
    const sigUndone = await waitUntil(async () => (await canvasSig()) !== modifiedSig, 6000)
    const sigAtUndo = await canvasSig()
    check('key    Ctrl+Z decreases history by 1', Boolean(sizeDown), `size=${await size()}`)
    check('key    Ctrl+Z repaints the preview canvas', Boolean(sigUndone), `now=${sigAtUndo} was=${modifiedSig}`)

    await page.keyboard.press('Control+Shift+z')
    const sizeBack = await waitUntil(async () => (await size()) === 1, 4000)
    const sigRedone = await waitUntil(async () => (await canvasSig()) === modifiedSig, 6000)
    check('key    Ctrl+Shift+Z restores the history size', Boolean(sizeBack), `size=${await size()}`)
    check('key    Ctrl+Shift+Z restores the preview canvas', Boolean(sigRedone), `sig=${await canvasSig()}`)

    await page.keyboard.press('Control+z')
    check('key    second Ctrl+Z undoes again', Boolean(await waitUntil(async () => (await size()) === 0, 4000)), `size=${await size()}`)
    await page.keyboard.press('Control+y')
    const ctrlY = await waitUntil(async () => (await size()) === 1, 4000)
    check('key    Ctrl+Y also redoes', Boolean(ctrlY), `size=${await size()}`)

    // ------------------------------------- gate 3: one drag = one entry
    step('gate 3', 'drag the Logo 大小 slider ~4.8 s (30 moves, 5 holds > 600 ms)')
    await page.locator('.editor-tabs button', { hasText: 'Logo' }).click()
    await page.waitForSelector('.editor-pane[data-editor-pane="logo"]', { timeout: 10000 })
    const logoRange = page.locator('.editor-pane[data-editor-pane="logo"] input[type="range"]').first()
    check('gesture logo slider present', (await logoRange.count()) === 1, `count=${await logoRange.count()}`)
    const beforeDrag = await size()
    const logoScaleBefore = await page.evaluate(() => window.__fmHistory.design().params.logoScale)
    const dragA = await dragSlider(logoRange, 'Logo 大小')
    const afterDrag = await size()
    const logoScaleAfter = await page.evaluate(() => window.__fmHistory.design().params.logoScale)
    const dragLabels = (await labels()).slice(beforeDrag)
    console.log(
      `      drag A: ${dragA.moves} moves in ${dragA.elapsed} ms; size ${beforeDrag} -> ${afterDrag}; ` +
        `logoScale ${String(logoScaleBefore)} -> ${String(logoScaleAfter)}; labels=${JSON.stringify(dragLabels)}`
    )
    check('gesture drag really lasted >= 1.2 s', dragA.elapsed >= 1200, `elapsed=${dragA.elapsed} ms`)
    check('gesture drag really emitted >= 25 moves', dragA.moves >= 25, `moves=${dragA.moves}`)
    check('gesture drag changed the slider value', logoScaleBefore !== logoScaleAfter, `${logoScaleBefore} -> ${logoScaleAfter}`)
    check('gesture ONE drag adds exactly 1 history entry', afterDrag - beforeDrag === 1, `delta=${afterDrag - beforeDrag}`)

    step('gate 3', 'drag a DIFFERENT slider (布局 · 白边高度)')
    await page.locator('.editor-tabs button', { hasText: '布局' }).click()
    await page.waitForSelector('.editor-pane[data-editor-pane="layout"]', { timeout: 10000 })
    const layoutRange = page.locator('.editor-pane[data-editor-pane="layout"] input[type="range"]').first()
    const layoutRangeCount = await layoutRange.count()
    check('gesture second slider present', layoutRangeCount === 1, `count=${layoutRangeCount}`)
    const beforeSecond = await size()
    const designBeforeSecond = await designJson()
    const dragB = await dragSlider(layoutRange, '白边高度')
    const afterSecond = await size()
    const designAfterSecond = await designJson()
    console.log(
      `      drag B: ${dragB.moves} moves in ${dragB.elapsed} ms; size ${beforeSecond} -> ${afterSecond}; ` +
        `designChanged=${designBeforeSecond !== designAfterSecond}; labels=${JSON.stringify((await labels()).slice(beforeSecond))}`
    )
    check('gesture second slider drag also adds 1 entry', afterSecond - beforeSecond === 1, `delta=${afterSecond - beforeSecond}`)
    check('gesture second slider drag changed the design', designBeforeSecond !== designAfterSecond)

    // ------------------------------------------- gate 4: reset is undoable
    step('gate 4', 'click the Inspector 重置 button, then one Ctrl+Z')
    const beforeReset = await size()
    const preResetParams = await paramsJson()
    const resetBtn = page.locator('.current-template-actions button', { hasText: '重置' }).first()
    check('reset  Inspector 重置 button present', (await resetBtn.count()) === 1, `count=${await resetBtn.count()}`)
    await resetBtn.click()
    const afterReset = await waitUntil(async () => (await size()) === beforeReset + 1, 4000)
    const resetLabels = (await labels()).slice(beforeReset)
    const resetParams = await paramsJson()
    check('reset  重置 adds exactly 1 history entry', Boolean(afterReset), `size ${beforeReset} -> ${await size()}`)
    check('reset  the new entry is labelled 重置参数', resetLabels.length === 1 && resetLabels[0] === '重置参数', `labels=${JSON.stringify(resetLabels)}`)
    check('reset  重置 actually changed params', resetParams !== preResetParams, `changed=${resetParams !== preResetParams}`)

    await page.keyboard.press('Control+z')
    const undoneReset = await waitUntil(async () => (await size()) === beforeReset, 4000)
    const restoredParams = await paramsJson()
    check('reset  one Ctrl+Z removes the reset step', Boolean(undoneReset), `size=${await size()}`)
    check('reset  one Ctrl+Z restores the pre-reset params JSON', restoredParams === preResetParams, `equal=${restoredParams === preResetParams}`)

    // --------------------------------- gate 2: text field owns its Ctrl+Z
    step('gate 2', `switch to hand template ${HAND_TEMPLATE}, type in the first text slot, press Ctrl+Z`)
    // The 卡片 category only shows its first 8 cards; search lifts that cap so
    // the card is genuinely visible (a hidden node must not be clicked).
    await page.locator('.border-search input').fill(HAND_TEMPLATE)
    await page.waitForTimeout(400)
    const handCard = page.locator('.tpl-card', { hasText: HAND_TEMPLATE }).first()
    await handCard.scrollIntoViewIfNeeded()
    await handCard.click()
    await page.waitForFunction(
      (name) => document.querySelector('.current-template h3')?.textContent?.trim() === name,
      HAND_TEMPLATE,
      { timeout: 15000 }
    )
    await waitDesignStable()
    // A history entry must exist, otherwise "size unchanged" proves nothing.
    await page.evaluate(() => window.__fmHistory.act.setParam('marginPct', 9))
    check('input  setup entry recorded before the typing test', (await size()) === 1, `size=${await size()}`)

    await page.locator('.editor-tabs button', { hasText: '文字' }).click()
    await page.waitForSelector('.editor-pane[data-editor-pane="text"]', { timeout: 10000 })
    const textInput = page.locator('.editor-pane[data-editor-pane="text"] .text-slot-row input:not([disabled])').first()
    const textInputCount = await textInput.count()
    check('input  hand template exposes an editable text slot', textInputCount === 1, `count=${textInputCount}`)
    await textInput.scrollIntoViewIfNeeded()
    await textInput.click()
    const valueBefore = await textInput.inputValue()
    await page.keyboard.type('S2X')
    const valueTyped = await textInput.inputValue()
    const sizeBeforeUndo = await size()
    check('input  typing really changed the field', valueTyped !== valueBefore, `"${valueBefore}" -> "${valueTyped}"`)
    check(
      'input  typing was recorded as design edits (test is not vacuous)',
      sizeBeforeUndo >= 1,
      `size=${sizeBeforeUndo}`
    )

    await page.keyboard.press('Control+z')
    await page.waitForTimeout(500)
    const sizeAfterUndo = await size()
    const focusAfter = await page.evaluate(() => document.activeElement?.tagName ?? 'NONE')
    check('input  Ctrl+Z in a text field does NOT change history size', sizeAfterUndo === sizeBeforeUndo, `${sizeBeforeUndo} -> ${sizeAfterUndo}`)
    check('input  the text field keeps focus (native undo owns it)', focusAfter === 'INPUT', `active=${focusAfter}`)
    console.log(
      `      native undo left the field as "${await textInput.inputValue()}" (DOM result is Chromium's, not ours)`
    )

    await page.screenshot({ path: path.join(research, 's2', 'final.png') })
    check('errors no page/console errors', errors.length === 0, errors.length ? errors.join(' | ') : '')
  } catch (e) {
    failures.push(`exception: ${e && e.message ? e.message : e}`)
    console.error('FAIL', e)
    try {
      await page.screenshot({ path: path.join(research, 's2', 'failure.png') })
    } catch {
      // app may already be gone
    }
  } finally {
    await app.close()
  }

  // ------------------------------------------ gate 5: no regressions
  // Sequential, AFTER this instance closed: the repo's probes share out/.
  if (SKIP_REGRESSIONS) {
    console.log('SKIP  gate 5 (--skip-regressions)')
  } else {
  step('gate 5', 'run scripts/_s1-undo-probe.cjs')
  const s1 = spawnSync(process.execPath, [path.join(__dirname, '_s1-undo-probe.cjs')], {
    cwd: root,
    encoding: 'utf8',
    timeout: 600000
  })
  fs.writeFileSync(path.join(research, 's2', 'gate5-s1.txt'), `${s1.stdout ?? ''}${s1.stderr ?? ''}`)
  const s1Tail = (s1.stdout ?? '').trim().split(/\r?\n/).slice(-1)[0] ?? ''
  check('regress S1 undo probe exits 0', s1.status === 0, `status=${s1.status}`)
  check('regress S1 undo probe prints GREEN', /\bGREEN\b/.test(s1Tail), `last="${s1Tail}"`)

  step('gate 5', 'run scripts/_batch-preview-probe.cjs')
  const batch = spawnSync(process.execPath, [path.join(__dirname, '_batch-preview-probe.cjs')], {
    cwd: root,
    encoding: 'utf8',
    timeout: 600000
  })
  const batchOut = `${batch.stdout ?? ''}${batch.stderr ?? ''}`
  fs.writeFileSync(path.join(research, 's2', 'gate5-batch.txt'), batchOut)
  const batchPass = (batchOut.match(/^PASS /gm) ?? []).length
  const batchFail = (batchOut.match(/^FAIL /gm) ?? []).length
  check('regress batch-preview probe exits 0', batch.status === 0, `status=${batch.status}`)
  check('regress batch-preview probe has no FAIL lines', batchFail === 0, `FAIL=${batchFail}`)
  console.log(`      batch-preview PASS lines=${batchPass} (6 feature checks + 1 error check)`)
  }

  console.log('')
  console.log(
    `${failures.length === 0 ? 'GREEN' : 'RED'}  steps=${stepNo}  failures=${failures.length}` +
      (failures.length ? ` -> ${failures.join(' | ')}` : '')
  )
  if (failures.length) process.exit(1)
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
