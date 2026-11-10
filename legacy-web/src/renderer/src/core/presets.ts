import type { Design, ExportSettings, ParamBucket, ParamOverrides, Params, TemplateVariant, TextSettings, WatermarkSettings } from '../types'
import { getTemplate } from './templates'
import { DEFAULT_VARIANT_ID, getVariant } from './variants'
import { migrateTextLineKeys } from './text-slots'

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

/* -------------------------------------------------------------------------
 * Per-style parameter buckets
 *
 * `design.params` stays the single flat object every renderer already reads
 * (templates.ts / core/system read ~276 sites of `env.params.<key>`; the
 * `全部参数` panel, `PreviewStage` and two e2e scripts also read it by path,
 * e.g. `JSON.parse(localStorage['framemark.lastDesign.v1']).params.footerTextScale`).
 * `design.paramOverrides` is the NEW sparse store of "what the user changed for
 * THIS style". `params` is kept materialised alongside it, so no reader changes.
 *
 * Invariants (the byte-baseline guarantee rests on these):
 *   I1  a bucket key exists only while it differs from the declared value;
 *   I2  `paramOverrides` is omitted entirely while nothing is overridden, so a
 *       pre-patch and a post-patch persisted design are the same JSON;
 *   I3  `params === declared(variant) ⊕ paramOverrides[bucket]` after every write.
 * ------------------------------------------------------------------------- */

/** The style identity that owns the bucket: variantId when known, else templateId. */
export function paramBucketKey (design: Pick<Design, 'variantId' | 'templateId'>): string {
  return design.variantId || design.templateId
}

/** What the style declares before any user edit — same merge `setVariant` does. */
export function declaredParams (design: Pick<Design, 'variantId' | 'templateId'>): Params {
  const variant = getVariant(design.variantId)
  const base = getTemplate(variant.base || design.templateId)
  return { ...base.defaults, ...(variant.params ?? {}) } as Params
}

export function isParamOverrides (value: unknown): value is ParamOverrides {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return Object.values(value as Record<string, unknown>).every(
    (bucket) => Boolean(bucket) && typeof bucket === 'object' && !Array.isArray(bucket)
  )
}

/**
 * Keys of `params` that are NOT what `declared` says ⇒ the genuine user overrides.
 *
 * `floor` is used ONLY by the one-time legacy migration below. It is the BASE
 * TEMPLATE's declared defaults, and it carries a specific meaning there: a value
 * that restates a base default while the variant declares something else cannot be
 * proven to be a user edit. Every pre-bucket design the app itself produced went
 * through `makeDesignFromVariant`/`setVariant`, both of which merge
 * `{...base.defaults, ...variant.params}` — so such a key can only come from a
 * design built WITHOUT the variant overlay (an empty or foreign `params`, e.g. a
 * hand-seeded `localStorage` design or a preset carrying a partial carrier).
 * Attributing it would silently re-declare that card's geometry: this is exactly
 * what turned wm-classic's footer band from the audited 11.1%W (reference
 * 10.94–11.13%W, `original-47/specs-after/wm-classic.json`) into the base
 * template's 7.5%W and emptied its `slots`/`frame`. In-session writes keep using
 * the un-filtered rule (`writeParam` → `pruneOverrides`), so a user who really
 * does set a value back onto a base default still keeps it.
 */
export function diffOverrides (params: Params, declared: Params, floor?: Params): ParamBucket {
  const out: ParamBucket = {}
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined) continue
    if (k in declared && declared[k] === v) continue
    if (floor && k in floor && floor[k] === v) continue
    out[k] = v
  }
  return out
}

/** declared ⊕ this style's bucket. Empty/absent bucket ⇒ declared, untouched. */
export function materializeParams (declared: Params, overrides: ParamOverrides | undefined, bucket: string): Params {
  const own = overrides?.[bucket]
  if (!own || typeof own !== 'object') return declared
  return { ...declared, ...own } as Params
}

/** drop keys whose value fell back to the declared one, and empty buckets with them */
export function pruneOverrides (overrides: ParamOverrides, declared: Params, bucket: string): ParamOverrides | undefined {
  const own = overrides[bucket]
  if (!own) return Object.keys(overrides).length ? overrides : undefined
  const kept: ParamBucket = {}
  for (const [k, v] of Object.entries(own)) {
    if (k in declared && declared[k] === v) continue
    kept[k] = v
  }
  const next: ParamOverrides = { ...overrides }
  if (Object.keys(kept).length) next[bucket] = kept
  else delete next[bucket]
  return Object.keys(next).length ? next : undefined
}

export function defaultDesign(): Design {
  return makeDesignFromVariant(getVariant(DEFAULT_VARIANT_ID))
}

export function hydrateDesign(input: Partial<Design> | null | undefined): Design {
  const fallback = defaultDesign()
  if (!input || typeof input !== 'object') return fallback
  const base = getTemplate(input.templateId || fallback.templateId)
  const variantId = input.variantId ?? fallback.variantId
  const bucket = paramBucketKey({ variantId, templateId: base.id })
  const declared = declaredParams({ variantId, templateId: input.templateId || fallback.templateId })
  // today's flat merge, kept verbatim as the legacy carrier being read
  const legacyParams = { ...base.defaults, ...(input.params ?? {}) } as Params
  // Migrate a pre-bucket design once, at load time: attribute the keys that differ
  // from what THIS style declares to this style's bucket. Keys equal to the declared
  // value are dropped, so the sparse invariant I1 survives the upgrade, and keys that
  // merely restate a BASE TEMPLATE default are not attributed either (see
  // `diffOverrides` — that is what kept a seeded/foreign flat `params` from
  // over-declaring wm-classic's footer).
  const migrated = !isParamOverrides(input.paramOverrides)
  const overrides: ParamOverrides = migrated
    ? { [bucket]: diffOverrides(legacyParams, declared, base.defaults) }
    : (input.paramOverrides as ParamOverrides)
  // `params` is always declared ⊕ this style's bucket (invariant I3) — for a legacy
  // carrier that reproduces `legacyParams` exactly, because every design the app
  // wrote before bucketing already carried `{...base.defaults, ...variant.params}`.
  const params = materializeParams(declared, overrides, bucket)
  const pruned = pruneOverrides(overrides, declared, bucket)
  return {
    templateId: base.id,
    variantId,
    ratio: input.ratio ?? 'original',
    params,
    ...(pruned ? { paramOverrides: pruned } : {}),
    text: migrateTextLineKeys({ ...DEFAULT_TEXT, ...(input.text ?? {}) }, variantId || base.id),
    watermark: { ...DEFAULT_WATERMARK, ...(input.watermark ?? {}) },
    logo: input.logo ?? null,
    avatar: input.avatar ?? null,
    // Additive metadata (see types.ts). Carried through verbatim only when the
    // carrier actually has it, so legacy designs/presets hydrate to the same
    // object shape they always did and `defaultDesign()` stays free of it.
    ...(input.brandVersion !== undefined ? { brandVersion: input.brandVersion } : {}),
    export: { ...DEFAULT_EXPORT, ...(input.export ?? {}) }
  }
}

export function cloneDesign(design: Design): Design {
  return JSON.parse(JSON.stringify(design)) as Design
}

/**
 * Presets are portable across machines/users: never persist a machine-specific
 * export directory. All other export settings (format/quality/...) are kept.
 *
 * `paramOverrides` rides along for free (JSON deep clone): a preset therefore
 * carries every style's bucket, so "save preset -> switch style -> reload preset"
 * restores each style's overrides separately instead of flattening them onto the
 * one style the preset happens to point at.
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
