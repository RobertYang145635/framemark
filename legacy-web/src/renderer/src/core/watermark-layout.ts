import { pct } from './draw'

export interface SimpleFooterMetrics {
  footerH: number
  pad: number
  gap: number
  fontLeft: number
  fontRight: number
  fontCenter: number
  logoW: number
  logoH: number
  scale: number
}

// Values reverse-engineered from the original app's 3000px-wide exports:
// 3:2 -> 335px footer, 4:5/2:3 -> 295px, 1:1 (2800px) -> 275px, 16:9 -> 357px.
// Left/right text and logo boxes scale with the long edge; footer height also
// grows with landscape excess so wide frames keep the same optical weight.
export function simpleFooterMetrics(pw: number, ph: number, params: Record<string, unknown> = {}): SimpleFooterMetrics {
  const long = Math.max(pw, ph)
  const aspect = pw / Math.max(1, ph)
  const scale = long / 3000
  const textScale = pct(params.footerTextScale, 1) || 1
  const autoH = 0.1 * long - 5 + 80 * Math.max(0, aspect - 1)
  const minH = Math.max(60, (54 + 2 * 96 * 0.6) * scale)
  // the original exports carry one extra pixel at the photo/footer seam
  const footerH = Math.ceil(Math.max(minH, autoH) * textScale) + 1
  return {
    footerH,
    pad: 96 * scale,
    gap: 24 * scale,
    fontLeft: 56 * scale * textScale,
    fontRight: 52 * scale * textScale,
    fontCenter: 52 * scale * textScale,
    logoW: 352 * scale * textScale,
    logoH: 54 * scale * textScale,
    scale
  }
}
