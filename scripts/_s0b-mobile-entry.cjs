/**
 * S0b gates: mobile-only 全部预览 entry on the preview stage.
 *
 * Gate 1  mobile 390x844, 3 photos: `.stage-batch-chip` visible, real click opens
 *          `.batch-preview-modal` at 1 / 3. Screenshot mobile-entry.png.
 * Gate 2  desktop 1280x800: chip has zero rendered area, the filmstrip-header
 *          button is the only 全部预览 entry. Screenshot desktop-no-chip.png.
 * Gate 3  mobile: chip rect must not intersect the ratio (画幅) control in the
 *          inspector, the compare toggle, or the zoom cluster. Prints rects.
 * Gate 4  mobile with exactly 1 photo: chip must not render at all.
 *
 * Isolation: throw-away `--user-data-dir`; one Electron instance at a time.
 * Usage: node scripts/_s0b-mobile-entry.cjs
 * Artifacts: _research/s0b/*.png, gate-log.txt, gate-results.json
 */
const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const outDir = path.join(root, '_research', 's0b')
const runDir = path.join(outDir, 'run')
const profile = path.join(runDir, 'profile')
const fixtures = [
  path.join(root, '..', '_research', 'test-assets', 'ratio-study', '4x3.jpg'),
  path.join(root, '..', '_research', 'test-assets', 'ratio-study', '16x9.jpg'),
  path.join(root, '..', '_research', 'test-assets', 'ratio-study', '1x1.jpg')
]
const logPath = path.join(outDir, 'gate-log.txt')
const jsonPath = path.join(outDir, 'gate-results.json')
const lines = []
const log = (s) => {
  lines.push(s)
  console.log(s)
}
function assert (cond, msg) {
  if (!cond) throw new Error(msg)
}

/**
 * Windows adds ~1-2 px to setContentSize on this machine. Nudge the requested
 * content size until window.innerWidth/innerHeight match the gate target.
 */
async function setExactContentSize (app, page, width, height) {
  let reqW = width
  let reqH = height
  for (let i = 0; i < 5; i += 1) {
    await app.evaluate(
      async ({ BrowserWindow }, size) => {
        const win = BrowserWindow.getAllWindows()[0]
        win.setMinimumSize(300, 300)
        win.setContentSize(size.width, size.height)
      },
      { width: reqW, height: reqH }
    )
    await page.waitForTimeout(400)
    const inner = await page.evaluate(() => ({ w: window.innerWidth, h: window.innerHeight }))
    if (inner.w === width && inner.h === height) return inner
    reqW += width - inner.w
    reqH += height - inner.h
  }
  return page.evaluate(() => ({ w: window.innerWidth, h: window.innerHeight }))
}

const results = { checks: {}, rects: {}, screenshots: [], errors: [], pass: false }

async function main () {
  for (const f of fixtures) assert(fs.existsSync(f), `fixture missing: ${f}`)
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })

  // Same launch pattern as scripts/e2e.mjs: remove ELECTRON_RUN_AS_NODE or the
  // packaged binary starts as plain Node, and use a throw-away profile.
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })

  let page = null
  try {
    page = await app.firstWindow()
    page.on('pageerror', (e) => results.errors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => {
      if (m.type() === 'error') results.errors.push(`console: ${m.text()}`)
    })
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.setInputFiles('#framemark-file-input', fixtures)
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 3, null, { timeout: 120000 })

    // -------------------------------------------------------------------------
    // Mobile 390x844
    // -------------------------------------------------------------------------
    const vp = await setExactContentSize(app, page, 390, 844)
    await page.waitForFunction(() => window.innerWidth <= 480, null, { timeout: 15000 })
    log(`[setup] mobile content size: ${vp.w}x${vp.h}`)
    results.viewport = vp
    assert(Math.abs(vp.w - 390) <= 1, `expected width 390, got ${vp.w}`)
    assert(Math.abs(vp.h - 844) <= 1, `expected height 844, got ${vp.h}`)
    // Let the first render + toast settle so the chip is over a stable stage.
    await page.waitForSelector('.toast', { state: 'detached', timeout: 8000 }).catch(() => undefined)

    // --- Gate 1a: chip visible at mobile width --------------------------------
    const chip = await page.evaluate(() => {
      const el = document.querySelector('.stage-batch-chip')
      if (!el) return null
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)
      return {
        rect: { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom },
        display: cs.display,
        visibility: cs.visibility,
        text: (el.textContent || '').trim(),
        svgCount: el.querySelectorAll('svg').length,
        hit: el.contains(hit),
        inViewport: r.x >= 0 && r.y >= 0 && r.right <= window.innerWidth && r.bottom <= window.innerHeight
      }
    })
    assert(chip, '.stage-batch-chip is not rendered at 390x844')
    results.rects.chipMobile = chip.rect
    log(`G1 chip rect ${JSON.stringify(chip.rect)} display=${chip.display} text="${chip.text}" hit=${chip.hit} inViewport=${chip.inViewport}`)
    const g1visible =
      chip.display !== 'none' &&
      chip.visibility !== 'hidden' &&
      chip.rect.width > 0 &&
      chip.rect.height > 0 &&
      chip.text === '全部 3 张' &&
      chip.svgCount === 1 &&
      chip.hit &&
      chip.inViewport
    log(`${g1visible ? 'PASS' : 'FAIL'}  G1a chip visible with photo count at 390x844 (actual ${vp.w}x${vp.h})`)
    assert(g1visible, 'gate 1a: chip not visible / wrong text')
    results.checks.g1Visible = g1visible
    await page.screenshot({ path: path.join(outDir, 'mobile-entry.png') })
    results.screenshots.push('_research/s0b/mobile-entry.png')

    // --- Gate 3: no collision with ratio / compare / zoom ---------------------
    await page.evaluate(() => {
      const tab = document.querySelector('[data-editor-tab="layout"]')
      if (!tab) throw new Error('layout editor tab missing')
      tab.click()
    })
    await page.waitForFunction(
      () => [...document.querySelectorAll('.panel-section h3')].some((h) => (h.textContent || '').trim() === '画幅'),
      null,
      { timeout: 10000 }
    )
    await page.waitForTimeout(300)
    const collision = await page.evaluate(() => {
      const rect = (el) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
      }
      const chip = document.querySelector('.stage-batch-chip')
      const compare = document.querySelector('.stage-tools button[title="按住查看原图"]')
      const zoom = document.querySelector('.zoom-cluster')
      const ratioSection = [...document.querySelectorAll('.panel-section')].find(
        (s) => (s.querySelector('.section-head h3')?.textContent || '').trim() === '画幅'
      )
      const ratio = ratioSection?.querySelector('.segmented')
      const intersect = (a, b) =>
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
      const payload = {
        chip: rect(chip),
        compare: compare ? rect(compare) : null,
        zoom: zoom ? rect(zoom) : null,
        ratio: ratio ? rect(ratio) : null,
        ratioSection: ratioSection ? rect(ratioSection) : null
      }
      return {
        ...payload,
        intersects: {
          compare: payload.compare ? intersect(payload.chip, payload.compare) : null,
          zoom: payload.zoom ? intersect(payload.chip, payload.zoom) : null,
          ratio: payload.ratio ? intersect(payload.chip, payload.ratio) : null
        }
      }
    })
    results.rects.collision = collision
    log(`G3 chip     ${JSON.stringify(collision.chip)}`)
    log(`G3 compare  ${JSON.stringify(collision.compare)}`)
    log(`G3 zoom     ${JSON.stringify(collision.zoom)}`)
    log(`G3 ratio    ${JSON.stringify(collision.ratio)} (画幅 section ${JSON.stringify(collision.ratioSection)})`)
    const g3 =
      collision.compare &&
      collision.zoom &&
      collision.ratio &&
      !collision.intersects.compare &&
      !collision.intersects.zoom &&
      !collision.intersects.ratio
    log(`${g3 ? 'PASS' : 'FAIL'}  G3 chip does not intersect compare toggle / zoom cluster / ratio control`)
    assert(g3, 'gate 3: chip overlaps an existing stage control')
    results.checks.g3NoCollision = g3

    // --- Gate 1b: real click opens the viewer at 1 / 3 ------------------------
    await page.locator('.stage-batch-chip').click()
    await page.waitForSelector('.batch-preview-modal', { timeout: 10000 })
    await page.waitForFunction(
      () => (document.querySelector('.modal-head p')?.textContent || '').startsWith('1 / 3'),
      null,
      { timeout: 15000 }
    )
    await page.waitForSelector('.batch-preview-stage img:not(.is-busy)', { timeout: 60000 })
    await page.waitForTimeout(400)
    const modal = await page.evaluate(() => {
      const el = document.querySelector('.batch-preview-modal')
      const r = el.getBoundingClientRect()
      return {
        rect: { x: r.x, y: r.y, width: r.width, height: r.height },
        title: document.querySelector('.modal-head h2')?.textContent ?? '',
        subtitle: document.querySelector('.modal-head p')?.textContent ?? '',
        stripItems: document.querySelectorAll('.batch-preview-strip-item').length
      }
    })
    results.modal = modal
    log(`G1 click -> modal "${modal.title}" subtitle "${modal.subtitle}" ${modal.rect.width.toFixed(0)}x${modal.rect.height.toFixed(0)} strip=${modal.stripItems}`)
    const g1modal = modal.subtitle.startsWith('1 / 3') && modal.stripItems === 3
    log(`${g1modal ? 'PASS' : 'FAIL'}  G1b chip click opens batch viewer at 1 / 3`)
    assert(g1modal, 'gate 1b: modal did not open at 1 / 3')
    results.checks.g1Modal = g1modal
    await page.screenshot({ path: path.join(outDir, 'mobile-entry-open.png') })
    results.screenshots.push('_research/s0b/mobile-entry-open.png')

    await page.keyboard.press('Escape')
    await page.waitForSelector('.batch-preview-modal', { state: 'detached', timeout: 10000 })

    // -------------------------------------------------------------------------
    // Gate 2: desktop 1280x800 — chip has zero area, filmstrip is the only entry
    // -------------------------------------------------------------------------
    await setExactContentSize(app, page, 1280, 800)
    await page.waitForFunction(() => window.innerWidth >= 1200, null, { timeout: 15000 })
    await page.waitForTimeout(600)
    const desk = await page.evaluate(() => {
      const chip = document.querySelector('.stage-batch-chip')
      const chipRect = chip ? chip.getBoundingClientRect() : null
      const chipStyle = chip ? getComputedStyle(chip) : null
      const head = document.querySelector('.filmstrip-head')
      const headStyle = head ? getComputedStyle(head) : null
      const visibleEntries = [...document.querySelectorAll('button')]
        .filter((b) => {
          const r = b.getBoundingClientRect()
          return r.width > 0 && r.height > 0 && (b.textContent || '').includes('全部预览')
        })
        .map((b) => ({
          className: b.className,
          inFilmstrip: !!b.closest('.filmstrip-head'),
          text: (b.textContent || '').trim()
        }))
      const chipEntries = [...document.querySelectorAll('button')]
        .filter((b) => b.classList.contains('stage-batch-chip'))
        .map((b) => {
          const r = b.getBoundingClientRect()
          return { width: r.width, height: r.height, display: getComputedStyle(b).display }
        })
      return {
        vw: window.innerWidth,
        vh: window.innerHeight,
        chipExists: !!chip,
        chipRect: chipRect ? { width: chipRect.width, height: chipRect.height } : null,
        chipDisplay: chipStyle?.display ?? null,
        chipOffsetParent: chip ? !!chip.offsetParent : null,
        filmstripHeadDisplay: headStyle?.display ?? null,
        visibleEntries,
        chipEntries
      }
    })
    results.desktop = desk
    log(`G2 desktop ${desk.vw}x${desk.vh} chip exists=${desk.chipExists} display=${desk.chipDisplay} rect=${JSON.stringify(desk.chipRect)} offsetParent=${desk.chipOffsetParent}`)
    log(`G2 filmstrip-head display=${desk.filmstripHeadDisplay} visible 全部预览 entries=${JSON.stringify(desk.visibleEntries)}`)
    const g2 =
      desk.chipRect &&
      desk.chipRect.width === 0 &&
      desk.chipRect.height === 0 &&
      desk.chipDisplay === 'none' &&
      desk.chipOffsetParent === false &&
      desk.filmstripHeadDisplay !== 'none' &&
      desk.visibleEntries.length === 1 &&
      desk.visibleEntries[0].inFilmstrip === true
    log(`${g2 ? 'PASS' : 'FAIL'}  G2 desktop: chip zero area, filmstrip-header button is the only entry`)
    assert(g2, 'gate 2: duplicate entry or chip still measurable on desktop')
    results.checks.g2NoDuplicate = g2
    await page.screenshot({ path: path.join(outDir, 'desktop-no-chip.png') })
    results.screenshots.push('_research/s0b/desktop-no-chip.png')

    // -------------------------------------------------------------------------
    // Gate 4: exactly 1 photo — chip must not render
    // -------------------------------------------------------------------------
    await setExactContentSize(app, page, 390, 844)
    await page.waitForFunction(() => window.innerWidth <= 480, null, { timeout: 15000 })
    await page.waitForTimeout(500)
    // Remove the 2nd and 3rd photos through the real thumb remove buttons.
    for (let i = 0; i < 2; i += 1) {
      const before = await page.locator('.thumb').count()
      await page.locator('.thumb-remove').last().click()
      await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, before - 1, { timeout: 15000 })
    }
    await page.waitForTimeout(400)
    const single = await page.evaluate(() => ({
      photos: document.querySelectorAll('.thumb').length,
      chipCount: document.querySelectorAll('.stage-batch-chip').length,
      filmstripAll: [...document.querySelectorAll('.filmstrip-actions button')].some((b) => (b.textContent || '').includes('全部预览')),
      vw: window.innerWidth,
      vh: window.innerHeight
    }))
    results.singlePhoto = single
    log(`G4 mobile ${single.vw}x${single.vh} thumbs=${single.photos} chip nodes=${single.chipCount}`)
    const g4 = single.photos === 1 && single.chipCount === 0
    log(`${g4 ? 'PASS' : 'FAIL'}  G4 exactly 1 photo: chip not rendered`)
    assert(g4, 'gate 4: chip rendered with a single photo')
    results.checks.g4SingleHidden = g4
    await page.screenshot({ path: path.join(outDir, 'mobile-single-photo.png') })
    results.screenshots.push('_research/s0b/mobile-single-photo.png')

    results.pass =
      results.checks.g1Visible &&
      results.checks.g1Modal &&
      results.checks.g2NoDuplicate &&
      results.checks.g3NoCollision &&
      results.checks.g4SingleHidden &&
      results.errors.length === 0
    log(`console/page errors: ${results.errors.length ? results.errors.join(' | ') : 'none'}`)
    assert(results.errors.length === 0, `console/page errors: ${results.errors.join(' | ')}`)
  } finally {
    if (page) await page.close().catch(() => undefined)
    await app.close().catch(() => undefined)
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
    fs.writeFileSync(jsonPath, JSON.stringify(results, null, 2))
  }
  log(`GATES: ${results.pass ? 'PASS' : 'FAIL'}`)
  fs.writeFileSync(logPath, lines.join('\n') + '\n')
  fs.writeFileSync(jsonPath, JSON.stringify(results, null, 2))
  if (!results.pass) process.exit(1)
}

main().catch((e) => {
  log(`FAIL ${e && e.stack ? e.stack : e}`)
  try {
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
    fs.writeFileSync(jsonPath, JSON.stringify(results, null, 2))
  } catch {}
  process.exit(1)
})
