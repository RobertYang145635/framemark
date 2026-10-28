/**
 * S4 timing probe (not a gate): how long does one item take at 长边限制=4096?
 * Needed to size the gate-4 batch so the export is still running 3 minutes in.
 *
 * Usage: node scripts/_s4-timing.cjs [count=8]
 */
const path = require('node:path')
const fs = require('node:fs')
const L = require('./_s4-lib.cjs')

async function main() {
  const count = Number(process.argv[2] || 8)
  const setName = process.argv[3] || 's4-fixture'
  const appPid = await L.forceStopAndStart()
  console.log(`pid=${appPid}`)
  const { browser, page } = await L.connectOverCdp()
  await L.waitForApp(page)
  const fixtures = fs
    .readdirSync(L.FIXTURES)
    .filter((f) => new RegExp('^' + setName + '-[0-9]+\.jpg$').test(f))
    .sort()
    .slice(0, count)
    .map((f) => path.join(L.FIXTURES, f))
  await L.injectFixtures(page, fixtures)
  console.log(`thumbs=${await L.waitForThumbs(page, count)}`)
  await L.dismissResumeCard(page)

  await L.dismissResumeCard(page)
  const open = page.locator('button:has-text("导出当前")').first()
  await open.click()
  await L.sleep(500)
  await page.locator('.segmented button:has-text("全部")').first().click()
  await L.sleep(200)
  await page.locator('.toggle-chip:has-text("4096")').first().click()
  await L.sleep(200)
  const t0 = Date.now()
  await page.locator('button:has-text("开始导出")').first().click()

  let job = { done: 0, total: count }
  const deadline = Date.now() + 180000
  while (Date.now() < deadline) {
    job = await L.readJob(page)
    if (!job.running) break
    await L.sleep(300)
  }
  const elapsed = Date.now() - t0
  console.log(JSON.stringify({ count, elapsedMs: elapsed, perItemMs: Math.round(elapsed / count), job }, null, 2))
  await browser.close().catch(() => undefined)
}

main().catch((e) => {
  console.error(e)
  process.exitCode = 1
})
