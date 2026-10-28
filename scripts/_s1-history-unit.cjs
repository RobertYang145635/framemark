/**
 * S1 unit gate for core/history.ts (no Electron, no browser).
 *
 * The module is pure (no React / no zustand), so it is bundled with the repo's
 * own esbuild and exercised directly: coalescing window, forced merging inside a
 * gesture, distinct keys, undo/redo bookkeeping, the 50-step cap, txn ids, clear.
 *
 * Usage: node scripts/_s1-history-unit.cjs   (exit 0 = all PASS)
 */
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')
const esbuild = require('esbuild')

const root = path.resolve(__dirname, '..')
const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'fm-history-'))
const outfile = path.join(outDir, 'history.cjs')
esbuild.buildSync({
  entryPoints: [path.join(root, 'src/renderer/src/core/history.ts')],
  outfile,
  bundle: true,
  format: 'cjs',
  platform: 'node',
  logLevel: 'warning'
})
const history = require(outfile)

const failures = []
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`)
  if (!ok) failures.push(name)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const design = (tag) => ({ templateId: 'probe', variantId: tag, params: {}, tag })
const tag = (d) => (d ? d.tag : null)

;(async () => {
  history.clear()
  check('limit constant is 50', history.HISTORY_LIMIT === 50)
  check('undo on an empty stack returns null', history.undo(design('x')) === null)

  // coalescing window
  history.push({ label: 'A', key: 'param:x', before: design('d0') })
  history.push({ label: 'A2', key: 'param:x', before: design('d1') })
  check('same key inside 600 ms merges', history.size() === 1, `size=${history.size()}`)
  check('merged step keeps the OLDEST before', tag(history.undo(design('d2'))) === 'd0')
  history.redo(design('d0'))

  // different key never merges
  history.push({ label: 'B', key: 'param:y', before: design('d1') })
  check('different key opens a new step', history.size() === 2, `size=${history.size()}`)

  // window expiry
  await sleep(650)
  history.push({ label: 'B2', key: 'param:y', before: design('d2') })
  check('same key after 600 ms opens a new step', history.size() === 3, `size=${history.size()}`)

  // gesture forces merging regardless of the window
  history.beginGesture()
  history.push({ label: 'C', key: 'param:y', before: design('d3') })
  await sleep(700)
  history.push({ label: 'C2', key: 'param:y', before: design('d4') })
  history.endGesture()
  check('gesture forces same-key merging past 600 ms', history.size() === 4, `size=${history.size()}`)
  await sleep(650)
  history.push({ label: 'D', key: 'param:y', before: design('d5') })
  check('after endGesture the window applies again', history.size() === 5, `size=${history.size()}`)

  // undo/redo bookkeeping
  const undone = history.undo(design('d6'))
  check('undo returns the popped before', tag(undone) === 'd5', `got=${tag(undone)}`)
  check('undo parks the live design on the redo stack', history.redoSize() === 1)
  const redone = history.redo(design('d5'))
  check('redo returns the design the user was looking at', tag(redone) === 'd6', `got=${tag(redone)}`)
  check('redoSize drops back to 0', history.redoSize() === 0)
  history.clear()

  // cap
  for (let i = 0; i < 60; i++) history.push({ label: `L${i}`, key: `k${i}`, before: design(`c${i}`) })
  check('ring buffer caps at 50', history.size() === 50, `size=${history.size()}`)
  const capsLabels = history.labels()
  check('cap drops the OLDEST entries', capsLabels[0] === 'L10' && capsLabels[49] === 'L59', `${capsLabels[0]}..${capsLabels[49]}`)

  // any new push clears redo
  history.undo(design('t'))
  history.push({ label: 'after-undo', key: 'fresh', before: design('t2') })
  check('a new push clears the redo stack', history.redoSize() === 0)

  // txn ids
  const txn = history.openTxn()
  check('openTxn reports open', history.isOpenTxn(txn) === true)
  history.closeTxn(txn)
  check('closeTxn reports closed', history.isOpenTxn(txn) === false)
  check('unknown txn id is not open', history.isOpenTxn(txn + 999) === false)

  history.clear()
  check('clear empties both stacks', history.size() === 0 && history.redoSize() === 0 && history.labels().length === 0)

  fs.rmSync(outDir, { recursive: true, force: true })
  console.log('')
  console.log(failures.length === 0 ? `GREEN  ${failures.length} failures` : `RED  ${failures.length} failures -> ${failures.join(' | ')}`)
  if (failures.length) process.exit(1)
})().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
