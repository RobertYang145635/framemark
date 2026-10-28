/**
 * S4 — Android foreground service for batch export: end-to-end gates on the
 * real device (8267fe3e, OPPO Find X6 Pro / Android 16).
 *
 * Gates (all evidence lands in <repo>/../_research/s4/):
 *   1. service alive  — after 开始导出 `dumpsys activity services` lists
 *      ExportService with isForeground=true, a PARTIAL_WAKE_LOCK is held and a
 *      「正在导出 X/Y」 notification is posted (screenshot in notification.png).
 *   2. lock screen    — with job.done >= 1 press POWER, keep the screen off for
 *      180 s and assert MediaStore gained >= 2 images while locked; the service
 *      must stay foreground and the notification must keep counting.
 *   3. stop           — when the batch ends the notification and the service
 *      are gone and no FrameMark wake lock remains.
 *   4. denied         — POST_NOTIFICATIONS blocked through the app-op: the
 *      export must still run to completion, only the notification is missing.
 *
 * The WebView is driven over CDP (`adb forward tcp:9222
 * localabstract:webview_devtools_remote_<pid>`), files are injected as real
 * File objects through #framemark-file-input.
 *
 * Usage:
 *   node scripts/_s4-android-foreground.cjs --phase=main --run=full \
 *        --big=64 --small=16 --window=180000
 *   node scripts/_s4-android-foreground.cjs --phase=denied --run=denied
 *   node scripts/_s4-android-foreground.cjs            # both phases
 */
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const sharp = require('sharp')
const L = require('./_s4-lib.cjs')

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.split('=').slice(1).join('=') : fallback
}

const PHASE = arg('phase', 'all') // main | denied | all
const RUN = arg('run', 'run')
const BIG = Number(arg('big', 64))
const SMALL = Number(arg('small', 16))
const WINDOW_MS = Number(arg('window', 180000))
const SMALL_W = 1600
const SMALL_H = 1067
const BIG_W = 4000
const BIG_H = 2667

const RESULTS = []
const TELEMETRY = []
const LOG = []
const SUMMARY = { run: RUN, phase: PHASE, startedAt: new Date().toISOString(), results: RESULTS }

function log(line) {
  console.log(line)
  LOG.push(line)
}

function record(name, ok, detail = '') {
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
  RESULTS.push({ name, ok, detail })
  return ok
}

const safe = (fn, fallback = null) => {
  try {
    return fn()
  } catch (error) {
    return fallback
  }
}

// ------------------------------------------------------------------ device io

function serviceState() {
  const dump = safe(() => L.serviceDump(), '') || ''
  const present = /com\.framemark\.app\/\.ExportService/.test(dump) || /ExportService/.test(dump)
  const fg = present && /isForeground=true/.test(dump)
  const type = (dump.match(/foregroundServiceType=(\w+)/) || [])[1] || ''
  return { present, fg, type, dump }
}

/** Our ongoing export notification; the title carries `X/Y · file`. */
function exportNotification() {
  const notes = (safe(() => L.appNotifications(), []) || []).filter(
    (n) => /正在导出/.test(n.title) || /正在导出/.test(n.text)
  )
  if (!notes.length) return { count: 0, title: '', text: '', done: null, total: null }
  const n = notes[0]
  const m = (n.title.match(/(\d+)\s*\/\s*(\d+)/) || n.text.match(/(\d+)\s*\/\s*(\d+)/) || [])
  return {
    count: notes.length,
    title: n.title.trim(),
    text: n.text.trim(),
    done: m[1] != null ? Number(m[1]) : null,
    total: m[2] != null ? Number(m[2]) : null
  }
}

function stayOn() {
  return (safe(() => L.sh('dumpsys', 'power'), '') || '')
}

function powerState() {
  const dump = stayOn()
  return {
    wakefulness: (dump.match(/mWakefulness=(\w+)/) || [])[1] || 'unknown',
    stayOn: (dump.match(/mStayOn=(true|false)/) || [])[1] || '?'
  }
}

function locks() {
  return safe(() => L.exportWakeLocks(), []) || []
}

function mediaGlobal() {
  return safe(() => L.mediaCount(), -1)
}

function mediaFrame() {
  return safe(() => L.mediaFrameCount(), -1)
}

function screenSize() {
  const out = safe(() => L.sh('wm', 'size'), '') || ''
  const m = out.match(/Override size:\s*(\d+)x(\d+)/) || out.match(/Physical size:\s*(\d+)x(\d+)/)
  return m ? { w: Number(m[1]), h: Number(m[2]) } : { w: 1260, h: 2780 }
}

function pressPower() {
  L.sh('input', 'keyevent', 'KEYCODE_POWER')
}

async function wakeAndUnlock() {
  safe(() => L.sh('input', 'keyevent', 'KEYCODE_WAKEUP'))
  await L.sleep(1500)
  try {
    const { w, h } = screenSize()
    safe(() =>
      L.sh(
        'input',
        'swipe',
        String(Math.round(w / 2)),
        String(Math.round(h * 0.78)),
        String(Math.round(w / 2)),
        String(Math.round(h * 0.25)),
        '220'
      )
    )
  } catch {
    // no keyguard
  }
  await L.sleep(1500)
}

function focusLine() {
  const out = safe(() => L.sh('dumpsys', 'window', 'windows'), '') || ''
  return (out.split(/\r?\n/).find((l) => /mCurrentFocus=/.test(l)) || '').trim()
}

async function ensureDeviceAwake() {
  if (powerState().wakefulness === 'Awake') return
  await wakeAndUnlock()
}

// ------------------------------------------------------------------ fixtures

function fixtureSvg(index, width, height, tag) {
  const hue = (index * 41 + 7) % 360
  const hue2 = (hue + 137) % 360
  const band = 55 + (index % 9) * 14
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">
  <defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="hsl(${hue},40%,22%)"/>
      <stop offset="0.55" stop-color="hsl(${hue},36%,44%)"/>
      <stop offset="1" stop-color="hsl(${hue2},28%,20%)"/>
    </linearGradient>
    <radialGradient id="sun" cx="0.7" cy="0.25" r="0.34">
      <stop offset="0" stop-color="hsl(${hue2},68%,86%)" stop-opacity="0.85"/>
      <stop offset="1" stop-color="hsl(${hue2},68%,86%)" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="${width}" height="${height}" fill="url(#sky)"/>
  <rect width="${width}" height="${height}" fill="url(#sun)"/>
  <path d="M0 ${band * 6} L${width * 0.2} ${band * 3} L${width * 0.34} ${band * 5}
           L${width * 0.5} ${band * 2.4} L${width * 0.66} ${band * 4.6}
           L${width * 0.82} ${band * 2.8} L${width} ${band * 5.4} V${height} H0 Z"
        fill="hsl(${hue},26%,12%)" opacity="0.92"/>
  <path d="M0 ${band * 7.2} L${width * 0.24} ${band * 5.6} L${width * 0.44} ${band * 7}
           L${width * 0.64} ${band * 5.1} L${width * 0.86} ${band * 6.8} L${width} ${band * 5.9} V${height} H0 Z"
        fill="hsl(${hue2},22%,9%)" opacity="0.95"/>
  <text x="${Math.round(width * 0.04)}" y="${height - 120}" font-family="sans-serif"
        font-size="${Math.round(width / 40)}" fill="#f2ede1" opacity="0.92">${tag} · ${String(index + 1).padStart(2, '0')}</text>
  <text x="${Math.round(width * 0.04)}" y="${height - 60}" font-family="sans-serif"
        font-size="${Math.round(width / 78)}" fill="#d8d2c4" opacity="0.75">${width} × ${height} · S4 foreground gate</text>
</svg>`
}

async function makeFixtures(prefix, count, width, height) {
  fs.mkdirSync(L.FIXTURES, { recursive: true })
  const made = []
  for (let i = 0; i < count; i++) {
    const file = path.join(L.FIXTURES, `${prefix}-${String(i + 1).padStart(3, '0')}.jpg`)
    if (!fs.existsSync(file)) {
      await sharp(Buffer.from(fixtureSvg(i, width, height, prefix)))
        .jpeg({ quality: 88, mozjpeg: true })
        .toFile(file)
    }
    made.push(file)
  }
  return made
}

// ------------------------------------------------------------------ WebView

async function attach(label) {
  // Drop any stale forward first: the WebView pid changes across restarts.
  safe(() => L.adb(['forward', '--remove', 'tcp:9222']))
  const { browser, page } = await L.connectOverCdp({ timeoutMs: 90000 })
  await L.waitForApp(page, 90000)
  log(`CDP attached (${label}) url=${page.url()}`)
  return { browser, page }
}

async function reattach(page) {
  try {
    await L.evalWithTimeout(page, () => document.title, undefined, 4000)
    return page
  } catch {
    log('CDP target unreachable after wake; re-attaching')
  }
  // The activity may have been backgrounded while the screen was off; a
  // frozen WebView does not answer /json/list. Bring the task to the front
  // first — that resumes the very same WebView, not a reload.
  safe(() => L.sh('am', 'start', '-n', `${L.PKG}/.MainActivity`))
  await L.sleep(2500)
  const { browser, page: fresh } = await attach('after-wake')
  SUMMARY.secondBrowser = true
  return fresh
}

async function startBatch(page, count) {
  await L.dismissResumeCard(page)
  const open = page.locator('button:has-text("导出当前")').first()
  await open.waitFor({ state: 'visible', timeout: 30000 })
  await open.click()
  await L.sleep(600)
  const allScope = page.locator('.segmented button:has-text("全部")').first()
  await allScope.waitFor({ state: 'visible', timeout: 20000 })
  await allScope.click()
  await L.sleep(200)
  // Normalise the render size: 4096 is the biggest chip and keeps 4 MP items
  // slow enough that the batch still runs 3 minutes in.
  const chip = page.locator('.toggle-chip:has-text("4096")').first()
  if (await chip.count()) {
    await chip.click()
    await L.sleep(200)
  }
  const start = page.locator('button:has-text("开始导出")').first()
  await start.waitFor({ state: 'visible', timeout: 20000 })
  await start.click()
  log(`batch started (${count} photos)`)
}

async function readJobSafe(page) {
  try {
    return await L.readJob(page, 6000)
  } catch (error) {
    return { present: false, running: false, done: 0, total: 0, text: `unreadable: ${error.message}` }
  }
}

async function importSet(page, files) {
  await L.injectFixtures(page, files)
  const thumbs = await L.waitForThumbs(page, files.length, 900000)
  return thumbs
}

// ------------------------------------------------------------------ gates

function saveDumpsys(name) {
  const svc = serviceState()
  const notes = safe(() => L.notificationDump(), '') || ''
  const heads = notes
    .split(/NotificationRecord\(/)
    .filter((b) => b.includes(`pkg=${L.PKG}`))
    .map((b) => 'NotificationRecord(' + b.slice(0, 1600))
    .join('\n---\n')
  const body = [
    `# dumpsys activity services ${L.PKG} — ${L.now()}`,
    svc.dump,
    '',
    `# dumpsys notification — our records — ${L.now()}`,
    svc.present ? `present=${svc.present} foreground=${svc.fg} type=${svc.type}` : 'present=false',
    heads || '(no NotificationRecord for the package)'
  ].join('\n')
  fs.mkdirSync(L.RESEARCH, { recursive: true })
  fs.writeFileSync(path.join(L.RESEARCH, name), body)
  log(`saved ${name} (${body.length} bytes)`)
  return svc
}

async function gateMain() {
  log(`\n=== S4 MAIN GATES — ${L.now()} run=${RUN} big=${BIG} window=${WINDOW_MS}ms ===`)

  // --- permission (documented limitation: ColorOS blocks pm grant on shell)
  const pmGrant = safe(() => L.sh('pm', 'grant', L.PKG, 'android.permission.POST_NOTIFICATIONS'), null)
  const pkgDump = safe(() => L.sh('dumpsys', 'package', L.PKG), '') || ''
  const granted = /POST_NOTIFICATIONS: granted=true/.test(pkgDump)
  record('POST_NOTIFICATIONS granted', granted, pmGrant === null ? 'pm grant rejected by ROM; already granted=true' : 'pm grant ok')
  SUMMARY.notifGranted = granted

  const appPid = await L.forceStopAndStart()
  record('app process started', Boolean(appPid), `pid=${appPid}`)
  await ensureDeviceAwake()
  const { browser, page: page0 } = await attach('main')
  let page = page0

  const big = await makeFixtures(`${RUN}-big`, BIG, BIG_W, BIG_H)
  log(`fixtures: ${big.length} × ${BIG_W}x${BIG_H} (~${(fs.statSync(big[0]).size / 1024).toFixed(0)} KB each)`)
  const thumbs = await importSet(page, big)
  record(`${BIG} photos imported`, thumbs >= BIG, `thumbs=${thumbs}`)
  L.screencap('gate1-imported.png')

  const mediaBefore = mediaGlobal()
  const frameBefore = mediaFrame()
  log(`MediaStore before: global=${mediaBefore} Pictures/FrameMark=${frameBefore}`)

  const tStart = Date.now()
  await startBatch(page, BIG)

  // ------------------------------------------------------------- gate 1
  let svc = { present: false, fg: false }
  let notif = { count: 0 }
  let held = []
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    svc = serviceState()
    notif = exportNotification()
    held = locks()
    if (svc.fg && notif.count > 0 && held.length > 0) break
    await L.sleep(500)
  }
  const tService = Date.now()
  record('ExportService present', svc.present, `foregroundServiceType=${svc.type || 'n/a'}`)
  record('ExportService isForeground=true', svc.fg)
  record('ongoing notification posted', notif.count > 0, `"${notif.title}"`)
  record('PARTIAL_WAKE_LOCK held', held.length > 0, (held[0] || '').slice(0, 120))
  saveDumpsys('gate1-service.txt')

  // Notification screenshot: the card only exists inside the shade.
  safe(() => L.sh('cmd', 'statusbar', 'expand-notifications'))
  await L.sleep(1500)
  L.screencap('notification.png')
  safe(() => L.sh('cmd', 'statusbar', 'collapse'))
  await L.sleep(800)
  record('notification screenshot saved', fs.existsSync(path.join(L.RESEARCH, 'notification.png')), 'notification.png')

  // Wait for the first finished item so the lock lands mid-job.
  let job = { done: 0, total: BIG, running: true, text: '' }
  const firstDeadline = Date.now() + 300000
  while (Date.now() < firstDeadline) {
    job = await readJobSafe(page)
    if (job.done >= 1) break
    await L.sleep(400)
  }
  const doneAtLock = Math.max(job.done, exportNotification().done || 0)
  record('first item exported (job.done >= 1)', doneAtLock >= 1, `done=${doneAtLock}/${BIG}`)
  const unlockedSeconds = Math.max(0.001, (Date.now() - tStart) / 1000)
  const unlockedRate = (doneAtLock / unlockedSeconds) * 60
  SUMMARY.unlocked = { items: doneAtLock, seconds: Number(unlockedSeconds.toFixed(1)), perMinute: Number(unlockedRate.toFixed(1)) }
  log(`unlocked throughput: ${doneAtLock} items in ${unlockedSeconds.toFixed(1)}s = ${unlockedRate.toFixed(1)} items/min`)

  // ------------------------------------------------------------- gate 2
  const graceOriginal = safe(() => L.setLockGrace(1800000), '')
  const stayOnOriginal = safe(() => L.stayOnWhilePluggedIn(), '15')
  const stayOnNow = safe(() => L.setStayOnWhilePluggedIn(0), '0')
  SUMMARY.settings = { graceOriginal, stayOnOriginal, stayOnNow }
  log(`lock grace ${graceOriginal} -> 1800000; stay_on ${stayOnOriginal} -> ${stayOnNow}`)

  const beforeLock = {
    t: L.now(),
    global: mediaGlobal(),
    frame: mediaFrame(),
    notif: exportNotification(),
    power: powerState(),
    service: serviceState().fg,
    locks: locks().length,
    focus: focusLine()
  }
  SUMMARY.beforeLock = beforeLock
  log(`before lock: global=${beforeLock.global} frame=${beforeLock.frame} notif="${beforeLock.notif.title}" wake=${beforeLock.power.wakefulness} stayOn=${beforeLock.power.stayOn} svc=${beforeLock.service} locks=${beforeLock.locks}`)
  L.screencap('gate2-before-lock.png')

  // The shade must be closed or POWER only dismisses it.
  safe(() => L.sh('cmd', 'statusbar', 'collapse'))
  await L.sleep(600)
  const tLock = Date.now()
  pressPower()
  await L.sleep(5000)
  const offNow = powerState()
  record('screen is off after POWER', offNow.wakefulness !== 'Awake', `wakefulness=${offNow.wakefulness} stayOn=${offNow.stayOn}`)
  log(`LOCK ${new Date(tLock).toISOString()}`)

  const samples = []
  const timeline = []
  const marks = [60, 120, 180].filter((s) => s * 1000 <= WINDOW_MS).concat([Math.round(WINDOW_MS / 1000)])
  const uniqueMarks = [...new Set(marks)].sort((a, b) => a - b)
  let nextMark = 0
  let firstWake = null
  let wasAwake = false
  while (Date.now() < tLock + WINDOW_MS + 500) {
    const elapsed = Math.round((Date.now() - tLock) / 1000)
    const power = powerState()
    timeline.push({ t: L.now(), elapsed, wakefulness: power.wakefulness, stayOn: power.stayOn })
    if (power.wakefulness === 'Awake' && !wasAwake) {
      wasAwake = true
      if (!firstWake) {
        firstWake = { t: L.now(), elapsed }
        log(`!! screen turned back on at +${elapsed}s`)
        const events = safe(() => L.sh('logcat', '-b', 'events', '-d', '-t', '300'), '') || ''
        fs.writeFileSync(
          path.join(L.RESEARCH, 'gate2-wake-events.txt'),
          events.split(/\r?\n/).filter((l) => /screen|wake|power|notification|resume/i.test(l)).join('\n')
        )
      }
    }
    while (nextMark < uniqueMarks.length && elapsed >= uniqueMarks[nextMark]) {
      const mark = uniqueMarks[nextMark++]
      const svcNow = serviceState()
      const sample = {
        t: L.now(),
        mark,
        power,
        notif: exportNotification(),
        service: svcNow.fg,
        present: svcNow.present,
        locks: locks().length,
        frame: mediaFrame()
      }
      samples.push(sample)
      TELEMETRY.push(sample)
      log(
        `SAMPLE +${mark}s wake=${sample.power.wakefulness} stayOn=${sample.power.stayOn} ` +
          `frame=${sample.frame} notif="${sample.notif.title}" svc=${sample.present ? (sample.service ? 'FG' : 'bg') : 'none'} locks=${sample.locks}`
      )
    }
    await L.sleep(3000)
  }
  const tWindowEnd = Date.now()
  fs.writeFileSync(
    path.join(L.RESEARCH, 'gate2-lock-timeline.txt'),
    timeline.map((p) => `+${String(p.elapsed).padStart(3, '0')}s  ${p.wakefulness}  stayOn=${p.stayOn}`).join('\n') + '\n'
  )
  SUMMARY.lockTimeline = {
    polls: timeline.length,
    awakePolls: timeline.filter((p) => p.wakefulness === 'Awake').length,
    firstWake
  }
  if (!samples.length) {
    const svcNow = serviceState()
    samples.push({ t: L.now(), mark: 0, power: powerState(), notif: exportNotification(), service: svcNow.fg, present: svcNow.present, locks: locks().length, frame: mediaFrame() })
  }

  // Wake, then measure again (MediaStore only settles once awake on some ROMs).
  await wakeAndUnlock()
  await L.sleep(2500)
  const afterWake = powerState()
  record('screen back on', afterWake.wakefulness === 'Awake', `wakefulness=${afterWake.wakefulness}`)
  const lockedNow = safe(() => L.deviceLocked(), '?')
  SUMMARY.deviceLockedAfterWake = lockedNow
  log(`deviceLocked after wake: ${lockedNow}`)
  L.screencap('gate2-after-wake.png')

  const globalAfter = mediaGlobal()
  const frameAfter = mediaFrame()
  const lockSeconds = (tWindowEnd - tLock) / 1000
  const lockedItems = samples[samples.length - 1].notif.done != null && beforeLock.notif.done != null
    ? samples[samples.length - 1].notif.done - beforeLock.notif.done
    : null
  const lockedRate = lockedItems != null ? (lockedItems / lockSeconds) * 60 : null
  const frameDelta = frameAfter - beforeLock.frame
  const globalDelta = globalAfter - beforeLock.global

  SUMMARY.locked = {
    lockAt: new Date(tLock).toISOString(),
    windowSeconds: Number(lockSeconds.toFixed(1)),
    items: lockedItems,
    perMinute: lockedRate != null ? Number(lockedRate.toFixed(1)) : null,
    mediaGlobalBefore: beforeLock.global,
    mediaGlobalAfter: globalAfter,
    mediaGlobalDelta: globalDelta,
    frameBefore: beforeLock.frame,
    frameAfter,
    frameDelta,
    samples
  }
  log(
    `locked ${lockSeconds.toFixed(0)}s: notif +${lockedItems} items (${lockedRate != null ? lockedRate.toFixed(1) : '?'}/min), ` +
      `MediaStore global ${beforeLock.global}->${globalAfter} (+${globalDelta}), Pictures/FrameMark ${beforeLock.frame}->${frameAfter} (+${frameDelta})`
  )

  record('MediaStore gained >= 2 images during the locked window', globalDelta >= 2, `global +${globalDelta}`)
  record('Pictures/FrameMark rows gained >= 2', frameDelta >= 2, `+${frameDelta}`)
  record(
    'screen stayed off for the whole window',
    timeline.every((p) => p.wakefulness !== 'Awake'),
    `${timeline.filter((p) => p.wakefulness === 'Awake').length}/${timeline.length} polls awake` +
      (firstWake ? `, first at +${firstWake.elapsed}s` : '')
  )
  record('service foreground at every locked sample', samples.every((s) => s.service), samples.map((s) => `${s.service ? 'FG' : '!'}`).join(''))
  record('wake lock held at every locked sample', samples.every((s) => s.locks > 0), samples.map((s) => s.locks).join(','))
  record(
    'notification kept counting while locked',
    samples.some((s) => s.notif.done != null && beforeLock.notif.done != null && s.notif.done > beforeLock.notif.done),
    `${beforeLock.notif.done} -> ${samples.map((s) => s.notif.done).join(' -> ')}`
  )
  record('job advanced >= 2 while locked', lockedItems == null ? globalDelta >= 2 : lockedItems >= 2, `notif items +${lockedItems}`)

  // If the screen came back on, keep the evidence that explains why.
  if (samples.some((s) => s.power.wakefulness === 'Awake')) {
    const events = safe(() => L.sh('logcat', '-b', 'events', '-d', '-t', '400'), '') || ''
    fs.writeFileSync(
      path.join(L.RESEARCH, 'gate2-wake-events.txt'),
      events
        .split(/\r?\n/)
        .filter((l) => /screen|wake|power|notification/i.test(l))
        .join('\n')
    )
    log('screen woke during the window; wake events saved to gate2-wake-events.txt')
  }

  // ------------------------------------------------------------- gate 3
  page = await reattach(page)
  let finished = false
  let finalJob = { text: '', running: true }
  const finishDeadline = Date.now() + 15 * 60000
  while (Date.now() < finishDeadline) {
    finalJob = await readJobSafe(page)
    if (!finalJob.running && finalJob.present) {
      finished = true
      break
    }
    await L.sleep(1000)
  }
  const elapsedAfterWake = Math.round((Date.now() - tWindowEnd) / 1000)
  record('batch finished', finished, `+${elapsedAfterWake}s after wake; "${(finalJob.text || '').slice(0, 70)}"`)

  let clean = null
  const cleanDeadline = Date.now() + 30000
  while (Date.now() < cleanDeadline) {
    const s = serviceState()
    const n = exportNotification()
    clean = { present: s.present, fg: s.fg, locks: locks().length, notif: n.count, title: n.title }
    if (!s.present && clean.locks === 0 && clean.notif === 0) break
    await L.sleep(500)
  }
  record('service gone after completion', Boolean(clean) && !clean.present, JSON.stringify(clean))
  record('no FrameMark wake lock after completion', Boolean(clean) && clean.locks === 0, `locks=${clean && clean.locks}`)
  record('ongoing notification removed', Boolean(clean) && clean.notif === 0, `count=${clean && clean.notif}`)
  record('overlay shows 导出完成', /导出完成/.test(finalJob.text || ''), (finalJob.text || '').slice(0, 60))
  L.screencap('gate3-complete.png')
  saveDumpsys('gate3-service-after.txt')

  SUMMARY.afterStop = clean
  SUMMARY.totalElapsedSeconds = Math.round((Date.now() - tStart) / 1000)
  SUMMARY.media = { globalBefore: mediaBefore, globalAfter: mediaGlobal(), frameBefore, frameAfter: mediaFrame() }

  // ------------------------------------------------------------- restore
  const graceRestored = safe(() => L.restoreLockGrace(graceOriginal), '')
  record('lock grace restored', graceRestored === graceOriginal, `${graceOriginal} -> ${graceRestored}`)
  const stayRestored = safe(() => L.setStayOnWhilePluggedIn(stayOnOriginal), '')
  record('stay_on restored', stayRestored === stayOnOriginal, `${stayOnOriginal} -> ${stayRestored}`)

  await browser.close().catch(() => undefined)
  if (SUMMARY.secondBrowser) log('note: a second CDP attachment was needed after wake')
}

async function gateDenied() {
  log(`\n=== S4 GATE 4 — notification permission denied — ${L.now()} ===`)

  // Simulate the user denying POST_NOTIFICATIONS. `pm revoke` is blocked on
  // ColorOS (shell lacks REVOKE_RUNTIME_PERMISSIONS); the app-op is what the
  // permission maps onto, so `appops set ... ignore` is the faithful denial.
  safe(() => L.sh('cmd', 'appops', 'set', L.PKG, 'POST_NOTIFICATION', 'ignore'))
  const appop = safe(() => L.sh('cmd', 'appops', 'get', L.PKG, 'POST_NOTIFICATION'), '') || ''
  const denied = /POST_NOTIFICATION:\s*ignore/.test(appop)
  record('POST_NOTIFICATIONS denied via app-op', denied, appop.split(/\r?\n/).filter(Boolean)[0] || 'unknown')

  const appPid = await L.forceStopAndStart()
  record('app process started with notifications denied', Boolean(appPid), `pid=${appPid}`)
  await ensureDeviceAwake()
  const { browser, page } = await attach('denied')

  const small = await makeFixtures(`${RUN}-small`, SMALL, SMALL_W, SMALL_H)
  log(`fixtures: ${small.length} × ${SMALL_W}x${SMALL_H}`)
  const thumbs = await importSet(page, small)
  record(`${SMALL} photos imported`, thumbs >= SMALL, `thumbs=${thumbs}`)

  const mediaBefore = mediaGlobal()
  const frameBefore = mediaFrame()
  const t0 = Date.now()
  await startBatch(page, SMALL)

  let svcAfterStart = { present: false, fg: false }
  let notif = { count: 0 }
  const deadline = Date.now() + 25000
  while (Date.now() < deadline) {
    svcAfterStart = serviceState()
    notif = exportNotification()
    if (svcAfterStart.present) break
    await L.sleep(500)
  }
  record('ExportService still starts', svcAfterStart.present, `isForeground=${svcAfterStart.fg} type=${svcAfterStart.type || 'n/a'}`)
  record('service is foreground even without the permission', svcAfterStart.fg)
  record('no visible export notification', notif.count === 0, notif.count ? `title="${notif.title}"` : 'none in dumpsys notification')
  record('wake lock still held', locks().length > 0, (locks()[0] || 'none').slice(0, 100))

  let finished = false
  let finalJob = { text: '', running: true }
  const finishDeadline = Date.now() + 8 * 60000
  while (Date.now() < finishDeadline) {
    finalJob = await readJobSafe(page)
    if (!finalJob.running && finalJob.present) {
      finished = true
      break
    }
    await L.sleep(800)
  }
  const elapsed = Math.round((Date.now() - t0) / 1000)
  const frameAfter = mediaFrame()
  const globalAfter = mediaGlobal()
  record('export finished', finished, `${elapsed}s, "${(finalJob.text || '').slice(0, 60)}"`)
  record('files still written to MediaStore', frameAfter - frameBefore >= SMALL, `Pictures/FrameMark +${frameAfter - frameBefore}, global +${globalAfter - mediaBefore}`)

  // The app must not have crashed while posting a notification it may not post.
  const crash = safe(
    () =>
      L.sh('logcat', '-b', 'crash', '-d', '-t', '400')
        .split(/\r?\n/)
        .filter((l) => /com\.framemark\.app/.test(l))
        .join('\n'),
    ''
  ) || ''
  record('no app crash in logcat', !crash.trim(), crash ? crash.slice(0, 200) : 'clean')

  let clean = null
  const cleanDeadline = Date.now() + 25000
  while (Date.now() < cleanDeadline) {
    const s = serviceState()
    const n = exportNotification()
    clean = { present: s.present, locks: locks().length, notif: n.count }
    if (!s.present && clean.locks === 0) break
    await L.sleep(500)
  }
  record('service cleaned up after the denied-permission batch', Boolean(clean) && !clean.present, JSON.stringify(clean))
  L.screencap('gate4-denied-complete.png')

  SUMMARY.denied = {
    appop: appop.trim(),
    serviceStarted: svcAfterStart.present,
    serviceForeground: svcAfterStart.fg,
    notificationCount: notif.count,
    finished,
    seconds: elapsed,
    filesWritten: frameAfter - frameBefore,
    crash: crash || null,
    after: clean
  }

  // Restore the permission state the device had before this phase.
  safe(() => L.sh('cmd', 'appops', 'set', L.PKG, 'POST_NOTIFICATION', 'allow'))
  const restored = safe(() => L.sh('cmd', 'appops', 'get', L.PKG, 'POST_NOTIFICATION'), '') || ''
  record('notification app-op restored to allow', /POST_NOTIFICATION:\s*allow|No operations/.test(restored), restored.split(/\r?\n/).filter(Boolean)[0] || '')

  await browser.close().catch(() => undefined)
}

// ------------------------------------------------------------------ runner

async function main() {
  log(`S4 Android foreground gates — ${L.now()}`)
  log(`serial=${L.SERIAL} pkg=${L.PKG} phase=${PHASE} run=${RUN}`)

  if (PHASE === 'main' || PHASE === 'all') await gateMain()
  if (PHASE === 'denied' || PHASE === 'all') await gateDenied()

  const failed = RESULTS.filter((r) => !r.ok)
  log(`\nTOTAL ${RESULTS.length}  FAIL ${failed.length}`)
  SUMMARY.finishedAt = new Date().toISOString()
  SUMMARY.failed = failed.length
  fs.mkdirSync(L.RESEARCH, { recursive: true })
  fs.writeFileSync(path.join(L.RESEARCH, `gate-${RUN}-${PHASE}.txt`), LOG.join('\n') + '\n')
  fs.writeFileSync(path.join(L.RESEARCH, `gate-${RUN}-${PHASE}.json`), JSON.stringify(SUMMARY, null, 2))
  if (failed.length) process.exitCode = 1
}

main().catch((error) => {
  console.error('FATAL', error)
  try {
    fs.mkdirSync(L.RESEARCH, { recursive: true })
    fs.writeFileSync(path.join(L.RESEARCH, `gate-${RUN}-${PHASE}.txt`), LOG.join('\n') + `\nFATAL ${error.stack}\n`)
    fs.writeFileSync(
      path.join(L.RESEARCH, `gate-${RUN}-${PHASE}.json`),
      JSON.stringify({ ...SUMMARY, fatal: String(error && error.stack) }, null, 2)
    )
  } catch {
    // ignore
  }
  process.exitCode = 1
})
