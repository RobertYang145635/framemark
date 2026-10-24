const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const project = path.resolve(__dirname, '..')
const outDir = path.join(project, 'src/renderer/src/assets/previews/rendered')
fs.mkdirSync(outDir, { recursive: true })

async function main() {
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  const list = await page.evaluate(() => window.__fmVariants.list())
  console.log('variants', list.length)
  let ok = 0
  let failed = 0
  for (const item of list) {
    try {
      const res = await page.evaluate((id) => window.__fmVariants.render(id), item.id)
      const buf = Buffer.from(res.data.replace(/^data:image\/\w+;base64,/, ''), 'base64')
      if (buf.length < 800) throw new Error(`preview too small: ${buf.length}`)
      fs.writeFileSync(path.join(outDir, `${item.id}.webp`), buf)
      console.log('OK', item.id, res.W, res.H, buf.length)
      ok++
    } catch (e) {
      console.error('FAIL', item.id, e.message)
      failed++
    }
  }
  console.log({ ok, failed, errors: errors.slice(0, 5) })
  await app.close()
  if (failed) process.exitCode = 1
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
