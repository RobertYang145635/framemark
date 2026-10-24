const path = require('node:path')
const fs = require('node:fs')
const crypto = require('node:crypto')
const { _electron: electron } = require('playwright-core')

const project = path.resolve(__dirname, '..', '..')
const outDir = path.join(project, 'src/renderer/src/assets/previews/system')
fs.mkdirSync(outDir, { recursive: true })

function variantId(styleId) {
  if (styleId.startsWith('oppo-gallery:')) return 'stock-oppo-' + styleId.slice('oppo-gallery:'.length)
  return 'stock-' + styleId.replace(/[:]/g, '-')
}

async function main() {
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  const list = await page.evaluate(() => window.__fmStock.list())
  console.log('styles', list.length)
  let ok = 0
  let failed = 0
  const manifest = []
  for (const item of list) {
    const id = item.id
    const file = path.join(outDir, `${variantId(id)}.webp`)
    try {
      const res = await page.evaluate((styleId) => window.__fmStock.render(styleId), id)
      const buf = Buffer.from(res.data.replace(/^data:image\/\w+;base64,/, ''), 'base64')
      if (buf.length < 800) throw new Error(`preview too small: ${buf.length}`)
      fs.writeFileSync(file, buf)
      manifest.push({
        styleId: id,
        variantId: variantId(id),
        file: path.basename(file),
        bytes: buf.length,
        sha256: crypto.createHash('sha256').update(buf).digest('hex')
      })
      console.log('OK', id, res.W, res.H, buf.length)
      ok++
    } catch (e) {
      console.error('FAIL', id, e.message)
      failed++
    }
  }
  const auditDir = path.resolve(project, '..', '_research', 'stock-audit')
  fs.mkdirSync(auditDir, { recursive: true })
  fs.writeFileSync(path.join(auditDir, 'previews-manifest.json'), JSON.stringify(manifest, null, 2))
  console.log({ ok, failed })
  await app.close()
  if (failed) process.exitCode = 1
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
