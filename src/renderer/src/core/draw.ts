import type { CropInfo, TemplateRenderEnv } from '../types'
import { exifLine, cameraLine, formatDateOnly, modelWithoutMake } from './exif'

export { modelWithoutMake }

export const FONT_UI = '"Inter", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'
export const FONT_DISPLAY = '"Bebas Neue", "Inter", "Microsoft YaHei", sans-serif'
export const FONT_MONO = '"JetBrains Mono", "SFMono-Regular", "Consolas", monospace'
export const FONT_HAND = '"Caveat", "Segoe Script", "KaiTi", cursive'
export const FONT_CN = '"YouShe", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'
export const FONT_ZEISS = '"Zeiss", "Inter", "Microsoft YaHei", sans-serif'
export const FONT_VENDOR = '"OplusSans", "Inter", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'
export const FONT_TECH = '"Chakra Petch", "OplusSans", "Inter", sans-serif'

export interface Theme {
  bg: string
  fg: string
  sub: string
  line: string
  accent: string
  paper: string
  shadow: string
}

export const THEMES: Record<string, Theme> = {
  paper: { bg: '#f6f3ec', fg: '#151515', sub: '#6f6a61', line: 'rgba(21,21,21,0.16)', accent: '#a9824c', paper: '#fbf9f4', shadow: 'rgba(20,18,15,0.22)' },
  white: { bg: '#ffffff', fg: '#0c0c0d', sub: '#74747a', line: 'rgba(0,0,0,0.12)', accent: '#c3a06a', paper: '#ffffff', shadow: 'rgba(0,0,0,0.18)' },
  cream: { bg: '#eee6d7', fg: '#1d1811', sub: '#746958', line: 'rgba(29,24,17,0.18)', accent: '#9b6d3d', paper: '#f8f2e6', shadow: 'rgba(40,32,22,0.24)' },
  ink: { bg: '#111214', fg: '#f5f2ea', sub: '#aaa49a', line: 'rgba(245,242,234,0.18)', accent: '#d0ac72', paper: '#1a1b1e', shadow: 'rgba(0,0,0,0.5)' },
  black: { bg: '#08090a', fg: '#f4f0e6', sub: '#9e988d', line: 'rgba(244,240,230,0.16)', accent: '#d8b477', paper: '#101113', shadow: 'rgba(0,0,0,0.6)' },
  stone: { bg: '#d9d4c9', fg: '#191714', sub: '#665f54', line: 'rgba(25,23,20,0.2)', accent: '#7a644b', paper: '#ece7dc', shadow: 'rgba(30,26,20,0.26)' }
}

export function themeOf(params: Record<string, unknown>): Theme {
  return THEMES[String(params.theme ?? 'paper')] ?? THEMES.paper
}

export function pct(value: unknown, fallback = 0): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

export function shortSide(env: Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>): number {
  return Math.min(env.pw, env.ph)
}

export function marginOf(env: Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>): number {
  return shortSide(env) * (pct(env.params.marginPct, 6) / 100)
}

export function radiusOf(env: Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>): number {
  return shortSide(env) * (pct(env.params.radiusPct, 0) / 100)
}

export function scaleFont(env: Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>, base: number): number {
  return base * (pct(env.text.fontScale, 1) || 1)
}

export function setFont(ctx: CanvasRenderingContext2D, size: number, family = FONT_UI, weight: string | number = 400, style = 'normal'): void {
  ctx.font = `${style} ${weight} ${Math.max(1, size)}px ${family}`
}

/**
 * The single clamp applied to `design.text.fontScale` (the Inspector's 字号缩放
 * slider). `scene.ts` scales every `ctx.font` it paints with this value, so any
 * code that measures text has to use the same number or the glyphs paint
 * outside the advance they were laid out with. Returns exactly `1` for the
 * default so callers can skip the whole branch.
 */
export function textScaleOf(raw: unknown): number {
  const n = Number(raw)
  const s = Math.max(0.5, Math.min(2, Number.isFinite(n) ? n : 1))
  return Math.abs(s - 1) < 0.001 ? 1 : s
}

/**
 * #69 — what font scale is the context we are *measuring through* actually
 * applying?
 *
 * `scene.ts/scaledContext()` rewrites only one thing: the px inside `ctx.font`.
 * Every coordinate, every `letterSpacing` and every width cap a caller hands us
 * stays in design units. So a fit loop measuring through that context reads ink
 * `s` times wider than the room it is fitting into — `fitSize()`'s 0.96 ladder
 * then ate the whole scale and 字号缩放 stopped working (14 cards / 15 ops, 9 of
 * them pinned at exactly `0.96^5 = 0.9948`: the user drags the slider and the
 * bottom EXIF row does not move at all).
 *
 * Probing is the only way to recover `s` without touching `scene.ts` or the 40+
 * call sites: write a known size, read back what the context reports, restore
 * what was there. An unscaled context gives `100/100 === 1`, and every product
 * that multiplies by it is then an exact IEEE no-op — which is what keeps the 1.0
 * arm (and the signed 175-card baseline derived from it) byte-identical.
 */
export function paintedTextScale(ctx: CanvasRenderingContext2D, probe = 100): number {
  try {
    const prior = ctx.font
    setFont(ctx, probe, FONT_UI, 400)
    const raw = /(\d*\.?\d+)px/.exec(ctx.font)?.[1]
    const painted = raw === undefined ? NaN : Number(raw)
    if (!Number.isFinite(painted)) return 1
    const s = textScaleOf(painted / probe)
    ctx.font = s === 1 ? prior : prior.replace(/(\d*\.?\d+)px/, (_m, n: string) => `${parseFloat(n) / s}px`)
    return s
  } catch {
    return 1
  }
}

export function setTracking(ctx: CanvasRenderingContext2D, px: number): void {
  try {
    ;(ctx as unknown as { letterSpacing: string }).letterSpacing = `${px}px`
  } catch {
    // older canvas implementations ignore tracking
  }
}

export function resetTracking(ctx: CanvasRenderingContext2D): void {
  setTracking(ctx, 0)
}

export function roundRectPath(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number): void {
  const rr = Math.max(0, Math.min(r, Math.min(w, h) / 2))
  ctx.beginPath()
  ctx.moveTo(x + rr, y)
  ctx.arcTo(x + w, y, x + w, y + h, rr)
  ctx.arcTo(x + w, y + h, x, y + h, rr)
  ctx.arcTo(x, y + h, x, y, rr)
  ctx.arcTo(x, y, x + w, y, rr)
  ctx.closePath()
}

export function fillRoundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number, color: string): void {
  roundRectPath(ctx, x, y, w, h, r)
  ctx.fillStyle = color
  ctx.fill()
}

export function line(ctx: CanvasRenderingContext2D, x1: number, y1: number, x2: number, y2: number, color: string, width = 1, dash?: number[]): void {
  ctx.save()
  ctx.strokeStyle = color
  ctx.lineWidth = width
  if (dash) ctx.setLineDash(dash)
  ctx.beginPath()
  ctx.moveTo(x1, y1)
  ctx.lineTo(x2, y2)
  ctx.stroke()
  ctx.restore()
}

export function drawPhoto(
  env: TemplateRenderEnv,
  x: number,
  y: number,
  w: number,
  h: number,
  radius = 0,
  shadow = 0
): void {
  const ctx = env.ctx
  const c: CropInfo = env.crop
  if (shadow > 0) {
    ctx.save()
    ctx.shadowColor = env.params.theme === 'ink' || env.params.theme === 'black' ? 'rgba(0,0,0,0.65)' : 'rgba(25,21,16,0.25)'
    ctx.shadowBlur = shortSide(env) * 0.03 * shadow
    ctx.shadowOffsetY = shortSide(env) * 0.012 * shadow
    ctx.fillStyle = '#000'
    roundRectPath(ctx, x, y, w, h, radius)
    ctx.fill()
    ctx.restore()
  }
  ctx.save()
  roundRectPath(ctx, x, y, w, h, radius)
  ctx.clip()
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(env.photo, c.sx, c.sy, c.sw, c.sh, x, y, w, h)
  ctx.restore()
}

export function drawPhotoCover(
  env: TemplateRenderEnv,
  x: number,
  y: number,
  w: number,
  h: number,
  radius = 0,
  shadow = 0
): void {
  const ctx = env.ctx
  const c = env.crop
  const scale = Math.max(w / c.sw, h / c.sh)
  const sw = w / scale
  const sh = h / scale
  const sx = c.sx + (c.sw - sw) / 2
  const sy = c.sy + (c.sh - sh) / 2
  if (shadow > 0) {
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.5)'
    ctx.shadowBlur = shortSide(env) * 0.03 * shadow
    ctx.fillStyle = '#000'
    roundRectPath(ctx, x, y, w, h, radius)
    ctx.fill()
    ctx.restore()
  }
  ctx.save()
  roundRectPath(ctx, x, y, w, h, radius)
  ctx.clip()
  ctx.drawImage(env.photo, sx, sy, sw, sh, x, y, w, h)
  ctx.restore()
}

export function drawBlurredBackdrop(
  env: TemplateRenderEnv,
  W: number,
  H: number,
  tint: Theme,
  tintAlpha: number
): void {
  // 假控件补线：这里原来写死 W/7 的降采样倍率，Inspector 的「背景模糊」滑杆
  // （params.blur）根本没人读。现在按倍率缩放，默认档 26 ⇒ 7*(26/26)=7，
  // 与写死值逐位相同（IEEE754 下 x/1、x*1、26/26 都是精确运算）⇒ 基线不动。
  //
  // #72：滑杆下限是 0，而 `pct()` 对 0（以及 null / ''）返回的就是 0 ——
  // `blurDiv = 7*(0/26) = 0`，`W/0 = Infinity`，被 `min(420, max(160, …))` 收成
  // 420，于是「完全不模糊」这一档照样走了降采样 + `blur(max(1, …))` 的 1px 滤镜
  // 地板（零除 + 一个既降采样又带滤镜的底片）。blur <= 0 现在直接走无降采样、无
  // ctx.filter 的分支；blur > 0 的算式逐位不变（同一个 `blurPct`、同一串运算、
  // 同一组 clamp），175 卡基线不受影响。
  const blurPct = pct(env.params.blur, 26)
  const noBlur = !(blurPct > 0)
  const blurDiv = 7 * (blurPct / 26)
  const smallW = noBlur ? Math.max(1, W) : Math.min(420, Math.max(160, Math.round(W / blurDiv)))
  const smallH = noBlur ? Math.max(1, H) : Math.max(80, Math.round(smallW * (H / W)))
  const c = env.crop
  const scale = Math.max(smallW / c.sw, smallH / c.sh)
  const sw = smallW / scale
  const sh = smallH / scale
  const sx = c.sx + (c.sw - sw) / 2
  const sy = c.sy + (c.sh - sh) / 2
  const ctx = env.ctx
  const over = 1.12
  ctx.save()
  ctx.imageSmoothingQuality = 'high'
  if (noBlur) {
    // full-resolution crop straight onto the canvas: no downsample step, no filter
    ctx.drawImage(env.photo, sx, sy, sw, sh, (-W * (over - 1)) / 2, (-H * (over - 1)) / 2, W * over, H * over)
  } else {
    const tmp = document.createElement('canvas')
    tmp.width = smallW
    tmp.height = smallH
    const tctx = tmp.getContext('2d')!
    tctx.drawImage(env.photo, sx, sy, sw, sh, 0, 0, smallW, smallH)
    const blur = shortSide(env) * 0.0015 * Math.max(0, blurPct)
    ctx.filter = `blur(${Math.max(1, blur)}px)`
    ctx.drawImage(tmp, -W * (over - 1) / 2, -H * (over - 1) / 2, W * over, H * over)
    ctx.filter = 'none'
  }
  ctx.globalAlpha = tintAlpha
  ctx.fillStyle = tint.bg
  ctx.fillRect(0, 0, W, H)
  ctx.globalAlpha = 1
  ctx.restore()
}

export function drawContain(
  ctx: CanvasRenderingContext2D,
  img: CanvasImageSource,
  x: number,
  y: number,
  w: number,
  h: number
): { x: number; y: number; w: number; h: number } {
  const iw = (img as HTMLImageElement).naturalWidth || (img as HTMLImageElement).width || w
  const ih = (img as HTMLImageElement).naturalHeight || (img as HTMLImageElement).height || h
  const s = Math.min(w / iw, h / ih)
  const dw = iw * s
  const dh = ih * s
  const dx = x + (w - dw) / 2
  const dy = y + (h - dh) / 2
  ctx.drawImage(img, dx, dy, dw, dh)
  return { x: dx, y: dy, w: dw, h: dh }
}

export function drawBrandLogo(
  env: TemplateRenderEnv,
  x: number,
  y: number,
  w: number,
  h: number,
  color: string
): boolean {
  if (!env.text.showLogo) return false
  const logo = env.images?.logo
  if (logo) {
    drawContain(env.ctx, logo, x, y, w, h)
    return true
  }
  const text = (env.exif.make || env.exif.model || '').toUpperCase()
  if (!text) return false
  const ctx = env.ctx
  ctx.save()
  ctx.fillStyle = color
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'center'
  const size = Math.min(h, w / Math.max(4, text.length * 0.58))
  setFont(ctx, size, FONT_UI, 800)
  ctx.fillText(text, x + w / 2, y + h / 2)
  ctx.restore()
  return true
}

export function pickTitle(env: TemplateRenderEnv, fallback = true): string {
  const t = env.text.title.trim()
  if (t) return t
  if (!fallback) return ''
  const make = env.exif.make.trim()
  const model = modelWithoutMake(make, env.exif.model)
  return [make, model].filter(Boolean).join(' ') || 'FRAME'
}

export function fitSize(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  start: number,
  min: number,
  family: string,
  weight: string | number,
  tracking = 0,
  scale?: number
): number {
  // #69 A5 — the cap has to be stated on the same scale as the measurement.
  // `setFont()` below goes through `scene.ts/scaledContext`, so
  // `ctx.measureText()` returns ink painted at `size * s`; the slot the caller
  // measured out in design units is `maxWidth`, so its painted room at this font
  // scale is `maxWidth * s`. Comparing the two on different scales is what made
  // the ladder consume `k` extra 0.96 steps and cancel the slider out. With the
  // cap scaled, the loop again solves the *design* fit — the same answer the 1.0
  // arm gets — and the ink that lands on canvas is that size times `s`: exactly
  // linear in 字号缩放. `scale === 1` keeps `maxWidth` byte-for-byte.
  const s = scale ?? paintedTextScale(ctx)
  // `+ 1e-6` only on the scaled branch: a text that fits the slot EXACTLY at 1.0
  // compares `(size*s)*advance` against `maxWidth*s`, and those two products round
  // differently by ~1e-13 — without the slack the ladder would drop a whole 0.96
  // step (a 4% font jump) on a knife-edge float. 1e-6 px is invisible and is NOT
  // applied at s === 1, where `cap` stays the byte-for-byte `maxWidth`.
  const cap = s === 1 ? maxWidth : maxWidth * s + 1e-6
  let size = start
  setTracking(ctx, tracking * size)
  while (size > min) {
    setFont(ctx, size, family, weight)
    if (ctx.measureText(text).width <= cap) break
    size *= 0.96
  }
  setFont(ctx, size, family, weight)
  return size
}

export function drawFitted(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  maxWidth: number,
  size: number,
  color: string,
  opts: { family?: string; weight?: string | number; align?: CanvasTextAlign; baseline?: CanvasTextBaseline; tracking?: number; min?: number; shadow?: boolean } = {}
): number {
  if (!text) return 0
  const family = opts.family ?? FONT_UI
  const weight = opts.weight ?? 400
  const tracking = opts.tracking ?? 0
  const scale = paintedTextScale(ctx)
  const finalSize = fitSize(ctx, text, maxWidth, size, opts.min ?? size * 0.45, family, weight, tracking, scale)
  ctx.save()
  ctx.fillStyle = color
  ctx.textAlign = opts.align ?? 'left'
  ctx.textBaseline = opts.baseline ?? 'alphabetic'
  if (opts.shadow) {
    ctx.shadowColor = 'rgba(0,0,0,0.5)'
    ctx.shadowBlur = finalSize * 0.35
    ctx.shadowOffsetY = finalSize * 0.08
  }
  setTracking(ctx, tracking * finalSize)
  setFont(ctx, finalSize, family, weight)
  ctx.fillText(text, x, y)
  resetTracking(ctx)
  ctx.restore()
  // #69 A1/A2 — the part of the row pitch that lives in this file. Every stacked
  // template advances with this return value (`y += drawFitted(...)`,
  // `metaY +=`, `cy += camSize * 0.55` in `drawMeta` below), and it used to be the
  // *design* size. Once A5 stops the fit ladder from eating the scale, the design
  // size no longer grows with 字号缩放 — so a pitch built on it would keep a 1.0
  // line step under 1.22 ink, which is exactly the collision the audit calls 叠字.
  // Returning the advance the ink actually took keeps the pitch proportional to
  // the glyphs. `scale === 1` hands back `finalSize` unmultiplied, so the 1.0 arm
  // is bit-for-bit untouched.
  // Tracking is deliberately NOT multiplied here: that is `card-4`'s settled
  // 1.2708 = 1.22/0.96 quantisation (verdict B), out of scope for this batch.
  // Rows positioned by an absolute fraction of the card (`ticket-1`'s
  // `cardH * (0.2395 + i * 0.1208)`, `fm-editorial`'s `top * 0.30 / top * 0.62`)
  // ignore this return value and still need the `templates.ts` step in README §A1.
  return scale === 1 ? finalSize : finalSize * scale
}

export function drawMeta(
  env: TemplateRenderEnv,
  x: number,
  y: number,
  maxWidth: number,
  theme: Theme,
  opts: {
    align?: 'left' | 'center' | 'right'
    size?: number
    color?: string
    sub?: string
    date?: boolean
    exif?: boolean
    gap?: number
    tracking?: number
    family?: string
  } = {}
): number {
  const ctx = env.ctx
  const align = opts.align ?? 'left'
  const anchorX = align === 'center' ? x + maxWidth / 2 : align === 'right' ? x + maxWidth : x
  const textAlign: CanvasTextAlign = align
  const base = opts.size ?? shortSide(env) * 0.022
  const gap = opts.gap ?? base * 0.55
  const color = opts.color ?? theme.fg
  const sub = opts.sub ?? theme.sub
  let cy = y
  const cam = cameraLine(env.exif, pickTitle(env, false) || 'FRAMEMARK')
  const camSize = drawFitted(ctx, cam.toUpperCase(), anchorX, cy, maxWidth, base, color, {
    family: opts.family ?? FONT_UI,
    weight: 600,
    align: textAlign,
    tracking: opts.tracking ?? 0.08,
    min: base * 0.5
  })
  cy += camSize * 0.55
  if (opts.exif !== false) {
    const line = exifLine(env.exif)
    if (line) {
      cy += gap
      cy += drawFitted(ctx, line, anchorX, cy, maxWidth, base * 0.78, sub, {
        family: FONT_MONO,
        weight: 400,
        align: textAlign,
        min: base * 0.4
      })
    }
  }
  if (opts.date !== false && env.text.showDate && env.exif.dateTime) {
    cy += gap * 0.8
    cy += drawFitted(ctx, formatDateOnly(env.exif.dateTime), anchorX, cy, maxWidth, base * 0.66, sub, {
      family: FONT_MONO,
      weight: 400,
      align: textAlign,
      min: base * 0.35
    })
  }
  return cy - y
}

export function drawSwatches(
  env: TemplateRenderEnv,
  colors: string[],
  x: number,
  y: number,
  maxWidth: number,
  size: number,
  labelColor: string,
  opts?: { spread?: boolean }
): void {
  const ctx = env.ctx
  const gap = size * 0.34
  const n = colors.length
  const total = n * size + (n - 1) * gap
  // spread: first/last swatch centres sit on the row edges so the row fills
  // `maxWidth`; the default keeps the packed, centred group.
  const spread = Boolean(opts?.spread) && n > 1
  const start = spread ? x + size / 2 : x + Math.max(0, (maxWidth - total) / 2)
  const step = spread ? (maxWidth - size) / (n - 1) : size + gap
  for (let i = 0; i < n; i++) {
    const cx = spread ? start + i * step : start + i * (size + gap) + size / 2
    ctx.save()
    ctx.beginPath()
    ctx.arc(cx, y + size / 2, size / 2, 0, Math.PI * 2)
    ctx.fillStyle = colors[i]
    ctx.fill()
    ctx.restore()
    ctx.save()
    ctx.fillStyle = labelColor
    ctx.textAlign = 'center'
    ctx.textBaseline = 'top'
    setFont(ctx, size * 0.3, FONT_MONO, 400)
    ctx.fillText(colors[i].replace('#', '').toUpperCase(), cx, y + size + size * 0.22)
    ctx.restore()
  }
}

// Flat contiguous tile strip (reference palette style for poetryCard).
// Five equal cells spanning `width`, no gaps, no hex labels.
export function drawPaletteTiles(
  env: TemplateRenderEnv,
  colors: string[],
  x: number,
  y: number,
  width: number,
  height: number
): void {
  const ctx = env.ctx
  if (!colors.length || width <= 0 || height <= 0) return
  const tw = width / colors.length
  const radius = Math.min(height * 0.12, tw * 0.06)
  for (let i = 0; i < colors.length; i++) {
    fillRoundRect(ctx, x + i * tw, y, tw + 0.5, height, radius, colors[i])
  }
}

export function extractPalette(env: TemplateRenderEnv, count = 5): string[] {
  try {
    const w = 48
    const h = Math.max(24, Math.round(w * (env.ph / env.pw)))
    const tmp = document.createElement('canvas')
    tmp.width = w
    tmp.height = h
    const tctx = tmp.getContext('2d', { willReadFrequently: true })!
    const c = env.crop
    tctx.drawImage(env.photo, c.sx, c.sy, c.sw, c.sh, 0, 0, w, h)
    const data = tctx.getImageData(0, 0, w, h).data
    const buckets: { r: number; g: number; b: number; n: number }[] = []
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]
      const g = data[i + 1]
      const b = data[i + 2]
      const a = data[i + 3]
      if (a < 200) continue
      const max = Math.max(r, g, b)
      const min = Math.min(r, g, b)
      const l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
      if (l > 0.96 || l < 0.035) continue
      if (max - min < 6 && l > 0.9) continue
      const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4)
      const found = buckets.find((x) => (((x.r >> 4) << 8) | ((x.g >> 4) << 4) | (x.b >> 4)) === key)
      if (found) {
        found.r += r
        found.g += g
        found.b += b
        found.n++
      } else buckets.push({ r, g, b, n: 1 })
    }
    buckets.sort((a, b) => b.n - a.n)
    const picked: string[] = []
    for (const b of buckets) {
      const c = { r: b.r / b.n, g: b.g / b.n, b: b.b / b.n }
      const l = (0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b) / 255
      if (picked.length && picked.some((hex) => {
        const rr = parseInt(hex.slice(1, 3), 16)
        const gg = parseInt(hex.slice(3, 5), 16)
        const bb = parseInt(hex.slice(5, 7), 16)
        return Math.hypot(rr - c.r, gg - c.g, bb - c.b) < 45
      })) continue
      picked.push(`#${[c.r, c.g, c.b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`)
      if (picked.length >= count) break
    }
    while (picked.length < count) {
      const light = picked.length % 2 === 0 ? '#d8d3c8' : '#8f887c'
      picked.push(light)
    }
    return picked
  } catch {
    return ['#2b2b2b', '#6f6a61', '#a99f8c', '#d6cfc1', '#f2eee6']
  }
}

export function drawGrain(env: TemplateRenderEnv, W: number, H: number, opacity: number): void {
  if (opacity <= 0) return
  const s = 128
  const tmp = document.createElement('canvas')
  tmp.width = s
  tmp.height = s
  const tctx = tmp.getContext('2d')!
  const img = tctx.createImageData(s, s)
  let seed = 1337
  for (let i = 0; i < img.data.length; i += 4) {
    seed = (seed * 1664525 + 1013904223) >>> 0
    const v = seed & 255
    img.data[i] = v
    img.data[i + 1] = v
    img.data[i + 2] = v
    img.data[i + 3] = 255
  }
  tctx.putImageData(img, 0, 0)
  const ctx = env.ctx
  ctx.save()
  ctx.globalAlpha = opacity
  ctx.globalCompositeOperation = 'overlay'
  ctx.fillStyle = ctx.createPattern(tmp, 'repeat')!
  ctx.fillRect(0, 0, W, H)
  ctx.restore()
}

export function drawBarcode(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  color: string,
  seedText = 'FRAMEMARK'
): void {
  let seed = 0
  for (let i = 0; i < seedText.length; i++) seed = (seed * 31 + seedText.charCodeAt(i)) >>> 0
  ctx.save()
  ctx.fillStyle = color
  let cx = x
  while (cx < x + w) {
    seed = (seed * 1664525 + 1013904223) >>> 0
    const bw = 1 + (seed % 4)
    if ((seed & 7) > 2) ctx.fillRect(cx, y, Math.min(bw, x + w - cx), h)
    cx += bw + 1 + ((seed >> 4) % 3)
  }
  ctx.restore()
}

export function drawSprockets(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  count: number,
  color: string
): void {
  const gap = w / count
  const holeW = gap * 0.42
  const holeH = h * 0.42
  for (let i = 0; i < count; i++) {
    const cx = x + i * gap + gap / 2
    fillRoundRect(ctx, cx - holeW / 2, y + (h - holeH) / 2, holeW, holeH, holeH * 0.22, color)
  }
}

export function drawVerticalText(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  size: number,
  color: string,
  opts: { family?: string; weight?: string | number; tracking?: number } = {}
): void {
  ctx.save()
  ctx.translate(x, y)
  ctx.rotate(-Math.PI / 2)
  ctx.fillStyle = color
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  setTracking(ctx, (opts.tracking ?? 0.06) * size)
  setFont(ctx, size, opts.family ?? FONT_UI, opts.weight ?? 600)
  ctx.fillText(text, 0, 0)
  resetTracking(ctx)
  ctx.restore()
}

export function luminance(hex: string): number {
  const h = hex.replace('#', '')
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
}
