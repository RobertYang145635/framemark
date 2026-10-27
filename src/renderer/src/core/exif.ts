import type { ExifData } from '../types'

const pad = (n: number) => String(n).padStart(2, '0')

export function formatDate(value: unknown, style: 'dash' | 'dot' | 'compact' = 'dash'): string {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(/^(\d{4}):(\d{2}):(\d{2})/, '$1-$2-$3'))
  if (Number.isNaN(d.getTime())) return String(value)
  const date = style === 'dot' ? `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())}` : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  if (style === 'compact') return date
  return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function formatDateOnly(value: unknown): string {
  return formatDate(value, 'compact')
}

export function formatTime(value: unknown): string {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(/^(\d{4}):(\d{2}):(\d{2})/, '$1-$2-$3'))
  if (Number.isNaN(d.getTime())) return ''
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatAperture(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(Number(value))) return ''
  const n = Number(value)
  return `F${Number.isInteger(n) ? n : n.toFixed(1)}`
}

export function formatShutter(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(Number(value))) return ''
  const n = Number(value)
  if (n >= 1) return `${Number.isInteger(n) ? n : n.toFixed(1)}s`
  const denom = Math.round(1 / n)
  return `1/${denom}s`
}

export function normalizeExif(
  raw: Record<string, any> | null | undefined,
  width: number,
  height: number,
  fallbackName = ''
): ExifData {
  raw = raw || {}
  const dateValue = raw.DateTimeOriginal || raw.CreateDate || raw.ModifyDate || raw.DateTime || ''
  const make = String(raw.Make || '').trim()
  const model = String(raw.Model || raw.ModelName || '').trim()
  const lens = String(raw.LensModel || raw.Lens || raw.LensInfo || '').trim()
  // Vendor samples print the 35mm equivalent (23mm), not the physical focal
  // length (3.5mm). Prefer 0xA405 when present; keep the writeback path from
  // treating it as 0x920A (see focalEquivalent in the exporter/main). exifr may
  // hand back a rational pair for this tag, so normalize arrays before Number().
  const num = (v: unknown): number => {
    const pair = v as { length?: number; [i: number]: number } | null
    if (pair && typeof pair.length === 'number' && pair.length === 2) {
      const d = Number(pair[1]) || 1
      return Number(pair[0]) / d
    }
    return Number(v)
  }
  const focal35 = num(raw.FocalLengthIn35mmFormat) || 0
  const focalPhysical = num(raw.FocalLength) || 0
  const focalEquivalent = focal35 > 0
  const focal = focal35 || focalPhysical || null
  const aperture = Number(raw.FNumber) || Number(raw.ApertureValue) || null
  const shutter = Number(raw.ExposureTime) || null
  const iso = Number(raw.ISO) || Number(raw.ISOSpeedRatings) || null
  const hasAny = Boolean(make || model || lens || focal || aperture || shutter || iso || dateValue)
  return {
    make,
    model,
    lens,
    focal: Number.isFinite(focal as number) ? (focal as number) : null,
    ...(focalEquivalent ? { focalEquivalent: true } : {}),
    aperture: Number.isFinite(aperture as number) ? (aperture as number) : null,
    shutter: Number.isFinite(shutter as number) ? (shutter as number) : null,
    iso: Number.isFinite(iso as number) ? (iso as number) : null,
    dateTime: formatDate(dateValue),
    width,
    height,
    hasAny,
    raw,
    ...(fallbackName ? {} : {})
  }
}

export function exifLine(exif: ExifData, opts: { focal?: boolean; aperture?: boolean; shutter?: boolean; iso?: boolean; separator?: string } = {}): string {
  const parts: string[] = []
  const sep = opts.separator ?? '  '
  if (opts.focal !== false && exif.focal) parts.push(`${Math.round(exif.focal)}mm`)
  if (opts.aperture !== false && exif.aperture) parts.push(formatAperture(exif.aperture))
  if (opts.shutter !== false && exif.shutter) parts.push(formatShutter(exif.shutter))
  if (opts.iso !== false && exif.iso) parts.push(`ISO${exif.iso}`)
  return parts.join(sep)
}

export function modelWithoutMake(make: string, model: string): string {
  const m = String(make || '').trim()
  const d = String(model || '').trim()
  if (!d) return ''
  if (m && d.toLowerCase().startsWith(`${m.toLowerCase()} `)) return d.slice(m.length).trim()
  return d
}

export function cameraLine(exif: ExifData, fallback = 'FRAMEMARK'): string {
  return [exif.make, modelWithoutMake(exif.make, exif.model)].filter(Boolean).join('  ') || fallback
}

export function resolveTokens(
  template: string,
  exif: ExifData,
  vars: { filename: string; width: number; height: number; templateName: string; date: Date }
): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => {
    switch (key) {
      case 'make': return exif.make
      case 'model': return exif.model
      case 'lens': return exif.lens
      case 'focal': return exif.focal ? `${Math.round(exif.focal)}mm` : ''
      case 'aperture': return formatAperture(exif.aperture)
      case 'shutter': return formatShutter(exif.shutter)
      case 'iso': return exif.iso ? String(exif.iso) : ''
      case 'date': return formatDateOnly(exif.dateTime) || formatDate(vars.date, 'compact')
      case 'time': return formatTime(exif.dateTime) || `${pad(vars.date.getHours())}:${pad(vars.date.getMinutes())}`
      case 'width': return String(vars.width)
      case 'height': return String(vars.height)
      case 'filename': return vars.filename
      case 'template': return vars.templateName
      case 'exif': return exifLine(exif)
      case 'camera': return cameraLine(exif, '')
      default: return ''
    }
  })
}

export function exifSummary(exif: ExifData): string {
  return [cameraLine(exif, ''), exifLine(exif)].filter(Boolean).join(' · ')
}
