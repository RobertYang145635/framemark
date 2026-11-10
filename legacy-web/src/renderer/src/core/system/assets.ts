import type { StockBrandEnv, StockImageNode, StockNode, StockStyle } from './ir'
import type { BrandVersionRef, Params, TemplateRenderEnv } from '../../types'
import { BRANDS, autoLogo, logoCutForFile, logoUrlFor, matchBrand } from '../brands'
import { LOGO_VERSION_BY_ID, versionForAssetKey } from '../logo-catalog'
import { cutFitsSurface, cutForVersionSurface } from '../logo-compat'
import { createCanvas, loadImage } from '../images'
import { ensureSystemFonts } from './fonts'

const ASSET_URLS = import.meta.glob('../../assets/system/**/*.{webp,png}', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>

const URL_BY_NAME = new Map<string, string>()
for (const [rawPath, url] of Object.entries(ASSET_URLS)) {
  const normalized = rawPath.replace(/\\/g, '/')
  const marker = 'assets/system/'
  const cut = normalized.indexOf(marker)
  const rel = cut >= 0 ? normalized.slice(cut + marker.length) : normalized
  URL_BY_NAME.set(rel, url)
  const name = rel.split('/').pop() ?? rel
  URL_BY_NAME.set(name, url)
  URL_BY_NAME.set(name.replace(/\.[^.]+$/, ''), url)
}

export function stockAssetUrl(file: string): string | undefined {
  return URL_BY_NAME.get(file) ?? URL_BY_NAME.get(file.replace(/\.[^.]+$/, ''))
}

/* ------------------------------------------------------------------ *
 * Brand slots (isBrandLogo) — the phone wordmark follows the photo.
 *
 * A stock image node flagged `isBrandLogo: true` is a *slot*: the vendor
 * config fills it at runtime (`path=${logo}` in the Xiaomi watermarks), so
 * the bitmap that shipped in the vendor folder is only one possible value.
 * `prepareStockStyle()` therefore preloads the photo's own brand wordmark
 * under a synthetic `__brand__<file>` key and `paint.ts` prefers that bitmap
 * over the baked one. When the photo's brand has no usable wordmark the slot
 * renders nothing — falling back to the vendor's own mark is what made a
 * Panasonic photo read "xiaomi DC-S1".
 * ------------------------------------------------------------------ */

/** Prefix of the synthetic image keys that hold brand-slot bitmaps. */
export const BRAND_SLOT_PREFIX = '__brand__'
/** Anything at least this wide is read as a horizontal wordmark, not a square lockup. */
export const WORDMARK_MIN_ASPECT = 1.6

export function brandSlotKey(file: string): string {
  return `${BRAND_SLOT_PREFIX}${file}`
}

export function isBrandSlotKey(key: string): boolean {
  return key.startsWith(BRAND_SLOT_PREFIX)
}

export interface BrandSlotChoice {
  /** logo registry file name, e.g. `panasionic_white` */
  file: string
  img: HTMLImageElement
  /** intrinsic width / height of the chosen bitmap */
  aspect: number
}

/** Every image node that is a phone-brand slot, in document order. */
export function collectBrandNodes(style: StockStyle): StockImageNode[] {
  const found: StockImageNode[] = []
  const walk = (nodes: StockNode[]): void => {
    for (const node of nodes) {
      if (node.kind === 'image') {
        if (node.isBrandLogo) found.push(node)
      } else if (node.kind === 'group') {
        walk(node.children)
      }
    }
  }
  for (const band of style.bands) walk(band.children)
  return found
}

/** Brand key (`brands.ts` registry key) the photo's EXIF resolves to, or null. */
export function brandKeyForEnv(env: StockBrandEnv | TemplateRenderEnv | undefined): string | null {
  return matchBrand(String(env?.exif?.make ?? ''), String(env?.exif?.model ?? ''))
}

const BRAND_FILE_INDEX = new Map<string, string>()
for (const brand of BRANDS) {
  for (const file of brand.files) if (!BRAND_FILE_INDEX.has(file)) BRAND_FILE_INDEX.set(file, brand.key)
}

/**
 * Reverse lookup used by the Inspector override: `design.logo.name` is the
 * registry file name the user picked (`logoAsset()` sets it), so a picked logo
 * tells us which brand the user wants. Unknown / hand-dropped logos return null
 * and leave the EXIF resolution in charge.
 *
 * Catalog keys win over the legacy `brands.ts` index: a version-only cut
 * (`ver:<brand>:<cut>`) has no `BRANDS.files` entry, and an alias stem may map
 * to a different brand than the file-name heuristic would guess.
 */
export function brandKeyOfLogoFile(name: string | undefined): string | null {
  if (!name) return null
  const raw = String(name)
  const base = raw.replace(/\\/g, '/').split('/').pop() ?? ''
  const clean = base.replace(/^logo-/, '').replace(/\.(webp|png|jpe?g)$/i, '')
  const catalog = versionForAssetKey(raw) ?? versionForAssetKey(base) ?? versionForAssetKey(clean)
  if (catalog) return catalog.brandKey
  return BRAND_FILE_INDEX.get(clean) ?? null
}

const logoCache = new Map<string, HTMLImageElement>()

export async function loadBrandLogo(file: string): Promise<HTMLImageElement | null> {
  const hit = logoCache.get(file)
  if (hit) return hit
  // `logoUrlFor()` resolves both legacy registry stems and catalog `ver:` keys.
  const url = logoUrlFor(file)
  if (!url) return null
  try {
    const img = await loadImage(url)
    logoCache.set(file, img)
    return img
  } catch {
    return null
  }
}

function imageAspect(img: HTMLImageElement): number {
  const w = img.naturalWidth || img.width
  const h = img.naturalHeight || img.height
  return h > 0 ? w / h : 0
}

/* ------------------------------------------------------------------ *
 * Hand-card brand wordmarks (FIX #64, second iteration)
 *
 * `scene.ts` used to build `env.images.logo` from `design.logo`, and
 * `design.logo` is a GLOBAL field: `rescopeParams()` (store.ts) deliberately does
 * not re-scope it, so the mark of the previously selected card painted the next
 * one (the `stock-xiaomi-leica-1` defect). Clearing it on every variant switch
 * closed the leak but left the hand family with no brand at all — a hand
 * `TemplateDef` has no `isBrandLogo` slot for `resolveBrandSlot()` to fill, so
 * `drawBrandLogo()` (draw.ts) fell through to its drawn-text substitute.
 *
 * The carrier a hand card *does* own is its style definition, and it already has
 * three declared sources, in this precedence:
 *   1. `params.brandLogoFile` — the Inspector pick, bucketed per style
 *      (`writeParam`), so it is this card's own pick or nothing;
 *   2. `variant.vendorLogo`  — a fixed co-brand mark the style names
 *      (`card-1` → `laika`, `person-1` → `hasu_long_black`, …);
 *   3. `variant.autoLogo`    — "the wordmark follows the photo's brand", which
 *      `wm-classic`/`wm-black`/`wm-canon`/`person-5`/`glass-white-*` already
 *      declare and which resolves through the SAME registry the stock slots use.
 * Anything else paints nothing. A brand mark is never synthesised from a name:
 * if `LOGO_FILES` has no real bitmap for the resolved brand there is no
 * wordmark, exactly as `assets.ts:37-39` prescribes for the stock slots.
 * ------------------------------------------------------------------ */

/** The tone rule the whole app already uses for an auto-matched wordmark. */
export function themeIsDark (params: Params | undefined): boolean {
  return ['ink', 'black'].includes(String(params?.theme ?? ''))
}

export interface StyleBrandDecl {
  /** `params.brandLogoFile` — this style's own bucketed Inspector pick. */
  pick?: unknown
  /** `variant.vendorLogo` — fixed mark declared by the style definition. */
  vendorLogo?: string
  /** `variant.autoLogo` — resolve the wordmark from this photo's EXIF brand. */
  autoLogo?: boolean
  /** `design.brandVersion` — an explicit Logo Library version pick. */
  version?: BrandVersionRef | null
}

/**
 * The registry file name this style declares, or null when it declares none.
 * Pure and per-render: no argument here can be supplied by another card.
 *
 * Compatibility is enforced HERE, not in the picker: a file that cannot read on
 * `surface` yields null, never a silent switch to the opposite tone.
 *
 *   1. `version`  — the explicit version pick wins outright. A version with no
 *      cut for this surface yields null (slot empty); it must never fall
 *      through to `pick`/`vendorLogo`/`autoLogo`.
 *   2. `pick`     — a catalogued legacy file is rejected when its cut does not
 *      fit the surface; uncatalogued (hand-dropped) files pass through.
 *   3. `vendorLogo` — fixed co-brand mark, vendor-faithful, returned as-is.
 *   4. `autoLogo` — legacy resolution, then the resulting cut is re-validated
 *      (or, without cut metadata, the `_white`/`_black` suffix heuristic).
 */
export function styleBrandFile (
  decl: StyleBrandDecl,
  exif: { make?: string; model?: string },
  surface: 'light' | 'dark'
): string | null {
  if (decl.version) {
    const version = LOGO_VERSION_BY_ID[decl.version.versionId]
    if (!version) return null
    const cut = cutForVersionSurface(version, surface)
    return cut ? cut.assetKey : null
  }
  const pick = typeof decl.pick === 'string' ? decl.pick.trim() : ''
  if (pick) {
    const cut = logoCutForFile(pick)
    if (cut) return cutFitsSurface(cut, surface) ? cut.assetKey : null
    return pick
  }
  if (decl.vendorLogo) return decl.vendorLogo
  if (decl.autoLogo) {
    const file = autoLogo(String(exif?.make ?? ''), String(exif?.model ?? ''), surface === 'dark')
    if (!file) return null
    const cut = logoCutForFile(file)
    if (cut) return cutFitsSurface(cut, surface) ? file : null
    // No catalog metadata: the registry's own tone suffixes are the only signal.
    if (surface === 'dark' && /_black$/i.test(file)) return null
    if (surface === 'light' && /_white$/i.test(file)) return null
    return file
  }
  return null
}

/** Decode the declared brand bitmap, or null (⇒ the slot stays empty). */
export async function loadStyleBrandLogo (
  decl: StyleBrandDecl,
  exif: { make?: string; model?: string },
  surface: 'light' | 'dark'
): Promise<HTMLImageElement | null> {
  const file = styleBrandFile(decl, exif, surface)
  if (!file) return null
  return loadBrandLogo(file)
}

/* ------------------------------------------------------------------ *
 * Visible-ink metrics (FIX-BRAND-ALIGN)
 *
 * A wordmark bitmap's CANVAS is not its mark. The vendor's Xiaomi wordmarks
 * (`assets/system/xiaomi/<family>/<id>/xiaomi_{black,white}.webp`, 673x160)
 * carry ~24px of transparent padding top and bottom, so only 70-76% of the
 * canvas height is ink. The `assets/logos` substitutes are trimmed tight
 * (`panasionic_white.webp` 1024x157, inkH/canvasH = 1.000). Sizing the drawn
 * rect from the canvas therefore paints a swapped wordmark ~43% taller than the
 * vendor's own mark would have been, and puts its optical centre in the wrong
 * place — the "PanasonicDC-S1" defect in `stock-xiaomi-classic-19`.
 *
 * `inkBoxOf()` measures the real alpha bounding box once per decoded image and
 * caches it, so `paint.ts` can scale and align by INK instead of by canvas.
 * ------------------------------------------------------------------ */

/** Tight alpha box of a bitmap, in that bitmap's own canvas pixels. */
export interface InkBox {
  x: number
  y: number
  w: number
  h: number
  /** canvas size the ink box was measured against */
  cw: number
  ch: number
}

/** Alpha above this counts as visible; at these sizes <=8/255 is invisible. */
const INK_ALPHA_THRESHOLD = 8

const inkCache = new WeakMap<HTMLImageElement, InkBox | null>()

/**
 * Tight alpha bbox of a decoded image, or null when it cannot be read (a
 * cross-origin/tainted canvas). Callers must fall back to canvas metrics —
 * a failed measurement may never make a slot disappear.
 */
export function inkBoxOf(img: HTMLImageElement | undefined | null): InkBox | null {
  if (!img) return null
  const hit = inkCache.get(img)
  if (hit !== undefined) return hit
  let out: InkBox | null = null
  try {
    const cw = img.naturalWidth || img.width
    const ch = img.naturalHeight || img.height
    if (cw && ch) {
      const canvas = createCanvas(cw, ch)
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      if (ctx) {
        ctx.clearRect(0, 0, cw, ch)
        ctx.drawImage(img, 0, 0)
        const data = ctx.getImageData(0, 0, cw, ch).data
        let minX = cw
        let minY = ch
        let maxX = -1
        let maxY = -1
        for (let y = 0; y < ch; y++) {
          const row = y * cw * 4
          for (let x = 0; x < cw; x++) {
            if (data[row + x * 4 + 3] > INK_ALPHA_THRESHOLD) {
              if (x < minX) minX = x
              if (x > maxX) maxX = x
              if (y < minY) minY = y
              if (y > maxY) maxY = y
            }
          }
        }
        if (maxX >= 0) out = { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1, cw, ch }
      }
    }
  } catch {
    out = null
  }
  inkCache.set(img, out)
  return out
}

function toneRank(file: string, dark: boolean): number {
  const white = /_white$/i.test(file)
  const black = /_black$/i.test(file)
  if (dark) return white ? 0 : black ? 2 : 1
  return black ? 0 : white ? 2 : 1
}

/**
 * Pick the best preloaded bitmap for a brand: wordmark-shaped first (a square
 * lockup in a wide slot reads as a smudge), then the tone that suits the card,
 * then the widest candidate. Returns null when nothing usable was preloaded.
 *
 * Incompatible candidates are dropped BEFORE ranking: a catalogued cut must fit
 * the surface (`cutFitsSurface`), and an uncatalogued legacy file is rejected by
 * its `_black`/`_white` suffix. This is what stops a black mark landing on a
 * dark card when the brand also ships a white cut.
 */
export function chooseBrandSlot(
  brandKey: string,
  images: Record<string, HTMLImageElement | undefined>,
  dark: boolean
): BrandSlotChoice | null {
  const brand = BRANDS.find((b) => b.key === brandKey)
  if (!brand) return null
  const surface: 'light' | 'dark' = dark ? 'dark' : 'light'
  const candidates: BrandSlotChoice[] = []
  for (const file of brand.files) {
    const img = images[brandSlotKey(file)]
    if (!img) continue
    const cut = logoCutForFile(file)
    if (cut) {
      if (!cutFitsSurface(cut, surface)) continue
    } else if (dark ? /_black$/i.test(file) : /_white$/i.test(file)) {
      continue
    }
    const aspect = imageAspect(img)
    if (!aspect) continue
    candidates.push({ file, img, aspect })
  }
  if (!candidates.length) return null
  const isWordmark = (c: BrandSlotChoice): number => (c.aspect >= WORDMARK_MIN_ASPECT ? 1 : 0)
  candidates.sort(
    (a, b) =>
      isWordmark(b) - isWordmark(a) ||
      toneRank(a.file, dark) - toneRank(b.file, dark) ||
      b.aspect - a.aspect
  )
  return candidates[0] ?? null
}

/**
 * The bitmap a brand slot should draw for this render, or null when the slot
 * must stay empty. Reads the map produced by `prepareStockStyle()` off `env`.
 * Precedence:
 *   1. `design.brandVersion` — an explicit Logo Library version. Its cut for
 *      this surface is drawn verbatim (no re-cut by wordmark shape), and a
 *      version with no cut here leaves the slot empty.
 *   2. `params.brandLogoFile` — the Inspector pick; an incompatible catalogued
 *      cut is rejected instead of being re-toned.
 *   3. EXIF `matchBrand` — through `chooseBrandSlot()` (already filtered).
 */
export function resolveBrandSlot(
  env: TemplateRenderEnv,
  dark: boolean
): BrandSlotChoice | null {
  const surface: 'light' | 'dark' = dark ? 'dark' : 'light'
  // 1. explicit version pick: the user's choice is authoritative.
  const versionRef = env.design?.brandVersion
  if (versionRef) {
    const version = LOGO_VERSION_BY_ID[versionRef.versionId]
    if (!version) return null
    const cut = cutForVersionSurface(version, surface)
    if (!cut) return null
    const img = env.images[brandSlotKey(cut.assetKey)]
    if (!img) return null
    const aspect = imageAspect(img)
    if (!aspect) return null
    return { file: cut.assetKey, img, aspect }
  }
  const exifKey = brandKeyForEnv(env)
  const pickedParam = env.params?.brandLogoFile
  // FIX #64: the pick is `params.brandLogoFile` ONLY. `design.logo` is a GLOBAL
  // field that `rescopeParams()` (store.ts:164-169) deliberately does not re-scope,
  // while `brandLogoFile` is bucketed per variant — so reading `design.logo.name`
  // here re-imported the previously selected variant's brand into a slot whose
  // bucket had just been reset to "no pick => resolve from this photo's EXIF
  // brand", the rule store.ts:440-442 states for the Inspector pick. On
  // `stock-xiaomi-leica-1` that substituted Hasselblad's 1024x70 long wordmark
  // for the photo's own Panasonic 1024x157 one.
  const picked = typeof pickedParam === 'string' ? pickedParam.trim() : ''
  // 2. legacy pick: a catalogued cut is drawn EXACTLY as picked. It must fit the
  // surface (an incompatible cut hides the slot — never re-toned into the
  // opposite ink behind the user's back), and it must have been preloaded: a
  // missing decode yields an empty slot, never a different version/tone file
  // that `chooseBrandSlot()` might have ranked higher.
  if (picked) {
    const pickedCut = logoCutForFile(picked)
    if (pickedCut) {
      if (!cutFitsSurface(pickedCut, surface)) return null
      const exact = env.images[brandSlotKey(pickedCut.assetKey)]
      if (!exact) return null
      const aspect = imageAspect(exact)
      if (!aspect) return null
      return { file: pickedCut.assetKey, img: exact, aspect }
    }
  }
  const pickedKey = brandKeyOfLogoFile(picked || undefined)
  // An explicit Inspector pick of a *different* brand wins. The pick's own
  // brand files are preloaded by `prepareStockStyle()` (via
  // `params.brandLogoFile`), so `chooseBrandSlot` can re-cut the pick to a
  // wordmark shape; the raw `images.logo` bitmap is only a last resort and a
  // pick that yields nothing hides the slot (never the vendor's baked mark).
  if (pickedKey && pickedKey !== exifKey) {
    const cut = chooseBrandSlot(pickedKey, env.images, dark)
    if (cut) return cut
    // No last resort through `env.images.logo`: a brand slot must never be painted from
    // an arbitrary user bitmap whose intrinsic canvas size then drives `brandSlotRect()`.
    // Leave the slot empty, as assets.ts:37-39 and paint.ts:432 already prescribe for a
    // pick with no usable wordmark.
    return null
  }
  // 3. EXIF brand.
  if (!exifKey) return null
  const resolved = chooseBrandSlot(exifKey, env.images, dark)
  if (resolved) return resolved
  // same brand as the pick, but the wordmark failed to load — draw the picked bitmap
  if (pickedKey === exifKey && env.images.logo) {
    return { file: pickedKey, img: env.images.logo, aspect: imageAspect(env.images.logo) }
  }
  return null
}

export async function prepareStockStyle(
  style: StockStyle,
  env?: StockBrandEnv
): Promise<Record<string, HTMLImageElement>> {
  await ensureSystemFonts()
  const out: Record<string, HTMLImageElement> = {}
  for (const [key, asset] of Object.entries(style.assets)) {
    const url = stockAssetUrl(asset.file)
    if (!url) continue
    try {
      out[key] = await loadImage(url)
    } catch {
      // a missing asset should degrade the style, not break the whole render
    }
  }
  if (collectBrandNodes(style).length) {
    // preload every variant of the matched brand AND, when the Inspector
    // carries an explicit pick (`params.brandLogoFile`), of that brand too —
    // `chooseBrandSlot()` picks the wordmark-shaped cut once the intrinsic
    // sizes are known. A pick of a brand with no loadable file simply adds
    // nothing to the map, which makes the slot render empty (see paint.ts).
    const keys = new Set<string>()
    const exifKey = brandKeyForEnv(env)
    if (exifKey) keys.add(exifKey)
    const pickedKey = brandKeyOfLogoFile(env?.brandLogoFile)
    if (pickedKey) keys.add(pickedKey)
    for (const key of keys) {
      const brand = BRANDS.find((b) => b.key === key)
      for (const file of brand?.files ?? []) {
        const img = await loadBrandLogo(file)
        if (img) out[brandSlotKey(file)] = img
      }
    }
    // An explicit selection may name cuts no `BRANDS.files` entry covers (`ver:`
    // keys): the version branch and the legacy pick both preload EVERY cut of
    // the selected version under its own asset key, so a theme flip can switch
    // light↔dark without another decode and `resolveBrandSlot()` can return the
    // exact pick instead of letting `chooseBrandSlot()` re-rank the siblings.
    const versions = new Map<string, ReturnType<typeof versionForAssetKey>>()
    const version = env?.brandVersion ? LOGO_VERSION_BY_ID[env.brandVersion.versionId] : undefined
    if (version) versions.set(version.id, version)
    const pickedCut = env?.brandLogoFile ? logoCutForFile(env.brandLogoFile) : null
    const pickedVersion = pickedCut ? versionForAssetKey(pickedCut.assetKey) : null
    if (pickedVersion) versions.set(pickedVersion.id, pickedVersion)
    for (const selected of versions.values()) {
      for (const cut of selected?.cuts ?? []) {
        const img = await loadBrandLogo(cut.assetKey)
        if (img) out[brandSlotKey(cut.assetKey)] = img
      }
    }
  }
  return out
}
