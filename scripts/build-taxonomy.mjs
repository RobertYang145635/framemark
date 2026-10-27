#!/usr/bin/env node
/**
 * Generate the 7-category border taxonomy from the approved v4 prototype data.
 *
 * Source of truth: _research/ui-design/prototype-v4/data.js (window.FM_DATA)
 * Output: src/renderer/src/core/taxonomy.generated.ts (deterministic, no timestamps)
 *
 * Also cross-checks every prototype variant id against the app's real variant
 * registry (src/renderer/src/core/variants.ts), bundled with esbuild and a stub
 * for import.meta.glob so it can be loaded in Node.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT_FILE = path.join(ROOT, 'src/renderer/src/core/taxonomy.generated.ts')
const VARIANT_ENTRY = path.join(ROOT, 'src/renderer/src/core/variants.ts')

const CATEGORY_KEYS = ['vendor', 'text', 'impression', 'white', 'card', 'color', 'minimal']
const CATEGORY_KEY_SET = new Set(CATEGORY_KEYS)
const EXPECTED_COUNTS = {
  vendor: 99,
  text: 21,
  impression: 13,
  white: 12,
  card: 19,
  color: 6,
  minimal: 5
}
const EXPECTED_TOTAL = 175
const EXPECTED_VENDOR_BRANDS = [
  { key: 'OPPO', name: 'OPPO', count: 47 },
  { key: '小米', name: '小米', count: 40 },
  { key: 'vivo', name: 'vivo', count: 12 }
]
const FORBIDDEN_CARD_FLAGS = ['badge', 'recommend', 'recommended', 'recommendation', 'selected', 'approx']

const DATA_CANDIDATES = [
  path.join(ROOT, '_research/ui-design/prototype-v4/data.js'),
  path.resolve(ROOT, '..', '_research/ui-design/prototype-v4/data.js')
]

class ValidationError extends Error {}

function fail(message) {
  throw new ValidationError(message)
}

function findSourceData() {
  for (const candidate of DATA_CANDIDATES) {
    if (fs.existsSync(candidate)) return candidate
  }
  fail(`prototype data not found. Looked in:\n  ${DATA_CANDIDATES.join('\n  ')}`)
}

function parsePrototype(sourcePath) {
  const raw = fs.readFileSync(sourcePath, 'utf8')
  const withoutPrefix = raw.replace(/^\uFEFF/, '').trim().replace(/^window\.FM_DATA\s*=\s*/, '')
  if (withoutPrefix === raw.trim()) fail(`expected data.js to start with "window.FM_DATA=" (${sourcePath})`)
  const json = withoutPrefix.replace(/;\s*$/, '')
  try {
    return JSON.parse(json)
  } catch (error) {
    fail(`could not parse ${sourcePath} as JSON: ${error.message}`)
  }
}

function validateAndNormalize(data, sourcePath) {
  if (!data || typeof data !== 'object') fail('prototype data is not an object')
  if (!Array.isArray(data.cards)) fail('prototype data has no cards[] array')
  if (!Array.isArray(data.categories)) fail('prototype data has no categories[] array')

  const cards = data.cards
  if (cards.length !== EXPECTED_TOTAL) {
    fail(`expected exactly ${EXPECTED_TOTAL} cards, found ${cards.length}`)
  }

  const ids = new Set()
  const duplicateIds = []
  const counts = Object.fromEntries(CATEGORY_KEYS.map((key) => [key, 0]))
  const brandCounts = new Map()

  cards.forEach((card, index) => {
    const where = `cards[${index}]`
    if (!card || typeof card !== 'object') fail(`${where} is not an object`)

    if (typeof card.i !== 'string' || card.i.trim() === '') fail(`${where} has an empty variant id`)
    if (ids.has(card.i)) duplicateIds.push(card.i)
    ids.add(card.i)

    if (typeof card.n !== 'string' || card.n.trim() === '') {
      fail(`${where} (${card.i}) has an empty name`)
    }

    if (typeof card.catKey !== 'string' || !CATEGORY_KEY_SET.has(card.catKey)) {
      fail(
        `${where} (${card.i}) must have exactly one catKey among ${CATEGORY_KEYS.join(', ')}; got ${JSON.stringify(card.catKey)}`
      )
    }
    counts[card.catKey] += 1

    for (const flag of FORBIDDEN_CARD_FLAGS) {
      if (card[flag] !== undefined && card[flag] !== null && card[flag] !== false) {
        fail(`${where} (${card.i}) carries forbidden "${flag}" badge metadata`)
      }
    }

    if (card.cat !== undefined && typeof card.cat !== 'string') {
      fail(`${where} (${card.i}) has a non-string cat label`)
    }

    if (card.catKey === 'vendor') {
      if (typeof card.brand !== 'string' || card.brand.trim() === '') {
        fail(`${where} (${card.i}) is a vendor card without a brand`)
      }
      brandCounts.set(card.brand, (brandCounts.get(card.brand) ?? 0) + 1)
    }
  })

  if (duplicateIds.length > 0) {
    fail(`duplicate variant ids (${duplicateIds.length}): ${duplicateIds.join(', ')}`)
  }
  if (ids.size !== EXPECTED_TOTAL) fail(`expected ${EXPECTED_TOTAL} unique ids, found ${ids.size}`)

  for (const key of CATEGORY_KEYS) {
    if (counts[key] !== EXPECTED_COUNTS[key]) {
      fail(`category "${key}" count is ${counts[key]}, expected ${EXPECTED_COUNTS[key]}`)
    }
  }
  const actualTotal = Object.values(counts).reduce((a, b) => a + b, 0)
  if (actualTotal !== EXPECTED_TOTAL) fail(`category counts add up to ${actualTotal}, expected ${EXPECTED_TOTAL}`)

  if (data.categories.length !== CATEGORY_KEYS.length) {
    fail(`expected ${CATEGORY_KEYS.length} categories, found ${data.categories.length}`)
  }
  const categoryKeys = new Set()
  for (const category of data.categories) {
    if (!category || typeof category.key !== 'string' || !CATEGORY_KEY_SET.has(category.key)) {
      fail(`unknown category metadata entry: ${JSON.stringify(category)}`)
    }
    if (categoryKeys.has(category.key)) fail(`duplicate category metadata key "${category.key}"`)
    categoryKeys.add(category.key)
    if (typeof category.name !== 'string' || category.name.trim() === '') {
      fail(`category "${category.key}" has an empty name`)
    }
    if (category.count !== counts[category.key]) {
      fail(
        `category "${category.key}" declares count ${category.count}, but cards yield ${counts[category.key]}`
      )
    }
    if (category.key === 'vendor') {
      if (!Array.isArray(category.brands)) fail('vendor category must declare a brands[] list')
      const declared = category.brands.map((brand) => ({
        key: brand?.key,
        name: brand?.name,
        count: brand?.count
      }))
      if (JSON.stringify(declared) !== JSON.stringify(EXPECTED_VENDOR_BRANDS)) {
        fail(
          `vendor brands must be exactly ${JSON.stringify(EXPECTED_VENDOR_BRANDS)}, got ${JSON.stringify(declared)}`
        )
      }
    } else if (category.brands !== null && category.brands !== undefined) {
      fail(`category "${category.key}" must not declare brands`)
    }
  }
  for (const key of CATEGORY_KEYS) {
    if (!categoryKeys.has(key)) fail(`missing category metadata for "${key}"`)
  }

  const brandNames = [...brandCounts.keys()].sort()
  const expectedBrandNames = EXPECTED_VENDOR_BRANDS.map((brand) => brand.key).sort()
  if (JSON.stringify(brandNames) !== JSON.stringify(expectedBrandNames)) {
    fail(`unexpected vendor brands: ${JSON.stringify(brandNames)}; expected ${JSON.stringify(expectedBrandNames)}`)
  }
  for (const brand of EXPECTED_VENDOR_BRANDS) {
    const actual = brandCounts.get(brand.key) ?? 0
    if (actual !== brand.count) {
      fail(`vendor brand "${brand.key}" count is ${actual}, expected ${brand.count}`)
    }
  }

  if (data.counts) {
    for (const key of CATEGORY_KEYS) {
      if (data.counts[key] !== counts[key]) {
        fail(`data.counts.${key} is ${data.counts[key]}, but cards yield ${counts[key]}`)
      }
    }
  }
  if (data.total !== undefined && data.total !== cards.length) {
    fail(`data.total is ${data.total}, but there are ${cards.length} cards`)
  }

  const categoryNameByKey = new Map(data.categories.map((category) => [category.key, category.name]))
  for (const card of cards) {
    if (card.cat !== undefined && card.cat !== categoryNameByKey.get(card.catKey)) {
      fail(
        `card ${card.i} label "${card.cat}" does not match category "${card.catKey}" (${categoryNameByKey.get(card.catKey)})`
      )
    }
  }

  const categories = CATEGORY_KEYS.map((key) => {
    const category = {
      key,
      name: categoryNameByKey.get(key),
      count: counts[key]
    }
    if (key === 'vendor') {
      category.brands = EXPECTED_VENDOR_BRANDS.map((brand) => ({
        key: brand.key,
        name: brand.name,
        count: brandCounts.get(brand.key) ?? 0
      }))
    }
    return category
  })

  const taxonomyCards = cards.map((card) => {
    const normalized = {
      variantId: card.i,
      name: card.n,
      category: card.catKey
    }
    if (typeof card.brand === 'string' && card.brand.trim() !== '') normalized.brand = card.brand
    return normalized
  })

  return { categories, cards: taxonomyCards, counts, sourcePath }
}

async function loadAppVariantIds() {
  let esbuild
  try {
    esbuild = await import('esbuild')
  } catch (error) {
    fail(`cross-check needs esbuild (devDependency): ${error.message}`)
  }
  if (!fs.existsSync(VARIANT_ENTRY)) fail(`app variant registry not found: ${VARIANT_ENTRY}`)

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'framemark-taxonomy-'))
  const bundlePath = path.join(tmpDir, 'variants.cjs')
  try {
    esbuild.buildSync({
      entryPoints: [VARIANT_ENTRY],
      outfile: bundlePath,
      bundle: true,
      platform: 'node',
      format: 'cjs',
      target: 'node20',
      logLevel: 'silent',
      define: { 'import.meta.glob': '__fm_glob' },
      banner: { js: 'const __fm_glob = () => ({});' }
    })
    const require = createRequire(import.meta.url)
    const mod = require(bundlePath)
    if (!Array.isArray(mod.TEMPLATE_VARIANTS)) {
      fail('bundled variants.ts did not export TEMPLATE_VARIANTS[]')
    }
    return mod.TEMPLATE_VARIANTS.map((variant) => variant.id)
  } catch (error) {
    if (error instanceof ValidationError) throw error
    fail(`could not load app variant registry: ${error.message}`)
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true })
  }
}

function crossCheckVariants(taxonomyCards, appVariantIds) {
  if (appVariantIds.length === 0) fail('app variant registry is empty')
  const appIds = new Set()
  const duplicateAppIds = []
  for (const id of appVariantIds) {
    if (appIds.has(id)) duplicateAppIds.push(id)
    appIds.add(id)
  }
  if (duplicateAppIds.length > 0) {
    fail(`app variant registry has duplicate ids (${duplicateAppIds.length}): ${duplicateAppIds.join(', ')}`)
  }

  const taxonomyIds = new Set(taxonomyCards.map((card) => card.variantId))
  const missing = taxonomyCards.map((card) => card.variantId).filter((id) => !appIds.has(id))
  if (missing.length > 0) {
    fail(
      `${missing.length} prototype variant id(s) do not exist in the app registry:\n  ${missing.join('\n  ')}`
    )
  }
  const appOnly = [...appIds].filter((id) => !taxonomyIds.has(id))
  if (appOnly.length > 0) {
    console.warn(
      `[build-taxonomy] warning: ${appOnly.length} app variant id(s) are not in the taxonomy:\n  ${appOnly.join('\n  ')}`
    )
  }
  return { appTotal: appIds.size, matched: taxonomyCards.length, appOnly: appOnly.length }
}

function quote(value) {
  return `'${String(value)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "\\'")
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')}'`
}

function renderTypeScript(taxonomy) {
  const lines = []
  lines.push('// Generated by scripts/build-taxonomy.mjs from the approved v4 prototype data.')
  lines.push('// Do not edit by hand; re-run `node scripts/build-taxonomy.mjs`.')
  lines.push('')
  lines.push(`export type BorderCategoryKey = ${CATEGORY_KEYS.map(quote).join(' | ')}`)
  lines.push('')
  lines.push('export interface BorderCategory {')
  lines.push('  key: BorderCategoryKey')
  lines.push('  name: string')
  lines.push('  count: number')
  lines.push('  brands?: { key: string; name: string; count: number }[]')
  lines.push('}')
  lines.push('')
  lines.push('export interface TaxonomyCard {')
  lines.push('  variantId: string')
  lines.push('  name: string')
  lines.push('  category: BorderCategoryKey')
  lines.push('  brand?: string')
  lines.push('}')
  lines.push('')
  lines.push('export const BORDER_CATEGORIES: BorderCategory[] = [')
  for (const category of taxonomy.categories) {
    if (!category.brands) {
      lines.push(
        `  { key: ${quote(category.key)}, name: ${quote(category.name)}, count: ${category.count} },`
      )
      continue
    }
    lines.push('  {')
    lines.push(`    key: ${quote(category.key)},`)
    lines.push(`    name: ${quote(category.name)},`)
    lines.push(`    count: ${category.count},`)
    lines.push('    brands: [')
    for (const brand of category.brands) {
      lines.push(
        `      { key: ${quote(brand.key)}, name: ${quote(brand.name)}, count: ${brand.count} },`
      )
    }
    lines.push('    ]')
    lines.push('  },')
  }
  lines.push(']')
  lines.push('')
  lines.push('export const TAXONOMY_CARDS: TaxonomyCard[] = [')
  for (const card of taxonomy.cards) {
    const brand = card.brand === undefined ? '' : `, brand: ${quote(card.brand)}`
    lines.push(
      `  { variantId: ${quote(card.variantId)}, name: ${quote(card.name)}, category: ${quote(card.category)}${brand} },`
    )
  }
  lines.push(']')
  lines.push('')
  lines.push('export const TAXONOMY_BY_ID: Record<string, TaxonomyCard> = Object.fromEntries(')
  lines.push('  TAXONOMY_CARDS.map((card) => [card.variantId, card])')
  lines.push(')')
  lines.push('')
  return lines.join('\n')
}

async function main() {
  const sourcePath = findSourceData()
  const data = parsePrototype(sourcePath)
  const taxonomy = validateAndNormalize(data, sourcePath)
  const app = crossCheckVariants(taxonomy.cards, await loadAppVariantIds())

  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true })
  fs.writeFileSync(OUT_FILE, renderTypeScript(taxonomy), 'utf8')

  console.log(`[build-taxonomy] source: ${sourcePath}`)
  console.log(
    `[build-taxonomy] cards: ${taxonomy.cards.length} (${CATEGORY_KEYS.map((key) => `${key} ${taxonomy.counts[key]}`).join(', ')})`
  )
  console.log(
    `[build-taxonomy] variant cross-check: ${app.matched}/${app.matched} prototype ids matched app registry (${app.appTotal} app variants, ${app.appOnly} app-only)`
  )
  console.log(`[build-taxonomy] wrote ${OUT_FILE}`)
}

main().catch((error) => {
  const message = error instanceof ValidationError ? error.message : error?.stack || String(error)
  console.error(`[build-taxonomy] FAILED: ${message}`)
  process.exitCode = 1
})
