import type { TemplateRenderEnv } from '../../types'
import { drawContain, drawPhoto, roundRectPath, setFont, setTracking } from '../draw'
import { brandSlotKey, resolveBrandSlot } from './assets'
import type { StockStyle } from './ir'
import type { BrandFree, PlacedItem, StockLayout, StockRect } from './layout'

function scaleRect(r: StockRect, k: number): StockRect {
  return { x: r.x * k, y: r.y * k, w: r.w * k, h: r.h * k }
}

/** `layout.ts` measures brand-slot slack in unsolved layout units — same scale as the box. */
function scaleFree(f: BrandFree | undefined, k: number): BrandFree | undefined {
  if (!f) return undefined
  return { left: f.left * k, right: f.right * k, top: f.top * k, bottom: f.bottom * k }
}

const tintCache = new Map<string, HTMLCanvasElement>()

function tinted(img: HTMLImageElement, color: string, key: string): CanvasImageSource {
  const cacheKey = `${key}|${color}`
  const cached = tintCache.get(cacheKey)
  if (cached) return cached
  const w = img.naturalWidth || img.width
  const h = img.naturalHeight || img.height
  if (!w || !h) return img
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')!
  ctx.drawImage(img, 0, 0)
  ctx.globalCompositeOperation = 'source-in'
  ctx.fillStyle = color
  ctx.fillRect(0, 0, w, h)
  tintCache.set(cacheKey, canvas)
  return canvas
}

/**
 * Vendor `foreground` arrays sometimes carry bare hex (`E6A54F`), which canvas
 * silently rejects — the fill then keeps whatever colour was set last. Only used
 * on the brand-slot tint path so no existing render changes behind this.
 */
function slotTintColour(value: string | undefined): string | undefined {
  if (!value) return undefined
  return /^[0-9a-fA-F]{6,8}$/.test(value) ? `#${value}` : value
}

function cssColor(value: string | undefined, alpha = 1): string {
  const color = value ?? '#000000'
  const m = /^#([0-9a-fA-F]{8})$/.exec(color)
  if (m) {
    const a = parseInt(m[1].slice(0, 2), 16) / 255
    return `rgba(${parseInt(m[1].slice(2, 4), 16)},${parseInt(m[1].slice(4, 6), 16)},${parseInt(m[1].slice(6, 8), 16)},${a * alpha})`
  }
  if (alpha < 1 && /^#[0-9a-fA-F]{6}$/.test(color)) {
    return `rgba(${parseInt(color.slice(1, 3), 16)},${parseInt(color.slice(3, 5), 16)},${parseInt(color.slice(5, 7), 16)},${alpha})`
  }
  return color
}

const THEME_FALLBACK: Record<string, string> = {
  gradient_color_red: '#3b1516',
  gradient_color_blue_dark: '#101c2b',
  gradient_color_blue_light: '#dcebf1',
  gradient_color_yellow: '#d9c48a',
  light_blur: '#e9e9e9',
  dark_blur: '#151515'
}

function parseSolid(value: string | undefined): { r: number; g: number; b: number; a: number } | null {
  const s = String(value ?? '').trim().toLowerCase()
  if (s === 'white') return { r: 255, g: 255, b: 255, a: 1 }
  if (s === 'black') return { r: 0, g: 0, b: 0, a: 1 }
  const m = /^#([0-9a-f]{6}|[0-9a-f]{8})$/.exec(s)
  if (!m) return null
  const hex = m[1]
  const alpha = hex.length === 8 ? parseInt(hex.slice(0, 2), 16) / 255 : 1
  const rgb = hex.length === 8 ? hex.slice(2) : hex
  return { r: parseInt(rgb.slice(0, 2), 16), g: parseInt(rgb.slice(2, 4), 16), b: parseInt(rgb.slice(4, 6), 16), a: alpha }
}

function lightSurface(value: string | undefined): boolean {
  const c = parseSolid(value)
  if (!c || c.a < 0.4) return false
  return (c.r * 0.299 + c.g * 0.587 + c.b * 0.114) / 255 > 0.7
}

function contrastOnLight(value: string | undefined): string {
  const c = parseSolid(value)
  if (!c) return value ?? '#000000'
  const lum = (c.r * 0.299 + c.g * 0.587 + c.b * 0.114) / 255
  if (lum <= 0.55) return value ?? '#000000'
  const mix = 0.62
  const r = Math.round(c.r * (1 - mix))
  const g = Math.round(c.g * (1 - mix))
  const b = Math.round(c.b * (1 - mix))
  const a = c.a < 1 ? Math.round(c.a * 255).toString(16).padStart(2, '0').toUpperCase() : ''
  return `#${a}${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`.toUpperCase()
}

function themeBackground(id: string | undefined, raw: string | undefined): string {
  const s = String(raw ?? '')
  const solid = /^#(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})/.exec(s)
  if (solid) {
    const alpha = s.length >= 9 ? s.slice(1, 3) : 'FF'
    if (alpha !== '00') return cssColor(s.slice(0, alpha === 'FF' && s.length >= 9 ? 9 : 7))
  }
  return THEME_FALLBACK[id ?? ''] ?? '#1c1d1f'
}

/** Intrinsic pixel size of a decoded image or the tinted offscreen canvas. */
function imageSize(img: CanvasImageSource): { w: number; h: number } {
  const el = img as HTMLImageElement & HTMLCanvasElement & ImageBitmap
  const w = el.naturalWidth ?? (el as HTMLCanvasElement).width ?? 0
  const h = el.naturalHeight ?? (el as HTMLCanvasElement).height ?? 0
  return { w, h }
}

/**
 * Same decomposition `layout.ts/gravityParts` uses for the node box, kept local
 * so the drawn rect can hug the edge the vendor anchored the mark to
 * (left/centre/right gravity must not drift when the width changes).
 */
function gravityAnchor(gravity: string | undefined): { h: 'left' | 'center' | 'right'; v: 'top' | 'center' | 'bottom' } {
  const s = gravity ?? 'leftTop'
  const h = /right/i.test(s) ? 'right' : /hor|center/i.test(s) && !/left/i.test(s) ? 'center' : 'left'
  const v = /bottom/i.test(s) ? 'bottom' : /verticalCenter|center/i.test(s) && !/top/i.test(s) ? 'center' : 'top'
  return { h, v }
}

/**
 * Breathing room a grown wordmark keeps from the nearest sibling. There is no
 * slot-specific safe margin in the project, so this is the same 2% the layout
 * solver insets bands by (`fitBandItems`), floored at 2px so the small vendor
 * slots (h ≈ 22) still keep a visible gap instead of touching glyphs.
 */
function slotSafeGap(box: StockRect): number {
  return Math.max(2, box.h * 0.02)
}

/**
 * Aspect-aware fitting for brand slots, under one hard rule: **grow to fit,
 * never overlap**.
 *
 * `contain` in a 4.206 box leaves ~35% letterbox for a 6.52 wordmark, so a
 * swapped brand renders visibly smaller than the vendor's own mark — the node
 * **height** is the design anchor and the desired drawn width comes from the
 * asset's intrinsic aspect (drawn width = h × aspect); when the asset is
 * *taller* than the box (asset aspect < box aspect) the inverse holds and the
 * width is the anchor. But the layout solver positions siblings off the
 * DECLARED box, so a mark that simply grew +55% painted straight over the model
 * text (`DC-S1`) in 37/37 slot styles.
 *
 * `free` (from `layout.ts/annotateBrandFree`) is the empty room the band really
 * leaves on the side the gravity anchor grows into. The mark takes all of it
 * when the desired width fits; when it does not, the whole rect scales down
 * proportionally (height follows width, aspect preserved exactly) so the mark
 * stops short of the sibling by `slotSafeGap()` (2% of the node height, the
 * same proportion the band inset uses, floored at 2px). Width is never clamped
 * on its own and the mark is never stretched; the gravity edge stays put.
 */
export function brandSlotRect(
  img: CanvasImageSource,
  box: StockRect,
  gravity: string | undefined,
  free?: BrandFree
): StockRect {
  const { w: iw, h: ih } = imageSize(img)
  if (!iw || !ih || !box.w || !box.h) return box
  const assetAspect = iw / ih
  const boxAspect = box.w / box.h
  const wantW = assetAspect >= boxAspect ? box.h * assetAspect : box.w
  const wantH = assetAspect >= boxAspect ? box.h : box.w / assetAspect
  const { h: ha, v: va } = gravityAnchor(gravity)
  let w = wantW
  let h = wantH
  if (free) {
    const gap = slotSafeGap(box)
    const slackX = ha === 'left' ? free.right : ha === 'right' ? free.left : Math.min(free.left, free.right) * 2
    const slackY = va === 'top' ? free.bottom : va === 'bottom' ? free.top : Math.min(free.top, free.bottom) * 2
    // never below the declared box: the fit may only grow into real slack
    const maxW = box.w + Math.max(0, slackX - gap)
    const maxH = box.h + Math.max(0, slackY - gap)
    const k = Math.min(1, maxW / wantW, maxH / wantH)
    w = wantW * k
    h = wantH * k
  }
  const x = ha === 'right' ? box.x + box.w - w : ha === 'center' ? box.x + (box.w - w) / 2 : box.x
  const y = va === 'bottom' ? box.y + box.h - h : va === 'center' ? box.y + (box.h - h) / 2 : box.y
  return { x, y, w, h }
}

function paintItem(
  env: TemplateRenderEnv,
  item: PlacedItem,
  k: number,
  foreground: string[],
  themeId: string | undefined,
  onLightSurface = false,
  brandDark = false
): void {
  const ctx = env.ctx
  let box = scaleRect(item.box, k)
  if (box.w <= 0 || box.h <= 0) return

  if (item.kind === 'group') {
    if (!item.color) return
    ctx.save()
    ctx.fillStyle = cssColor(item.color)
    ctx.fillRect(box.x, box.y, box.w, box.h)
    ctx.restore()
    return
  }

  if (item.kind === 'shape') {
    ctx.save()
    ctx.globalAlpha = item.node.alpha ?? 1
    ctx.fillStyle = cssColor(item.node.color)
    if (item.node.shape === 'circle') {
      ctx.beginPath()
      ctx.arc(box.x + box.w / 2, box.y + box.h / 2, Math.min(box.w, box.h) / 2, 0, Math.PI * 2)
      ctx.fill()
    } else {
      ctx.fillRect(box.x, box.y, box.w, box.h)
    }
    ctx.restore()
    return
  }

  if (item.kind === 'image') {
    const themedAsset = themeId ? item.node.themeAssets?.[themeId] : undefined
    const bakedKey = themedAsset ?? item.node.asset
    // A brand slot follows the photo, never the vendor folder: resolveBrandSlot()
    // returns the wordmark of the EXIF brand (or the brand picked in the
    // Inspector). No usable wordmark means the slot stays empty — drawing the
    // vendor's own mark is exactly the "xiaomi DC-S1" defect.
    const slot = item.node.isBrandLogo ? resolveBrandSlot(env, brandDark) : null
    if (item.node.isBrandLogo && !slot) return
    const raw = slot?.img ?? env.images[bakedKey]
    if (!raw) return
    // The vendor fills `${logo}` with a theme colour whenever the node declares a
    // colourIndex, so a substituted bitmap is tinted under the same condition.
    const tint = slot
      ? slotTintColour(item.node.tint ?? (item.node.colorIndex != null ? foreground[item.node.colorIndex] : undefined))
      : item.node.tint ?? (!themedAsset && item.node.isBrandLogo ? foreground[item.node.colorIndex ?? 0] : undefined)
    const img = tint ? tinted(raw, cssColor(tint), slot ? brandSlotKey(slot.file) : bakedKey) : raw
    ctx.save()
    ctx.globalAlpha = item.node.alpha ?? 1
    if (slot) {
      // aspect-aware fit driven by the resolved wordmark's intrinsic aspect:
      // keeps the design height, grows the width into the band's real slack,
      // scales back before it would touch a sibling, anchors on the gravity edge.
      const rect = brandSlotRect(raw, box, item.node.gravity, scaleFree(item.brandFree, k))
      ctx.drawImage(img, rect.x, rect.y, rect.w, rect.h)
    } else if (item.node.scale === 'contain') {
      drawContain(ctx, img, box.x, box.y, box.w, box.h)
    } else if (item.node.scale === 'cover') {
      const iw = (img as HTMLImageElement).naturalWidth || (img as HTMLImageElement).width
      const ih = (img as HTMLImageElement).naturalHeight || (img as HTMLImageElement).height
      const s = Math.max(box.w / iw, box.h / ih)
      const sw = box.w / s
      const sh = box.h / s
      ctx.beginPath()
      ctx.rect(box.x, box.y, box.w, box.h)
      ctx.clip()
      ctx.drawImage(img, (iw - sw) / 2, (ih - sh) / 2, sw, sh, box.x, box.y, box.w, box.h)
    } else {
      ctx.drawImage(img, box.x, box.y, box.w, box.h)
    }
    ctx.restore()
    return
  }

  // text
  const node = item.node
  ctx.save()
  ctx.globalAlpha = item.alpha
  const themed = node.colorIndex != null ? foreground[node.colorIndex] : undefined
  const rawColor = themed ?? item.color
  ctx.fillStyle = cssColor(onLightSurface ? contrastOnLight(rawColor) : rawColor)
  ctx.textBaseline = 'middle'
  ctx.textAlign = item.align
  const pad = Math.max(3, Math.min(36, Math.round(env.W * 0.025)))
  let fit = 1
  if (box.w > env.W - pad * 2 || box.h > env.H - pad * 2) {
    fit = Math.min(
      1,
      (env.W - pad * 2) / Math.max(1, box.w),
      (env.H - pad * 2) / Math.max(1, box.h)
    )
  }
  const centerX = box.x + box.w / 2
  const rightX = box.x + box.w
  const centerY = box.y + box.h / 2
  box = { ...box, w: box.w * fit, h: box.h * fit }
  if (item.align === 'center') box.x = centerX - box.w / 2
  else if (item.align === 'right') box.x = rightX - box.w
  box.x = Math.max(pad, Math.min(box.x, env.W - pad - box.w))
  box.y = Math.max(pad, Math.min(box.y, env.H - pad - box.h))
  if (node.outline && node.outline.strokeWidth > 0) {
    const stroke = node.outline.strokeColorIndex != null
      ? foreground[node.outline.strokeColorIndex]
      : node.outline.strokeColor
    if (stroke) {
      const lw = node.outline.strokeWidth * k * fit
      const inset = lw / 2
      const radius = Math.min(node.outline.radius ?? 0, Math.min(box.w, box.h) / 2)
      ctx.strokeStyle = cssColor(stroke)
      ctx.lineWidth = lw
      ctx.beginPath()
      roundRectPath(ctx, box.x + inset, box.y + inset, box.w - lw, box.h - lw, Math.max(0, radius - inset))
      ctx.stroke()
    }
  }
  if (node.shadow) {
    ctx.shadowColor = node.shadow.color
    ctx.shadowBlur = node.shadow.blur * k * fit
    ctx.shadowOffsetX = node.shadow.x * k * fit
    ctx.shadowOffsetY = node.shadow.y * k * fit
  }
  setTracking(ctx, item.tracking * k * fit)
  setFont(ctx, item.fontSize * k * fit, item.family, node.weight)
  const x = item.align === 'center' ? box.x + box.w / 2 : item.align === 'right' ? box.x + box.w : box.x
  const lh = item.lineHeight * k * fit
  const top = box.y + (box.h - lh * item.lines.length) / 2
  item.lines.forEach((line, i) => {
    ctx.fillText(line, x, top + lh * (i + 0.5))
  })
  setTracking(ctx, 0)
  ctx.restore()
}

export function paintStockLayout(
  env: TemplateRenderEnv,
  style: StockStyle,
  layout: StockLayout,
  scale: number
): StockRect {
  const ctx = env.ctx
  const theme = style.themes?.find((t) => t.id === String(env.params.theme ?? style.defaultTheme))
  const fg = theme?.foreground ?? []
  // brand wordmarks come in a light and a dark cut; pick the one that reads on this card
  const brandDark = !lightSurface(theme?.background ?? style.background)
  if (!style.transparentBackground) {
    ctx.fillStyle = themeBackground(theme?.id, theme?.background ?? style.background)
    ctx.fillRect(0, 0, layout.W * scale, layout.H * scale)
  }

  const drawBand = (band: StockLayout['bands'][number]): void => {
    const r = scaleRect(band.box, scale)
    if (band.band.background) {
      ctx.save()
      ctx.fillStyle = cssColor(band.band.background)
      if (band.band.radius) {
        ctx.beginPath()
        roundRectPath(ctx, r.x, r.y, r.w, r.h, band.band.radius * scale)
        ctx.fill()
      } else {
        ctx.fillRect(r.x, r.y, r.w, r.h)
      }
      ctx.restore()
    }
    const onLightSurface = false
    for (const item of band.items) paintItem(env, item, scale, fg, theme?.id, onLightSurface, brandDark)
  }

  for (const band of layout.bands) if (band.band.drawFirst) drawBand(band)
  const photo = scaleRect(layout.photo, scale)
  drawPhoto(env, photo.x, photo.y, photo.w, photo.h, 0, 0)
  for (const band of layout.bands) if (!band.band.drawFirst) drawBand(band)
  return photo
}
