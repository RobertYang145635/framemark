const { spawnSync } = require('node:child_process')
const ADB = 'D:/dev/android-sdk/platform-tools/adb.exe'
const target = process.argv[2] || ''
const maxScroll = Number(process.argv[3] || 0)
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)

function adb(args) {
  const r = spawnSync(ADB, args, { encoding: 'utf8', maxBuffer: 40 * 1024 * 1024 })
  return (r.stdout || '').replace(/\r/g, '')
}
function dump() {
  adb(['shell', 'uiautomator', 'dump', '/sdcard/Download/ui.xml'])
  const xml = adb(['shell', 'cat', '/sdcard/Download/ui.xml'])
  adb(['shell', 'rm', '/sdcard/Download/ui.xml'])
  return xml
}
function find(xml) {
  const out = []
  for (const m of xml.matchAll(/<node[^>]*>/g)) {
    const t = m[0]
    const desc = (t.match(/content-desc="([^"]*)"/) || [])[1] || ''
    const text = (t.match(/text="([^"]*)"/) || [])[1] || ''
    const bounds = (t.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) || [])
    if (!bounds.length) continue
    if (!(desc.includes(target) || text.includes(target))) continue
    out.push({ desc, text, x1: +bounds[1], y1: +bounds[2], x2: +bounds[3], y2: +bounds[4] })
  }
  return out
}
function tapNode(n) {
  const x = Math.round((n.x1 + n.x2) / 2)
  const y = Math.round((n.y1 + n.y2) / 2)
  adb(['shell', 'input', 'tap', String(x), String(y)])
  console.log('TAP', x, y, JSON.stringify({ desc: n.desc, text: n.text }))
}
let found = find(dump()).filter((n) => n.y1 < 2900 && n.y2 > 300)
if (found.length) {
  tapNode(found[0])
  process.exit(0)
}
for (let i = 0; i < maxScroll; i++) {
  adb(['shell', 'input', 'swipe', '720', '2600', '720', '700', '280'])
  sleep(500)
  found = find(dump()).filter((n) => n.y1 < 2900 && n.y2 > 300)
  if (found.length) {
    tapNode(found[0])
    process.exit(0)
  }
}
console.error('NOT FOUND', target)
process.exit(2)
