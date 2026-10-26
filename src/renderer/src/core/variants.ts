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
  },
  {
    id: 'stock-vivo-polaroid-frame',
    name: '拍立得 · 签名框',
    en: 'POLAROID',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-polaroid:frame' },
    text: { showMake: true, showModel: true, showExif: false, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-stamp-frame',
    name: '邮票 · 齿孔框',
    en: 'STAMP',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-stamp:frame' },
    text: { showMake: true, showModel: true, showExif: false, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-tone-frame',
    name: '色调 · 右侧栏',
    en: 'TONE',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-tone:frame' },
    text: { showMake: true, showModel: true, showExif: false, showDate: false, showLogo: true },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-geographic-overlay',
    name: '地理 · 信息叠加',
    en: 'GEOGRAPHIC',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-geographic:overlay' },
    text: { showMake: true, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-postcard-frame',
    name: '明信片 · 邮戳框',
    en: 'POST CARD',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-postcard:frame' },
    text: { showMake: true, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-chinoiserie-loc-frame',
    name: '国风 · 地点印章',
    en: 'CHINOISERIES LOC',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-chinoiserie-loc:frame' },
    text: { showMake: false, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-chinoiserie-signet-frame',
    name: '国风 · 文字印章',
    en: 'CHINOISERIES SIGNET',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-chinoiserie-signet:frame' },
    text: { showMake: false, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-chinoiserie-ink-frame',
    name: '国风 · 水墨印章',
    en: 'CHINOISERIES INK',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-chinoiserie-ink:frame' },
    text: { showMake: false, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-signature-overlay',
    name: '艺术 · 手写签名',
    en: 'SIGNATURE',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-signature:overlay' },
    text: { showMake: false, showModel: true, showExif: false, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-border-photo-frame',
    name: '个性 · 色卡边框',
    en: 'BORDER PHOTO',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-border-photo:frame' },
    text: { showMake: false, showModel: false, showExif: true, showDate: true, showLogo: false },
    badge: '近似',
    vendor: 'vivo',
    family: 'vivo 相机',
    fidelity: 'approx'
  },
  {
    id: 'stock-vivo-feature-photo-frame',
    name: '功能 · 参数边框',
    en: 'FEATURE PHOTO',
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: 'vivo-feature-photo:frame' },
    text: { showMake: false, showModel: true, showExif: true, showDate: true, showLogo: false },
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
  // logoPos:'right' parks the brand mark in the footer margin instead of on the
  // centred model/EXIF column; autoLogo resolves the mark from the photo's own
  // EXIF so a Panasonic frame cannot inherit the Leica roundel that the
  // vendor-xiaomi* variants force-set into store.design.logo.
  V('blur-classic', '经典模式', '背景模糊', 'blur', null, { theme: 'paper', marginPct: 3.5, radiusPct: 2, shadow: 6, footerPct: 11, blur: 26, logoPos: 'right' }, '原版', undefined, undefined, true),
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
    { showDate: false },
    undefined,
    // FIX #64b: own brand declaration (see wm-simple in 水印边框 below).
    true
  ),

  // 水印边框
  V(
    'wm-classic',
    '经典双行',
    '水印边框',
    'brandFooter',
    'new_watermark1.webp',
    {
      theme: 'white',
      logoPos: 'center',
      footerPct: 11.1,
      logoCenterX: 0.594,
      slots: 'brand,model,logo,exif,date',
      frame: '{"logoW":0.12,"ruleX":0.659,"rightX":0.677,"rightW":0.282}'
    },
    '热门',
    undefined,
    undefined,
    true
  ),
  V(
    'wm-simple',
    '简约单行',
    '水印边框',
    'brandFooter',
    null,
    { theme: 'white', logoPos: 'center', footerMode: 'simple', footerAuto: true },
    '实测',
    { showDate: false },
    undefined,
    // FIX #64b: this card's own brand declaration — the wordmark follows the
    // photo's EXIF brand (same rule as wm-classic/wm-black/wm-canon above),
    // resolved per render by scene.ts from THIS style. It is not inherited from
    // the global `design.logo`, which is what leaked one card's mark onto the
    // next one (assets.ts "Hand-card brand wordmarks").
    true
  ),
  V(
    'wm-black',
    '黑底日期',
    '水印边框',
    'brandFooter',
    'new-watermark2.webp',
    {
      theme: 'black',
      logoPos: 'center',
      footerPct: 0,
      footerMode: 'overlay',
      // 'lens' stays in the stack: the reference (new-watermark2.webp) right block
      // is 3 separate rows — EXIF 74.06%H, lens line 'LEICA + 24MM F1.8' 78.39%H,
      // date 81.84%H — so dropping the slot deleted a real reference row.
      // The duplicate is removed at content level instead: lensRowMode 'reference'
      // makes that row the optics designation (brand/lens name + focal + aperture)
      // instead of restating the camera model + the EXIF run.
      slots: 'title,logo,brand,exif,lens,date',
      lensRowMode: 'reference',
      frame:
        '{"ovLeftX":0.0913,"ovTextX":0.1293,"ovLogoW":0.0323,"ovLogoH":0.0519,"ovLogoCenterY":0.7839,"ovTitleY":0.7637,"ovBrandY":0.7968,"ovRightCenterX":0.749,"ovExifY":0.7507,"ovLensY":0.7925,"ovDateY":0.8285}'
    },
    undefined,
    undefined,
    undefined,
    true
  ),
  V(
    'wm-canon',
    '经典单行',
    '水印边框',
    'brandFooter',
    'watermark1.webp',
    {
      theme: 'white',
      logoPos: 'center',
      footerPct: 11.1,
      logoCenterX: 0.594,
      slots: 'brand,model,logo,exif,date',
      frame: '{"logoW":0.12,"ruleX":0.659,"rightX":0.677,"rightW":0.282}'
    },
    undefined,
    undefined,
    undefined,
    true
  ),
  // FIX #64b: wm-left / wm-wide / wm-right are the same 水印边框 family as
  // wm-classic, wm-black and wm-canon, so they declare `autoLogo` like their
  // siblings do: their wordmark is resolved from the photo they are painted on,
  // through the brand registry — never from the global `design.logo`.
  V('wm-left', '左标双行', '水印边框', 'brandFooter', 'watermark2.webp', {
    theme: 'white',
    logoPos: 'center',
    footerPct: 11.13,
    logoCenterX: 0.5,
    slots: 'model,logo,exif',
    frame: '{"logoW":0.11,"rule":0,"bandRule":0,"rightX":0.7383,"rightW":0.25,"modelY":0.48,"exifY":0.48}'
  }, undefined, undefined, undefined, true),
  V('wm-wide', '白边双行', '水印边框', 'brandFooter', 'watermark3.webp', {
    theme: 'white',
    logoPos: 'center',
    footerPct: 11.13,
    logoCenterX: 0.5,
    slots: 'logo',
    frame: '{"logoW":0.11,"rule":0,"bandRule":0}'
  }, undefined, undefined, undefined, true),
  V('wm-right', '右标双行', '水印边框', 'brandFooter', 'watermark4.webp', {
    theme: 'white',
    logoPos: 'right',
    footerPct: 8.6,
    slots: 'brand,logo,exif',
    frame:
      '{"borderPct":1.32,"borderTopPct":0.88,"logoW":0.068,"logoCx":0.6304,"leftX":0.0596,"ruleX":0.6738,"rightX":0.6924,"rightW":0.30,"brandY":0.58,"exifY":0.58,"bandRule":0}'
  }, undefined, undefined, undefined, true),

  // 留白
  V('white-1', '纯白 01', '留白', 'matteOverlay', 'whiteborder1.webp', { theme: 'white', marginPct: 4.3, marginTopPct: 3.47, marginBottomPct: 4.44, radiusPct: 2.0, overlayMode: 'brand', brandWidthPct: 26.4, brandOffsetPct: 5.6, overlayBrand: 'HASSELBLAD' }, '热门', { showLogo: true, showExif: false, showDate: false }, 'hasu_long_white'),
  V('white-2', '纯白 02', '留白', 'matteOverlay', 'whiteborder2.webp', { theme: 'white', marginPct: 4.29, marginTopPct: 3.32, radiusPct: 2.0, overlayMode: 'brand-exif', brandWidthPct: 31.0, brandOffsetPct: 10.0, exifWidthPct: 36.0, exifSizePct: 1.80, exifScaleX: 1.47, exifOffsetPct: 5.3, overlayBrand: 'FUJIFILM', exifAlign: 'center' }, '原版', { showLogo: true, showExif: true, showDate: false }, 'fushi_white'),
  V('white-3', '纯白 03', '留白', 'matteOverlay', 'whiteborder3.webp', { theme: 'white', marginPct: 4.29, marginTopPct: 3.46, radiusPct: 1.8, overlayMode: 'exif', exifWidthPct: 38.0, exifSizePct: 2.03, exifScaleX: 1.49, exifOffsetPct: 5.0, exifAlign: 'center' }, '原版', { showLogo: false, showExif: true, showDate: false }),
  V('white-4', '米白 04', '留白', 'matteOverlay', 'whiteborder4.webp', { theme: 'white', marginPct: 2.14, marginTopPct: 1.74, marginBottomPct: 2.55, radiusPct: 1.3, overlayMode: 'none' }, '原版', { showLogo: false, showExif: false, showDate: false }),

  // 参数
  V('simple-1', '极简 01', '参数', 'paramStack', 'simple1.webp', { theme: 'ink', corner: 'tl', canvasAspect: 1.3045 }, '热门', { showLogo: false, showExif: false, showDate: false }),
  V('simple-2', '极简 02', '参数', 'paramStack', 'simple2.webp', { theme: 'ink', corner: 'bl', canvasAspect: 1.3045 }, '原版', { showLogo: false, showExif: false, showDate: false }),
  // simple-3: the reference block sits 5.18 %W from the right edge and 4.8 %H
  // from the top; the shared 3 % pad landed at 2.95 %W / 3.28 %H.
  V('simple-3', '极简 03', '参数', 'paramStack', 'simple3.webp', { theme: 'ink', corner: 'tr', paramPadXPct: 5.2, paramPadYPct: 4.45 }, '原版', { showLogo: false, showExif: false, showDate: false }),
  V('simple-4', '极简 04', '参数', 'paramStack', 'simple4.webp', { theme: 'ink', corner: 'br', canvasAspect: 1.3045 }, '原版', { showLogo: false, showExif: false, showDate: false }),

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
  // glass-white-1/2/3 reference previews are a 0.692 (2:3) portrait photo card,
  // 5.56%S inset, with 11.70%S text-row pitch and the row block anchored at
  // 14.16 / 67.48 / 77.93%W (text-left / text-right / split).
  V('glass-white-1', '玻璃 01', '液态玻璃', 'sideCard', 'glass1.webp', { theme: 'white', sideLayout: 'text-left', marginPct: 5.56, radiusPct: 2, shadow: 0.8, cardAspect: 0.692, rowGap: 6.5, rowsX: 14.16 }, '热门', undefined, undefined, true),
  V('glass-white-2', '玻璃 02', '液态玻璃', 'sideCard', 'glass2.webp', { theme: 'white', sideLayout: 'text-right', marginPct: 5.56, radiusPct: 2, shadow: 0.8, cardAspect: 0.692, rowGap: 6.5, rowsX: 67.48 }, undefined, undefined, undefined, true),
  V('glass-white-3', '玻璃 03', '液态玻璃', 'sideCard', 'glass3.webp', { theme: 'white', sideLayout: 'split', marginPct: 5.56, radiusPct: 2, shadow: 0.8, cardAspect: 0.692, rowGap: 6.5, rowsX: 77.93 }, undefined, undefined, undefined, true),
  // glass-card-4 ref footer: chip-row ink span 27.0%W (3 cells => contentW 32.43),
  // divider at 601/1200 = 50.08%W (the audit's 44.83%W read the word "z30" as the
  // divider; pixel run 538..584 is text, x=601 is the 1px rule), divider->logo 1.83%S.
  V('glass-card-4', '玻璃 04', '液态玻璃', 'specFooter', 'glass4.webp', { theme: 'white', marginPct: 3, footerPct: 22, radiusPct: 1.5, shadow: 0.5, contentW: 32.43, sepX: 50, logoGap: 1.83 }, '热门', undefined, undefined, true),
  // FIX #64b: the 液态玻璃 cards that paint a brand mark (glassSideCard/glass)
  // declare `autoLogo` like glass-white-1..3 and glass-card-4 already do, so the
  // mark resolves from the photo through the brand registry instead of from the
  // global `design.logo`.
  V('glass-pill-5', '玻璃 05', '液态玻璃', 'glassSideCard', 'glass5.webp', { theme: 'ink', canvasAspect: 1.4971, cardAspect: 0.6903, cardSide: 'right', contentSide: 'left', cardHeightPct: 88.7, cardWidthPct: 0, cardInsetPct: 13.18, cardRadiusPct: 2.4, cardMattePct: 1.1, blur: 30, tintAlpha: 0.24, rowSet: 'fmm', textInsetPct: 31.1, brandInsetPct: 31.1, brandWidthPct: 26.0, brandHeightPct: 4.6, brandTopPct: 28.5, rowsTopPct: 44.3, rowBoxWPct: 9.1, rowBoxHPct: 7.4, rowPitchPct: 13.0, rowValueGapPct: 10.4, rowLabelPct: 3.0, rowValuePct: 4.0 }, undefined, undefined, undefined, true),
  V('glass-liquid', '液态玻璃', '液态玻璃', 'glass', 'liquid_glass.webp', { theme: 'black', glassLayout: 'pill', pillOrder: 'stack' }, undefined, undefined, undefined, true),
  // glass-palette ref is a 3:4 portrait canvas (1500x2000) with one glass panel at
  // 89.2%W x 29.7%H (6.5%H bottom) holding a 3-col x 2-row grid of 11%W discs with
  // hex labels. It uses its own local def so the portrait canvas and the wide
  // panel do not leak into `glass` (glass-liquid stays a landscape pill).
  V('glass-palette', '玻璃色卡', '液态玻璃', 'glassPalettePortrait', 'glass_palette.webp', { theme: 'ink', panelWidthPct: 89.2, panelHeightPct: 29.7, panelBottomPct: 6.5, swatchCols: 3, swatchRows: 2, swatchPct: 12.3 }),
  V('glass-card', '玻璃卡片', '液态玻璃', 'glassSideCard', 'glass.webp', { theme: 'ink', cardAspect: 1.3333, cardSide: 'right', contentSide: 'left', cardWidthPct: 56.25, cardHeightPct: 0, cardInsetPct: 6.35, cardRadiusPct: 1.6, blur: 26, tintAlpha: 0.2, rowSet: 'fiso', rowValueGapPct: 6.0, rowValuePct: 5.6 }, undefined, undefined, undefined, true),

  // 色卡
  V('color-1', '色卡 01', '色卡', 'paletteCard', 'color1.webp', { theme: 'white', marginPct: 2, radiusPct: 2, shadow: 0.4, palettePct: 22, paletteRowPct: 120.5, paletteGapPct: 3.6 }, '热门'),
  V('color-2', '色卡 02', '色卡', 'paletteCard', 'color2.webp', { theme: 'white', marginPct: 2, radiusPct: 0, shadow: 0.4, palettePct: 22, paletteRowPct: 120.4, paletteGapPct: 4.1 }),
  V('color-3', '色卡 03', '色卡', 'paletteCard', 'color3.webp', { theme: 'paper', marginPct: 0, radiusPct: 0, shadow: 0, palettePct: 16.1, paletteRowPct: 115.4, paletteGapPct: 0 }),
  // color_reveal ref is a single 360x202 (16:9) colour still with a vertical
  // 3-dot picker, not the legacy grayscale split / horizontal dots.
  V('color-reveal', '颜色显现', '色卡', 'splitReveal', 'color_reveal.png', {
    theme: 'paper',
    canvasAspect: 16 / 9,
    frameMode: 'single',
    dotOrientation: 'vertical',
    dotCount: 3
  }),
  V('color-walk', '色卡漫步', '色卡', 'locationCard', 'color-walk.webp', { theme: 'paper', locationBg: '#405c66', locationFg: '#f5f2ea', locationTitle: '中国', locationTime: '08:00 AM', headerPct: 45 }),

  // 电影
  V(
    'movie-1',
    '电影 01',
    '电影',
    'movie',
    'movie1.webp',
    { theme: 'black', photoAspect: 2.34, barPct: 11.7, subtitleMode: 'bar' },
    '原版',
    { title: '人生是旷野不是轨道', subtitle: 'Life is a wilderness,not a track' }
  ),
  V(
    'movie-2',
    '电影 02',
    '电影',
    'movie',
    'movie2.webp',
    { theme: 'black', photoAspect: 2.34, barPct: 7.9, subtitleMode: 'in-photo' },
    '原版',
    { title: '人生是旷野不是轨道', subtitle: 'Life is a wilderness,not a track' }
  ),

  // 票据
  V('ticket-1', '票据 01', '票据', 'sideTicket', 'ticket.webp', { theme: 'ink', ticketBg: '#244231', ticketPanel: '#31533f', stubTitle: 'HANG ZHOU', stubLabel: 'GUANGYING 100' }),
  V('ticket-2', '票据 02', '票据', 'portraitTicket', 'ticket2.webp', { theme: 'stone', ticketBg: '#c7dfe6', ticketCard: '#ffffff', ticketInk: '#2d6f7c', stubTitle: 'QING DAO', stubLabel: 'GUANGYING 100', stubTitleSize: 0.066 }),

  // 毕业
  V(
    'grad-1',
    '毕业 01',
    '毕业',
    'gradFooter',
    'graduation1.webp',
    { theme: 'white', footerPct: 12, sticker: 'graduation1' },
    undefined,
    { showLogo: false, showDate: false, showExif: true, showMake: true, showModel: true }
  ),
  V(
    'grad-2',
    '毕业 02',
    '毕业',
    'gradFooter',
    'graduation2.webp',
    { theme: 'white', footerPct: 12, sticker: 'graduation2' },
    undefined,
    { showLogo: false, showDate: false, showExif: true, showMake: true, showModel: true }
  ),

  // 比例
  V(
    'ratio-1',
    '比例 01',
    '比例',
    'ratioMatte',
    'ratio.webp',
    {
      theme: 'ink',
      photoAspect: 1.485,
      sideMarginPct: 9.03,
      topMarginPct: 5.72,
      blur: 22,
      tintAlpha: 0.9,
      vignette: 0.32,
      brandSizePct: 3.43,
      brandGapPct: 4.62,
      metaSizePct: 1.8,
      metaGapPct: 8.5,
      metaAlphaPct: 80
    },
    undefined,
    { showLogo: false, showDate: false }
  ),
  V(
    'ratio-2',
    '比例 02',
    '比例',
    'ratioMatte',
    'ratio2.webp',
    {
      theme: 'cream',
      photoAspect: 1.7485,
      sideMarginPct: 6.54,
      topMarginPct: 7.04,
      blur: 26,
      tintAlpha: 0.06,
      vignette: 0.1,
      brandSizePct: 5.25,
      brandGapPct: 5.72,
      brandTracking: 0.06,
      metaSizePct: 3.2,
      metaGapPct: 12.17,
      metaAlphaPct: 92
    },
    undefined,
    { showLogo: false, showExif: false }
  ),
  V(
    'ratio-3',
    '比例 03',
    '比例',
    'ratioBlurSquare',
    'ratio3.webp',
    { theme: 'ink', blur: 30, tintAlpha: 0.18, squarePct: 88.27, radiusPct: 2.6 },
    undefined,
    { showMake: false, showModel: false, showExif: false, showDate: false, showLogo: false }
  ),

  // 其他原版
  V('new-color-2', '自然诗篇', '留白', 'poetryCard', 'new-color2.webp', { theme: 'white', marginPct: 6.5, radiusPct: 2, headerPct: 15.5, footerPct: 15.9, shadow: 0.3, photoAspect: 1.0811 }, undefined, { title: "Nature's poetry" }),
  V(
    'video-cover',
    '视频封面',
    'FrameMark 精选',
    'bareCover',
    'watermark_video_cover.jpg',
    { theme: 'ink' },
    '视频',
    { showLogo: false, showExif: false, showDate: false }
  ),

  // FrameMark 精选（自研补充）
  V('fm-editorial', '杂志编辑', 'FrameMark 精选', 'editorial', null, { theme: 'cream', marginPct: 8.5, footerPct: 14 }),
  V('fm-polaroid', '拍立得', 'FrameMark 精选', 'polaroid', null, { theme: 'paper', marginPct: 5.5, footerPct: 22 }),
  V('fm-film', '胶片齿孔', 'FrameMark 精选', 'film', null, { theme: 'black', grain: 0.16 }),
  // FIX #64b: fm-vertical paints a brand mark (templates.ts vertical render), so
  // it declares where that mark comes from — this photo's EXIF brand.
  V('fm-vertical', '竖排字幕', 'FrameMark 精选', 'vertical', null, { theme: 'ink' }, undefined, undefined, undefined, true),
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
