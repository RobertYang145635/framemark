import { setFont, setTracking } from '../draw'
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
  box: StockRect
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

function measureLine(ctx: CanvasRenderingContext2D, text: string, node: StockTextNode): number {
  setTracking(ctx, (node.tracking ?? 0) * node.size)
  setFont(ctx, node.size, stockFontFamily(node.font), node.weight)
  const w = ctx.measureText(text).width
  setTracking(ctx, 0)
  return w
}

function wrapText(ctx: CanvasRenderingContext2D, text: string, node: StockTextNode, maxWidth: number): string[] {
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
      if (line && measureLine(ctx, next, node) > maxWidth) {
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
  const lines = wrapText(ctx, value, node, node.width ?? 0)
  const lineHeight = lineHeightOf(node)
  const textWidth = node.width ?? Math.max(...lines.map((l) => measureLine(ctx, l, node)), 0)
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
    fitBandItems(box, out)
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
  fitBandItems(box, out)
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

export function solveStockLayout(style: StockStyle, env: StockEnv): StockLayout {
  const baseW = style.referenceWidth
  const ratio = env.ph / env.pw
  const basePhotoH = baseW * ratio
  const m = style.margins
  const W = m.left + baseW + m.right
  const H = m.top + basePhotoH + m.bottom
  const photo: StockRect = { x: m.left, y: m.top, w: baseW, h: basePhotoH }
  const bands = style.bands.map((band) => placeBandRoot(band, bandBox(style, band, photo, W, H), env))
  return { W, H, photo, bands }
}
