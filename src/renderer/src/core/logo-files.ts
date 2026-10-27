/**
 * Static registry of the app-shipped logo bitmaps (`assets/logos/*.webp`).
 *
 * Kept in its own module so `logo-version-assets.ts` can resolve legacy file
 * stems without importing `brands.ts` — that import used to close a cycle
 * (`brands -> logo-version-assets -> brands`) once the version catalog needed
 * `logoAsset()` to resolve `ver:` keys.
 *
 * Keys are bare file stems (`fushi_black`), exactly what `LOGO_FILES` has always
 * contained; the glob query/import shape must not change or every existing
 * `params.brandLogoFile` / `design.logo.name` stops resolving.
 */
const modules = import.meta.glob('../assets/logos/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>

export const LOGO_FILES: Record<string, string> = {}
for (const [filePath, url] of Object.entries(modules)) {
  const name = filePath.split('/').pop()?.replace(/\.webp$/i, '') ?? ''
  if (!name || name === 'logo' || name === 'logo_empty') continue
  LOGO_FILES[name] = url
}
