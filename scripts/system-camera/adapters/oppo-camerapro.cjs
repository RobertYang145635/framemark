// Convert OPPO CameraPro watermark JSONs into the shared stock IR.
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const ROOT = 'D:/border&watermark/_research/extracted/oppo-watermarks/assets/watermark'
const OUT_DIR = 'D:/border&watermark/framemark/src/renderer/src/core/system/generated'
const APP_ASSET_DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/system/oppo/camerapro'

const STYLES = [
  { file: 'gr_style_1.json', name: '理光 GR · 上框', en: 'RICOH GR · TOP', family: 'OPPO 相机' },
  { file: 'gr_style_2.json', name: '理光 GR · 页脚', en: 'RICOH GR · FOOTER', family: 'OPPO 相机' },
  { file: 'gr_style_3.json', name: '理光 GR · 下框', en: 'RICOH GR · BOTTOM', family: 'OPPO 相机' },
  { file: 'gr_style_4.json', name: '理光 GR · 叠加', en: 'RICOH GR · OVERLAY', family: 'OPPO 相机' },
  { file: 'gr_style_5.json', name: '理光 GR · 参数边栏', en: 'RICOH GR · SIDEBAR', family: 'OPPO 相机' },
  { file: 'tone_frame_style_1.json', name: '色调边框 · 上', en: 'TONE FRAME · TOP', family: 'OPPO 相机' },
  { file: 'tone_gallery_frame_style_1.json', name: '影调画框 · 页脚', en: 'TONE FRAME · FOOTER', family: 'OPPO 相机' },
  { file: 'leica_live_moment_style_1.json', name: '徕卡 · 动态', en: 'LEICA LIVE MOMENT', family: 'OPPO 相机' },
  { file: 'liquid_glass_style_1.json', name: '液态玻璃', en: 'LIQUID GLASS', family: 'OPPO 相机' }
]

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

const FILTER_TITLES = {
  filterTitle: 'Style',
  saturationTitle: 'Saturation',
  hueTitle: 'Hue',
  lightAndShadeTitle: 'High / Low',
  contrastTitle: 'Contrast',
  contrastLightTitle: 'Highlight',
  contrastShadowTitle: 'Shadow',
  sharpnessTitle: 'Sharpness',
  clarityTitle: 'Clarity',
  particleAmountTitle: 'Grain Intensity',
  particleSizeTitle: 'Grain Size',
  toneTitle: 'Tone'
}

function num(v, fallback = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

function safeName(s) {
  return String(s).replace(/[^A-Za-z0-9_-]+/g, '_')
}

function fontKey(paint) {
  const s = `${paint?.fontName ?? ''} ${paint?.font ?? ''}`
  if (/FZLTZCHK/i.test(s)) return 'sys'
  if (/misans/i.test(s)) return 'misans'
  if (/gilroy|avenir/i.test(s)) return 'avenir'
  return 'sys'
}

function scaleOf(v) {
  if (v === 2 || v === 3) return 'contain'
  if (v === 6) return 'cover'
  return 'fill'
}

function marginsOf(pos = {}) {
  const out = {}
  for (const key of ['left', 'top', 'right', 'bottom']) {
    const v = pos[`${key}Margin`]
    if (v != null && Number.isFinite(Number(v)) && Number(v) !== 0) out[key] = Number(v)
  }
  return out
}

function shadowOf(paint) {
  if (!paint?.withShadow) return undefined
  const v = Array.isArray(paint.shadowValues) ? paint.shadowValues : [1, 0, 0.33]
  return {
    color: paint.shadowColor ?? 'rgba(0,0,0,0.4)',
    blur: Math.max(0, num(v[2], 0)) * 10,
    x: num(v[0], 0),
    y: num(v[1], 0)
  }
}

function buildAssetIndex() {
  const index = new Map()
  const visit = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        visit(full)
        continue
      }
      if (!/\.(webp|png)$/i.test(entry.name)) continue
      const key = path.basename(entry.name, path.extname(entry.name))
      if (!index.has(key)) index.set(key, full)
    }
  }
  visit(ROOT)
  return index
}

const ASSET_INDEX = buildAssetIndex()

function tokenOf(content) {
  const src = Number(content.textSource ?? -1)
  if (src === -1) {
    const text = String(content.content ?? content.text ?? '')
    return text ? { kind: 'literal', value: text } : null
  }
  if (src === 8) {
    const part = String(content.textSourcePart ?? '')
    if (/Value$/.test(part)) return { kind: 'literal', value: '—' }
    const label = FILTER_TITLES[part]
    return label ? { kind: 'literal', value: label } : null
  }
  if (src === 1 && content.textSourcePart) {
    const part = String(content.textSourcePart)
    if (part === 'FValue') return { kind: 'template', value: '@exif=F@{aperture}' }
    if (part === 'SValue') return { kind: 'template', value: '@exif=@{speed}s' }
    if (part === 'ISOTitle') return { kind: 'literal', value: 'ISO' }
    if (part === 'ISOValue') return { kind: 'template', value: '@exif=@{iso}' }
    return null
  }
  const kind = TEXT_KINDS[src]
  return kind ? { kind } : null
}

function convertText(node, ctx) {
  const content = node.content ?? node
  const token = tokenOf(content)
  if (!token) {
    // Tripwire for the exact mechanism that almost got #59 filed as "the whole element class is
    // dropped": a text node that maps to no token must never vanish without a trace.
    // `textSource` here is a *CameraPro*-local enum -- the cracked Gallery table
    // (_research/textsource-enum/enum-map.json) only spans -1 and 0..7 (NONE..FILTER), while this
    // corpus uses 0 / 1 / 3 / 8 / -1. 8 is the GR filter-parameter family (24 nodes, all of them
    // in gr_style_5: $.bitmaps[0].content.elements[*].content.textSourcePart = filterTitle ..
    // particleSizeValue) and is NOT the gallery's 7 = TEXT_SOURCE_FILTER, so nothing here proves
    // what the next value would mean -- an unhandled bump would silently delete rows.
    // ts -1 with an empty literal is the documented NONE placeholder (liquid_glass_style_1,
    // polaroid_pld_style_1) and stays quiet, so today's IR is byte-identical.
    const src = Number(content.textSource ?? -1)
    if (src !== -1) {
      ctx.unsupported.add(`dropped text element: textSource=${src} part=${content.textSourcePart ?? '-'}`)
    }
    return null
  }
  const paint = node.paint ?? content.paint ?? {}
  const optional = token.kind === 'model' || token.kind === 'imageParams' || token.kind === 'time' || token.kind === 'timeLocation'
  const width = num(content.width, 0)
  return {
    kind: 'text',
    token,
    font: fontKey(paint),
    size: num(paint.textSize, 8),
    weight: num(paint.fontWeight, 400),
    color: Array.isArray(paint.colors) && paint.colors[0] ? paint.colors[0] : '#000000',
    alpha: num(paint.alpha, 1),
    tracking: num(paint.letterSpacing, 0),
    lineHeight: num(paint.lineHeight, 0) || undefined,
    ...(width > 0 ? { width } : {}),
    align: 'left',
    gravity: node.position?.layoutGravity,
    margins: marginsOf(node.position),
    optional,
    shadow: shadowOf(paint)
  }
}

function convertImage(node, ctx) {
  const content = node.content ?? node
  const bitmap = String(content.bitmap ?? content.bitmapResName ?? '')
  if (!bitmap) return null
  const src = ASSET_INDEX.get(bitmap)
  if (!src) {
    ctx.unsupported.add(`missing bitmap ${bitmap}`)
    return null
  }
  fs.mkdirSync(APP_ASSET_DIR, { recursive: true })
  const file = path.basename(src)
  fs.copyFileSync(src, path.join(APP_ASSET_DIR, file))
  const asset = `${ctx.styleId}-${safeName(bitmap)}`
  const width = num(content.width, 0) > 0 ? num(content.width) : 1
  const height = num(content.height, 0) > 0 ? num(content.height) : 1
  ctx.assets[asset] = { file: `oppo/camerapro/${file}`, width, height }
  return {
    kind: 'image',
    asset,
    width,
    height,
    scale: scaleOf(content.scaleType),
    gravity: node.position?.layoutGravity,
    margins: marginsOf(node.position),
    alpha: num(content.alpha, 1)
  }
}

function convertNode(node, ctx) {
  if (!node || node.visible === false) return null
  const content = node.content ?? node
  const type = content.type ?? node.type
  if (type === 'space') return { kind: 'space', weight: 1 }
  if (type === 'text') return convertText(node, ctx)
  if (type === 'image') return convertImage(node, ctx)
  if (type === 'elements') {
    const children = (content.elements ?? node.elements ?? []).map((c) => convertNode(c, ctx)).filter(Boolean)
    if (!children.length) return null
    return {
      kind: 'group',
      orientation: content.orientation === 1 ? 'column' : 'row',
      width: num(content.width, 0) > 0 ? num(content.width) : undefined,
      height: num(content.height, 0) > 0 ? num(content.height) : undefined,
      gravity: node.position?.layoutGravity,
      margins: marginsOf(node.position),
      children
    }
  }
  return null
}

function rootAdjust(children) {
  for (const c of children) {
    if (c.kind !== 'group') continue
    c.gravity = c.gravity ?? 'center'
    if (c.width == null) c.matchWidth = true
  }
}

function bandThickness(content, margin) {
  if (margin > 0) return margin
  const h = num(content.height, 0)
  return h > 0 ? h : undefined
}

async function convertStyle(spec) {
  const filePath = path.join(ROOT, spec.file)
  const j = JSON.parse(fs.readFileSync(filePath, 'utf8').replace(/^\uFEFF/, ''))
  const size = j.size ?? {}
  const styleId = path.basename(spec.file, '.json')
  const ctx = { styleId, assets: {}, unsupported: new Set() }
  const bands = []
  let placement = 'out'

  for (const band of j.bitmaps ?? []) {
    const content = band.content ?? band
    const raw = content.elements ?? band.elements ?? []
    const children = raw.map((c) => convertNode(c, ctx)).filter(Boolean)
    if (!children.length) continue
    const direction = Number(band.direction)
    const pos = band.position ?? {}
    if (direction === 1) {
      rootAdjust(children)
      const thickness = bandThickness(content, num(size.topMargin))
      if (!thickness) continue
      bands.push({ direction: 'top', thickness, children })
    } else if (direction === 3) {
      rootAdjust(children)
      const thickness = bandThickness(content, num(size.bottomMargin))
      if (!thickness) continue
      bands.push({ direction: 'bottom', thickness, children })
    } else if (direction === 2) {
      rootAdjust(children)
      const right = num(size.rightMargin)
      const left = num(size.leftMargin)
      const dir = right >= left ? 'right' : 'left'
      const thickness = bandThickness(content, dir === 'right' ? right : left)
      if (!thickness) continue
      bands.push({ direction: dir, thickness, children })
      placement = 'with'
    } else if (direction === 4) {
      placement = 'overlay'
      rootAdjust(children)
      const width = num(content.width, 0) > 0 ? num(content.width) : undefined
      const height = bandThickness(content, 0)
      if (!height) continue
      bands.push({
        direction: 'overlay',
        thickness: height,
        ...(width ? { width } : {}),
        gravity: 'centerBottom',
        offset: { x: 0, y: num(pos.borderY, 0) },
        children
      })
    }
  }

  if (!bands.length) return null

  const effect = j._opcproEffect
  if (effect?.type === 'liquidGlass') {
    const overlay = bands.find((b) => b.direction === 'overlay')
    if (overlay) {
      const alpha = Math.max(0.12, 0.12 + num(effect.tintAlpha, 0) * 4)
      overlay.background = `rgba(255,255,255,${alpha.toFixed(3)})`
      overlay.radius = num(effect.cornerRadius, 0)
    }
    ctx.unsupported.add('liquid glass blur/refraction approximated by translucent rounded fill')
  }
  if (/FZLTZCHK/i.test(JSON.stringify(j))) ctx.unsupported.add('FZLTZCHK substituted with SysSans')
  if (/Gilroy/i.test(JSON.stringify(j))) ctx.unsupported.add('Gilroy substituted with Avenir')
  if (/"textSource"\s*:\s*8/.test(JSON.stringify(j))) {
    ctx.unsupported.add('GR filter values are not stored in EXIF; rendered as dash placeholders')
  }

  const overlay = bands.some((b) => b.direction === 'overlay')
  const margins = overlay
    ? { left: 0, top: 0, right: 0, bottom: 0 }
    : {
        left: num(size.leftMargin),
        top: num(size.topMargin),
        right: num(size.rightMargin),
        bottom: num(size.bottomMargin)
      }
  const background = overlay ? '#00000000' : (j.background?.color ?? '#FFFFFF')
  const notes = [...ctx.unsupported]
  return {
    id: `oppo-camerapro:${styleId}`,
    vendor: 'oppo',
    family: spec.family,
    name: spec.name,
    nameEn: spec.en,
    placement,
    // #65, same source as the gallery adapter: the vendor's `baseImageSize` IS the reference
    // width and it is the PHOTO width (proved on the vendor's own photo pair in
    // _research/scale-audit2/semantics-by-photo.cjs). Read the field instead of assuming.
    // Scanned corpus (_research/camerapro-adapter/scan.json): all 10 shipped CameraPro JSONs
    // declare baseImageSize = 360 ($.baseImageSize), so on today's material this is a byte-level
    // no-op -- the point is that a future asset pack shipping another photo width
    // (the gallery corpus already has one: hassel_style_xpan_1 = 291) cannot silently
    // re-introduce the 1.237x scale error this line had.
    referenceWidth: Number(j.baseImageSize) || 360,
    margins,
    background,
    bands,
    assets: ctx.assets,
    audit: {
      status: 'approx',
      ...(notes.length ? { unsupported: notes } : {}),
      notes: 'Auto-converted from OPPO CameraPro config; pending visual audit.'
    }
  }
}

async function main() {
  const styles = []
  const blocked = ['polaroid_pld_style_1 (ratio-dependent full-canvas frames handled by polaroidFrame)']
  for (const spec of STYLES) {
    const style = await convertStyle(spec)
    if (style) styles.push(style)
  }
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(
    path.join(OUT_DIR, 'oppoCameraPro.ts'),
    `import type { StockStyle } from '../ir'\n\nexport const OPPO_CAMERAPRO_STYLES = ${JSON.stringify(styles, null, 2)} as StockStyle[]\n`
  )
  const variants = styles.map((s) => ({
    id: `stock-${s.id.replace(/[:]/g, '-')}`,
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
    path.join(OUT_DIR, 'oppoCameraProVariants.ts'),
    `import type { TemplateVariant } from '../../../types'\n\nexport const OPPO_CAMERAPRO_VARIANTS = ${JSON.stringify(variants, null, 2)} as TemplateVariant[]\n`
  )
  console.log('styles', styles.length, 'blocked', blocked.length)
  console.log('BLOCKED\n' + blocked.join('\n'))
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
