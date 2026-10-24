const path = require('node:path')
const { _electron: electron } = require('playwright-core')

async function main() {
  const project = path.resolve(__dirname, '..')
  const app = await electron.launch({ executablePath: require('electron'), args: ['.'], cwd: project })
  const page = await app.firstWindow()
  const failed = []
  page.on('requestfailed', (r) => failed.push(`${r.url()} :: ${r.failure()?.errorText}`))
  page.on('response', (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`) })
  page.on('console', (m) => { if (m.type() === 'error') failed.push('console: ' + m.text()) })
  await page.waitForSelector('.app-shell.ready', { timeout: 20000 })
  await page.waitForTimeout(2500)
  console.log(failed)
  await app.close()
}
main().catch((e) => { console.error(e); process.exit(1) })
