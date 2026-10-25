// Convert OPPO Gallery master-watermark JSONs into FrameMark's stock IR.
// Source: OPPO Gallery 17.10.7 (com.coloros.gallery3d 17010007, Find X9 Ultra PMA120).
// Read-only over _research; writes generated TS + copies referenced bitmaps into app assets.
// Usage: node oppo.cjs [--styles-only]  (--styles-only skips generated/oppoVariants.ts)
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const ROOT = 'D:/border&watermark/_research/vendor-latest/oppo/assets'
const STYLE_DIR = path.join(ROOT, 'watermark_master_styles')
const OUT_DIR = 'D:/border&watermark/framemark/src/renderer/src/core/system/generated'
const APP_ASSET_DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/system/oppo/gallery'
const STYLES_ONLY = process.argv.includes('--styles-only')

const TEXT_KINDS = {
  0: 'model',
  1: 'imageParams',
  2: 'timeLocation',
  3: 'time',
  4: 'location',
  5: 'greeting',
  6: 'custom',
  7: 'filter'
}

const NAME_MAP = [
  ['video_hassel_text_style', '哈苏文字 · 视频'],
  ['hassel_text_style', '哈苏文字'],
  ['hassel', '哈苏'],
  ['lumo_text_style', 'LUMO 文字'],
  ['lumo', 'LUMO'],
  ['personalize_masterSign', '大师签名'],
  ['personalize_retroCamera', '复古相机'],
  ['personalize_brand', '品牌'],
  ['personalize_film_realme', 'realme 胶片'],
  ['personalize_film', '胶片'],
  ['realme_brand_imprint_inspirationPhoto', 'realme 品牌印记'],
  ['video_text_style', '文字 · 视频'],
  ['text_style', '文字'],
  ['restrict_local_songkran', '宋干节']
]

function num(v, fallback = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

function nameOf(styleId) {
  for (const [key, label] of NAME_MAP) {
    if (styleId.startsWith(key)) {
      if (styleId.includes('master_mode')) return `${label} · 大师模式`
      if (styleId.includes('xpan')) return `${label} · XPAN`
      const m = /(\d+)$/.exec(styleId)
      return m ? `${label} · ${m[1]}` : label
    }
  }
  return styleId
}

function fontKey(paint) {
  const s = String(paint?.font ?? '') + ' ' + String(paint?.fontName ?? '')
  if (/avenir/i.test(s)) return 'avenir'
  if (/butler|bulter/i.test(s)) return 'butler'
  if (/fz/i.test(s)) return 'fz'
  return 'sys'
}

function firstColor(paint) {
  const c = paint?.colors?.[0]
  return typeof c === 'string' ? c : '#000000'
}

function shadowOf(paint) {
  if (!paint?.withShadow) return undefined
  const v = Array.isArray(paint.shadowValues) ? paint.shadowValues : [1, 0, 0.33]
  return { color: paint.shadowColor ?? 'rgba(0,0,0,0.4)', blur: Math.max(0, num(v[2], 0)) * 10, x: num(v[0], 0), y: num(v[1], 0) }
}

function scaleOf(v) {
  if (v === 3) return 'contain'
  if (v === 6) return 'cover'
  return 'fill'
}

function partToken(part) {
  const map = {
    ISOValue: { kind: 'template', value: '@exif=@{iso}' },
    ISOTitle: { kind: 'literal', value: 'ISO' },
    FValue: { kind: 'template', value: '@exif=@{aperture}' },
    FTitle: { kind: 'literal', value: 'F' },
    ApertureValue: { kind: 'template', value: '@exif=@{aperture}' },
    ApertureTitle: { kind: 'literal', value: 'f/' },
    MMValue: { kind: 'template', value: '@exif=@{focal}' },
    MMTitle: { kind: 'literal', value: 'mm' },
    SValue: { kind: 'template', value: '@exif=@{speed}' },
    STitle: { kind: 'literal', value: 's' },
    ShutterTitle: { kind: 'skip' },
    FLTitle: { kind: 'skip' }
  }
  return map[part] ?? null
}

function marginsOf(pos = {}) {
  const out = {}
  for (const key of ['left', 'top', 'right', 'bottom']) {
    const v = pos[`${key}Margin`]
    if (v != null && Number.isFinite(Number(v)) && Number(v) !== 0) out[key] = Number(v)
  }
  return out
}

function registerAsset(asset, rawFile, assets, dimensions) {
  const ext = path.extname(rawFile) || '.webp'
  const dest = asset + ext
  const base = path.basename(rawFile)
  const src = path.join(ROOT, 'bitmaps', base)
  if (fs.existsSync(src)) {
    fs.mkdirSync(APP_ASSET_DIR, { recursive: true })
    fs.copyFileSync(src, path.join(APP_ASSET_DIR, dest))
    const dim = dimensions.get(base)
    assets[asset] = { file: dest, width: dim?.width ?? 0, height: dim?.height ?? 0 }
  }
}

// `bitmapSourceType: 4` (a `colorCard` block) is a *dynamically composed* colour card: the APK
// ships it as a 240x240 design placeholder whose artwork only occupies a small band in the
// middle, on a solid white canvas. Contain-fitting the padded placeholder into the declared box
// (e.g. 62x12) shrinks the real artwork to ~5x1 layout units, i.e. an invisible dash. The
// placeholder's content box matches the declared box aspect 1:1 (explore 120x24 -> 5.00 vs
// 62x12 -> 5.17; perspective 96x48 -> 2.00 vs 42x21 -> 2.00), so the content is what is meant
// to fill the box: the copy that ships is cropped to its non-white content.
const COLOR_CARD_ASSETS = new Map()

function contentBox(data, info) {
  let x1 = Infinity
  let y1 = Infinity
  let x2 = -1
  let y2 = -1
  for (let y = 0; y < info.height; y++) {
    for (let x = 0; x < info.width; x++) {
      const i = (y * info.width + x) * 4
      const white = data[i] > 250 && data[i + 1] > 250 && data[i + 2] > 250
      if (data[i + 3] > 20 && !white) {
        if (x < x1) x1 = x
        if (x > x2) x2 = x
        if (y < y1) y1 = y
        if (y > y2) y2 = y
      }
    }
  }
  if (!Number.isFinite(x1) || x2 < 0) return null
  return { left: x1, top: y1, width: x2 - x1 + 1, height: y2 - y1 + 1 }
}

async function trimColorCardAssets() {
  const trimmed = new Map()
  for (const [asset, file] of COLOR_CARD_ASSETS) {
    const full = path.join(APP_ASSET_DIR, file)
    if (!fs.existsSync(full)) continue
    const { data, info } = await sharp(full).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
    const box = contentBox(data, info)
    if (!box) continue
    // already cropped to its content (idempotent on re-runs)
    if (box.left === 0 && box.top === 0 && box.width >= info.width && box.height >= info.height) continue
    const tmp = `${full}.trim.tmp`
    await sharp(full).extract(box).webp({ quality: 92 }).toFile(tmp)
    fs.rmSync(full, { force: true })
    fs.renameSync(tmp, full)
    const meta = await sharp(full).metadata()
    trimmed.set(asset, { width: meta.width, height: meta.height })
  }
  return trimmed
}

function convertNode(n, assets, bitmapFiles, dimensions) {
  if (!n || n.visible === false) return null
  const content = n.content ?? n
  const type = content.type ?? n.type
  const pos = n.position ?? content.position ?? {}
  const gravity = pos.layoutGravity
  const margins = marginsOf(pos)

  if (type === 'space') return { kind: 'space', weight: 1 }
  if (type === 'text') {
    const source = content.textSource ?? n.textSource
    const part = content.textSourcePart ?? n.textSourcePart
    const token = source === 1 && part ? partToken(part) : TEXT_KINDS[source] ? { kind: TEXT_KINDS[source] } : null
    if (!token || token.kind === 'skip') return null
    const paint = n.paint ?? content.paint ?? {}
    return {
      kind: 'text',
      token,
      font: fontKey(paint),
      size: num(paint.textSize, 8),
      weight: num(paint.fontWeight, 400),
      color: firstColor(paint),
      alpha: num(paint.alpha, 1),
      tracking: num(paint.letterSpacing, 0),
      lineHeight: num(paint.lineHeight, 0) || undefined,
      align: 'left',
      gravity,
      margins,
      optional: source === -1,
      shadow: shadowOf(paint)
    }
  }
  if (type === 'image') {
    const asset = content.bitmap ?? content.bitmapResName
    if (!asset) return null
    const file = bitmapFiles.get(asset)
    if (file) registerAsset(asset, file, assets, dimensions)
    if (content.colorCard || content.bitmapSourceType === 4) {
      COLOR_CARD_ASSETS.set(asset, asset + (path.extname(file ?? '') || '.webp'))
    }
    const width = Math.max(1, num(content.width, 1))
    const height = Math.max(1, num(content.height, 1))
    let scale = scaleOf(content.scaleType)
    const dim = file ? dimensions.get(path.basename(file)) : null
    if (scale === 'fill' && dim?.width && dim?.height) {
      const assetAspect = dim.width / dim.height
      const boxAspect = width / height
      if (Math.abs(assetAspect - boxAspect) / assetAspect > 0.02) scale = 'contain'
    }
    return {
      kind: 'image',
      asset,
      width,
      height,
      scale,
      gravity,
      margins,
      alpha: 1
    }
  }
  if (type === 'shape') {
    const shape = content.shape
    const paint = n.paint ?? content.paint ?? {}
    if (shape === 'circle') {
      const d = Math.max(1, num(content.diameter, num(content.width, 6)))
      return { kind: 'shape', shape: 'circle', width: d, height: d, color: firstColor(paint), gravity, margins }
    }
    return {
      kind: 'shape',
      shape: 'rect',
      width: Math.max(0.5, num(content.width, 1)),
      height: Math.max(0.5, num(content.height, 1)),
      color: firstColor(paint),
      gravity,
      margins
    }
  }
  if (type === 'elements') {
    const raw = content.elements ?? n.elements ?? []
    const children = raw.map((c) => convertNode(c, assets, bitmapFiles, dimensions)).filter(Boolean)
    if (!children.length) return null
    return {
      kind: 'group',
      orientation: content.orientation === 1 ? 'column' : 'row',
      width: num(content.width, 0) > 0 ? num(content.width) : undefined,
      height: num(content.height, 0) > 0 ? num(content.height) : undefined,
      gravity,
      margins,
      children
    }
  }
  return null
}

function convertStyle(styleId, j, bitmapFiles, dimensions) {
  const size = j.size ?? {}
  const margins = {
    left: num(size.leftMargin),
    top: num(size.topMargin),
    right: num(size.rightMargin),
    bottom: num(size.bottomMargin)
  }
  const assets = {}
  const bands = []
  for (const band of j.bitmaps ?? []) {
    if (band.visible === false) continue
    const direction = band.direction
    const content = band.content ?? band
    const pos = band.position ?? {}
    const children = (content.elements ?? band.elements ?? []).map((n) => convertNode(n, assets, bitmapFiles, dimensions)).filter(Boolean)
    while (children[0]?.kind === 'space') children.shift()
    while (children[children.length - 1]?.kind === 'space') children.pop()
    if (!children.length) continue
    let dir
    let thickness
    if (direction === 1) {
      dir = 'top'
      thickness = num(content.height, 0)
      if (!(thickness > 0)) thickness = margins.top
    } else if (direction === 3) {
      dir = 'bottom'
      thickness = num(content.height, 0)
      if (!(thickness > 0)) thickness = margins.bottom
    } else if (direction === 2) {
      dir = margins.right >= margins.left ? 'right' : 'left'
      thickness = num(content.width, 0)
      if (!(thickness > 0)) thickness = dir === 'right' ? margins.right : margins.left
    } else if (direction === 4) {
      dir = 'overlay'
      thickness = num(content.height, 0)
      if (!(thickness > 0)) thickness = num(content.width, 54)
    } else {
      continue
    }
    bands.push({
      direction: dir,
      thickness: Math.max(0, thickness),
      offset: { x: num(pos.borderX), y: num(pos.borderY) },
      gravity: pos.gravity,
      children
    })
  }
  if (!bands.length) return null
  const hasSide = margins.left > 0 || margins.right > 0
  const hasVertical = margins.top > 0 || margins.bottom > 0
  const placement = hasSide ? 'with' : hasVertical ? 'out' : 'in'
  return {
    id: `oppo-gallery:${styleId}`,
    vendor: 'oppo',
    family: 'OPPO 相册',
    name: nameOf(styleId),
    nameEn: styleId,
    placement,
    referenceWidth: 360,
    margins,
    background: j.background?.color ?? '#00000000',
    bands,
    assets,
    audit: {
      status: 'approx',
      notes: 'Auto-converted from OPPO Gallery JSON; pending visual audit.'
    }
  }
}

function collectBitmapFiles() {
  const map = JSON.parse(fs.readFileSync(path.join(ROOT, 'bitmaps/_map.json'), 'utf8').replace(/^﻿/, ''))
  const out = new Map()
  for (const [name, item] of Object.entries(map)) {
    if (item && item.present !== false && item.file) out.set(name, path.basename(item.file))
  }
  return out
}

async function main() {
  const bitmapFiles = collectBitmapFiles()
  const dimensions = new Map()
  for (const file of new Set(bitmapFiles.values())) {
    const meta = await sharp(path.join(ROOT, 'bitmaps', file)).metadata().catch(() => null)
    if (meta?.width && meta?.height) dimensions.set(file, { width: meta.width, height: meta.height })
  }
  const styles = []
  for (const file of fs.readdirSync(STYLE_DIR).filter((f) => f.endsWith('.json')).sort()) {
    const styleId = file.replace(/\.json$/, '')
    const raw = fs.readFileSync(path.join(STYLE_DIR, file))
    const j = JSON.parse(raw.toString('utf8').replace(/^﻿/, ''))
    const style = convertStyle(styleId, j, bitmapFiles, dimensions)
    if (style) styles.push(style)
  }
  for (const style of styles) {
    if (style.id === 'oppo-gallery:restrict_local_songkran_holiday_2025_style_1') {
      const walk = (n) => {
        if (!n || typeof n !== 'object') return
        if (n.kind === 'text') n.color = '#3B2C12'
        for (const c of n.children ?? []) walk(c)
      }
      for (const band of style.bands) for (const child of band.children) walk(child)
    }
  }
  const trimmed = await trimColorCardAssets()
  for (const style of styles) {
    for (const [key, dim] of trimmed) {
      if (style.assets?.[key]) style.assets[key] = { ...style.assets[key], width: dim.width, height: dim.height }
    }
  }
  if (trimmed.size) console.log('colour-card assets cropped to content:', [...trimmed.keys()].join(', '))
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(
    path.join(OUT_DIR, 'oppoGallery.ts'),
    `import type { StockStyle } from '../ir'\n\nexport const OPPO_GALLERY_STYLES = ${JSON.stringify(styles, null, 2)} as StockStyle[]\n`
  )
  if (STYLES_ONLY) {
    console.log('styles', styles.length)
    console.log('variants skipped (--styles-only)')
  } else {
    const variants = styles.map((s) => ({
      id: `stock-${s.id.replace('oppo-gallery:', 'oppo-')}`,
      name: s.name,
      en: s.nameEn,
      category: '系统相机',
      base: 'systemCamera',
      params: { systemStyle: s.id },
      text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
      badge: '近似',
      vendor: 'oppo',
      family: s.family,
      fidelity: s.audit.status
    }))
    fs.writeFileSync(
      path.join(OUT_DIR, 'oppoVariants.ts'),
      `import type { TemplateVariant } from '../../../types'\n\nexport const OPPO_GALLERY_VARIANTS = ${JSON.stringify(variants, null, 2)} as TemplateVariant[]\n`
    )
    console.log('styles', styles.length, 'variants', variants.length)
  }
  console.log('missing assets:')
  const allAssets = new Set(styles.flatMap((s) => Object.keys(s.assets)))
  for (const a of allAssets) if (!bitmapFiles.has(a)) console.log(' -', a)
  console.log('asset count', allAssets.size)
}

main().catch((e) => { console.error(e); process.exit(1) })
