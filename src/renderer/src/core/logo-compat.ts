/**
 * Logo version compatibility helpers (Phase 1b-1).
 *
 * Pure lookup/decision functions: no React, no canvas, no scene/paint imports.
 * They answer the three questions the UI and store need during the transition
 * from the legacy `assets/logos` picker to the version catalog:
 *
 *   1. what surface is this design painted on?          (`surfaceForDesign`)
 *   2. can a version/cut live on that surface?          (`versionCompatible`, `cutForVersionSurface`)
 *   3. what should replace a pick that cannot?          (`fallbackVersionForSurface`, `autoLogoVersionForBrand`)
 *
 * Everything here is additive: callers may keep using `logo`/`brandLogoFile`
 * untouched (the renderer enforcement slice comes later).
 */
import type { BrandVersionRef, Design } from '../types'
import type { LogoCut, LogoVersion } from './logo-catalog'
import {
  LOGO_BRANDS,
  LOGO_CUT_BY_ASSET_KEY,
  LOGO_VERSIONS,
  bestCutForSurface,
  cutForAssetKey,
  versionForAssetKey
} from './logo-catalog'
import { themeOf } from './draw'
import { getStockStyle } from './system/styles'

export type Surface = 'light' | 'dark'

interface Rgba {
  r: number
  g: number
  b: number
  a: number
}

function clamp255(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)))
}

function parseChannel(raw: string): number | null {
  const part = raw.trim()
  if (!part) return null
  if (part.endsWith('%')) {
    const pct = Number(part.slice(0, -1))
    return Number.isFinite(pct) ? clamp255((pct / 100) * 255) : null
  }
  const n = Number(part)
  return Number.isFinite(n) ? clamp255(n) : null
}

function parseAlpha(raw: string | undefined): number {
  if (raw === undefined) return 1
  const part = raw.trim()
  if (!part) return 1
  if (part.endsWith('%')) {
    const pct = Number(part.slice(0, -1))
    return Number.isFinite(pct) ? Math.max(0, Math.min(1, pct / 100)) : 1
  }
  const n = Number(part)
  return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 1
}

function parseColor(input: string | undefined): Rgba | null {
  if (!input) return null
  const value = input.trim().toLowerCase()
  if (!value) return null
  if (value === 'white') return { r: 255, g: 255, b: 255, a: 1 }
  if (value === 'black') return { r: 0, g: 0, b: 0, a: 1 }

  if (value.startsWith('#')) {
    const hex = value.slice(1)
    if (!/^[0-9a-f]+$/.test(hex)) return null
    const expand = (h: string): number => parseInt(h.length === 1 ? h + h : h, 16)
    if (hex.length === 3 || hex.length === 4) {
      return {
        r: expand(hex[0]),
        g: expand(hex[1]),
        b: expand(hex[2]),
        a: hex.length === 4 ? expand(hex[3]) / 255 : 1
      }
    }
    if (hex.length === 6 || hex.length === 8) {
      // Stock theme backgrounds use Android ARGB (`#AARRGGBB`, see
      // `paint.ts:themeBackground()`), so the 8-digit form is read the same way
      // `paint.ts:parseSolid()` reads it — otherwise a translucent white card
      // (`#00FFFFFF`) would be "light" here and "dark" in the painter.
      const alpha = hex.length === 8 ? parseInt(hex.slice(0, 2), 16) / 255 : 1
      const rgb = hex.length === 8 ? hex.slice(2) : hex
      return {
        r: parseInt(rgb.slice(0, 2), 16),
        g: parseInt(rgb.slice(2, 4), 16),
        b: parseInt(rgb.slice(4, 6), 16),
        a: alpha
      }
    }
    return null
  }

  const fn = value.match(/^rgba?\(([^)]*)\)$/)
  if (!fn) return null
  // Accept both `rgb(r, g, b[, a])` and `rgb(r g b / a)`.
  const body = fn[1].replace(/\//g, ' ')
  const parts = body.includes(',')
    ? body.split(',').map((p) => p.trim()).filter(Boolean)
    : body.split(/\s+/).filter(Boolean)
  if (parts.length < 3) return null
  const r = parseChannel(parts[0])
  const g = parseChannel(parts[1])
  const b = parseChannel(parts[2])
  if (r === null || g === null || b === null) return null
  return { r, g, b, a: parseAlpha(parts[3]) }
}

/**
 * Which logo ink a background needs. Conservative on purpose: an unparseable
 * colour, or a translucent one (< 0.4 alpha), is treated as DARK so callers use
 * the white logo — invisible-ish white on a light card is a far smaller defect
 * than black ink vanishing into a dark one.
 *
 * The threshold is EXACTLY `paint.ts:lightSurface()`: simple BT.601 luma
 * `(0.299r + 0.587g + 0.114b) / 255 > 0.7`, alpha < 0.4 ⇒ dark. Using a WCAG
 * relative-luminance cut here disagreed with the painter on mid-tones, so
 * `surfaceForDesign()` and `brandDark` could pick opposite tones for one card.
 * Only the parser below is broader (it also accepts `rgb()` strings).
 */
export function surfaceFromBackground(bg: string | undefined): Surface {
  const c = parseColor(bg)
  if (!c) return 'dark'
  if (c.a < 0.4) return 'dark'
  const luma = (c.r * 0.299 + c.g * 0.587 + c.b * 0.114) / 255
  return luma > 0.7 ? 'light' : 'dark'
}

/**
 * The surface the logo will sit on. Stock (`systemCamera`) styles paint their own
 * bg via a theme, everything else uses the hand-card theme palette. `paint.ts`
 * is deliberately NOT imported: this must stay usable from the store before a
 * render exists.
 */
export function surfaceForDesign(design: Design): Surface {
  if (design.templateId === 'systemCamera') {
    const style = getStockStyle(String(design.params.systemStyle ?? ''))
    if (style) {
      const theme = style.themes?.find(
        (t) => t.id === String(design.params.theme ?? style.defaultTheme)
      )
      return surfaceFromBackground(theme?.background ?? style.background)
    }
  }
  return surfaceFromBackground(themeOf(design.params).bg)
}

/** A version is usable on a surface when it declares that surface or `both`. */
export function versionCompatible(version: LogoVersion, surface: Surface): boolean {
  if (version.surface === 'none') return false
  return version.surface === 'both' || version.surface === surface
}

/**
 * A single cut is usable on a surface when it was cut for it (or `both`) and its
 * ink cannot disappear into the background. `ambiguous`/`color` ink is allowed.
 */
export function cutFitsSurface(cut: LogoCut, surface: Surface): boolean {
  if (cut.surface !== 'both' && cut.surface !== surface) return false
  if (surface === 'dark') return cut.ink !== 'dark'
  return cut.ink !== 'light'
}

/**
 * Best cut of a version for a surface, re-validated: the catalog's ranking can
 * return a `both` cut whose ink is wrong for the surface, so the winner is only
 * accepted when `cutFitsSurface` agrees; otherwise the first sane cut wins.
 */
export function cutForVersionSurface(version: LogoVersion, surface: Surface): LogoCut | null {
  const best = bestCutForSurface(version, surface)
  if (best && cutFitsSurface(best, surface)) return best
  return version.cuts.find((cut) => cutFitsSurface(cut, surface)) ?? null
}

/** Publishable pointer for a version on a surface, or null when nothing fits. */
export function refForVersion(version: LogoVersion, surface: Surface): BrandVersionRef | null {
  const cut = cutForVersionSurface(version, surface)
  if (!cut) return null
  return {
    brandKey: version.brandKey,
    versionId: version.id,
    cutId: cut.id,
    assetKey: cut.assetKey,
    file: cut.assetKey,
    ink: cut.ink,
    surface: cut.surface,
    name: version.name
  }
}

/** Bare registry stem: strips a path, a `logo-` prefix and a bitmap extension. */
function cleanAssetKey(file: string | null | undefined): string {
  if (!file) return ''
  const base = String(file).replace(/\\/g, '/').split('/').pop() ?? ''
  return base.replace(/^logo-/, '').replace(/\.(webp|png|jpe?g)$/i, '').trim()
}

/** Catalog lookup by asset key / legacy file stem, then the surface-safe ref. */
export function refFromAssetKey(assetKey: string | null | undefined, surface: Surface): BrandVersionRef | null {
  const key = cleanAssetKey(assetKey)
  if (!key) return null
  const version = versionForAssetKey(key)
  if (!version) return null
  return refForVersion(version, surface)
}

/** Brand key behind an asset key / legacy file stem, or null when unknown. */
export function brandKeyForAssetKey(assetKey: string | null | undefined): string | null {
  const key = cleanAssetKey(assetKey)
  if (!key) return null
  return versionForAssetKey(key)?.brandKey ?? null
}

/**
 * The light/dark surface a cut is meant for. `both` cannot be resolved from the
 * cut alone, so its `previewSurface` (the design's intended viewing surface) is
 * returned as a hint — callers that need a hard answer decide themselves.
 */
export function surfaceForCut(cut: LogoCut): Surface {
  if (cut.surface === 'light' || cut.surface === 'dark') return cut.surface
  return cut.previewSurface
}

/** Strips parenthetical colour/era suffixes and normalises spacing/case. */
function normalizeVersionName(name: string): string {
  return name
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/[·•]/g, ' ')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * The same design in the other surface's tone, if the catalog knows one: same
 * brand, same `kind`, and the same normalised name (e.g. "Logo (white)" →
 * "logo"). Never crosses brands; READY wins over LEGACY only by catalog order.
 */
export function fallbackVersionForSurface(version: LogoVersion, surface: Surface): LogoVersion | null {
  const target = normalizeVersionName(version.name)
  const kind = version.kind ?? ''
  return (
    LOGO_VERSIONS.find(
      (candidate) =>
        candidate.id !== version.id &&
        candidate.brandKey === version.brandKey &&
        (candidate.kind ?? '') === kind &&
        normalizeVersionName(candidate.name) === target &&
        (candidate.status === 'READY' || candidate.status === 'LEGACY') &&
        versionCompatible(candidate, surface) &&
        cutForVersionSurface(candidate, surface) !== null
    ) ?? null
  )
}

/**
 * Automatic pick for a brand on a surface: the brand's canonical version when
 * it fits, else the first READY/LEGACY version that does. This is what the
 * auto-match path will use once it moves off the legacy `pickVariant`.
 *
 * `versionCompatible()` alone is not enough: a `both` version can still ship no
 * cut that fits the surface (e.g. only dark-ink cuts declared `both`), so the
 * candidate must also resolve through `cutForVersionSurface()`.
 */
export function autoLogoVersionForBrand(brandKey: string, surface: Surface): LogoVersion | null {
  const brand = LOGO_BRANDS.find((b) => b.key === brandKey)
  if (brand) {
    const canonical = LOGO_VERSIONS.find((v) => v.id === brand.canonicalVersion)
    if (canonical && versionCompatible(canonical, surface) && cutForVersionSurface(canonical, surface)) {
      return canonical
    }
  }
  return (
    LOGO_VERSIONS.find(
      (v) =>
        v.brandKey === brandKey &&
        (v.status === 'READY' || v.status === 'LEGACY') &&
        versionCompatible(v, surface) &&
        cutForVersionSurface(v, surface) !== null
    ) ?? null
  )
}

/** Every catalog version of a brand, in catalog order. */
export function allVersionsForBrand(brandKey: string): LogoVersion[] {
  return LOGO_VERSIONS.filter((v) => v.brandKey === brandKey)
}

/**
 * Cut behind a picked asset key or a legacy file name (`fushi_black.webp`,
 * `logo-fushi_black`). Used by the store to upgrade old picks to version refs.
 */
export function cutKeyForLegacyFile(file: string | null | undefined): LogoCut | null {
  if (!file) return null
  const raw = String(file)
  const clean = cleanAssetKey(raw)
  return cutForAssetKey(raw) ?? LOGO_CUT_BY_ASSET_KEY[raw]?.cut ?? (clean ? cutForAssetKey(clean) : null)
}
