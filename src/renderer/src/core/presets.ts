import type { Design, ExportSettings, TemplateVariant, TextSettings, WatermarkSettings } from '../types'
import { getTemplate } from './templates'
import { DEFAULT_VARIANT_ID, getVariant } from './variants'

export const DEFAULT_TEXT: TextSettings = {
  title: '',
  subtitle: '',
  signature: '',
  customLine: '',
  location: '',
  showMake: true,
  showModel: true,
  showLens: true,
  showExif: true,
  showDate: true,
  showLogo: true,
  fontScale: 1
}

export const DEFAULT_WATERMARK: WatermarkSettings = {
  enabled: false,
  asset: null,
  anchor: 'bottom-right',
  offsetX: 3,
  offsetY: 3,
  widthPct: 14,
  opacity: 0.9,
  rotation: 0,
  tile: false,
  gapPct: 4,
  target: 'photo'
}

export const DEFAULT_EXPORT: ExportSettings = {
  format: 'jpeg',
  quality: 100,
  longEdge: 0,
  removeGps: true,
  filenamePattern: '{name}-{template}',
  outputDir: ''
}

export function makeDesignFromVariant(variant: TemplateVariant): Design {
  const base = getTemplate(variant.base)
  return {
    templateId: base.id,
    variantId: variant.id,
    ratio: 'original',
    params: { ...base.defaults, ...(variant.params ?? {}) } as import('../types').Params,
    text: { ...DEFAULT_TEXT, ...(variant.text ?? {}) },
    watermark: { ...DEFAULT_WATERMARK },
    logo: null,
    avatar: null,
    export: { ...DEFAULT_EXPORT }
  }
}

export function defaultDesign(): Design {
  return makeDesignFromVariant(getVariant(DEFAULT_VARIANT_ID))
}

export function hydrateDesign(input: Partial<Design> | null | undefined): Design {
  const fallback = defaultDesign()
  if (!input || typeof input !== 'object') return fallback
  const base = getTemplate(input.templateId || fallback.templateId)
  return {
    templateId: base.id,
    variantId: input.variantId ?? fallback.variantId,
    ratio: input.ratio ?? 'original',
    params: { ...base.defaults, ...(input.params ?? {}) } as import('../types').Params,
    text: { ...DEFAULT_TEXT, ...(input.text ?? {}) },
    watermark: { ...DEFAULT_WATERMARK, ...(input.watermark ?? {}) },
    logo: input.logo ?? null,
    avatar: input.avatar ?? null,
    export: { ...DEFAULT_EXPORT, ...(input.export ?? {}) }
  }
}

export function cloneDesign(design: Design): Design {
  return JSON.parse(JSON.stringify(design)) as Design
}

/**
 * Presets are portable across machines/users: never persist a machine-specific
 * export directory. All other export settings (format/quality/...) are kept.
 */
export function portablePresetDesign(design: Design): Design {
  const next = cloneDesign(design)
  next.export.outputDir = ''
  return next
}

export function isValidDesign(value: unknown): value is Design {
  if (!value || typeof value !== 'object') return false
  const d = value as Partial<Design>
  return typeof d.templateId === 'string' && typeof d.params === 'object'
}
