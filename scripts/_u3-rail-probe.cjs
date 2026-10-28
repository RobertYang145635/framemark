// Diagnostics: capture the left border library at two widths and dump the scroll topology.
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const runDir = path.join(root, '..', '_research', 'u3')
fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
fs.mkdirSync(runDir, { recursive: true })

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE

;(async () => {
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${path.join(runDir, 'profile')}`],
    cwd: root,
    env,
    timeout: 60000
  })
  const page = await app.firstWindow()
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

  // sample photo so the workspace renders
  const sample = page.locator('button', { hasText: '使用示例照片' })
  if (await sample.count()) {
    await sample.first().click()
    await page.waitForSelector('.workspace', { timeout: 60000 })
  }

  for (const [w, h] of [
    [1280, 800],
    [1000, 800]
  ]) {
    await app.evaluate(({ BrowserWindow }, size) => {
      const win = BrowserWindow.getAllWindows()[0]
      win.setContentSize(size[0], size[1])
    }, [w, h])
    await page.waitForTimeout(1200)
    await page.screenshot({ path: path.join(runDir, `win-${w}.png`) })
    const panel = page.locator('.templates-panel')
    if (await panel.count()) {
      await panel.screenshot({ path: path.join(runDir, `rail-${w}.png`) }).catch(() => {})
    }
    const topology = await page.evaluate(() => {
      const q = (sel) => {
        const el = document.querySelector(sel)
        if (!el) return null
        const cs = getComputedStyle(el)
        const r = el.getBoundingClientRect()
        return {
          box: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
          overflowY: cs.overflowY,
          radius: cs.borderRadius,
          scrollH: el.scrollHeight,
          clientH: el.clientHeight
        }
      }
      return {
        viewport: [innerWidth, innerHeight],
        shell: q('.app-shell'),
        workspace: q('.workspace'),
        sidebar: q('.sidebar.templates-panel'),
        library: q('.border-library'),
        nav: q('.border-category-nav'),
        toggle: q('.border-nav-toggle'),
        chips: q('.border-vendor-chips'),
        scroll: q('.border-scroll'),
        inspector: q('.sidebar.inspector-panel')
      }
    })
    fs.writeFileSync(path.join(runDir, `topology-${w}.json`), JSON.stringify(topology, null, 1))
    console.log(w, JSON.stringify(topology.sidebar), 'scroll=', JSON.stringify(topology.scroll), 'chips=', JSON.stringify(topology.chips))
  }

  await app.close()
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
