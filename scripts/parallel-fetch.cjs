// Multi-connection, resumable HTTP range downloader using curl per chunk.
// Usage: node parallel-fetch.cjs --url URL --out FILE [--connections 32] [--chunk-mb 16] [--referer URL]
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]])
    return acc
  }, [])
)
const url = args.url
const out = args.out
const connections = Number(args.connections || 32)
const chunkSize = Number(args['chunk-mb'] || 16) * 1024 * 1024
const referer = args.referer || ''
const forcedSize = Number(args.size || 0)
if (!url || !out) {
  console.error('usage: node parallel-fetch.cjs --url URL --out FILE [--connections 32] [--chunk-mb 16] [--referer URL]')
  process.exit(2)
}
const chunkDir = `${out}.chunks`
fs.mkdirSync(chunkDir, { recursive: true })

function curl(args, capture = false) {
  return new Promise((resolve, reject) => {
    const p = spawn('curl', args, { windowsHide: true })
    let stdout = ''
    let stderr = ''
    if (capture) p.stdout.on('data', (d) => (stdout += d))
    p.stderr.on('data', (d) => (stderr += d))
    p.on('error', reject)
    p.on('close', (code) => resolve({ code, stdout, stderr }))
  })
}

async function probeSize() {
  const headers = ['-A', 'Mozilla/5.0']
  if (referer) headers.push('-e', referer)
  const r = await curl(['-sL', ...headers, '-r', '0-0', '-D', '-', '-o', process.platform === 'win32' ? 'NUL' : '/dev/null', url], true)
  const m = /content-range:\s*bytes\s+0-0\/(\d+)/i.exec(r.stdout)
  if (m) return Number(m[1])
  const len = /content-length:\s*(\d+)/i.exec(r.stdout)
  if (len) return Number(len[1])
  throw new Error(`cannot probe size (curl ${r.code}): ${r.stdout.slice(0, 300)} ${r.stderr.slice(0, 300)}`)
}

async function main() {
  const size = forcedSize || (await probeSize())
  if (!Number.isFinite(size) || size <= 0) throw new Error('bad size')
  const totalChunks = Math.ceil(size / chunkSize)
  console.log(`size=${size} chunks=${totalChunks} conns=${connections} chunk=${chunkSize}`)

  if (fs.existsSync(out) && fs.statSync(out).size === size) {
    console.log('already complete')
    return
  }

  const pending = []
  for (let i = 0; i < totalChunks; i++) {
    const start = i * chunkSize
    const end = Math.min(size - 1, start + chunkSize - 1)
    const file = path.join(chunkDir, `chunk-${String(i).padStart(5, '0')}`)
    const expected = end - start + 1
    if (fs.existsSync(file) && fs.statSync(file).size === expected) continue
    pending.push({ i, start, end, expected, file })
  }
  console.log(`pending=${pending.length}`)

  let done = totalChunks - pending.length
  let failures = 0
  const started = Date.now()
  let lastReport = Date.now()
  let lastBytes = done * chunkSize
  let lastTime = started
  const headers = ['-A', 'Mozilla/5.0']
  if (referer) headers.push('-e', referer)

  async function fetchChunk(c) {
    for (let attempt = 1; attempt <= 5; attempt++) {
      try { fs.unlinkSync(c.file) } catch {}
      const r = await curl([
        '-sL', '--fail', '--max-time', '1800', '--speed-limit', '2000', '--speed-time', '40', ...headers,
        '-r', `${c.start}-${c.end}`,
        '-o', c.file,
        url
      ])
      if (r.code === 0) {
        const got = fs.existsSync(c.file) ? fs.statSync(c.file).size : 0
        if (got === c.expected) return true
      }
      await new Promise((res) => setTimeout(res, 1500 * attempt))
    }
    failures++
    return false
  }

  async function worker(queue) {
    while (queue.length) {
      const c = queue.shift()
      const ok = await fetchChunk(c)
      if (ok) {
        done++
      } else {
        console.error(`chunk failed: ${c.i} ${c.start}-${c.end}`)
      }
      report()
    }
  }

  function report() {
    const now = Date.now()
    if (now - lastReport < 5000) return
    lastReport = now
    const pct = (done / totalChunks) * 100
    const bytesNow = done * chunkSize
    const rate = (bytesNow - lastBytes) / Math.max(1, now - lastTime) / 1e6
    lastBytes = bytesNow
    lastTime = now
    const remain = totalChunks - done
    const eta = rate > 0.01 ? (remain * chunkSize) / (rate * 1e6) : 0
    console.log(`progress ${done}/${totalChunks} ${pct.toFixed(1)}% ${rate.toFixed(2)}MB/s eta ${(eta / 60).toFixed(1)}min fail=${failures}`)
  }
  const timer = setInterval(report, 8000)
  const queue = pending.slice()
  await Promise.all(Array.from({ length: Math.min(connections, pending.length) }, () => worker(queue)))
  clearInterval(timer)

  // Any chunks that failed remain; re-queue once.
  const remaining = []
  for (let i = 0; i < totalChunks; i++) {
    const start = i * chunkSize
    const end = Math.min(size - 1, start + chunkSize - 1)
    const file = path.join(chunkDir, `chunk-${String(i).padStart(5, '0')}`)
    if (!fs.existsSync(file) || fs.statSync(file).size !== end - start + 1) remaining.push(i)
  }
  if (remaining.length) {
    console.error(`still missing ${remaining.length} chunks after retries`)
    process.exit(1)
  }

  console.log('assembling')
  const ws = fs.createWriteStream(out)
  for (let i = 0; i < totalChunks; i++) {
    const file = path.join(chunkDir, `chunk-${String(i).padStart(5, '0')}`)
    await new Promise((res, rej) => {
      const rs = fs.createReadStream(file)
      rs.on('error', rej)
      rs.on('end', res)
      rs.pipe(ws, { end: false })
    })
  }
  await new Promise((res) => ws.end(res))
  const finalSize = fs.statSync(out).size
  console.log(`assembled ${out} size=${finalSize} expected=${size} ${finalSize === size ? 'OK' : 'MISMATCH'}`)
  if (finalSize !== size) process.exit(1)
  fs.rmSync(chunkDir, { recursive: true, force: true })
}
main().catch((e) => { console.error(e); process.exit(1) })
