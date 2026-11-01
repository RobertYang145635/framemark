'use strict'

// Stage the current repository-owned desktop assets and the engines built in
// this run into an isolated, relocatable QA runtime. No historical _research
// artifact is an input to this packager.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')

const REGISTRY_FILES = [
  ['engine/data/taxonomy.json', 'taxonomy.json'],
  ['engine/data/capabilities.json', 'capabilities.json'],
  ['engine/data/controls.json', 'controls.json'],
  ['engine/data/logos.json', 'logos.json'],
  ['ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json', 'inspector-meta.json'],
  ['src/renderer/src/core/controls/data/controls.oppo.json', 'controls.oppo.json'],
  ['src/renderer/src/core/controls/data/controls.xiaomi.json', 'controls.xiaomi.json'],
  ['src/renderer/src/core/controls/data/controls.vivo.json', 'controls.vivo.json'],
  ['bridge/desktop/ir-slot-binding.json', 'ir-slot-binding.json']
]

function parseArgs (args) {
  const result = {}
  for (let i = 0; i < args.length; i++) {
    const key = args[i]
    if (!key.startsWith('--')) throw new Error(`Unexpected argument: ${key}`)
    const name = key.slice(2)
    if (result[name] !== undefined) throw new Error(`Duplicate argument: ${key}`)
    result[name] = args[++i]
    if (!result[name] || result[name].startsWith('--')) throw new Error(`Missing value for ${key}`)
  }
  for (const name of ['root', 'artifact-root', 'out', 'extract', 'bundle', 'engine-dir', 'engine-provenance', 'smoke-dir', 'report', 'build-id']) {
    if (!result[name]) throw new Error(`Required argument missing: --${name}`)
  }
  return result
}

function sha256File (filename) {
  return crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex')
}

function sha256Files (files) {
  const hash = crypto.createHash('sha256')
  const ordered = [...files].sort((a, b) => {
    const left = a.path
    const right = b.path
    return left < right ? -1 : left > right ? 1 : 0
  })
  for (const filename of ordered) {
    hash.update(filename.path).update('\0')
    hash.update(filename.sha256).update('\0')
  }
  return hash.digest('hex')
}

function walkFiles (directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const filename = path.join(directory, entry.name)
    if (entry.isSymbolicLink()) throw new Error(`Symlink is not allowed in staged runtime: ${filename}`)
    return entry.isDirectory() ? walkFiles(filename) : [filename]
  }).sort()
}

function isWithin (parent, child) {
  const relative = path.relative(parent, child)
  return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative)
}

function assertRepoPath (root, relative) {
  const normalized = relative.replaceAll('\\', '/')
  if (path.posix.isAbsolute(normalized) || normalized.split('/').includes('..')) {
    throw new Error(`Unsafe package-relative path: ${relative}`)
  }
  const resolved = path.resolve(root, ...normalized.split('/'))
  if (!isWithin(root, resolved)) throw new Error(`Path leaves repository root: ${relative}`)
  return resolved
}

function extractBundle (archive, destination) {
  const listing = spawnSync('tar.exe', ['-tf', archive], { encoding: 'utf8', windowsHide: true })
  if (listing.status !== 0) throw new Error(`Could not list the repository asset archive: ${listing.stderr || listing.stdout}`)
  const entries = listing.stdout.split(/\r?\n/).filter(Boolean)
  const allowedRoots = new Set(['assets', 'designs', 'e4-extra-fonts', 'exif.jpg', 'extras', 'fallback', 'fonts', 'hand', 'icc', 'logos', 'manifest.json', 'photo.png', 'styles', 'META-INF'])
  for (const entry of entries) {
    const normalized = entry.replaceAll('\\', '/')
    if (path.posix.isAbsolute(normalized) || normalized.split('/').includes('..')) throw new Error(`Unsafe ZIP entry path: ${entry}`)
    const first = normalized.split('/')[0]
    if (!allowedRoots.has(first)) throw new Error(`Unexpected root entry in asset archive: ${entry}`)
  }
  fs.mkdirSync(destination, { recursive: true })
  const extracted = spawnSync('tar.exe', ['-xf', archive, '-C', destination], { encoding: 'utf8', windowsHide: true })
  if (extracted.status !== 0) throw new Error(`Could not extract the repository asset archive: ${extracted.stderr || extracted.stdout}`)
  for (const entry of entries) {
    const target = path.resolve(destination, ...entry.replaceAll('\\', '/').split('/'))
    const absoluteDestination = path.resolve(destination)
    const relative = path.relative(absoluteDestination, target)
    if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) throw new Error(`Extracted ZIP entry escaped staging: ${entry}`)
  }
  return entries
}

function copyTree (source, destination) {
  if (!fs.existsSync(source)) throw new Error(`Required asset source is missing: ${source}`)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.cpSync(source, destination, { recursive: true, errorOnExist: true, force: false })
}

function sanitizeDesigns (dataRoot) {
  const dir = path.join(dataRoot, 'designs')
  let fixed = 0
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith('.json')) continue
    const filename = path.join(dir, name)
    const document = JSON.parse(fs.readFileSync(filename, 'utf8'))
    let changed = false
    if (document.design?.export && typeof document.design.export.outputDir === 'string') {
      document.design.export.outputDir = ''
      changed = true
    }
    if (document.fixture && typeof document.fixture.path === 'string') {
      document.fixture.path = 'photo.png'
      changed = true
    }
    if (changed) {
      fs.writeFileSync(filename, JSON.stringify(document, null, 2) + '\n', 'utf8')
      fixed++
    }
  }
  return fixed
}

function sanitizeHandCards (dataRoot) {
  const filename = path.join(dataRoot, 'hand/cards.json')
  const document = JSON.parse(fs.readFileSync(filename, 'utf8'))
  let fixed = 0
  delete document.inputs
  for (const card of document.cards || []) {
    let changed = false
    for (const key of ['ops', 'opsSha256', 'images']) {
      if (key in card) { delete card[key]; changed = true }
    }
    if (typeof card.golden === 'string') { card.golden = `goldens/${card.card}.png`; changed = true }
    for (const extra of card.extras || []) {
      if (typeof extra.source === 'string' && extra.source !== extra.png) { extra.source = extra.png; changed = true }
    }
    if (changed) fixed++
  }
  fs.writeFileSync(filename, JSON.stringify(document, null, 1) + '\n', 'utf8')
  return fixed
}

function sanitizeTextSlots (dataRoot) {
  const filename = path.join(dataRoot, 'hand/text-slots.json')
  const document = JSON.parse(fs.readFileSync(filename, 'utf8'))
  let fixed = 0
  if (document.provenance && typeof document.provenance.source === 'string') {
    document.provenance.source = 'designs/<card>.json'
    fixed++
  }
  for (const card of Object.values(document.cards || {})) {
    if (typeof card.source === 'string' && (card.source.includes('_research/') || /^[A-Za-z]:[\\/]/.test(card.source))) {
      card.source = `designs/${path.basename(card.source).replaceAll('\\', '/')}`
      fixed++
    }
  }
  fs.writeFileSync(filename, JSON.stringify(document, null, 1) + '\n', 'utf8')
  return fixed
}

function stageRegistry (root, out) {
  const registryRoot = path.join(out, 'data/registry')
  fs.mkdirSync(registryRoot, { recursive: true })
  const sourceHashes = {}
  const packagedHashes = {}
  for (const [relative, name] of REGISTRY_FILES) {
    const source = assertRepoPath(root, relative)
    if (!fs.existsSync(source)) throw new Error(`Registry source is missing: ${relative}`)
    const destination = path.join(registryRoot, name)
    fs.copyFileSync(source, destination)
    JSON.parse(fs.readFileSync(destination, 'utf8'))
    sourceHashes[relative] = sha256File(source)
    packagedHashes[name] = sha256File(destination)
  }
  const needsNeutralize = (value) => /border&watermark|_research\/|^[A-Za-z]:[\\/]|^\\\\/.test(value)
  const neutralize = (value) => `provenance/${value.replace(/[\\/]+$/, '').split(/[\\/]/).pop()}`
  let sanitizedValues = 0
  for (const [, name] of REGISTRY_FILES) {
    const filename = path.join(registryRoot, name)
    const document = JSON.parse(fs.readFileSync(filename, 'utf8'))
    const walk = (value) => {
      if (Array.isArray(value)) {
        value.forEach((item, index) => {
          if (typeof item === 'string' && needsNeutralize(item)) { value[index] = neutralize(item); sanitizedValues++ } else walk(item)
        })
      } else if (value && typeof value === 'object') {
        for (const key of Object.keys(value)) {
          const item = value[key]
          if (typeof item === 'string' && needsNeutralize(item)) { value[key] = neutralize(item); sanitizedValues++ } else walk(item)
        }
      }
    }
    walk(document)
    fs.writeFileSync(filename, JSON.stringify(document, null, 2) + '\n', 'utf8')
    packagedHashes[name] = sha256File(filename)
  }
  return { sourceHashes, packagedHashes, sanitizedValues }
}

function buildDecoded (dataRoot, manifest) {
  const byTarget = new Map()
  let copied = 0
  for (const card of manifest.cards) {
    if (card.backend !== 'ir' || !Array.isArray(card.assets)) continue
    for (const asset of card.assets) {
      const sourceRelative = asset.source.replace(/^data\//, '')
      const targetRelative = asset.engineFile.replace(/^data\//, '')
      const source = assertRepoPath(dataRoot, sourceRelative)
      const destination = assertRepoPath(dataRoot, targetRelative)
      if (!fs.existsSync(source)) throw new Error(`IR asset missing for ${card.cardId}: ${asset.source}`)
      const digest = sha256File(source)
      if (byTarget.has(targetRelative)) {
        if (byTarget.get(targetRelative) !== digest) throw new Error(`Decoded target collision with different bytes: ${targetRelative}`)
        continue
      }
      byTarget.set(targetRelative, digest)
      fs.mkdirSync(path.dirname(destination), { recursive: true })
      fs.copyFileSync(source, destination)
      copied++
    }
  }
  const logos = path.join(dataRoot, 'logos')
  copyTree(logos, path.join(dataRoot, 'decoded/logos'))
  return copied
}

function validateManifest (dataRoot, manifest, manifestText) {
  if (!manifest || !Array.isArray(manifest.cards) || manifest.cards.length === 0) throw new Error('Authoritative desktop manifest has no cards array')
  const ids = new Set()
  const counts = { ir: 0, hand: 0 }
  const referenced = new Set([manifest.photo?.path, manifest.photo?.exifSource].filter(Boolean))
  for (const card of manifest.cards) {
    if (!card.cardId || ids.has(card.cardId)) throw new Error(`Missing or duplicate card ID: ${card.cardId || '<empty>'}`)
    ids.add(card.cardId)
    if (!Object.hasOwn(counts, card.backend)) throw new Error(`Unknown rendering backend for ${card.cardId}: ${card.backend}`)
    counts[card.backend]++
    for (const key of card.backend === 'ir' ? ['style'] : ['cardsFile', 'design']) {
      if (!card[key]) throw new Error(`${card.cardId} has no ${key}`)
      referenced.add(card[key])
    }
    for (const asset of card.assets || []) {
      if (!asset.source || !asset.engineFile) throw new Error(`${card.cardId} has an incomplete asset reference`)
      referenced.add(asset.source)
    }
  }
  for (const relative of referenced) {
    if (typeof relative !== 'string' || !relative.startsWith('data/')) throw new Error(`Manifest path is not data-relative: ${relative}`)
    const local = assertRepoPath(dataRoot, relative.slice('data/'.length))
    if (!fs.existsSync(local)) throw new Error(`Manifest input is not packaged: ${relative}`)
  }
  if (!fs.existsSync(path.join(dataRoot, 'fonts'))) throw new Error('Packaged fonts are missing')
  if (!fs.existsSync(path.join(dataRoot, 'fallback/cjk.ttf'))) throw new Error('Packaged CJK fallback font is missing')
  if (!fs.existsSync(path.join(dataRoot, 'icc/srgb-456.icc'))) throw new Error('Packaged ICC profile is missing')
  return { total: manifest.cards.length, ...counts, manifestSha256: crypto.createHash('sha256').update(manifestText).digest('hex') }
}

function hasReportedFailure (value) {
  if (value == null || value === false) return false
  if (typeof value === 'string') return value.trim().length > 0
  if (typeof value === 'number') return value !== 0
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value).length > 0
  return value === true
}

function collectExplicitFailures (value, location = '$', failures = []) {
  if (!value || typeof value !== 'object') return failures
  for (const [key, child] of Object.entries(value)) {
    if (['error', 'errors', 'missingassets'].includes(key.toLowerCase()) && hasReportedFailure(child)) {
      failures.push(`${location}.${key}=${JSON.stringify(child).slice(0, 500)}`)
    } else if (child && typeof child === 'object') {
      collectExplicitFailures(child, `${location}.${key}`, failures)
    }
  }
  return failures
}

function readJsonReport (filename, label) {
  if (!fs.existsSync(filename)) throw new Error(`${label} engine report was not written: ${filename}`)
  let report
  try { report = JSON.parse(fs.readFileSync(filename, 'utf8')) } catch (error) {
    throw new Error(`${label} engine report is not valid JSON: ${error.message}`)
  }
  if (!report || typeof report !== 'object' || Array.isArray(report)) throw new Error(`${label} engine report must be a JSON object`)
  const explicitFailures = collectExplicitFailures(report)
  if (explicitFailures.length) throw new Error(`${label} engine report contains failures: ${explicitFailures.join('; ')}`)
  return report
}

function positiveInteger (value) {
  return Number.isInteger(value) && value > 0
}

function validatePng (filename, label, reportWidth, reportHeight) {
  if (!fs.existsSync(filename)) throw new Error(`${label} render did not write a PNG: ${filename}`)
  const bytes = fs.readFileSync(filename)
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
  if (bytes.length < 24 || !bytes.subarray(0, 8).equals(signature) || bytes.toString('ascii', 12, 16) !== 'IHDR') {
    throw new Error(`${label} output is not a valid PNG: ${filename}`)
  }
  const width = bytes.readUInt32BE(16)
  const height = bytes.readUInt32BE(20)
  if (!positiveInteger(width) || !positiveInteger(height)) throw new Error(`${label} PNG has invalid dimensions ${width}x${height}`)
  if (reportWidth != null && reportHeight != null && (width !== reportWidth || height !== reportHeight)) {
    throw new Error(`${label} PNG/report dimensions disagree: PNG=${width}x${height} report=${reportWidth}x${reportHeight}`)
  }
  return { bytes: bytes.length, width, height, sha256: sha256File(filename) }
}

function validateIrReport (report) {
  const canvas = report.canvas
  if (!canvas || typeof canvas !== 'object' || !positiveInteger(canvas.w) || !positiveInteger(canvas.h)) {
    throw new Error('IR engine report canvas.w/canvas.h must be positive integers')
  }
  if (!Array.isArray(report.photo) || report.photo.length !== 4 || !report.photo.every((v) => typeof v === 'number' && Number.isFinite(v)) || report.photo[2] <= 0 || report.photo[3] <= 0) {
    throw new Error('IR engine report photo rectangle is missing or invalid')
  }
  if (!Array.isArray(report.items)) throw new Error('IR engine report items must be an array')
  const malformed = report.items.findIndex((item) => !item || typeof item !== 'object' || Array.isArray(item) || typeof item.kind !== 'string' || !item.kind.trim())
  if (malformed >= 0) throw new Error(`IR engine report items[${malformed}] is malformed`)
  // Match DesktopEngineBridge: image_missing and brand_slot_missing indicate
  // unavailable required resources. brand_slot_empty is an intentional rule
  // outcome for a brand with no wordmark and is not a missing-file failure.
  const resourceFailures = report.items.filter((item) => item.kind === 'image_missing' || item.kind === 'brand_slot_missing')
  if (resourceFailures.length) throw new Error(`IR engine report has missing required resources: ${JSON.stringify(resourceFailures.slice(0, 8))}`)
  return { width: canvas.w, height: canvas.h, itemCount: report.items.length, requiredResourceErrors: [] }
}

function validateHandReport (report) {
  if (!Array.isArray(report.canvas) || report.canvas.length !== 2 || !report.canvas.every(positiveInteger)) {
    throw new Error('Hand engine report canvas must contain two positive integers')
  }
  if (!Array.isArray(report.photo_rect) || report.photo_rect.length !== 4 || !report.photo_rect.every((v) => typeof v === 'number' && Number.isFinite(v)) || report.photo_rect[2] <= 0 || report.photo_rect[3] <= 0) {
    throw new Error('Hand engine report photo_rect is missing or invalid')
  }
  const drawReport = report.report
  if (!drawReport || typeof drawReport !== 'object' || Array.isArray(drawReport)) throw new Error('Hand engine draw report is missing')
  for (const key of ['images', 'texts', 'shapes', 'gaps']) {
    if (!Array.isArray(drawReport[key])) throw new Error(`Hand engine draw report ${key} must be an array`)
  }
  // E4's gaps intentionally include informational parity notes. Only resource
  // failures are fatal; normal calibration/feature notes remain visible in the
  // report without blocking runtime packaging.
  const resourceGaps = drawReport.gaps.filter((gap) => typeof gap === 'string' && /(?:missing\s+(?:image|asset|font)|(?:image|asset|font)\s+(?:missing|unavailable)|font face unavailable)/i.test(gap))
  if (resourceGaps.length) throw new Error(`Hand engine report has missing required resources: ${resourceGaps.join('; ')}`)
  return { width: report.canvas[0], height: report.canvas[1], imageCount: drawReport.images.length, resourceGaps: [] }
}

function assertNoAbsolutePaths (root) {
  const hits = []
  for (const filename of walkFiles(root)) {
    if (!/\.(json|txt|md|cmd|ps1|cjs|mjs)$/i.test(filename)) continue
    const relative = path.relative(root, filename).replaceAll(path.sep, '/')
    const text = fs.readFileSync(filename, 'utf8')
    let document = null
    if (/\.json$/i.test(filename)) {
      try { document = JSON.parse(text) } catch { hits.push(`${relative}: invalid JSON`); continue }
    }
    const check = (value, where) => {
      if (typeof value === 'string') {
        if (value.startsWith('data:')) return
        if (/^[A-Za-z]:[\\/]|^\\\\|^\//.test(value) || value.includes('_research/') || value.includes('border&watermark')) hits.push(`${relative}${where}: ${value.slice(0, 120)}`)
      } else if (Array.isArray(value)) value.forEach((item, index) => check(item, `${where}[${index}]`))
      else if (value && typeof value === 'object') Object.keys(value).forEach((key) => check(value[key], `${where}.${key}`))
    }
    if (document !== null) check(document, '')
    else if (/[A-Za-z]:[\\/]|_research\/|border&watermark/.test(text)) hits.push(`${relative}: text contains a developer path`)
  }
  return hits
}

function smoke (out, smokeDir, manifest) {
  const dataRoot = path.join(out, 'data')
  fs.mkdirSync(smokeDir, { recursive: true })
  const ir = manifest.cards.find((card) => card.backend === 'ir' && card.cardId === 'stock-oppo-hassel_style_1') || manifest.cards.find((card) => card.backend === 'ir')
  const hand = manifest.cards.find((card) => card.backend === 'hand' && card.cardId === 'blur-classic') || manifest.cards.find((card) => card.backend === 'hand')
  if (!ir || !hand) throw new Error('The current manifest must contain at least one IR and one hand card for runtime smoke')
  const irOutput = path.join(smokeDir, 'smoke-ir.png')
  const irReport = path.join(smokeDir, 'smoke-ir.json')
  const irResult = spawnSync(path.join(out, 'bin/e2b.exe'), [
    '--style', ir.style.replace(/^data\//, ''), '--photo', 'photo.png', '--exif-source', 'exif.jpg',
    '--fonts', 'fonts', '--decoded', 'decoded', '--assets', 'assets', '--t1b', 'assets', '--out', irOutput, '--report', irReport
  ], { cwd: dataRoot, encoding: 'utf8', windowsHide: true })
  if (irResult.status !== 0) throw new Error(`IR CWD smoke failed (${irResult.status}): ${irResult.stderr || irResult.stdout}`)
  const parsedIrReport = readJsonReport(irReport, 'IR')
  const irSummary = validateIrReport(parsedIrReport)
  const irPng = validatePng(irOutput, 'IR', irSummary.width, irSummary.height)
  const handOutput = path.join(smokeDir, 'smoke-hand.png')
  const handReport = path.join(smokeDir, 'smoke-hand.json')
  const handResult = spawnSync(path.join(out, 'bin/e4hand.exe'), [
    'render', '--card', hand.engineId, '--cards', 'hand/cards.json', '--hand', 'hand', '--fonts', 'fonts',
    '--extra-fonts', 'e4-extra-fonts', '--out', handOutput, '--report', handReport
  ], { cwd: dataRoot, encoding: 'utf8', windowsHide: true })
  if (handResult.status !== 0) throw new Error(`Hand CWD smoke failed (${handResult.status}): ${handResult.stderr || handResult.stdout}`)
  const parsedHandReport = readJsonReport(handReport, 'Hand')
  const handSummary = validateHandReport(parsedHandReport)
  const handPng = validatePng(handOutput, 'Hand', handSummary.width, handSummary.height)
  return {
    ir: { cardId: ir.cardId, ...irSummary, ...irPng },
    hand: { cardId: hand.cardId, ...handSummary, ...handPng }
  }
}

function main () {
  const args = parseArgs(process.argv.slice(2))
  const root = path.resolve(args.root)
  const artifactRoot = path.resolve(args['artifact-root'])
  const out = path.resolve(args.out)
  const extract = path.resolve(args.extract)
  const bundle = path.resolve(args.bundle)
  const engineDir = path.resolve(args['engine-dir'])
  const engineProvenancePath = path.resolve(args['engine-provenance'])
  const smokeDir = path.resolve(args['smoke-dir'])
  const reportPath = path.resolve(args.report)
  if (!isWithin(artifactRoot, out) || !isWithin(artifactRoot, extract) || !isWithin(artifactRoot, smokeDir) || !isWithin(artifactRoot, reportPath) || !isWithin(artifactRoot, engineProvenancePath)) {
    throw new Error('Runtime, extraction, smoke and report destinations must stay under the configured artifact root')
  }
  if (!fs.existsSync(bundle)) throw new Error(`Repository asset bundle is missing: ${path.relative(root, bundle)}`)
  if (fs.existsSync(out)) throw new Error(`Runtime destination already exists; refusing to replace: ${path.relative(root, out)}`)
  if (fs.existsSync(extract)) throw new Error(`Extraction destination already exists; refusing to replace: ${path.relative(root, extract)}`)

  const executableNames = ['e2b.exe', 'e4hand.exe', 'e5-worker.exe']
  const executableHashes = {}
  const engineProvenance = JSON.parse(fs.readFileSync(engineProvenancePath, 'utf8'))
  if (engineProvenance.status !== 'PASS' || !Array.isArray(engineProvenance.engines)) throw new Error('The desktop engine provenance is incomplete or not passing')
  for (const name of executableNames) {
    const source = path.join(engineDir, name)
    if (!fs.existsSync(source)) throw new Error(`Source-built desktop engine is missing: ${name}`)
    executableHashes[name] = sha256File(source)
    const sourceRow = engineProvenance.engines.find((row) => row.binary === name)
    if (!sourceRow || sourceRow.status !== 'PASS' || sourceRow.executableSha256 !== executableHashes[name] || !sourceRow.crateSourceLockSha256) {
      throw new Error(`Source/build provenance does not match staged executable: ${name}`)
    }
  }

  const archiveEntries = extractBundle(bundle, extract)
  const dataRoot = path.join(out, 'data')
  fs.mkdirSync(path.join(out, 'bin'), { recursive: true })
  fs.mkdirSync(dataRoot, { recursive: true })
  const packageRoots = ['assets', 'designs', 'e4-extra-fonts', 'extras', 'fallback', 'fonts', 'hand', 'icc', 'logos', 'styles']
  for (const name of packageRoots) copyTree(path.join(extract, name), path.join(dataRoot, name))
  for (const name of ['manifest.json', 'photo.png', 'exif.jpg']) copyTree(path.join(extract, name), path.join(dataRoot, name))
  for (const name of executableNames) fs.copyFileSync(path.join(engineDir, name), path.join(out, 'bin', name))
  fs.copyFileSync(path.join(dataRoot, 'icc/srgb-456.icc'), path.join(out, 'bin/srgb-456.icc'))

  const manifestPath = assertRepoPath(root, 'bridge/desktop/cards-175.json')
  const manifestText = fs.readFileSync(manifestPath, 'utf8')
  const manifest = JSON.parse(manifestText)
  const counts = validateManifest(dataRoot, manifest, manifestText)
  const fixedDesigns = sanitizeDesigns(dataRoot)
  const fixedHandCards = sanitizeHandCards(dataRoot)
  const fixedTextSlots = sanitizeTextSlots(dataRoot)
  let droppedProvenance = 0
  for (const relative of ['e4-extra-fonts/manifest.json', 'e4-extra-fonts/SOURCES.md']) {
    const filename = path.join(dataRoot, relative)
    if (fs.existsSync(filename)) { fs.rmSync(filename); droppedProvenance++ }
  }
  const decodedCopied = buildDecoded(dataRoot, manifest)
  for (const card of manifest.cards) {
    for (const asset of card.assets || []) {
      const decoded = assertRepoPath(dataRoot, asset.engineFile.replace(/^data\//, ''))
      if (!fs.existsSync(decoded)) throw new Error(`Decoded IR asset was not staged for ${card.cardId}: ${asset.engineFile}`)
    }
  }
  const registry = stageRegistry(root, out)
  fs.mkdirSync(path.join(out, 'bridge/desktop'), { recursive: true })
  fs.copyFileSync(manifestPath, path.join(out, 'bridge/desktop/cards-175.json'))
  fs.writeFileSync(path.join(out, 'FRAMEMARK-PACKAGE.json'), JSON.stringify({
    schema: 'framemark-package/v1',
    label: `FrameMark QA runtime ${args['build-id']}`,
    buildId: args['build-id'],
    manifest: 'bridge/desktop/cards-175.json',
    cardCount: counts.total
  }, null, 2) + '\n', 'utf8')

  const pathHits = assertNoAbsolutePaths(out)
  if (pathHits.length) throw new Error(`Absolute/developer paths remain in runtime:\n${pathHits.slice(0, 20).join('\n')}`)
  const smokeResult = smoke(out, smokeDir, manifest)
  const files = walkFiles(out).map((filename) => ({
    path: path.relative(out, filename).replaceAll(path.sep, '/'),
    bytes: fs.statSync(filename).size,
    sha256: sha256File(filename)
  }))
  const report = {
    schema: 'framemark-desktop-runtime-provenance/v1',
    status: 'PASS',
    buildId: args['build-id'],
    packager: { path: path.relative(root, __filename).replaceAll(path.sep, '/'), sha256: sha256File(__filename) },
    runtime: path.relative(root, out).replaceAll(path.sep, '/'),
    assetBundle: { path: path.relative(root, bundle).replaceAll(path.sep, '/'), sha256: sha256File(bundle), archiveEntries: archiveEntries.length },
    authoritativeManifest: { path: path.relative(root, manifestPath).replaceAll(path.sep, '/'), sha256: counts.manifestSha256, filename: 'cards-175.json', cardCounts: counts },
    engineBuildProvenance: {
      path: path.relative(root, engineProvenancePath).replaceAll(path.sep, '/'),
      sha256: sha256File(engineProvenancePath)
    },
    engines: Object.fromEntries(executableNames.map((name) => {
      const row = engineProvenance.engines.find((engine) => engine.binary === name)
      return [name, { sha256: executableHashes[name], crateManifest: row.crateManifest, crateSourceLockSha256: row.crateSourceLockSha256, sourceInputsSha256: row.sourceInputsSha256, skiaArchiveSha256: row.skiaArchiveSha256 }]
    })),
    registry,
    sanitization: { fixedDesigns, fixedHandCards, fixedTextSlots, droppedProvenance, decodedAssets: decodedCopied, absolutePathHits: pathHits.length },
    smoke: smokeResult,
    fileCount: files.length,
    packageBytes: files.reduce((sum, file) => sum + file.bytes, 0),
    packageFilesSha256: sha256Files(files),
    files
  }
  fs.mkdirSync(path.dirname(reportPath), { recursive: true })
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n', 'utf8')
  process.stdout.write(JSON.stringify({ status: report.status, runtime: report.runtime, cardCounts: counts, fileCount: report.fileCount, packageBytes: report.packageBytes, packageFilesSha256: report.packageFilesSha256, smoke: smokeResult }) + '\n')
}

try {
  main()
} catch (error) {
  process.stderr.write(`Desktop runtime staging failed: ${error.stack || error.message}\n`)
  process.exitCode = 1
}
