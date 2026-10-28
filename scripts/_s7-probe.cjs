#!/usr/bin/env node
/**
 * S7 one-off probe: is the glass-liquid `design.logo` recheck flip real, or is
 * the glass render nondeterministic? Renders the same card repeatedly, with and
 * without design.logo, at both the audit hash size and native size.
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { _electron: electron } = require('playwright-core')

const ROOT = path.resolve(__dirname, '..')
const OUT = path.join(path.resolve(ROOT, '..'), '_research', 's7')
const PROFILE = path.join(OUT, 'probe-profile')

async function main () {
  fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(PROFILE, { recursive: true })
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`],
    cwd: ROOT,
    env,
    timeout: 60000
  })
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    await page.evaluate(() => document.fonts.ready.then(() => true))
    await page.waitForFunction(() => !!(window.__fmVariants && window.__fmStock), null, { timeout: 15000 })
    const runs = await page.evaluate(async () => {
      const api = window.__fmVariants
      const out = []
      const cases = [
        { name: 'native-base', opts: { hash: true, maxEdge: 100000 } },
        { name: 'native-logo-null', opts: { hash: true, maxEdge: 100000, logoFile: null } },
        { name: 'audit-base', opts: { hash: true, maxEdge: 800 } },
        { name: 'audit-logo-null', opts: { hash: true, maxEdge: 800, logoFile: null } }
      ]
      for (let i = 0; i < 5; i++) {
        for (const c of cases) {
          const r = await api.render('glass-liquid', c.opts)
          out.push({ i, name: c.name, hash: r.data, W: r.W, H: r.H })
        }
      }
      return out
    })
    fs.writeFileSync(path.join(OUT, 'probe-glass-liquid.json'), JSON.stringify(runs, null, 2), 'utf8')
    const grouped = {}
    for (const r of runs) {
      grouped[r.name] = grouped[r.name] || new Set()
      grouped[r.name].add(r.hash)
    }
    console.log(Object.fromEntries(Object.entries(grouped).map(([k, v]) => [k, [...v]])))
  } finally {
    await app.close().catch(() => undefined)
    fs.rmSync(PROFILE, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 })
  }
}

main().catch((e) => {
  console.error(e && e.stack ? e.stack : e)
  process.exitCode = 1
})
