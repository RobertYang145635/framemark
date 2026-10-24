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
      document.fonts.load('700 40px Zeiss')
    ])
      .then(() => document.fonts.ready)
      .then(() => undefined)
      .catch(() => undefined)
  }
  return promise
}
