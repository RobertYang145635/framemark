/**
 * Diagnostic for S3b gate 5: why doesn't the persisted task match the hydrated
 * design after a restart? Prints both designKeys and a recursive diff.
 * Reuses the profile left behind by `_s3b-design-identity.cjs --gates=g5`.
 */
const path = require('node:path')
const fs = require('node:fs')
const { _electron: electron } = require('playwright-core')

const root = path.resolve(__dirname, '..')
const research = path.join(root, '..', '_research')
const profile = path.join(research, 's3b', 'run', 'resume', 'profile')
if (!fs.existsSync(profile)) throw new Error('run gate 5 first: ' + profile)

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE

;(async () => {
  const app = await electron.launch({
    executablePath: require('electron'),
    args: ['.', `--user-data-dir=${profile}`],
    cwd: root,
    env,
    timeout: 60000
  })
  const page = await app.firstWindow()
  page.setDefaultTimeout(60000)
  await page.waitForSelector('.app-shell.ready', { timeout: 60000 })
  await new Promise((r) => setTimeout(r, 1500))
  const out = await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const foldLong = (value) => {
          if (value.length <= 96) return value
          let h = 0x811c9dc5
          for (let i = 0; i < value.length; i++) {
            h ^= value.charCodeAt(i)
            h = Math.imul(h, 0x01000193)
          }
          return `«${value.length}:${(h >>> 0).toString(36)}»`
        }
        const keyOf = (design) => JSON.stringify(design, (_k, v) => (typeof v === 'string' ? foldLong(v) : v))
        const req = indexedDB.open('framemark')
        req.onerror = () => reject(new Error(String(req.error)))
        req.onsuccess = () => {
          const db = req.result
          const tx = db.transaction('jobs', 'readonly')
          const q = tx.objectStore('jobs').getAll()
          q.onsuccess = () => {
            db.close()
            const jobs = (q.result || []).sort((a, b) => b.createdAt - a.createdAt)
            const task = jobs[0]
            const raw = localStorage.getItem('framemark.lastDesign.v1')
            const last = raw ? JSON.parse(raw) : null
            const taskKey = task ? keyOf(task.design) : null
            const lastKey = last ? keyOf(last) : null
            const diffs = []
            const walk = (a, b, p) => {
              if (a === b) return
              if (typeof a !== typeof b || a === null || b === null || typeof a !== 'object') {
                diffs.push(`${p}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`)
                return
              }
              const keys = new Set([...Object.keys(a), ...Object.keys(b)])
              for (const k of keys) walk(a[k], b[k], p ? `${p}.${k}` : k)
            }
            if (task && last) walk(task.design, last, 'design')
            resolve({
              taskId: task?.id,
              taskStatus: task?.status,
              storedDesignKey: task?.designKey,
              recomputedTaskKey: taskKey,
              recomputedLastKey: lastKey,
              storedMatchesTask: task?.designKey === taskKey,
              lastMatchesTask: task?.designKey === lastKey,
              diffCount: diffs.length,
              diffs: diffs.slice(0, 25),
              taskLogoName: task?.design?.logo?.name,
              lastLogoName: last?.logo?.name,
              taskBrandVersion: task?.design?.brandVersion ?? null,
              lastBrandVersion: last?.brandVersion ?? null
            })
          }
          q.onerror = () => reject(q.error)
        }
      })
  )
  console.log(JSON.stringify(out, null, 2))
  await app.close()
})().catch((e) => {
  console.error(e)
  process.exit(1)
})
