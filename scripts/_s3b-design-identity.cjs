/**
 * S3b gate — export identity is PHOTO SET + DESIGN, not photos alone.
 *
 * The trap this probe exists to kill: S3 keyed the persisted export history by
 * photo fingerprints only, so exporting the same 3 photos with another template
 * (or another quality / long edge / format) silently wrote 0 files.
 *
 * GATE 1 (different design => new files)
 *   Import 3 fixtures, export (3 files, task A), change ONE visible layout
 *   parameter (文字区高度 slider), export again. Must write 3 NEW files (the
 *   `-1` collision names are expected) and persist a NEW task id.
 *
 * GATE 2 (same design => still skips)
 *   Export once more without touching the design: 0 new files, overlay reports
 *   `成功 0 张 · 跳过 3 张（之前已导出）`, and the seeded task holds 3 results.
 *
 * GATE 3 (export settings are part of the design)
 *   Change 长边限制 to 2048 only, export: 3 new files (this proves
 *   `design.export` is inside `designKey`).
 *
 * GATE 4 (revert)
 *   Restore the slider default and 原始尺寸, then export: the design key equals
 *   the FIRST task's key, 0 new files, 3 skipped.
 *
 * GATE 5 (crash resume still works)
 *   Repeat the S3 kill-at-1/3: launch, import, export, hard-kill once the row
 *   holds 1..2 results, relaunch on the SAME profile, re-import, 继续. Exactly
 *   3 files, no collision duplicate, task done.
 *
 * GATE 6 (no regressions)
 *   _s1-undo-probe, _s2-undo-ui-probe, _batch-preview-probe and the S3
 *   resume/retry gates must exit 0. NOTE: the S3 probe's own gate 3 asserts the
 *   trap ("changed design still skips") and is superseded by S3b gates 1-4; the
 *   S3 run here uses `--gates=g1,g2` (kill/resume + retry) on purpose.
 *
 * RED RUN (gate 1 must be able to fail): disable the `t.designKey === currentKey`
 * comparisons in store.ts (both `findResumable` and `runExport`), rebuild, run
 * `node scripts/_s3b-design-identity.cjs --gates=g1`. The second export then
 * writes 0 new files and the gate goes red.
 *
 * Usage: node scripts/_s3b-design-identity.cjs [--gates=g1,g2,...] [--skip-regressions]
 * Exit 0 = green.
 */
const path = require('node:path')
const fs = require('node:fs')
const { spawnSync } = require('node:child_process')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const research = path.join(root, '..', '_research')
const outRoot = path.join(research, 's3b')
const runDir = path.join(outRoot, 'run')
const srcFixtures = path.join(research, 'test-assets', 'ratio-study')

const SKIP_REGRESSIONS = process.argv.includes('--skip-regressions')
const ONLY = (process.argv.find((a) => a.startsWith('--gates=')) || '').split('=')[1]
const SELECTED = ONLY ? ONLY.split(',') : ['g1', 'g2', 'g3', 'g4', 'g5']
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
// harness (same shape as _s3-resume-probe.cjs)
// ---------------------------------------------------------------------------
async function launch(profile) {
  const env = { ...process.env }
  // Electron 45 ships node20 mode; leaving this set makes `electron .` exit.
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
  await page.locator('.modal-foot .btn-primary').click()
  await page.waitForSelector('.export-card', { timeout: 30000 })
}

async function closeOverlay(page) {
  const btn = page.getByRole('button', { name: '完成', exact: true })
  if (await btn.count()) {
    await btn.click()
    await page.waitForSelector('.export-overlay', { state: 'detached', timeout: 10000 }).catch(() => undefined)
  }
}

async function slowRenderer(page, rate = 20) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setCPUThrottlingRate', { rate })
}

async function progress(page) {
  const txt = await page.locator('.export-card-head p').first().textContent().catch(() => '')
  const m = /(\d+)\s*\/\s*(\d+)/.exec(txt || '')
  return m ? { done: Number(m[1]), total: Number(m[2]) } : null
}

async function waitTaskUpdate(page, id, prevUpdatedAt) {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    const jobs = await readJobs(page)
    const t = jobs.find((j) => j.id === id)
    if (t && t.status !== 'running' && t.updatedAt !== prevUpdatedAt) {
      await sleep(400)
      return t
    }
    await sleep(120)
  }
  throw new Error(`task ${id} never settled`)
}

/** Wait for a task that did not exist before the click and has settled. */
async function waitNewTask(page, beforeIds) {
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    const jobs = await readJobs(page)
    const created = jobs.filter((j) => !beforeIds.has(j.id)).sort((a, b) => b.createdAt - a.createdAt)[0]
    if (created && created.status !== 'running') {
      await sleep(400)
      return created
    }
    await sleep(120)
  }
  throw new Error('no new task ever settled')
}

async function taskIds(page) {
  return new Set((await readJobs(page)).map((j) => j.id))
}

function outFiles(dir) {
  if (!fs.existsSync(dir)) return []
  return fs
    .readdirSync(dir)
    .filter((f) => !f.startsWith('.'))
    .sort()
}

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

async function killMidJob(app, page, outDir) {
  const deadline = Date.now() + 240000
  let snap = null
  while (Date.now() < deadline) {
    const prog = await progress(page)
    const task = await newestTask(page)
    const persisted = task ? Object.keys(task.results || {}).length : 0
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

function prepare(name) {
  const dir = path.join(runDir, name)
  const profile = path.join(dir, 'profile')
  const fixtures = path.join(dir, 'fixtures')
  const outDir = path.join(dir, 'out')
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  fs.mkdirSync(fixtures, { recursive: true })
  fs.mkdirSync(outDir, { recursive: true })
  for (const [src, name2] of [
    ['4x3.jpg', 'a.jpg'],
    ['3x2.jpg', 'b.jpg'],
    ['1x1.jpg', 'c.jpg']
  ]) {
    fs.copyFileSync(path.join(srcFixtures, src), path.join(fixtures, name2))
  }
  return { dir, profile, fixtures, outDir }
}

function hasDup(files) {
  return files.filter((f) => /\(\d+\)/.test(f) || /-\d+\.[a-z0-9]+$/i.test(f))
}

function collisionSuffix(files) {
  return files.filter((f) => /-\d+\.[a-z0-9]+$/i.test(f))
}

// ---------------------------------------------------------------------------
// design edits through the real UI
// ---------------------------------------------------------------------------
async function showLayoutPane(page) {
  await page.locator('[data-editor-tab="layout"]').click()
  await page.waitForSelector('.editor-pane[data-editor-pane="layout"]', { timeout: 15000 })
}

async function footerSlider(page) {
  const row = page.locator('.slider-row', { hasText: '文字区高度' }).first()
  await row.waitFor({ state: 'visible', timeout: 15000 })
  return row
}

/** Change ONE visible layout parameter (the footer-height slider). */
async function bumpFooter(page) {
  await showLayoutPane(page)
  const row = await footerSlider(page)
  const input = row.locator('input[type="range"]')
  const labelBefore = ((await row.locator('.slider-value').textContent()) || '').trim()
  const domBefore = await input.inputValue()
  await input.focus()
  await page.keyboard.press('ArrowRight')
  const deadline = Date.now() + 10000
  let labelAfter = labelBefore
  let domAfter = domBefore
  while (Date.now() < deadline) {
    labelAfter = ((await row.locator('.slider-value').textContent()) || '').trim()
    domAfter = await input.inputValue()
    if (labelAfter !== labelBefore || domAfter !== domBefore) break
    await sleep(80)
  }
  return { labelBefore, labelAfter, domBefore, domAfter }
}

/** Double-click the slider row = 复位到默认值 (onDoubleClick -> resetParam). */
async function resetFooter(page, expectLabel) {
  await showLayoutPane(page)
  const row = await footerSlider(page)
  // dispatchEvent hits the label handler without clicking the range thumb
  await row.dispatchEvent('dblclick')
  const deadline = Date.now() + 10000
  while (Date.now() < deadline) {
    const label = ((await row.locator('.slider-value').textContent()) || '').trim()
    if (label === expectLabel) return label
    await sleep(80)
  }
  // fallback: dblclick on the label text corner
  await row.dblclick({ position: { x: 10, y: 8 } })
  return ((await row.locator('.slider-value').textContent()) || '').trim()
}

async function setLongEdge(page, px) {
  const chip = page.locator(`.modal .toggle-chip[class*="toggle-chip"]`, { hasText: px === 0 ? '原始尺寸' : String(px) })
  await chip.first().click()
}

// ---------------------------------------------------------------------------
// GATES 1-4 — design identity across one profile
// ---------------------------------------------------------------------------
async function identityGates(gates) {
  const upTo = Math.max(...gates.map((g) => ({ g1: 1, g2: 2, g3: 3, g4: 4 })[g] || 0))
  step('gate 1-4', 'same photos, different design => new files; same design => skip; revert => skip')
  const { dir, profile, fixtures, outDir } = prepare('identity')
  const { app, page, errors } = await launch(profile)
  try {
    await importFolder(app, page, fixtures)
    await openExportDialog(page)
    await pickOutDir(app, page, outDir)

    // ---- GATE 1, first design A ------------------------------------------
    let beforeIds = await taskIds(page)
    await startExport(page)
    const task1 = await waitNewTask(page, beforeIds)
    const filesA = outFiles(outDir)
    check('g1  design A exports 3 files', filesA.length === 3, filesA.join(', '))
    check(
      'g1  design A task is done with 3 results',
      task1.status === 'done' && Object.keys(task1.results || {}).length === 3,
      `status=${task1.status} results=${Object.keys(task1.results || {}).length}`
    )
    check('g1  task persists a designKey', typeof task1.designKey === 'string' && task1.designKey.length > 0, `len=${(task1.designKey || '').length}`)

    // ---- GATE 1, design B (one visible param changed) --------------------
    await closeOverlay(page)
    const bump = await bumpFooter(page)
    check('g1  visible layout param changed (文字区高度)', bump.labelAfter !== bump.labelBefore, `${bump.labelBefore} -> ${bump.labelAfter}`)

    await openExportDialog(page)
    await pickOutDir(app, page, outDir)
    beforeIds = await taskIds(page)
    await startExport(page)
    const task2 = await waitNewTask(page, beforeIds)
    const filesB = outFiles(outDir)
    const addedB = filesB.filter((f) => !filesA.includes(f))
    check('g1  different design exports 3 NEW files', addedB.length === 3 && filesB.length === 6, `added=${addedB.join(', ')} total=${filesB.length}`)
    check('g1  new files use collision names (-1)', addedB.length === 3 && collisionSuffix(addedB).length === 3, addedB.join(', '))
    check('g1  different design created a NEW task id', task2.id !== task1.id, `${task1.id} -> ${task2.id}`)
    check('g1  new task has a DIFFERENT designKey', task2.designKey !== task1.designKey)
    const task1After = (await readJobs(page)).find((t) => t.id === task1.id)
    check('g1  the old record stays as history (status unchanged)', task1After?.status === 'done', `status=${task1After?.status}`)

    let task3 = null
    let task4 = null

    // ---- GATE 2: same design B, export again -----------------------------
    if (upTo >= 2) {
      await closeOverlay(page)
      const filesB2 = outFiles(outDir)
      await openExportDialog(page)
      await pickOutDir(app, page, outDir)
      beforeIds = await taskIds(page)
      await startExport(page)
      task3 = await waitNewTask(page, beforeIds)
      const body2 = (await page.locator('.export-card-head p').first().textContent()) || ''
      const filesB3 = outFiles(outDir)
      check('g2  same design writes 0 new files', filesB3.length === filesB2.length, `files=${filesB3.length}`)
      check('g2  overlay reports all 3 skipped', /成功 0 张 · 跳过 3 张（之前已导出）/.test(body2), body2.trim())
      check(
        'g2  known-task path seeded all 3 results',
        task3.status === 'done' && Object.keys(task3.results || {}).length === 3,
        `status=${task3.status} results=${Object.keys(task3.results || {}).length}`
      )
      check('g2  the skip record carries design B', task3.designKey === task2.designKey)
      check('g2  no 「有未完成的导出」 card (nothing to resume)', (await page.locator('.export-card h3', { hasText: '有未完成的导出' }).count()) === 0)
    }

    // ---- GATE 3: export settings only (长边限制 2048) --------------------
    if (upTo >= 3) {
      await closeOverlay(page)
      const filesB4 = outFiles(outDir)
      await openExportDialog(page)
      await pickOutDir(app, page, outDir)
      await setLongEdge(page, 2048)
      const chipOn = await page.locator('.modal .toggle-chip.on', { hasText: '2048' }).count()
      check('g3  long edge switched to 2048', chipOn === 1)
      beforeIds = await taskIds(page)
      await startExport(page)
      task4 = await waitNewTask(page, beforeIds)
      const filesC = outFiles(outDir)
      const addedC = filesC.filter((f) => !filesB4.includes(f))
      check('g3  settings-only change is a new design', task4.designKey !== task3.designKey)
      check('g3  settings-only change exports 3 NEW files', addedC.length === 3 && filesC.length === 9, `added=${addedC.join(', ')} total=${filesC.length}`)
      check('g3  newest files are -2 collisions', addedC.length === 3 && addedC.every((f) => /-2\.[a-z0-9]+$/i.test(f)), addedC.join(', '))

      // ---- GATE 4: revert both changes => design A again -----------------
      if (upTo >= 4) {
        await closeOverlay(page)
        const label = await resetFooter(page, bump.labelBefore)
        check('g4  footer slider reverted to its default', label === bump.labelBefore, `${label} (want ${bump.labelBefore})`)
        const filesC2 = outFiles(outDir)
        await openExportDialog(page)
        await pickOutDir(app, page, outDir)
        await setLongEdge(page, 0)
        const chipZero = await page.locator('.modal .toggle-chip.on', { hasText: '原始尺寸' }).count()
        check('g4  long edge reverted to 原始尺寸', chipZero === 1)
        beforeIds = await taskIds(page)
        await startExport(page)
        const task5 = await waitNewTask(page, beforeIds)
        const body4 = (await page.locator('.export-card-head p').first().textContent()) || ''
        const filesD = outFiles(outDir)
        check('g4  reverted design matches the FIRST task key', task5.designKey === task1.designKey)
        check('g4  reverted design writes 0 new files', filesD.length === filesC2.length, `files=${filesD.length}`)
        check('g4  overlay reports 3 skipped', /跳过 3 张/.test(body4), body4.trim())
        check('g4  history lookup skipped the newer different-design tasks', task5.designKey !== task4.designKey && task5.designKey !== task3.designKey)

        // ---- EDGE: output folder is inside design.export => new identity --
        await closeOverlay(page)
        const outDir2 = path.join(dir, 'out2')
        fs.mkdirSync(outDir2, { recursive: true })
        await openExportDialog(page)
        await pickOutDir(app, page, outDir2)
        beforeIds = await taskIds(page)
        await startExport(page)
        const task6 = await waitNewTask(page, beforeIds)
        const filesE = outFiles(outDir2)
        check('edge  folder-only change creates a new designKey', task6.designKey !== task5.designKey)
        check('edge  folder-only change writes 3 fresh files into the new folder', filesE.length === 3, filesE.join(', '))
      }
    }
    check('edge  no page/console errors in the identity run', errors.length === 0, errors.slice(0, 3).join(' | '))
  } finally {
    await app.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// GATE 5 — crash resume still works
// ---------------------------------------------------------------------------
async function resumeGate() {
  step('gate 5', 'kill at 1/3, relaunch the SAME profile, 继续 must finish with exactly 3 files')
  const { profile, fixtures, outDir } = prepare('resume')

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
  const killedFiles = outFiles(outDir)
  check('g5  kill happened inside 1..3 exported items', snap.done >= 1 && snap.done < 3, `done=${snap.done}/3 persisted=${snap.persisted}`)
  check('g5  out dir matches the persisted row at kill time (no orphan file)', killedFiles.length === snap.persisted, `files=${killedFiles.length} results=${snap.persisted}`)
  check('g5  the interrupted run wrote no collision duplicate', hasDup(killedFiles).length === 0, killedFiles.join(', '))

  const second = await relaunch(profile)
  try {
    const { app, page } = second
    await importFolder(app, page, fixtures)
    const taskBefore = await newestTask(page)
    check('g5  persisted task survived the restart as running', taskBefore?.status === 'running', `status=${taskBefore?.status}`)
    check('g5  the persisted task carries a designKey', typeof taskBefore?.designKey === 'string' && taskBefore.designKey.length > 0)

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
    check('g5  overlay offers 「上次导到 X/Y，可继续」', Boolean(m), resumeText.trim())
    check('g5  X/Y match the persisted row', x === persistedX && y === 3 && x >= 1 && x < 3, `X=${x} persisted=${persistedX} Y=${y}`)

    await page.getByRole('button', { name: '继续', exact: true }).click()
    await waitTaskUpdate(page, taskBefore.id, taskBefore.updatedAt)

    const doneTitle = (await page.locator('.export-card-head h3').textContent()) || ''
    const doneBody = (await page.locator('.export-card-head p').first().textContent()) || ''
    check('g5  resumed run completed', /导出完成/.test(doneTitle), doneTitle.trim())
    check('g5  overlay reports the skipped items', new RegExp(`跳过 ${x} 张`).test(doneBody), doneBody.trim())

    const files = outFiles(outDir)
    check('g5  out dir holds exactly 3 files', files.length === 3, `files=${files.length}: ${files.join(', ')}`)
    check('g5  no collision-suffixed filename (-1 / (1))', hasDup(files).length === 0, files.join(', '))
    const bases = files.map((f) => f.replace(/^([a-c])-.*/, '$1'))
    check('g5  one output per fixture (a,b,c)', new Set(bases).size === 3, bases.join(', '))

    const taskAfter = (await readJobs(page)).find((t) => t.id === taskBefore.id)
    const results = Object.keys(taskAfter?.results || {}).length
    check('g5  task status is done', taskAfter?.status === 'done', `status=${taskAfter?.status}`)
    check('g5  task holds 3 results', results === 3 && taskAfter?.order?.length === 3, `results=${results} order=${taskAfter?.order?.length}`)
    check('g5  no resumable notice left after completion', (await page.locator('.export-card h3', { hasText: '有未完成的导出' }).count()) === 0)
  } finally {
    await second.app.close().catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// GATE 6 — previous probes
// ---------------------------------------------------------------------------
function runRegression(name, args, minPass) {
  const started = Date.now()
  const script = args[0]
  const r = spawnSync(process.execPath, args, { cwd: root, encoding: 'utf8', timeout: 900000, maxBuffer: 64 * 1024 * 1024 })
  const out = `${r.stdout || ''}${r.stderr || ''}`
  const pass = (out.match(/^PASS/gm) || []).length
  const fail = (out.match(/^FAIL/gm) || []).length
  const dir = path.join(outRoot, 'regressions')
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, `${name.replace(/\s+/g, '-')}.txt`), out)
  const ok = r.status === 0 && fail === 0 && pass >= minPass
  check(`g6  ${name} GREEN`, ok, `exit=${r.status} pass=${pass} fail=${fail} ${(Date.now() - started) / 1000}s`)
  if (!ok) console.log(out.split('\n').slice(-25).join('\n'))
  return ok
}

;(async () => {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(runDir, { recursive: true })
  fs.mkdirSync(outRoot, { recursive: true })
  for (const f of ['4x3.jpg', '3x2.jpg', '1x1.jpg']) {
    if (!fs.existsSync(path.join(srcFixtures, f))) throw new Error(`fixture missing: ${f}`)
  }

  if (SELECTED.some((g) => ['g1', 'g2', 'g3', 'g4'].includes(g))) {
    await identityGates(SELECTED.filter((g) => ['g1', 'g2', 'g3', 'g4'].includes(g)))
  }
  if (SELECTED.includes('g5')) await resumeGate()

  if (!SKIP_REGRESSIONS && !ONLY) {
    step('gate 6', 'previous probes stay green (app closed, one instance at a time)')
    runRegression('s1 undo', ['scripts/_s1-undo-probe.cjs'], 1)
    runRegression('s2 undo ui', ['scripts/_s2-undo-ui-probe.cjs'], 1)
    runRegression('batch preview', ['scripts/_batch-preview-probe.cjs'], 6)
    // S3's own gate 3 asserts the trap that S3b fixes; resume+retry (g1,g2) are
    // the regression surface that must stay green.
    runRegression('s3 resume', ['scripts/_s3-resume-probe.cjs', '--gates=g1,g2', '--skip-regressions'], 1)
  }

  console.log('')
  if (failures.length) {
    console.log(`RESULT FAIL  ${failures.length} check(s): ${failures.join(' | ')}`)
    process.exit(1)
  }
  console.log('RESULT GREEN  all S3b gates passed')
  process.exit(0)
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
