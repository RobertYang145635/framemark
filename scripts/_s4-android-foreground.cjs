/**
 * S4R — Android background export gate, HOME instead of screen-off.
 *
 * Why this exists: the S4 run turned the screen off with KEYCODE_POWER and
 * polluted its own evidence (the device woke itself mid-window and CDP lost the
 * page ~60 s later), so "did the export die at 23 min?" was never answered.
 * This script backgrounds the app with KEYCODE_HOME and samples ONLY from the
 * host (adb), never through CDP, so a dropped devtools socket cannot invalidate
 * the measurement.
 *
 * LIMITATION (also printed in the report): this gate does NOT cover
 * screen-off / Doze throttling. The screen stays on and the device stays in
 * `Awake` for the whole window; nothing here presses POWER.
 *
 * Flow
 *   1. preflight  — device online, POST_NOTIFICATIONS granted, wakefulness
 *                   already Awake (abort instead of waking it: the test must
 *                   not touch the power key).
 *   2. import     — N fixtures injected as real File objects through
 *                   #framemark-file-input over CDP (DataTransfer technique).
 *   3. export     — 导出当前 -> 全部 -> 4096 chip -> 开始导出.
 *   4. HOME       — `input keyevent KEYCODE_HOME` as soon as job.done >= 1.
 *   5. window     — host-side sampling every 30 s for 180 s:
 *                   pidof, dumpsys activity services (foreground), MediaStore
 *                   count (global + Pictures/FrameMark), notification progress.
 *                   A light 6 s poll inside the same loop sharpens the pid and
 *                   throughput curve without touching CDP.
 *   6. after      — service gone / wake lock released / notification removed,
 *                   plus the final overlay text re-read over CDP once the
 *                   window is over (post-window only, clearly labelled).
 *
 * Fixture size note: the brief asked for ≈1600×1067, but `exporter.ts` only
 * DOWNSCALES to `longEdge` (`if (longEdge > 0 && max(W,H) > longEdge)`), so a
 * 1600 px source ignores the 4096 chip and exports in about a second per item;
 * a 15-item batch would end before the first 30 s sample and gates 3/4 could
 * not be observed at all. 4000×2667 fixtures measured 4.4 s/item on this
 * device (`_research/s4/timing.log`), which keeps the batch alive across the
 * sampling grid. Documented as a deviation in _research/handoff/S4R.md.
 *
 * Usage:
 *   node scripts/_s4-android-foreground.cjs                    # 15 × 4000×2667, 180 s
 *   node scripts/_s4-android-foreground.cjs --count=15 --window=180000 --interval=30000
 *   node scripts/_s4-android-foreground.cjs --import-only      # fixtures + import, no export
 */
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')
const L = require('./_s4-lib.cjs')

const arg = (name, fallback) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.split('=').slice(1).join('=') : fallback
}
const flag = (name) => process.argv.includes(`--${name}`)

const RUN = arg('run', 's4r')
const COUNT = Number(arg('count', 15))
const W = Number(arg('w', 4000))
const H = Number(arg('h', 2667))
const WINDOW_MS = Number(arg('window', 180000))
const INTERVAL_MS = Number(arg('interval', 30000))
const DENSE_MS = Number(arg('dense', 6000))
const FINISH_WAIT_MS = Number(arg('finish-wait', 600000))
const IMPORT_ONLY = flag('import-only')
/**
 * --light   control mode: no `content query` at all (it scans 32 k rows in
 *           ~3 s), counts come from `ls`, plus a cgroup-freezer probe. Used to
 *           separate a real background stall from a measurement artefact.
 * --freeze  add the cgroup-freezer probe to the normal (heavy) sampling too.
 * --no-restart  attach to the running app instead of force-stop + start, so the
 *           already imported photo set is reused.
 * --cancel-after-window  cancel the batch over CDP once the window is over, so
 *           a control run does not add another full set of gallery files.
 */
const LIGHT = flag('light')
const FREEZE = LIGHT || flag('freeze')
const NO_RESTART = flag('no-restart')
const SKIP_IMPORT = flag('skip-import')
const CANCEL_AFTER_WINDOW = flag('cancel-after-window')

const OUT = path.resolve(L.ROOT, '_research', 's4r')
const RAW = path.join(OUT, 'raw')
const FIXTURES = path.join(OUT, 'fixtures')

const LOG = []
const RESULT = []
const SAMPLES = []
const DENSE = []
let APP_UID = ''
const SUMMARY = { run: RUN, startedAt: new Date().toISOString(), count: COUNT, fixture: `${W}x${H}`, windowMs: WINDOW_MS, samples: SAMPLES, gates: RESULT }

function log(line) {
  console.log(line)
  LOG.push(line)
}

function save(name, text) {
  fs.mkdirSync(path.dirname(path.join(OUT, name)), { recursive: true })
  fs.writeFileSync(path.join(OUT, name), text)
  return name
}

function safe(fn, fallback = null) {
  try {
    return fn()
  } catch (error) {
    return fallback
  }
}

function gate(name, ok, detail = '') {
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`)
  RESULT.push({ name, ok, detail })
  return ok
}

const secs = (ms) => Number((ms / 1000).toFixed(1))

// ------------------------------------------------------------------ device io

function deviceState() {
  return (safe(() => L.adb(['get-state']), '') || '').trim()
}

function wakefulness() {
  const dump = safe(() => L.sh('dumpsys', 'power'), '') || ''
  return (dump.match(/mWakefulness=(\w+)/) || [])[1] || 'unknown'
}

function topActivity() {
  const dump = safe(() => L.sh('dumpsys', 'activity', 'activities'), '') || ''
  const m = dump.match(/topResumedActivity=ActivityRecord\{\S+ u0 ([^ }]+)/)
  return m ? m[1] : ''
}

function focusLine() {
  const dump = safe(() => L.sh('dumpsys', 'window', 'windows'), '') || ''
  return (dump.split(/\r?\n/).find((l) => /mCurrentFocus=/.test(l)) || '').trim()
}

function serviceInfo(saveName = '') {
  const dump = safe(() => L.serviceDump(), '') || ''
  if (saveName) save(`raw/${saveName}`, `# dumpsys activity services com.framemark.app — ${L.now()}\n${dump}\n`)
  const present = /ExportService/.test(dump)
  const fg = present && /isForeground=true/.test(dump)
  const type = (dump.match(/foregroundServiceType=(\w+)/) || [])[1] || ''
  const createTime = (dump.match(/createTime=([^\n]*)/) || [])[1] || ''
  return { present, fg, type, createTime, bytes: dump.length }
}

/** One `dumpsys notification --noredact` call: parsed progress + raw grep lines. */
function notification(saveName = '') {
  const dump = safe(() => L.notificationDump(), '') || ''
  const lines = dump.split(/\r?\n/)
  const grepLines = []
  for (let i = 0; i < lines.length; i++) {
    if (/framemark/i.test(lines[i])) grepLines.push(...lines.slice(i, i + 3))
  }
  const blocks = dump.split(/NotificationRecord\(/).slice(1).filter((b) => b.includes(`pkg=${L.PKG}`))
  let title = ''
  let text = ''
  for (const b of blocks) {
    const t = (b.match(/android\.title=([^\n]*)/) || [])[1] || ''
    const x = (b.match(/android\.text=([^\n]*)/) || [])[1] || ''
    if (/正在导出/.test(t) || /正在导出/.test(x)) {
      title = t.trim()
      text = x.trim()
      break
    }
  }
  const m = (title + ' ' + text).match(/(\d+)\s*\/\s*(\d+)/)
  if (saveName) {
    const uniq = [...new Set(grepLines)]
    save(`raw/${saveName}`, `# dumpsys notification --noredact | grep -A2 framemark — ${L.now()}\n${uniq.join('\n')}\n`)
  }
  return {
    records: blocks.length,
    exportRecords: title ? 1 : 0,
    title,
    text,
    done: m ? Number(m[1]) : null,
    total: m ? Number(m[2]) : null,
    grep: [...new Set(grepLines)].slice(0, 40)
  }
}

function wakeLocks() {
  return safe(() => L.exportWakeLocks(), []) || []
}

function frameCount() {
  return safe(() => L.mediaFrameCount(), -1)
}

function globalCount() {
  return safe(() => L.mediaCount(), -1)
}

function galleryFiles() {
  const out = safe(() => L.sh('ls', '-1', '/storage/emulated/0/Pictures/FrameMark'), '') || ''
  return out
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => /\.(jpe?g|png|webp)$/i.test(l))
}

/**
 * Android's uid cgroup freezer (the thing ColorOS uses for 应用速冻). A frozen
 * uid stops every thread of the app *and* of its isolated WebView renderer, so
 * the export makes no progress while pidof keeps answering.
 */
function freezeProbe(appUid) {
  const read = (p) => (safe(() => L.sh('cat', p), '') || '').trim()
  const uidFrozen = appUid ? read(`/sys/fs/cgroup/apps/uid_${appUid}/cgroup.freeze`) : ''
  const ps = safe(() => L.sh('ps', '-A', '-o', 'PID,NAME'), '') || ''
  const rendererPids = ps
    .split(/\r?\n/)
    .filter((l) => /sandboxed_process0/.test(l))
    .map((l) => Number((l.trim().split(/\s+/)[0] || '0')))
    .filter(Boolean)
  const rendererPid = rendererPids.length ? Math.max(...rendererPids) : 0
  let rendererUid = ''
  let rendererFrozen = ''
  let rendererCpu = null
  if (rendererPid) {
    const cg = safe(() => L.sh('cat', `/proc/${rendererPid}/cgroup`), '') || ''
    rendererUid = (cg.match(/\/apps\/uid_(\d+)\//) || [])[1] || ''
    if (rendererUid) rendererFrozen = read(`/sys/fs/cgroup/apps/uid_${rendererUid}/cgroup.freeze`)
    const stat = safe(() => L.sh('cat', `/proc/${rendererPid}/stat`), '') || ''
    const parts = stat.trim().split(/\s+/)
    rendererCpu = parts.length > 14 ? Number(parts[13]) + Number(parts[14]) : null
  }
  return { appUid, uidFrozen, rendererPid, rendererUid, rendererFrozen, rendererCpu }
}


function newFiles(before, after) {
  const seen = new Set(before)
  return after.filter((f) => !seen.has(f))
}

/** Full host-only sample. Never touches CDP. */
function fullSample(label, markSec, tWindowStart, rawTag = '') {
  const t = Date.now()
  const pid = safe(() => L.pid(), 0)
  const svc = serviceInfo(rawTag ? `${rawTag}-service.txt` : '')
  const notif = notification(rawTag ? `${rawTag}-notification.txt` : '')
  const locks = wakeLocks()
  // Light mode avoids every `content query` (32 k rows / ~3 s) and counts the
  // files on disk instead; --freeze adds the cgroup probe to the heavy mode.
  const frame = LIGHT ? galleryFiles().length : frameCount()
  const global = LIGHT ? null : globalCount()
  const wake = wakefulness()
  const top = LIGHT ? '' : topActivity()
  const freeze = FREEZE ? freezeProbe(APP_UID) : null
  const sample = {
    label,
    at: new Date(t).toISOString(),
    clock: new Date(t).toTimeString().slice(0, 8),
    elapsedSec: tWindowStart ? secs(t - tWindowStart) : null,
    markSec,
    pid,
    servicePresent: svc.present,
    serviceForeground: svc.fg,
    serviceType: svc.type,
    wakeLocks: locks.length,
    wakeLock: (locks[0] || '').slice(0, 140),
    frame,
    global,
    wakefulness: wake,
    topActivity: top,
    freeze,
    notifRecords: notif.records,
    notifTitle: notif.title,
    notifDone: notif.done,
    notifTotal: notif.total,
    notifGrep: notif.grep
  }
  SAMPLES.push(sample)
  log(
    `${label.padEnd(14)} +${String(sample.elapsedSec ?? 0).padStart(5)}s  pid=${pid}  ` +
      `svc=${svc.present ? (svc.fg ? 'FG' : 'bg') : 'none'}  locks=${locks.length}  ` +
      `files=${frame}  global=${global ?? '-'}  wake=${wake}  ` +
      `${freeze ? `frozen=${freeze.uidFrozen || '?'}  ` : ''}notif="${notif.title || '(none)'}"`
  )
  return sample
}

function lightPoll(tWindowStart) {
  const t = Date.now()
  const pid = safe(() => L.pid(), 0)
  const frame = frameCount()
  const notif = notification('')
  const row = {
    at: new Date(t).toTimeString().slice(0, 8),
    elapsedSec: secs(t - tWindowStart),
    pid,
    frame,
    notifDone: notif.done,
    notifTitle: notif.title
  }
  DENSE.push(row)
  return row
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
        font-size="${Math.round(width / 78)}" fill="#d8d2c4" opacity="0.75">${width} × ${height} · S4R HOME gate</text>
</svg>`
}

async function makeFixtures(prefix, count, width, height) {
  fs.mkdirSync(FIXTURES, { recursive: true })
  const made = []
  for (let i = 0; i < count; i++) {
    const file = path.join(FIXTURES, `${prefix}-${String(i + 1).padStart(3, '0')}.jpg`)
    if (!fs.existsSync(file)) {
      await sharp(Buffer.from(fixtureSvg(i, width, height, prefix))).jpeg({ quality: 88, mozjpeg: true }).toFile(file)
    }
    made.push(file)
  }
  return made
}

// ------------------------------------------------------------------ WebView

async function attach(label) {
  safe(() => L.adb(['forward', '--remove', 'tcp:9222']))
  const { browser, page } = await L.connectOverCdp({ timeoutMs: 90000 })
  await L.waitForApp(page, 90000)
  log(`CDP attached (${label}) url=${page.url()}`)
  return { browser, page }
}

async function startBatch(page) {
  // A finished-but-unclosed overlay (导出完成) covers 导出当前; close it first.
  try {
    const overlay = page.locator('.export-overlay .export-card').first()
    if (await overlay.count()) {
      const running = await page.evaluate(
        () => /取消导出/.test((document.querySelector('.export-overlay .export-card') || {}).innerText || '')
      )
      if (!running) {
        const done = page.locator('.export-overlay button:has-text("完成")').first()
        if (await done.count()) await done.click({ timeout: 5000 })
        await L.sleep(500)
      }
    }
  } catch {
    // overlay is optional
  }
  await L.dismissResumeCard(page)
  const open = page.locator('button:has-text("导出当前")').first()
  await open.waitFor({ state: 'visible', timeout: 30000 })
  await open.click()
  await L.sleep(600)
  const allScope = page.locator('.segmented button:has-text("全部")').first()
  await allScope.waitFor({ state: 'visible', timeout: 20000 })
  await allScope.click()
  await L.sleep(200)
  // 4096 is the biggest chip; it only downscales, so the render size equals the
  // fixture size here — kept so the run matches the previous S4 configuration.
  const chip = page.locator('.toggle-chip:has-text("4096")').first()
  if (await chip.count()) {
    await chip.click()
    await L.sleep(200)
  }
  const start = page.locator('button:has-text("开始导出")').first()
  await start.waitFor({ state: 'visible', timeout: 20000 })
  await start.click()
}

async function readJob(page) {
  try {
    return await L.readJob(page, 8000)
  } catch (error) {
    return { present: false, running: false, done: 0, total: 0, text: `unreadable: ${error.message}` }
  }
}

// ------------------------------------------------------------------ runner

async function main() {
  fs.mkdirSync(RAW, { recursive: true })
  log(`S4R Android HOME-background gate — ${L.now()}`)
  log(`serial=${L.SERIAL} pkg=${L.PKG} run=${RUN} count=${COUNT} fixture=${W}x${H} window=${WINDOW_MS}ms interval=${INTERVAL_MS}ms`)
  log(`LIMITATION: screen stays ON and the device stays Awake — this gate does not cover screen-off/Doze throttling.`)

  // ---------------------------------------------------------------- preflight
  const state = deviceState()
  gate('device online', state === 'device', `adb get-state=${state}`)
  const pkgDump = safe(() => L.sh('dumpsys', 'package', L.PKG), '') || ''
  const notifGranted = /POST_NOTIFICATIONS: granted=true/.test(pkgDump)
  gate('POST_NOTIFICATIONS granted', notifGranted, 'dumpsys package')
  const wake0 = wakefulness()
  if (wake0 !== 'Awake') {
    // Never press POWER: abort instead of waking the device.
    save('ABORT.txt', `device was ${wake0} at start — this run must not touch the power key.\n`)
    log(`ABORT device wakefulness=${wake0}; not touching POWER.`)
    throw new Error(`device not Awake (${wake0}); refusing to press POWER`)
  }
  gate('device already Awake (no POWER pressed)', true, `mWakefulness=${wake0}`)
  const versions = (pkgDump.match(/lastUpdateTime=([^\n]*)/) || [])[1] || ''
  SUMMARY.installedLastUpdate = versions.trim()
  APP_UID = (pkgDump.match(/userId=(\d+)/) || [])[1] || ''
  SUMMARY.appUid = APP_UID
  const whitelist = safe(() => L.sh('dumpsys', 'deviceidle', 'whitelist'), '') || ''
  SUMMARY.dozeWhitelisted = new RegExp(`(^|[,\\s])${L.PKG}(,|$)`, 'm').test(whitelist)
  log(`app uid=${APP_UID} dozeWhitelisted=${SUMMARY.dozeWhitelisted}`)

  if (NO_RESTART) {
    const pidNow = safe(() => L.pid(), 0)
    gate('app already running (--no-restart)', pidNow > 0, `pid=${pidNow}`)
    if (!pidNow) await L.forceStopAndStart()
  } else {
    const startPid = await L.forceStopAndStart()
    gate('app process started', Boolean(startPid), `pid=${startPid}`)
  }

  if (NO_RESTART) safe(() => L.sh('am', 'start', '-n', `${L.PKG}/.MainActivity`))
  const { browser, page } = await attach('main')

  if (SKIP_IMPORT) {
    const thumbs = await L.thumbnailCount(page).catch(() => 0)
    gate('photo set reused (--skip-import)', thumbs >= 1, `thumbs=${thumbs}`)
  } else {
    const fixtures = await makeFixtures(`${RUN}`, COUNT, W, H)
    log(`fixtures: ${fixtures.length} × ${W}x${H} (~${(fs.statSync(fixtures[0]).size / 1024).toFixed(0)} KB each)`)
    await L.injectFixtures(page, fixtures)
    const thumbs = await L.waitForThumbs(page, COUNT, 900000)
    gate(`${COUNT} photos imported`, thumbs >= COUNT, `thumbs=${thumbs}`)
  }

  if (IMPORT_ONLY) {
    log('import-only: stopping here')
    save(`gate-${RUN}.txt`, LOG.join('\n') + '\n')
    save(`gate-${RUN}.json`, JSON.stringify(SUMMARY, null, 2))
    await browser.close().catch(() => undefined)
    return
  }

  const filesBefore = galleryFiles()
  const frameBefore = frameCount()
  const globalBefore = globalCount()
  log(`MediaStore before: global=${globalBefore} Pictures/FrameMark=${frameBefore} (${filesBefore.length} files)`)

  // ---------------------------------------------------------------- export
  const tExport = Date.now()
  await startBatch(page)

  let job = { present: false, running: true, done: 0, total: COUNT, text: '' }
  const firstDeadline = Date.now() + 300000
  while (Date.now() < firstDeadline) {
    job = await readJob(page)
    if (job.done >= 1) break
    await L.sleep(300)
  }
  const firstItemMs = Date.now() - tExport
  const doneAtHome = Math.max(job.done, 0)
  gate('first item exported (job.done >= 1)', doneAtHome >= 1, `done=${doneAtHome}/${COUNT} after ${secs(firstItemMs)}s`)

  const svc0 = serviceInfo('home-service-before.txt')
  const notif0 = notification('home-notification-before.txt')
  gate('ExportService foreground before HOME', svc0.present && svc0.fg, `isForeground=${svc0.fg} type=${svc0.type || 'n/a'}`)
  gate('ongoing notification before HOME', notif0.exportRecords > 0, `"${notif0.title}"`)

  // ---------------------------------------------------------------- HOME
  const beforeHomeTop = topActivity()
  L.sh('input', 'keyevent', 'KEYCODE_HOME')
  await L.sleep(2000)
  const afterHomeTop = topActivity()
  const homeWake = wakefulness()
  const tWindowStart = Date.now()
  SUMMARY.home = { pressedAt: new Date(tWindowStart).toISOString(), topBefore: beforeHomeTop, topAfter: afterHomeTop, wakefulness: homeWake }
  save('raw/home.txt', [
    `# KEYCODE_HOME at ${new Date(tWindowStart).toISOString()}`,
    `topResumedActivity before: ${beforeHomeTop}`,
    `topResumedActivity after : ${afterHomeTop}`,
    `mCurrentFocus after       : ${focusLine()}`,
    `mWakefulness after        : ${homeWake}`,
    ''
  ].join('\n'))
  gate('app backgrounded by KEYCODE_HOME', !/framemark/.test(afterHomeTop), `top=${afterHomeTop}`)
  gate('screen stayed on (no POWER)', homeWake === 'Awake', `mWakefulness=${homeWake}`)

  const sHome = fullSample('HOME', 0, tWindowStart, 'mark000')
  const frameAtHome = sHome.frame
  const pidAtHome = sHome.pid
  SUMMARY.home.frame = frameAtHome
  SUMMARY.home.pid = pidAtHome
  SUMMARY.home.notif = { title: sHome.notifTitle, done: sHome.notifDone, total: sHome.notifTotal }

  // ---------------------------------------------------------------- window
  const marks = []
  for (let s = INTERVAL_MS; s <= WINDOW_MS; s += INTERVAL_MS) marks.push(Math.round(s / 1000))
  let nextMark = 0
  let nextDense = Date.now() + DENSE_MS
  let finishSeenAt = null
  while (Date.now() < tWindowStart + WINDOW_MS) {
    const elapsed = Date.now() - tWindowStart
    if (nextDense && Date.now() >= nextDense && elapsed < WINDOW_MS) {
      const row = lightPoll(tWindowStart)
      if (finishSeenAt === null && row.notifDone === null && DENSE.length > 1) finishSeenAt = row.elapsedSec
      nextDense = Date.now() + DENSE_MS
    }
    if (nextMark < marks.length && elapsed >= marks[nextMark] * 1000) {
      const mark = marks[nextMark++]
      const tag = `mark${String(mark).padStart(3, '0')}`
      const s = fullSample(`+${mark}s`, mark, tWindowStart, tag)
      if (finishSeenAt === null && s.notifDone === null && s.notifTotal === null) finishSeenAt = s.elapsedSec
      if (s.notifRecords === 0 && finishSeenAt === null) finishSeenAt = s.elapsedSec
    }
    await L.sleep(Math.min(500, Math.max(100, tWindowStart + WINDOW_MS - Date.now())))
  }
  const tWindowEnd = Date.now()
  const windowSec = secs(tWindowEnd - tWindowStart)

  save('dense.txt', DENSE.map((r) => `+${String(r.elapsedSec).padStart(6)}s  pid=${r.pid}  frame=${r.frame}  notif=${r.notifDone ?? '-'}  "${r.notifTitle || ''}"`).join('\n') + '\n')

  // ---------------------------------------------------------------- gates 1-3
  const frameAtEnd = frameCount()
  const globalAtEnd = globalCount()
  const filesAfter = galleryFiles()
  const added = newFiles(filesBefore, filesAfter)
  const windowAdded = added
  SUMMARY.window = {
    start: new Date(tWindowStart).toISOString(),
    end: new Date(tWindowEnd).toISOString(),
    seconds: windowSec,
    frameAtHome,
    frameAtEnd,
    frameDelta: frameAtEnd - frameAtHome,
    globalBefore,
    globalAtEnd,
    globalDelta: globalAtEnd - globalBefore,
    filesAtHome: filesBefore.length,
    filesAtEnd: filesAfter.length,
    addedDuringRun: windowAdded,
    batchFinishedAtSec: finishSeenAt
  }
  log(`window ${windowSec}s: Pictures/FrameMark ${frameAtHome} -> ${frameAtEnd} (+${frameAtEnd - frameAtHome}), global ${globalBefore} -> ${globalAtEnd} (+${globalAtEnd - globalBefore})`)

  const gate1 = gate(
    'G1 >=2 new images in Pictures/FrameMark during the 180 s background window',
    frameAtEnd - frameAtHome >= 2,
    `+${frameAtEnd - frameAtHome} (${frameAtHome} -> ${frameAtEnd})`
  )

  const pids = SAMPLES.map((s) => s.pid)
  const densePids = DENSE.map((r) => r.pid)
  const allPids = [...new Set([...pids, ...densePids])]
  const pidStable = allPids.length === 1 && allPids[0] === pidAtHome && pidAtHome > 0
  const gate2 = gate(
    'G2 pid unchanged for the entire window',
    pidStable,
    pidStable ? `pid=${pidAtHome} at ${SAMPLES.length} samples + ${DENSE.length} polls` : `pids seen: ${allPids.join(',')}`
  )
  SUMMARY.pids = { atHome: pidAtHome, samples: pids, densePolls: densePids.length, distinct: allPids }

  // Two consecutive samples whose notification progress moved.
  let advancePair = null
  for (let i = 1; i < SAMPLES.length; i++) {
    const a = SAMPLES[i - 1]
    const b = SAMPLES[i]
    if (a.notifDone != null && b.notifDone != null && b.notifDone > a.notifDone) {
      advancePair = { from: a, to: b }
      break
    }
  }
  const gate3 = gate(
    'G3 notification progress advanced while backgrounded (two consecutive samples)',
    Boolean(advancePair),
    advancePair
      ? `+${advancePair.from.markSec}s "${advancePair.from.notifTitle}" -> +${advancePair.to.markSec}s "${advancePair.to.notifTitle}"`
      : `notif sequence: ${SAMPLES.map((s) => s.notifDone ?? '-').join(',')}`
  )

  // ---------------------------------------------------------------- wait for completion
  const frozenSamples = SAMPLES.filter((s) => s.freeze && s.freeze.uidFrozen === '1')
  SUMMARY.freeze = {
    probed: SAMPLES.filter((s) => s.freeze).length,
    frozenSamples: frozenSamples.length,
    firstFrozenAtSec: frozenSamples.length ? frozenSamples[0].elapsedSec : null,
    renderers: [...new Set(SAMPLES.map((s) => (s.freeze ? s.freeze.rendererPid : 0)).filter(Boolean))]
  }
  if (SUMMARY.freeze.probed) {
    gate(
      'app uid cgroup stayed unfrozen for the whole window',
      frozenSamples.length === 0,
      `${frozenSamples.length}/${SUMMARY.freeze.probed} samples frozen` +
        (frozenSamples.length ? `, first at +${SUMMARY.freeze.firstFrozenAtSec}s` : '')
    )
  }

  if (CANCEL_AFTER_WINDOW) {
    try {
      safe(() => L.sh('am', 'start', '-n', `${L.PKG}/.MainActivity`))
      await L.sleep(2500)
      const again = await attach('cancel')
      const cancelled = await L.cancelExport(again.page)
      log(`cancel-after-window: cancelExport=${cancelled}`)
      await again.browser.close().catch(() => undefined)
    } catch (error) {
      log(`cancel-after-window failed: ${error.message}`)
    }
  }

  let final = null
  const deadline = Date.now() + FINISH_WAIT_MS
  let lastFrame = frameAtEnd
  let stableSince = Date.now()
  while (Date.now() < deadline) {
    const pid = safe(() => L.pid(), 0)
    const svc = serviceInfo('')
    const notif = notification('')
    const locks = wakeLocks()
    const frame = frameCount()
    final = { at: new Date().toISOString(), pid, servicePresent: svc.present, serviceForeground: svc.fg, locks: locks.length, frame, notifRecords: notif.records, notifTitle: notif.title }
    if (frame !== lastFrame) {
      lastFrame = frame
      stableSince = Date.now()
    }
    const done = !svc.present && locks.length === 0 && notif.records === 0
    if (done) break
    await L.sleep(4000)
  }
  const tAfterDone = Date.now()
  SUMMARY.after = final
  save('raw/after-service.txt', `# dumpsys activity services after the batch — ${L.now()}\n${safe(() => L.serviceDump(), '')}\n`)
  save('raw/after-notification.txt', `# dumpsys notification --noredact after the batch — ${L.now()}\n${[...new Set((safe(() => L.notificationDump(), '') || '').split(/\r?\n/).filter((l) => /framemark/i.test(l)))].join('\n')}\n`)

  const gate4a = gate('G4a service gone after completion', Boolean(final) && !final.servicePresent, JSON.stringify({ present: final && final.servicePresent }))
  const gate4b = gate('G4b FrameMark wake lock released', Boolean(final) && final.locks === 0, `locks=${final && final.locks}`)
  const gate4c = gate('G4c ongoing notification removed', Boolean(final) && final.notifRecords === 0, `records=${final && final.notifRecords}`)
  gate('process still alive after the batch', Boolean(final) && final.pid > 0, `pid=${final && final.pid}`)

  // ---------------------------------------------------------------- post-window CDP read
  let postJob = { text: '', running: null }
  try {
    safe(() => L.sh('am', 'start', '-n', `${L.PKG}/.MainActivity`))
    await L.sleep(2500)
    const second = await attach('post-window')
    postJob = await readJob(second.page)
    save('raw/post-window-overlay.txt', `# overlay after re-opening the activity (post-window only)\n${postJob.text}\n`)
    await second.browser.close().catch(() => undefined)
  } catch (error) {
    save('raw/post-window-overlay.txt', `# re-attach failed: ${error.message}\n`)
  }
  SUMMARY.postJob = postJob
  gate('overlay reports 导出完成', /导出完成/.test(postJob.text || ''), (postJob.text || '').slice(0, 80))

  // ---------------------------------------------------------------- throughput
  const bgItems = Math.max(0, frameAtEnd - frameAtHome)
  const bgPerMin = (bgItems / windowSec) * 60
  const fgItems = doneAtHome
  const fgPerMin = firstItemMs > 0 ? (fgItems / (firstItemMs / 1000)) * 60 : null
  SUMMARY.throughput = {
    foreground: { items: fgItems, seconds: secs(firstItemMs), perMinute: fgPerMin == null ? null : Number(fgPerMin.toFixed(1)) },
    background: { items: bgItems, seconds: windowSec, perMinute: Number(bgPerMin.toFixed(1)) }
  }
  const totalSec = secs(tAfterDone - tExport)
  const totalPerMin = COUNT / (totalSec / 60)
  SUMMARY.throughput.wholeBatch = { items: COUNT, seconds: totalSec, perMinute: Number(totalPerMin.toFixed(1)) }
  log(
    `throughput: foreground ${fgItems} items in ${secs(firstItemMs)}s (${fgPerMin == null ? '?' : fgPerMin.toFixed(1)}/min), ` +
      `background ${bgItems} in ${windowSec}s (${bgPerMin.toFixed(1)}/min), batch ${COUNT} in ${totalSec}s (${totalPerMin.toFixed(1)}/min)`
  )

  // ---------------------------------------------------------------- lifecycle evidence
  const events = safe(() => L.sh('logcat', '-b', 'events', '-d', '-t', '4000'), '') || ''
  save(
    'raw/lifecycle-events.txt',
    events
      .split(/\r?\n/)
      .filter((l) => /framemark|am_kill|am_proc_died|lowmemory|lmk|force-stop|ANR/i.test(l))
      .join('\n') + '\n'
  )
  // Only kills that happen once this batch is running count. The install
  // (`am_kill ... due to installPackageLI`) and the harness's own
  // `am force-stop` (`due to from pid ...`) are caused by the test itself and
  // must not be reported as an OS kill.
  const logTime = (line) => {
    const m = line.match(/^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)/)
    if (!m) return 0
    const year = new Date().getFullYear()
    return new Date(`${year}-${m[1]}-${m[2]}T${m[3]}:${m[4]}:${m[5]}.${m[6]}`).getTime()
  }
  const killLines = events.split(/\r?\n/).filter((l) => /am_proc_died|am_kill/.test(l) && /framemark/.test(l))
  const relevantKills = killLines
    .filter((l) => logTime(l) >= tExport - 5000)
    .filter((l) => !/installPackageLI|due to from pid|force-stop|force stop/i.test(l))
  gate('no am_proc_died / am_kill for the app after the batch started', relevantKills.length === 0, relevantKills.length ? relevantKills.slice(0, 3).join(' | ') : `none (harness-caused kills before the batch: ${killLines.length})`)

  const finalAfter = galleryFiles()
  SUMMARY.finalFrameCount = frameCount()
  SUMMARY.addedFiles = newFiles(filesBefore, finalAfter)
  SUMMARY.totalWindow = { home: SUMMARY.home, window: SUMMARY.window }
  SUMMARY.limitation = 'this gate does not cover screen-off/Doze throttling: the screen stayed on and the device stayed Awake for the whole window'
  log(`\nLIMITATION: this gate does not cover screen-off/Doze throttling.`)

  const failed = RESULT.filter((r) => !r.ok)
  log(`\nTOTAL ${RESULT.length}  FAIL ${failed.length}`)
  SUMMARY.finishedAt = new Date().toISOString()
  SUMMARY.failed = failed.length
  save(`gate-${RUN}.txt`, LOG.join('\n') + '\n')
  save(`gate-${RUN}.json`, JSON.stringify(SUMMARY, null, 2))
  save(
    `samples-${RUN}.csv`,
    ['mark,elapsed_s,pid,service,fg,wake_locks,files,global,wakefulness,uid_frozen,renderer_pid,renderer_frozen,renderer_cpu,notif_done,notif_title']
      .concat(
        SAMPLES.map((s) =>
          [
            s.markSec,
            s.elapsedSec,
            s.pid,
            s.servicePresent ? 'yes' : 'no',
            s.serviceForeground ? 'FG' : (s.servicePresent ? 'bg' : '-'),
            s.wakeLocks,
            s.frame,
            s.global ?? '',
            s.wakefulness,
            s.freeze ? s.freeze.uidFrozen : '',
            s.freeze ? s.freeze.rendererPid : '',
            s.freeze ? s.freeze.rendererFrozen : '',
            s.freeze && s.freeze.rendererCpu != null ? s.freeze.rendererCpu : '',
            s.notifDone ?? '',
            `"${(s.notifTitle || '').replace(/"/g, "'")}"`
          ].join(',')
        )
      )
      .join('\n') + '\n'
  )
  if (failed.length) process.exitCode = 1
  await browser.close().catch(() => undefined)
}

main().catch((error) => {
  console.error('FATAL', error)
  try {
    fs.mkdirSync(OUT, { recursive: true })
    fs.writeFileSync(path.join(OUT, `gate-${RUN}.txt`), LOG.join('\n') + `\nFATAL ${error.stack}\n`)
    fs.writeFileSync(path.join(OUT, `gate-${RUN}.json`), JSON.stringify({ ...SUMMARY, fatal: String(error && error.stack) }, null, 2))
  } catch {
    // ignore
  }
  process.exitCode = 1
})
