import type { Params, TemplateId, TemplateVariant, TextSettings } from '../types'
import { OPPO_GALLERY_VARIANTS } from './system/generated/oppoVariants'
import { OPPO_CAMERAPRO_VARIANTS } from './system/generated/oppoCameraProVariants'
import { XIAOMI_VARIANTS } from './system/generated/xiaomiVariants'
import { STOCK_STYLE_LIST } from './system/styles'

const STOCK_PREVIEWS = import.meta.glob('../assets/previews/system/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>
const RENDERED_PREVIEWS = import.meta.glob('../assets/previews/rendered/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>
function renderedPreview(id: string): string | undefined {
  return RENDERED_PREVIEWS[`../assets/previews/rendered/${id}.webp`]
}
const STOCK_STYLE_BY_ID = new Map(STOCK_STYLE_LIST.map((s) => [s.id, s]))
const THEME_LABEL: Record<string, string> = { white: '白底', black: '黑底' }
const NON_CANVAS_THEME = new Set(['top_bg', 'bottom_bg', 'transparent'])
const solidTheme = (t: { id: string; background: string }) =>
  /^#[0-9a-fA-F]{6,8}$/.test(t.background) && !/^#00/.test(t.background) && !NON_CANVAS_THEME.has(t.id)
const VIVO_VARIANTS: TemplateVariant[] = [
  {
    id: 'stock-vivo-zeiss-border_top',
    name: '蔡司 · 上框',
    en: 'ZEISS TOP',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-zeiss:border_top' },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-zeiss-up_down',
    name: '蔡司 · 上下框',
    en: 'ZEISS UP / DOWN',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-zeiss:up_down' },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-zeiss-inside',
    name: '蔡司 · 内嵌',
    en: 'ZEISS INSIDE',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-zeiss:inside' },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  }
]
const XIAOMI_SIDEBAR_VARIANTS: TemplateVariant[] = [
  {
    id: 'stock-xiaomi-classic-22',
    name: '经典 · 22',
    en: 'classic_22',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'xiaomi-classic:22' },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'xiaomi',
    family: '小米相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-xiaomi-classic-99',
    name: '经典 · 99',
    en: 'classic_99',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'xiaomi-classic:99' },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'xiaomi',
    family: '小米相机',
    fidelity: 'approx'
  }
]

const STOCK_VARIANTS = [...OPPO_GALLERY_VARIANTS, ...OPPO_CAMERAPRO_VARIANTS, ...XIAOMI_VARIANTS, ...VIVO_VARIANTS, ...XIAOMI_SIDEBAR_VARIANTS].map((v) => {
  const style = STOCK_STYLE_BY_ID.get(String(v.params?.systemStyle ?? ''))
  const themes = style?.themes
    ?.filter(solidTheme)
    .map(({ id, label, swatch }) => ({ id, label: THEME_LABEL[id] ?? label, swatch }))
  const defaultTheme = themes?.some((t) => t.id === style?.defaultTheme) ? style?.defaultTheme : themes?.[0]?.id
  return {
    ...v,
    preview: STOCK_PREVIEWS[`../assets/previews/system/${v.id}.webp`],
    themes,
    defaultTheme
  }
})

const previewModules = import.meta.glob('../assets/previews/*.{webp,png,jpg}', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>

function preview(name: string): string | undefined {
  return previewModules[`../assets/previews/${name}`]
}

const V = (
  id: string,
  name: string,
  category: string,
  base: TemplateId,
  previewName: string | null,
  params: Partial<Params> = {},
  badge?: string,
  text?: Partial<TextSettings>,
  vendorLogo?: string,
  autoLogo = false
): TemplateVariant => ({
  id,
  name,
  en: id.toUpperCase(),
  category,
  base,
  params,
  text,
  preview: previewName ? preview(previewName) : undefined,
  renderedPreview: renderedPreview(id),
  badge,
  vendorLogo,
  autoLogo
})

export const TEMPLATE_VARIANTS: TemplateVariant[] = [
  ...STOCK_VARIANTS,
  // 背景模糊
  V('blur-classic', '经典模式', '背景模糊', 'blur', null, { theme: 'paper', marginPct: 3.5, radiusPct: 2, shadow: 6, footerPct: 11, blur: 26 }, '原版'),
  V('blur-date', '简约日期', '背景模糊', 'blur', null, { theme: 'ink', marginPct: 3, radiusPct: 1.5, shadow: 5, footerPct: 9, blur: 30, minimalFooter: true }),

  // 厂商水印
  V(
    'vendor-hasselblad',
    '哈苏 · 白底',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'hasselblad', footerPct: 16.67 },
    '官方规格',
    { showDate: true, showExif: false },
    'hasu_long_black'
  ),
  V(
    'vendor-hasselblad-dark',
    '哈苏 · 黑底',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'black', vendor: 'hasselblad', footerPct: 16.67 },
    '官方规格',
    { showDate: true, showExif: false },
    'hasu_long_white'
  ),
  V(
    'vendor-hasselblad-h',
    '哈苏 · H 标',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'hasselblad', vendorStyle: 'h2', footerPct: 18.89 },
    '官方规格',
    { showDate: true, showExif: false },
    'hassel_h_black'
  ),
  V(
    'vendor-hasselblad-frame',
    '哈苏 · 白框',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'hasselblad', vendorStyle: 'h3', footerPct: 30.83, sidePadPct: 2.78 },
    '官方规格',
    { showDate: true, showExif: false },
    'hassel_h_black'
  ),
  V(
    'vendor-hasselblad-matte',
    '哈苏 · 宽白边',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'hasselblad', vendorStyle: 'h4', footerPct: 21.94, topPadPct: 21.94, sidePadPct: 2.78 },
    '官方规格',
    { showDate: false, showExif: true },
    'hassel_wordmark_black'
  ),
  V(
    'vendor-lumo',
    '一加 · LUMO',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'lumo', vendorStyle: 'l1' },
    '官方规格',
    { showDate: true, showExif: false },
    'lumo_black'
  ),
  V(
    'vendor-lumo-frame',
    '一加 · LUMO 白框',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'lumo', vendorStyle: 'l2', footerPct: 30.83, sidePadPct: 2.78 },
    '官方规格',
    { showDate: true, showExif: false },
    'lumo_black'
  ),
  V(
    'vendor-lumo-matte',
    '一加 · LUMO 宽白边',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'lumo', vendorStyle: 'l3', footerPct: 21.94, topPadPct: 21.94, sidePadPct: 2.78 },
    '官方规格',
    { showDate: true, showExif: false },
    'lumo_black'
  ),
  V(
    'vendor-zeiss',
    '蔡司 · 白底',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'zeiss', footerPct: 12 },
    '官方素材',
    { showDate: false },
    'zeiss'
  ),
  V(
    'vendor-leica',
    '徕卡 · 白底',
    '厂商水印',
    'vendorFooter',
    null,
    { theme: 'white', vendor: 'leica', footerPct: 14.8 },
    '复刻',
    { showDate: true },
    'laika_long'
  ),
  V(
    'vendor-xiaomi',
    '小米 · 徕卡',
    '厂商水印',
    'xiaomiLeica',
    null,
    { theme: 'white' },
    '官方规格',
    { showDate: true, showExif: true },
    'laika'
  ),
  V(
    'vendor-xiaomi-single',
    '小米 · 徕卡单行',
    '厂商水印',
    'xiaomiLeica',
    null,
    { theme: 'white' },
    '官方规格',
    { showDate: false, showExif: true },
    'laika'
  ),

  // 个人边框
  V(
    'personal-simple',
    '个人签名',
    '个人边框',
    'personalBorder',
    null,
    { theme: 'white', borderPct: 2, footerPct: 9.5, signature: 'FrameMark' },
    '实测',
    { showDate: false }
  ),

  // 水印边框
  V('wm-classic', '经典双行', '水印边框', 'brandFooter', 'new_watermark1.webp', { theme: 'white', logoPos: 'center', footerPct: 11.1 }, '热门'),
  V(
    'wm-simple',
    '简约单行',
    '水印边框',
    'brandFooter',
    null,
    { theme: 'white', logoPos: 'center', footerMode: 'simple', footerAuto: true },
    '实测',
    { showDate: false }
  ),
  V('wm-black', '黑底日期', '水印边框', 'brandFooter', 'new-watermark2.webp', { theme: 'black', logoPos: 'center', footerPct: 8 }),
  V('wm-canon', '经典单行', '水印边框', 'brandFooter', 'watermark1.webp', { theme: 'white', logoPos: 'left', footerPct: 7.5 }),
  V('wm-left', '左标双行', '水印边框', 'brandFooter', 'watermark2.webp', { theme: 'white', logoPos: 'left', footerPct: 8 }),
  V('wm-wide', '白边双行', '水印边框', 'brandFooter', 'watermark3.webp', { theme: 'white', logoPos: 'center', footerPct: 7.5 }),
  V('wm-right', '右标双行', '水印边框', 'brandFooter', 'watermark4.webp', { theme: 'white', logoPos: 'right', footerPct: 7.5 }),

  // 留白
  V('white-1', '纯白 01', '留白', 'matteOverlay', 'whiteborder1.webp', { theme: 'white', marginPct: 4.3, marginTopPct: 3.47, marginBottomPct: 4.44, radiusPct: 2.0, overlayMode: 'brand', brandWidthPct: 26.4, brandOffsetPct: 5.6, overlayBrand: 'HASSELBLAD' }, '热门', { showLogo: true, showExif: false, showDate: false }, 'hasu_long_white'),
  V('white-2', '纯白 02', '留白', 'matteOverlay', 'whiteborder2.webp', { theme: 'white', marginPct: 4.29, marginTopPct: 3.32, radiusPct: 2.0, overlayMode: 'brand-exif', brandWidthPct: 31.0, brandOffsetPct: 10.0, exifWidthPct: 36.0, exifSizePct: 1.80, exifScaleX: 1.47, exifOffsetPct: 5.3, overlayBrand: 'FUJIFILM', exifAlign: 'center' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'fushi_white'),
  V('white-3', '纯白 03', '留白', 'matteOverlay', 'whiteborder3.webp', { theme: 'white', marginPct: 4.29, marginTopPct: 3.46, radiusPct: 1.8, overlayMode: 'exif', exifWidthPct: 38.0, exifSizePct: 2.03, exifScaleX: 1.49, exifOffsetPct: 5.0, exifAlign: 'center' }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('white-4', '米白 04', '留白', 'matteOverlay', 'whiteborder4.webp', { theme: 'white', marginPct: 2.14, marginTopPct: 1.74, marginBottomPct: 2.55, radiusPct: 1.3, overlayMode: 'none' }, '原版', { showLogo: false, showExif: false, showDate: false }),

  // 参数
  V('simple-1', '极简 01', '参数', 'paramStack', 'simple1.webp', { theme: 'ink', corner: 'tl' }, '热门', { showLogo: false, showExif: false, showDate: false }),
  V('simple-2', '极简 02', '参数', 'paramStack', 'simple2.webp', { theme: 'ink', corner: 'bl' }, '原版', { showLogo: false, showExif: false, showDate: false }),
  V('simple-3', '极简 03', '参数', 'paramStack', 'simple3.webp', { theme: 'ink', corner: 'tr' }, '原版', { showLogo: false, showExif: false, showDate: false }),
  V('simple-4', '极简 04', '参数', 'paramStack', 'simple4.webp', { theme: 'ink', corner: 'br' }, '原版', { showLogo: false, showExif: false, showDate: false }),

  // 卡片
  V('card-1', '卡片 01', '卡片', 'logoFooter', 'card1.webp', { theme: 'white', insetPct: 1.13, footerPct: 14.79, logoPct: 5.64, exifSizePct: 2.3, exifWidthPct: 29.0 }, '原版', { showLogo: true, showExif: true, showDate: false }, 'laika'),
  // card-2/3/4 reference photo windows are 1006x784 (1.2832), 1006x793 (1.2686) and 1002x780 (1.2846), not 4:3.
  V('card-2', '卡片 02', '卡片', 'logoFooter', 'card2.webp', { theme: 'white', insetPct: 1.1, footerPct: 10.84, logoPct: 5.36, photoAspect: 1.2832 }, '原版', { showLogo: true, showExif: false, showDate: false }, 'laika'),
  V('card-3', '卡片 03', '卡片', 'matteFooter', 'card3.webp', { theme: 'white', marginPct: 1.13, footerPct: 9.58, exifSizePct: 2.15, exifWidthPct: 28.3, overlayBrand: '', photoAspect: 1.2686 }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('card-4', '卡片 04', '卡片', 'matteFooter', 'card4.webp', { theme: 'white', marginPct: 1.41, footerPct: 10.9, footerOffsetPct: -0.15, logoPct: 5.5, brandTextSizePct: 2.95, overlayBrand: 'Leica', photoAspect: 1.2846 }, '原版', { showLogo: true, showExif: false, showDate: false }, 'laika'),

  // 人物
  V('person-1', '人物 01', '人物', 'profileFrame', 'person1.webp', { theme: 'white', profileMode: 'header', profileName: '鹤羽轻风', marginPct: 4.13, headerPct: 11.57, footerPct: 11.29, radiusPct: 1.4, shadow: 0.8, avatarPct: 5.6, nameSizePct: 2.44, brandCenterPct: 4.75, brandWidthPct: 33.6, overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: false, showDate: false }, 'hasu_long_black'),
  V('person-2', '人物 02', '人物', 'profileFrame', 'person2.webp', { theme: 'white', profileMode: 'header', profileName: '鹤羽轻风', marginPct: 4.9, headerPct: 12.43, footerPct: 12.43, radiusPct: 1.7, shadow: 0.9, avatarPct: 5, nameSizePct: 2.6, brandCenterPct: 5.74, brandWidthPct: 34.0, exifWidthPct: 32.0, exifSizePct: 2.06, exifScaleX: 1.285, exifOffsetPct: 4.2, overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_black'),
  V('person-3', '人物 03', '人物', 'profileFrame', 'person3.webp', { theme: 'white', profileMode: 'chip', profileName: '鹤羽轻风', marginPct: 3.1, marginBottomPct: 0, chipTopPct: 5.7, footerPct: 11.35, radiusPct: 1.35, shadow: 0, brandPlacement: 'footer', brandCenterPct: 5.4, avatarPct: 4.75, nameSizePct: 2.05, brandWidthPct: 30.0, exifWidthPct: 34.3, exifSizePct: 2.3, exifScaleX: 1.09, exifOffsetPct: 3.8, exifAlign: 'center', overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_black'),
  V('person-4', '人物 04', '人物', 'profileFrame', 'person4.webp', { theme: 'white', profileMode: 'chip', profileName: '鹤羽轻风', marginPct: 2.5, chipTopPct: 5.1, radiusPct: 1.1, shadow: 0, brandPlacement: 'inside', brandOffsetPct: 8.3, avatarPct: 4.5, nameSizePct: 2.3, brandWidthPct: 25.6, exifWidthPct: 28.1, exifSizePct: 1.70, exifScaleX: 1.33, exifOffsetPct: 3.4, exifAlign: 'center', overlayBrand: 'HASSELBLAD' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'hasu_long_white'),
  V('person-5', '人物 05', '人物', 'profileFrame', 'person5.webp', { theme: 'white', profileMode: 'footer', profileName: '鹤羽轻风', marginPct: 2.99, footerPct: 9.9, radiusPct: 1.35, shadow: 0, avatarPct: 5.4, nameSizePct: 2.25, exifWidthPct: 34.5, exifSizePct: 1.9, modelSizePct: 2.1, footerLogoPct: 10 }, '原版', { showLogo: true, showExif: true, showDate: false }, undefined, true),

  // 液态玻璃
  V('glass-white-1', '玻璃 01', '液态玻璃', 'sideCard', 'glass1.webp', { theme: 'white', sideLayout: 'text-left', marginPct: 4, radiusPct: 2, shadow: 0.8 }, '热门', undefined, undefined, true),
  V('glass-white-2', '玻璃 02', '液态玻璃', 'sideCard', 'glass2.webp', { theme: 'white', sideLayout: 'text-right', marginPct: 4, radiusPct: 2, shadow: 0.8 }, undefined, undefined, undefined, true),
  V('glass-white-3', '玻璃 03', '液态玻璃', 'sideCard', 'glass3.webp', { theme: 'white', sideLayout: 'split', marginPct: 4, radiusPct: 2, shadow: 0.8 }, undefined, undefined, undefined, true),
  V('glass-card-4', '玻璃 04', '液态玻璃', 'specFooter', 'glass4.webp', { theme: 'white', marginPct: 3, footerPct: 22, radiusPct: 1.5, shadow: 0.5 }, '热门', undefined, undefined, true),
  V('glass-pill-5', '玻璃 05', '液态玻璃', 'glass', 'glass5.webp', { theme: 'ink', glassLayout: 'pill' }),
  V('glass-liquid', '液态玻璃', '液态玻璃', 'glass', 'liquid_glass.webp', { theme: 'black', glassLayout: 'pill' }),
  V('glass-palette', '玻璃色卡', '液态玻璃', 'glass', 'glass_palette.webp', { theme: 'ink', glassLayout: 'palette', glassAlign: 'center' }),
  V('glass-card', '玻璃卡片', '液态玻璃', 'glass', 'glass.webp', { theme: 'ink', glassLayout: 'card', glassAlign: 'left' }),

  // 色卡
  V('color-1', '色卡 01', '色卡', 'paletteCard', 'color1.webp', { theme: 'white', marginPct: 2, radiusPct: 2, shadow: 0.4, palettePct: 22 }, '热门'),
  V('color-2', '色卡 02', '色卡', 'paletteCard', 'color2.webp', { theme: 'white', marginPct: 2, radiusPct: 2, shadow: 0.4, palettePct: 22 }),
  V('color-3', '色卡 03', '色卡', 'paletteCard', 'color3.webp', { theme: 'paper', marginPct: 2, radiusPct: 2, shadow: 0.4, palettePct: 22 }),
  V('color-reveal', '颜色显现', '色卡', 'splitReveal', 'color_reveal.png', { theme: 'paper' }),
  V('color-walk', '色卡漫步', '色卡', 'locationCard', 'color-walk.webp', { theme: 'paper', locationBg: '#405c66', locationFg: '#f5f2ea', locationTitle: '中国', locationTime: '08:00 AM', headerPct: 45 }),

  // 电影
  V('movie-1', '电影 01', '电影', 'movie', 'movie1.webp', { theme: 'black', barPct: 10 }, '原版', {
    title: '人生是旷野不是轨道',
    subtitle: 'Life is a wilderness, not a track'
  }),
  V('movie-2', '电影 02', '电影', 'movie', 'movie2.webp', { theme: 'black', barPct: 10 }, '原版', {
    title: '人生是旷野不是轨道',
    subtitle: 'Life is a wilderness, not a track'
  }),

  // 票据
  V('ticket-1', '票据 01', '票据', 'sideTicket', 'ticket.webp', { theme: 'ink', ticketBg: '#244231', ticketPanel: '#31533f', stubTitle: 'HANG ZHOU', stubLabel: 'GUANGYING 100' }),
  V('ticket-2', '票据 02', '票据', 'portraitTicket', 'ticket2.webp', { theme: 'stone', ticketBg: '#c7dfe6', ticketCard: '#ffffff', ticketInk: '#2d6f7c', stubTitle: 'QING DAO', stubLabel: 'GUANGYING 100' }),

  // 毕业
  V('grad-1', '毕业 01', '毕业', 'minimal', 'graduation1.webp', { theme: 'white', marginPct: 3, footerPct: 10, logoPos: 'footer-center' }),
  V('grad-2', '毕业 02', '毕业', 'brandFooter', 'graduation2.webp', { theme: 'white', logoPos: 'center', footerPct: 8 }),

  // 比例
  V('ratio-1', '比例 01', '比例', 'ratio', 'ratio.webp', { theme: 'ink', corner: 3 }),
  V('ratio-2', '比例 02', '比例', 'ratio', 'ratio2.webp', { theme: 'ink', corner: 2 }),
  V('ratio-3', '比例 03', '比例', 'ratio', 'ratio3.webp', { theme: 'ink', corner: 1 }),

  // 其他原版
  V('new-color-2', '自然诗篇', '留白', 'poetryCard', 'new-color2.webp', { theme: 'white', marginPct: 6, radiusPct: 2, headerPct: 18, footerPct: 14, shadow: 0.3 }, undefined, { title: "Nature's poetry" }),
  V('video-cover', '视频封面', 'FrameMark 精选', 'overlay', 'watermark_video_cover.jpg', { theme: 'ink' }, '视频'),

  // FrameMark 精选（自研补充）
  V('fm-editorial', '杂志编辑', 'FrameMark 精选', 'editorial', null, { theme: 'cream', marginPct: 8.5, footerPct: 14 }),
  V('fm-polaroid', '拍立得', 'FrameMark 精选', 'polaroid', null, { theme: 'paper', marginPct: 5.5, footerPct: 22 }),
  V('fm-film', '胶片齿孔', 'FrameMark 精选', 'film', null, { theme: 'black', grain: 0.16 }),
  V('fm-vertical', '竖排字幕', 'FrameMark 精选', 'vertical', null, { theme: 'ink' }),
  V('fm-overlay', '电影字幕', 'FrameMark 精选', 'overlay', null, { theme: 'ink' })
]

TEMPLATE_VARIANTS.push({
  id: 'oppo-polaroid',
  name: 'Polaroid · PLD',
  en: 'POLAROID PLD',
  category: '系统相机',
  base: 'polaroidFrame',
  params: {},
  text: { showLogo: false, showExif: true, showDate: true },
  renderedPreview: renderedPreview('oppo-polaroid'),
  badge: '近似',
  vendor: 'oppo',
  family: 'OPPO 相机',
  fidelity: 'approx'
})

const xiaomiStandardVariant = (id: string, name: string): TemplateVariant => ({
  id,
  name,
  en: 'XIAOMI STANDARD',
  category: '系统相机',
  base: 'xiaomiStandard',
  params: { theme: 'ink', standardPosition: 'bottom', insetPct: 4.44, offsetPct: 3.43, shadow: 0.6 },
  text: { showLogo: true, showModel: true, showExif: false, showDate: true },
  renderedPreview: renderedPreview(id),
  badge: '近似',
  autoLogo: true,
  vendor: 'xiaomi',
  family: '小米相机',
  fidelity: 'approx'
})
TEMPLATE_VARIANTS.push(xiaomiStandardVariant('xiaomi-standard-6', '小米标准 · 6'))
TEMPLATE_VARIANTS.push(xiaomiStandardVariant('xiaomi-standard-107', '小米标准 · 107'))
const xiaomiStackVariant = (id: string, name: string, showExif: boolean): TemplateVariant => ({
  ...xiaomiStandardVariant(id, name),
  params: { theme: 'ink', standardLayout: 'stack', showStandardExif: showExif, offsetPct: 2.8 },
  text: { showLogo: true, showModel: true, showExif: showExif, showDate: true }
})
TEMPLATE_VARIANTS.push(xiaomiStackVariant('xiaomi-standard-7', '小米标准 · 7', false))
TEMPLATE_VARIANTS.push(xiaomiStackVariant('xiaomi-standard-8', '小米标准 · 8', true))

export const VARIANT_MAP: Record<string, TemplateVariant> = Object.fromEntries(
  TEMPLATE_VARIANTS.map((v) => [v.id, v])
)

export const DEFAULT_VARIANT_ID = 'wm-classic'

export const VARIANT_CATEGORIES = [
  '系统相机',
  '厂商水印',
  '个人边框',
  '背景模糊',
  '水印边框',
  '参数',
  '留白',
  '卡片',
  '人物',
  '液态玻璃',
  '色卡',
  '电影',
  '票据',
  '毕业',
  '比例',
  'FrameMark 精选'
]

export function getVariant(id: string | undefined): TemplateVariant {
  return (id && VARIANT_MAP[id]) || VARIANT_MAP[DEFAULT_VARIANT_ID]
}

export function variantsByCategory(): { category: string; items: TemplateVariant[] }[] {
  return VARIANT_CATEGORIES.map((category) => ({
    category,
    items: TEMPLATE_VARIANTS.filter((v) => v.category === category)
  })).filter((g) => g.items.length > 0)
}
