// Rebuild the 69 rendered variant previews through window.__fmVariants (ID based).
//
// Thumbnails are staged in a temp directory and swapped in atomically, so a
// partial failure never destroys the existing previews (previews for failed ids
// and unrelated files are carried over from the old directory).
//
// Usage: node scripts/build-variant-previews.cjs
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const project = path.resolve(__dirname, '..', '..')
const previewsDir = path.join(project, 'legacy-web/src/renderer/src/assets/previews')
const outDir = path.join(previewsDir, 'rendered')
const expectedVariants = 69
const runId = `${process.pid}-${Date.now()}`
const tmpDir = path.join(previewsDir, `.rendered-tmp-${runId}`)
const backupDir = path.join(previewsDir, `.rendered-backup-${runId}`)

function recoverInterruptedSwap() {
  if (fs.existsSync(outDir)) return
  const backups = fs
    .readdirSync(previewsDir)
    .filter((f) => f.startsWith('.rendered-backup-'))
    .sort()
  if (!backups.length) return
  const latest = path.join(previewsDir, backups[backups.length - 1])
  fs.renameSync(latest, outDir)
  console.warn(`restored interrupted preview swap from ${latest}`)
}

function isWebp(buf) {
  return buf.length > 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP'
}

function cleanStaleTempDirs() {
  for (const entry of fs.readdirSync(previewsDir)) {
    if (entry.startsWith('.rendered-tmp-') || entry.startsWith('.rendered-backup-')) {
      const target = path.join(previewsDir, entry)
      if (target !== tmpDir && target !== backupDir) fs.rmSync(target, { recursive: true, force: true })
    }
  }
}

async function main() {
  recoverInterruptedSwap()
  cleanStaleTempDirs()
  fs.rmSync(tmpDir, { recursive: true, force: true })
  fs.mkdirSync(tmpDir, { recursive: true })

  const failures = []
  let ok = 0
  let list = []
  let app = null
  try {
    app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
    const page = await app.firstWindow()
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    await page.waitForSelector('.app-shell.ready', { timeout: 45000 })

    list = await page.evaluate(() => window.__fmVariants.list())
    if (!Array.isArray(list)) throw new Error('window.__fmVariants.list() did not return an array')
    const ids = new Set(list.map((v) => v.id))
    if (ids.size !== list.length) throw new Error('window.__fmVariants.list() returned duplicate ids')
    if (list.length !== expectedVariants) {
      throw new Error(`expected ${expectedVariants} renderable variants, got ${list.length}`)
    }

    for (const item of list) {
      try {
        const res = await page.evaluate((id) => window.__fmVariants.render(id), item.id)
        if (!res || typeof res.data !== 'string') throw new Error('empty render result')
        const buf = Buffer.from(res.data.replace(/^data:image\/\w+;base64,/, ''), 'base64')
        if (buf.length < 800) throw new Error(`preview too small: ${buf.length} bytes`)
        if (!isWebp(buf)) throw new Error('render did not return a WebP thumbnail')
        fs.writeFileSync(path.join(tmpDir, `${item.id}.webp`), buf)
        ok++
      } catch (e) {
        failures.push({ id: item.id, message: e.message })
        console.error('FAIL', item.id, e.message)
      }
    }
    if (errors.length) console.warn('page errors:', errors.slice(0, 5))
  } catch (e) {
    failures.push({ id: '__variant_api__', message: e.message })
    console.error('FAIL __variant_api__', e.message)
  } finally {
    if (app) await app.close().catch(() => undefined)
  }

  // Carry over previews we did not regenerate (failed ids, unrelated files).
  let preserved = 0
  if (fs.existsSync(outDir)) {
    for (const file of fs.readdirSync(outDir)) {
      const src = path.join(outDir, file)
      const dest = path.join(tmpDir, file)
      if (!fs.statSync(src).isFile() || fs.existsSync(dest)) continue
      fs.copyFileSync(src, dest)
      preserved++
    }
  }

  // Atomic-ish swap: old dir -> backup, temp -> live, drop backup. On failure
  // the old directory is restored before the error is re-thrown.
  fs.rmSync(backupDir, { recursive: true, force: true })
  const hadOld = fs.existsSync(outDir)
  let swapped = false
  try {
    if (hadOld) fs.renameSync(outDir, backupDir)
    fs.renameSync(tmpDir, outDir)
    swapped = true
  } catch (e) {
    if (!fs.existsSync(outDir) && hadOld && fs.existsSync(backupDir)) fs.renameSync(backupDir, outDir)
    failures.push({ id: '__swap__', message: e.message })
    console.error('FAIL __swap__', e.message)
  }
  if (swapped) fs.rmSync(backupDir, { recursive: true, force: true })
  fs.rmSync(tmpDir, { recursive: true, force: true })

  console.log(`variants ${list.length || expectedVariants} / ok ${ok} / failed ${failures.length}`)
  if (preserved) console.log(`preserved existing previews: ${preserved}`)
  for (const f of failures) console.error(`FAILED ${f.id}: ${f.message}`)
  if (failures.length) process.exitCode = 1
}

main().catch((e) => {
  fs.rmSync(tmpDir, { recursive: true, force: true })
  console.error(e)
  process.exit(1)
})
