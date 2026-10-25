import type { TemplateRenderEnv } from '../../types'
import { drawContain, drawPhoto, roundRectPath, setFont, setTracking } from '../draw'
import { brandSlotKey, inkBoxOf, resolveBrandSlot } from './assets'
import type { InkBox } from './assets'
import type { StockImageNode, StockStyle } from './ir'
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
 * Absolute floor on the wordmark→sibling gap, so a slot can never paint
 * touching glyphs even when a style declares no margin at all. The real gap
 * comes from the vendor (`brandSlotGapPx`), not from here.
 */
const SLOT_GAP_FLOOR = 2

/**
 * Vendor slot→sibling margin used by both Xiaomi adapters when the vendor
 * config declares none (`adapters/xiaomi.cjs` writes `margins.right = 8`), and
 * the value the hand-written slot styles use. Only a fallback: the node's own
 * declared margin always wins.
 */
const SLOT_MARGIN_FALLBACK_UNITS = 8

/**
 * The gap the VENDOR leaves between this slot's visible mark and the next
 * element, in output pixels. Two vendor-authored components, no invented
 * constant:
 *   1. the IR node's own margin on the side the mark grows into — the row
 *      spacing the vendor config declared (`margins.right`, or `left` for a
 *      right-anchored slot), and
 *   2. the transparent padding the vendor's own wordmark bitmap carries on that
 *      side, which `contain` renders *inside* the box, so the vendor's mark
 *      visually ends short of its box edge.
 * Component 2 is what makes the pair read as spaced rather than merely
 * non-intersecting: for `classic 19` it is 8 + 1.7 = 9.7 layout units, while
 * the pre-fix code kept `max(2, h * 0.02)` = 2px — the same distance as the
 * intra-letter spacing inside "Panasonic", which is why the pair read as one
 * run.
 */
function brandSlotGapPx(
  node: StockImageNode,
  box: StockRect,
  k: number,
  vendorInk: InkBox | null
): number {
  const m = node.margins
  const preferRight = !/right/i.test(String(node.gravity ?? ''))
  const declared =
    (preferRight ? m?.right ?? m?.left : m?.left ?? m?.right) ?? SLOT_MARGIN_FALLBACK_UNITS
  let padSide = 0
  if (vendorInk && vendorInk.cw > 0) {
    const pad = preferRight ? vendorInk.cw - vendorInk.x - vendorInk.w : vendorInk.x
    padSide = (pad / vendorInk.cw) * box.w
  }
  return Math.max(SLOT_GAP_FLOOR, (declared + padSide) * k)
}

/** Ink padding fractions of a bitmap (0 when unmeasured → canvas == ink). */
function inkPads(ink: InkBox | null): { l: number; r: number; t: number; h: number } {
  if (!ink || !ink.cw || !ink.ch) return { l: 0, r: 0, t: 0, h: 1 }
  return {
    l: ink.x / ink.cw,
    r: (ink.cw - ink.x - ink.w) / ink.cw,
    t: ink.y / ink.ch,
    h: ink.h / ink.ch
  }
}

/**
 * Per-asset ink geometry a slot needs to be fitted like the vendor would.
 * Computed by `paintItem` (the pixel reads live there), passed in so the fit
 * itself stays a pure function the spacing detector can drive with real values.
 */
export interface BrandSlotMetrics {
  /** alpha bbox of the substituted bitmap, in its own canvas px */
  sub?: InkBox | null
  /** alpha bbox of the vendor bitmap this slot replaces, in its own canvas px */
  vendor?: InkBox | null
  /** vendor-derived minimum gap from the substituted INK to the nearest sibling, output px */
  gapPx?: number
}

/**
 * Breathing room a grown wordmark keeps from the nearest sibling. Kept for the
 * no-metrics fallback path only; real slot renders use the vendor-derived
 * `brandSlotGapPx()`.
 */
function slotSafeGap(box: StockRect): number {
  return Math.max(SLOT_GAP_FLOOR, box.h * 0.02)
}

/**
 * Aspect-aware fitting for brand slots, under three hard rules: **match the
 * vendor's visible ink, grow to fit, never overlap**.
 *
 * The node **box** is sized from the vendor's own bitmap (`adapters/xiaomi.cjs`
 * derives `width = height × canvasAspect`) and the vendor paints its wordmark
 * `contain` into it — so what the vendor actually *shows* is its canvas shrunk
 * to its ink: `box.h × (vendorInkH / vendorCanvasH)` tall, sitting where its
 * padding puts it. Xiaomi's wordmarks show 0.70-0.76 of the box height; the
 * `assets/logos/**` substitutes are trimmed to 1.00. Sizing the drawn rect from
 * the CANVAS (the pre-fix rule) therefore let a swapped mark paint up to 43%
 * taller than the vendor's, with its optical centre several px off the model
 * text.
 *
 * So:
 *  1. **scale by ink height** — the target is the vendor's ink height in this
 *     box, converted back to the substitute's canvas height through the
 *     substitute's own ink fraction;
 *  2. **share the vendor's optical centre** — the ink is placed where the
 *     vendor's ink sits inside the box. The vendor's padding is symmetric
 *     (padTop == padBottom), so that is the box's vertical centre, which is also
 *     where `textBaseline: 'middle'` puts the model text beside it;
 *  3. **keep the vendor's gap** — growth is clamped on the INK edge, not the
 *     canvas edge, and stops short of the sibling by `brandSlotGapPx()`.
 *
 * `free` (from `layout.ts/annotateBrandFree`) is the empty room the band really
 * leaves on the growth side. When the ink-height target does not fit, the whole
 * rect scales down proportionally — height follows width, the intrinsic aspect
 * is preserved exactly, nothing is stretched or letterboxed, and the gravity
 * edge stays put. Width is never clamped on its own, so the no-overlap guarantee
 * still holds for a long wordmark ("Panasonic" is 9 glyphs against the vendor's
 * 6, and is allowed to be that much wider).
 */
export function brandSlotRect(
  img: CanvasImageSource,
  box: StockRect,
  gravity: string | undefined,
  free?: BrandFree,
  metrics?: BrandSlotMetrics
): StockRect {
  const { w: iw, h: ih } = imageSize(img)
  if (!iw || !ih || !box.w || !box.h) return box
  const assetAspect = iw / ih
  const boxAspect = box.w / box.h
  const { h: ha, v: va } = gravityAnchor(gravity)
  const sub = inkPads(metrics?.sub ?? null)
  const ven = inkPads(metrics?.vendor ?? null)
  const hasInk = !!metrics?.sub && !!metrics?.vendor

  // 1. the size the vendor's own mark would have shown in this box
  let wantW: number
  let wantH: number
  if (assetAspect >= boxAspect) {
    wantH = hasInk ? (box.h * ven.h) / sub.h : box.h
    wantW = wantH * assetAspect
  } else {
    wantW = box.w
    wantH = box.w / assetAspect
  }

  // 2. clamp on the INK edge so the mark stops short of the sibling
  const gap = hasInk ? Math.max(metrics?.gapPx ?? 0, SLOT_GAP_FLOOR) : slotSafeGap(box)
  if (free) {
    // How wide the INK may be: from the edge the vendor's own mark started on to
    // the sibling limit (`slack - gap`) on the growth side. Note the gap is
    // *subtracted*, not floored at zero slack — the vendor's declared margin can
    // be smaller than the required gap, and a grow-only clamp (`max(0, slack -
    // gap)`) then let the mark eat the whole box and leave only `slack`, which
    // is exactly how the pre-fix render collapsed 9.7 units of spacing to 2px.
    const slackX =
      ha === 'center' ? Math.min(free.left, free.right) * 2 : ha === 'left' ? free.right : free.left
    const maxInkW =
      ha === 'right'
        ? box.w * (1 - ven.r) + slackX - gap
        : box.w * (1 - ven.l) + slackX - (ha === 'center' ? gap * 2 : gap)
    const maxW = Math.max(box.w * 0.2, maxInkW) / Math.max(0.05, 1 - sub.l - sub.r)
    const slackY = va === 'top' ? free.bottom : va === 'bottom' ? free.top : Math.min(free.top, free.bottom) * 2
    const maxH = box.h + Math.max(0, slackY - gap)
    const k = Math.min(1, maxW / wantW, maxH / wantH)
    wantW *= k
    wantH *= k
  }
  const w = wantW
  const h = wantH

  // 3. place the INK on the vendor's ink edges and optical centre
  let x: number
  if (ha === 'right') x = box.x + box.w * (1 - ven.r) - w * (1 - sub.r)
  else if (ha === 'center') x = box.x + box.w / 2 - w / 2
  else x = box.x + box.w * ven.l - w * sub.l
  let y: number
  if (!hasInk && va !== 'center') {
    y = va === 'bottom' ? box.y + box.h - h : box.y
  } else {
    y = box.y + box.h * (ven.t + ven.h / 2) - h * (sub.t + sub.h / 2)
    if (va === 'top') y = Math.max(y, box.y)
    else if (va === 'bottom') y = Math.min(y, box.y + box.h - h)
  }
  return { x, y, w, h }
}

function paintItem(
  env: TemplateRenderEnv,
  item: PlacedItem,
  k: number,
  foreground: string[],
  themeId: string | undefined,
  onLightSurface = false,
  brandDark = false,
  // Rotated bands paint inside a canvas transform, in the band's pre-rotation
  // coordinate space: the on-canvas clamp below compares against the WRONG
  // axis there (a ±90° band's text runs along the photo's long edge), so it is
  // skipped — `fitBandItems()` already keeps the content inside the band.
  inRotatedBand = false
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
      // ink-aware fit: match the vendor's *visible* mark height and optical
      // centre, grow into the band's real slack, stop short of the sibling by
      // the vendor's own declared gap. The vendor bitmap is still loaded for
      // every slot (it is the baked asset the slot replaces), which is what
      // makes the vendor's ink metrics available here.
      const vendorImg = env.images[bakedKey]
      const metrics: BrandSlotMetrics = {
        sub: inkBoxOf(raw),
        vendor: vendorImg && vendorImg !== raw ? inkBoxOf(vendorImg) : null,
        gapPx: brandSlotGapPx(item.node, box, k, vendorImg ? inkBoxOf(vendorImg) : null)
      }
      const rect = brandSlotRect(raw, box, item.node.gravity, scaleFree(item.brandFree, k), metrics)
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
  if (!inRotatedBand && (box.w > env.W - pad * 2 || box.h > env.H - pad * 2)) {
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
  if (!inRotatedBand) {
    box.x = Math.max(pad, Math.min(box.x, env.W - pad - box.w))
    box.y = Math.max(pad, Math.min(box.y, env.H - pad - box.h))
  }
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
    // Whole-band rotation (vendor `bitmaps[i].rotation`): turn the band's
    // pre-rotation drawing (background + items, one coordinate space) about
    // the band box centre. Text and child images rotate with it.
    const rotated = !!band.rotation
    ctx.save()
    if (rotated) {
      const cx = r.x + r.w / 2
      const cy = r.y + r.h / 2
      ctx.translate(cx, cy)
      ctx.rotate(((band.rotation as number) * Math.PI) / 180)
      ctx.translate(-cx, -cy)
    }
    if (band.band.background) {
      const bg = rotated ? scaleRect(band.layoutBox ?? band.box, scale) : r
      ctx.save()
      ctx.fillStyle = cssColor(band.band.background)
      if (band.band.radius) {
        ctx.beginPath()
        roundRectPath(ctx, bg.x, bg.y, bg.w, bg.h, band.band.radius * scale)
        ctx.fill()
      } else {
        ctx.fillRect(bg.x, bg.y, bg.w, bg.h)
      }
      ctx.restore()
    }
    const onLightSurface = false
    for (const item of band.items) paintItem(env, item, scale, fg, theme?.id, onLightSurface, brandDark, rotated)
    ctx.restore()
  }

  for (const band of layout.bands) if (band.band.drawFirst) drawBand(band)
  const photo = scaleRect(layout.photo, scale)
  drawPhoto(env, photo.x, photo.y, photo.w, photo.h, 0, 0)
  for (const band of layout.bands) if (!band.band.drawFirst) drawBand(band)
  return photo
}
