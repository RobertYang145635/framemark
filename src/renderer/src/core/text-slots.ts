import type { Anchor, ExifData, TextSettings, TextSlot, TextToken } from '../types'
import { exifLine, formatDateOnly, modelWithoutMake } from './exif'
import { titleOf } from './templates'

/** One raw fillText call captured during a template render (canvas pixels). */
export interface TextDraw {
  key: string
  source: TextSlot['source']
  text: string
  hidden: boolean
  x: number
  y: number
  w: number
  h: number
  alpha: number
}

function norm(value: string): string {
  return value.replace(/\s+/g, '').toLowerCase()
}

function titleValue(text: TextSettings, exif: ExifData): string {
  // Same fallback the painter uses; exported from templates.ts so the two can
  // never disagree about which row is the title.
  return titleOf({ text, exif } as unknown as Parameters<typeof titleOf>[0]).trim()
}

/** Which editable setting (or EXIF field) produced this painted line. */
export function classifyTextSource(
  raw: string,
  text: TextSettings,
  exif: ExifData
): TextSlot['source'] {
  const hay = norm(raw)
  const tokens: [TextToken, string][] = [
    ['title', titleValue(text, exif)],
    ['subtitle', text.subtitle.trim()],
    ['signature', text.signature.trim()],
    ['customLine', text.customLine.trim()],
    ['location', (text.location ?? '').trim()]
  ]
  const tokenHit = tokens
    .filter(([, value]) => value && hay.includes(norm(value)))
    .sort((a, b) => norm(b[1]).length - norm(a[1]).length)[0]
  if (tokenHit) return tokenHit[0]
  const flags: [TextSlot['source'], string][] = [
    ['make', exif.make],
    ['model', modelWithoutMake(exif.make, exif.model)],
    ['lens', exif.lens],
    ['exif', exifLine(exif)],
    ['date', formatDateOnly(exif.dateTime)]
  ]
  const hits = flags.filter(([, value]) => value && hay.includes(norm(value)))
  return hits.length === 1 ? hits[0][0] : 'photo'
}

/**
 * Records every `fillText` the hand template makes. Patching the one method on
 * the canvas context keeps every other Canvas call untouched (a Proxy wrapper
 * trips Chromium's "Illegal invocation" on some native methods). Per-line
 * overrides and hidden lines are applied here, before painting, keyed by
 * `<source>#<occurrence>` so the 文字 pane can edit any line positionally.
 * Returns a restore function the caller must run after the template render.
 */
export function attachTextRecorder(
  ctx: CanvasRenderingContext2D,
  sink: TextDraw[],
  settings: TextSettings,
  exif: ExifData,
  scope: string
): () => void {
  const target = ctx
  const original = target.fillText
  const counts = new Map<string, number>()
  const hidden = new Set(settings.hiddenLines ?? [])
  target.fillText = function (raw: string, x: number, y: number, maxWidth?: number) {
    if (typeof raw !== 'string' || !raw.trim()) {
      if (maxWidth === undefined) original.call(target, raw, x, y)
      else original.call(target, raw, x, y, maxWidth)
      return
    }
    const source = classifyTextSource(raw, settings, exif)
    const index = counts.get(source) ?? 0
    counts.set(source, index + 1)
    const key = `${scope}#${source}#${index}`
    const text = settings.lineText?.[key] ?? raw
    const isHidden = hidden.has(key) || !text
    const size = parseFloat(target.font) || 12
    if (!isHidden) {
      if (maxWidth === undefined) original.call(target, text, x, y)
      else original.call(target, text, x, y, maxWidth)
    }
    recordTextDraw(target, { key, source, text, hidden: isHidden }, x, y, size, sink)
  } as typeof target.fillText
  return () => {
    target.fillText = original
  }
}

function recordTextDraw(
  ctx: CanvasRenderingContext2D,
  line: Pick<TextDraw, 'key' | 'source' | 'text' | 'hidden'>,
  x: number,
  y: number,
  localSize: number,
  sink: TextDraw[]
): void {
  if (ctx.globalAlpha < 0.15) return
  const m = ctx.getTransform()
  const scaleX = Math.hypot(m.a, m.b) || 1
  const scaleY = Math.hypot(m.c, m.d) || 1
  const wx = m.a * x + m.c * y + m.e
  const wy = m.b * x + m.d * y + m.f
  const width = Math.max(2, ctx.measureText(line.text || ' ').width * scaleX)
  const size = localSize * scaleY
  if (!Number.isFinite(width) || !Number.isFinite(size) || size < 5) return
  const align = ctx.textAlign
  const baseline = ctx.textBaseline
  const left = align === 'center' ? wx - width / 2 : align === 'right' || align === 'end' ? wx - width : wx
  const top = baseline === 'middle' ? wy - size / 2 : baseline === 'top' ? wy : wy - size * 0.8
  sink.push({ ...line, x: left, y: top, w: width, h: size, alpha: ctx.globalAlpha })
}

function anchorOf(cx: number, cy: number, W: number, H: number): Anchor {
  const col = cx < W / 3 ? 'left' : cx < (W * 2) / 3 ? 'center' : 'right'
  const row = cy < H / 3 ? 'top' : cy < (H * 2) / 3 ? 'middle' : 'bottom'
  if (row === 'middle' && col === 'center') return 'center'
  return `${row}-${col}` as Anchor
}

/** Turns the captured draws into the positional rows the 文字 pane renders. */
export function attributeTextSlots(
  draws: readonly TextDraw[],
  W: number,
  H: number
): TextSlot[] {
  const seen = new Set<string>()
  const slots: TextSlot[] = []
  for (const draw of draws) {
    const anchor = anchorOf(draw.x + draw.w / 2, draw.y + draw.h / 2, W, H)
    if (seen.has(draw.key)) continue
    seen.add(draw.key)
    slots.push({
      id: draw.key,
      key: draw.key,
      anchor,
      text: draw.text,
      source: draw.source,
      hidden: draw.hidden,
      x: draw.x,
      y: draw.y,
      w: draw.w,
      h: draw.h
    })
  }
  return slots.sort((a, b) => a.y - b.y || a.x - b.x)
}

/**
 * Pre-v4.1 designs keyed line overrides `<source>#<n>`; the painter now writes
 * `<variant>#<source>#<n>` so two cards that share a base template cannot read
 * each other's overrides. Idempotent: keys that already carry the scope stay.
 */
export function migrateTextLineKeys(
  text: TextSettings,
  scope: string
): TextSettings {
  if (!text.lineText && !text.hiddenLines) return text
  const prefix = `${scope}#`
  const fix = (key: string) => (key.startsWith(prefix) ? key : `${prefix}${key}`)
  return {
    ...text,
    ...(text.lineText
      ? { lineText: Object.fromEntries(Object.entries(text.lineText).map(([k, v]) => [fix(k), v])) }
      : {}),
    ...(text.hiddenLines ? { hiddenLines: text.hiddenLines.map(fix) } : {})
  }
}
