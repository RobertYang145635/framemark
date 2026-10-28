/**
 * A3 gate 4 — compare the before/after encode baselines.
 *
 * Both JSONs come from scripts/_a3-baseline-encode.cjs with the same fixture
 * (test-assets/ratio-study/4x3.jpg) and the default design. Byte identity means
 * the sha256 of each output is unchanged, not merely the same dimensions.
 */
const fs = require('node:fs')
const path = require('node:path')

const a3 = path.resolve(__dirname, '..', '..', '_research', 'a3')
const read = (name) => JSON.parse(fs.readFileSync(path.join(a3, name), 'utf8'))

const before = read('baseline-encode-before.json')
const after = read('baseline-encode-after.json')

const rows = {}
let pass = true
for (const format of ['jpeg', 'png']) {
  const b = before.outputs[format]
  const a = after.outputs[format]
  if (!b || !a) {
    rows[format] = { present: false, before: Boolean(b), after: Boolean(a) }
    pass = false
    continue
  }
  const sameHash = b.sha256 === a.sha256
  const sameBytes = b.bytes === a.bytes
  const sameDims = b.width === a.width && b.height === a.height
  rows[format] = {
    before: { file: b.file, bytes: b.bytes, dims: `${b.width}x${b.height}`, sha256: b.sha256 },
    after: { file: a.file, bytes: a.bytes, dims: `${a.width}x${a.height}`, sha256: a.sha256 },
    sameHash,
    sameBytes,
    sameDims,
    identical: sameHash && sameBytes && sameDims
  }
  if (!rows[format].identical) pass = false
}

const result = {
  fixture: before.source,
  sameFixture: JSON.stringify(before.source) === JSON.stringify(after.source),
  rows,
  pass
}
fs.writeFileSync(path.join(a3, 'baseline-compare.json'), JSON.stringify(result, null, 2))
console.log(JSON.stringify(result, null, 2))
console.log(`\n${pass ? 'PASS' : 'FAIL'} A3 byte identity (JPEG + PNG)`)
process.exit(pass ? 0 : 1)
