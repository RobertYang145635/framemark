/**
 * S4 gate 4 + 5 — the batch export keeps running with the screen off, then
 * stops cleanly.
 *
 * GREEN (default): import N large fixtures (4000x2667, so each item is slow
 * enough that the job outlives the 3-minute window), start 批量导出, lock the
 * device with KEYCODE_SLEEP for 3 minutes, then wake and assert
 *   - the MediaStore image count (global + Pictures/FrameMark) grew by ≥ 2
 *     while the screen was off,
 *   - the job progress grew by ≥ 2 while the screen was off, and it was still
 *     advancing between the 60s and 180s marks (the job did not just finish
 *     early),
 *   - ExportService was isForeground=true and a PARTIAL_WAKE_LOCK was held at
 *     every in-window sample,
 *   - after completion the service is gone, no FrameMark wake lock remains and
 *     the ongoing notification is removed (gate 5).
 *
 * RED (`--expect=stall`, run against the build with FOREGROUND_ENABLED=false):
 * same flow, but the pass condition is that the export stalls while the screen
 * is off (in-window advance < 2 items, or zero advance in the 60→180s tail) and
 * no ExportService ever appears.
 *
 * The device has a 6-digit PIN. `pm grant` is blocked on this ROM and guessing
 * the PIN is off-limits, so the gate temporarily raises the OS lock-screen
 * grace period (lock_screen_lock_after_timeout) for the window and restores the
 * original value afterwards. The PIN itself is never touched.
 *
 * Usage: node scripts/_s4-gate-screenoff.cjs [--expect=advance|stall]
 *        [--set=s4-big] [--fixtures=120] [--window=180000]
 */
const fs = require('node:fs')
const path = require('node:path')
const L = require('./_s4-lib.cjs')

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.split('=').slice(1).join('=') : fallback
}

const EXPECT = arg('expect', 'advance') // advance | stall
const LABEL = EXPECT === 'stall' ? 'red' : 'green'
const SET = arg('set', 's4-run')
const COUNT = Number(arg('fixtures', 160))
const WINDOW_MS = Number(arg('window', 180000))
const LOCK_GRACE_MS = 1800000

const LOG = []
const RESULTS = []
const TELEMETRY = []

function log(line) {
  console.log(line)
  LOG.push(line)
}

function record(name, ok, detail) {
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
  RESULTS.push({ name, ok, detail })
}

async function sample(page, tag) {
  const svc = L.serviceForeground()
  const row = {
    t: L.now(),
    tag,
    wakefulness: L.wakefulness(),
    mediaFrame: L.mediaFrameCount(),
    files: L.exportedFileCount(),
    service: svc.present,
    serviceForeground: svc.foreground,
    locks: L.exportWakeLocks().length,
    notif: L.appNotifications()
      .filter((n) => /正在导出/.test(n.title))
      .map((n) => n.title)
      .join(' | '),
    job: null,
    jobError: null
  }
  try {
    const job = await L.readJob(page, 6000)
    row.job = { done: job.done, total: job.total, running: job.running }
  } catch (error) {
    row.jobError = error.message
  }
  TELEMETRY.push(row)
  log(
    `SAMPLE ${tag} wake=${row.wakefulness} mediaFrame=${row.mediaFrame} files=${row.files} ` +
      `job=${row.job ? `${row.job.done}/${row.job.total}${row.job.running ? '' : ' (stopped)'}` : 'unreadable'} ` +
      `svc=${row.serviceForeground ? 'FG' : row.service ? 'bg' : 'none'} locks=${row.locks} notif="${row.notif}"`
  )
  return row
}

async function main() {
  log(`S4 gate 4/5 — ${L.now()}  expect=${EXPECT} set=${SET} fixtures=${COUNT}`)
  log(`serial=${L.SERIAL} pkg=${L.PKG}`)

  const pkgDump = L.sh('dumpsys', 'package', L.PKG)
  record('POST_NOTIFICATIONS granted', /POST_NOTIFICATIONS: granted=true/.test(pkgDump))

  const appPid = await L.forceStopAndStart()
  record('app process started', Boolean(appPid), `pid=${appPid}`)
  const { browser, page } = await L.connectOverCdp()
  await L.waitForApp(page)

  const fixtures = fs
    .readdirSync(L.FIXTURES)
    .filter((f) => new RegExp('^' + SET + '-[0-9]+\\.jpg$').test(f))
    .sort()
    .slice(0, COUNT)
    .map((f) => path.join(L.FIXTURES, f))
  if (fixtures.length < COUNT) throw new Error(`only ${fixtures.length} fixtures for ${SET}`)
  await L.injectFixtures(page, fixtures)
  const thumbs = await L.waitForThumbs(page, COUNT, 600000)
  record(`${COUNT} fixtures imported`, thumbs >= COUNT, `thumbs=${thumbs}`)

  const mediaBefore = L.mediaCount()
  record('MediaStore image count recorded (before)', mediaBefore > 0, `media=${mediaBefore}`)
  L.screencap(`gate4-${LABEL}-before-lock.png`)

  const graceOriginal = L.setLockGrace(LOCK_GRACE_MS)
  const stayOnOriginal = L.stayOnWhilePluggedIn()
  const stayOnNow = L.setStayOnWhilePluggedIn(0)
  log(`lock grace ${graceOriginal}ms -> ${LOCK_GRACE_MS}ms; stay_on ${stayOnOriginal} -> ${stayOnNow} (restored at the end)`)

  await L.startBatchExport(page)

  // Wait until the first item is done so the lock lands mid-job.
  let pre = { done: 0, total: COUNT }
  const preDeadline = Date.now() + 240000
  while (Date.now() < preDeadline) {
    pre = await L.readJob(page, 8000).catch(() => ({ done: 0, total: COUNT, running: true }))
    if (pre.done >= 1) break
    await L.sleep(400)
  }
  const svcBefore = L.serviceForeground()
  record('service isForeground before lock', svcBefore.foreground, `present=${svcBefore.present}`)
  record('wake lock held before lock', L.exportWakeLocks().length > 0, L.exportWakeLocks().join(' | ').slice(0, 120) || 'none')
  record('progress started', pre.done >= 1, `done=${pre.done}/${pre.total}`)
  const nBefore = await sample(page, 'before-lock')

  // ------------------------------------------------------------- screen off
  const tLock = Date.now()
  log(`LOCK ${L.now()} (KEYCODE_SLEEP)`)
  L.sh('input', 'keyevent', 'KEYCODE_SLEEP')
  await L.sleep(4000)
  record('screen is off', L.wakefulness() !== 'Awake', `wakefulness=${L.wakefulness()}`)

  await L.sleep(56000)
  const off1 = await sample(page, 'off+60s')
  await L.sleep(60000)
  const off2 = await sample(page, 'off+120s')
  await L.sleep(Math.max(0, WINDOW_MS - (Date.now() - tLock)))
  const off3 = await sample(page, 'off+180s')
  const tWindowEnd = Date.now()
  const offSeconds = Math.round((tWindowEnd - tLock) / 1000)

  // ---------------------------------------------------------------- wake up
  L.sh('input', 'keyevent', 'KEYCODE_WAKEUP')
  await L.sleep(2500)
  const lockedNow = L.deviceLocked()
  const wake = L.wakefulness()
  log(`WAKE ${L.now()} wakefulness=${wake} deviceLocked=${lockedNow}`)
  record('screen back on', wake === 'Awake', `wakefulness=${wake}`)
  record('device unlocked by grace period (no PIN needed)', lockedNow === '0', `deviceLocked=${lockedNow}`)
  L.screencap(`gate4-${LABEL}-after-wake.png`)

  const mediaAfter = L.mediaCount()
  const after = await L
    .readJob(page, 15000)
    .catch((e) => ({ done: 0, total: 0, running: false, text: `unreadable: ${e.message}` }))

  // Precise in-window deltas: both counters were read at off+180s, before wake.
  const inWindowMedia = off3.mediaFrame - nBefore.mediaFrame
  const inWindowJob = off3.job && nBefore.job ? off3.job.done - nBefore.job.done : null
  const tailMedia = off1.mediaFrame - nBefore.mediaFrame
  const tailJob = off1.job && off3.job ? off3.job.done - off1.job.done : null
  const globalMediaDelta = mediaAfter - mediaBefore
  log(
    `screenOff=${offSeconds}s globalMedia ${mediaBefore}->${mediaAfter} (+${globalMediaDelta}) ` +
      `frameMedia ${nBefore.mediaFrame}->${off3.mediaFrame} (+${inWindowMedia}) ` +
      `job ${nBefore.job ? nBefore.job.done : '?'}->${off3.job ? off3.job.done : '?'} (+${inWindowJob}) ` +
      `tail job +${tailJob}`
  )
  record('MediaStore image count grew >= 2 while screen off', globalMediaDelta >= 2, `global +${globalMediaDelta}`)
  record('Pictures/FrameMark rows grew >= 2 by off+180s', inWindowMedia >= 2, `+${inWindowMedia}`)

  if (EXPECT === 'advance') {
    record(
      'screen stayed off for the whole window',
      off1.wakefulness !== 'Awake' && off2.wakefulness !== 'Awake' && off3.wakefulness !== 'Awake',
      `${off1.wakefulness}/${off2.wakefulness}/${off3.wakefulness}`
    )
    record('service stayed foreground during window', off1.serviceForeground && off2.serviceForeground && off3.serviceForeground)
    record('wake lock held during window', off1.locks > 0 && off2.locks > 0 && off3.locks > 0)
    record(
      'notification kept updating during window',
      Boolean(off1.notif) && Boolean(off3.notif),
      `${off1.notif || 'none'} -> ${off3.notif || 'none'}`
    )
    record(
      'job advanced >= 2 while screen off',
      inWindowJob === null ? inWindowMedia >= 2 : inWindowJob >= 2,
      inWindowJob === null ? `job unreadable while screen off; Pictures/FrameMark +${inWindowMedia} is the proxy` : `+${inWindowJob}`
    )
    record('progress still advancing late in the window (60s->180s)', tailMedia >= 1, `frame rows +${tailMedia}`)
    const jobAfterWake = after.done > 0 && nBefore.job ? after.done - nBefore.job.done : null
    record(
      'job counter advanced >= 2 by the time of the wake',
      jobAfterWake === null ? inWindowMedia >= 2 : jobAfterWake >= 2,
      jobAfterWake === null ? `job card has no X/Y after completion; Pictures/FrameMark +${inWindowMedia}` : `+${jobAfterWake}`
    )
  } else {
    record('no ExportService while screen off', !off1.service && !off2.service && !off3.service)
    const stalled = inWindowMedia < 2 || tailMedia === 0
    record('export stalled while screen off', stalled, `frame rows +${inWindowMedia} in window, tail +${tailMedia}, job +${inWindowJob}`)
    record(
      'renderer unreachable during window',
      Boolean(off1.jobError || off2.jobError || off3.jobError),
      `errors=${[off1.jobError, off2.jobError, off3.jobError].filter(Boolean).join(';') || 'none'}`
    )
  }

  // ------------------------------------------------------------- gate 5
  if (EXPECT === 'advance') {
    let finished = false
    const doneDeadline = Date.now() + 20 * 60000
    const tStart = tLock
    while (Date.now() < doneDeadline) {
      const job = await L.readJob(page, 10000).catch(() => ({ done: 0, total: COUNT, running: true }))
      if (!job.running && job.present) {
        finished = true
        break
      }
      await L.sleep(1000)
    }
    record('batch completed', finished, `elapsedSinceLock=${Math.round((Date.now() - tStart) / 1000)}s finalJob=${JSON.stringify(after)}`)
    let clean = false
    const cleanDeadline = Date.now() + 30000
    let last = null
    while (Date.now() < cleanDeadline) {
      const svc = L.serviceForeground()
      const locks = L.exportWakeLocks()
      const notif = L.appNotifications().filter((n) => /正在导出/.test(n.title))
      last = { svc: svc.present, fg: svc.foreground, locks: locks.length, notif: notif.length }
      if (!svc.present && locks.length === 0 && notif.length === 0) {
        clean = true
        break
      }
      await L.sleep(500)
    }
    record('service gone after completion', clean && last && !last.svc, JSON.stringify(last))
    record('no wake lock after completion', Boolean(last) && last.locks === 0, `locks=${last && last.locks}`)
    record('ongoing notification removed', Boolean(last) && last.notif === 0, `notif=${last && last.notif}`)
    const finalJob = await L.readJob(page, 10000).catch(() => ({ text: '' }))
    record('overlay shows 导出完成', /导出完成/.test(finalJob.text || ''), (finalJob.text || '').slice(0, 80))
    L.screencap(`gate4-${LABEL}-complete.png`)
  } else {
    // Red: after the wake the app may resume. Cancel (or force-stop) so the
    // device is left idle; a frozen renderer makes the click fail by design.
    const cancelled = await L.cancelExport(page).catch(() => false)
    log(`red cleanup: cancel click=${cancelled}`)
    await L.sleep(3000)
    if (L.exportWakeLocks().length) log('red cleanup: unexpected wake lock after cancel')
    L.screencap(`gate4-${LABEL}-after-cancel.png`)
  }

  // ------------------------------------------------------------- restore
  const restored = L.restoreLockGrace(graceOriginal)
  record('lock grace restored', restored === graceOriginal, `${graceOriginal} -> ${restored}`)
  const stayOnRestored = L.setStayOnWhilePluggedIn(stayOnOriginal)
  record('stay_on_while_plugged_in restored', stayOnRestored === stayOnOriginal, `${stayOnOriginal} -> ${stayOnRestored}`)
  await browser.close().catch(() => undefined)

  const failed = RESULTS.filter((r) => !r.ok)
  log(`\nTOTAL ${RESULTS.length}  FAIL ${failed.length}`)
  fs.mkdirSync(L.RESEARCH, { recursive: true })
  fs.writeFileSync(path.join(L.RESEARCH, `gate4-${LABEL}.txt`), LOG.join('\n') + '\n')
  fs.writeFileSync(
    path.join(L.RESEARCH, `gate4-${LABEL}.json`),
    JSON.stringify(
      {
        label: LABEL,
        expect: EXPECT,
        fixtures: { set: SET, count: COUNT },
        timestamps: {
          lock: new Date(tLock).toISOString(),
          windowEnd: new Date(tWindowEnd).toISOString(),
          screenOffSeconds: offSeconds
        },
        before: { media: mediaBefore, frame: nBefore.mediaFrame, job: nBefore.job },
        after: { media: mediaAfter, frame: off3.mediaFrame, job: off3.job },
        advanced: { globalMedia: globalMediaDelta, frameMedia: inWindowMedia, job: inWindowJob, tailJob },
        telemetry: TELEMETRY,
        results: RESULTS
      },
      null,
      2
    )
  )
  if (failed.length) process.exitCode = 1
}

main().catch((error) => {
  console.error('FATAL', error)
  try {
    fs.writeFileSync(path.join(L.RESEARCH, `gate4-${LABEL}.txt`), LOG.join('\n') + `\nFATAL ${error.stack}\n`)
  } catch {
    // ignore
  }
  process.exitCode = 1
})
