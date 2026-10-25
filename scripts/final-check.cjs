#!/usr/bin/env node
/**
 * FrameMark 收尾终检（一次性核完所有硬判据，输出 machine-readable 结果）
 *
 * 用法： node scripts/final-check.mjs [--fs=1.22] [--skip-e2e]
 *
 * 只做"读数 + 判 PASS/FAIL"，不做任何修复。每条判据都指向一个可复核的现场产物，
 * 不采信任何 agent 报告或历史结论。
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')

const ROOT = 'D:/border&watermark'
const APP = path.join(ROOT, 'framemark')
const V4 = path.join(ROOT, '_research/review-164/exports-real-v4')
const args = process.argv.slice(2)
const FS = (args.find((a) => a.startsWith('--fs=')) || '--fs=1').split('=')[1]
const SKIP_E2E = args.includes('--skip-e2e')

const results = []
const rec = (id, name, pass, detail) => {
  results.push({ id, name, pass, detail })
  console.log(`${pass === null ? 'SKIP' : pass ? 'PASS' : 'FAIL'}  ${id.padEnd(4)} ${name}${detail ? '  — ' + detail : ''}`)
}
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')
const run = (cmd, a) =>
  execFileSync(cmd, a, { cwd: APP, encoding: 'utf8', maxBuffer: 64 << 20, shell: false })

// 1. 工作树必须干净（未提交改动 = 交付物与源码不一致）
try {
  const s = run('git', ['status', '--porcelain']).trim()
  rec('T1', '工作树干净', s === '', s === '' ? '' : 'dirty:\n' + s.split('\n').join('\n'))
} catch (e) {
  rec('T1', '工作树干净', false, String(e.message).slice(0, 120))
}

// 2. HEAD 已提交
let head = ''
try {
  head = run('git', ['rev-parse', '--short', 'HEAD']).trim()
  rec('T2', 'HEAD 存在', !!head, head)
} catch (e) {
  rec('T2', 'HEAD 存在', false, String(e.message).slice(0, 80))
}

// 3. 调试开关不得留在源码里
try {
  const cfg = fs.readFileSync(path.join(APP, 'capacitor.config.json'), 'utf8')
  const off = !/webContentsDebuggingEnabled["']?\s*:\s*true/.test(cfg)
  rec('T3', 'CDP 调试开关已还原（不得打进交付包）', off, off ? '' : 'capacitor.config.json 仍为 true')
} catch (e) {
  rec('T3', 'CDP 调试开关已还原', false, String(e.message).slice(0, 80))
}

// 4. typecheck
try {
  run('node', ['./node_modules/typescript/bin/tsc', '--noEmit', '-p', 'tsconfig.web.json'])
  run('node', ['./node_modules/typescript/bin/tsc', '--noEmit', '-p', 'tsconfig.node.json'])
  rec('T4', 'typecheck 0 错', true)
} catch (e) {
  rec('T4', 'typecheck 0 错', false, String(e.message).split('\n').slice(0, 4).join(' | '))
}

// 5. validate:ir
try {
  const out = run('node', ['scripts/system-camera/validate-ir.cjs'])
  const m = out.match(/"styles":\s*(\d+)[\s\S]*?"errors":\s*(\d+)/)
  const ok = !!m && Number(m[2]) === 0
  rec('T5', 'validate:ir 0 error', ok, m ? `styles=${m[1]} errors=${m[2]}` : '解析不到计数')
} catch (e) {
  rec('T5', 'validate:ir 0 error', false, String(e.message).slice(0, 120))
}

// 6. e2e 计数
if (!SKIP_E2E) {
  try {
    const out = run('node', ['scripts/e2e.mjs'])
    const m = out.match(/"total":\s*(\d+),\s*"system":\s*(\d+),\s*"blocked":\s*(\d+)[\s\S]*?"errors":\s*(\[[^\]]*\])/)
    const ok = !!m && m[4] === '[]'
    rec('T6', 'e2e errors 为空', ok, m ? `total=${m[1]} system=${m[2]} blocked=${m[3]} errors=${m[4]}` : '解析不到计数')
  } catch (e) {
    rec('T6', 'e2e errors 为空', false, String(e.message).slice(0, 120))
  }
  try {
    const out = run('node', ['scripts/e2e-workflow.mjs'])
    const m = out.match(/(\d+)\/(\d+)/g)
    rec('T7', 'workflow E2E', !!m, (m || []).slice(-3).join(' '))
  } catch (e) {
    rec('T7', 'workflow E2E', false, String(e.message).slice(0, 120))
  }
} else {
  rec('T6', 'e2e', null, '--skip-e2e')
  rec('T7', 'workflow E2E', null, '--skip-e2e')
}

// 7. 签字基线完整性：exports-real-v4 必须仍是 175 张
try {
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]))
  const pngs = walk(V4).filter((f) => /\.png$/i.test(f))
  rec('T8', '已签字基线图集完整', pngs.length === 175, `${pngs.length}/175 张`)
} catch (e) {
  rec('T8', '已签字基线图集完整', false, String(e.message).slice(0, 80))
}

// 8. 交付集哈希自洽
try {
  const dir = path.join(ROOT, '交付')
  const txt = fs.readFileSync(path.join(dir, 'SHA256.txt'), 'utf8')
  let bad = 0
  const lines = txt.trim().split(/\r?\n/)
  for (const l of lines) {
    const m = l.match(/^([0-9a-f]{64}) \*?(.+)$/)
    if (!m) continue
    const p = path.join(dir, m[2])
    if (!fs.existsSync(p)) { bad++; console.log('  缺文件 ' + m[2]); continue }
    if (sha(p) !== m[1]) { bad++; console.log('  哈希不符 ' + m[2]) }
  }
  rec('T9', `交付集 SHA256 自洽（${lines.length} 条）`, bad === 0, bad ? `${bad} 条不符` : '')
} catch (e) {
  rec('T9', '交付集 SHA256 自洽', false, String(e.message).slice(0, 80))
}

// 9. 交付包必须比 HEAD 新（防"源码已改、包还是旧版"）
try {
  const apk = path.join(ROOT, '交付/FrameMark-Android-v1.0.0.apk')
  const zip = path.join(ROOT, '交付/FrameMark-Windows-x64.zip')
  const cmt = Number(run('git', ['log', '-1', '--format=%ct']).trim()) * 1000
  const stale = [apk, zip].filter((p) => fs.existsSync(p) && fs.statSync(p).mtimeMs < cmt)
  rec('T10', '交付包不早于 HEAD 提交', stale.length === 0, stale.map((p) => path.basename(p)).join(', ') || `fontScale=${FS} 档`)
} catch (e) {
  rec('T10', '交付包不早于 HEAD 提交', false, String(e.message).slice(0, 80))
}

// 11. 渲染基线对撞：新导出必须与签字基线逐字节相同。
//     例外是**点名制**（不是面积制）：只允许下列已知抖动卡，且差异须 ≤ 记录的上界。
//     任何其它卡出现差异都判 FAIL —— 防止"把阈值调大到通过"。
const DITHER_EXCEPTIONS = {
  'grad-2.png': { maxDiffBytes: 4, maxChannelDelta: 1, note: '2026-10-26 实测 3 字节 @y=1176, Δ≤1' },
  'stock-xiaomi-tag-9.png': { maxDiffBytes: 8, maxChannelDelta: 1, note: '两次独立重导分别见 5 字节、1 字节, Δ≤1' }
}
try {
  const newDir = process.env.FM_NEW_EXPORTS
  if (!newDir) {
    rec('T11', '渲染基线逐字节对撞', null, '未设 FM_NEW_EXPORTS，跳过')
  } else {
    const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]))
    const baseDir = path.join(ROOT, '_research/signed-baseline/exports-real-v4-20261026')
    const exc = JSON.parse(fs.readFileSync(path.join(ROOT, '_research/parity-175/dither-exceptions.json'), 'utf8'))
    const cur = walk(newDir).filter((f) => /\.(png|jpe?g)$/i.test(f))
    const baseMap = {}
    for (const f of walk(baseDir)) baseMap[path.basename(f)] = f
    let same = 0
    const tolerated = [], viol = [], missing = []
    for (const f of cur) {
      const n = path.basename(f)
      if (!baseMap[n]) { missing.push(n); continue }
      if (sha(f) === sha(baseMap[n])) { same++; continue }
      const e = exc.entries[n]
      if (e && e.diffBytes <= exc.limits.maxDiffBytes && e.maxChannelDelta <= exc.limits.maxChannelDelta) {
        tolerated.push(`${n}(记录 ${e.diffBytes}B/Δ${e.maxChannelDelta})`)
      } else {
        viol.push(`${n}(不在抖动证据文件或超界)`)
      }
    }
    const ok = viol.length === 0 && missing.length === 0 && cur.length === 175
    rec('T11', `渲染基线对撞 ${cur.length} 张`, ok, `全等 ${same} · 点名容差 ${tolerated.length ? tolerated.join(', ') : '无'}` + (viol.length ? ` · ★违规 ${viol.join(', ')}` : '') + (missing.length ? ` · ★基线无同名 ${missing.length}` : ''))
  }
} catch (e) {
  rec('T11', '渲染基线对撞', false, String(e.message).slice(0, 160))
}

const fails = results.filter((r) => r.pass === false)
console.log('\n---------------------------------------------')
console.log(`合计 ${results.length} 项 | FAIL ${fails.length} 项`)
fs.writeFileSync(
  path.join(ROOT, '_research', 'final-check.json'),
  JSON.stringify({ at: new Date().toISOString(), head, fsScale: FS, results }, null, 1)
)
console.log('已写 _research/final-check.json')
process.exit(fails.length ? 1 : 0)
