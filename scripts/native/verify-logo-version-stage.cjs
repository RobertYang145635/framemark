'use strict'

// Audit the app-packaged Logo WebPs and the exact PNG stems the desktop/IR
// renderer will consume. Output is intentionally retained as evidence.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { stageExactLogoCuts, logoEngineKey } = require('./runtime.cjs')

async function main () {
  const [rootArg, evidenceArg] = process.argv.slice(2)
  if (!rootArg || !evidenceArg || process.argv.length !== 4) {
    throw new Error('Usage: node scripts/native/verify-logo-version-stage.cjs <repo-root> <new-evidence-dir>')
  }
  const root = path.resolve(rootArg)
  const evidence = path.resolve(evidenceArg)
  if (!fs.statSync(root).isDirectory()) throw new Error(`Repository root is not a directory: ${root}`)
  if (fs.existsSync(evidence)) throw new Error(`Evidence destination already exists: ${evidence}`)
  fs.mkdirSync(evidence, { recursive: true })
  const dataRoot = path.join(evidence, 'runtime-data')
  const result = await stageExactLogoCuts(root, dataRoot)
  const keys = result.entries.map((entry) => entry.assetKey)
  const engineKeys = result.entries.map((entry) => entry.engineKey)
  if (keys.length !== result.uniqueSelectedAssetKeys || new Set(keys).size !== keys.length) {
    throw new Error('Logo map contains duplicate or missing catalog asset keys')
  }
  if (new Set(engineKeys).size !== engineKeys.length) throw new Error('Logo map contains an engine-key collision')
  for (const entry of result.entries) {
    if (logoEngineKey(entry.assetKey) !== entry.engineKey) throw new Error(`Non-deterministic engine key: ${entry.assetKey}`)
    const png = path.join(dataRoot, 'logos', `${entry.engineKey}.png`)
    if (!fs.existsSync(png) || !fs.statSync(png).isFile()) throw new Error(`Staged PNG is missing: ${entry.assetKey}`)
    const actual = crypto.createHash('sha256').update(fs.readFileSync(png)).digest('hex')
    if (actual !== entry.pngSha256) throw new Error(`Staged PNG hash mismatch: ${entry.assetKey}`)
  }
  fs.writeFileSync(path.join(evidence, 'logo-versions.json'), JSON.stringify(result, null, 2) + '\n', 'utf8')
  const summary = {
    status: 'PASS',
    sourceCatalog: result.sourceCatalog,
    sourceCatalogSha256: result.sourceCatalogSha256,
    selectedVersions: result.selectedVersions,
    uniqueSelectedAssetKeys: result.uniqueSelectedAssetKeys,
    exactVersionKeys: keys.filter((key) => key.startsWith('ver:')).length,
    sourceResourcesVerified: result.entries.length,
    stagedPngsVerified: result.entries.length,
    alphaResources: result.entries.filter((entry) => entry.sourceHasAlpha).length,
    evidence: path.relative(root, evidence).replaceAll(path.sep, '/')
  }
  fs.writeFileSync(path.join(evidence, 'summary.json'), JSON.stringify(summary, null, 2) + '\n', 'utf8')
  process.stdout.write(JSON.stringify(summary) + '\n')
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message}\n`)
  process.exitCode = 1
})
