#!/usr/bin/env node
'use strict'
/**
 * FrameMark release gate B: render every stock border through the shipped
 * engine binaries and the shipped runtime data bundle.
 *
 * 111 IR styles come from data/manifest.json, 69 hand variants from
 * data/hand/cards.json.  Each border is rendered for the canonical 4:3 photo
 * (the photo the oracle goldens were captured with) plus portrait / square /
 * wide fixtures.  Renders + engine reports are written per fixture so the
 * checker can apply layout and pixel rules and diff the canonical frames
 * against the oracle goldens.
 *
 *   node sweep.cjs [--workers-ir 2] [--workers-hand 2] [--fixtures canonical,portrait]
 *                  [--kind ir|hand] [--limit N] [--out DIR] [--rt DIR] [--resume]
 *
 * The engine protocol is the shipped preview worker: one JSON argv array per
 * stdin line, one FM_PREVIEW_WORKER_DONE line per request.
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const readline = require('node:readline')
const { spawn } = require('node:child_process')

function parseArgs (argv) {
  const out = {
    rt: process.env.FM_RT || 'E:/FrameMark/execution-ui-v2-20261106/build/runs/20261117055959864/artifacts/desktop-runtime',
    out: process.env.FM_SWEEP_OUT || 'E:/FrameMark/qa/release-gate-20261117',
    fixtures: 'canonical,portrait,square,wide',
    kind: 'all',
    limit: 0,
    workersIr: 2,
    workersHand: 2,
    timeoutMs: 300000,
    resume: false
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    if (a === '--rt') out.rt = next()
    else if (a === '--out') out.out = next()
    else if (a === '--fixtures') out.fixtures = next()
    else if (a === '--kind') out.kind = next()
    else if (a === '--limit') out.limit = Number(next())
    else if (a === '--workers-ir') out.workersIr = Number(next())
    else if (a === '--workers-hand') out.workersHand = Number(next())
    else if (a === '--timeout') out.timeoutMs = Number(next())
    else if (a === '--resume') out.resume = true
    else throw new Error(`unknown option ${a}`)
  }
  if (!['all', 'ir', 'hand'].includes(out.kind)) throw new Error('--kind must be all|ir|hand')
  out.fixtures = out.fixtures.split(',').map((s) => s.trim()).filter(Boolean)
  return out
}

const sha256 = (file) => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const safe = (s) => s.replace(/[^A-Za-z0-9._-]+/g, '_')
const now = () => Date.now()

class Worker {
  constructor (exe, cwd, kind, index, logDir) {
    this.exe = exe
    this.cwd = cwd
    this.kind = kind
    this.name = `${kind}-${index}`
    this.logPath = path.join(logDir, `${this.name}.log`)
    this.logStream = fs.createWriteStream(this.logPath, { flags: 'a' })
    this.dead = false
    this.ready = false
    this.pending = null
    this.last = []
  }

  start () {
    return new Promise((resolve, reject) => {
      const child = spawn(this.exe, ['preview-server'], {
        cwd: this.cwd,
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true
      })
      this.child = child
      const rl = readline.createInterface({ input: child.stdout })
      rl.on('line', (line) => this.onLine(line))
      child.stderr.on('data', (buf) => {
        const text = buf.toString('utf8')
        this.logStream.write(text)
        this.last.push(text.trimEnd())
        if (this.last.length > 80) this.last.shift()
      })
      child.on('error', (err) => {
        this.dead = true
        if (this.pending) this.pending.reject(err)
        if (!this.ready) reject(err)
      })
      child.on('close', (code) => {
        this.dead = true
        this.logStream.end()
        const err = new Error(`${this.name} exited code=${code}`)
        if (this.pending) { this.pending.reject(err); this.pending = null }
        if (!this.ready) reject(err)
      })
      this.readyResolve = resolve
      this.readyReject = reject
    })
  }

  onLine (line) {
    this.logStream.write(line + '\n')
    this.last.push(line)
    if (this.last.length > 80) this.last.shift()
    if (line.startsWith('FM_PREVIEW_WORKER_READY')) {
      this.ready = true
      this.readyResolve()
      return
    }
    if (line.startsWith('FM_PREVIEW_WORKER_DONE')) {
      const m = /code=(\d+)/.exec(line)
      const code = m ? Number(m[1]) : 1
      if (this.pending) {
        const p = this.pending
        this.pending = null
        p.resolve({ code, line, logs: this.last.slice(-40) })
      }
    }
  }

  run (args, timeoutMs) {
    if (this.dead) return Promise.reject(new Error(`${this.name} is dead`))
    if (this.pending) return Promise.reject(new Error(`${this.name} busy`))
    return new Promise((resolve, reject) => {
      const started = now()
      const timer = setTimeout(() => {
        this.pending = null
        this.kill()
        reject(new Error(`${this.name} timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      this.pending = {
        resolve: (v) => { clearTimeout(timer); resolve({ ...v, ms: now() - started }) },
        reject: (e) => { clearTimeout(timer); reject(e) }
      }
      this.child.stdin.write(JSON.stringify(args) + '\n', (err) => {
        if (err) { clearTimeout(timer); this.pending = null; reject(err) }
      })
    })
  }

  kill () {
    this.dead = true
    try { this.child.kill('SIGKILL') } catch {}
    this.logStream.end()
  }
}

function discoverJobs (dataDir, fixtures) {
  const manifest = JSON.parse(fs.readFileSync(path.join(dataDir, 'manifest.json'), 'utf8'))
  const cards = JSON.parse(fs.readFileSync(path.join(dataDir, 'hand', 'cards.json'), 'utf8')).cards
  const jobs = []
  const irStyles = manifest.styles.filter((s) => s && s.style)
  for (const fx of fixtures) {
    for (const style of irStyles) {
      jobs.push({
        kind: 'ir',
        fixture: fx,
        id: style.uiCardId,
        name: safe(style.uiCardId),
        styleId: style.id,
        style: path.join(dataDir, style.style),
        photo: fx === 'canonical' ? path.join(dataDir, 'photo.png') : path.join(fixturesDir, `${fx}.jpg`),
        exif: path.join(dataDir, 'exif.jpg')
      })
    }
    for (const card of cards) {
      jobs.push({
        kind: 'hand',
        fixture: fx,
        id: card.card,
        name: safe(card.card),
        card: card.card,
        photo: fx === 'canonical' ? null : path.join(fixturesDir, `${fx}.jpg`)
      })
    }
  }
  return jobs
}

// fixturesDir is resolved from --out/fixtures unless FM_FIXTURES says otherwise.
let fixturesDir = process.env.FM_FIXTURES || ''

function buildArgs (job, dataDir) {
  const outDir = path.join(OPTS.out, 'renders', job.fixture, job.kind)
  fs.mkdirSync(outDir, { recursive: true })
  const out = path.join(outDir, job.name + '.png')
  const report = path.join(outDir, job.name + '.json')
  if (job.kind === 'ir') {
    return {
      out,
      report,
      args: [
        '--style', job.style,
        '--photo', job.photo,
        '--exif-source', job.exif,
        '--fonts', path.join(dataDir, 'fonts'),
        '--decoded', path.join(dataDir, 'decoded'),
        '--assets', path.join(dataDir, 'assets'),
        '--t1b', dataDir,
        '--out', out,
        '--report', report
      ]
    }
  }
  const args = [
    'render',
    '--card', job.card,
    '--cards', path.join(dataDir, 'hand', 'cards.json'),
    '--hand', path.join(dataDir, 'hand'),
    '--fonts', path.join(dataDir, 'fonts'),
    '--extra-fonts', path.join(dataDir, 'e4-extra-fonts'),
    '--out', out,
    '--report', report
  ]
  if (job.photo) args.push('--photo', job.photo)
  return { out, report, args }
}

async function runPool (kind, jobs, workerCount, dataDir, logDir, exe, results, onProgress) {
  let next = 0
  let failed = 0
  const startedAt = now()
  const total = jobs.length
  async function workerLoop (index) {
    let w = new Worker(exe, dataDir, `${kind}${index}`, index, logDir)
    const ensure = async () => {
      if (w.dead) w = new Worker(exe, dataDir, `${kind}${index}`, index, logDir)
      if (!w.ready) { await w.start(); w.ready = true }
      return w
    }
    while (next < jobs.length) {
      const job = jobs[next++]
      const built = buildArgs(job, dataDir)
      let attempt = 0
      let record = null
      for (;;) {
        try {
          const ww = await ensure()
          const res = await ww.run(built.args, OPTS.timeoutMs)
          record = { ...job, out: built.out, report: built.report, ms: res.ms, code: res.code, logs: res.code === 0 ? undefined : res.logs, attempt }
          if (res.code !== 0 && attempt++ < 1) continue
          break
        } catch (err) {
          record = { ...job, out: built.out, report: built.report, code: -1, error: String(err.message || err), attempt }
          if (attempt++ < 1) { try { w.kill() } catch {}; continue }
          break
        }
      }
      if (record.code !== 0) failed++
      results.push(record)
      const done = results.filter((r) => r.kind === kind).length
      onProgress(kind, done, total, failed, startedAt)
    }
    try { w.kill() } catch {}
  }
  await Promise.all(Array.from({ length: Math.max(1, workerCount) }, (_, i) => workerLoop(i)))
}

const OPTS = parseArgs(process.argv.slice(2))

async function main () {
  const dataDir = path.join(OPTS.rt, 'data')
  if (!fs.existsSync(path.join(dataDir, 'manifest.json'))) throw new Error(`no runtime manifest under ${dataDir}`)
  fixturesDir = process.env.FM_FIXTURES || path.join(OPTS.out, 'fixtures')
  for (const fx of OPTS.fixtures) {
    if (fx === 'canonical') continue
    if (!fs.existsSync(path.join(fixturesDir, `${fx}.jpg`))) throw new Error(`missing fixture ${fx}.jpg under ${fixturesDir}`)
  }
  fs.mkdirSync(OPTS.out, { recursive: true })
  const logDir = path.join(OPTS.out, 'logs')
  fs.mkdirSync(logDir, { recursive: true })

  if (OPTS.resume && fs.existsSync(path.join(OPTS.out, 'results.json'))) {
    const prev = JSON.parse(fs.readFileSync(path.join(OPTS.out, 'results.json'), 'utf8'))
    global.__prior = prev.results || []
  }
  const all = discoverJobs(dataDir, OPTS.fixtures)
  let jobs = all.filter((j) => OPTS.kind === 'all' || j.kind === OPTS.kind)
  if (OPTS.limit > 0) jobs = jobs.slice(0, OPTS.limit)
  // one style/card across fixtures stays adjacent so the worker reuses its caches
  jobs.sort((a, b) => (a.kind + a.id + a.fixture).localeCompare(b.kind + b.id + b.fixture))

  const env = {
    when: new Date().toISOString(),
    runtime: path.resolve(OPTS.rt),
    fixtures: OPTS.fixtures,
    fixtureDir: path.resolve(fixturesDir),
    workers: { ir: OPTS.workersIr, hand: OPTS.workersHand },
    engines: {
      e2b: { path: path.join(OPTS.rt, 'bin', 'e2b.exe'), sha256: sha256(path.join(OPTS.rt, 'bin', 'e2b.exe')) },
      e4hand: { path: path.join(OPTS.rt, 'bin', 'e4hand.exe'), sha256: sha256(path.join(OPTS.rt, 'bin', 'e4hand.exe')) }
    },
    jobs: jobs.length
  }
  fs.writeFileSync(path.join(OPTS.out, 'sweep-env.json'), JSON.stringify(env, null, 2))
  fs.writeFileSync(path.join(OPTS.out, 'jobs.json'), JSON.stringify(jobs, null, 2))
  console.log(`sweep: ${jobs.length} renders (${jobs.filter((j) => j.kind === 'ir').length} ir, ${jobs.filter((j) => j.kind === 'hand').length} hand) -> ${OPTS.out}`)

  const results = []
  let lastPrint = 0
  const onProgress = (kind, done, total, failed, startedAt) => {
    const allDone = results.length
    if (allDone - lastPrint >= 10 || allDone === jobs.length) {
      lastPrint = allDone
      const sec = (now() - startedAt) / 1000
      const rate = allDone / sec
      const eta = rate > 0 ? Math.round((jobs.length - allDone) / rate) : 0
      console.log(`  ${allDone}/${jobs.length} renders (${kind} ${done}/${total}) failed=${failed} ${rate.toFixed(2)}/s eta=${eta}s`)
    }
  }

  const irJobs = jobs.filter((j) => j.kind === 'ir')
  const handJobs = jobs.filter((j) => j.kind === 'hand')
  if (OPTS.kind !== 'hand' && irJobs.length) {
    await runPool('ir', irJobs, OPTS.workersIr, dataDir, logDir, path.join(OPTS.rt, 'bin', 'e2b.exe'), results, onProgress)
  }
  if (OPTS.kind !== 'ir' && handJobs.length) {
    await runPool('hand', handJobs, OPTS.workersHand, dataDir, logDir, path.join(OPTS.rt, 'bin', 'e4hand.exe'), results, onProgress)
  }

  results.sort((a, b) => (a.kind + a.id + a.fixture).localeCompare(b.kind + b.id + b.fixture))
  const summary = {
    when: new Date().toISOString(),
    runtime: env.runtime,
    fixtures: OPTS.fixtures,
    total: results.length,
    ok: results.filter((r) => r.code === 0).length,
    failed: results.filter((r) => r.code !== 0).length,
    byFixture: {},
    byKind: {}
  }
  for (const r of results) {
    summary.byFixture[r.fixture] = summary.byFixture[r.fixture] || { total: 0, ok: 0, failed: 0 }
    summary.byKind[r.kind] = summary.byKind[r.kind] || { total: 0, ok: 0, failed: 0 }
    for (const bucket of [summary.byFixture[r.fixture], summary.byKind[r.kind]]) {
      bucket.total++
      if (r.code === 0) bucket.ok++; else bucket.failed++
    }
  }
  const payload = { summary, results }
  fs.writeFileSync(path.join(OPTS.out, 'results.json'), JSON.stringify(payload, null, 2))
  console.log(`done: ok=${summary.ok} failed=${summary.failed}`)
  if (summary.failed) {
    for (const r of results.filter((x) => x.code !== 0).slice(0, 20)) console.log(`  FAIL ${r.kind} ${r.fixture} ${r.id}: ${r.error || (r.logs || []).slice(-4).join(' | ')}`)
    process.exitCode = 2
  }
}

main().catch((err) => {
  console.error('sweep failed:', err)
  process.exit(3)
})
