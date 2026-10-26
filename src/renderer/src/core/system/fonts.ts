export const STOCK_FONT_SYS = '"StockSysSans", "StockThai", "OplusSans", "Inter", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'
export const STOCK_FONT_AVENIR = '"StockAvenir", "StockSysSans", "Inter", "Microsoft YaHei", sans-serif'
export const STOCK_FONT_BUTLER = '"StockButler", "StockSysSans", "Inter", "Microsoft YaHei", sans-serif'
export const STOCK_FONT_FZ = '"StockFZ", "StockSysSans", "Microsoft YaHei", "Noto Sans CJK SC", serif'
export const STOCK_FONT_OXANIUM = '"StockOxanium", "OplusSans", "Inter", "Microsoft YaHei", sans-serif'
export const STOCK_FONT_MISANS = '"StockMiSans", "OplusSans", "Inter", "Microsoft YaHei", sans-serif'
export const STOCK_FONT_LG = '"StockLG1056", "StockOxanium", "OplusSans", "Inter", serif'
export const STOCK_FONT_ZEISS = '"Zeiss", "StockSysSans", "Inter", "Microsoft YaHei", sans-serif'

const FONT_FAMILIES: Record<string, string> = {
  sys: STOCK_FONT_SYS,
  oplus: STOCK_FONT_SYS,
  avenir: STOCK_FONT_AVENIR,
  butler: STOCK_FONT_BUTLER,
  fz: STOCK_FONT_FZ,
  oxanium: STOCK_FONT_OXANIUM,
  misans: STOCK_FONT_MISANS,
  lg1056: STOCK_FONT_LG,
  zeiss: STOCK_FONT_ZEISS
}

export function stockFontFamily(key: string | undefined): string {
  return FONT_FAMILIES[String(key ?? 'sys').toLowerCase()] ?? STOCK_FONT_SYS
}

let promise: Promise<void> | null = null

export function ensureSystemFonts(): Promise<void> {
  if (!promise) {
    promise = Promise.all([
      document.fonts.load('400 40px "StockSysSans"'),
      document.fonts.load('500 40px "StockSysSans"'),
      document.fonts.load('700 40px "StockSysSans"'),
      document.fonts.load('400 40px "StockThai"', 'ส'),
      document.fonts.load('700 40px "StockThai"', 'ส'),
      document.fonts.load('400 40px "StockAvenir"'),
      document.fonts.load('500 40px "StockAvenir"'),
      document.fonts.load('600 40px "StockAvenir"'),
      document.fonts.load('700 40px "StockAvenir"'),
      document.fonts.load('500 40px "StockButler"'),
      document.fonts.load('700 40px "StockButler"'),
      document.fonts.load('400 40px "StockFZ"'),
      document.fonts.load('400 40px "StockOxanium"'),
      document.fonts.load('500 40px "StockOxanium"'),
      document.fonts.load('400 40px "StockMiSans"'),
      document.fonts.load('600 40px "StockMiSans"'),
      document.fonts.load('400 40px "StockLG1056"'),
      document.fonts.load('700 40px "Zeiss"')
    ])
      .then(() => document.fonts.ready)
      .then(() => undefined)
      .catch(() => undefined)
  }
  return promise
}
