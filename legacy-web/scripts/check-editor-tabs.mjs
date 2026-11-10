/**
 * Phase 3 audit — the 439 per-style controls (106 records) must partition across
 * the four editor tabs exactly once, with every control assigned and none lost.
 *
 * Runs the REAL modules (`core/editor/capabilities.ts`, `core/controls`) through
 * esbuild, so this is not a re-implementation of the mapping. Node-only helper;
 * the app never imports it.
 *
 * The bundle pulls the template/variant modules, which use Vite `import.meta.glob`
 * for asset maps. Node has no such API, so the bundle's `import_meta` object is
 * stubbed to return empty maps — the control partition does not read those maps.
 *
 *   node scripts/check-editor-tabs.mjs
 */
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import esbuild from 'esbuild'

const root = path.resolve(import.meta.dirname, '..')
const TABS = ['logo', 'text', 'layout', 'theme']

const entry = `
export { editorTabForControl } from './src/renderer/src/core/editor/capabilities.ts'
export { inspectControl, CONTROL_ITEM_COUNT } from './src/renderer/src/core/controls/index.ts'
`

const bundle = await esbuild.build({
  stdin: { contents: entry, resolveDir: root, sourcefile: 'editor-tabs-audit-entry.ts', loader: 'ts' },
  bundle: true,
  write: false,
  format: 'cjs',
  platform: 'node',
  target: 'node20',
  logLevel: 'silent'
})
const code = Buffer.from(bundle.outputFiles[0].contents)
  .toString()
  .replace(/\bimport_meta\d*\b/g, 'import_meta')
  .replace(/var import_meta = \{\};/g, 'var import_meta = { glob: () => ({}) };')
const mod = { exports: {} }
new Function('module', 'exports', 'require', code)(mod, mod.exports, createRequire(import.meta.url))

const { editorTabForControl, inspectControl, CONTROL_ITEM_COUNT } = mod.exports

const dataDir = path.join(root, 'src/renderer/src/core/controls/data')
const records = ['oppo', 'xiaomi', 'vivo'].flatMap((family) => {
  const file = JSON.parse(fs.readFileSync(path.join(dataDir, `controls.${family}.json`), 'utf8'))
  return file.records
})
const byStyle = new Map()
for (const record of records) if (!byStyle.has(record.styleId)) byStyle.set(record.styleId, record)

let items = 0
let unassigned = 0
const perTab = Object.fromEntries(TABS.map((tab) => [tab, { total: 0, live: 0 }]))
const combinations = new Map()

for (const record of byStyle.values()) {
  const views = record.controls.map((control) => inspectControl({}, control, record.styleId))
  items += views.length

  const seen = new Set()
  const tabsHere = new Set()
  for (const view of views) {
    const tab = editorTabForControl(view)
    if (!TABS.includes(tab)) {
      unassigned++
      continue
    }
    assert.ok(!seen.has(view.control.id), `${record.styleId}: control ${view.control.id} assigned twice`)
    seen.add(view.control.id)
    perTab[tab].total++
    if (view.live) perTab[tab].live++
    tabsHere.add(tab)
  }
  assert.equal(seen.size, views.length, `${record.styleId}: ${views.length - seen.size} control(s) unassigned`)
  combinations.set(TABS.filter((tab) => tabsHere.has(tab)).join('+'), (combinations.get(TABS.filter((tab) => tabsHere.has(tab)).join('+')) ?? 0) + 1)
}

const assigned = TABS.reduce((n, tab) => n + perTab[tab].total, 0)
assert.equal(CONTROL_ITEM_COUNT, 439, `control item count changed: ${CONTROL_ITEM_COUNT}`)
assert.equal(items, CONTROL_ITEM_COUNT, `records enumerate ${items} controls, index says ${CONTROL_ITEM_COUNT}`)
assert.equal(unassigned, 0, `${unassigned} control(s) returned no editor tab`)
assert.equal(assigned, items, `${assigned}/${items} controls assigned`)

console.log(
  JSON.stringify(
    {
      records: byStyle.size,
      items,
      controlItemCount: CONTROL_ITEM_COUNT,
      unassigned,
      perTab,
      liveTotal: TABS.reduce((n, tab) => n + perTab[tab].live, 0),
      tabCombinations: Object.fromEntries([...combinations.entries()].sort())
    },
    null,
    2
  )
)
