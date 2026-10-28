/**
 * S3 gate — resumable + retryable export jobs with a persistent export history
 * (desktop, path-based imports, real process kill).
 *
 * GATE 1 (resume after kill)
 *   launch #1 (profile P) imports 3 fixtures from a folder, starts 批量导出 into
 *   an isolated out dir, and the Electron process is KILLED once the persisted
 *   task holds 1..2 results (job.done >= 1 && < 3). Launch #2 reuses the SAME
 *   profile P, re-imports the same 3 files, the overlay must offer
 *   「上次导到 X/Y，可继续」, 继续 must finish with exactly 3 files, no `(1)`
 *   duplicate, and the task must end `done`.
 *
 * GATE 2 (retry)
 *   Same kill, then the FIRST still-pending fixture is deleted before 继续 so its
 *   source path is missing at export time. The failure must be recorded per task
 *   key, 重试失败项 must appear, and after restoring the file that button must
 *   write exactly that one file (one new file, no `(1)`, errors cleared).
 *
 * GATE 3 (history identity)
 *   Clean 3-item run; export again with the same photos -> all 3 skipped, 0 new
 *   files. Change a design field (长边限制 2048) and export again -> STILL
 *   skipped (identity is photo-based on purpose). 新建任务 must then allow a
 *   real re-export (3 more files, this time with `(1)`).
 *
 * GATE 4 (no regressions, run sequentially AFTER the app closes)
 *   scripts/_s1-undo-probe.cjs, scripts/_s2-undo-ui-probe.cjs,
 *   scripts/_batch-preview-probe.cjs (6/6 PASS lines).
 *
 * RED run (gate 1 must be able to fail): build with the `skip` branch disabled in
 * core/exporter.ts (`if (false && key && skip?.has(key))`). Resume then re-exports
 * the finished item, the out dir gains a `(1)` file and the file count is 4.
 *
 * Usage: node scripts/_s3-resume-probe.cjs [--skip-regressions]
 * Exit 0 = green.
 */
const path = require('node:path')
const fs = require('node:fs')
const { spawnSync } = require('node:child_process')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const research = path.join(root, '..', '_research')
const runDir = path.join(research, 's3', 'run')
const srcFixtures = path.join(research, 'test-assets', 'ratio-study')

const SKIP_REGRESSIONS = process.argv.includes('--skip-regressions')
/** Optional subset for local iteration only, e.g. `--gates=g1` or `--gates=g3`. */
const ONLY = (process.argv.find((a) => a.startsWith('--gates=')) || '').split('=')[1]
const SELECTED = ONLY ? ONLY.split(',') : ['g1', 'g2', 'g3']
const failures = []
let stepNo = 0

function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  if (!ok) failures.push(name)
}
function step(kind, text) {
  stepNo += 1
  console.log(`STEP ${String(stepNo).padStart(2, '0')}  [${kind}]  ${text}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------------------------------------------------
// harness
// ---------------------------------------------------------------------------
async function launch(profile) {
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
  const errors = []
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(`console: ${m.text()}`)
  })
  await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
  return { app, page, errors }
}

async function relaunch(profile) {
  let last = null
  for (let i = 0; i < 3; i++) {
    try {
      return await launch(profile)
    } catch (e) {
      last = e
      await sleep(1500)
    }
  }
  throw last
}

async function stubDialog(app, dir) {
  await app.evaluate(async ({ dialog }, target) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [target] })
  }, dir)
}

async function importFolder(app, page, dir) {
  await stubDialog(app, dir)
  await page.getByRole('button', { name: '从文件夹导入' }).click()
  await page.waitForFunction(() => document.querySelectorAll('.thumb').length === 3, null, { timeout: 120000 })
}

async function openExportDialog(page) {
  await page.getByRole('button', { name: '批量导出' }).click()
  await page.waitForSelector('.modal .export-dialog', { timeout: 15000 })
}

async function pickOutDir(app, page, outDir) {
  await stubDialog(app, outDir)
  const input = page.locator('.export-dir-row input')
  if ((await input.inputValue()) === outDir) return
  await page.locator('.export-dir-row button').click()
  await page.waitForFunction((want) => {
    const el = document.querySelector('.export-dir-row input')
    return Boolean(el && el.value === want)
  }, outDir, { timeout: 15000 })
}

async function startExport(page) {
  await page.locator('.modal-foot .btn-primary').click()
  await page.waitForSelector('.export-card', { timeout: 30000 })
}

/**
 * Slow the renderer so an item takes seconds instead of ~50 ms. Without this the
 * kill would have to land inside a few-ms window (the gate would be a coin flip).
 * This only widens the interval BETWEEN a committed item and the next file write;
 * it does not change any export behaviour.
 */
async function slowRenderer(page, rate = 20) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setCPUThrottlingRate', { rate })
}

async function progress(page) {
  const txt = await page.locator('.export-card-head p').first().textContent().catch(() => '')
  const m = /(\d+)\s*\/\s*(\d+)/.exec(txt || '')
  return m ? { done: Number(m[1]), total: Number(m[2]) } : null
}

/**
 * Wait for a run to settle by watching the PERSISTED row, not the card title:
 * the card can still show the previous terminal title for a moment, which made
 * an earlier version of this probe read "done" while the run had just started.
 */
async function waitTaskUpdate(page, id, prevUpdatedAt) {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    const jobs = await readJobs(page)
    const t = jobs.find((j) => j.id === id)
    if (t && t.status !== 'running' && t.updatedAt !== prevUpdatedAt) {
      await sleep(400) // let the last setState paint the terminal card
      return t
    }
    await sleep(120)
  }
  throw new Error(`task ${id} never settled`)
}

/** Same, for a run that starts a NEW task (gate 3 re-exports). */
async function waitNewTask(page, prevId) {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    const jobs = await readJobs(page)
    const t = jobs.sort((a, b) => b.createdAt - a.createdAt)[0]
    if (t && t.id !== prevId && t.status !== 'running') {
      await sleep(400)
      return t
    }
    await sleep(120)
  }
  throw new Error('no new task ever settled')
}

function outFiles(dir) {
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir)
    .filter((f) => !f.startsWith('.'))
    .sort()
}

/** Read the persisted history straight out of the app's IndexedDB. */
function readJobs(page) {
  return page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const req = indexedDB.open('framemark')
        req.onerror = () => reject(new Error(String(req.error)))
        req.onsuccess = () => {
          const db = req.result
          if (!db.objectStoreNames.contains('jobs')) {
            db.close()
            resolve([])
            return
          }
          const tx = db.transaction('jobs', 'readonly')
          const q = tx.objectStore('jobs').getAll()
          q.onsuccess = () => {
            db.close()
            resolve(q.result || [])
          }
          q.onerror = () => {
            db.close()
            reject(q.error)
          }
        }
      })
  )
}

async function newestTask(page) {
  const jobs = await readJobs(page)
  return jobs.sort((a, b) => b.createdAt - a.createdAt)[0] || null
}

/**
 * Wait until the run has persisted 1..2 results and the UI agrees with the row,
 * then hard-kill the Electron process (a real crash, not app.quit()).
 */
async function killMidJob(app, page, outDir) {
  const deadline = Date.now() + 240000
  let snap = null
  while (Date.now() < deadline) {
    const prog = await progress(page)
    const task = await newestTask(page)
    const persisted = task ? Object.keys(task.results || {}).length : 0
    // Only kill on a committed boundary: row, UI and the out dir all agree. Killing
    // while the next photo is mid-render could leave a written file without its row.
    const files = outFiles(outDir).length
    if (persisted >= 1 && prog && prog.total === 3 && prog.done === persisted && prog.done < 3 && files === persisted) {
      snap = { persisted, done: prog.done, files, taskId: task.id }
      break
    }
    await sleep(40)
  }
  if (!snap) throw new Error('never observed a persisted 1..2 item window to kill in')
  const proc = app.process()
  const exited = new Promise((resolve) => proc.once('exit', resolve))
  try {
    proc.kill('SIGKILL')
  } catch {
    try {
      proc.kill()
    } catch {
      /* already gone */
    }
  }
  await Promise.race([exited, sleep(6000)])
  await sleep(900)
  return snap
}

function prepareGate(name) {
  const dir = path.join(runDir, name)
  const profile = path.join(dir, 'profile')
  const fixtures = path.join(dir, 'fixtures')
  const outDir = path.join(dir, 'out')
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  fs.mkdirSync(fixtures, { recursive: true })
  fs.mkdirSync(outDir, { recursive: true })
  for (const [src, name] of [
    ['4x3.jpg', 'a.jpg'],
    ['3x2.jpg', 'b.jpg'],
    ['1x1.jpg', 'c.jpg']
  ]) {
    fs.copyFileSync(path.join(srcFixtures, src), path.join(fixtures, name))
  }
  return { dir, profile, fixtures, outDir }
}

/**
 * A collision duplicate. main/index.ts `uniqueOutputPath` appends `-1`, `-2`…
 * (some older builds/tools described this as `(1)`); both shapes are caught.
 */
function hasDup(files) {
  return files.filter((f) => /\(\d+\)/.test(f) || /-\d+\.[a-z0-9]+$/i.test(f))
}

// ---------------------------------------------------------------------------
// GATE 1 — resume after kill
// ---------------------------------------------------------------------------
async function gate1() {
  step('gate 1', 'kill mid-export, relaunch on the SAME profile, 继续 must finish cleanly')
  const { profile, fixtures, outDir } = prepareGate('g1')

  const first = await launch(profile)
  let snap
  try {
    await importFolder(first.app, first.page, fixtures)
    await openExportDialog(first.page)
    await pickOutDir(first.app, first.page, outDir)
    await slowRenderer(first.page)
    await startExport(first.page)
    snap = await killMidJob(first.app, first.page, outDir)
  } finally {
    // the process is already dead; this is only a transport cleanup
    await first.app.close().catch(() => undefined)
  }
  const killedFiles = outFiles(outDir)
  check('g1  kill happened inside 1..3 exported items', snap.done >= 1 && snap.done < 3, `done=${snap.done}/${3} persisted=${snap.persisted}`)
  check('g1  out dir matches the persisted row at kill time (no orphan file)', killedFiles.length === snap.persisted, `files=${killedFiles.length} results=${snap.persisted}`)
  check('g1  the interrupted run wrote no collision duplicate', hasDup(killedFiles).length === 0, killedFiles.join(', '))

  const second = await relaunch(profile)
  try {
    const { app, page } = second
    await importFolder(app, page, fixtures)

    const taskBefore = await newestTask(page)
    check('g1  re-imported photos are 3', (await page.locator('.thumb').count()) === 3)
    check('g1  persisted task survived the restart as running', taskBefore?.status === 'running', `status=${taskBefore?.status}`)

    const resumeP = page.locator('.export-card-head p').first()
    await page.waitForFunction(
      () => /上次导到\s*\d+\/\d+，可继续/.test(document.querySelector('.export-card-head p')?.textContent || ''),
      null,
      { timeout: 30000 }
    )
    const resumeText = (await resumeP.textContent()) || ''
    const m = /上次导到\s*(\d+)\/(\d+)/.exec(resumeText)
    const x = m ? Number(m[1]) : -1
    const y = m ? Number(m[2]) : -1
    const persistedX = Object.keys(taskBefore?.results || {}).length
    check('g1  overlay offers 「上次导到 X/Y，可继续」', Boolean(m), resumeText.trim())
    check('g1  Y is the task item count', y === 3, `Y=${y}`)
    check('g1  X is the number of persisted results', x === persistedX && x >= 1 && x < 3, `X=${x} persisted=${persistedX}`)
    check('g1  X matches the files already on disk', outFiles(outDir).length === x, `files=${outFiles(outDir).length}`)
    check('g1  继续 button present', (await page.getByRole('button', { name: '继续', exact: true }).count()) === 1)

    await page.getByRole('button', { name: '继续', exact: true }).click()
    await waitTaskUpdate(page, taskBefore.id, taskBefore.updatedAt)
    const doneTitle = (await page.locator('.export-card-head h3').textContent()) || ''
    const doneBody = (await page.locator('.export-card-head p').first().textContent()) || ''
    check('g1  resumed run completed', /导出完成/.test(doneTitle), doneTitle.trim())
    check('g1  overlay reports the skipped items', new RegExp(`跳过 ${x} 张`).test(doneBody), doneBody.trim())

    const files = outFiles(outDir)
    check('g1  out dir holds exactly 3 files', files.length === 3, `files=${files.length}: ${files.join(', ')}`)
    check('g1  no collision-suffixed filename (-1 / (1))', hasDup(files).length === 0, files.join(', '))
    const bases = files.map((f) => f.replace(/^([a-c])-.*/, '$1'))
    check('g1  one output per fixture (a,b,c)', new Set(bases).size === 3, bases.join(','))

    const taskAfter = await newestTask(page)
    const results = Object.keys(taskAfter?.results || {}).length
    check('g1  task status is done', taskAfter?.status === 'done', `status=${taskAfter?.status}`)
    check('g1  task holds 3 results', results === 3 && taskAfter?.order?.length === 3, `results=${results} order=${taskAfter?.order?.length}`)
    check('g1  recorded paths match the files on disk', (Object.values(taskAfter?.results || {}).every((r) => fs.existsSync(r.path))), '')
    check('g1  no resumable notice left after completion', (await page.locator('.export-card h3', { hasText: '有未完成的导出' }).count()) === 0)
  } finally {
    await second.app.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// GATE 2 — retry a single failed item
// ---------------------------------------------------------------------------
async function gate2() {
  step('gate 2', 'one pending source is deleted before 继续; 重试失败项 must export only it')
  const { profile, fixtures, outDir } = prepareGate('g2')

  const first = await launch(profile)
  let snap
  try {
    await importFolder(first.app, first.page, fixtures)
    await openExportDialog(first.page)
    await pickOutDir(first.app, first.page, outDir)
    await slowRenderer(first.page)
    await startExport(first.page)
    snap = await killMidJob(first.app, first.page, outDir)
  } finally {
    await first.app.close().catch(() => undefined)
  }
  check('g2  kill left 1..2 items done', snap.done >= 1 && snap.done < 3, `done=${snap.done}`)

  const struck = path.join(fixtures, 'struck.bak')
  let missingName = ''
  const second = await relaunch(profile)
  try {
    const { app, page } = second
    await importFolder(app, page, fixtures)
    const task = await newestTask(page)
    const pending = (task?.order || []).filter((k) => !task.results[k])
    const entry = (task?.photos || []).find((p) => p.key === pending[0])
    missingName = entry?.name || ''
    check('g2  a pending item exists to break', Boolean(entry), `pending=${pending.join(',')}`)

    // Point that item's source at a missing file (previews are already in memory).
    const victim = path.join(fixtures, missingName)
    fs.renameSync(victim, struck)
    check('g2  fixture removed before resuming', !fs.existsSync(victim), victim)

    await page.waitForFunction(
      () => /上次导到\s*\d+\/\d+，可继续/.test(document.querySelector('.export-card-head p')?.textContent || ''),
      null,
      { timeout: 30000 }
    )
    await page.getByRole('button', { name: '继续', exact: true }).click()
    await waitTaskUpdate(page, task.id, task.updatedAt)

    const title = (await page.locator('.export-card-head h3').textContent()) || ''
    const body = (await page.locator('.export-card-head p').first().textContent()) || ''
    check('g2  the run reports a failure', /有失败/.test(title), `${title.trim()} ${body.trim()}`)
    check('g2  the failed photo is named in the overlay', (await page.locator('.export-errors strong').first().textContent()) === missingName)

    const failedTask = await newestTask(page)
    const errKeys = Object.keys(failedTask?.errors || {})
    check('g2  the failure is recorded per task key', errKeys.length === 1 && errKeys[0] === pending[0], `keys=${errKeys.join(',')} pending=${pending[0]}`)
    check('g2  the failed key has no result', !failedTask?.results?.[pending[0]])
    check('g2  the other pending item succeeded', Object.keys(failedTask?.results || {}).length === 2, `results=${Object.keys(failedTask?.results || {}).length}`)
    check('g2  task is paused, not done', failedTask?.status === 'paused', `status=${failedTask?.status}`)
    check('g2  重试失败项 button appears', (await page.getByRole('button', { name: '重试失败项' }).count()) === 1)

    const before = outFiles(outDir)
    check('g2  two files on disk after the failed run', before.length === 2, before.join(', '))

    // Restore the source, then retry.
    fs.renameSync(struck, victim)
    check('g2  fixture restored', fs.existsSync(victim), victim)
    await page.getByRole('button', { name: '重试失败项' }).click()
    await waitTaskUpdate(page, failedTask.id, failedTask.updatedAt)

    const after = outFiles(outDir)
    const added = after.filter((f) => !before.includes(f))
    check('g2  retry wrote exactly one new file', added.length === 1, `added=${added.join(', ')}`)
    check('g2  the retried file is the previously failed photo', added.length === 1 && added[0].startsWith(missingName.replace(/\.[^.]+$/, '')), added.join(', '))
    check('g2  no collision-suffixed filename (-1 / (1))', hasDup(after).length === 0, after.join(', '))
    const retried = await newestTask(page)
    check('g2  errors cleared after a successful retry', Object.keys(retried?.errors || {}).length === 0)
    check('g2  task ends done with 3 results', retried?.status === 'done' && Object.keys(retried?.results || {}).length === 3, `status=${retried?.status} results=${Object.keys(retried?.results || {}).length}`)
    check('g2  failed photo still openable', fs.existsSync(victim))
  } finally {
    if (fs.existsSync(struck) && missingName) {
      try {
        fs.renameSync(struck, path.join(fixtures, missingName))
      } catch {
        /* already restored */
      }
    }
    await second.app.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// GATE 3 — history identity: photo fingerprint, not design
// ---------------------------------------------------------------------------
async function gate3() {
  step('gate 3', 'same photos => skipped; changed design => still skipped; 新建任务 => real re-export')
  const { profile, fixtures, outDir } = prepareGate('g3')

  const { app, page } = await launch(profile)
  try {
    await importFolder(app, page, fixtures)
    await openExportDialog(page)
    await pickOutDir(app, page, outDir)
    const prev1 = (await newestTask(page))?.id ?? null
    await startExport(page)
    await waitNewTask(page, prev1)

    const first = outFiles(outDir)
    const task1 = await newestTask(page)
    check('g3  clean run writes 3 files', first.length === 3, first.join(', '))
    check('g3  clean run task is done', task1?.status === 'done' && Object.keys(task1?.results || {}).length === 3)

    // --- second export, identical photos and design -------------------------
    await page.getByRole('button', { name: '完成', exact: true }).click()
    await page.waitForSelector('.export-overlay', { state: 'detached', timeout: 10000 }).catch(() => undefined)
    await openExportDialog(page)
    await pickOutDir(app, page, outDir)
    const prev2 = (await newestTask(page))?.id ?? null
    await startExport(page)
    await waitNewTask(page, prev2)

    const second = outFiles(outDir)
    const body2 = (await page.locator('.export-card-head p').first().textContent()) || ''
    check('g3  re-export of the same photos writes 0 new files', second.length === 3, `files=${second.length}`)
    check('g3  overlay reports all 3 skipped', /跳过 3 张/.test(body2), body2.trim())
    const tasks2 = await readJobs(page)
    check('g3  the second run persisted its own (done) record', tasks2.length === 2 && tasks2.every((t) => t.status === 'done'), `tasks=${tasks2.length}`)

    // --- third export after a DESIGN change --------------------------------
    await page.getByRole('button', { name: '完成', exact: true }).click()
    await openExportDialog(page)
    await pickOutDir(app, page, outDir)
    await page.locator('.modal .toggle-chip', { hasText: '2048' }).click()
    const chipOn = await page.locator('.modal .toggle-chip.on', { hasText: '2048' }).count()
    check('g3  design changed (长边限制 2048)', chipOn === 1)
    const prev3 = (await newestTask(page))?.id ?? null
    await startExport(page)
    await waitNewTask(page, prev3)

    const third = outFiles(outDir)
    const body3 = (await page.locator('.export-card-head p').first().textContent()) || ''
    check('g3  changed design still skips (fingerprint is photo-based)', third.length === 3, `files=${third.length}`)
    check('g3  overlay still reports 3 skipped', /跳过 3 张/.test(body3), body3.trim())

    // --- 新建任务 is the escape hatch --------------------------------------
    const newTaskBtn = page.getByRole('button', { name: '新建任务', exact: true })
    check('g3  新建任务 button offered when everything was skipped', (await newTaskBtn.count()) === 1)
    await newTaskBtn.click()
    await page.waitForSelector('.export-overlay', { state: 'detached', timeout: 10000 }).catch(() => undefined)

    await openExportDialog(page)
    await pickOutDir(app, page, outDir)
    const prev4 = (await newestTask(page))?.id ?? null
    await startExport(page)
    await waitNewTask(page, prev4)

    const fourth = outFiles(outDir)
    const dups = hasDup(fourth)
    check('g3  新建任务 allows a real re-export (3 more files)', fourth.length === 6, `files=${fourth.length}`)
    check('g3  the re-export collided into -1 names as expected', dups.length === 3, dups.join(', '))
    const task4 = await newestTask(page)
    check('g3  the new task is done with 3 fresh results', task4?.status === 'done' && Object.keys(task4?.results || {}).length === 3)
    check('g3  the retired record is cancelled (not reusable as history)', (await readJobs(page)).some((t) => t.status === 'cancelled'))
  } finally {
    await app.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// GATE 4 — previous gates
// ---------------------------------------------------------------------------
function runRegression(name, script, minPass) {
  const started = Date.now()
  const r = spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8', timeout: 900000, maxBuffer: 64 * 1024 * 1024 })
  const out = `${r.stdout || ''}${r.stderr || ''}`
  const pass = (out.match(/^PASS/gm) || []).length
  const fail = (out.match(/^FAIL/gm) || []).length
  fs.mkdirSync(path.join(research, 's3'), { recursive: true })
  fs.writeFileSync(path.join(research, 's3', `${name.replace(/\s+/g, '-')}.txt`), out)
  const ok = r.status === 0 && fail === 0 && pass >= minPass
  check(`g4  ${name} GREEN`, ok, `exit=${r.status} pass=${pass} fail=${fail} ${(Date.now() - started) / 1000}s`)
  if (!ok) console.log(out.split('\n').slice(-25).join('\n'))
}

;(async () => {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(runDir, { recursive: true })
  for (const f of ['4x3.jpg', '3x2.jpg', '1x1.jpg']) {
    if (!fs.existsSync(path.join(srcFixtures, f))) throw new Error(`fixture missing: ${f}`)
  }

  if (SELECTED.includes('g1')) await gate1()
  if (SELECTED.includes('g2')) await gate2()
  if (SELECTED.includes('g3')) await gate3()

  if (!SKIP_REGRESSIONS) {
    step('gate 4', 'previous gates stay green (app closed, one instance at a time)')
    runRegression('s1 undo', 'scripts/_s1-undo-probe.cjs', 1)
    runRegression('s2 undo ui', 'scripts/_s2-undo-ui-probe.cjs', 1)
    runRegression('batch preview', 'scripts/_batch-preview-probe.cjs', 6)
  }

  console.log('')
  if (failures.length) {
    console.log(`RESULT FAIL  ${failures.length} check(s): ${failures.join(' | ')}`)
    process.exit(1)
  }
  console.log('RESULT GREEN  all S3 gates passed')
  process.exit(0)
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
