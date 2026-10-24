import type { StockStyle } from './ir'
import { loadImage } from '../images'
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

export async function prepareStockStyle(style: StockStyle): Promise<Record<string, HTMLImageElement>> {
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
  return out
}
