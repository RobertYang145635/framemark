let promise: Promise<void> | null = null

export function ensureFonts(): Promise<void> {
  if (!promise) {
    promise = Promise.all([
      document.fonts.load('400 40px Inter'),
      document.fonts.load('600 40px Inter'),
      document.fonts.load('400 40px "Bebas Neue"'),
      document.fonts.load('400 40px "JetBrains Mono"'),
      document.fonts.load('400 40px Caveat'),
      document.fonts.load('400 40px YouShe'),
      document.fonts.load('400 40px OplusSans'),
      document.fonts.load('600 40px OplusSans'),
      document.fonts.load('700 40px OplusSans'),
      // Hand-written brand-footers now set their brand/model rows in Avenir
      // Next; it is a stock asset, so load it here instead of pulling in the
      // whole system font set.
      document.fonts.load('400 40px "StockAvenir"'),
      document.fonts.load('500 40px "StockAvenir"'),
      document.fonts.load('600 40px "StockAvenir"'),
      document.fonts.load('700 40px "StockAvenir"'),
      document.fonts.load('400 40px "StockMiSans"'),
      document.fonts.load('600 40px "StockMiSans"'),
      document.fonts.load('400 40px "StockVivo"'),
      document.fonts.load('600 40px "StockVivo"'),
      document.fonts.load('400 40px "StockSysSans"'),
      document.fonts.load('600 40px "StockSysSans"'),
      document.fonts.load('700 40px "StockSysSans"'),
      document.fonts.load('500 40px "StockButler"'),
      document.fonts.load('700 40px "StockButler"'),
      document.fonts.load('700 40px Zeiss'),
      // FONT_TECH is declared as Chakra Petch 600 italic only; the exif/tech
      // lines render with a normal 600 request, so load both to avoid a cold
      // first render falling back to OplusSans.
      document.fonts.load('600 40px "Chakra Petch"'),
      document.fonts.load('italic 600 40px "Chakra Petch"')
    ])
      .then(() => document.fonts.ready)
      .then(() => undefined)
      .catch(() => undefined)
  }
  return promise
}
