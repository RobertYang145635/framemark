import type { StockBrandEnv, StockImageNode, StockNode, StockStyle } from './ir'
import type { TemplateRenderEnv } from '../../types'
import { BRANDS, LOGO_FILES, matchBrand } from '../brands'
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
 */
export function brandKeyOfLogoFile(name: string | undefined): string | null {
  if (!name) return null
  const clean = String(name).replace(/^logo-/, '').replace(/\.(webp|png|jpe?g)$/i, '')
  return BRAND_FILE_INDEX.get(clean) ?? null
}

const logoCache = new Map<string, HTMLImageElement>()

export async function loadBrandLogo(file: string): Promise<HTMLImageElement | null> {
  const hit = logoCache.get(file)
  if (hit) return hit
  const url = LOGO_FILES[file]
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
 */
export function chooseBrandSlot(
  brandKey: string,
  images: Record<string, HTMLImageElement | undefined>,
  dark: boolean
): BrandSlotChoice | null {
  const brand = BRANDS.find((b) => b.key === brandKey)
  if (!brand) return null
  const candidates: BrandSlotChoice[] = []
  for (const file of brand.files) {
    const img = images[brandSlotKey(file)]
    if (!img) continue
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
 * Precedence: explicit Inspector pick (`params.brandLogoFile`, written by
 * `store.ts` next to `design.logo`) → EXIF `matchBrand` → nothing.
 */
export function resolveBrandSlot(
  env: TemplateRenderEnv,
  dark: boolean
): BrandSlotChoice | null {
  const exifKey = brandKeyForEnv(env)
  const pickedParam = env.params?.brandLogoFile
  const pickedKey =
    brandKeyOfLogoFile(typeof pickedParam === 'string' ? pickedParam : undefined) ??
    brandKeyOfLogoFile(env.design?.logo?.name)
  // An explicit Inspector pick of a *different* brand wins. The pick's own
  // brand files are preloaded by `prepareStockStyle()` (via
  // `params.brandLogoFile`), so `chooseBrandSlot` can re-cut the pick to a
  // wordmark shape; the raw `images.logo` bitmap is only a last resort and a
  // pick that yields nothing hides the slot (never the vendor's baked mark).
  if (pickedKey && pickedKey !== exifKey) {
    const cut = chooseBrandSlot(pickedKey, env.images, dark)
    if (cut) return cut
    const img = env.images.logo
    if (img) return { file: String(env.design?.logo?.name ?? pickedKey), img, aspect: imageAspect(img) }
    return null
  }
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
  }
  return out
}
