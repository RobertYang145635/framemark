import { setFont, setTracking, textScaleOf } from '../draw'
import type {
  StockBand,
  StockGroupNode,
  StockImageNode,
  StockNode,
  StockShapeNode,
  StockSpaceNode,
  StockEnv,
  StockMargins,
  StockStyle,
  StockTextNode
} from './ir'
import { stockFontFamily } from './fonts'
import { stockTokenText } from './tokens'

export interface StockRect {
  x: number
  y: number
  w: number
  h: number
}

export interface PlacedText {
  kind: 'text'
  node: StockTextNode
  box: StockRect
  lines: string[]
  family: string
  fontSize: number
  lineHeight: number
  color: string
  alpha: number
  tracking: number
  align: 'left' | 'center' | 'right'
}

export interface PlacedImage {
  kind: 'image'
  node: StockImageNode
  box: StockRect
  /**
   * Brand slots only: how much empty space the solved band actually leaves
   * around the declared box, per side. `paint.ts` grows a swapped wordmark into
   * this slack (`grow to fit`) and scales it back the moment the slack runs out
   * (`never overlap`) — the declared box alone is what made the aspect-fit
   * wordmark paint straight over the model text.
   */
  brandFree?: BrandFree
}

/**
 * Empty distance from the declared box edge to the nearest sibling box edge
 * (or to the band's inner edge), never negative. Measured on the FINAL boxes,
 * after `fitBandItems()` has rescaled the band.
 */
export interface BrandFree {
  left: number
  right: number
  top: number
  bottom: number
}

export interface PlacedShape {
  kind: 'shape'
  node: StockShapeNode
  box: StockRect
}

export interface PlacedGroup {
  kind: 'group'
  box: StockRect
  color?: string
}

export type PlacedItem = PlacedText | PlacedImage | PlacedShape | PlacedGroup

export interface PlacedBand {
  band: StockBand
  /** Final on-canvas band rect (what the background must cover). */
  box: StockRect
  /**
   * Rotated bands only: the pre-rotation rect the items were laid out in —
   * `box` with w/h swapped about the same centre (±90°). Item boxes and
   * `layoutBox` share one coordinate space; `paint.ts` turns them into canvas
   * space with the band's rotation transform.
   */
  layoutBox?: StockRect
  /** Rotated bands only: degrees copied from `band.rotation`. */
  rotation?: number
  items: PlacedItem[]
}

export interface StockLayout {
  W: number
  H: number
  photo: StockRect
  bands: PlacedBand[]
}

type Measured = { w: number; h: number; text?: PlacedText }

let measureCtx: CanvasRenderingContext2D | null = null

function ctxForMeasure(): CanvasRenderingContext2D {
  if (!measureCtx) {
    const canvas = document.createElement('canvas')
    canvas.width = 8
    canvas.height = 8
    measureCtx = canvas.getContext('2d')!
  }
  return measureCtx
}

function lineHeightOf(node: StockTextNode): number {
  return node.lineHeight ?? node.size * 1.2
}

function measureLine(ctx: CanvasRenderingContext2D, text: string, node: StockTextNode, fontScale = 1): number {
  setTracking(ctx, (node.tracking ?? 0) * node.size)
  // `paint.ts` draws this node through `scene.ts/scaledContext`, which multiplies
  // the px in `ctx.font` by `text.fontScale`. Measuring at the unscaled size is
  // what let 字号缩放 > 1 paint glyphs 22% wider than the advance the row was
  // laid out with, i.e. the vendor's 16-unit gaps collapsing to ~12 and "35mm"
  // touching "f/". Same clamp, same size ⇒ the advance the layout spends is the
  // advance the glyph takes.
  setFont(ctx, node.size * fontScale, stockFontFamily(node.font), node.weight)
  const w = ctx.measureText(text).width
  setTracking(ctx, 0)
  return w
}

function wrapText(ctx: CanvasRenderingContext2D, text: string, node: StockTextNode, maxWidth: number, fontScale: number): string[] {
  if (!maxWidth || maxWidth <= 0) return text.split('\n')
  const out: string[] = []
  for (const paragraph of text.split('\n')) {
    if (!paragraph) {
      out.push('')
      continue
    }
    let line = ''
    for (const ch of Array.from(paragraph)) {
      const next = line + ch
      if (line && measureLine(ctx, next, node, fontScale) > maxWidth) {
        out.push(line)
        line = ch
      } else {
        line = next
      }
    }
    out.push(line)
  }
  return out
}

function textMeasured(node: StockTextNode, env: StockEnv): Measured {
  const value = stockTokenText(node.token, env)
  if (!value) return { w: 0, h: 0 }
  const ctx = ctxForMeasure()
  const fontScale = textScaleOf(env.text?.fontScale)
  const lines = wrapText(ctx, value, node, node.width ?? 0, fontScale)
  const lineHeight = lineHeightOf(node)
  const textWidth = node.width ?? Math.max(...lines.map((l) => measureLine(ctx, l, node, fontScale)), 0)
  const textHeight = lineHeight * Math.max(1, lines.length)
  const outline = node.outline
  const width = outline?.width ?? textWidth + (outline?.paddingX ?? 0) * 2
  const height = outline?.height ?? textHeight + (outline?.paddingY ?? 0) * 2
  return {
    w: width,
    h: height,
    text: {
      kind: 'text',
      node,
      box: { x: 0, y: 0, w: width, h: height },
      lines,
      family: stockFontFamily(node.font),
      fontSize: node.size,
      lineHeight,
      color: node.color ?? '#000000',
      alpha: node.alpha ?? 1,
      tracking: (node.tracking ?? 0) * node.size,
      align: outline ? 'center' : node.align ?? 'left'
    }
  }
}

function marginSum(m: { left?: number; top?: number; right?: number; bottom?: number } | undefined, axis: 'x' | 'y'): number {
  if (!m) return 0
  return axis === 'x' ? (m.left ?? 0) + (m.right ?? 0) : (m.top ?? 0) + (m.bottom ?? 0)
}

function intrinsic(node: StockNode, env: StockEnv): Measured {
  switch (node.kind) {
    case 'text':
      return textMeasured(node, env)
    case 'image':
      return { w: node.width, h: node.height }
    case 'shape':
      return { w: node.width, h: node.height }
    case 'space':
      return { w: node.width ?? 0, h: node.height ?? 0 }
    case 'group': {
      const flow = measureGroup(node, env)
      return { w: node.width ?? flow.w, h: node.height ?? flow.h }
    }
    default:
      return { w: 0, h: 0 }
  }
}

function measureGroup(node: StockGroupNode, env: StockEnv): { w: number; h: number } {
  const children = node.children.filter((c) => !(c.kind === 'text' && c.optional && !stockTokenText(c.token, env)))
  if (node.orientation === 'row') {
    let w = 0
    let h = 0
    for (const child of children) {
      const m = intrinsic(child, env)
      w += m.w + marginSum(child.kind === 'space' ? undefined : child.margins, 'x')
      h = Math.max(h, m.h + marginSum(child.kind === 'space' ? undefined : child.margins, 'y'))
    }
    w += Math.max(0, children.length - 1) * (node.gap ?? 0)
    return { w, h }
  }
  let w = 0
  let h = 0
  for (const child of children) {
    const m = intrinsic(child, env)
    h += m.h + marginSum(child.kind === 'space' ? undefined : child.margins, 'y')
    w = Math.max(w, m.w + marginSum(child.kind === 'space' ? undefined : child.margins, 'x'))
  }
  h += Math.max(0, children.length - 1) * (node.gap ?? 0)
  return { w, h }
}

function gravityParts(g: string | undefined): { h: 'left' | 'center' | 'right'; v: 'top' | 'center' | 'bottom' } {
  const s = g ?? 'leftTop'
  const h = /right/i.test(s) ? 'right' : /hor|center/i.test(s) && !/left/i.test(s) ? 'center' : 'left'
  const v = /bottom/i.test(s) ? 'bottom' : /verticalCenter|center/i.test(s) && !/top/i.test(s) ? 'center' : 'top'
  return { h, v }
}

function alignCross(g: string | undefined, outer: number, inner: number, horizontal: boolean): number {
  const { h, v } = gravityParts(g)
  if (horizontal) {
    if (v === 'top') return 0
    if (v === 'bottom') return outer - inner
    return (outer - inner) / 2
  }
  if (h === 'left') return 0
  if (h === 'right') return outer - inner
  return (outer - inner) / 2
}

function placeText(node: StockTextNode, box: StockRect, env: StockEnv, out: PlacedItem[]): void {
  const m = textMeasured(node, env)
  if (!m.text) return
  out.push({ ...m.text, box })
}

function placeNode(node: StockNode, box: StockRect, env: StockEnv, out: PlacedItem[]): void {
  switch (node.kind) {
    case 'text':
      placeText(node, box, env, out)
      break
    case 'image':
      out.push({ kind: 'image', node, box })
      break
    case 'shape':
      out.push({ kind: 'shape', node, box })
      break
    case 'group':
      if (node.background) out.push({ kind: 'group', box, color: node.background })
      layoutChildren(node, box, env, out)
      break
    case 'space':
      break
  }
}

function nodeGravity(node: StockNode): string | undefined {
  return node.kind === 'space' ? undefined : node.gravity
}

function nodeMargins(node: StockNode): StockMargins | undefined {
  return node.kind === 'space' ? undefined : node.margins
}

function layoutChildren(node: StockGroupNode, box: StockRect, env: StockEnv, out: PlacedItem[]): void {
  const children = node.children.filter((c) => !(c.kind === 'text' && c.optional && !stockTokenText(c.token, env)))
  if (!children.length) return

  if (children.length === 1) {
    const child = children[0]
    if (child.kind === 'space') return
    const m = child.margins
    let size = intrinsic(child, env)
    if (child.kind === 'group' && child.matchWidth) size = { ...size, w: box.w - marginSum(m, 'x') }
    if (child.kind === 'group' && child.matchHeight) size = { ...size, h: box.h - marginSum(m, 'y') }
    const parts = gravityParts(nodeGravity(child) ?? node.gravity)
    const contentW = box.w - (m?.left ?? 0) - (m?.right ?? 0)
    const contentH = box.h - (m?.top ?? 0) - (m?.bottom ?? 0)
    const x = box.x + (m?.left ?? 0) + (parts.h === 'right' ? contentW - size.w : parts.h === 'center' ? (contentW - size.w) / 2 : 0)
    const y = box.y + (m?.top ?? 0) + (parts.v === 'bottom' ? contentH - size.h : parts.v === 'center' ? (contentH - size.h) / 2 : 0)
    placeNode(child, { x, y, w: size.w, h: size.h }, env, out)
    return
  }

  const horizontal = node.orientation === 'row'
  const gap = node.gap ?? 0
  const sizes = children.map((c) => {
    let m = intrinsic(c, env)
    if (c.kind === 'group' && c.matchWidth) m = { ...m, w: box.w - marginSum(c.margins, 'x') }
    if (c.kind === 'group' && c.matchHeight) m = { ...m, h: box.h - marginSum(c.margins, 'y') }
    return m
  })

  const cross = (child: StockNode, m: StockMargins | undefined, inner: number): number =>
    alignCross(nodeGravity(child) ?? node.gravity, (horizontal ? box.h : box.w) - (horizontal ? (m?.top ?? 0) + (m?.bottom ?? 0) : (m?.left ?? 0) + (m?.right ?? 0)), inner, horizontal)

  if (horizontal && !children.some((c) => c.kind === 'space')) {
    const lefts: number[] = []
    const rights: number[] = []
    const centers: number[] = []
    children.forEach((c, i) => {
      const p = gravityParts(nodeGravity(c))
      if (p.h === 'right') rights.push(i)
      else if (p.h === 'center') centers.push(i)
      else lefts.push(i)
    })
    if (lefts.length && rights.length) {
      let xL = box.x
      for (const i of lefts) {
        const c = children[i]
        const m = nodeMargins(c)
        const size = sizes[i]
        const x = xL + (m?.left ?? 0)
        const y = box.y + (m?.top ?? 0) + cross(c, m, size.h)
        placeNode(c, { x, y, w: size.w, h: size.h }, env, out)
        xL = x + size.w + (m?.right ?? 0)
      }
      let xR = box.x + box.w
      for (let k = rights.length - 1; k >= 0; k--) {
        const i = rights[k]
        const c = children[i]
        const m = nodeMargins(c)
        const size = sizes[i]
        const x = xR - (m?.right ?? 0) - size.w
        const y = box.y + (m?.top ?? 0) + cross(c, m, size.h)
        placeNode(c, { x, y, w: size.w, h: size.h }, env, out)
        xR = x - (m?.left ?? 0)
      }
      for (const i of centers) {
        const c = children[i]
        const m = nodeMargins(c)
        const size = sizes[i]
        const x = box.x + (box.w - size.w) / 2
        const y = box.y + (m?.top ?? 0) + cross(c, m, size.h)
        placeNode(c, { x, y, w: size.w, h: size.h }, env, out)
      }
      return
    }
  }

  const totalWeight = children.reduce((n, c) => n + (c.kind === 'space' ? Math.max(0.001, c.weight ?? 1) : 0), 0)
  const totalFixed = children.reduce(
    (n, c, i) =>
      n +
      (c.kind === 'space'
        ? 0
        : (horizontal ? sizes[i].w : sizes[i].h) + marginSum(c.margins, horizontal ? 'x' : 'y')),
    0
  ) + gap * Math.max(0, children.length - 1)
  const available = horizontal ? box.w : box.h
  const free = Math.max(0, available - totalFixed)
  let cursor = horizontal ? box.x : box.y
  for (let i = 0; i < children.length; i++) {
    const child = children[i]
    const m = child.kind === 'space' ? undefined : child.margins
    if (i > 0) cursor += gap
    if (child.kind === 'space') {
      cursor += (free * Math.max(0.001, child.weight ?? 1)) / Math.max(0.001, totalWeight)
      continue
    }
    const size = sizes[i]
    if (horizontal) {
      const x = cursor + (m?.left ?? 0)
      const y = box.y + (m?.top ?? 0) + cross(child, m, size.h)
      placeNode(child, { x, y, w: size.w, h: size.h }, env, out)
      cursor += size.w + (m?.left ?? 0) + (m?.right ?? 0)
    } else {
      const x = box.x + (m?.left ?? 0) + cross(child, m, size.w)
      const y = cursor + (m?.top ?? 0)
      placeNode(child, { x, y, w: size.w, h: size.h }, env, out)
      cursor += size.h + (m?.top ?? 0) + (m?.bottom ?? 0)
    }
  }
}

function placedBounds(items: PlacedItem[]): StockRect | null {
  let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity
  for (const item of items) {
    if (item.box.w <= 0 || item.box.h <= 0) continue
    x1 = Math.min(x1, item.box.x)
    y1 = Math.min(y1, item.box.y)
    x2 = Math.max(x2, item.box.x + item.box.w)
    y2 = Math.max(y2, item.box.y + item.box.h)
  }
  if (!Number.isFinite(x1)) return null
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }
}

function fitBandItems(box: StockRect, items: PlacedItem[]): void {
  const bounds = placedBounds(items)
  if (!bounds) return
  const padX = box.w * 0.025
  const padY = box.h * 0.04
  const inner = { x: box.x + padX, y: box.y + padY, w: Math.max(1, box.w - padX * 2), h: Math.max(1, box.h - padY * 2) }
  const overflow = bounds.x < inner.x - 0.5 || bounds.y < inner.y - 0.5 ||
    bounds.x + bounds.w > inner.x + inner.w + 0.5 || bounds.y + bounds.h > inner.y + inner.h + 0.5
  if (!overflow) return
  const scale = Math.min(1, inner.w / Math.max(1, bounds.w), inner.h / Math.max(1, bounds.h))
  const offsetX = inner.x + (inner.w - bounds.w * scale) / 2 - bounds.x * scale
  const offsetY = inner.y + (inner.h - bounds.h * scale) / 2 - bounds.y * scale
  for (const item of items) {
    item.box = {
      x: item.box.x * scale + offsetX,
      y: item.box.y * scale + offsetY,
      w: item.box.w * scale,
      h: item.box.h * scale
    }
    if (item.kind === 'text') {
      item.fontSize *= scale
      item.lineHeight *= scale
      item.tracking *= scale
    }
  }
}

/** Band inner edge, the same 2.5% / 4% inset `fitBandItems()` keeps text to. */
function bandInnerBox(box: StockRect): StockRect {
  const padX = box.w * 0.025
  const padY = box.h * 0.04
  return { x: box.x + padX, y: box.y + padY, w: Math.max(1, box.w - padX * 2), h: Math.max(1, box.h - padY * 2) }
}

function sharesCrossAxis(a: StockRect, b: StockRect, axis: 'x' | 'y'): boolean {
  const lo = axis === 'x'
    ? Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
    : Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
  return lo > 0.5
}

/**
 * "Grow to fit, never overlap": measure the empty room a brand slot has before
 * `paint.ts` decides how wide the swapped wordmark may be. Siblings that
 * already intersect the slot's own box block both directions, so a slot can
 * never be blamed for slack it does not have. Background `group` rects and
 * decorative `shape` nodes are not obstacles — the wordmark is meant to sit on
 * top of them.
 */
function annotateBrandFree(bandBox: StockRect, items: PlacedItem[]): void {
  const inner = bandInnerBox(bandBox)
  for (const item of items) {
    if (item.kind !== 'image' || !item.node.isBrandLogo) continue
    const b = item.box
    let right = inner.x + inner.w - (b.x + b.w)
    let left = b.x - inner.x
    let bottom = inner.y + inner.h - (b.y + b.h)
    let top = b.y - inner.y
    for (const other of items) {
      if (other === item) continue
      if (other.kind !== 'text' && other.kind !== 'image') continue
      const o = other.box
      if (o.w <= 0 || o.h <= 0) continue
      if (sharesCrossAxis(b, o, 'y')) {
        if (o.x >= b.x + b.w - 0.5) right = Math.min(right, o.x - (b.x + b.w))
        else if (o.x + o.w <= b.x + 0.5) left = Math.min(left, b.x - (o.x + o.w))
        else { right = Math.min(right, 0); left = Math.min(left, 0) }
      }
      if (sharesCrossAxis(b, o, 'x')) {
        if (o.y >= b.y + b.h - 0.5) bottom = Math.min(bottom, o.y - (b.y + b.h))
        else if (o.y + o.h <= b.y + 0.5) top = Math.min(top, b.y - (o.y + o.h))
        else { bottom = Math.min(bottom, 0); top = Math.min(top, 0) }
      }
    }
    item.brandFree = {
      left: Math.max(0, left),
      right: Math.max(0, right),
      top: Math.max(0, top),
      bottom: Math.max(0, bottom)
    }
  }
}

function finishBand(box: StockRect, items: PlacedItem[]): void {
  fitBandItems(box, items)
  annotateBrandFree(box, items)
}

function placeBandRoot(band: StockBand, box: StockRect, env: StockEnv): PlacedBand {
  const out: PlacedItem[] = []
  if (band.children.length === 1) {
    const child = band.children[0]
    if (!(child.kind === 'text' && child.optional && !stockTokenText(child.token, env))) {
      let size = intrinsic(child, env)
      const m = child.kind === 'space' ? undefined : child.margins
      if (child.kind === 'group' && child.matchWidth) size = { ...size, w: box.w - marginSum(m, 'x') }
      if (child.kind === 'group' && child.matchHeight) size = { ...size, h: box.h - marginSum(m, 'y') }
      const g = (child.kind === 'group' ? child.gravity : child.kind === 'text' ? child.gravity : child.kind === 'image' ? child.gravity : child.kind === 'shape' ? child.gravity : undefined) ?? band.gravity
      const parts = gravityParts(g)
      const contentW = box.w - (m?.left ?? 0) - (m?.right ?? 0)
      const contentH = box.h - (m?.top ?? 0) - (m?.bottom ?? 0)
      const x = box.x + (m?.left ?? 0) + (parts.h === 'right' ? contentW - size.w : parts.h === 'center' ? (contentW - size.w) / 2 : 0)
      const y = box.y + (m?.top ?? 0) + (parts.v === 'bottom' ? contentH - size.h : parts.v === 'center' ? (contentH - size.h) / 2 : 0)
      placeNode(child, { x, y, w: size.w, h: size.h }, env, out)
    }
    finishBand(box, out)
    return { band, box, items: out }
  }
  // OPPO bands with multiple children are effectively a row; a single child group supplies its own orientation.
  const root: StockGroupNode = {
    kind: 'group',
    orientation: 'row',
    width: box.w,
    height: box.h,
    gravity: 'center',
    children: band.children
  }
  layoutChildren(root, box, env, out)
  finishBand(box, out)
  return { band, box, items: out }
}

function bandBox(style: StockStyle, band: StockBand, photo: StockRect, W: number, H: number): StockRect {
  const m = style.margins
  switch (band.direction) {
    case 'top':
      return { x: 0, y: 0, w: W, h: band.thickness ?? m.top }
    case 'bottom':
      return { x: 0, y: photo.y + photo.h, w: W, h: band.thickness ?? m.bottom }
    case 'left':
      return { x: 0, y: photo.y, w: band.thickness ?? m.left, h: photo.h }
    case 'right':
      return { x: photo.x + photo.w, y: photo.y, w: band.thickness ?? m.right, h: photo.h }
    case 'overlay': {
      const w = band.width ?? photo.w
      const h = band.thickness ?? photo.h
      const ox = band.offset?.x ?? 0
      const oy = band.offset?.y ?? 0
      const g = gravityParts(band.gravity)
      let x = photo.x
      let y = photo.y
      if (g.h === 'right') x = photo.x + photo.w - w - ox
      else if (g.h === 'center') x = photo.x + (photo.w - w) / 2 + ox
      else x = photo.x + ox
      if (g.v === 'bottom') y = photo.y + photo.h - h - oy
      else if (g.v === 'center') y = photo.y + (photo.h - h) / 2 + oy
      else y = photo.y + oy
      return { x, y, w, h }
    }
  }
}

/**
 * Rotated bands lay their content out in the pre-rotation rect (the final box
 * with w/h swapped about the same centre for ±90°), exactly like the vendor's
 * "horizontal strip, then rotate the band" semantics. Other angles keep the
 * final rect and are only turned at paint time.
 */
function layoutBoxForBand(band: StockBand, box: StockRect): StockRect {
  const rot = ((band.rotation ?? 0) % 360 + 360) % 360
  if (rot !== 90 && rot !== 270) return box
  const cx = box.x + box.w / 2
  const cy = box.y + box.h / 2
  return { x: cx - box.h / 2, y: cy - box.w / 2, w: box.h, h: box.w }
}

/**
 * Bands whose content is a repositionable watermark: overlay bands (text /
 * graphics laid on top of the photo) and rotated edge strips. Plain
 * `top`/`bottom`/`left`/`right` frame bands are the border, not movable text,
 * so the global offset never touches them.
 */
function isMovableBand(band: StockBand): boolean {
  return band.direction === 'overlay' || !!band.rotation
}

/**
 * Bounding rect of everything a band paints on the canvas — the reference the
 * global offset clamps against. A rotated band's items already live inside its
 * final `box` (they are solved in `layoutBox`, w/h swapped, and turned back into
 * `box` at paint time), so `box` alone is its canvas footprint; an unrotated
 * band paints its items directly and, only if it declares a `background`, the
 * plate at `box`.
 */
function bandVisualExtent(placed: PlacedBand): StockRect {
  if (placed.rotation) return placed.box
  const boxes: StockRect[] = placed.band.background
    ? [placed.box, ...placed.items.map((i) => i.box)]
    : placed.items.map((i) => i.box)
  let rect: StockRect | null = null
  for (const r of boxes) {
    if (r.w <= 0 || r.h <= 0) continue
    if (!rect) {
      rect = { x: r.x, y: r.y, w: r.w, h: r.h }
      continue
    }
    const x1 = Math.min(rect.x, r.x)
    const y1 = Math.min(rect.y, r.y)
    const x2 = Math.max(rect.x + rect.w, r.x + r.w)
    const y2 = Math.max(rect.y + rect.h, r.y + r.h)
    rect = { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }
  }
  return rect ?? placed.box
}

function clampShift(total: number, min: number, max: number): number {
  return max < min ? Math.max(0, min) : Math.min(Math.max(total, min), max)
}

/**
 * Rigidly translate a movable band by an already-clamped (tx,ty): `box`,
 * `layoutBox` and every item shift together, so a rotated band turns about its
 * moved centre — a translation is always canvas-space and never swaps axes.
 */
function translateBand(placed: PlacedBand, tx: number, ty: number): void {
  if (tx === 0 && ty === 0) return
  placed.box = { ...placed.box, x: placed.box.x + tx, y: placed.box.y + ty }
  if (placed.layoutBox) placed.layoutBox = { ...placed.layoutBox, x: placed.layoutBox.x + tx, y: placed.layoutBox.y + ty }
  for (const item of placed.items) item.box = { ...item.box, x: item.box.x + tx, y: item.box.y + ty }
}

/**
 * Rigidly translate a movable band by (dx,dy) in canvas-space layout units,
 * clamped so its visible extent never leaves [0,W]×[0,H]. The clamp is
 * symmetric — the same bound applies at either end of an axis, so pushing the
 * watermark right cannot drive it off the right edge any more than pushing left
 * can drive it off the left.
 */
function nudgeBand(placed: PlacedBand, W: number, H: number, dx: number, dy: number): void {
  const e = bandVisualExtent(placed)
  const tx = clampShift(dx, -e.x, W - (e.x + e.w))
  const ty = clampShift(dy, -e.y, H - (e.y + e.h))
  translateBand(placed, tx, ty)
}

function unionExtent(bands: PlacedBand[]): StockRect | null {
  let rect: StockRect | null = null
  for (const b of bands) {
    const e = bandVisualExtent(b)
    if (!rect) {
      rect = { x: e.x, y: e.y, w: e.w, h: e.h }
      continue
    }
    const x1 = Math.min(rect.x, e.x)
    const y1 = Math.min(rect.y, e.y)
    const x2 = Math.max(rect.x + rect.w, e.x + e.w)
    const y2 = Math.max(rect.y + rect.h, e.y + e.h)
    rect = { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }
  }
  return rect
}

/**
 * Nine-grid anchor picker for stock styles (`env.params.stockAnchor`), the same
 * mental model and the same values as the watermark layer's `Anchor` type
 * ('top-left' … 'bottom-right', plus 'center'). Absent or 'vendor' returns null
 * ⇒ the vendor's own position, which skips every nudge branch and keeps the
 * solve byte-identical to the committed build (hard requirement).
 */
function stockAnchorAxes(value: unknown): { h: 'left' | 'center' | 'right'; v: 'top' | 'center' | 'bottom' } | null {
  const s = String(value ?? 'vendor')
  if (!s || s === 'vendor') return null
  const parts = s.split('-')
  const h = parts.includes('left') ? 'left' : parts.includes('right') ? 'right' : 'center'
  const v = parts.includes('top') ? 'top' : parts.includes('bottom') ? 'bottom' : 'center'
  return { h, v }
}

/**
 * Translate the WHOLE movable watermark group (every overlay / rotated band,
 * rigidly, one shared delta) so the union of its visual extents sits at the
 * selected anchor of the usable canvas — the photo area, i.e. the region the
 * vendor watermark is meant to live on; margin frame bands are not usable.
 * The X/Y fine-tune sliders are then added on top of the anchor shift, and the
 * combined delta passes through the same canvas-bounded clamp `nudgeBand` uses
 * (`[0,W]×[0,H]`), so no anchor + slider combination can leave the canvas.
 * Rotated bands are moved by their final canvas-space `box` (bandVisualExtent
 * returns the post-rotation footprint), so an anchor never swaps their axes.
 */
function anchorGroup(movable: PlacedBand[], photo: StockRect, W: number, H: number, axes: NonNullable<ReturnType<typeof stockAnchorAxes>>, dx: number, dy: number): void {
  const e = unionExtent(movable)
  if (!e) return
  const tx0 =
    axes.h === 'left' ? photo.x - e.x
    : axes.h === 'right' ? photo.x + photo.w - (e.x + e.w)
    : photo.x + (photo.w - e.w) / 2 - e.x
  const ty0 =
    axes.v === 'top' ? photo.y - e.y
    : axes.v === 'bottom' ? photo.y + photo.h - (e.y + e.h)
    : photo.y + (photo.h - e.h) / 2 - e.y
  const tx = clampShift(tx0 + dx, -e.x, W - (e.x + e.w))
  const ty = clampShift(ty0 + dy, -e.y, H - (e.y + e.h))
  for (const b of movable) translateBand(b, tx, ty)
}

export function solveStockLayout(style: StockStyle, env: StockEnv): StockLayout {
  const baseW = style.referenceWidth
  const ratio = env.ph / env.pw
  const basePhotoH = baseW * ratio
  const m = style.margins
  const W = m.left + baseW + m.right
  const H = m.top + basePhotoH + m.bottom
  const photo: StockRect = { x: m.left, y: m.top, w: baseW, h: basePhotoH }
  // Global watermark nudge driven by `env.params` (Inspector sliders).
  // Convention: `stockOffsetXPct` = % of the reference width, `stockOffsetYPct`
  // = % of the photo's short edge, both expressed in layout units so the render
  // `scale` carries them to pixels — as painted, X moves by % of the photo width
  // and Y by % of the photo's short edge. Defaults are absent ⇒ 0/0, which skips
  // the whole nudge branch below and leaves every solved rect byte-identical to
  // the vendor-faithful solve.
  const px = Number(env.params.stockOffsetXPct)
  const py = Number(env.params.stockOffsetYPct)
  const dx = Number.isFinite(px) ? (px / 100) * baseW : 0
  const dy = Number.isFinite(py) ? (py / 100) * Math.min(baseW, basePhotoH) : 0
  const nudged = dx !== 0 || dy !== 0
  // Nine-grid anchor for the stock watermark group ('vendor' / absent ⇒ null ⇒
  // the vendor's own position). Both this and the slider branch are skipped at
  // the default, so the untouched solve stays byte-identical.
  const axes = stockAnchorAxes(env.params.stockAnchor)
  const bands = style.bands.map((band) => {
    const box = bandBox(style, band, photo, W, H)
    let placed: PlacedBand
    if (!band.rotation) {
      placed = placeBandRoot(band, box, env)
    } else {
      const layoutBox = layoutBoxForBand(band, box)
      placed = { ...placeBandRoot(band, layoutBox, env), box, layoutBox, rotation: band.rotation }
    }
    if (!axes && nudged && isMovableBand(band)) nudgeBand(placed, W, H, dx, dy)
    return placed
  })
  if (axes) {
    const movable = bands.filter((b) => isMovableBand(b.band))
    if (movable.length) anchorGroup(movable, photo, W, H, axes, dx, dy)
  }
  return { W, H, photo, bands }
}
