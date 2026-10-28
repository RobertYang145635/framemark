/**
 * S4 gate 2 + 3 — notification permission, and the export foreground service
 * is really foreground while a batch runs on the device.
 *
 * Gate 2: POST_NOTIFICATIONS granted. `adb shell pm grant` is rejected by this
 * ColorOS build (shell lacks GRANT_RUNTIME_PERMISSIONS); the permission is
 * granted once through the OEM notification settings screen and verified here.
 *
 * Gate 3: import 15 JPEG fixtures over CDP, start 批量导出, then assert
 *   - `dumpsys activity services com.framemark.app` lists ExportService with
 *     isForeground=true (and foregroundServiceType dataSync),
 *   - a notification for the app is posted with 正在导出 … in it,
 *   - a PARTIAL_WAKE_LOCK owned by FrameMark is held,
 *   - the notification tracks progress (X/15 changes),
 *   - 取消导出 tears everything down (service gone, wake lock gone, notif gone).
 *
 * Usage: node scripts/_s4-gate-service.cjs
 * Exit 0 = green.
 */
const path = require('node:path')
const fs = require('node:fs')
const L = require('./_s4-lib.cjs')

const RESULTS = []
const REPORT = path.join(L.RESEARCH, 'gate3-service.txt')

async function main() {
  const lines = []
  const log = (s) => {
    console.log(s)
    lines.push(s)
  }
  const record = (name, ok, detail) => {
    log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
    RESULTS.push({ name, ok, detail })
  }

  log(`S4 gate 2/3 — ${L.now()}`)
  log(`serial=${L.SERIAL} pkg=${L.PKG}`)

  // ---------------------------------------------------------------- gate 2
  const pkgDump = L.sh('dumpsys', 'package', L.PKG)
  const granted = /POST_NOTIFICATIONS: granted=(true|false)/.exec(pkgDump)
  record('gate2 POST_NOTIFICATIONS granted', granted && granted[1] === 'true', granted ? granted[0] : 'not found')
  const perms = ['FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_DATA_SYNC', 'WAKE_LOCK'].map((p) => {
    const m = new RegExp(`${p}: granted=(true|false)`).exec(pkgDump)
    return `${p}=${m ? m[1] : 'missing'}`
  })
  record('gate2 FGS/WAKE_LOCK install permissions', perms.every((p) => p.endsWith('true')), perms.join(' '))

  // ---------------------------------------------------------------- gate 3
  const appPid = await L.forceStopAndStart()
  record('app process started', Boolean(appPid), `pid=${appPid}`)
  const { browser, page } = await L.connectOverCdp()
  await L.waitForApp(page)
  record('CDP attached to WebView', true, `pid=${appPid} url=${page.url()}`)

  const fixtures = fs
    .readdirSync(L.FIXTURES)
    .filter((f) => /^s4-fixture-\d+\.jpg$/.test(f))
    .sort()
    .slice(0, 15)
    .map((f) => path.join(L.FIXTURES, f))
  await L.injectFixtures(page, fixtures)
  const thumbs = await L.waitForThumbs(page, fixtures.length)
  record('15 fixtures imported', thumbs >= fixtures.length, `thumbs=${thumbs}`)
  L.screencap('gate3-imported.png')

  const mediaBefore = L.mediaCount()
  const filesBefore = L.exportedFileCount()
  L.stamp('before-export', `media=${mediaBefore} files=${filesBefore}`)

  const t0 = Date.now()
  await L.startBatchExport(page)

  // Service must be up within a few seconds of 开始导出.
  let foreground = null
  let notif = null
  let locks = []
  const deadline = Date.now() + 25000
  while (Date.now() < deadline) {
    foreground = L.serviceForeground()
    notif = L.appNotifications().find((n) => /正在导出/.test(n.title) || /正在导出/.test(n.text)) || null
    locks = L.exportWakeLocks()
    if (foreground.foreground && notif && locks.length) break
    await L.sleep(500)
  }
  record('ExportService present', Boolean(foreground && foreground.present), foreground ? `type=${foreground.type}` : 'no dump')
  record('ExportService isForeground=true', Boolean(foreground && foreground.foreground), foreground ? `type=${foreground.type}` : '')
  record('notification posted with 正在导出', Boolean(notif), notif ? `title="${notif.title}" text="${notif.text}" channel=${notif.channel}` : 'none')
  record('PARTIAL_WAKE_LOCK held', locks.length > 0, locks.join(' | ').slice(0, 160))

  // Progress must reach the notification too.
  let job = { done: 0, total: 0 }
  const progDeadline = Date.now() + 120000
  while (Date.now() < progDeadline) {
    job = await L.readJob(page).catch(() => ({ done: 0, total: 0, running: true, text: '' }))
    if (job.done >= 3) break
    await L.sleep(500)
  }
  const elapsed = Date.now() - t0
  const notifAtProgress = L.appNotifications().find((n) => /正在导出/.test(n.title)) || null
  record('progress advanced to >= 3 items', job.done >= 3, `done=${job.done}/${job.total} in ${elapsed}ms (~${(elapsed / Math.max(1, job.done)).toFixed(0)}ms/item)`)
  record(
    'notification tracks progress (X/15)',
    Boolean(notifAtProgress && /\/\s*15/.test(notifAtProgress.title + ' ' + notifAtProgress.text)),
    notifAtProgress ? `title="${notifAtProgress.title}" text="${notifAtProgress.text}"` : 'none'
  )
  L.screencap('gate3-exporting.png')

  // ------------------------------------------------------------ clean stop
  const cancelled = await L.cancelExport(page)
  record('取消导出 clicked', cancelled)
  let stopped = false
  let cleanedAt = Date.now() - t0
  const stopDeadline = Date.now() + 40000
  while (Date.now() < stopDeadline) {
    const svc = L.serviceForeground()
    const remainingLock = L.exportWakeLocks().length
    const remainingNotif = L.appNotifications().filter((n) => /正在导出/.test(n.title)).length
    cleanedAt = Date.now() - t0
    if (!svc.present && remainingLock === 0 && remainingNotif === 0) {
      stopped = true
      break
    }
    await L.sleep(500)
  }
  const svcAfter = L.serviceForeground()
  record('service stopped + lock/notification cleared', stopped, `servicePresent=${svcAfter.present} elapsedSinceStart=${cleanedAt}ms`)
  record('service gone after cancel', !svcAfter.present, `present=${svcAfter.present}`)
  record('wake lock released after cancel', L.exportWakeLocks().length === 0, L.exportWakeLocks().join(' | ').slice(0, 160) || 'none')
  record('ongoing notification removed', L.appNotifications().filter((n) => /正在导出/.test(n.title)).length === 0)
  record('overlay shows 已取消', /已取消/.test((await L.readJob(page).catch(() => ({ text: '' }))).text || ''), (await L.readJob(page).catch(() => ({ text: '' }))).text.slice(0, 80))
  L.screencap('gate3-after-cancel.png')
  L.stamp('after-cancel', `elapsedSinceStart=${cleanedAt}ms`)

  log(`media=${mediaBefore}->${L.mediaCount()} files=${filesBefore}->${L.exportedFileCount()}`)
  await browser.close().catch(() => undefined)

  const failed = RESULTS.filter((r) => !r.ok)
  log(`\nTOTAL ${RESULTS.length}  FAIL ${failed.length}`)
  fs.mkdirSync(L.RESEARCH, { recursive: true })
  fs.writeFileSync(REPORT, lines.join('\n') + '\n')
  if (failed.length) process.exitCode = 1
}

main().catch((error) => {
  console.error('FATAL', error)
  try {
    fs.mkdirSync(L.RESEARCH, { recursive: true })
    fs.writeFileSync(REPORT, `FATAL ${new Date().toISOString()} ${error.stack}\n`)
  } catch {
    // ignore
  }
  process.exitCode = 1
})
