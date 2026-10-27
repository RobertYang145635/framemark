import type { ImageAsset } from '../types'
import { createCanvas, loadImage } from './images'
import { LOGO_FILES } from './logo-files'
import type { LogoCut } from './logo-catalog'
import { cutForAssetKey } from './logo-catalog'
import { logoVersionAssetUrl } from './logo-version-assets'

// Re-exported so every existing importer (`BrandPicker`, `system/assets.ts`,
// tests) keeps reading the same registry object from `core/brands`.
export { LOGO_FILES }

export interface BrandDef {
  key: string
  label: string
  cn: string
  group: 'camera' | 'phone'
  files: string[]
}

const def = (key: string, label: string, cn: string, group: BrandDef['group'], files: string[]): BrandDef => ({
  key,
  label,
  cn,
  group,
  files: files.filter((f) => Boolean(LOGO_FILES[f]))
})

const HIDDEN_BRANDS = new Set(['samyang'])

export const BRANDS: BrandDef[] = [
  def('laika', 'Leica', '徕卡', 'camera', ['laika', 'laika2', 'laika_long']),
  def('canon', 'Canon', '佳能', 'camera', ['canon', 'canon_black', 'canon_white', 'canon_red', 'canon_eos']),
  def('nikon', 'Nikon', '尼康', 'camera', ['nikon_black', 'nikon_white', 'nikon_yellow']),
  def('sony', 'Sony', '索尼', 'camera', ['sony_black', 'sony_white']),
  def('panasionic', 'Panasonic', '松下', 'camera', ['panasionic_black', 'panasionic_white']),
  def('lumix', 'LUMIX', 'LUMIX', 'camera', ['lumix_black', 'lumix_white']),
  def('fushi', 'FUJIFILM', '富士', 'camera', ['fushi_black', 'fushi_white']),
  def('hasu', 'Hasselblad', '哈苏', 'camera', ['hasu_black', 'hasu_white', 'hasu_long_black', 'hasu_long_white']),
  def('aolinbasi', 'OLYMPUS', '奥林巴斯', 'camera', ['aolinbasi', 'aolinbasi_black', 'aolinbasi_white']),
  def('binde', 'PENTAX', '宾得', 'camera', ['binde', 'binde_black', 'binde_white']),
  def('ricoh', 'RICOH', '理光', 'camera', ['ricoh_black', 'ricoh_red', 'ricoh_white']),
  def('caisi', 'ZEISS', '蔡司', 'camera', ['caisi', 'zeiss']),
  def('dji', 'DJI', '大疆', 'camera', ['dji_black', 'dji_white']),
  def('insta360', 'Insta360', '影石', 'camera', ['insta360_black', 'insta360_white']),
  def('contax', 'CONTAX', '康泰时', 'camera', ['contax_black', 'contax_white']),
  def('minolta', 'MINOLTA', '美能达', 'camera', ['minolta_black', 'minolta_white']),
  def('konica', 'KONICA', '柯尼卡', 'camera', ['konica_black', 'konica_white']),
  def('konica_minolta', 'KONICA MINOLTA', '柯尼卡美能达', 'camera', ['konica_minolta_black', 'konica_minolta_white']),
  def('yashica', 'YASHICA', '雅西卡', 'camera', ['yashica_black', 'yashica_white']),
  def('rollei', 'ROLLEI', '禄来', 'camera', ['rollei_black', 'rollei_white']),
  def('voigtlander', 'Voigtländer', '福伦达', 'camera', ['voigtlander_black', 'voigtlander_white']),
  def('mamiya', 'MAMIYA', '玛米亚', 'camera', ['mamiya_black', 'mamiya_white']),
  def('bronica', 'BRONICA', '勃朗尼卡', 'camera', ['bronica_black', 'bronica_white']),
  def('kodak', 'Kodak', '柯达', 'camera', ['kodak_black', 'kodak_white', 'kodak_yellow']),
  def('polaroid', 'POLAROID', '宝丽来', 'camera', ['polaroid_black', 'polaroid_white']),
  def('agfa', 'AGFA', '爱克发', 'camera', ['agfa_black', 'agfa_white', 'agfa_red']),
  def('praktica', 'PRAKTICA', '百佳', 'camera', ['praktica_black', 'praktica_white']),
  def('exakta', 'EXAKTA', '爱克山泰', 'camera', ['exakta_black', 'exakta_white']),
  def('minox', 'MINOX', '美乐时', 'camera', ['minox_black', 'minox_white']),
  def('sinar', 'SINAR', '仙娜', 'camera', ['sinar_black', 'sinar_white']),
  def('linhof', 'LINHOF', '林哈夫', 'camera', ['linhof_black', 'linhof_white']),
  def('alpa', 'ALPA', '阿尔帕', 'camera', ['alpa_black', 'alpa_white']),
  def('zenit', 'ZENIT', '泽尼特', 'camera', ['zenit_black', 'zenit_white']),
  def('lomo', 'LOMO', '乐魔', 'camera', ['lomo_black', 'lomo_white']),
  def('seagull', 'SEAGULL', '海鸥', 'camera', ['seagull_black', 'seagull_white']),
  def('casio', 'CASIO', '卡西欧', 'camera', ['casio_black', 'casio_white']),
  def('cosina', 'COSINA', '确善能', 'camera', ['cosina_black', 'cosina_white']),
  def('horseman', 'HORSEMAN', '骑士', 'camera', ['horseman_black', 'horseman_white']),
  def('graflex', 'GRAFLEX', '格拉菲', 'camera', ['graflex_black', 'graflex_white']),
  def('sigma', 'SIGMA', '适马', 'camera', ['sigma_black', 'sigma_white']),
  def('tamron', 'TAMRON', '腾龙', 'camera', ['tamron_black', 'tamron_white']),
  def('tokina', 'TOKINA', '图丽', 'camera', ['tokina_black', 'tokina_white']),
  def('samyang', 'SAMYANG', '森养', 'camera', ['samyang_black', 'samyang_white']),
  def('phaseone', 'PHASE ONE', '飞思', 'camera', ['phaseone_black', 'phaseone_white']),
  def('gopro', 'GoPro', 'GoPro', 'camera', ['gopro_black', 'gopro_white']),
  def('arri', 'ARRI', '阿莱', 'camera', ['arri_black', 'arri_white']),
  def('red', 'RED', 'RED', 'camera', ['red_black', 'red_white', 'red_red']),
  def('nokia', 'NOKIA', '诺基亚', 'phone', ['nokia_black', 'nokia_white']),
  def('motorola', 'motorola', '摩托罗拉', 'phone', ['motorola_black', 'motorola_white']),
  def('google', 'Google Pixel', '谷歌 Pixel', 'phone', ['google_black', 'google_white']),
  def('realme', 'realme', '真我', 'phone', ['realme_black', 'realme_white']),
  def('nothing', 'Nothing', 'Nothing', 'phone', ['nothing_black', 'nothing_white']),
  def('nubia', 'nubia', '努比亚', 'phone', ['nubia_black', 'nubia_white']),
  def('apple', 'Apple', '苹果', 'phone', ['apple', 'apple_black', 'apple_white']),
  def('huawei', 'HUAWEI', '华为', 'phone', ['huawei', 'huawe_black', 'huawei_white']),
  def('xiaomi', 'Xiaomi', '小米', 'phone', ['xiaomi', 'xiaomi_loing']),
  def('oppo', 'OPPO', 'OPPO', 'phone', ['oppo_black', 'oppo_white', 'oppo_green']),
  def('vivo', 'vivo', 'vivo', 'phone', ['vivo_black', 'vivo_white', 'vivo_blue']),
  def('honor', 'HONOR', '荣耀', 'phone', ['honor', 'honor_black', 'honor_white']),
  def('samsung', 'SAMSUNG', '三星', 'phone', ['samsung_black', 'samsung_blue', 'samsung_white']),
  def('iqoo', 'iQOO', 'iQOO', 'phone', ['iqoo_black', 'iqoo_white']),
  def('meizu', 'MEIZU', '魅族', 'phone', ['meizu_black', 'meizu_blue', 'meizu_white']),
  def('yijia', 'OnePlus', '一加', 'phone', ['yijia_black', 'yijia_red', 'yijia_white'])
].filter((brand) => !HIDDEN_BRANDS.has(brand.key))

export function variantTone(file: string): string {
  const m = file.match(/_(black|white|red|yellow|blue|green|long_black|long_white)$/)
  if (!m) return '彩色'
  const t = m[1]
  if (t.includes('black')) return '黑'
  if (t.includes('white')) return '白'
  if (t === 'red') return '红'
  if (t === 'yellow') return '黄'
  if (t === 'blue') return '蓝'
  if (t === 'green') return '绿'
  return '彩色'
}

export function pickVariant(key: string, tone: 'light' | 'dark' | 'color' = 'light'): string | null {
  const brand = BRANDS.find((b) => b.key === key)
  if (!brand) return null
  const files = brand.files
  if (tone === 'dark') return files.find((f) => /_white$/.test(f)) ?? files.find((f) => /_black$/.test(f)) ?? files[0] ?? null
  if (tone === 'color') {
    return files.find((f) => !/_(black|white)$/.test(f)) ?? files[0] ?? null
  }
  return files.find((f) => /_black$/.test(f)) ?? files.find((f) => !/_(white|red|yellow|blue|green)$/.test(f)) ?? files[0] ?? null
}

export function matchBrand(make: string, model: string): string | null {
  const s = `${make} ${model}`.toLowerCase()
  const exactMake = String(make || '').trim().toLowerCase()
  if (exactMake === 'red' || exactMake === 'arri') return exactMake
  const map: [string, string[]][] = [
    ['laika', ['leica', 'leitz']],
    ['canon', ['canon']],
    ['nikon', ['nikon']],
    ['sony', ['sony', 'ilce', 'alpha']],
    ['panasionic', ['panasonic', 'dc-', 'lumix']],
    ['lumix', ['lumix']],
    ['fushi', ['fuji', 'fujifilm']],
    ['hasu', ['hasselblad']],
    ['aolinbasi', ['olympus', 'om system']],
    ['binde', ['pentax']],
    ['ricoh', ['ricoh', 'gr ']],
    ['caisi', ['zeiss', 'carl zeiss']],
    ['dji', ['dji', 'mavic', 'osmo']],
    ['insta360', ['insta360']],
    ['konica_minolta', ['konica minolta', 'konicaminolta']],
    ['contax', ['contax']],
    ['minolta', ['minolta']],
    ['konica', ['konica']],
    ['yashica', ['yashica']],
    ['rollei', ['rollei', 'rolleiflex']],
    ['voigtlander', ['voigtlander', 'voigtländer']],
    ['mamiya', ['mamiya']],
    ['bronica', ['bronica', 'zenza']],
    ['kodak', ['kodak', 'eastman']],
    ['polaroid', ['polaroid']],
    ['agfa', ['agfa']],
    ['praktica', ['praktica', 'practika']],
    ['exakta', ['exakta']],
    ['minox', ['minox']],
    ['sinar', ['sinar']],
    ['linhof', ['linhof']],
    ['alpa', ['alpa']],
    ['zenit', ['zenit']],
    ['lomo', ['lomo', 'lomography']],
    ['seagull', ['seagull']],
    ['casio', ['casio']],
    ['cosina', ['cosina']],
    ['horseman', ['horseman']],
    ['graflex', ['graflex']],
    ['sigma', ['sigma']],
    ['tamron', ['tamron']],
    ['tokina', ['tokina']],
    ['samyang', ['samyang']],
    ['phaseone', ['phase one', 'phaseone', 'leaf']],
    ['gopro', ['gopro']],
    ['nokia', ['nokia']],
    ['motorola', ['motorola', 'moto ']],
    ['google', ['google', 'pixel']],
    ['realme', ['realme']],
    ['nothing', ['nothing']],
    ['nubia', ['nubia', 'zte ']],
    ['honor', ['honor']],
    ['huawei', ['huawei']],
    ['xiaomi', ['xiaomi', 'redmi', 'mi ']],
    ['oppo', ['oppo']],
    ['vivo', ['vivo']],
    ['samsung', ['samsung', 'galaxy']],
    ['iqoo', ['iqoo']],
    ['meizu', ['meizu']],
    ['yijia', ['oneplus', 'one plus']],
    ['apple', ['apple', 'iphone']]
  ]
  for (const [key, words] of map) if (words.some((w) => s.includes(w))) return key
  return null
}

const assetCache = new Map<string, Promise<ImageAsset>>()

/**
 * URL for an app logo file OR a catalog asset key (`ver:<brand>:<cut>`), or null
 * when neither registry knows it. Legacy app stems keep resolving exactly as
 * before, so `logoAsset()` call sites need no change.
 */
export function logoUrlFor(file: string): string | null {
  return LOGO_FILES[file] ?? logoVersionAssetUrl(file)
}

/**
 * Catalog cut behind a registry file name, accepting a legacy stem with or
 * without extension / a `logo-` prefix. Null for hand-dropped or unknown files.
 */
export function logoCutForFile(file: string): LogoCut | null {
  const base = String(file).replace(/\\/g, '/').split('/').pop() ?? ''
  const clean = base.replace(/^logo-/, '').replace(/\.(webp|png|jpe?g)$/i, '')
  return cutForAssetKey(file) ?? cutForAssetKey(base) ?? cutForAssetKey(clean)
}

export function logoAsset(file: string): Promise<ImageAsset> {
  const hit = assetCache.get(file)
  if (hit) return hit
  const p = (async () => {
    const url = logoUrlFor(file)
    if (!url) throw new Error('Logo 不存在')
    const img = await loadImage(url)
    const canvas = createCanvas(img.naturalWidth, img.naturalHeight)
    const ctx = canvas.getContext('2d')!
    ctx.drawImage(img, 0, 0)
    return {
      id: `logo-${file}`,
      name: file,
      dataUrl: canvas.toDataURL('image/png'),
      width: img.naturalWidth,
      height: img.naturalHeight
    }
  })()
  assetCache.set(file, p)
  p.catch(() => assetCache.delete(file))
  return p
}

export function autoLogo(make: string, model: string, dark: boolean): string | null {
  const key = matchBrand(make, model)
  if (!key) return null
  return pickVariant(key, dark ? 'dark' : 'light')
}
