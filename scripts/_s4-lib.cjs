/**
 * S4 — shared harness for the Android foreground-service gates.
 *
 * Everything device-side is adb (real device 8267fe3e); the WebView is driven
 * over CDP (adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>),
 * because the project path contains `&` and `npx cap` is unusable.
 */
const path = require('node:path')
const fs = require('node:fs')
const { execFileSync } = require('node:child_process')
const { chromium } = require('playwright-core')

const ROOT = path.resolve(__dirname, '..')
const RESEARCH = path.resolve(ROOT, '..', '_research', 's4')
const FIXTURES = path.join(RESEARCH, 'fixtures')
const SERIAL = process.env.S4_SERIAL || '8267fe3e'
const PKG = 'com.framemark.app'
const DEVICE_APK = `/data/app/` // resolved through pm path

const CANDIDATE_ADB = [
  'D:/dsh-home/apk-analysis/tools/platform-tools/adb.exe',
  'D:/dev/android-sdk/platform-tools/adb.exe',
  'adb'
]

let adbPath = null
function adbBin() {
  if (adbPath) return adbPath
  for (const candidate of CANDIDATE_ADB) {
    try {
      execFileSync(candidate, ['version'], { stdio: 'ignore' })
      adbPath = candidate
      return candidate
    } catch {
      // next
    }
  }
  throw new Error('adb not found')
}

function adb(args, opts = {}) {
  const out = execFileSync(adbBin(), ['-s', SERIAL, ...args], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: opts.timeout || 120000,
    stdio: ['ignore', 'pipe', 'pipe']
  })
  return out
}

function sh(...args) {
  return adb(['shell', ...args])
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function now() {
  return new Date().toISOString()
}

function stamp(label, extra = '') {
  const line = `${now()}  ${label}${extra ? '  ' + extra : ''}`
  console.log(line)
  return line
}

function check(results, name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
  results.push({ name, ok, detail })
  return ok
}

// ------------------------------------------------------------------ device

function pid() {
  try {
    const out = sh('pidof', PKG).trim()
    return out ? Number(out.split(/\s+/)[0]) : 0
  } catch {
    return 0
  }
}

async function forceStopAndStart() {
  try {
    sh('am', 'force-stop', PKG)
  } catch {
    // already stopped
  }
  await sleep(1500)
  sh('am', 'start', '-n', `${PKG}/.MainActivity`)
  for (let i = 0; i < 60; i++) {
    if (pid()) return pid()
    await sleep(500)
  }
  return pid()
}

function mediaIds(uri = 'content://media/external/images/media') {
  const out = sh('content', 'query', '--uri', uri, '--projection', '_id')
  return out
    .split(/\r?\n/)
    .filter((line) => /^Row:/.test(line.trim()))
    .map((line) => line.trim())
}

function mediaCount() {
  return mediaIds().length
}

/** Cheap, precise count of what THIS app wrote (MediaStore, FrameMark folder). */
function mediaFrameCount() {
  const out = sh(
    'content',
    'query',
    '--uri',
    'content://media/external/images/media',
    '--projection',
    '_id',
    '--where',
    "\"relative_path='Pictures/FrameMark/'\""
  )
  return out
    .split(String.fromCharCode(10))
    .filter((line) => /^Row:/.test(line.trim())).length
}

function exportedFileCount() {
  try {
    const out = sh('ls', '/storage/emulated/0/Pictures/FrameMark')
    return out.split(/\r?\n/).filter((f) => /\.(jpe?g|png|webp)$/i.test(f.trim())).length
  } catch {
    return 0
  }
}

function serviceDump() {
  try {
    return sh('dumpsys', 'activity', 'services', PKG)
  } catch (e) {
    return `dumpsys-failed: ${e.message}`
  }
}

function serviceForeground() {
  const dump = serviceDump()
  if (!/ExportService/.test(dump)) return { present: false, foreground: false, dump }
  const foreground = /isForeground=true/.test(dump)
  const type = (dump.match(/foregroundServiceType=(\S+)/) || [])[1] || ''
  return { present: true, foreground, type, dump }
}

function notificationDump() {
  try {
    return sh('dumpsys', 'notification', '--noredact')
  } catch (e) {
    return `dumpsys-notification-failed: ${e.message}`
  }
}

function appNotifications() {
  const dump = notificationDump()
  const blocks = dump.split(/NotificationRecord\(/).slice(1)
  const ours = blocks.filter((b) => b.includes(`pkg=${PKG}`))
  return ours.map((b) => {
    const title = (b.match(/android\.title=([^\n]*)/) || [])[1] || ''
    const text = (b.match(/android\.text=([^\n]*)/) || [])[1] || ''
    const channel = (b.match(/channel=([^\n]*)/) || [])[1] || ''
    return { title, text, channel }
  })
}

/**
 * Active wake locks only. `dumpsys power` also prints a history of every lock
 * ever acquired (ACQ/REL lines), which must not be mistaken for a held lock.
 */
function wakeLocks() {
  const dump = sh('dumpsys', 'power')
  const lines = dump.split(/\r?\n/)
  const active = []
  let inSection = false
  for (const line of lines) {
    if (/^\s*(Wake Locks|Restored Wake Locks):/.test(line)) {
      inSection = true
      continue
    }
    if (!inSection) continue
    if (!line.trim()) {
      inSection = false
      continue
    }
    active.push(line.trim())
  }
  return active
}

function exportWakeLocks() {
  return wakeLocks().filter((line) => /FrameMark/.test(line))
}

function wakefulness() {
  const out = sh('dumpsys', 'power')
  return (out.match(/mWakefulness=(\w+)/) || [])[1] || 'unknown'
}

function deviceLocked() {
  const out = sh('dumpsys', 'trust')
  const m = out.match(/\(current\):.*deviceLocked=(\d)/)
  return m ? m[1] : null
}

/** Developer option 保持唤醒: must be OFF or KEYCODE_SLEEP is undone by the OS. */
function stayOnWhilePluggedIn() {
  return sh('settings', 'get', 'global', 'stay_on_while_plugged_in').trim()
}

function setStayOnWhilePluggedIn(value) {
  sh('settings', 'put', 'global', 'stay_on_while_plugged_in', String(value))
  return sh('settings', 'get', 'global', 'stay_on_while_plugged_in').trim()
}

function focusedPackage() {
  const out = sh('dumpsys', 'window', 'windows')
  const line = (out.split(/\r?\n/).find((l) => /mCurrentFocus=/.test(l)) || '').trim()
  return line
}

function screenOn() {
  try {
    sh('input', 'keyevent', 'KEYCODE_WAKEUP')
  } catch {
    // ignore
  }
}

function screencap(name) {
  fs.mkdirSync(RESEARCH, { recursive: true })
  const target = path.join(RESEARCH, name)
  const buf = execFileSync(adbBin(), ['-s', SERIAL, 'exec-out', 'screencap', '-p'], {
    encoding: 'buffer',
    maxBuffer: 64 * 1024 * 1024,
    timeout: 60000
  })
  fs.writeFileSync(target, buf)
  console.log(`screenshot: ${target} (${(buf.length / 1024).toFixed(0)} KB)`)
  return target
}

// --------------------------------------------------------------------- CDP

async function connectOverCdp({ timeoutMs = 60000 } = {}) {
  const appPid = pid()
  if (!appPid) throw new Error('app is not running (pidof empty)')
  adb(['forward', 'tcp:9222', `localabstract:webview_devtools_remote_${appPid}`])
  const deadline = Date.now() + timeoutMs
  let lastError = null
  while (Date.now() < deadline) {
    try {
      const browser = await chromium.connectOverCDP('http://127.0.0.1:9222', { timeout: 15000 })
      const context = browser.contexts()[0]
      const pages = context ? context.pages() : []
      const page = pages.find((p) => /localhost|framemark/i.test(p.url())) || pages[0]
      if (!page) throw new Error('no CDP page')
      page.setDefaultTimeout(60000)
      return { browser, page, appPid }
    } catch (error) {
      lastError = error
      await sleep(1000)
    }
  }
  throw new Error(`CDP connect failed: ${lastError && lastError.message}`)
}

async function evalWithTimeout(page, fn, arg, ms = 8000) {
  let timer
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`evaluate timeout ${ms}ms`)), ms)
  })
  try {
    return await Promise.race([page.evaluate(fn, arg), timeout])
  } finally {
    clearTimeout(timer)
  }
}

/** Inject files as real File objects (bytes read on the PC, built in the page). */
async function injectFixtures(page, files) {
  for (const file of files) {
    const b64 = fs.readFileSync(file).toString('base64')
    const name = path.basename(file)
    await page.evaluate(
      async ({ name, b64 }) => {
        const bin = atob(b64)
        const bytes = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
        const dt = new DataTransfer()
        dt.items.add(new File([bytes], name, { type: 'image/jpeg' }))
        const input = document.getElementById('framemark-file-input')
        Object.defineProperty(input, 'files', { value: dt.files, configurable: true })
        input.dispatchEvent(new Event('change', { bubbles: true }))
      },
      { name, b64 }
    )
    // The importer is async (decode + thumbnail); let it drain before the next
    // dispatch so React never sees 40 in-flight imports at once.
    await sleep(80)
  }
}

async function thumbnailCount(page) {
  return page.evaluate(() => document.querySelectorAll('.thumbs .thumb-main').length)
}

/** The WebView can be attached (CDP) before React mounted: wait for the input. */
async function waitForApp(page, timeoutMs = 60000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const ready = await page.evaluate(
        () => document.readyState === 'complete' && !!document.getElementById('framemark-file-input')
      )
      if (ready) return true
    } catch {
      // navigation in flight
    }
    await sleep(500)
  }
  throw new Error('app DOM never exposed #framemark-file-input')
}

async function waitForThumbs(page, expected, timeoutMs = 240000) {
  const start = Date.now()
  let last = 0
  while (Date.now() - start < timeoutMs) {
    try {
      last = await thumbnailCount(page)
    } catch {
      // page busy importing
    }
    if (last >= expected) return last
    await sleep(500)
  }
  throw new Error(`thumbnails ${last}/${expected} after ${timeoutMs}ms`)
}

async function clickText(page, selector, text) {
  const locator = page.locator(`${selector}:has-text("${text}")`).first()
  await locator.waitFor({ state: 'visible', timeout: 30000 })
  await locator.click()
}

/** The 「上次导到 X/Y」 card is modal; 稍后 hides it for this session. */
async function dismissResumeCard(page) {
  const later = page.locator('.export-overlay button:has-text("稍后")').first()
  try {
    if ((await later.count()) && (await later.isVisible())) {
      await later.click({ timeout: 5000 })
      await sleep(400)
      return true
    }
  } catch {
    // card vanished between count and click
  }
  return false
}

/**
 * Mobile flow: the filmstrip's 批量导出 button is CSS-hidden on the phone
 * layout, so the batch is started from 导出当前 -> 范围「全部 N 张」 -> 开始导出,
 * which is the path a real user takes and lands in the same exportAll().
 */
async function startBatchExport(page) {
  await dismissResumeCard(page)
  const open = page.locator('button:has-text("导出当前")').first()
  await open.waitFor({ state: 'visible', timeout: 30000 })
  await open.click()
  await sleep(500)
  const allScope = page.locator('.segmented button:has-text("全部")').first()
  await allScope.waitFor({ state: 'visible', timeout: 15000 })
  await allScope.click()
  await sleep(200)
  const start = page.locator('button:has-text("开始导出")').first()
  await start.waitFor({ state: 'visible', timeout: 15000 })
  await start.click()
}

/** Progress from the export overlay card: `正在导出` + `3 / 15 · name`. */
async function readJob(page, timeoutMs = 8000) {
  const raw = await evalWithTimeout(
    page,
    () => {
      const card = document.querySelector('.export-overlay .export-card')
      if (!card) return null
      return { text: card.innerText, running: !!(card.querySelector('.spin') || /取消导出/.test(card.innerText)) }
    },
    undefined,
    timeoutMs
  )
  if (!raw) return { present: false, running: false, done: 0, total: 0, text: '' }
  const m = raw.text.match(/(\d+)\s*\/\s*(\d+)/)
  return {
    present: true,
    running: raw.running,
    done: m ? Number(m[1]) : 0,
    total: m ? Number(m[2]) : 0,
    text: raw.text.replace(/\s+/g, ' ').trim()
  }
}

async function cancelExport(page) {
  try {
    await page.locator('button:has-text("取消导出")').first().click({ timeout: 5000 })
    return true
  } catch {
    return false
  }
}

function setLockGrace(ms) {
  const original = sh('settings', 'get', 'secure', 'lock_screen_lock_after_timeout').trim()
  sh('settings', 'put', 'secure', 'lock_screen_lock_after_timeout', String(ms))
  return original
}

function restoreLockGrace(original) {
  try {
    sh('settings', 'put', 'secure', 'lock_screen_lock_after_timeout', original)
    const nowValue = sh('settings', 'get', 'secure', 'lock_screen_lock_after_timeout').trim()
    return nowValue
  } catch (e) {
    return `restore-failed: ${e.message}`
  }
}

function apkOnDevice() {
  const pmPath = sh('pm', 'path', PKG).trim()
  const pathOnDevice = pmPath.replace(/^package:/, '')
  const tmp = path.join(RESEARCH, 'pulled-base.apk')
  adoPull(pathOnDevice, tmp)
  return { pathOnDevice, localCopy: tmp }
}

function adoPull(devicePath, target) {
  execFileSync(adbBin(), ['-s', SERIAL, 'pull', devicePath, target], {
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
    timeout: 180000
  })
}

module.exports = {
  ROOT,
  RESEARCH,
  FIXTURES,
  SERIAL,
  PKG,
  sleep,
  now,
  stamp,
  check,
  adb,
  sh,
  pid,
  forceStopAndStart,
  mediaCount,
  mediaFrameCount,
  mediaIds,
  exportedFileCount,
  serviceDump,
  serviceForeground,
  notificationDump,
  appNotifications,
  wakeLocks,
  exportWakeLocks,
  wakefulness,
  deviceLocked,
  stayOnWhilePluggedIn,
  setStayOnWhilePluggedIn,
  focusedPackage,
  screenOn,
  screencap,
  connectOverCdp,
  evalWithTimeout,
  injectFixtures,
  thumbnailCount,
  waitForApp,
  waitForThumbs,
  clickText,
  startBatchExport,
  dismissResumeCard,
  readJob,
  cancelExport,
  setLockGrace,
  restoreLockGrace,
  apkOnDevice
}
