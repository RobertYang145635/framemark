/**
 * S0 Gate B (mobile half): 390x844 full-screen batch viewer.
 *
 * Asserts:
 *   B1 modal covers the 390x844 viewport
 *   B2 stage <img> rendered width >= 90% of window.innerWidth
 *   B3 bottom thumbnail strip visible with exactly one item per photo
 *   B4 tapping a strip item jumps to that index
 *
 * NOTE: at <=700px the app hides `.filmstrip-head` (existing mobile layout),
 * which is where the 全部预览 button lives. The probe therefore dispatches the
 * button's DOM click instead of a pointer click; the viewer under test is the
 * same one the desktop entry opens.
 *
 * Usage: node scripts/_s0-gate-b-mobile.cjs
 * Artifacts: _research/s0/mobile-1ofN.png, mobile-after-jump.png,
 *            gate-b-mobile.json, gate-b-mobile.txt
 */
const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const workspace = path.resolve(root, '..')
const s0 = path.join(workspace, '_research', 's0')
const runDir = path.join(s0, 'run-gate-b-mobile')
const profile = path.join(runDir, 'profile')
const fixtures = [
  path.join(workspace, '_research', 'test-assets', 'ratio-study', '4x3.jpg'),
  path.join(workspace, '_research', 'test-assets', 'ratio-study', '16x9.jpg'),
  path.join(s0, 'nonexif.jpg')
]
const logPath = path.join(s0, 'gate-b-mobile.txt')
const jsonPath = path.join(s0, 'gate-b-mobile.json')
const lines = []
const log = (s) => {
  lines.push(s)
  console.log(s)
}
function assert (cond, msg) {
  if (!cond) throw new Error(msg)
}

async function main () {
  for (const f of fixtures) assert(fs.existsSync(f), `fixture missing: ${f}`)
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })

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

  let report = null
  try {
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.setInputFiles('#framemark-file-input', fixtures)
    await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 3, null, { timeout: 120000 })

    // shrink the real BrowserWindow: minWidth is 1024, so lower both limits first
    await app.evaluate(async ({ BrowserWindow }) => {
      const win = BrowserWindow.getAllWindows()[0]
      win.setMinimumSize(300, 300)
      win.setContentSize(390, 844)
    })
    await page.waitForTimeout(700)
    const probe = await page.evaluate(() => ({
      iw: window.innerWidth,
      ih: window.innerHeight,
      ow: window.outerWidth,
      oh: window.outerHeight
    }))
    log(`after resize: inner ${probe.iw}x${probe.ih} outer ${probe.ow}x${probe.oh}`)
    await page.waitForFunction(() => window.innerWidth <= 480, null, { timeout: 15000 })
    const vw = await page.evaluate(() => window.innerWidth)
    const vh = await page.evaluate(() => window.innerHeight)
    log(`viewport: ${vw}x${vh}`)

    // open the 1/N viewer (button lives in the CSS-hidden filmstrip head)
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('.filmstrip-actions button')].find((b) =>
        (b.textContent || '').includes('全部预览')
      )
      if (!btn) throw new Error('全部预览 button missing')
      btn.click()
    })
    await page.waitForSelector('.batch-preview-modal', { timeout: 10000 })
    await page.waitForSelector('.batch-preview-stage img:not(.is-busy)', { timeout: 60000 })
    await page.waitForFunction(() => {
      const strip = document.querySelector('.batch-preview-strip')
      return strip && getComputedStyle(strip).display === 'flex'
    }, null, { timeout: 10000 })
    // let the import toast clear so it cannot cover the strip in the evidence shot
    await page.waitForSelector('.toast', { state: 'detached', timeout: 8000 }).catch(() => undefined)

    const m = await page.evaluate(() => {
      const rect = (el) => {
        const r = el.getBoundingClientRect()
        return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom, right: r.right }
      }
      const modal = document.querySelector('.batch-preview-modal')
      const img = document.querySelector('.batch-preview-stage img')
      const strip = document.querySelector('.batch-preview-strip')
      const items = [...document.querySelectorAll('.batch-preview-strip-item')]
      return {
        vw: window.innerWidth,
        vh: window.innerHeight,
        modal: rect(modal),
        img: rect(img),
        strip: { ...rect(strip), display: getComputedStyle(strip).display, overflowX: getComputedStyle(strip).overflowX },
        items: items.map((it, i) => ({
          i,
          active: it.classList.contains('active'),
          rect: rect(it),
          src: (it.querySelector('img')?.getAttribute('src') || '').slice(0, 32)
        })),
        title: document.querySelector('.modal-head p')?.textContent ?? ''
      }
    })

    await page.screenshot({ path: path.join(s0, 'mobile-1ofN.png') })

    const b1 = Math.abs(m.modal.width - vw) <= 1 && Math.abs(m.modal.height - vh) <= 1
    const b2 = m.img.width >= vw * 0.9
    const b3 =
      m.strip.display === 'flex' &&
      m.strip.height >= 40 &&
      m.strip.bottom <= vh + 1 &&
      m.strip.y >= m.img.y &&
      m.items.length === 3
    const allSrc = m.items.every((it) => it.src.length > 0)
    const b4pre = m.items.findIndex((it) => it.active) === 0 && m.title.startsWith('1 / 3')
    log(`B1 modal ${m.modal.width.toFixed(1)}x${m.modal.height.toFixed(1)} @ (${m.modal.x},${m.modal.y}) vs viewport ${vw}x${vh}`)
    log(`B2 stage img width ${m.img.width.toFixed(1)} = ${((m.img.width / vw) * 100).toFixed(1)}% of viewport (>=90%)`)
    log(
      `B3 strip display=${m.strip.display} h=${m.strip.height.toFixed(1)} items=${m.items.length} srcs=${m.items.map((it) => it.src.length > 0).join(',')}`
    )
    log(`B4 initial index title="${m.title}" active=${m.items.findIndex((it) => it.active)}`)
    log(`${b1 ? 'PASS' : 'FAIL'}  B1 full-screen modal`)
    log(`${b2 ? 'PASS' : 'FAIL'}  B2 image fills >= 90% width`)
    log(`${b3 && allSrc ? 'PASS' : 'FAIL'}  B3 bottom strip visible, one item per photo, every item has a src`)
    log(`${b4pre ? 'PASS' : 'FAIL'}  B4 initial active item is 1/3`)
    assert(b1 && b2 && b3 && allSrc && b4pre, 'mobile layout assertions failed')

    // B4: tapping the third strip item jumps to 3 / 3
    await page.locator('.batch-preview-strip-item').nth(2).click()
    await page.waitForFunction(
      () => (document.querySelector('.modal-head p')?.textContent ?? '').startsWith('3 / 3'),
      null,
      { timeout: 15000 }
    )
    await page.waitForSelector('.batch-preview-stage img:not(.is-busy)', { timeout: 30000 })
    await page.screenshot({ path: path.join(s0, 'mobile-after-jump.png') })
    const after = await page.evaluate(() => ({
      title: document.querySelector('.modal-head p')?.textContent ?? '',
      active: [...document.querySelectorAll('.batch-preview-strip-item')].findIndex((it) =>
        it.classList.contains('active')
      )
    }))
    const b4 = after.active === 2 && after.title.startsWith('3 / 3')
    log(`B4 jump: title="${after.title}" active=${after.active}`)
    log(`${b4 ? 'PASS' : 'FAIL'}  B4 tap strip item jumps to that index`)
    assert(b4, 'strip tap jump failed')
    assert(errors.length === 0, `page/console errors: ${errors.join(' | ')}`)

    report = {
      viewport: { width: vw, height: vh },
      modal: m.modal,
      image: m.img,
      strip: m.strip,
      items: m.items,
      afterTap: after,
      checks: { fullScreen: b1, imageWidth90: b2, strip: b3, srcs: allSrc, initial: b4pre, tapJump: b4 },
      errors,
      pass: b1 && b2 && b3 && allSrc && b4pre && b4 && errors.length === 0
    }
  } finally {
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
    if (report) fs.writeFileSync(jsonPath, JSON.stringify(report, null, 2))
    await app.close().catch(() => undefined)
  }

  log(report.pass ? 'GATE B MOBILE: PASS' : 'GATE B MOBILE: FAIL')
  fs.writeFileSync(logPath, lines.join('\n') + '\n')
  if (!report.pass) process.exit(1)
}

main().catch((e) => {
  log(`FAIL ${e && e.stack ? e.stack : e}`)
  try {
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
  } catch {}
  process.exit(1)
})
