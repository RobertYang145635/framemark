/**
 * A3 shared harness helpers (new file, prefix `_a3-`).
 *
 * One isolated Electron launch + the handful of UI operations the A3 gates
 * need: folder import through the real dialog IPC, export dialog format /
 * output-dir / start, and waiting for the export card to settle.
 *
 * Nothing here patches application code; dialog.showOpenDialog is stubbed in
 * the main process exactly like scripts/_s3-resume-probe.cjs does.
 */
const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const workspace = path.resolve(root, '..')
const a3Dir = path.join(workspace, '_research', 'a3')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

function cleanDir(dir) {
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(dir, { recursive: true })
  return dir
}

function listFiles(dir) {
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir)
    .filter((f) => {
      try {
        return fs.statSync(path.join(dir, f)).isFile()
      } catch {
        return false
      }
    })
    .sort()
}

async function launch(profile) {
  fs.rmSync(profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
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
  page.setDefaultTimeout(90000)
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e)))
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text())
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
  return { app, page, pageErrors, consoleErrors }
}

async function stubOpenDialog(app, target) {
  await app.evaluate(async ({ dialog }, dir) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [dir] })
  }, target)
}

async function importFolder(page, app, dir, expect = 1) {
  await stubOpenDialog(app, dir)
  await page.getByRole('button', { name: '从文件夹导入' }).click()
  await page.waitForFunction((n) => document.querySelectorAll('.thumb').length === n, expect, {
    timeout: 180000
  })
}

function exportDialog(page) {
  return page.locator('.export-dialog')
}

async function openExportDialog(page) {
  await page.locator('.topbar-actions .btn-primary').click()
  await page.waitForSelector('.export-dialog', { timeout: 15000 })
}

async function closeExportDialog(page) {
  const cancel = page.locator('.export-dialog-foot button', { hasText: '取消' })
  if (await cancel.count()) await cancel.click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 10000 }).catch(() => undefined)
}

async function chooseFormat(page, label) {
  const field = page.locator('.export-dialog label.field', { hasText: '格式' })
  await field.locator('button', { hasText: label, exact: true }).click()
  await page.waitForFunction(
    (l) => {
      const fields = [...document.querySelectorAll('.export-dialog label.field')]
      const sec = fields.find((f) => f.textContent?.includes('格式'))
      const active = sec?.querySelector('.segmented button.active')
      return active?.textContent?.trim() === l
    },
    label,
    { timeout: 10000 }
  )
}

async function activeFormatLabel(page) {
  const field = page.locator('.export-dialog label.field', { hasText: '格式' })
  const active = field.locator('.segmented button.active')
  return (await active.count()) ? ((await active.innerText()) || '').trim() : ''
}

async function ensureOriginalSize(page) {
  const chip = page.locator('.export-long-edge .toggle-chip', { hasText: '原始尺寸' })
  if (await chip.count()) await chip.first().click()
}

async function pickOutDir(app, page, outDir) {
  await stubOpenDialog(app, outDir)
  const input = page.locator('.export-dir-row input')
  if ((await input.inputValue()) === outDir) return
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction(
    (want) => {
      const el = document.querySelector('.export-dir-row input')
      return Boolean(el && el.value === want)
    },
    outDir,
    { timeout: 15000 }
  )
}

async function startExport(page) {
  await page.locator('.export-dialog-foot button', { hasText: '开始导出' }).click()
  await page.waitForSelector('.export-dialog', { state: 'detached', timeout: 15000 })
  await page.waitForSelector('.export-card', { timeout: 30000 })
  await page.waitForFunction(
    () => {
      const h = document.querySelector('.export-card h3')
      return Boolean(h) && !h.textContent?.includes('正在导出')
    },
    null,
    { timeout: 300000 }
  )
  const title = ((await page.locator('.export-card h3').first().innerText()) || '').trim()
  const body = (await page.locator('.export-card').innerText()).replace(/\n+/g, ' | ')
  const errors = await page
    .locator('.export-errors > div')
    .allInnerTexts()
    .catch(() => [])
  return { title, body, errors: errors.map((s) => s.replace(/\n+/g, ' ').trim()) }
}

async function closeExportOverlay(page) {
  const done = page.locator('.export-card button', { hasText: '完成' }).first()
  if (await done.count()) await done.click()
  else {
    const x = page.locator('.export-card button[title="关闭"]').first()
    if (await x.count()) await x.click()
  }
  await page.waitForSelector('.export-card', { state: 'detached', timeout: 15000 }).catch(() => undefined)
}

async function toastText(page) {
  return page
    .locator('.toast')
    .allInnerTexts()
    .then((list) => list.join(' | '))
    .catch(() => '')
}

function writeJson(file, data) {
  ensureDir(path.dirname(file))
  fs.writeFileSync(file, JSON.stringify(data, null, 2))
}

module.exports = {
  root,
  workspace,
  a3Dir,
  sleep,
  ensureDir,
  cleanDir,
  listFiles,
  launch,
  stubOpenDialog,
  importFolder,
  exportDialog,
  openExportDialog,
  closeExportDialog,
  chooseFormat,
  activeFormatLabel,
  ensureOriginalSize,
  pickOutDir,
  startExport,
  closeExportOverlay,
  toastText,
  writeJson
}
