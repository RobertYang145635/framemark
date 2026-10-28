/**
 * A2 part 1 — Chromium half of the quality matrix.
 *
 * ONE Electron instance (the real app build), throw-away --user-data-dir,
 * launched exactly like scripts/e2e.mjs:40-58. Inside that renderer every
 * fixture is drawn 1:1 into an alpha:false canvas and encoded with
 * `canvas.toBlob('image/jpeg', q/100)` for q = 100/95/90/85 — the same call the
 * Android export makes (core/exporter.ts exportAndroid).
 *
 * JPEG bytes come back as base64 and are written to _research/a2/out/chromium/.
 * Subsampling is NOT assumed: `_a2-metrics.cjs` parses the SOF marker of every
 * file. Each cell is encoded twice to prove determinism.
 *
 * The fixtures themselves are produced by _a2-fixtures.cjs (sharp only).
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')

const requireHere = createRequire(__filename)
const { _electron: electron } = requireHere('playwright-core')

const A2 = path.resolve(__dirname, '..', '..', '_research', 'a2')
const FIX = path.join(A2, 'fixtures')
const OUT = path.join(A2, 'out', 'chromium')
const PROFILE = path.join(A2, 'tmp', 'chromium-profile')
const QUALS = [100, 95, 90, 85]
const FIXTURES = ['bars', 'colortext', 'skin', 'detail']

function pageWork() {
  /* eslint-disable no-undef */
  function toB64(buf) {
    const bytes = new Uint8Array(buf)
    let s = ''
    const CH = 0x8000
    for (let i = 0; i < bytes.length; i += CH) {
      s += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + CH)))
    }
    return btoa(s)
  }

  window.__a2encode = async function (items) {
    const out = []
    for (const it of items) {
      const bin = Uint8Array.from(atob(it.b64), (ch) => ch.charCodeAt(0))
      const srcBlob = new Blob([bin], { type: 'image/png' })
      const bmp = await createImageBitmap(srcBlob, {
        colorSpaceConversion: 'none',
        premultiplyAlpha: 'none',
        imageOrientation: 'from-image'
      })
      for (const q of it.quals) {
        const c = document.createElement('canvas')
        c.width = bmp.width
        c.height = bmp.height
        const ctx = c.getContext('2d', { alpha: false })
        ctx.drawImage(bmp, 0, 0)
        const blob = await new Promise((res, rej) =>
          c.toBlob((b) => (b ? res(b) : rej(new Error('toBlob returned null'))), 'image/jpeg', q / 100)
        )
        const buf = new Uint8Array(await blob.arrayBuffer())
        // Determinism probe: encode the same pixels twice, compare bytes.
        const blob2 = await new Promise((res, rej) =>
          c.toBlob((b) => (b ? res(b) : rej(new Error('toBlob returned null'))), 'image/jpeg', q / 100)
        )
        const buf2 = new Uint8Array(await blob2.arrayBuffer())
        let same = buf.length === buf2.length
        if (same) for (let i = 0; i < buf.length; i++) if (buf[i] !== buf2[i]) { same = false; break }
        out.push({ name: it.name, q, bytes: buf.length, deterministic: same, b64: toB64(buf.buffer) })
      }
      bmp.close()
    }
    return out
  }
  /* eslint-enable no-undef */
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  fs.mkdirSync(PROFILE, { recursive: true })
  for (const f of FIXTURES) {
    if (!fs.existsSync(path.join(FIX, `${f}.png`))) throw new Error(`missing fixture ${f}.png — run _a2-fixtures.cjs first`)
  }
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE

  const app = await electron.launch({
    executablePath: requireHere('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`],
    cwd: path.resolve(__dirname, '..'),
    env,
    timeout: 60000
  })
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    const versions = await app.evaluate(({ app: a }) => ({
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      v8: process.versions.v8,
      appVersion: a.getVersion()
    }))
    const ua = await page.evaluate(() => navigator.userAgent)
    console.log('chromium:', JSON.stringify(versions))

    await page.evaluate(pageWork)

    const items = FIXTURES.map((name) => ({
      name,
      quals: QUALS,
      b64: fs.readFileSync(path.join(FIX, `${name}.png`)).toString('base64')
    }))
    const results = await page.evaluate((it) => window.__a2encode(it), items)

    const meta = []
    for (const r of results) {
      const p = path.join(OUT, `${r.name}-q${r.q}.jpg`)
      const buf = Buffer.from(r.b64, 'base64')
      fs.writeFileSync(p, buf)
      meta.push({ fixture: r.name, q: r.q, bytes: buf.length, deterministic: r.deterministic, path: p })
      console.log(`  chromium ${r.name} q${r.q}: ${buf.length} bytes deterministic=${r.deterministic}`)
    }
    fs.writeFileSync(
      path.join(A2, 'out', 'chromium-meta.json'),
      JSON.stringify({ generatedAt: new Date().toISOString(), versions, ua, cells: meta }, null, 2)
    )
  } finally {
    await app.close().catch(() => undefined)
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
