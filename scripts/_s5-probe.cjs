/**
 * S5 throw-away probe: proves the env -> main -> preload -> renderer flag
 * plumbing and lists the renderable variants the tile gate can drive.
 *
 * Usage: node scripts/_s5-probe.cjs [TILE] [CANARY]
 * Prints one JSON object. Never touches user data (throw-away profile).
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const { _electron: electron } = require('playwright-core')

const require2 = createRequire(__filename)
const root = path.resolve(__dirname, '..')
const workspace = path.resolve(root, '..')
const runDir = path.join(workspace, '_research', 's5', 'probe')
const profile = path.join(runDir, 'profile')

const tile = process.argv[2] === '1' ? '1' : process.argv[2] === '0' ? '0' : undefined
const canary = process.argv[3] === '1' ? '1' : process.argv[3] === '0' ? '0' : undefined

async function main() {
  fs.rmSync(runDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 150 })
  fs.mkdirSync(profile, { recursive: true })
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  if (tile === undefined) delete env.FRAMEMARK_TILE
  else env.FRAMEMARK_TILE = tile
  if (canary === undefined) delete env.FRAMEMARK_SEAM_CANARY
  else env.FRAMEMARK_SEAM_CANARY = canary

  const app = await electron.launch({
    executablePath: require2('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })
  try {
    const page = await app.firstWindow()
    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    const bridge = await page.evaluate(() => (window.framemark ? JSON.parse(JSON.stringify(window.framemark.flags)) : null))
    const debug = await page.evaluate(() => (window.__fmFlags ? window.__fmFlags.get() : null))
    const variants = await page.evaluate(() => window.__fmVariants.list())
    const stock = await page.evaluate(() => window.__fmStock.list())
    const out = {
      env: { tile: env.FRAMEMARK_TILE ?? null, seamCanary: env.FRAMEMARK_SEAM_CANARY ?? null },
      bridge,
      debug,
      variants: variants.length,
      stock: stock.length,
      variantIds: variants.map((v) => v.id),
      stockIds: stock.slice(0, 8).map((s) => s.id),
      errors
    }
    console.log(JSON.stringify(out, null, 1))
  } finally {
    await app.close().catch(() => undefined)
  }
}

main().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
