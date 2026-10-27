/**
 * FrameMark preset round-trip E2E — migrated to the v4 photo-first shell.
 *
 * Full round-trip kept: import a photo -> watermark tool + upload wm-black.png -> open
 * the preset dialog (topbar 预设 button) -> save -> export JSON -> delete -> import the
 * SAME JSON -> apply -> verify the watermark is restored.
 *
 * The export-JSON still lands in the main-process download dir (`app.getPath('downloads')/
 * FrameMark`); the path is derived from the toast text (`预设已保存到 <path>`), with the
 * old `~/Downloads/FrameMark` join as fallback, and that file is read back for assertions.
 *
 * ISOLATION: throw-away `--user-data-dir` under the run dir (fresh profile per run).
 * Exits non-zero on any failed assertion or any pageerror/console.error.
 */
const os = require('node:os')
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const PROJECT = path.resolve(__dirname, '..')
const FIXTURE = 'D:/border&watermark/_research/test-assets/p1170883.jpg'
const WM_BLACK = 'D:/border&watermark/_research/test-assets/wm-black.png'
const PRESET_NAME = '水印边框验收预设'

async function main() {
  const failures = []
  if (!fs.existsSync(FIXTURE)) throw new Error('missing photo fixture: ' + FIXTURE)
  if (!fs.existsSync(WM_BLACK)) throw new Error('missing watermark fixture: ' + WM_BLACK)

  const runDir = path.join(PROJECT, '..', '_research', 'preset-run')
  const profile = path.join(runDir, 'profile')
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: PROJECT,
    env,
    timeout: 60000
  })

  try {
    const page = await app.firstWindow()
    const errors = []
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`) })
    await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
    await page.waitForSelector('.import-screen', { timeout: 20000 })

    await page.setInputFiles('#framemark-file-input', FIXTURE)
    await page.waitForSelector('.thumb', { timeout: 120000 })
    await page.waitForTimeout(2200)

    await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
    await page.waitForSelector('.inspector-pane input[type=file]', { state: 'attached', timeout: 10000 })
    await page.setInputFiles('.inspector-pane input[type=file]', WM_BLACK)
    await page.waitForSelector('.wm-asset .wm-preview img', { timeout: 30000 })

    const openPresetDialog = async () => {
      await page.locator('.topbar-actions button', { hasText: '预设' }).click()
      await page.waitForSelector('.preset-save-box', { timeout: 10000 })
    }

    await openPresetDialog()
    await page.locator('.preset-save-box input').fill(PRESET_NAME)
    await page.locator('.preset-save-box button', { hasText: '保存当前设计' }).click()
    // savePreset() now closes the dialog itself — reopen before counting/exporting
    await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 15000 })
    await openPresetDialog()
    await page.waitForFunction(() => document.querySelectorAll('.preset-row').length === 1, null, { timeout: 15000 })
    console.log('saved presets', await page.locator('.preset-row').count())

    await page.locator('.modal-foot button', { hasText: '导出预设' }).click()
    let exportedToast = ''
    for (let i = 0; i < 40; i++) {
      await page.waitForTimeout(300)
      exportedToast = await page.locator('.toast').innerText().catch(() => '')
      if (exportedToast.includes('预设已保存到')) break
    }
    console.log('export toast', exportedToast)

    // Derive the download dir from the toast (`预设已保存到 <path>`); fall back like the
    // old script to ~/Downloads/FrameMark when the toast path cannot be parsed.
    let downloadDir = path.join(os.homedir(), 'Downloads', 'FrameMark')
    const toastPath = exportedToast.split('预设已保存到').pop()?.trim() ?? ''
    if (toastPath && fs.existsSync(toastPath)) downloadDir = path.dirname(toastPath)
    const files = fs.existsSync(downloadDir)
      ? fs.readdirSync(downloadDir).filter((f) => f.endsWith('.json')).sort().reverse()
      : []
    console.log('export files', files.slice(0, 3))
    const jsonPath = toastPath && fs.existsSync(toastPath) ? toastPath : path.join(downloadDir, files[0] ?? '')
    if (!fs.existsSync(jsonPath)) throw new Error('exported preset JSON not found: ' + jsonPath)
    const parsed = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))
    const jsonPresetCount = parsed.presets?.length ?? 0
    const jsonHasWm = Boolean(parsed.currentDesign?.watermark?.asset)
    console.log('json presets', jsonPresetCount, 'wm', jsonHasWm)
    if (jsonPresetCount !== 1) failures.push(`exported JSON holds ${jsonPresetCount} presets, expected 1`)
    if (!jsonHasWm) failures.push('exported JSON currentDesign has no watermark asset')

    await page.locator('.modal-foot button', { hasText: '完成' }).click()
    await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 10000 })
    await openPresetDialog()
    await page.locator('.preset-row .icon-btn.danger').click()
    await page.waitForFunction(() => document.querySelectorAll('.preset-row').length === 0, null, { timeout: 15000 })
    console.log('after delete', await page.locator('.preset-row').count())

    await page.setInputFiles('.preset-foot input[type=file]', jsonPath)
    await page.waitForFunction(() => document.querySelector('.toast')?.textContent?.includes('已导入 1 个预设') === true, null, { timeout: 30000 })
    console.log('after import toast', await page.locator('.toast').innerText().catch(() => ''))
    await page.waitForFunction(() => document.querySelectorAll('.preset-row').length === 1, null, { timeout: 15000 })
    const afterImport = await page.locator('.preset-row').count()
    console.log('after import presets', afterImport)
    if (afterImport !== 1) failures.push(`presets after import = ${afterImport}, expected 1`)

    await page.locator('.preset-row button', { hasText: '应用' }).click()
    await page.waitForSelector('.preset-save-box', { state: 'detached', timeout: 15000 })
    await page.waitForFunction((n) => document.querySelector('.toast')?.textContent?.includes(`已应用「${n}」`) === true, PRESET_NAME, { timeout: 15000 })
    await page.waitForTimeout(1200)
    console.log('applied template', (await page.locator('.stage-title').innerText()).replace(/\n+/g, ' | '))

    await page.locator('.inspector-tool-switch button', { hasText: '水印' }).click()
    await page.waitForSelector('.wm-info', { timeout: 15000 })
    const appliedWm = (await page.locator('.wm-info').innerText()).replace(/\n+/g, ' | ')
    console.log('applied wm', appliedWm)
    if (!appliedWm.includes('wm-black.png')) failures.push(`applied watermark not restored: ${appliedWm}`)

    console.log('errors', errors)
    if (errors.length) failures.push(`${errors.length} page/console error(s): ${errors.slice(0, 3).join(' | ')}`)
  } finally {
    await app.close().catch(() => undefined)
  }

  if (failures.length) {
    console.error('preset-e2e FAILED:', failures.join(' | '))
    process.exit(1)
  }
  console.log('preset-e2e OK')
}

main().catch((e) => { console.error(e); process.exit(1) })