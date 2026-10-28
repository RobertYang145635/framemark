#!/usr/bin/env node
/**
 * S7 — hand-template control differential audit.
 *
 * For every control in _research/hand-controls/controls.hand.json this script
 * renders the card's built-in sample fixture once at the control's current
 * value, then once per perturbation of the PARAMETER (never the control), and
 * compares an FNV-1a digest of the output pixels:
 *
 *   live    at least one perturbation changed the digest
 *   no-op   no perturbation changed the digest
 *   unmapped the inventory card has no renderable __fmVariants counterpart
 *   error   the baseline render (or every perturbed render) threw
 *
 * It is a read-only audit: it does not delete or edit a control, a template or
 * a default. The only product-code surface it uses is the debug render API in
 * src/renderer/src/main.tsx (`params`, `text`, `logoFile`, `hash`).
 *
 * Non-vacuity (S9b): before the sweep, the audit's own classify() must call a
 * known-live param live, a synthetic never-read key no-op, a repeated render
 * identical, and both ends of the brandPicker class right. `design.logo` is an
 * input now (scene.ts routes it into the brand slot), so clearing it must move
 * pixels on a card that paints the mark and must not on a card that paints no
 * brand bitmap at all.
 *
 * Usage:
 *   node scripts/control-differential.cjs --limit=20   # pilot, writes pilot.json
 *   node scripts/control-differential.cjs              # full audit, writes s7 artifacts
 *
 * Options (env fallbacks):
 *   --limit=N         audit only the first N mapped controls and write pilot.json
 *   --max-edge=N      hash canvas long edge (default 800; FM_AUDIT_MAX_EDGE)
 *
 * Isolation: throw-away --user-data-dir under _research/s7/profile, one
 * Electron instance, closed in `finally` (same pattern as scripts/e2e.mjs).
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { _electron: electron } = require('playwright-core')

const ROOT = path.resolve(__dirname, '..')
const RESEARCH = path.resolve(ROOT, '..', '_research')
const INVENTORY = path.join(RESEARCH, 'hand-controls', 'controls.hand.json')
const OUT_DIR = path.join(RESEARCH, 's7')
const PROFILE = path.join(OUT_DIR, 'profile')
const PILOT_FILE = path.join(OUT_DIR, 'pilot.json')

// Cards wired by the generated hand panels today (`core/controls/hand.generated.ts`,
// 8 panels / 45 controls). Used only to rank suspicious no-ops, never to filter.
const WIRED_CARDS = new Set([
  'ratio-1',
  'ratio-2',
  'ratio-3',
  'movie-1',
  'movie-2',
  'ticket-1',
  'ticket-2',
  'color-walk',
  'color-reveal',
  'video-cover'
])

function argValue (name, envName, fallback) {
  const hit = process.argv.slice(2).find((a) => a.startsWith(`--${name}=`))
  if (hit) return hit.slice(name.length + 3)
  if (envName && process.env[envName] !== undefined) return process.env[envName]
  return fallback
}

const MAX_EDGE = Math.max(80, Math.floor(Number(argValue('max-edge', 'FM_AUDIT_MAX_EDGE', '800')) || 800))
const LIMIT = Math.max(0, Math.floor(Number(argValue('limit', 'FM_AUDIT_LIMIT', '0')) || 0))
const IS_PILOT = LIMIT > 0

/* ------------------------------------------------------------------------- */
/* Value generation: perturb the param, never the control                     */
/* ------------------------------------------------------------------------- */

function num (value) {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

function targetOf (param) {
  if (param === 'theme') return { scope: 'params', key: 'theme' }
  if (param.startsWith('params.')) return { scope: 'params', key: param.slice('params.'.length) }
  if (param.startsWith('text.')) return { scope: 'text', key: param.slice('text.'.length) }
  if (param === 'design.logo') return { scope: 'logo', key: null }
  return null
}

function optsFor (param, value) {
  const target = targetOf(param)
  if (!target) throw new Error(`unknown control param: ${param}`)
  if (target.scope === 'params') return { params: { [target.key]: value } }
  if (target.scope === 'text') return { text: { [target.key]: value } }
  return { logoFile: value }
}

/** Round to the declared step, clamp to the declared domain. */
function quantize (value, domain) {
  let x = Number(value)
  if (!Number.isFinite(x)) return null
  const step = num(domain.step)
  if (step && step > 0) x = Math.round(x / step) * step
  x = Number(x.toFixed(6))
  const min = num(domain.min)
  const max = num(domain.max)
  if (min !== null) x = Math.max(min, x)
  if (max !== null) x = Math.min(max, x)
  return x
}

/**
 * Keyword vocabularies read from the renderers, used only where the inventory
 * does not expose `domain.options` (these params are declared as free text).
 * Every value is a branch the code actually compares against.
 */
const TEXT_VOCAB = {
  'params.subtitleMode': ['bar', 'in-photo'],
  'params.dotOrientation': ['vertical', 'horizontal'],
  'params.frameMode': ['single', 'split'],
  'params.lensRowMode': ['reference', 'model'],
  'params.rowSet': ['fmm', 'fiso'],
  'params.pillOrder': ['stack', 'inline'],
  'params.brandPlacement': ['footer', 'inside'],
  'params.slots': ['brand', 'title', 'model', 'logo', ''],
  'params.frame': ['{"leftX":0.18}']
}

function textCandidates (control, records) {
  const param = control.param
  const current = control.currentValue === null || control.currentValue === undefined ? '' : String(control.currentValue)
  const out = []
  const push = (value) => {
    const s = String(value)
    if (s === current) return
    if (out.includes(s)) return
    out.push(s)
  }
  // Same param on other cards: those values are known-good UI inputs.
  for (const record of records) {
    for (const c of record.controls) {
      if (c.param !== param || c.scope !== control.scope) continue
      if (c.currentValue === null || c.currentValue === undefined) continue
      push(c.currentValue)
    }
  }
  if (Object.prototype.hasOwnProperty.call(TEXT_VOCAB, param)) {
    for (const value of TEXT_VOCAB[param]) push(value)
  }
  // Colour fields: a saturated magenta is never the current card colour.
  if (/^#[0-9a-f]{6}$/i.test(current)) {
    push('#ff00ff')
    push('#00ff88')
  }
  push('AUDIT-9Q7')
  return out.slice(0, 4)
}

function perturbationsFor (control, records) {
  const domain = control.domain || {}
  const kind = control.kind
  const current = control.currentValue
  const values = []
  const push = (value) => {
    if (value === undefined) return
    if (values.some((v) => v === value)) return
    values.push(value)
  }

  if (kind === 'slider') {
    const cur = num(current) !== null ? num(current) : num(control.default)
    if (cur === null) return values
    const raw = [num(domain.min), num(domain.max), cur * 0.8, cur * 1.2]
    for (const candidate of raw) {
      if (candidate === null) continue
      let value = quantize(candidate, domain)
      if (value === null) continue
      if (value === cur) {
        // ±20% often collapses onto the step grid at 0 or at an endpoint.
        const step = num(domain.step) || Math.max(0.001, Math.abs(cur) * 0.2)
        const nudged = quantize(cur + step, domain)
        if (nudged === null || nudged === cur) continue
        value = nudged
      }
      push(value)
    }
    return values
  }

  if (kind === 'toggle') {
    const on = typeof current === 'boolean' ? current : String(control.default) === 'on'
    push(!on)
    return values
  }

  if (kind === 'themeSwatches' || kind === 'gravitySegment') {
    for (const option of domain.options || []) {
      if (option === current) continue
      push(option)
    }
    return values
  }

  if (kind === 'brandPicker') {
    // S9: `design.logo` is an INPUT — scene.ts maps the bitmap back to its
    // catalogued registry name and resolves it through `styleBrandFile()`.
    // null is the clearing value (no asset needed): on a card that paints the
    // mark it removes it (live); on a card that draws no brand bitmap it
    // cannot reach a pixel (no-op).
    push(null)
    return values
  }

  if (kind === 'text') {
    if (Array.isArray(domain.options) && domain.options.length) {
      for (const option of domain.options) {
        if (option === current) continue
        push(option)
      }
      return values
    }
    for (const candidate of textCandidates(control, records)) push(candidate)
    return values
  }

  return values
}

/* ------------------------------------------------------------------------- */
/* Classification                                                             */
/* ------------------------------------------------------------------------- */

function classify (baseline, runs) {
  if (!baseline || baseline.error) return { verdict: 'error', changed: [], errors: baseline ? [baseline.error] : ['missing baseline'] }
  const changed = []
  const errors = []
  for (const run of runs) {
    if (run.error) {
      errors.push(`${JSON.stringify(run.value)}: ${run.error}`)
      continue
    }
    if (run.hash !== baseline.hash) changed.push(run)
  }
  if (changed.length) return { verdict: 'live', changed, errors }
  if (errors.length && errors.length === runs.length) return { verdict: 'error', changed, errors }
  return { verdict: 'no-op', changed, errors }
}

/* ------------------------------------------------------------------------- */
/* Electron / renderer plumbing                                               */
/* ------------------------------------------------------------------------- */

function assertNoDevElectron () {
  try {
    const out = execFileSync('tasklist', ['/FI', 'IMAGENAME eq electron.exe', '/FO', 'CSV', '/NH'], {
      encoding: 'utf8',
      windowsHide: true
    })
    const rows = out.split(/\r?\n/).filter((l) => l.toLowerCase().includes('electron.exe'))
    if (rows.length) {
      throw new Error(`another Electron instance is running: ${rows.join(' | ')}`)
    }
  } catch (error) {
    if (String(error.message || '').includes('another Electron instance')) throw error
    // tasklist absent / non-Windows: the isolated profile is still the guard.
  }
}

async function renderBatch (page, jobs) {
  return page.evaluate(async (batch) => {
    const api = window.__fmVariants
    if (!api) throw new Error('__fmVariants missing')
    const out = []
    for (const job of batch) {
      const t0 = performance.now()
      try {
        const result = await api.render(job.id, job.opts)
        out.push({ hash: result.data, W: result.W, H: result.H, ms: performance.now() - t0 })
      } catch (error) {
        out.push({ error: String((error && error.message) || error), ms: performance.now() - t0 })
      }
    }
    return out
  }, jobs)
}

function baseOpts (edge = MAX_EDGE) {
  return { hash: true, maxEdge: edge }
}

function short (hash) {
  return hash ? hash.replace(/^fnv1a:/, '') : '—'
}

/**
 * Full-resolution recheck of every 800px no-op. A sub-pixel change can vanish
 * when the canvas is downscaled; re-rendering at native size (`maxEdge` above
 * the canvas long edge keeps scale 1) proves the no-op is not a resolution
 * artefact. Reads control-audit.json, does not change any verdict.
 */
async function recheckNoOps () {
  const auditPath = path.join(OUT_DIR, 'control-audit.json')
  if (!fs.existsSync(auditPath)) throw new Error(`run the full audit first: ${auditPath} missing`)
  const audit = JSON.parse(fs.readFileSync(auditPath, 'utf8'))
  const rows = audit.rows.filter((r) => r.verdict === 'no-op' && r.variant)
  if (!rows.length) throw new Error('no no-op rows to recheck')

  fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(PROFILE, { recursive: true })
  assertNoDevElectron()
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const started = Date.now()
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`, '--disable-gpu'],
    cwd: ROOT,
    env,
    timeout: 60000
  })
  const woken = []
  const failures = []
  let still = 0
  let checks = 0
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    await page.waitForFunction(() => !!(window.__fmVariants && window.__fmStock), null, { timeout: 15000 })

    let index = 0
    while (index < rows.length) {
      // Batch by card so each evaluate is one contiguous card slice.
      const card = rows[index].card
      const group = []
      while (index < rows.length && rows[index].card === card) group.push(rows[index++])

      const jobs = []
      for (const row of group) {
        const target = targetOf(row.param)
        const baselineOpts = row.baseline && row.baseline.value !== null && target.scope !== 'logo'
          ? optsFor(row.param, row.baseline.value)
          : {}
        const offset = jobs.length
        jobs.push({ id: row.variant, opts: { ...baseOpts(100000), ...baselineOpts } })
        for (const probe of row.perturbations) jobs.push({ id: row.variant, opts: { ...baseOpts(100000), ...optsFor(row.param, probe.value) } })
        row.__offset = offset
      }
      const results = await renderBatch(page, jobs)
      checks += jobs.length
      for (const row of group) {
        const baseline = results[row.__offset]
        if (!baseline || baseline.error) {
          failures.push({ card: row.card, param: row.param, error: baseline ? baseline.error : 'missing baseline' })
          continue
        }
        const hits = row.perturbations
          .map((probe, i) => ({ probe, result: results[row.__offset + 1 + i] }))
          .filter(({ result }) => result && result.hash && result.hash !== baseline.hash)
        if (hits.length) {
          woken.push({
            card: row.card,
            param: row.param,
            kind: row.kind,
            baselineHash: baseline.hash,
            changed: hits.map(({ probe, result }) => ({ value: probe.value, hash: result.hash }))
          })
        } else {
          still += 1
        }
      }
    }
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }

  const report = {
    schemaVersion: 'framemark-control-differential/recheck-v1',
    generatedAt: new Date().toISOString(),
    source: 'control-audit.json',
    checkedNoOps: rows.length,
    stillNoOpAtFullRes: still,
    wokenAtFullRes: woken.length,
    renderErrors: failures,
    checks,
    ms: Date.now() - started,
    woken,
    note: 'Native-size recheck (maxEdge above the canvas long edge): a no-op that moves pixels only below the 800px hash would appear in `wokenAtFullRes`.'
  }
  fs.writeFileSync(path.join(OUT_DIR, 'noop-recheck.json'), JSON.stringify(report, null, 2), 'utf8')

  const summaryPath = path.join(OUT_DIR, 'summary.json')
  if (fs.existsSync(summaryPath)) {
    const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'))
    summary.fullResolutionRecheck = {
      checked: report.checkedNoOps,
      stillNoOp: report.stillNoOpAtFullRes,
      woken: report.wokenAtFullRes,
      ms: report.ms,
      artifact: 'noop-recheck.json'
    }
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')
  }
  console.log(JSON.stringify({ checked: rows.length, still, woken: woken.length, ms: report.ms }, null, 2))
}

/**
 * Render determinism census: every renderable card three times at the audit
 * hash size and three times at native size. Cards whose identical requests
 * return different digests cannot support a hash-equality verdict; their rows
 * are flagged rather than trusted.
 */
async function stabilityCheck () {
  fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(PROFILE, { recursive: true })
  assertNoDevElectron()
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const started = Date.now()
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`, '--disable-gpu'],
    cwd: ROOT,
    env,
    timeout: 60000
  })
  const rows = []
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    await page.waitForFunction(() => !!(window.__fmVariants && window.__fmStock), null, { timeout: 15000 })
    const variants = await page.evaluate(() => window.__fmVariants.list())
    for (const variant of variants) {
      const jobs = []
      for (const edge of [MAX_EDGE, 100000]) {
        for (let i = 0; i < 3; i++) jobs.push({ id: variant.id, opts: baseOpts(edge) })
      }
      const results = await renderBatch(page, jobs)
      const auditHashes = results.slice(0, 3).map((r) => r.hash || `error:${r.error}`)
      const nativeHashes = results.slice(3).map((r) => r.hash || `error:${r.error}`)
      rows.push({
        card: variant.id,
        name: variant.name,
        auditHashes,
        nativeHashes,
        auditStable: new Set(auditHashes).size === 1,
        nativeStable: new Set(nativeHashes).size === 1
      })
    }
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }
  const auditUnstable = rows.filter((r) => !r.auditStable)
  const nativeUnstable = rows.filter((r) => !r.nativeStable)
  const report = {
    schemaVersion: 'framemark-control-differential/stability-v1',
    generatedAt: new Date().toISOString(),
    rendersPerCard: 6,
    auditEdge: MAX_EDGE,
    cards: rows.length,
    auditUnstable,
    nativeUnstable,
    ms: Date.now() - started,
    note: 'Identical requests, same session. A card in either list cannot support hash inequality as evidence at that size.'
  }
  fs.writeFileSync(path.join(OUT_DIR, 'stability.json'), JSON.stringify(report, null, 2), 'utf8')
  const summaryPath = path.join(OUT_DIR, 'summary.json')
  if (fs.existsSync(summaryPath)) {
    const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'))
    summary.renderStability = {
      cards: rows.length,
      auditUnstableCards: auditUnstable.map((r) => r.card),
      nativeUnstableCards: nativeUnstable.map((r) => r.card),
      ms: report.ms,
      artifact: 'stability.json'
    }
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')
  }
  console.log(JSON.stringify({ cards: rows.length, auditUnstable: auditUnstable.map((r) => r.card), nativeUnstable: nativeUnstable.map((r) => r.card), ms: report.ms }, null, 2))
}

function mdCell (value) {
  return String(value).replace(/\|/g, '\\|').replace(/\n/g, ' ')
}

function writeProgress (line) {
  const file = path.join(RESEARCH, 'progress', 'S7.md')
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
  // Idempotent: a re-run must not duplicate the checklist.
  if (text.includes(line)) return
  fs.appendFileSync(file, `${line}\n`, 'utf8')
}

/* ------------------------------------------------------------------------- */
/* Main                                                                       */
/* ------------------------------------------------------------------------- */

async function main () {
  if (process.argv.includes('--stability')) {
    await stabilityCheck()
    return
  }
  if (process.argv.includes('--recheck')) {
    await recheckNoOps()
    return
  }
  if (!fs.existsSync(INVENTORY)) throw new Error(`inventory missing: ${INVENTORY}`)
  const inventory = JSON.parse(fs.readFileSync(INVENTORY, 'utf8'))
  const records = inventory.records
  const totalControls = records.reduce((sum, r) => sum + r.controls.length, 0)

  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(PROFILE, { recursive: true })

  assertNoDevElectron()

  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const startedAt = new Date().toISOString()
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`, '--disable-gpu'],
    cwd: ROOT,
    env,
    timeout: 60000
  })

  try {
    const page = await app.firstWindow()
    const pageErrors = []
    page.on('pageerror', (e) => pageErrors.push(`pageerror: ${e.message}`))
    page.on('console', (m) => {
      if (m.type() === 'error') pageErrors.push(`console: ${m.text()}`)
    })
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    await page.waitForFunction(() => !!(window.__fmVariants && window.__fmStock), null, { timeout: 15000 })

    const variants = await page.evaluate(() => window.__fmVariants.list())
    const variantById = new Map(variants.map((v) => [v.id, v]))

    // -----------------------------------------------------------------------
    // Card mapping
    // -----------------------------------------------------------------------
    const unmappedCards = []
    const cardMap = []
    for (const record of records) {
      const variant = variantById.get(record.styleId)
      const mapped = Boolean(variant)
      cardMap.push({
        card: record.styleId,
        name: record.name,
        template: record.template,
        family: record.family,
        variant: mapped ? variant.id : null,
        controls: record.controls.length,
        mapped
      })
      if (!mapped) unmappedCards.push({ card: record.styleId, name: record.name, template: record.template })
    }

    // -----------------------------------------------------------------------
    // Non-vacuity gate: the same classify() must say live to a known-live
    // param and no-op to a known-dead one, and the render must be deterministic.
    // -----------------------------------------------------------------------
    const nonvac = []
    const probeCard = variantById.has('blur-classic') ? 'blur-classic' : (variantById.keys().next().value)
    const liveBaseline = (await renderBatch(page, [{ id: probeCard, opts: { ...baseOpts(), params: { marginPct: 2 } } }]))[0]
    const livePerturbed = (await renderBatch(page, [{ id: probeCard, opts: { ...baseOpts(), params: { marginPct: 12 } } }]))[0]
    const liveClass = classify(liveBaseline, [{ ...livePerturbed, value: 12 }])
    nonvac.push({
      case: 'known-live',
      detail: `${probeCard} params.marginPct 2 -> 12 (blur template reads pct(params.marginPct))`,
      baseline: liveBaseline.hash,
      perturbed: livePerturbed.hash,
      verdict: liveClass.verdict,
      expected: 'live',
      pass: liveClass.verdict === 'live'
    })

    const deadBaseline = (await renderBatch(page, [{ id: probeCard, opts: baseOpts() }]))[0]
    const deadPerturbed = (await renderBatch(page, [{ id: probeCard, opts: { ...baseOpts(), params: { __auditDeadProbe: 12 } } }]))[0]
    const deadClass = classify(deadBaseline, [{ ...deadPerturbed, value: 12 }])
    nonvac.push({
      case: 'known-dead',
      detail: `${probeCard} params.__auditDeadProbe absent -> 12 (no reader)`,
      baseline: deadBaseline.hash,
      perturbed: deadPerturbed.hash,
      verdict: deadClass.verdict,
      expected: 'no-op',
      pass: deadClass.verdict === 'no-op'
    })

    const repeat = (await renderBatch(page, [{ id: probeCard, opts: baseOpts() }]))[0]
    nonvac.push({
      case: 'determinism',
      detail: `${probeCard} identical request rendered twice`,
      baseline: deadBaseline.hash,
      perturbed: repeat.hash,
      verdict: deadBaseline.hash === repeat.hash ? 'identical' : 'drift',
      expected: 'identical',
      pass: deadBaseline.hash === repeat.hash
    })

    // S9b: `design.logo` is an INPUT now — scene.ts maps the bitmap back to a
    // catalogued file and routes it through `styleBrandFile()`'s pick slot, on
    // cards that own a brand slot. The old "design.logo is dead" expectation
    // encoded the pre-S9 renderer and had to fail; the gate now proves both
    // ends of the class with the rows the sweep actually emits:
    //   live  — a brandPicker row on a card that paints the mark: the sweep's
    //           own perturbation (design.logo -> null) must remove the bitmap;
    //   no-op — the same control on a card that provably draws no brand bitmap
    //           (white-3: showLogo:false and no brand band at all), even when
    //           the picker holds a real mark, must not move a pixel.
    const brandPickerRows = records.flatMap((record) =>
      record.controls
        .filter((control) => control.kind === 'brandPicker' && control.param === 'design.logo')
        .map((control) => ({ card: record.styleId, control }))
    )
    const liveBrandRow = brandPickerRows.find((row) => row.card === 'vendor-hasselblad')
    const noopBrandRow = brandPickerRows.find((row) => row.card === 'white-3')
    if (!liveBrandRow || !noopBrandRow) {
      throw new Error('non-vacuity: inventory lost the brandPicker rows on vendor-hasselblad / white-3')
    }

    const brandLiveBaseline = (await renderBatch(page, [{ id: liveBrandRow.card, opts: baseOpts() }]))[0]
    const brandLiveCleared = (await renderBatch(page, [{ id: liveBrandRow.card, opts: { ...baseOpts(), logoFile: null } }]))[0]
    const brandLiveClass = classify(brandLiveBaseline, [{ ...brandLiveCleared, value: null }])
    nonvac.push({
      case: 'brandPicker-live',
      detail: `${liveBrandRow.card} ${liveBrandRow.control.id}: design.logo -> null (card declares vendorLogo hasu_long_black, so clearing the mark must move pixels)`,
      baseline: brandLiveBaseline.hash,
      perturbed: brandLiveCleared.hash,
      verdict: brandLiveClass.verdict,
      expected: 'live',
      pass: brandLiveClass.verdict === 'live'
    })

    // The bitmap is loaded explicitly here: a null current value would make
    // "clear the nothing" vacuous. Even a real pick cannot reach white-3's
    // pixels, which is exactly the no-op end the instrument must still see.
    const brandNoopPicked = (await renderBatch(page, [{ id: noopBrandRow.card, opts: { ...baseOpts(), logoFile: 'hasu_long_black' } }]))[0]
    const brandNoopCleared = (await renderBatch(page, [{ id: noopBrandRow.card, opts: { ...baseOpts(), logoFile: null } }]))[0]
    const brandNoopClass = classify(brandNoopPicked, [{ ...brandNoopCleared, value: null }])
    nonvac.push({
      case: 'brandPicker-no-op',
      detail: `${noopBrandRow.card} ${noopBrandRow.control.id}: design.logo hasu_long_black -> null (showLogo:false, no brand band; nothing a bitmap could reach)`,
      baseline: brandNoopPicked.hash,
      perturbed: brandNoopCleared.hash,
      verdict: brandNoopClass.verdict,
      expected: 'no-op',
      pass: brandNoopClass.verdict === 'no-op'
    })

    // The pick slot those rows depend on must itself be alive: the declared
    // consumer (`params.brandLogoFile`) on the same card must move pixels.
    const brandParam = (await renderBatch(page, [{ id: liveBrandRow.card, opts: { ...baseOpts(), params: { brandLogoFile: 'lumo_black' } } }]))[0]
    const brandParamClass = classify(brandLiveBaseline, [{ ...brandParam, value: 'lumo_black' }])
    nonvac.push({
      case: 'brandLogoFile-live',
      detail: `${liveBrandRow.card} params.brandLogoFile=lumo_black (the declared pick slot overrides the variant's hasu_long_black)`,
      baseline: brandLiveBaseline.hash,
      perturbed: brandParam.hash,
      verdict: brandParamClass.verdict,
      expected: 'live',
      pass: brandParamClass.verdict === 'live'
    })

    const nonvacPass = nonvac.every((c) => c.pass)
    const nonvacText = [
      'S7 non-vacuity gate',
      `run at: ${startedAt}`,
      `harness: the audit's own classify() (hash inequality vs baseline)`,
      '',
      ...nonvac.map((c) => `[${c.pass ? 'PASS' : 'FAIL'}] ${c.case}: ${c.detail}\n       baseline=${c.baseline} perturbed=${c.perturbed} verdict=${c.verdict} expected=${c.expected}`),
      '',
      `determinism: ${nonvac[2].pass ? 'baseline repeated with identical hash' : 'BASELINE DRIFTED'}`,
      `RESULT: ${nonvacPass ? 'PASS' : 'FAIL'} (${nonvac.filter((c) => c.pass).length}/${nonvac.length} expectations)`,
      ''
    ].join('\n')
    fs.writeFileSync(path.join(OUT_DIR, 'nonvacuity.txt'), nonvacText, 'utf8')
    if (!nonvacPass) throw new Error(`non-vacuity gate failed:\n${nonvacText}`)
    writeProgress(`- [x] Non-vacuity PASS (live marginPct changes, synthetic dead key does not, repeat render identical, brandPicker live on a marked card / no-op on a card with no brand bitmap).`)

    // -----------------------------------------------------------------------
    // Render sweep
    // -----------------------------------------------------------------------
    const rows = []
    const skipped = []
    const renderErrors = []
    let renderCount = 0
    let renderMs = 0
    let controlCount = 0
    const sweepStarted = Date.now()

    for (const record of records) {
      if (LIMIT && controlCount >= LIMIT) break
      const variant = variantById.get(record.styleId)
      if (!variant) {
        for (const control of record.controls) {
          rows.push({
            card: record.styleId,
            cardName: record.name,
            template: record.template,
            variant: null,
            controlId: control.id,
            param: control.param,
            kind: control.kind,
            scope: control.scope,
            currentValue: control.currentValue,
            verdict: 'unmapped',
            baseline: null,
            perturbations: [],
            changedBy: [],
            errors: [],
            reason: 'inventory card has no renderable __fmVariants counterpart'
          })
          controlCount += 1
        }
        continue
      }

      const controls = LIMIT ? record.controls.slice(0, Math.max(0, LIMIT - controlCount)) : record.controls
      if (!controls.length) break

      // One batch per card keeps the Electron round-trips low.
      const jobs = []
      const plans = []
      for (const control of controls) {
        const target = targetOf(control.param)
        if (!target) {
          skipped.push({ card: record.styleId, controlId: control.id, param: control.param, reason: 'param scope not handled by the debug API' })
          controlCount += 1
          continue
        }
        const values = perturbationsFor(control, records)
        if (!values.length) {
          skipped.push({ card: record.styleId, controlId: control.id, param: control.param, reason: 'no distinct perturbation value inside the declared domain' })
          controlCount += 1
          continue
        }
        const hasCurrent = control.currentValue !== null && control.currentValue !== undefined
        const baselineOpts = hasCurrent && target.scope !== 'logo' ? optsFor(control.param, control.currentValue) : {}
        const baselineJob = { id: variant.id, opts: { ...baseOpts(), ...baselineOpts } }
        const perturbJobs = values.map((value) => ({ id: variant.id, opts: { ...baseOpts(), ...optsFor(control.param, value) } }))
        const offset = jobs.length
        jobs.push(baselineJob, ...perturbJobs)
        plans.push({ control, target, values, offset, baselineValue: hasCurrent ? control.currentValue : null })
      }

      if (!jobs.length) continue
      const results = await renderBatch(page, jobs)
      const before = renderMs
      for (const result of results) renderMs += Number(result.ms) || 0
      renderCount += results.length
      void before

      for (const plan of plans) {
        const baseline = results[plan.offset]
        const runs = plan.values.map((value, index) => ({ ...results[plan.offset + 1 + index], value }))
        const outcome = classify(baseline, runs)
        if (outcome.verdict === 'error') {
          renderErrors.push({ card: record.styleId, controlId: plan.control.id, error: (outcome.errors || []).join(' | ') })
        }
        rows.push({
          card: record.styleId,
          cardName: record.name,
          template: record.template,
          variant: variant.id,
          controlId: plan.control.id,
          param: plan.control.param,
          kind: plan.control.kind,
          scope: plan.control.scope,
          currentValue: plan.control.currentValue,
          verdict: outcome.verdict,
          baseline: baseline
            ? { value: plan.baselineValue, hash: baseline.hash || null, W: baseline.W, H: baseline.H, error: baseline.error || null }
            : null,
          perturbations: runs.map((run) => ({
            value: run.value,
            hash: run.hash || null,
            changed: Boolean(baseline && run.hash && run.hash !== baseline.hash),
            error: run.error || null
          })),
          changedBy: outcome.changed.map((run) => run.value),
          errors: outcome.errors
        })
        controlCount += 1
      }
    }

    const sweepMs = Date.now() - sweepStarted
    const pageErrorsAfter = pageErrors.slice()

    // -----------------------------------------------------------------------
    // Totals + artifacts
    // -----------------------------------------------------------------------
    const totals = { live: 0, 'no-op': 0, unmapped: 0, error: 0 }
    for (const row of rows) totals[row.verdict] = (totals[row.verdict] || 0) + 1
    const byKind = {}
    const byScope = {}
    for (const row of rows) {
      const kind = (byKind[row.kind] = byKind[row.kind] || { live: 0, 'no-op': 0, unmapped: 0, error: 0, total: 0 })
      kind[row.verdict] = (kind[row.verdict] || 0) + 1
      kind.total += 1
      const scope = (byScope[row.scope] = byScope[row.scope] || { live: 0, 'no-op': 0, unmapped: 0, error: 0, total: 0 })
      scope[row.verdict] = (scope[row.verdict] || 0) + 1
      scope.total += 1
    }

    // Params live on at least one card but dead on another are the suspicious ones.
    const liveByParam = {}
    const rowsByParam = {}
    for (const row of rows) {
      if (row.verdict !== 'live' && row.verdict !== 'no-op') continue
      rowsByParam[row.param] = rowsByParam[row.param] || []
      rowsByParam[row.param].push(row)
      if (row.verdict === 'live') liveByParam[row.param] = (liveByParam[row.param] || 0) + 1
    }
    const suspiciousScored = rows
      .filter((row) => row.verdict === 'no-op')
      .map((row) => {
        let score = 0
        const liveElsewhere = liveByParam[row.param] || 0
        if (liveElsewhere > 0) score += 10
        // S9: `design.logo` is an input, not a dead param. A no-op brandPicker
        // row means this card paints no brand bitmap (no slot, or showLogo
        // off) — still worth reviewing, but no longer proof of mis-wiring.
        if (row.scope === 'design-logo') score += 15
        if (row.kind === 'toggle') score += 3
        if (WIRED_CARDS.has(row.card)) score += 2
        if (row.currentValue !== null && row.currentValue !== undefined && row.currentValue !== '') score += 1
        return { row, score, liveElsewhere, sameParam: (rowsByParam[row.param] || []).length }
      })
      .sort((a, b) => b.score - a.score || b.liveElsewhere - a.liveElsewhere || a.row.card.localeCompare(b.row.card))
    // Keep the list scannable: at most two rows per param class, so one 43-card
    // class cannot fill all twenty slots.
    const perParam = {}
    const suspicious = []
    for (const item of suspiciousScored) {
      const key = `${item.row.scope}|${item.row.param}`
      perParam[key] = (perParam[key] || 0) + 1
      if (perParam[key] > 2) continue
      suspicious.push(item)
      if (suspicious.length >= 20) break
    }
    const suspiciousRows = suspicious.map(({ row, liveElsewhere, sameParam }) => ({
      card: row.card,
      cardName: row.cardName,
      controlId: row.controlId,
      param: row.param,
      kind: row.kind,
      currentValue: row.currentValue,
      liveElsewhere,
      sameParamControls: sameParam,
      probes: row.perturbations.length,
      baselineHash: row.baseline ? row.baseline.hash : null
    }))

    // Class view: the same no-op multiplied across cards is one decision, not 43.
    const noOpByParam = Object.entries(rowsByParam)
      .map(([param, paramRows]) => {
        const noops = paramRows.filter((r) => r.verdict === 'no-op')
        return {
          param,
          scope: paramRows[0].scope,
          kind: paramRows[0].kind,
          controls: paramRows.length,
          noop: noops.length,
          liveElsewhere: liveByParam[param] || 0,
          cards: noops.slice(0, 8).map((r) => r.card)
        }
      })
      .filter((entry) => entry.noop > 0)
      .sort((a, b) => b.noop - a.noop || a.param.localeCompare(b.param))

    const pilot = fs.existsSync(PILOT_FILE) ? JSON.parse(fs.readFileSync(PILOT_FILE, 'utf8')) : null
    const cost = {
      maxEdge: MAX_EDGE,
      fixture: 'built-in sample (1600x1067 LUMIX S1, no file fixture)',
      pilot: pilot && pilot.cost ? pilot.cost : null,
      run: {
        controls: controlCount,
        renders: renderCount,
        renderMs: Math.round(renderMs),
        sweepMs,
        msPerControl: controlCount ? Number((renderMs / controlCount).toFixed(1)) : null,
        msPerRender: renderCount ? Number((renderMs / renderCount).toFixed(1)) : null
      }
    }

    const coverage = {
      cardsTotal: records.length,
      cardsWithVariant: cardMap.filter((c) => c.mapped).length,
      cardsUnmapped: unmappedCards.length,
      controlsTotal: totalControls,
      controlsAudited: rows.filter((r) => r.verdict !== 'unmapped').length,
      controlsUnmapped: rows.filter((r) => r.verdict === 'unmapped').length,
      controlsSkipped: skipped.length,
      controlsErrored: rows.filter((r) => r.verdict === 'error').length
    }

    const audit = {
      schemaVersion: 'framemark-control-differential/v1',
      generatedAt: new Date().toISOString(),
      startedAt,
      repo: ROOT,
      mode: IS_PILOT ? 'pilot' : 'full',
      inventory: { path: INVENTORY, records: records.length, controls: totalControls },
      render: cost,
      coverage,
      unmappedCards,
      cards: cardMap,
      rows,
      skipped,
      errors: { render: renderErrors, page: pageErrorsAfter },
      nonvacuity: nonvac,
      note: 'Read-only differential: no control, template, default or inventory entry was modified.'
    }

    if (IS_PILOT) {
      const out = {
        schemaVersion: audit.schemaVersion,
        mode: 'pilot',
        generatedAt: audit.generatedAt,
        maxEdge: MAX_EDGE,
        cost: {
          controls: controlCount,
          renders: renderCount,
          renderMs: Math.round(renderMs),
          msPerControl: cost.run.msPerControl,
          msPerRender: cost.run.msPerRender,
          projectedFullRenders: 3994,
          projectedFullMs: controlCount ? Math.round((renderMs / controlCount) * (totalControls + 2)) : null
        },
        nonvacuity: nonvac,
        rows
      }
      fs.writeFileSync(PILOT_FILE, JSON.stringify(out, null, 2), 'utf8')
      console.log(`PILOT ok: ${controlCount} controls / ${renderCount} renders in ${sweepMs}ms; ${cost.run.msPerControl} ms/control, ${cost.run.msPerRender} ms/render`)
      return
    }

    // control-audit.json ----------------------------------------------------
    fs.writeFileSync(path.join(OUT_DIR, 'control-audit.json'), JSON.stringify(audit, null, 2), 'utf8')

    // summary.json ----------------------------------------------------------
    const summary = {
      schemaVersion: audit.schemaVersion,
      generatedAt: audit.generatedAt,
      totals,
      coverage,
      unmappedCards,
      byKind,
      byScope,
      cost,
      nonvacuity: {
        passed: nonvacPass,
        checks: nonvac.map((c) => ({ case: c.case, verdict: c.verdict, expected: c.expected, pass: c.pass }))
      },
      suspiciousNoOps: suspiciousRows,
      noOpByParam,
      artifacts: {
        json: 'control-audit.json',
        table: 'control-audit.md',
        nonvacuity: 'nonvacuity.txt'
      }
    }
    fs.writeFileSync(path.join(OUT_DIR, 'summary.json'), JSON.stringify(summary, null, 2), 'utf8')

    // control-audit.md ------------------------------------------------------
    const lines = []
    lines.push('# S7 — hand-template control differential audit')
    lines.push('')
    lines.push(`Generated: ${audit.generatedAt}`)
    lines.push('')
    lines.push('Fixture: ' + cost.fixture + `. Hash: FNV-1a over the RGBA bytes of the ${MAX_EDGE}px-long-edge render. Rasterizer: \`--disable-gpu\` software raster (GPU raster drifted on blur cards; see stability.json).`)
    lines.push('Method: baseline = the control\'s current value; each perturbation changes only the parameter. `live` = at least one perturbation produced a different digest.')
    lines.push('')
    lines.push('Scope: `live` means the parameter moves pixels through the debug render API. It does not mean the control is exposed in the UI — `Inspector.tsx` still renders only the six built-in chips plus `HandControlsPanel` (45 controls).')
    lines.push('')
    lines.push('## Totals')
    lines.push('')
    lines.push('| verdict | controls |')
    lines.push('| --- | ---: |')
    for (const key of ['live', 'no-op', 'unmapped', 'error']) lines.push(`| ${key} | ${totals[key] || 0} |`)
    lines.push(`| **all** | **${rows.length}** |`)
    lines.push('')
    lines.push(`Coverage: ${coverage.cardsWithVariant}/${coverage.cardsTotal} cards renderable, ${coverage.controlsAudited}/${coverage.controlsTotal} controls audited, ${coverage.controlsSkipped} skipped, ${coverage.controlsUnmapped} unmapped.`)
    lines.push(`Unmapped cards: ${unmappedCards.length ? unmappedCards.map((c) => `\`${c.card}\``).join(', ') : 'none'}.`)
    lines.push('')
    lines.push('Verdicts by kind / scope are in summary.json. Evidence hashes are the full 32-bit digests from control-audit.json; the table shows the baseline digest and the first probe that moved it.')
    lines.push('')
    lines.push('Reading `no-op`: the shadowed control does not move a pixel **in this card\'s current declared state**. Some no-ops are gated by another parameter rather than by a missing reader — e.g. `text.title` while `params.slots` omits `title`, `params.exif*` while `overlayMode` is `brand`, `params.topPadPct` outside the h4/l3 vendor style. Perturbing the gate would wake them up, so a no-op is evidence against deleting a control only after checking its card\'s gate params (see `noOpByParam` and the card table in summary.json).')
    lines.push('')

    let currentCard = null
    for (const row of rows) {
      if (row.card !== currentCard) {
        if (currentCard !== null) lines.push('')
        currentCard = row.card
        const cardRows = rows.filter((r) => r.card === row.card)
        const counts = { live: 0, 'no-op': 0, unmapped: 0, error: 0 }
        for (const r of cardRows) counts[r.verdict] = (counts[r.verdict] || 0) + 1
        lines.push(`## ${row.card} — ${row.cardName} (${row.template})`)
        lines.push('')
        lines.push(`live ${counts.live} / no-op ${counts['no-op']} / unmapped ${counts.unmapped} / error ${counts.error}`)
        lines.push('')
        lines.push('| control | param | kind | current | verdict | baseline | changed probe |')
        lines.push('| --- | --- | --- | --- | --- | --- | --- |')
      }
      let probe = '—'
      if (row.verdict === 'live') {
        const first = row.perturbations.find((p) => p.changed)
        probe = first ? `${mdCell(JSON.stringify(first.value))} → ${short(first.hash)}` : '—'
      } else if (row.verdict === 'unmapped') {
        probe = 'no renderable variant'
      } else if (row.verdict === 'error') {
        probe = mdCell((row.errors || []).join('; ') || 'render error')
      } else {
        probe = `${row.perturbations.length} probe(s), all = ${short(row.baseline && row.baseline.hash)}`
      }
      lines.push(
        `| ${mdCell(row.controlId)} | ${mdCell(row.param)} | ${row.kind} | ${mdCell(JSON.stringify(row.currentValue))} | **${row.verdict}** | ${short(row.baseline && row.baseline.hash)} | ${probe} |`
      )
    }
    lines.push('')
    if (skipped.length) {
      lines.push('## Skipped')
      lines.push('')
      for (const s of skipped) lines.push(`- ${s.card} · ${s.controlId} (${s.param}): ${s.reason}`)
      lines.push('')
    }
    fs.writeFileSync(path.join(OUT_DIR, 'control-audit.md'), lines.join('\n'), 'utf8')

    writeProgress(`- [x] Full sweep: ${controlCount} controls, ${renderCount} renders, ${cost.run.msPerControl} ms/control.`)
    writeProgress(`- [x] Verdicts: live ${totals.live} / no-op ${totals['no-op']} / unmapped ${totals.unmapped} / error ${totals.error}.`)
    writeProgress(`- [x] Artifacts: control-audit.json, control-audit.md, summary.json, nonvacuity.txt.`)
    console.log(JSON.stringify({ totals, coverage, cost: cost.run, nonvacuity: nonvacPass }, null, 2))
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }
}

main().catch((error) => {
  console.error(`S7 audit failed: ${error && error.stack ? error.stack : error}`)
  process.exitCode = 1
})
