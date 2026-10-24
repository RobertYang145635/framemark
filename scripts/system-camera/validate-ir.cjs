const fs = require('node:fs')
const path = require('node:path')

const project = path.resolve(__dirname, '..', '..')
const generated = path.join(project, 'src/renderer/src/core/system/generated')
const assetsRoot = path.join(project, 'src/renderer/src/assets')
const previews = path.join(assetsRoot, 'previews/system')

function readArray(file) {
  const s = fs.readFileSync(path.join(generated, file), 'utf8')
  const start = s.indexOf('= [')
  const end = s.lastIndexOf('] as')
  if (start < 0 || end < 0) throw new Error(`cannot parse ${file}`)
  return JSON.parse(s.slice(start + 2, end + 1))
}

const styles = [
  ...readArray('oppoGallery.ts'),
  ...readArray('oppoCameraPro.ts'),
  ...readArray('xiaomiStyles.ts')
]
const variants = [
  ...readArray('oppoVariants.ts'),
  ...readArray('oppoCameraProVariants.ts'),
  ...readArray('xiaomiVariants.ts')
]
const errors = []
const warnings = []
const styleIds = new Set()

function walkAssets(dir, index) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walkAssets(full, index)
    } else if (!index.has(entry.name)) {
      index.set(entry.name, full)
    }
  }
}
const assetIndex = new Map()
walkAssets(assetsRoot, assetIndex)
const assetExists = (file) => {
  if (fs.existsSync(path.join(assetsRoot, file))) return true
  return assetIndex.has(path.basename(file))
}

for (const style of styles) {
  if (styleIds.has(style.id)) errors.push(`duplicate style id: ${style.id}`)
  styleIds.add(style.id)
  for (const [key, asset] of Object.entries(style.assets ?? {})) {
    if (!assetExists(asset.file)) errors.push(`missing asset ${style.id}/${key}: ${asset.file}`)
  }
  for (const theme of style.themes ?? []) {
    if (String(theme.background).includes('@img=')) warnings.push(`texture theme not painted as texture: ${style.id}/${theme.id}`)
    if (/^#00000000/.test(String(theme.background))) warnings.push(`transparent theme uses fallback: ${style.id}/${theme.id}`)
  }
}

const variantIds = new Set()
for (const v of variants) {
  if (variantIds.has(v.id)) errors.push(`duplicate variant id: ${v.id}`)
  variantIds.add(v.id)
  const styleId = v.params?.systemStyle
  if (!styleId || !styleIds.has(styleId)) errors.push(`variant ${v.id} references unknown style ${styleId}`)
  if (!fs.existsSync(path.join(previews, `${v.id}.webp`))) errors.push(`missing preview: ${v.id}.webp`)
}

const blocked = fs.readFileSync(path.join(project, 'src/renderer/src/core/system/blocked.ts'), 'utf8')
for (const id of variantIds) {
  if (blocked.includes(`id: '${id}'`)) errors.push(`blocked id also shipped as variant: ${id}`)
}
if (styles.length !== 82) warnings.push(`generated stock styles ${styles.length} (expected 82; manual Polaroid + Xiaomi standard + vivo Zeiss are separate)`)
if (variants.length !== 82) warnings.push(`generated stock variants ${variants.length} (expected 82; manual Polaroid + Xiaomi standard + vivo Zeiss are separate)`)
for (const id of ['stock-vivo-zeiss-border_top', 'stock-vivo-zeiss-up_down', 'stock-vivo-zeiss-inside']) {
  if (!fs.existsSync(path.join(previews, `${id}.webp`))) errors.push(`missing preview: ${id}.webp`)
}

const manifestFile = path.resolve(project, '..', '_research/stock-audit/previews-manifest.json')
if (fs.existsSync(manifestFile)) {
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
  const listed = new Set(manifest.map((x) => x.variantId))
  for (const v of variants) if (!listed.has(v.id)) errors.push(`variant missing from previews manifest: ${v.id}`)
}

console.log(JSON.stringify({
  styles: styles.length,
  variants: variants.length,
  previews: fs.readdirSync(previews).filter((f) => f.endsWith('.webp')).length,
  errors: errors.length,
  warnings: warnings.length
}, null, 2))
if (warnings.length) console.log('warnings:\n' + warnings.slice(0, 30).join('\n'))
if (errors.length) {
  console.error('errors:\n' + errors.slice(0, 50).join('\n'))
  process.exit(1)
}
