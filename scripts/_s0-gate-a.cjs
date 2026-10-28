/**
 * S0 Gate A — preview/export composition parity on a NO-EXIF photo.
 *
 * Template under test: `paramStack` (variants 极简 01–04), the only renderer that
 * injects `['28mm','F2.8','1/125','ISO 100']` when `env.isPreview` and the photo
 * has no EXIF (templates.ts:1927). Reproduced here through the real UI:
 *   import _research/s0/nonexif.jpg -> 搜索/点击「极简 03」-> 读取预览画布 W/H
 *   -> 导出当前（JPG, 原始尺寸, 隔离输出目录）-> sharp 读导出像素尺寸。
 *
 * Two assertions:
 *   A1 ASPECT  |H/W(preview) - H/W(export)| < 0.002                 (计划原文)
 *   A2 LAYOUT  preview 与 export 顶部右侧「白色参数块」占比差 < 0.1%  (构图本身)
 * A1 alone cannot see the defect: paramStack.layout() depends only on pw/ph, so
 * the canvas aspect never changes with the line count. A2 is the operative check
 * that actually goes red before the fix (preview paints 4 fake lines, export 0).
 *
 * Usage: node scripts/_s0-gate-a.cjs before|after
 * Artifacts in _research/s0/: preview-<tag>.png, preview-stage-<tag>.png,
 * gate-a-<tag>.json, gate-a-<tag>.txt
 */
const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')
const sharp = require('sharp')

const tag = (process.argv[2] || 'run').replace(/[^\w-]/g, '')
const root = path.resolve(__dirname, '..')
const workspace = path.resolve(root, '..')
const s0 = path.join(workspace, '_research', 's0')
const runDir = path.join(s0, `run-gate-a-${tag}`)
const profile = path.join(runDir, 'profile')
const outDir = path.join(runDir, 'exports')
const fixture = path.join(s0, 'nonexif.jpg')
const reportPath = path.join(s0, `gate-a-${tag}.json`)
const logPath = path.join(s0, `gate-a-${tag}.txt`)
const lines = []
const log = (s) => {
  lines.push(s)
  console.log(s)
}

function assert (cond, msg) {
  if (!cond) throw new Error(msg)
}

async function rgbAt (file, w, h) {
  const { data } = await sharp(file)
    .resize(w, h, { fit: 'fill', kernel: 'lanczos3' })
    .removeAlpha()
    .toColourspace('srgb')
    .raw()
    .toBuffer({ resolveWithObject: true })
  return data
}

function collect (px, w, h, region) {
  const out = { white: 0, n: 0 }
  for (let y = region.y0; y < region.y1; y++) {
    for (let x = region.x0; x < region.x1; x++) {
      const i = (y * w + x) * 3
      if (Math.min(px[i], px[i + 1], px[i + 2]) >= 228) out.white++
      out.n++
    }
  }
  return out
}

function diffStats (a, b, w, h, region) {
  let sum = 0
  let n = 0
  let over32 = 0
  for (let y = region.y0; y < region.y1; y++) {
    for (let x = region.x0; x < region.x1; x++) {
      const i = (y * w + x) * 3
      const d = Math.abs(a[i] - b[i]) + Math.abs(a[i + 1] - b[i + 1]) + Math.abs(a[i + 2] - b[i + 2])
      sum += d
      if (d > 96) over32++
      n++
    }
  }
  return { mae: sum / n / 3, over32Pct: over32 / n }
}

async function main () {
  assert(fs.existsSync(fixture), `fixture missing: ${fixture}`)
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(outDir, { recursive: true })
  fs.mkdirSync(profile, { recursive: true })

  const meta = await sharp(fixture).metadata()
  log(`fixture: ${meta.width}x${meta.height} ${meta.format} exif=${meta.exif ? meta.exif.length : 0}`)
  assert(meta.width === 3000 && meta.height === 2000, 'fixture must be 3000x2000')
  assert(!meta.exif, 'fixture must have zero EXIF')

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

  let result = null
  try {
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.setInputFiles('#framemark-file-input', fixture)
    await page.waitForSelector('.thumb', { timeout: 120000 })
    await page.waitForSelector('.canvas-viewport canvas', { timeout: 30000 })

    // pick 极简 03 (simple-3 -> paramStack, corner 'tr', no canvasAspect)
    await page.locator('.border-search input').fill('极简 03')
    const card = page.locator('.tpl-card[title="极简 03"]').first()
    await card.waitFor({ state: 'visible', timeout: 15000 })
    await card.click()
    await page.waitForFunction(
      () => document.querySelector('.current-template h3')?.textContent?.trim() === '极简 03',
      null,
      { timeout: 20000 }
    )
    // stage meta comes from the same setMeta/setPreviewMeta call -> proves previewMeta
    await page.waitForFunction(
      () => (document.querySelector('.stage-meta')?.textContent ?? '').includes('3000 × 2000'),
      null,
      { timeout: 60000 }
    )
    await page.waitForFunction(
      () => {
        const c = document.querySelector('.canvas-viewport canvas')
        return c && c.width === 1800 && c.height === 1200
      },
      null,
      { timeout: 30000 }
    )
    await page.waitForTimeout(500)
    const preview = await page.evaluate(() => {
      const c = document.querySelector('.canvas-viewport canvas')
      return {
        W: c.width,
        H: c.height,
        dataUrl: c.toDataURL('image/png'),
        stageMeta: document.querySelector('.stage-meta')?.textContent ?? '',
        variant: document.querySelector('.current-template h3')?.textContent ?? '',
        outFoot: document.querySelector('.inspector-foot strong')?.textContent ?? ''
      }
    })
    fs.writeFileSync(path.join(s0, `preview-${tag}.png`), Buffer.from(preview.dataUrl.split(',')[1], 'base64'))
    await page.locator('.canvas-frame').screenshot({ path: path.join(s0, `preview-stage-${tag}.png`) })
    log(`preview: canvas ${preview.W}x${preview.H} · stageMeta="${preview.stageMeta}" · outFoot="${preview.outFoot}"`)

    // ---- export the same photo at full resolution through the real dialog ----
    await app.evaluate(async ({ dialog }, dir) => {
      dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
    }, outDir)
    await page.locator('.topbar-actions .btn-primary').click()
    await page.waitForSelector('.modal .export-dialog', { timeout: 10000 })
    await page.locator('.export-dir-row button').click()
    await page.waitForFunction((d) => document.querySelector('.export-dir-row input')?.value === d, outDir, { timeout: 15000 })
    const formatField = page.locator('.export-dialog label.field', { hasText: '格式' })
    await formatField.locator('button', { hasText: 'JPG', exact: true }).click()
    await page.locator('.export-long-edge .toggle-chip', { hasText: '原始尺寸' }).click()
    await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
    await page.waitForSelector('.export-card', { timeout: 30000 })
    await page.waitForFunction(() => {
      const h = document.querySelector('.export-card h3')
      return h && !h.textContent?.includes('正在导出')
    }, null, { timeout: 240000 })
    const status = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
    assert(status.includes('导出完成'), `export failed: ${status}`)
    const files = fs.readdirSync(outDir).filter((f) => /\.jpe?g$/i.test(f))
    assert(files.length === 1, `expected 1 jpg, got ${JSON.stringify(files)}`)
    const exportPath = path.join(outDir, files[0])
    const exportMeta = await sharp(exportPath).metadata()
    log(`export: ${files[0]} ${exportMeta.width}x${exportMeta.height} ${exportMeta.format}`)

    // ---- A1: aspect parity (plan's literal assert) ----
    const previewRatio = preview.H / preview.W
    const exportRatio = exportMeta.height / exportMeta.width
    const ratioDiff = Math.abs(previewRatio - exportRatio)

    // ---- A2: composition parity at a common 900x600 canvas ----
    const CW = 900
    const CH = 600
    const pv = await rgbAt(path.join(s0, `preview-${tag}.png`), CW, CH)
    const ex = await rgbAt(exportPath, CW, CH)
    const region = { x0: Math.floor(CW * 0.52), y0: 0, x1: CW, y1: Math.floor(CH * 0.5) }
    const pvWhite = collect(pv, CW, CH, region)
    const exWhite = collect(ex, CW, CH, region)
    const whitePctP = pvWhite.white / pvWhite.n
    const whitePctE = exWhite.white / exWhite.n
    const whiteDelta = Math.abs(whitePctP - whitePctE)
    const ds = diffStats(pv, ex, CW, CH, region)
    log(
      `A1 aspect: preview H/W=${previewRatio.toFixed(6)} export H/W=${exportRatio.toFixed(6)} diff=${ratioDiff.toFixed(6)}`
    )
    log(
      `A2 layout: white pixels in top-right region preview=${(whitePctP * 100).toFixed(3)}% export=${(whitePctE * 100).toFixed(3)}% delta=${(whiteDelta * 100).toFixed(3)}pp · region MAE=${ds.mae.toFixed(2)} · pixels>32/chan=${(ds.over32Pct * 100).toFixed(3)}%`
    )

    const passAspect = ratioDiff < 0.002
    const passLayout = whiteDelta < 0.001 && ds.mae < 4
    log(`${passAspect ? 'PASS' : 'FAIL'}  A1 aspect parity (|ΔH/W| < 0.002)`)
    log(`${passLayout ? 'PASS' : 'FAIL'}  A2 composition parity (white block Δ < 0.1pp, region MAE < 4)`)

    result = {
      tag,
      fixture: { path: fixture, ...meta, exifBytes: meta.exif ? meta.exif.length : 0 },
      preview: { W: preview.W, H: preview.H, stageMeta: preview.stageMeta, outFoot: preview.outFoot },
      export: { file: files[0], width: exportMeta.width, height: exportMeta.height, format: exportMeta.format },
      aspect: { previewRatio, exportRatio, diff: ratioDiff, pass: passAspect },
      layout: {
        canvas: `${CW}x${CH}`,
        region: { name: 'top-right 48%x50%', ...region },
        whitePctPreview: whitePctP,
        whitePctExport: whitePctE,
        whiteDelta,
        regionMae: ds.mae,
        over32Pct: ds.over32Pct,
        pass: passLayout
      },
      errors,
      pass: passAspect && passLayout && errors.length === 0
    }
    assert(errors.length === 0, `page/console errors: ${errors.join(' | ')}`)
  } finally {
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
    if (result) fs.writeFileSync(reportPath, JSON.stringify(result, null, 2))
    await app.close().catch(() => undefined)
  }

  log(result.pass ? `GATE A: PASS (${tag})` : `GATE A: FAIL (${tag})`)
  fs.writeFileSync(logPath, lines.join('\n') + '\n')
  if (!result.pass) process.exit(1)
}

main().catch((e) => {
  log(`FAIL ${e && e.stack ? e.stack : e}`)
  try {
    fs.writeFileSync(logPath, lines.join('\n') + '\n')
  } catch {}
  process.exit(1)
})
