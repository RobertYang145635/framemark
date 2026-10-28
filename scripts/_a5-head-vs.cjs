// Supervisor check: does HEAD's draw-op stream match a given baseline tag?
// Usage: node scripts/_a5-head-vs.cjs <headDir> <baseDir>
const fs = require('node:fs')
const path = require('node:path')

const [, , headDir, baseDir] = process.argv
const strip = (v) => JSON.parse(JSON.stringify(v), (k, val) => (k === 'src' ? undefined : val))

function load(dir) {
  const m = new Map()
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('.json')) continue
    try {
      m.set(f.replace(/\.json$/, ''), strip(JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'))))
    } catch (e) {
      m.set(f.replace(/\.json$/, ''), { loadError: String(e.message) })
    }
  }
  return m
}

const head = load(headDir)
const base = load(baseDir)
const ids = [...head.keys()].filter((k) => base.has(k)).sort()
const same = []
const diff = []
for (const id of ids) {
  const a = JSON.stringify(head.get(id))
  const b = JSON.stringify(base.get(id))
  if (a === b) same.push(id)
  else diff.push(id)
}
console.log(
  JSON.stringify(
    {
      headDir: path.basename(headDir),
      baseDir: path.basename(baseDir),
      headCount: head.size,
      baseCount: base.size,
      shared: ids.length,
      identical: same.length,
      differ: diff.length,
      missingInBase: [...head.keys()].filter((k) => !base.has(k)).length,
      differing: diff
    },
    null,
    1
  )
)
