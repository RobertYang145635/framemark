#!/usr/bin/env node
/**
 * S7 follow-up: the 43 brandPicker controls declare `param: design.logo`.
 * The audit perturbed them to null; this probe sets each to a different VALID
 * brand-registry file, which would move pixels if any renderer read
 * design.logo. Expected (from scene.ts:191-221): 0 live.
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const ROOT = path.resolve(__dirname, '..')
const RESEARCH = path.resolve(ROOT, '..', '_research')
const OUT = path.join(RESEARCH, 's7')
const PROFILE = path.join(OUT, 'brand-alt-profile')
const INVENTORY = path.join(RESEARCH, 'hand-controls', 'controls.hand.json')
const CANDIDATES = ['lumo_black', 'hasu_long_black', 'zeiss', 'laika_long']

async function main () {
  const inventory = JSON.parse(fs.readFileSync(INVENTORY, 'utf8'))
  const cards = inventory.records
    .filter((r) => r.controls.some((c) => c.kind === 'brandPicker'))
    .map((r) => {
      const control = r.controls.find((c) => c.kind === 'brandPicker')
      const current = String(control.currentValue || '')
      const alternate = CANDIDATES.find((f) => f !== current)
      return { card: r.styleId, current, alternate }
    })

  fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(PROFILE, { recursive: true })
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`, '--disable-gpu'],
    cwd: ROOT,
    env,
    timeout: 60000
  })
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    await page.waitForFunction(() => !!(window.__fmVariants && window.__fmStock), null, { timeout: 15000 })
    const rows = []
    for (const entry of cards) {
      const results = await page.evaluate(async ({ id, opts }) => {
        const out = []
        for (const o of opts) {
          const r = await window.__fmVariants.render(id, o)
          out.push(r.data)
        }
        return out
      }, { id: entry.card, opts: [{ hash: true, maxEdge: 800 }, { hash: true, maxEdge: 800, logoFile: entry.alternate }] })
      rows.push({
        card: entry.card,
        current: entry.current,
        alternate: entry.alternate,
        baselineHash: results[0],
        alternateHash: results[1],
        changed: results[0] !== results[1]
      })
    }
    const report = {
      generatedAt: new Date().toISOString(),
      controls: rows.length,
      liveWithValidAlternate: rows.filter((r) => r.changed).map((r) => r.card),
      rows
    }
    fs.writeFileSync(path.join(OUT, 'brand-picker-alternate.json'), JSON.stringify(report, null, 2), 'utf8')
    console.log(JSON.stringify({ controls: report.controls, live: report.liveWithValidAlternate.length }, null, 2))
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : e)
  process.exitCode = 1
})
