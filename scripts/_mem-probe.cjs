/** Throw-away probe: per-photo peak + cross-photo accumulation of a 50MP batch export. */
const path = require('node:path')
const fs = require('node:fs')
const crypto = require('node:crypto')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

const root = path.resolve(__dirname, '..')
const runDir = path.join(root, '..', '_research', 'mem-probe')
const outDir = path.join(runDir, 'exports')
const profile = path.join(runDir, 'profile')

const N = Number(process.env.PROBE_N || 3)
const W = 8192
const H = 6144

async function makeFixtures() {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(outDir, { recursive: true })
  fs.mkdirSync(profile, { recursive: true })
  const base = path.join(runDir, '50mp-0.jpg')
  const pixels = Buffer.alloc(W * H * 3)
  crypto.randomFillSync(pixels)
  await sharp(pixels, { raw: { width: W, height: H, channels: 3 } })
    .blur(0.6)
    .jpeg({ quality: 92 })
    .toFile(base)
  const files = [base]
  for (let i = 1; i < N; i++) {
    const f = path.join(runDir, `50mp-${i}.jpg`)
    fs.copyFileSync(base, f)
    files.push(f)
  }
  console.log(`fixtures: ${files.length} x ${W}x${H}, ${(fs.statSync(base).size / 1048576).toFixed(1)} MB each`)
  return files
}

async function metrics(app) {
  return app.evaluate(({ app: eapp }) =>
    eapp.getAppMetrics().map((p) => ({
      type: p.type,
      pid: p.pid,
      ws: p.memory.workingSetSize,
      peak: p.memory.peakWorkingSetSize
    }))
  )
}

const mb = (kb) => (kb / 1024).toFixed(0)

async function main() {
  const files = await makeFixtures()

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
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message))
  await page.waitForSelector('.app-shell.ready', { timeout: 30000 })

  const t0 = Date.now()
  await page.setInputFiles('#framemark-file-input', files)
  await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, N, { timeout: 300000 })
  console.log(`import ${N}x50MP: ${((Date.now() - t0) / 1000).toFixed(1)}s`)

  const before = await metrics(app)
  console.log('baseline:', JSON.stringify(before.filter((p) => p.type === 'Tab')))

  await app.evaluate(async ({ dialog }, dir) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
  }, outDir)

  await page.locator('.topbar-actions .btn-primary').click()
  await page.waitForSelector('.modal .export-dialog', { timeout: 15000 })
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, outDir, {
    timeout: 20000
  })
  const scope = page.locator('.export-dialog label.field', { hasText: '范围' })
  await scope.locator('button', { hasText: '全部' }).click()

  let sampling = true
  const series = []
  const poll = (async () => {
    while (sampling) {
      try {
        const m = (await metrics(app)).find((p) => p.type === 'Tab')
        if (m) series.push({ t: Date.now() - t0, ws: m.ws })
      } catch {
        /* app may be mid-teardown */
      }
      await new Promise((r) => setTimeout(r, 120))
    }
  })()

  const tExp = Date.now()
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 20000 })
  await page.waitForSelector('.export-card', { timeout: 120000 })
  await page.waitForFunction(
    () => {
      const h = document.querySelector('.export-card h3')
      return h && !h.textContent?.includes('正在导出')
    },
    null,
    { timeout: 1800000 }
  )
  sampling = false
  await poll
  const elapsed = (Date.now() - tExp) / 1000

  const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  console.log(`export ${N} photos: ${elapsed.toFixed(1)}s (${(elapsed / N).toFixed(1)}s/photo)`)
  console.log('status:', status)

  const peak = series.reduce((a, s) => (s.ws > a.ws ? s : a), { ws: 0, t: 0 })
  const baseWs = before.find((p) => p.type === 'Tab')?.ws || 0
  console.log(`renderer baseline: ${mb(baseWs)} MB`)
  console.log(`renderer peak:     ${mb(peak.ws)} MB  (+${mb(peak.ws - baseWs)} MB) at t=${(peak.t / 1000).toFixed(1)}s`)
  console.log(`samples: ${series.length}, tail: ${series.slice(-5).map((s) => mb(s.ws)).join('/')} MB`)

  const out = fs.readdirSync(outDir)
  for (const f of out) {
    const meta = await sharp(path.join(outDir, f)).metadata()
    console.log(`out ${f}: ${meta.width}x${meta.height} ${(fs.statSync(path.join(outDir, f)).size / 1048576).toFixed(1)} MB`)
  }

  // does memory come back down after the batch?
  await page.waitForTimeout(3000)
  const after = (await metrics(app)).find((p) => p.type === 'Tab')
  console.log(`renderer 3s after batch: ${mb(after.ws)} MB`)

  // GC-lag or real retention? Force a full GC and look again.
  const cdp = await page.context().newCDPSession(page)
  for (let i = 0; i < 4; i++) {
    await cdp.send('HeapProfiler.collectGarbage').catch(() => {})
    await page.waitForTimeout(400)
  }
  const gc = (await metrics(app)).find((p) => p.type === 'Tab')
  console.log(`renderer after forced GC: ${mb(gc.ws)} MB  (peak reported ${mb(gc.peak)} MB)`)
  const early = series.slice(0, 10).map((s) => mb(s.ws)).join('/')
  console.log(`first 10 samples: ${early} MB`)
  fs.writeFileSync(path.join(runDir, 'series.json'), JSON.stringify({ series, files: out }, null, 1))

  await app.close()
}

main().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
