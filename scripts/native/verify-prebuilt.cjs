'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

const root = path.resolve(__dirname, '../..')
const manifestPath = path.join(__dirname, 'android-prebuilt-manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))

function sha256 (filename) {
  return crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex')
}

const rows = manifest.libraries.map((item) => {
  const filename = path.join(root, item.path)
  if (!fs.existsSync(filename)) {
    return { path: item.path, status: 'FAIL', reason: 'missing' }
  }
  const actual = sha256(filename)
  return {
    path: item.path,
    expectedSha256: item.sha256,
    actualSha256: actual,
    status: actual === item.sha256 ? 'PASS' : 'FAIL'
  }
})

const result = {
  schema: 'framemark-android-prebuilt-verification/v1',
  manifest: path.relative(root, manifestPath).replaceAll(path.sep, '/'),
  provenanceStatus: manifest.provenanceStatus,
  provenanceNote: manifest.provenanceNote,
  rows,
  verdict: rows.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL'
}

process.stdout.write(JSON.stringify(result, null, 2) + '\n')
if (result.verdict !== 'PASS') process.exitCode = 1
