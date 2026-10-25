#!/usr/bin/env node
/**
 * vivo-vector.cjs — Android VectorDrawable extractor/converter for vivo
 * system-camera template APKs.
 *
 * Reads a template `edit.apk` (ZIP) containing binary AXML (`res/*.xml`),
 * decodes AXML back into a VectorDrawable tree (paths + fills/strokes +
 * viewport + group transforms), and emits a compact typed TS module the vivo
 * IR can consume. Path data is copied verbatim from the vendor file — nothing
 * here redraws or approximates artwork.
 *
 * Usage:
 *   node vivo-vector.cjs list <apk>                     # zip entries
 *   node vivo-vector.cjs dump <apk> [entry ...]         # decoded AXML (debug)
 *   node vivo-vector.cjs vector <apk> <entry> [...]     # VectorDrawable JSON
 *   node vivo-vector.cjs emit <config.json> <out.ts>    # generated TS module
 *
 * config.json = { "sources": [{"key":"<artKey>","apk":"<abs path>","entry":"res/x.xml"}] }
 */
'use strict'
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

/* ------------------------------ ZIP reading ------------------------------ */

function zipRead(buf) {
  // locate EOCD
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('not a zip: no EOCD')
  const n = buf.readUInt16LE(eocd + 10)
  let off = buf.readUInt32LE(eocd + 16)
  const entries = new Map()
  for (let i = 0; i < n; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('bad central dir')
    const method = buf.readUInt16LE(off + 10)
    const csize = buf.readUInt32LE(off + 20)
    const nameLen = buf.readUInt16LE(off + 28)
    const extraLen = buf.readUInt16LE(off + 30)
    const cmtLen = buf.readUInt16LE(off + 32)
    const lho = buf.readUInt32LE(off + 42)
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen)
    off += 46 + nameLen + extraLen + cmtLen
    entries.set(name, { method, csize, lho })
  }
  return {
    names: [...entries.keys()],
    read(name) {
      const e = entries.get(name)
      if (!e) throw new Error('zip entry missing: ' + name)
      const l = e.lho
      if (buf.readUInt32LE(l) !== 0x04034b50) throw new Error('bad local header ' + name)
      const nameLen = buf.readUInt16LE(l + 26)
      const extraLen = buf.readUInt16LE(l + 28)
      const start = l + 30 + nameLen + extraLen
      const raw = buf.subarray(start, start + e.csize)
      return e.method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw)
    }
  }
}

/* ---------------------------- AXML decoding ----------------------------- */

const AndroidAttrNames = {
  0x01010003: 'name',
  0x010100d9: 'visibility',
  0x01010155: 'height',
  0x01010159: 'width',
  0x01010402: 'viewportWidth',
  0x01010403: 'viewportHeight',
  0x01010404: 'fillColor',
  0x01010405: 'pathData',
  0x01010406: 'strokeColor',
  0x01010407: 'strokeWidth',
  0x01010408: 'strokeAlpha',
  0x01010409: 'fillAlpha',
  0x0101040a: 'strokeLineCap',
  0x0101040b: 'strokeLineJoin',
  0x0101040c: 'strokeMiterLimit',
  0x0101040d: 'trimPathStart',
  0x0101040e: 'trimPathEnd',
  0x0101040f: 'trimPathOffset',
  0x01010410: 'strokeLineCap',
  0x01010411: 'strokeLineJoin',
  0x01010412: 'strokeMiterLimit',
  0x01010413: 'pivotX',
  0x01010414: 'pivotY',
  0x01010415: 'scaleX',
  0x01010416: 'scaleY',
  0x01010417: 'rotation',
  0x01010418: 'translationX',
  0x01010419: 'translationY',
  0x010104cc: 'fillAlpha',
  0x01010454: 'fillType',
  0x010104f0: 'tint'
}

function decodeStringPool(buf, start) {
  const stringCount = buf.readUInt32LE(start + 8)
  const flags = buf.readUInt32LE(start + 16)
  const stringsStart = buf.readUInt32LE(start + 20)
  const isUtf8 = (flags & 0x100) !== 0
  const offsets = []
  for (let i = 0; i < stringCount; i++) offsets.push(buf.readUInt32LE(start + 28 + i * 4))
  const base = start + stringsStart
  return offsets.map((o) => {
    let p = base + o
    if (isUtf8) {
      const readLen = () => {
        const c = buf[p]
        p += c > 127 ? 2 : 1
        return c > 127 ? (((c & 0x7f) << 8) | buf[p - 1 + 1 - 1]) : c
      }
      // u16 chars (1 or 2 bytes), u16 bytes (1 or 2 bytes)
      let n = buf[p]; p++
      if (n > 127) { n = ((n & 0x7f) << 8) | buf[p]; p++ }
      let m = buf[p]; p++
      if (m > 127) { m = ((m & 0x7f) << 8) | buf[p]; p++ }
      const s = buf.toString('utf8', p, p + m)
      void n; void readLen
      return s
    }
    let n = buf.readUInt16LE(p); p += 2
    if (n > 0x7fff) { n = ((n & 0x7fff) << 16) | buf.readUInt16LE(p); p += 2 }
    const s = buf.toString('utf16le', p, p + n * 2)
    return s
  })
}

function value(buf, pool, p) {
  const size = buf.readUInt16LE(p)
  const dataType = buf[p + 3]
  const data = buf.readUInt32LE(p + 4)
  const out = { t: dataType, d: data }
  switch (dataType) {
    case 0x00: return { kind: 'null' }
    case 0x01: return { kind: 'reference', id: '0x' + data.toString(16), data }
    case 0x02: return { kind: 'attribute', id: '0x' + data.toString(16) }
    case 0x03: return { kind: 'string', v: pool[data] ?? '' }
    case 0x04: return { kind: 'float', v: buf.readFloatLE(p + 4) }
    case 0x05: case 0x06: return { kind: 'dimen', v: floatComplex(data) }
    case 0x10: return { kind: 'int', v: buf.readInt32LE(p + 4) }
    case 0x11: return { kind: 'hex', v: '0x' + data.toString(16).padStart(8, '0') }
    case 0x12: return { kind: 'bool', v: data !== 0 }
    case 0x1c: case 0x1d: case 0x1e: case 0x1f:
      return { kind: 'color', v: intColor(data, dataType) }
    default: return { kind: 'raw', t: dataType, v: data, size }
  }
}

function floatComplex(d) {
  const radix = d & 0x0f
  const mant = d >> 8
  const unit = [0, 1, 2, 3, 4, 5, 6, 8, 9, 10][radix] ?? radix
  return { value: mant / (1 << (radix * 4)), unit }
}

function intColor(data, type) {
  // android color ints are AARRGGBB
  const a = (data >>> 24) & 0xff
  const r = (data >>> 16) & 0xff
  const g = (data >>> 8) & 0xff
  const b = data & 0xff
  if (type === 0x1e || type === 0x1f) {
    // 4-bit alpha/RGB replicated to 8 bits
    const s = (v4) => ((v4 << 4) | v4).toString(16).padStart(2, '0')
    return '#' + s(a & 0xf) + s(r & 0xf) + s(g & 0xf) + s(b & 0xf)
  }
  const h = (v) => v.toString(16).padStart(2, '0')
  return '#' + h(a) + h(r) + h(g) + h(b)
}

/** Decode a binary AXML buffer into an element tree with android attrs. */
function decodeAXML(buf) {
  if (buf.readUInt16LE(0) !== 0x0003) throw new Error('not RES_XML_TYPE')
  let pool = []
  let resMap = []
  const root = { tag: '#root', attrs: {}, children: [] }
  const stack = [root]
  let p = 8 // after header + lineNumber + comment
  while (p + 8 <= buf.length) {
    const type = buf.readUInt16LE(p)
    const size = buf.readUInt32LE(p + 4)
    if (size < 8 || p + size > buf.length) break
    if (type === 0x0001) pool = decodeStringPool(buf, p)
    else if (type === 0x0180) {
      resMap = []
      for (let i = p + 8; i < p + size; i += 4) resMap.push(buf.readUInt32LE(i))
    } else if (type === 0x0102) {
      // line(4) comment(4) at p+8..p+16, then attrExt: ns(4) name(4)
      const nameIdx = buf.readUInt32LE(p + 20)
      const attrStart = buf.readUInt16LE(p + 24)
      const attrSize = buf.readUInt16LE(p + 26)
      const attrCount = buf.readUInt16LE(p + 28)
      const attrs = {}
      const aBase = p + 16 + attrStart
      for (let i = 0; i < attrCount; i++) {
        const a = aBase + i * attrSize
        const nsIdx = buf.readUInt32LE(a)
        const nmIdx = buf.readUInt32LE(a + 4)
        const rawIdx = buf.readInt32LE(a + 8)
        const val = value(buf, pool, a + 12)
        let nm
        if (nsIdx !== 0xffffffff) {
          nm = resMap[nmIdx] ? '0x' + resMap[nmIdx].toString(16) : 'attr#' + nmIdx
          if (resMap[nmIdx] && (resMap[nmIdx] >>> 16) === 0x0101) {
            nm = AndroidAttrNames[resMap[nmIdx]] || nm
          }
        } else nm = pool[nmIdx] ?? ('attr#' + nmIdx)
        attrs[nm] = val.kind === 'string' ? val.v : val
        if (rawIdx >= 0 && val.kind !== 'string') attrs[nm + '@raw'] = pool[rawIdx]
      }
      const el = { tag: pool[nameIdx] ?? ('el#' + nameIdx), attrs, children: [] }
      stack[stack.length - 1].children.push(el)
      stack.push(el)
    } else if (type === 0x0103) {
      stack.pop()
    }
    p += size
  }
  return root.children[0] || null
}

/* ------------------------ VectorDrawable extraction ---------------------- */

/** Minimal resources.arsc color resolver: resid 0xPPTTEEEE -> '#AARRGGBB'. */
function arscColor(arsc, resid) {
  const typeId = (resid >>> 16) & 0xff
  const entryId = resid & 0xff
  // after the table header (12) comes the global string pool, then package chunks
  let p = 12
  const poolSize = arsc.readUInt32LE(p + 4)
  p += poolSize
  while (p + 8 <= arsc.length) {
    if (arsc.readUInt16LE(p) !== 0x0200) break
    const pkg = p
    const pkgSize = arsc.readUInt32LE(p + 4)
    let r = pkg + 268
    while (r + 8 < pkg + pkgSize) {
      const t = arsc.readUInt16LE(r)
      const s2 = arsc.readUInt32LE(r + 4)
      if (t !== 0x0201 || s2 < 28 || s2 > pkgSize) { r += 4; continue }
      if (arsc[r + 8] === typeId) {
        const entryCount = arsc.readUInt32LE(r + 12)
        const startOfEntries = arsc.readUInt32LE(r + 16)
        const hs = arsc.readUInt16LE(r + 2) || 28 // entry-offsets array follows the fixed header
        if (entryId >= entryCount) return null
        const eoff = arsc.readUInt32LE(r + hs + entryId * 4)
        if (eoff === 0xffffffff) return null
        const ep = r + startOfEntries + eoff
        const flags = arsc.readUInt16LE(ep + 2)
        if (flags & 0x1) return null // complex entry (style/selector) — not a literal color
        const vt = arsc[ep + 8 + 3] // ResTable_entry: size(2) flags(2) key(4), then Res_value
        const vd = arsc.readUInt32LE(ep + 8 + 4)
        if (vt >= 0x1c && vt <= 0x1f) return intColor(vd, vt)
        if (vt === 0x01) return arscColor(arsc, vd)
        return null
      }
      r += s2
    }
    p += pkgSize
  }
  return null
}

function arscColorSafe(arsc, resid) {
  try { return arscColor(arsc, resid) } catch { return null }
}

function vectorize(el, arsc) {
  if (!el || el.tag !== 'vector') return null
  const num = (v) =>
    v == null ? undefined : typeof v === 'object' ? v.kind === 'float' ? v.v : v.kind === 'dimen' ? v.v.value : v.kind === 'int' ? v.v : undefined : v
  const color = (a) => {
    if (!a || typeof a === 'string') return a ?? undefined
    if (a.kind === 'color') return a.v
    if (a.kind === 'string' && String(a.v).startsWith('#')) return a.v
    if (a.kind === 'reference' && arsc) return arscColorSafe(arsc, a.data) ?? { unresolved: a.id }
    if (a.kind === 'int') return intColor(a.v, 0x1c)
    if (a.kind === 'hex') return '#' + a.v.slice(2)
    return undefined
  }
  const conv = (node) => {
    if (node.tag === 'path' || node.tag === 'clip-path') {
      const out = {}
      if (node.attrs.pathData) out.d = node.attrs.pathData
      const f = color(node.attrs.fillColor); if (f && !f.unresolved) out.fill = f
      const s = color(node.attrs.strokeColor); if (s && !s.unresolved) out.stroke = s
      const sw = num(node.attrs.strokeWidth); if (sw != null) out.sw = sw
      const fa = num(node.attrs.fillAlpha); if (fa != null && fa !== 1) out.fa = fa
      const sa = num(node.attrs.strokeAlpha); if (sa != null && sa !== 1) out.sa = sa
      if (node.attrs.fillType === 'evenOdd') out.rule = 'evenodd'
      return node.tag === 'clip-path' ? { clip: out.d } : out
    }
    if (node.tag === 'group') {
      const g = { c: node.children.map(conv).filter(Boolean) }
      const t = (k) => num(node.attrs[k])
      if (t('translationX') != null) g.tx = t('translationX')
      if (t('translationY') != null) g.ty = t('translationY')
      if (t('scaleX') != null) g.sx = t('scaleX')
      if (t('scaleY') != null) g.sy = t('scaleY')
      if (t('rotation') != null) g.rot = t('rotation')
      if (t('pivotX') != null) g.px = t('pivotX')
      if (t('pivotY') != null) g.py = t('pivotY')
      return g
    }
    return null
  }
  return {
    vw: num(el.attrs.viewportWidth) || 1,
    vh: num(el.attrs.viewportHeight) || 1,
    name: typeof el.attrs.name === 'string' ? el.attrs.name : undefined,
    tree: el.children.map(conv).filter(Boolean)
  }
}

/* --------------------------------- CLI ---------------------------------- */

const args = process.argv.slice(2)
const cmd = args[0]

function loadApk(p) {
  const zip = zipRead(fs.readFileSync(p))
  let arsc = null
  if (zip.names.includes('resources.arsc')) arsc = zip.read('resources.arsc')
  return { zip, arsc }
}

if (cmd === 'list') {
  const { zip } = loadApk(args[1])
  for (const n of zip.names) {
    const b = zip.read(n)
    let tag = ''
    if (n.endsWith('.xml') && b.readUInt16LE(0) === 0x0003) tag = ' AXML'
    console.log(`${n}\t${b.length}${tag}`)
  }
} else if (cmd === 'dump' || cmd === 'vector') {
  const { zip, arsc } = loadApk(args[1])
  const entries = args.slice(2).length ? args.slice(2) : zip.names.filter((n) => n.startsWith('res/') && n.endsWith('.xml'))
  for (const e of entries) {
    const tree = decodeAXML(zip.read(e))
    if (cmd === 'dump') { console.log('== ' + e); console.log(JSON.stringify(tree, null, 1)); continue }
    const v = vectorize(tree, arsc)
    console.log('== ' + e + (v ? `  [vector ${v.vw}x${v.vh} paths=${v.tree.length}]` : '  [not a vector]'))
    if (v) console.log(JSON.stringify(v))
  }
} else if (cmd === 'color') {
  const { arsc } = loadApk(args[1])
  console.log('direct:', arscColor(arsc, parseInt(args[2], 16)))
} else if (cmd === 'emit') {
  const cfg = JSON.parse(fs.readFileSync(args[1], 'utf8'))
  const art = {}
  for (const s of cfg.sources) {
    const { zip, arsc } = loadApk(s.apk)
    const tree = decodeAXML(zip.read(s.entry))
    const v = vectorize(tree, arsc)
    if (!v) throw new Error('entry is not a vector: ' + s.apk + ' ' + s.entry)
    art[s.key] = { ...v, src: path.basename(s.apk) + '#' + s.entry }
  }
  const header =
    '/* GENERATED by scripts/system-camera/adapters/vivo-vector.cjs — do not hand-edit.\n' +
    ' * Source: Android VectorDrawable XML extracted verbatim from the vendor\n' +
    ' * template APKs listed in `src` per entry (path data unmodified). */\n\n' +
    'export interface VdPathNode { d: string; fill?: string; stroke?: string; sw?: number; fa?: number; sa?: number; rule?: string }\n' +
    'export interface VdClipNode { clip: string }\n' +
    'export interface VdGroupNode { tx?: number; ty?: number; sx?: number; sy?: number; rot?: number; px?: number; py?: number; c: Array<VdPathNode | VdClipNode | VdGroupNode> }\n' +
    'export interface VdArt { vw: number; vh: number; name?: string; src: string; tree: VdNode[] }\n' +
    'export type VdNode = VdPathNode | VdClipNode | VdGroupNode\n\n' +
    'export const VIVO_VECTOR_ART: Record<string, VdArt> = '
  fs.writeFileSync(args[2], header + JSON.stringify(art, null, 1) + '\nexport default VIVO_VECTOR_ART\n', 'utf8')
  console.log('wrote ' + args[2] + ' keys=' + Object.keys(art).join(','))
} else {
  console.log('usage: vivo-vector.cjs list|dump|vector <apk> [entries] | emit <config.json> <out.ts>')
}
