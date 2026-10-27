import type { Rect, TemplateDef, TemplateRenderEnv } from '../types'
import { systemCamera } from './system/template'
import {
  FONT_UI,
  FONT_AVENIR,
  FONT_SERIF,
  FONT_DISPLAY,
  FONT_MONO,
  FONT_HAND,
  FONT_CN,
  FONT_VENDOR,
  FONT_TECH,
  THEMES,
  themeOf,
  shortSide,
  marginOf,
  radiusOf,
  pct,
  setFont,
  setTracking,
  resetTracking,
  roundRectPath,
  fillRoundRect,
  line,
  drawPhoto,
  drawPhotoCover,
  drawBlurredBackdrop,
  drawBrandLogo,
  modelWithoutMake,
  pickTitle,
  fitSize,
  drawFitted,
  paintedTextScale,
  drawMeta,
  drawSwatches,
  drawPaletteTiles,
  extractPalette,
  drawGrain,
  drawBarcode,
  drawSprockets,
  drawVerticalText
} from './draw'
import { cameraLine, exifLine, formatDate, formatDateOnly, formatAperture, formatShutter } from './exif'
import { loadImage } from './images'
import { simpleFooterMetrics } from './watermark-layout'

const POLAROID_URLS = import.meta.glob('../assets/system/oppo/camerapro/polaroid/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>

// Real graduation stickers shipped in the original APK (260x90 each).
const GRAD_STICKER_URLS = import.meta.glob('../assets/graduation/*.webp', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>
const GRAD_STICKER_FILES: Record<string, string> = {
  graduation1: 'graduation1.webp',
  graduation2: 'graduation2.webp'
}

const POLAROID_META = [
  { file: 'pld_frame_1_1_landscape.webp', w: 960, h: 1256, win: [42, 87, 876, 875], aspect: 876 / 875, orient: 'landscape' },
  { file: 'pld_frame_4_3_landscape.webp', w: 1248, h: 1256, win: [42, 87, 1164, 875], aspect: 1164 / 875, orient: 'landscape' },
  { file: 'pld_frame_16_9_landscape.webp', w: 1224, h: 942, win: [30, 64, 1164, 659], aspect: 1164 / 659, orient: 'landscape' },
  { file: 'pld_frame_wide_landscape.webp', w: 1200, h: 616, win: [17, 40, 1166, 435], aspect: 1166 / 435, orient: 'landscape' },
  { file: 'pld_frame_4_3_portrait.webp', w: 960, h: 1544, win: [42, 87, 876, 1163], aspect: 876 / 1163, orient: 'portrait' },
  { file: 'pld_frame_16_9_portrait.webp', w: 720, h: 1446, win: [30, 64, 660, 1163], aspect: 660 / 1163, orient: 'portrait' },
  { file: 'pld_frame_wide_portrait.webp', w: 472, h: 1344, win: [18, 38, 436, 1170], aspect: 436 / 1170, orient: 'portrait' }
]

function choosePolaroidFrame(pw: number, ph: number) {
  const target = pw / ph
  if (Math.abs(target - 1) < 0.06) return POLAROID_META[0]
  const orient = target > 1 ? 'landscape' : 'portrait'
  const candidates = POLAROID_META.filter((m) => m.orient === orient)
  return candidates.reduce((best, m) =>
    Math.abs(Math.log(m.aspect / target)) < Math.abs(Math.log(best.aspect / target)) ? m : best
  )
}

const lightThemes = [
  { id: 'white', label: '纯白', swatch: THEMES.white.bg },
  { id: 'paper', label: '纸白', swatch: THEMES.paper.bg },
  { id: 'cream', label: '米色', swatch: THEMES.cream.bg },
  { id: 'stone', label: '灰米', swatch: THEMES.stone.bg },
  { id: 'ink', label: '墨黑', swatch: THEMES.ink.bg },
  { id: 'black', label: '纯黑', swatch: THEMES.black.bg }
]
const darkThemes = [
  { id: 'ink', label: '墨黑', swatch: THEMES.ink.bg },
  { id: 'black', label: '纯黑', swatch: THEMES.black.bg },
  { id: 'paper', label: '纸白', swatch: THEMES.paper.bg },
  { id: 'cream', label: '米色', swatch: THEMES.cream.bg }
]

/**
 * Footer title without the generic camera-line placeholder. A photo with no
 * EXIF has nothing to substitute; painting "FRAME"/"FRAMEMARK" invents content
 * (and used to keep an empty right column visible via its divider).
 */
function footerTitle(env: Pick<TemplateRenderEnv, 'text' | 'exif'>): string {
  const typed = env.text.title.trim()
  if (typed) return typed
  const e = env.exif
  const hasInfo = Boolean(e.make || e.model || e.lens || e.focal || e.iso || e.dateTime)
  return hasInfo ? titleOf(env as unknown as Parameters<typeof titleOf>[0]) : ''
}

export function titleOf(env: TemplateRenderEnv | Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>): string {
  if (env.text.hidden?.includes('title')) return ''
  const t = env.text.title.trim()
  if (t) return t
  const cam = [
    env.text.showMake ? env.exif.make : '',
    env.text.showModel ? modelWithoutMake(env.exif.make, env.exif.model) : ''
  ]
    .filter(Boolean)
    .join(' ')
  return cam || cameraLine(env.exif, 'FRAME')
}

function subLine(env: TemplateRenderEnv): string {
  return env.text.subtitle.trim()
}

function exifText(env: TemplateRenderEnv): string {
  return env.text.showExif ? exifLine(env.exif) : ''
}

function dateText(env: TemplateRenderEnv): string {
  return env.text.showDate ? formatDateOnly(env.exif.dateTime) : ''
}

function logoScale(env: TemplateRenderEnv | Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>): number {
  return 0.68 + pct(env.params.logoScale, 5) * 0.064
}

function metaColor(theme: { fg: string; sub: string }, _env: TemplateRenderEnv): { fg: string; sub: string } {
  return { fg: theme.fg, sub: theme.sub }
}

/* ------------------------------------------------------------------ *
 * Measured-extent flow for the hand cards (#2, fix-round2)
 *
 * `fm-editorial` and `ticket-1` place their rows at absolute pixel offsets
 * (`top * 0.30`, `cardH * (0.2395 + i * 0.1208)`). `scene.ts/scaledContext`
 * scales the FONT with 字号缩放 and leaves the coordinates alone, so a gap that
 * cleared the ink at 1.0 stops clearing at 1.22 — three SEVERE overlaps. These
 * two helpers let a row be positioned from the box its text ACTUALLY paints.
 *
 * `measureFitted()` runs the same fit ladder `drawFitted()` will run and returns
 * both the size it lands on and the width that size paints. Because `setFont`
 * and `measureText` go through the scaled proxy, `width` (and `size * scale`)
 * are PAINTED pixels — the same canvas space the overlap detector measures in.
 *
 * `INK_ABOVE / INK_BELOW` are the em box (0.93 em tall, split 0.72 above /
 * 0.21 below the alphabetic baseline; 0.465/0.465 around a `middle` baseline).
 * The detector's `inkBox()` uses exactly this model, so clearing an em box
 * clears the measurement — and it is conservative against real glyph extents.
 * ------------------------------------------------------------------ */

const INK_ABOVE = 0.72
const INK_BELOW = 0.21
const INK_ABOVE_MIDDLE = 0.465

function inkTops(size: number, baseline: 'alphabetic' | 'middle'): number {
  return baseline === 'middle' ? size * INK_ABOVE_MIDDLE : size * INK_ABOVE
}

interface FittedRun {
  /** the size the ink is painted at (design size x 字号缩放) */
  size: number
  /** the width that size paints, in canvas px */
  width: number
}

function measureFitted(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  size: number,
  min: number,
  family: string,
  weight: string | number,
  tracking = 0
): FittedRun {
  if (!text) return { size: 0, width: 0 }
  const scale = paintedTextScale(ctx)
  const fitted = fitSize(ctx, text, maxWidth, size, min, family, weight, tracking, scale)
  // identical state to the paint pass in `drawFitted`, so the advance matches
  setTracking(ctx, tracking * fitted)
  setFont(ctx, fitted, family, weight)
  const width = ctx.measureText(text).width
  resetTracking(ctx)
  return { size: fitted * scale, width }
}

const minimal: TemplateDef = {
  id: 'minimal',
  name: '留白 · 极简',
  en: 'MARGIN',
  category: '留白',
  description: '细画框与底部参数区，干净、克制，适合街拍与日常。',
  defaults: {
    marginPct: 6.5,
    radiusPct: 1.2,
    shadow: 3,
    footerPct: 12,
    theme: 'white',
    logoScale: 5,
    footerAlign: 'center',
    logoPos: 'none',
    borderStyle: 'none'
  },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 6.5)) / 100
    const hasLogo = String(env.params.logoPos ?? 'none') !== 'none'
    const bottom = Math.max((S * pct(env.params.footerPct, 12)) / 100, S * (hasLogo ? 0.21 : 0.16))
    return { W: env.pw + 2 * m, H: env.ph + m + bottom }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = marginOf(env)
    const bottom = H - ph - m
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, m, pw, ph, radiusOf(env), pct(env.params.shadow, 3))
    if (String(env.params.borderStyle ?? 'none') === 'thin') {
      ctx.save()
      ctx.strokeStyle = theme.line
      ctx.lineWidth = Math.max(1, S * 0.0008)
      roundRectPath(ctx, m, m, pw, ph, radiusOf(env))
      ctx.stroke()
      ctx.restore()
    }
    const align = (String(env.params.footerAlign ?? 'center') as 'left' | 'center' | 'right') || 'center'
    const logoPos = String(env.params.logoPos ?? 'none')
    const hasLogo = logoPos !== 'none' && env.text.showLogo
    const anchorX = align === 'left' ? m : align === 'right' ? W - m : W / 2
    if (hasLogo) {
      const lw = S * 0.2 * logoScale(env)
      const lh = bottom * 0.24
      const lx = logoPos === 'footer-left' ? m : logoPos === 'footer-right' ? W - m - lw : W / 2 - lw / 2
      drawBrandLogo(env, lx, m + ph + bottom * 0.10, lw, lh, theme.fg)
    }
    let y = m + ph + bottom * (hasLogo ? 0.46 : 0.30)
    if (align === 'center') {
      line(ctx, anchorX - S * 0.026, y - S * 0.11, anchorX + S * 0.026, y - S * 0.11, theme.accent, Math.max(1, S * 0.0013))
    }
    y += drawFitted(ctx, titleOf(env).toUpperCase(), anchorX, y, W - 2 * m, S * 0.026, theme.fg, {
      align,
      weight: 600,
      tracking: 0.08,
      family: FONT_UI,
      min: S * 0.014
    })
    const ex = exifText(env)
    if (ex) {
      y += bottom * 0.18
      y += drawFitted(ctx, ex, anchorX, y, W - 2 * m, S * 0.018, theme.sub, {
        align,
        family: FONT_MONO,
        min: S * 0.01
      })
    }
    const dt = dateText(env)
    if (dt) {
      y += bottom * 0.15
      drawFitted(ctx, dt, anchorX, y, W - 2 * m, S * 0.015, theme.sub, {
        align,
        family: FONT_MONO,
        min: S * 0.009
      })
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const sideCard: TemplateDef = {
  id: 'sideCard',
  name: '侧栏卡片',
  en: 'SIDE CARD',
  category: '液态玻璃',
  description: '固定 3:2 白卡：照片保比例，品牌与 F/MM/S 参数在侧栏。',
  defaults: {
    theme: 'white',
    sideLayout: 'text-right',
    marginPct: 4,
    radiusPct: 2,
    shadow: 0.8
  },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    return { W: S * 1.5, H: S }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const S = Math.min(pw, ph)
    const theme = themeOf(env.params)
    const m = (S * pct(env.params.marginPct, 4)) / 100
    const radius = (S * pct(env.params.radiusPct, 2)) / 100
    const shadow = pct(env.params.shadow, 0.8)
    const layout = String(env.params.sideLayout ?? 'text-right')
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)

    // Portrait card mode (glass-white-1/2/3): the reference card *is* the photo,
    // a 2:3 (0.692) portrait card inset 5.56%S and centred on 1/3, 1/2 or 2/3 W.
    // cardAspect 0 keeps the legacy landscape card for every other user.
    const cardAspect = pct(env.params.cardAspect, 0)
    const portraitCard = cardAspect > 0
    let phH: number
    let phW: number
    let photoX: number
    if (portraitCard) {
      // marginPct doubles as the card inset, so the margin control stays live.
      phH = H - 2 * m
      phW = phH * cardAspect
      const autoCx = layout === 'text-left' ? 2 / 3 : layout === 'text-right' ? 1 / 3 : 0.5
      const cxPct = pct(env.params.cardCx, 0)
      photoX = W * (cxPct > 0 ? cxPct / 100 : autoCx) - phW / 2
    } else {
      const maxW = W * (layout === 'split' ? 0.52 : 0.58)
      phH = H - 2 * m
      phW = (phH * pw) / ph
      if (phW > maxW) {
        phW = maxW
        phH = (phW * ph) / pw
      }
      photoX = layout === 'text-right' ? m : layout === 'text-left' ? W - m - phW : (W - phW) / 2
    }
    const photoY = (H - phH) / 2
    if (portraitCard) drawPhotoCover(env, photoX, photoY, phW, phH, radius, shadow)
    else drawPhoto(env, photoX, photoY, phW, phH, radius, shadow)

    const rows = [
      ['F', formatAperture(env.exif.aperture).replace(/^F/, '')],
      ['MM', env.exif.focal ? String(Math.round(env.exif.focal)) : ''],
      ['S', formatShutter(env.exif.shutter).replace(/s$/, '')]
    ]

    if (portraitCard) {
      // Reference text block: label box 8.6%S x 5.2%S, 11.70%S row pitch
      // (boxH + rowGap), value column centred 20.32%S right of the row left,
      // brand left-aligned with the rows (text layouts) or centred (split).
      const boxW = S * 0.086
      const boxH = S * 0.052
      const rowGap = (S * pct(env.params.rowGap, 6.5)) / 100
      const rowPitch = boxH + rowGap
      const labelSize = S * 0.023
      const valueSize = S * 0.035
      const valueCt = S * 0.2032
      const split = layout === 'split'
      ctx.save()
      setFont(ctx, valueSize, FONT_MONO, 400)
      let valueW = 0
      for (const [, value] of rows) valueW = Math.max(valueW, ctx.measureText(value).width)
      ctx.restore()
      const blockW = valueCt + valueW / 2
      const rowsTop = (H * pct(env.params.rowsTop, split ? 39.4 : 43.13)) / 100
      const brandCY = (H * pct(env.params.brandCY, split ? 49.9 : 30.55)) / 100
      const panelL = photoX
      const panelR = W - (photoX + phW)
      const explicitX = pct(env.params.rowsX, 0)
      let blockLeft =
        explicitX > 0
          ? (W * explicitX) / 100
          : split
            ? photoX + phW + (panelR - blockW) / 2
            : layout === 'text-left'
              ? (panelL - blockW) / 2
              : photoX + phW + (panelR - blockW) / 2
      blockLeft = Math.max(S * 0.02, Math.min(blockLeft, W - blockW - S * 0.02))
      const valueCx = blockLeft + valueCt
      for (let i = 0; i < rows.length; i++) {
        const [label, value] = rows[i]
        const y = rowsTop + i * rowPitch
        ctx.save()
        ctx.strokeStyle = theme.line
        ctx.lineWidth = Math.max(1, S * 0.0014)
        roundRectPath(ctx, blockLeft, y, boxW, boxH, boxH * 0.22)
        ctx.stroke()
        ctx.restore()
        drawFitted(ctx, label, blockLeft + boxW / 2, y + boxH / 2, boxW * 0.7, labelSize, theme.sub, {
          family: FONT_MONO,
          align: 'center',
          baseline: 'middle'
        })
        drawFitted(ctx, value, valueCx, y + boxH / 2, valueW * 1.02, valueSize, theme.fg, {
          family: FONT_MONO,
          align: 'center',
          baseline: 'middle'
        })
      }
      const bw = S * (split ? 0.178 : 0.256)
      const bh = S * (split ? 0.03 : 0.044)
      drawBrandLogo(env, split ? (photoX - bw) / 2 : blockLeft, brandCY - bh / 2, bw, bh, theme.fg)
      return { x: photoX, y: photoY, w: phW, h: phH }
    }

    const gap = S * 0.045
    const labelSize = S * 0.021
    const boxW = S * 0.055
    const boxH = S * 0.038
    const rowGap = S * 0.035
    const valueSize = S * 0.029
    const brandH = S * 0.055
    const drawRows = (x: number, width: number, top: number) => {
      const rowH = boxH + rowGap
      for (let i = 0; i < rows.length; i++) {
        const [label, value] = rows[i]
        const y = top + i * rowH
        ctx.save()
        ctx.strokeStyle = theme.line
        ctx.lineWidth = Math.max(1, S * 0.0014)
        roundRectPath(ctx, x, y, boxW, boxH, boxH * 0.22)
        ctx.stroke()
        ctx.restore()
        drawFitted(ctx, label, x + boxW / 2, y + boxH / 2, boxW * 0.7, labelSize, theme.sub, {
          family: FONT_MONO,
          align: 'center',
          baseline: 'middle'
        })
        drawFitted(ctx, value, x + boxW + S * 0.022, y + boxH / 2, width - boxW - S * 0.022, valueSize, theme.fg, {
          family: FONT_MONO,
          baseline: 'middle'
        })
      }
    }
    const drawBrand = (cx: number, width: number, cy: number) => {
      const bw = Math.min(width * 0.72, S * 0.3)
      drawBrandLogo(env, cx - bw / 2, cy - brandH / 2, bw, brandH, theme.fg)
    }

    if (layout === 'split') {
      const leftW = photoX - gap - m
      const rightX = photoX + phW + gap
      const rightW = W - m - rightX
      drawBrand(m + leftW / 2, leftW, H * 0.5)
      drawRows(rightX, rightW, H * 0.29)
    } else {
      const panelX = layout === 'text-left' ? m : photoX + phW + gap
      const panelW = layout === 'text-left' ? photoX - gap - m : W - m - panelX
      drawBrand(panelX + panelW / 2, panelW, H * 0.27)
      drawRows(panelX, panelW, H * 0.41)
    }
    return { x: photoX, y: photoY, w: phW, h: phH }
  }
}

const specFooter: TemplateDef = {
  id: 'specFooter',
  name: '参数页脚卡',
  en: 'SPEC FOOTER',
  category: '液态玻璃',
  description: '照片 + 白底参数页脚：机型/品牌与 F/MM/S 三框。',
  defaults: {
    theme: 'white',
    marginPct: 3,
    footerPct: 22,
    radiusPct: 1.5,
    shadow: 0.5
  },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 3)) / 100
    const footerH = (S * pct(env.params.footerPct, 22)) / 100
    return { W: env.pw + 2 * m, H: env.ph + 2 * m + footerH }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const S = Math.min(pw, ph)
    const theme = themeOf(env.params)
    const m = (W - pw) / 2
    const footerH = H - ph - 2 * m
    const radius = (S * pct(env.params.radiusPct, 1.5)) / 100
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, m, pw, ph, radius, pct(env.params.shadow, 0.5))

    const contentW = (W * pct(env.params.contentW, 82)) / 100
    const footerTop = m + ph + m
    const brandY = footerTop + footerH * 0.25
    const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
    const sepX = (W * pct(env.params.sepX, 50)) / 100
    drawFitted(ctx, model, sepX - S * 0.035, brandY, W * 0.24, S * 0.022, theme.fg, {
      family: FONT_UI,
      weight: 600,
      align: 'right',
      baseline: 'middle'
    })
    ctx.save()
    ctx.strokeStyle = theme.line
    ctx.lineWidth = Math.max(1, S * 0.0012)
    ctx.beginPath()
    ctx.moveTo(sepX, brandY - S * 0.028)
    ctx.lineTo(sepX, brandY + S * 0.028)
    ctx.stroke()
    ctx.restore()
    const logoW = S * 0.14
    const logoH = S * 0.035
    const logoGap = (S * pct(env.params.logoGap, 3.5)) / 100
    drawBrandLogo(env, sepX + logoGap, brandY - logoH / 2, logoW, logoH, theme.fg)

    const values = [
      ['F', formatAperture(env.exif.aperture).replace(/^F/, '')],
      ['MM', env.exif.focal ? String(Math.round(env.exif.focal)) : ''],
      ['S', formatShutter(env.exif.shutter).replace(/s$/, '')]
    ]
    const rowW = contentW
    const cellW = rowW / 3
    const boxW = S * 0.075
    const boxH = S * 0.04
    const boxY = footerTop + footerH * 0.50
    for (let i = 0; i < values.length; i++) {
      const [label, value] = values[i]
      const cx = (W - rowW) / 2 + cellW * (i + 0.5)
      const bx = cx - boxW / 2
      ctx.save()
      ctx.strokeStyle = theme.line
      ctx.lineWidth = Math.max(1, S * 0.0014)
      roundRectPath(ctx, bx, boxY, boxW, boxH, boxH * 0.25)
      ctx.stroke()
      ctx.restore()
      drawFitted(ctx, label, cx, boxY + boxH / 2, boxW * 0.7, S * 0.018, theme.sub, {
        family: FONT_MONO,
        align: 'center',
        baseline: 'middle'
      })
      drawFitted(ctx, value, cx, footerTop + footerH * 0.80, cellW * 0.8, S * 0.026, theme.fg, {
        family: FONT_MONO,
        align: 'center',
        baseline: 'middle'
      })
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const splitReveal: TemplateDef = {
  id: 'splitReveal',
  name: '色彩显现',
  en: 'SPLIT REVEAL',
  category: '色卡',
  description: '整幅彩色照片与纵向三色点取色器。',
  // color-reveal reference is a single 360x202 (16:9) colour still with a
  // vertical 3-dot picker near the horizontal centre. canvasAspect forces the
  // 16:9 canvas; frameMode 'single' drops the legacy grayscale split; the dot
  // params switch the row to a vertical stack. They are opt-in, so no other
  // template (none uses this def) changes behaviour.
  defaults: {
    theme: 'paper',
    canvasAspect: 16 / 9,
    frameMode: 'single',
    dotOrientation: 'vertical',
    dotCount: 3,
    dotXPct: 50,
    dotTopPct: 46.3,
    dotGapPct: 7.7,
    dotSizePct: 2.45
  },
  themes: lightThemes,
  controls: [],
  layout(env) {
    const aspect = Math.max(0.5, pct(env.params.canvasAspect, 16 / 9))
    return { W: env.pw, H: Math.max(2, Math.round(env.pw / aspect)) }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const split = W / 2
    const single = String(env.params.frameMode ?? 'single') === 'single'
    if (single) {
      drawPhotoCover(env, 0, 0, W, H, 0, 0)
    } else {
      drawPhoto(env, 0, 0, W, H, 0, 0)
      ctx.save()
      ctx.beginPath()
      ctx.rect(0, 0, split, H)
      ctx.clip()
      ctx.filter = 'grayscale(1)'
      drawPhoto(env, 0, 0, W, H, 0, 0)
      ctx.filter = 'none'
      ctx.restore()
      ctx.save()
      ctx.strokeStyle = 'rgba(255,255,255,0.78)'
      ctx.lineWidth = Math.max(1, S * 0.0016)
      ctx.beginPath()
      ctx.moveTo(split, 0)
      ctx.lineTo(split, H)
      ctx.stroke()
      ctx.restore()
    }
    const palette = extractPalette(env, 5)
    const vertical = String(env.params.dotOrientation ?? 'vertical') === 'vertical'
    const count = Math.max(1, Math.min(6, Math.round(Number(env.params.dotCount)) || 3))
    const r = vertical ? S * (pct(env.params.dotSizePct, 2.45) / 100) : S * 0.0105
    const gap = vertical ? H * (pct(env.params.dotGapPct, 7.7) / 100) : r * 2.9
    const cx = vertical ? W * (pct(env.params.dotXPct, 50) / 100) : split
    const cy = vertical ? H * (pct(env.params.dotTopPct, 46.3) / 100) : H * 0.62
    for (let i = 0; i < count; i++) {
      ctx.save()
      ctx.beginPath()
      ctx.arc(vertical ? cx : split + (i - (count - 1) / 2) * gap, vertical ? cy + i * gap : cy, r, 0, Math.PI * 2)
      ctx.fillStyle = palette[i + 1] ?? palette[i] ?? '#ffffff'
      ctx.fill()
      ctx.strokeStyle = 'rgba(255,255,255,0.82)'
      ctx.lineWidth = Math.max(1, S * (vertical ? 0.0025 : 0.0012))
      ctx.stroke()
      ctx.restore()
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

const sideTicket: TemplateDef = {
  id: 'sideTicket',
  name: '票据 · 侧存根',
  en: 'SIDE TICKET',
  category: '票据',
  description: '竖版背景中一张横票，照片在左、城市存根在右。',
  defaults: {
    theme: 'ink',
    ticketBg: '#244231',
    ticketPanel: '#31533f',
    stubTitle: 'HANG ZHOU',
    stubLabel: 'GUANGYING 100'
  },
  themes: darkThemes,
  controls: [],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    return { W: S * 1.5, H: S * 2 }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(env.pw, env.ph)
    const bg = String(env.params.ticketBg ?? '#244231')
    const panel = String(env.params.ticketPanel ?? '#31533f')
    const fg = '#f7f2e7'
    const sub = 'rgba(247,242,231,0.72)'
    // Reference card: 1317x534 at (92,736) of 1500x2000, photo cell 950
    // (72.1 % of the card), stub 27.9 %.
    const cardW = W * 0.878
    const cardH = cardW * 0.401
    const cardX = (W - cardW) / 2
    const cardY = (H - cardH) / 2
    const stubW = cardW * 0.2787
    const photoW = cardW * 0.7213
    const boundary = cardX + photoW
    const radius = S * 0.022
    ctx.fillStyle = bg
    ctx.fillRect(0, 0, W, H)
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.38)'
    ctx.shadowBlur = S * 0.018
    ctx.shadowOffsetY = S * 0.006
    fillRoundRect(ctx, cardX, cardY, cardW, cardH, radius, panel)
    ctx.restore()
    ctx.save()
    roundRectPath(ctx, cardX, cardY, cardW, cardH, radius)
    ctx.clip()
    drawPhotoCover(env, cardX, cardY, photoW, cardH, 0, 0)
    ctx.fillStyle = panel
    ctx.fillRect(boundary, cardY, stubW, cardH)
    ctx.restore()

    const notch = cardH * 0.024
    for (const y of [cardY, cardY + cardH]) {
      ctx.save()
      ctx.fillStyle = bg
      ctx.beginPath()
      ctx.arc(boundary, y, notch, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }
    ctx.save()
    ctx.strokeStyle = 'rgba(247,242,231,0.35)'
    ctx.lineWidth = Math.max(1, S * 0.0012)
    ctx.setLineDash([S * 0.012, S * 0.01])
    ctx.beginPath()
    ctx.moveTo(boundary, cardY + notch * 1.3)
    ctx.lineTo(boundary, cardY + cardH - notch * 1.3)
    ctx.stroke()
    ctx.restore()

    // Stub geometry from the reference: text inset 45/1317 of cardW, text
    // column 274/1317; title line centres 0.240 / 0.361 cardH, date 0.592,
    // label 0.693, barcode 0.817 top with 0.106 cardH height.
    const sx = boundary + cardW * 0.0342
    const sw = cardW * 0.208
    const lines = String(env.params.stubTitle ?? 'HANG ZHOU').toUpperCase().split(/\s+/).slice(0, 2)
    // #2 — the two stub title lines advance by 0.98211 EM of the size they are
    // painted at, not by an absolute `cardH * 0.1208` step. 0.1208/0.123 is the
    // pitch this card already declares at fontScale 1 (line step / declared size),
    // so the 1.0 render is unchanged; but `scaledContext` grows the font and not
    // the coordinates, so a fixed step kept the 1.0 line height under 1.22 ink
    // (overlap-check ticket-1 op1xop2, 13.44%). Advancing in em keeps the leading
    // proportional to the glyphs at every scale.
    const stubSize = cardH * 0.123
    const stubPitch = 0.1208 / 0.123
    let stubY = cardY + cardH * 0.2395
    for (const lineText of lines) {
      const painted = drawFitted(ctx, lineText, sx, stubY, sw, stubSize, fg, {
        family: FONT_UI,
        weight: 800,
        baseline: 'middle',
        min: cardH * 0.06
      })
      stubY += painted * stubPitch
    }
    const dt = dateText(env)
    const stubDate = /^\d{4}-\d{2}-\d{2}$/.test(dt) ? dt.slice(0, 7) : dt
    if (stubDate) {
      drawFitted(ctx, stubDate, sx, cardY + cardH * 0.5915, sw, cardH * 0.0698, fg, {
        family: FONT_MONO,
        weight: 600,
        baseline: 'middle',
        min: cardH * 0.04
      })
    }
    drawFitted(ctx, String(env.params.stubLabel ?? 'GUANGYING 100'), sx, cardY + cardH * 0.693, sw, cardH * 0.0621, sub, {
      family: FONT_MONO,
      weight: 500,
      tracking: 0.08,
      baseline: 'middle',
      min: cardH * 0.035
    })
    drawBarcode(ctx, sx, cardY + cardH * 0.817, sw * 0.96, cardH * 0.106, fg, env.exif.model || 'FRAMEMARK')
    return { x: cardX, y: cardY, w: photoW, h: cardH }
  }
}

const poetryCard: TemplateDef = {
  id: 'poetryCard',
  name: '自然诗篇',
  en: 'POETRY CARD',
  category: '留白',
  description: '标题/日期 + 圆角照片 + 色板与机型参数页脚。',
  defaults: {
    theme: 'white',
    marginPct: 6.5,
    radiusPct: 2,
    headerPct: 15.5,
    footerPct: 15.9,
    shadow: 0.3,
    photoAspect: 0
  },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 6.5)) / 100
    const header = (S * pct(env.params.headerPct, 15.5)) / 100
    const footer = (S * pct(env.params.footerPct, 15.9)) / 100
    const photoAspect = pct(env.params.photoAspect, 0)
    const windowH = photoAspect > 0 ? env.pw / photoAspect : env.ph
    return { W: env.pw + 2 * m, H: header + windowH + footer + 2 * m }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const S = Math.min(pw, ph)
    const theme = themeOf(env.params)
    const m = (W - pw) / 2
    const header = (S * pct(env.params.headerPct, 15.5)) / 100
    // photoAspect > 0 opens a fixed portrait window (reference 1067x987 =
    // 1.081) inside the crop; the canvas follows the window height.
    const photoAspect = pct(env.params.photoAspect, 0)
    const windowH = photoAspect > 0 ? pw / photoAspect : ph
    const footer = H - header - windowH - 2 * m
    const radius = (S * pct(env.params.radiusPct, 2)) / 100
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    // Type sizes are tied to the photo window height (reference: title
    // 38.1 %S, date 2.1, model 2.9, exif 1.9 of the window height).
    drawFitted(ctx, titleOf(env), m, m + header * 0.305, pw, windowH * 0.0492, theme.fg, {
      family: FONT_UI,
      weight: 600,
      min: windowH * 0.024
    })
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, `PHOTOGRAPHED IN: ${dt}`, m, m + header * 0.72, pw, windowH * 0.023, theme.sub, {
        family: FONT_MONO,
        tracking: 0.02,
        min: windowH * 0.012
      })
    }
    if (photoAspect > 0) drawPhotoCover(env, m, m + header, pw, windowH, radius, pct(env.params.shadow, 0.3))
    else drawPhoto(env, m, m + header, pw, ph, radius, pct(env.params.shadow, 0.3))
    const footerTop = m + header + windowH
    const below = footer + m
    const rightX = W - m - W * 0.018
    drawPaletteTiles(env, extractPalette(env, 5), m, footerTop + below * 0.464, W * 0.317, windowH * 0.0253)
    const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
    drawFitted(ctx, model, rightX, footerTop + below * 0.537, W * 0.42, windowH * 0.038, theme.fg, {
      family: FONT_UI,
      weight: 500,
      align: 'right',
      min: windowH * 0.018
    })
    const ex = techExifLine(env).replace(/\s+/g, ' ')
    if (ex) {
      drawFitted(ctx, ex, rightX, footerTop + below * 0.757, W * 0.5, windowH * 0.0185, theme.sub, {
        family: FONT_MONO,
        align: 'right',
        min: windowH * 0.01
      })
    }
    return { x: m, y: m + header, w: pw, h: windowH }
  }
}

const gallery: TemplateDef = {
  id: 'gallery',
  name: '留白 · 画廊',
  en: 'GALLERY',
  category: '留白',
  description: '宽卡纸与标题层，像一张展览作品卡。',
  defaults: { marginPct: 9, radiusPct: 0.5, shadow: 2, footerPct: 20, theme: 'cream', logoScale: 5 },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 9)) / 100
    const bottom = Math.max((S * pct(env.params.footerPct, 20)) / 100, S * 0.20)
    return { W: env.pw + 2 * m, H: env.ph + m + bottom }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = marginOf(env)
    const bottom = H - ph - m
    const baseY = m + ph
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, m, pw, ph, radiusOf(env), pct(env.params.shadow, 2))
    ctx.save()
    ctx.strokeStyle = theme.line
    ctx.lineWidth = Math.max(1, S * 0.0008)
    roundRectPath(ctx, m, m, pw, ph, radiusOf(env))
    ctx.stroke()
    ctx.restore()
    line(ctx, m, baseY + bottom * 0.10, W - m, baseY + bottom * 0.10, theme.line, Math.max(1, S * 0.0008))
    const title = titleOf(env)
    const titleY = baseY + bottom * 0.37
    drawFitted(ctx, title, m, titleY, W - 2 * m, S * 0.052, theme.fg, {
      family: FONT_CN,
      weight: 400,
      min: S * 0.025
    })
    let metaY = titleY + S * 0.036
    const sub = subLine(env)
    if (sub) {
      metaY += drawFitted(ctx, sub, m, metaY, W * 0.58, S * 0.017, theme.sub, {
        family: FONT_UI,
        min: S * 0.01
      })
      metaY += S * 0.006
    }
    const cam = env.text.showMake || env.text.showModel ? cameraLine(env.exif, '') : ''
    if (cam) {
      metaY += drawFitted(ctx, cam.toUpperCase(), m, metaY + S * 0.01, W * 0.55, S * 0.014, theme.sub, {
        family: FONT_UI,
        weight: 600,
        tracking: 0.12,
        min: S * 0.009
      })
    }
    const ex = exifText(env)
    if (ex) {
      drawFitted(ctx, ex, W - m, baseY + bottom * 0.37, W * 0.38, S * 0.017, theme.sub, {
        align: 'right',
        family: FONT_MONO,
        min: S * 0.01
      })
    }
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, W - m, baseY + bottom * 0.52, W * 0.38, S * 0.014, theme.sub, {
        align: 'right',
        family: FONT_MONO,
        min: S * 0.009
      })
    }
    if (env.text.signature.trim()) {
      drawFitted(ctx, env.text.signature, W - m, baseY + bottom * 0.76, W * 0.42, S * 0.035, theme.fg, {
        align: 'right',
        family: FONT_HAND,
        min: S * 0.02
      })
    }
    line(ctx, m, baseY + bottom * 0.82, W * 0.28, baseY + bottom * 0.82, theme.accent, Math.max(1, S * 0.0012))
    return { x: m, y: m, w: pw, h: ph }
  }
}

/**
 * Local geometry overrides for the brandFooter family. Stored as one JSON string
 * in params.frame so each variant can nudge the centre logo / right block /
 * overlay insets without cloning the template or changing the family defaults.
 */
function footerFrame(env: Pick<TemplateRenderEnv, 'params'>): Record<string, number> {
  const raw = env.params.frame
  if (typeof raw !== 'string' || !raw.trim().startsWith('{')) return {}
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const out: Record<string, number> = {}
    for (const [key, value] of Object.entries(parsed)) {
      const n = Number(value)
      if (Number.isFinite(n)) out[key] = n
    }
    return out
  } catch {
    return {}
  }
}

/** Reference slot whitelist ('' = every slot the classic footer used to draw). */
function footerSlots(env: Pick<TemplateRenderEnv, 'params'>): Set<string> {
  const raw = String(env.params.slots ?? '').trim()
  if (!raw) return new Set()
  return new Set(raw.split(',').map((s) => s.trim()).filter(Boolean))
}

/**
 * Overlay lens row in 'reference' mode (wm-black only).
 * The reference (new-watermark2.webp) second right-stack row is the optics
 * designation — 'LEICA + 24MM F1.8' — not a second copy of the EXIF run: no
 * shutter, no ISO and no camera model (the left title/brand block already prints
 * the model). Falls back to the lens name when the EXIF carries a short one, and
 * never repeats a token the designation already spells out after the '+'.
 */
function overlayLensLine(exif: TemplateRenderEnv['exif'], optics: string[]): string {
  const raw = String(exif.lens || '').trim()
  const name = raw.length > 0 && raw.length <= 24 ? raw.split('|')[0].trim() : ''
  const designation = name || String(exif.make || '').trim() || modelWithoutMake(exif.make, exif.model)
  const rest = optics.filter((t) => t && !designation.toLowerCase().includes(t.toLowerCase())).join(' ')
  return [designation, rest].filter(Boolean).join(' + ').toUpperCase()
}

/**
 * Overlay variant of the brand footer: full-bleed photo with no reserved band.
 * Left column: title above brand, brand logo badge at the left edge.
 * Right column: exif / camera spec / date, centred like the reference.
 * All ink uses theme fg/sub plus a soft shadow so it stays legible on photos.
 */
function drawBrandOverlay(env: TemplateRenderEnv): Rect {
  const { ctx, W, H, pw, ph } = env
  const theme = themeOf(env.params)
  const S = Math.min(pw, ph)
  const f = footerFrame(env)
  const slots = footerSlots(env)
  const on = (key: string) => slots.size === 0 || slots.has(key)
  ctx.fillStyle = theme.bg
  ctx.fillRect(0, 0, W, H)
  drawPhoto(env, 0, 0, pw, ph, 0, 0)
  const leftX = W * (f.ovLeftX ?? 0.0913)
  const textX = W * (f.ovTextX ?? 0.1293)
  const rightX = W * (f.ovRightCenterX ?? 0.749)
  const logoW = W * (f.ovLogoW ?? 0.0323)
  const logoH = H * (f.ovLogoH ?? 0.0519)
  const logoCy = H * (f.ovLogoCenterY ?? 0.7839)
  const make = env.exif.make.trim()
  const model = modelWithoutMake(env.exif.make, env.exif.model)
  const title = footerTitle(env)
  const brand = ((env.text.showMake ? make : '') || model || '').trim()
  const exifLineText = exifText(env)
  const dateLine = dateText(env)
  const optics = [env.exif.focal ? `${Math.round(env.exif.focal)}mm` : '', formatAperture(env.exif.aperture)]
  // Default ('model') is the historic overlay line — model + focal + aperture — so
  // every other brandFooter variant keeps rendering exactly what it did before.
  const spec =
    String(env.params.lensRowMode ?? 'model') === 'reference'
      ? overlayLensLine(env.exif, optics)
      : [model, ...optics].filter(Boolean).join(' ').toUpperCase()
  if (on('logo') && env.text.showLogo) {
    drawBrandLogo(env, leftX, logoCy - logoH / 2, logoW, logoH, theme.fg)
  }
  if (on('title') && title) {
    drawFitted(ctx, title, textX, H * (f.ovTitleY ?? 0.7637), W * 0.32, S * 0.019, theme.fg, {
      family: FONT_UI,
      weight: 500,
      baseline: 'middle',
      min: S * 0.011,
      shadow: true
    })
  }
  if (on('brand') && brand) {
    drawFitted(ctx, brand.toUpperCase(), textX, H * (f.ovBrandY ?? 0.7968), W * 0.3, S * 0.019, theme.fg, {
      family: FONT_UI,
      weight: 600,
      tracking: 0.04,
      baseline: 'middle',
      min: S * 0.011,
      shadow: true
    })
  }
  if (on('exif') && exifLineText) {
    drawFitted(ctx, exifLineText, rightX, H * (f.ovExifY ?? 0.7507), W * 0.3, S * 0.02, theme.fg, {
      family: FONT_MONO,
      weight: 400,
      align: 'center',
      baseline: 'middle',
      min: S * 0.012,
      shadow: true
    })
  }
  if (on('lens') && spec) {
    drawFitted(ctx, spec, rightX, H * (f.ovLensY ?? 0.7925), W * 0.26, S * 0.0175, theme.fg, {
      family: FONT_MONO,
      weight: 400,
      align: 'center',
      baseline: 'middle',
      min: S * 0.01,
      shadow: true
    })
  }
  if (on('date') && dateLine) {
    drawFitted(ctx, dateLine, rightX, H * (f.ovDateY ?? 0.8285), W * 0.2, S * 0.018, theme.fg, {
      family: FONT_MONO,
      weight: 400,
      align: 'center',
      baseline: 'middle',
      min: S * 0.01,
      shadow: true
    })
  }
  return { x: 0, y: 0, w: pw, h: ph }
}

function drawSimpleBrandFooter(env: TemplateRenderEnv): Rect {
  const { ctx, W, H, pw, ph } = env
  const theme = themeOf(env.params)
  const m = simpleFooterMetrics(pw, ph, env.params)
  ctx.fillStyle = theme.bg
  ctx.fillRect(0, 0, W, H)
  drawPhoto(env, 0, 0, pw, ph, 0, 0)
  const cy = ph + m.footerH / 2
  const logoPos = String(env.params.logoPos ?? 'center')
  const make = env.exif.make.trim()
  const model = modelWithoutMake(env.exif.make, env.exif.model)
  const leftText = model || make || footerTitle(env)
  const rightLines = [
    env.text.showExif ? exifLine(env.exif, { separator: ' ' }) : '',
    env.text.showDate ? formatDateOnly(env.exif.dateTime) : ''
  ].filter(Boolean)
  const hasLogo = env.text.showLogo && Boolean(env.images?.logo)
  let logoBox: { x: number; y: number; w: number; h: number } | null = null
  if (hasLogo) {
    let lx = W / 2 - m.logoW / 2
    if (logoPos === 'left') lx = m.pad
    else if (logoPos === 'right') lx = W - m.pad - m.logoW
    logoBox = { x: lx, y: cy - m.logoH / 2, w: m.logoW, h: m.logoH }
  }
  const centerLeft = logoBox ? logoBox.x - m.gap : W / 2
  const centerRight = logoBox ? logoBox.x + logoBox.w + m.gap : W / 2
  if (logoPos === 'left' && logoBox) {
    const tx = logoBox.x + logoBox.w + m.gap
    drawFitted(ctx, leftText, tx, cy, Math.max(40, W / 2 - tx), m.fontLeft, theme.fg, {
      family: FONT_VENDOR,
      weight: 500,
      baseline: 'middle',
      min: m.fontLeft * 0.5
    })
  } else {
    drawFitted(ctx, leftText, m.pad, cy, Math.max(40, centerLeft - m.pad), m.fontLeft, theme.fg, {
      family: FONT_VENDOR,
      weight: 500,
      baseline: 'middle',
      min: m.fontLeft * 0.5
    })
  }
  if (logoBox) {
    drawBrandLogo(env, logoBox.x, logoBox.y, logoBox.w, logoBox.h, theme.fg)
  } else {
    drawFitted(ctx, (make || leftText).toUpperCase(), W / 2, cy, W - 2 * m.pad, m.fontCenter, theme.fg, {
      family: FONT_VENDOR,
      weight: 700,
      align: 'center',
      baseline: 'middle',
      min: m.fontCenter * 0.5
    })
  }
  if (rightLines.length) {
    const rightX = logoPos === 'right' && logoBox ? logoBox.x - m.gap : W - m.pad
    const maxW = Math.max(60, logoPos === 'right' && logoBox ? rightX - centerRight : rightX - centerRight)
    const lineGap = m.fontRight * 0.95
    const blockH = m.fontRight + (rightLines.length - 1) * lineGap
    let y = cy - blockH / 2 + m.fontRight * 0.52
    rightLines.forEach((text, i) => {
      drawFitted(ctx, text, rightX, y, maxW, i === 0 ? m.fontRight : m.fontRight * 0.78, i === 0 ? theme.fg : theme.sub, {
        family: FONT_VENDOR,
        weight: i === 0 ? 700 : 400,
        align: 'right',
        baseline: 'middle',
        min: (i === 0 ? m.fontRight : m.fontRight * 0.78) * 0.5
      })
      y += lineGap
    })
  }
  return { x: 0, y: 0, w: pw, h: ph }
}

const brandFooter: TemplateDef = {
  id: 'brandFooter',
  name: '品牌 · 页脚',
  en: 'BRAND FOOTER',
  category: '品牌',
  description: '白/黑页脚、相机 Logo 与左右参数栏，最接近相机原厂水印。',
  defaults: {
    marginPct: 0,
    radiusPct: 0,
    shadow: 0,
    footerPct: 7.5,
    theme: 'white',
    logoScale: 5,
    logoPos: 'center',
    footerMode: 'classic',
    footerAuto: false,
    footerTextScale: 1,
    slots: '',
    logoCenterX: 0.53,
    frame: ''
  },
  themes: lightThemes,
  controls: ['footer'],
  layout(env) {
    if (env.params.footerMode === 'simple' && env.params.footerAuto !== false) {
      return { W: env.pw, H: env.ph + simpleFooterMetrics(env.pw, env.ph, env.params).footerH }
    }
    if (env.params.footerMode === 'overlay') return { W: env.pw, H: env.ph }
    const S = Math.min(env.pw, env.ph)
    const footer = Math.max((env.pw * pct(env.params.footerPct, 7.5)) / 100, S * 0.085)
    // Opt-in outer frame: photo inset on all sides except the footer, which stays full-bleed.
    const f = footerFrame(env)
    const border = (env.pw * pct(f.borderPct, 0)) / 100
    const topInset = (env.pw * pct(f.borderTopPct, f.borderPct ?? 0)) / 100
    return { W: env.pw + 2 * border, H: env.ph + topInset + footer }
  },
  render(env) {
    if (env.params.footerMode === 'simple') return drawSimpleBrandFooter(env)
    if (env.params.footerMode === 'overlay') return drawBrandOverlay(env)
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const f = footerFrame(env)
    const slots = footerSlots(env)
    const on = (key: string) => slots.size === 0 || slots.has(key)
    const border = (pw * pct(f.borderPct, 0)) / 100
    const topInset = (pw * pct(f.borderTopPct, f.borderPct ?? 0)) / 100
    const footerTop = topInset + ph
    const footer = H - footerTop
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, border, topInset, pw, ph, 0, 0)
    if (f.bandRule !== 0) {
      line(ctx, border, footerTop, border + pw, footerTop, theme.line, Math.max(1, S * 0.0009))
    }
    const leftX = W * (f.leftX ?? 0.04)
    const leftW = W * (f.leftW ?? 0.28)
    const brandY = f.brandY ?? 0.42
    const modelY = f.modelY ?? 0.70
    const logoPos = String(env.params.logoPos ?? 'center')
    // Declared rows: a blank photo keeps the same four editable ends; values typed
    // here win over EXIF, and an absent EXIF no longer removes the row itself.
    const slotValues = env.design.text.slotValues ?? {}
    const slotSizes = env.design.text.slotSizes ?? {}
    const slotScope = env.design.variantId ?? env.design.templateId
    const slotOf = (id: string) => String(slotValues[slotScope + '#' + id] ?? '').trim()
    // Per-row size multiplier (0.6–1.6), default 1 so untouched renders are
    // byte-identical. Only the declared footer rows expose this control.
    const sizeOf = (id: string) =>
      Math.max(0.6, Math.min(1.6, pct(slotSizes[slotScope + '#' + id], 1)))
    const make = env.text.showMake ? slotOf('brand') || env.exif.make : ''
    const model = env.text.showModel
      ? slotOf('model') || modelWithoutMake(env.exif.make, env.exif.model)
      : ''
    const title = footerTitle(env)
    const makeText = (make || title).toUpperCase()
    const modelText = model || (env.text.showLens ? env.exif.lens : '')
    const showBrand = on('brand')
    const showModel = on('model') && Boolean(modelText)
    const showLeftText = !(logoPos === 'left' && env.text.showLogo) && (showBrand || showModel)
    if (showLeftText) {
      if (showBrand) {
        drawFitted(ctx, makeText, leftX, footerTop + footer * brandY, leftW, S * 0.028 * sizeOf('brand'), theme.fg, {
          family: FONT_AVENIR,
          weight: 600,
          tracking: 0.02,
          min: S * 0.013
        })
      }
      if (showModel) {
        drawFitted(ctx, modelText, leftX, footerTop + footer * modelY, leftW, S * 0.025 * sizeOf('model'), theme.sub, {
          family: FONT_SERIF,
          weight: 700,
          tracking: 0.015,
          min: S * 0.01
        })
      }
    }
    const rightX = W * (f.rightX ?? (logoPos === 'right' ? 0.44 : 0.72))
    const rightW = W * (f.rightW ?? (logoPos === 'right' ? 0.34 : 0.25))
    const exifY = f.exifY ?? 0.42
    const dateY = f.dateY ?? 0.70
    const ex = env.text.showExif ? slotOf('exif') || exifText(env) : ''
    const dt = env.text.showDate ? slotOf('date') || formatDate(env.exif.dateTime) : ''
    const custom = on('custom') ? env.text.customLine.trim() : ''
    const hasRightColumn = Boolean((on('exif') && ex) || (on('date') && dt) || custom)
    // No EXIF and no custom line means there is no right column; drawing its
    // divider leaves an empty column that reads as a layout bug.
    const ruleX = W * (f.ruleX ?? (logoPos === 'right' ? 0.42 : 0.7))
    if (on('logo') && env.text.showLogo) {
      const grow = logoScale(env)
      const baseW = W * (f.logoW ?? 0.17)
      const baseH = footer * (f.logoH ?? 0.36)
      let lw = baseW * grow
      let lh = Math.min(baseH * grow, footer * 0.9)
      const centerX = W * pct(env.params.logoCenterX, 0.53)
      let lx = centerX - lw / 2
      if (logoPos === 'left') lx = leftX
      else if (logoPos === 'right') lx = W * 0.96 - lw
      if (f.logoCx !== undefined) lx = W * f.logoCx - lw / 2
      // Growth must not run into the neighbouring column: clamp to the slack
      // between the left block and the divider, and slide the mark sideways
      // when it is centred. At scale 1 the original placement is kept so the
      // default render stays byte-identical.
      if (grow > 1.0001) {
        const gap = S * 0.008
        const leftBound = leftX + (showLeftText ? leftW : 0) + gap
        const rightBound = (hasRightColumn ? (f.rule !== 0 ? ruleX : rightX) : W * 0.96) - gap
        const maxW = Math.max(0, rightBound - leftBound)
        lw = Math.min(lw, maxW)
        if (logoPos === 'center' || f.logoCx !== undefined) {
          const half = lw / 2
          const cx = Math.min(Math.max(lx + half, leftBound + half), rightBound - half)
          lx = cx - half
        } else if (logoPos === 'left') {
          lx = leftBound
        } else {
          lx = rightBound - lw
        }
      }
      drawBrandLogo(env, lx, footerTop + footer / 2 - lh / 2, lw, lh, theme.fg)
    }
    if (f.rule !== 0 && hasRightColumn) {
      line(ctx, ruleX, footerTop + footer * 0.22, ruleX, footerTop + footer * 0.78, theme.line, Math.max(1, S * 0.0009))
    }
    if (on('exif') && ex) {
      drawFitted(ctx, ex, rightX, footerTop + footer * exifY, rightW, S * 0.027 * sizeOf('exif'), theme.fg, {
        family: FONT_AVENIR,
        weight: 500,
        tracking: 0.01,
        min: S * 0.012
      })
    }
    if (on('date') && dt) {
      drawFitted(ctx, dt, rightX, footerTop + footer * dateY, rightW, S * 0.021 * sizeOf('date'), theme.sub, {
        family: FONT_AVENIR,
        weight: 400,
        tracking: 0.01,
        min: S * 0.01
      })
    }
    if (custom) {
      drawFitted(ctx, custom, rightX, footerTop + footer * 0.88, rightW, S * 0.016, theme.accent, {
        family: FONT_AVENIR,
        weight: 500,
        min: S * 0.009
      })
    }
    return { x: border, y: topInset, w: pw, h: ph }
  }
}

// Graduation footer: edge-to-edge photo + white footer with EXIF brand/exif on
// the left and the APK's real graduation sticker on the right. No matte, no
// centred logo. Footer height is 12% of photo width (ref 180/1500).
const gradFooter: TemplateDef = {
  id: 'gradFooter',
  name: '毕业 · 页脚',
  en: 'GRAD FOOTER',
  category: '毕业',
  description: '满幅照片 + 纯白页脚：左侧 EXIF 品牌/参数，右侧毕业贴纸。',
  defaults: {
    theme: 'white',
    footerPct: 12,
    stickerScalePct: 100,
    stickerRightPct: 0,
    brandXPct: 3.9,
    brandY: 0.46,
    brandSizePct: 3.7,
    exifY: 0.772,
    exifSizePct: 2.85
  },
  themes: lightThemes,
  controls: ['footer'],
  async prepare(env) {
    const key = String(env.params.sticker ?? '')
    const file = GRAD_STICKER_FILES[key]
    const url = file ? GRAD_STICKER_URLS[`../assets/graduation/${file}`] : undefined
    if (!url) return {}
    return { images: { sticker: await loadImage(url) } }
  },
  layout(env) {
    const footer = (env.pw * pct(env.params.footerPct, 12)) / 100
    return { W: env.pw, H: env.ph + footer }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const footer = H - ph
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, 0, 0, pw, ph, 0, 0)
    const sticker = env.images?.sticker
    if (sticker) {
      const sh = (footer * pct(env.params.stickerScalePct, 100)) / 100
      const aspect = (sticker.naturalWidth || 260) / (sticker.naturalHeight || 90)
      const sw = sh * aspect
      const sx = W - sw - (W * pct(env.params.stickerRightPct, 0)) / 100
      ctx.drawImage(sticker, sx, ph, sw, sh)
    }
    const leftX = (W * pct(env.params.brandXPct, 3.9)) / 100
    const leftW = W * (sticker ? 0.45 : 0.9)
    const make = env.text.showMake ? env.exif.make : ''
    const model = env.text.showModel ? modelWithoutMake(env.exif.make, env.exif.model) : ''
    const brand = [make, model].filter(Boolean).join(' ').toUpperCase()
    if (brand) {
      drawFitted(ctx, brand, leftX, ph + footer * pct(env.params.brandY, 0.46), leftW, (S * pct(env.params.brandSizePct, 3.7)) / 100, theme.fg, {
        family: FONT_UI,
        weight: 700,
        tracking: 0.02,
        min: S * 0.018
      })
    }
    const ex = env.text.showExif ? exifLine(env.exif) : ''
    if (ex) {
      drawFitted(ctx, ex, leftX, ph + footer * pct(env.params.exifY, 0.772), leftW, (S * pct(env.params.exifSizePct, 2.85)) / 100, theme.sub, {
        family: FONT_UI,
        weight: 400,
        min: S * 0.014
      })
    }
    return { x: 0, y: 0, w: pw, h: ph }
  }
}

const personalBorder: TemplateDef = {
  id: 'personalBorder',
  name: '个人 · 签名边框',
  en: 'PERSONAL BORDER',
  category: '个人',
  description: '原版个人边框：照片满幅、四周细白边 + 底部签名栏，照片按模板画幅裁切。',
  defaults: {
    marginPct: 2,
    radiusPct: 0,
    shadow: 0,
    footerPct: 9.5,
    borderPct: 2,
    theme: 'white',
    logoScale: 5,
    logoPos: 'center',
    signature: 'FrameMark'
  },
  themes: lightThemes,
  controls: ['footer'],
  layout(env) {
    const b = (env.pw * pct(env.params.borderPct, 2)) / 100
    return { W: env.pw + 2 * b, H: env.ph + 2 * b }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const b = (pw * pct(env.params.borderPct, 2)) / 100
    const footerH = W * (pct(env.params.footerPct, 9.5) / 100)
    const photoH = Math.max(ph * 0.4, H - b - footerH)
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhotoCover(env, b, b, pw, photoH, 0, 0)
    const footerTop = H - footerH
    const cy = footerTop + footerH / 2
    const signature = String(env.params.signature ?? '').trim() || env.text.customLine.trim() || env.text.title.trim() || 'FrameMark'
    const avatarSize = W * 0.036
    const avatarX = b * 2
    ctx.save()
    ctx.beginPath()
    ctx.arc(avatarX + avatarSize / 2, cy, avatarSize / 2, 0, Math.PI * 2)
    ctx.fillStyle = '#c8a96b'
    ctx.fill()
    ctx.fillStyle = '#fff'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    setFont(ctx, avatarSize * 0.42, FONT_VENDOR, 700)
    ctx.fillText(signature.slice(0, 1).toUpperCase(), avatarX + avatarSize / 2, cy)
    ctx.restore()
    const signatureX = avatarX + avatarSize + W * 0.016
    drawFitted(ctx, signature, signatureX, cy, W * 0.245, Math.max(W * 0.021, 58), theme.fg, {
      family: FONT_VENDOR,
      weight: 600,
      baseline: 'middle',
      min: Math.max(W * 0.014, 34)
    })
    const dividerX = W * 0.663
    const logoW = W * 0.097
    const logoH = W * 0.0165
    const gap = W * 0.03
    const logoLeft = dividerX - gap - logoW
    const modelRight = logoLeft - gap
    const model = modelWithoutMake(env.exif.make, env.exif.model) || env.exif.make || pickTitle(env, false)
    if (model) {
      drawFitted(ctx, model, modelRight, cy, W * 0.17, W * 0.0205, theme.fg, {
        family: FONT_VENDOR,
        weight: 400,
        align: 'right',
        baseline: 'middle',
        min: W * 0.013
      })
    }
    if (env.text.showLogo) {
      drawBrandLogo(env, logoLeft, cy - logoH / 2, logoW, logoH, theme.fg)
    }
    line(ctx, dividerX, cy - footerH * 0.28, dividerX, cy + footerH * 0.28, theme.line, Math.max(1, W * 0.0006))
    const rightX = W * 0.966
    const right = env.text.showExif ? exifLine(env.exif, { separator: ' ' }) : ''
    if (right) {
      drawFitted(ctx, right, rightX, cy, W * 0.27, W * 0.018, theme.fg, {
        family: FONT_VENDOR,
        weight: 400,
        align: 'right',
        baseline: 'middle',
        min: W * 0.013
      })
    }
    const dt = env.text.showDate ? formatDateOnly(env.exif.dateTime) : ''
    if (dt) {
      drawFitted(ctx, dt, rightX, cy + footerH * 0.24, W * 0.27, W * 0.018, theme.sub, {
        family: FONT_VENDOR,
        weight: 400,
        align: 'right',
        baseline: 'middle',
        min: W * 0.011
      })
    }
    return { x: b, y: b, w: pw, h: photoH }
  }
}

const vendorFooter: TemplateDef = {
  id: 'vendorFooter',
  name: '厂商 · 水印',
  en: 'VENDOR WATERMARK',
  category: '厂商',
  description: '哈苏/蔡司/徕卡厂商水印，哈苏按 OPPO 相册 JSON 规格，蔡司/徕卡按系统包提取的字体与字标。',
  defaults: {
    theme: 'white',
    vendor: 'hasselblad',
    footerPct: 16.67,
    logoScale: 5,
    logoPos: 'right',
    shadow: 0,
    radiusPct: 0
  },
  themes: lightThemes,
  controls: ['footer'],
  layout(env) {
    const vendor = String(env.params.vendor ?? 'hasselblad')
    const style = String(env.params.vendorStyle ?? (vendor === 'lumo' ? 'l1' : 'h1'))
    const S = Math.min(env.pw, env.ph)
    if (style === 'h3' || style === 'l2') {
      const side = (S * pct(env.params.sidePadPct, 2.78)) / 100
      const bottom = (S * pct(env.params.footerPct, 30.83)) / 100
      return { W: env.pw + 2 * side, H: env.ph + side + bottom }
    }
    if (style === 'h4' || style === 'l3') {
      const side = (S * pct(env.params.sidePadPct, 2.78)) / 100
      const top = (S * pct(env.params.topPadPct, 21.94)) / 100
      const bottom = (S * pct(env.params.footerPct, 21.94)) / 100
      return { W: env.pw + 2 * side, H: env.ph + top + bottom }
    }
    const defaults = vendor === 'hasselblad' ? (style === 'h2' ? 18.89 : 16.67) : vendor === 'lumo' ? 16.67 : 12
    const footerH =
      vendor === 'hasselblad' || vendor === 'lumo'
        ? (S * pct(env.params.footerPct, defaults)) / 100
        : (env.pw * pct(env.params.footerPct, 12)) / 100
    return { W: env.pw, H: env.ph + footerH }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const vendor = String(env.params.vendor ?? 'hasselblad')
    const style = String(env.params.vendorStyle ?? (vendor === 'lumo' ? 'l1' : 'h1'))
    const S = Math.min(pw, ph)
    const u = S / 360
    const device = [env.exif.make, modelWithoutMake(env.exif.make, env.exif.model)].filter(Boolean).join(' ') || titleOf(env)
    const model = modelWithoutMake(env.exif.make, env.exif.model)
    const full = cameraLine(env.exif) || model || titleOf(env)
    const subText = dateText(env) || env.text.subtitle.trim()
    const exifTextLine = exifText(env)
    const matte = style === 'h3' || style === 'l2' || style === 'h4' || style === 'l3'
    const side = matte ? (S * pct(env.params.sidePadPct, 2.78)) / 100 : 0
    const topPad = style === 'h4' || style === 'l3' ? (S * pct(env.params.topPadPct, 21.94)) / 100 : side
    const footerH = H - ph - topPad
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, side, topPad, pw, ph, 0, 0)
    const photoBottom = topPad + ph
    if (style === 'h2') {
      const cy = ph + footerH / 2
      const pad = 19 * u
      const lineH = 15.03 * u
      const blockH = lineH * 2 + 1 * u
      drawFitted(ctx, device, pad, cy - blockH / 2 + lineH / 2, 230 * u, 11 * u, theme.fg, {
        family: FONT_VENDOR,
        weight: 500,
        baseline: 'middle',
        min: 8 * u
      })
      if (subText) {
        ctx.save()
        ctx.globalAlpha = 0.65
        drawFitted(ctx, subText, pad, cy + 1 * u + lineH / 2, 230 * u, 11 * u, theme.fg, {
          family: FONT_VENDOR,
          weight: 400,
          baseline: 'middle',
          min: 8 * u
        })
        ctx.restore()
      }
      drawBrandLogo(env, W - pad - 36 * u, cy - 18 * u, 36 * u, 36 * u, theme.fg)
      return { x: side, y: topPad, w: pw, h: ph }
    }
    if (style === 'h3' || style === 'l2') {
      const cy = photoBottom + footerH / 2
      const logoW = (style === 'h3' ? 36 : 65) * u
      const logoH = (style === 'h3' ? 36 : 40) * u
      const modelSize = (style === 'h3' ? 11.6 : 10.5) * u
      const modelLh = (style === 'h3' ? 15.85 : 12) * u
      const subSize = (style === 'h3' ? 10 : 8) * u
      const subLh = (style === 'h3' ? 13.66 : 9) * u
      const gapA = (style === 'h3' ? 4 : 2.5) * u
      const gapB = (style === 'h3' ? 1 : 2) * u
      const blockH = logoH + gapA + modelLh + gapB + subLh
      const y0 = cy - blockH / 2
      drawBrandLogo(env, (W - logoW) / 2, y0, logoW, logoH, theme.fg)
      drawFitted(ctx, device, W / 2, y0 + logoH + gapA + modelLh / 2, 340 * u, modelSize, theme.fg, {
        family: FONT_VENDOR,
        weight: 500,
        align: 'center',
        baseline: 'middle',
        tracking: style === 'l2' ? 0.02 : 0,
        min: modelSize * 0.55
      })
      if (subText) {
        ctx.save()
        ctx.globalAlpha = 0.65
        drawFitted(ctx, subText, W / 2, y0 + logoH + gapA + modelLh + gapB + subLh / 2, 340 * u, subSize, theme.fg, {
          family: FONT_VENDOR,
          weight: 400,
          align: 'center',
          baseline: 'middle',
          tracking: style === 'l2' ? 0.06 : 0,
          min: subSize * 0.55
        })
        ctx.restore()
      }
      return { x: side, y: topPad, w: pw, h: ph }
    }
    if (style === 'h4' || style === 'l3') {
      const topArea = topPad
      if (style === 'h4') {
        drawBrandLogo(env, (W - 116 * u) / 2, (topArea - 9 * u) / 2, 116 * u, 9 * u, theme.fg)
      } else {
        drawBrandLogo(env, (W - 65 * u) / 2, (topArea - 40 * u) / 2, 65 * u, 40 * u, theme.fg)
      }
      const areaTop = photoBottom
      const areaH = footerH
      if (style === 'h4') {
        const modelLh = 16.39 * u
        const paramLh = 11.54 * u
        const blockH = modelLh + 3 * u + paramLh
        const y0 = areaTop + (areaH - blockH) / 2
        drawFitted(ctx, device, W / 2, y0 + modelLh / 2, 340 * u, 12 * u, theme.fg, {
          family: FONT_VENDOR,
          weight: 500,
          align: 'center',
          baseline: 'middle',
          tracking: 0.03,
          min: 8 * u
        })
        if (exifTextLine) {
          ctx.save()
          ctx.globalAlpha = 0.8
          drawFitted(ctx, exifTextLine, W / 2, y0 + modelLh + 3 * u + paramLh / 2, 340 * u, 8.45 * u, theme.fg, {
            family: FONT_VENDOR,
            weight: 500,
            align: 'center',
            baseline: 'middle',
            tracking: 0.03,
            min: 6 * u
          })
          ctx.restore()
        }
      } else {
        const modelLh = 14 * u
        const subLh = 9 * u
        const blockH = modelLh + 3 * u + subLh
        const y0 = areaTop + (areaH - blockH) / 2
        drawFitted(ctx, device, W / 2, y0 + modelLh / 2, 340 * u, 12 * u, theme.fg, {
          family: FONT_VENDOR,
          weight: 600,
          align: 'center',
          baseline: 'middle',
          tracking: 0.04,
          min: 8 * u
        })
        if (subText) {
          drawFitted(ctx, subText, W / 2, y0 + modelLh + 3 * u + subLh / 2, 340 * u, 8 * u, theme.fg, {
            family: FONT_VENDOR,
            weight: 400,
            align: 'center',
            baseline: 'middle',
            tracking: 0.08,
            min: 6 * u
          })
        }
      }
      return { x: side, y: topPad, w: pw, h: ph }
    }
    if (style === 'l1') {
      const cy = ph + footerH / 2
      const pad = 20 * u
      const modelLh = 14 * u
      const subLh = 8 * u
      const gap = 1 * u
      const blockH = modelLh + gap + subLh
      drawFitted(ctx, device, pad, cy - blockH / 2 + modelLh / 2, W * 0.6, 12 * u, theme.fg, {
        family: FONT_VENDOR,
        weight: 600,
        baseline: 'middle',
        tracking: 0.04,
        min: 8 * u
      })
      if (subText) {
        drawFitted(ctx, subText, pad, cy - blockH / 2 + modelLh + gap + subLh / 2, W * 0.6, 7 * u, theme.fg, {
          family: FONT_VENDOR,
          weight: 400,
          baseline: 'middle',
          tracking: 0.08,
          min: 5 * u
        })
      }
      drawBrandLogo(env, W - 16 * u - 52 * u, cy - 16 * u, 52 * u, 32 * u, theme.fg)
      return { x: 0, y: 0, w: pw, h: ph }
    }
    const cy = ph + footerH / 2
    if (vendor === 'hasselblad') {
      const S = Math.min(pw, ph)
      const pad = (S * 24) / 360
      const date = env.text.showDate ? formatDateOnly(env.exif.dateTime) : ''
      const left = date || model || full
      drawFitted(ctx, left, pad, cy, W * 0.5 - pad, (S * 13) / 360, theme.fg, {
        family: FONT_VENDOR,
        weight: 500,
        baseline: 'middle',
        min: S * 0.018
      })
      if (env.text.showLogo && env.images?.logo) {
        const lw = (S * 112.8) / 360
        const lh = (S * 8.96) / 360
        const rp = (S * 24) / 360
        const dot = (S * 7.2) / 360
        const gap = (S * 8.94) / 360
        const textSize = (S * 7.2) / 360
        const total = lh + gap + textSize
        const x = W - rp - lw
        const y0 = cy - total / 2
        drawBrandLogo(env, x, y0, lw, lh, theme.fg)
        const rowY = y0 + lh + gap + textSize / 2
        ctx.save()
        ctx.fillStyle = '#FF8933'
        ctx.beginPath()
        ctx.arc(x + dot / 2, rowY, dot / 2, 0, Math.PI * 2)
        ctx.fill()
        ctx.restore()
        ctx.save()
        ctx.globalAlpha = 0.7
        drawFitted(ctx, model || full, x + dot + (S * 6.6) / 360, rowY, lw - dot, textSize, theme.fg, {
          family: FONT_VENDOR,
          weight: 700,
          baseline: 'middle',
          min: textSize * 0.5
        })
        ctx.restore()
      } else {
        drawFitted(ctx, 'HASSELBLAD', W - pad, cy, W * 0.42, S * 0.03, theme.fg, {
          family: FONT_VENDOR,
          weight: 700,
          align: 'right',
          baseline: 'middle',
          min: S * 0.015
        })
      }
      return { x: 0, y: 0, w: pw, h: ph }
    }
    const pad = W * 0.04
    const square = vendor === 'zeiss'
    const logoH = square ? footerH * 0.55 : footerH * 0.32
    const logoW = square ? logoH : Math.min(W * 0.15, footerH * 1.2)
    const logoX = W - pad - logoW
    const textMax = Math.max(W * 0.2, logoX - pad - W * 0.03)
    const exif = env.text.showExif ? exifLine(env.exif, { separator: ' ' }) : ''
    const date = env.text.showDate ? formatDateOnly(env.exif.dateTime) : ''
    const titleSize = W * 0.024
    const subSize = W * 0.016
    const lines = [full || model || titleOf(env), exif, date].filter(Boolean)
    const lineGap = subSize * 0.55
    const blockH = lines.reduce((n, _, i) => n + (i === 0 ? titleSize : subSize) + (i ? lineGap : 0), 0)
    let y = cy - blockH / 2 + titleSize * 0.52
    lines.forEach((text, i) => {
      const size = i === 0 ? titleSize : subSize
      drawFitted(ctx, text, pad, y, textMax, size, i === 0 ? theme.fg : theme.sub, {
        family: FONT_VENDOR,
        weight: i === 0 ? 600 : 400,
        baseline: 'middle',
        min: size * 0.5
      })
      y += size + lineGap
    })
    if (env.text.showLogo && env.images?.logo) {
      drawBrandLogo(env, logoX, cy - logoH / 2, logoW, logoH, theme.fg)
    }
    return { x: 0, y: 0, w: pw, h: ph }
  }
}

const xiaomiLeica: TemplateDef = {
  id: 'xiaomiLeica',
  name: '小米 · 徕卡',
  en: 'XIAOMI LEICA',
  category: '厂商',
  description: '按小米相机 Leica 水印类反编译参数实现：短边 /1080 的 168/138 设计高度、左品牌右参数、分隔线、徕卡红标。',
  defaults: { theme: 'white', shadow: 0, radiusPct: 0, logoScale: 5, logoPos: 'right' },
  themes: lightThemes,
  controls: [], // A4 摘除假控件：见 _research/editing-model-hand/hand-controls-proposal.json.fakeControlInventory
  layout(env) {
    const ratio = Math.min(env.pw, env.ph) / 1080
    const designH = env.text.showDate ? 168 : 138
    const raw = designH * ratio
    return { W: env.pw, H: env.ph + Math.floor(raw / 8) * 8 }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const ratio = Math.min(pw, ph) / 1080
    const multi = env.text.showDate
    const footerH = H - ph
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, 0, 0, pw, ph, 0, 0)
    const pad = 50 * ratio
    const brand = [env.exif.make, modelWithoutMake(env.exif.make, env.exif.model)].filter(Boolean).join(' ') || titleOf(env)
    const exif = env.text.showExif ? exifLine(env.exif, { separator: '  ' }) : ''
    const brandSize = 30 * ratio
    const smallSize = 22 * ratio
    const topLineH = brandSize * 1.2
    const bottomLineH = smallSize * 1.2
    const gap = 20 * ratio
    const blockH = topLineH + (multi ? bottomLineH + gap : 0)
    const topCenter = ph + (footerH - blockH) / 2 + topLineH / 2
    const bottomCenter = ph + footerH - (footerH - blockH) / 2 - bottomLineH / 2
    drawFitted(ctx, brand, pad, topCenter, Math.max(W * 0.2, W * 0.55 - pad), brandSize, theme.fg, {
      family: FONT_VENDOR,
      weight: 500,
      baseline: 'middle',
      min: brandSize * 0.45
    })
    if (exif) {
      drawFitted(ctx, exif, W - pad, topCenter, W * 0.46, brandSize, theme.fg, {
        family: FONT_VENDOR,
        weight: 500,
        align: 'right',
        baseline: 'middle',
        min: brandSize * 0.45
      })
    }
    let exifW = 0
    if (exif) {
      ctx.save()
      setFont(ctx, brandSize, FONT_VENDOR, 500)
      exifW = ctx.measureText(exif).width
      ctx.restore()
    }
    if (multi) {
      const dt = formatDate(env.exif.dateTime).replace(/-/g, '.')
      if (dt) {
        drawFitted(ctx, dt, W - pad - exifW, bottomCenter, Math.max(W * 0.2, exifW), smallSize, 'rgba(0,0,0,0.55)', {
          family: FONT_VENDOR,
          weight: 400,
          baseline: 'middle',
          tracking: 0.05,
          min: smallSize * 0.5
        })
      }
    }
    if (exifW > 0) {
      const dividerX = W - pad - exifW - 22 * ratio
      const dividerTop = ph + (multi ? 52 : 44) * ratio
      ctx.save()
      ctx.fillStyle = 'rgba(0,0,0,0.2)'
      ctx.fillRect(dividerX, dividerTop, Math.max(2 * ratio, 1), H - dividerTop)
      ctx.restore()
      if (env.text.showLogo && env.images?.logo) {
        const logoH = (multi ? 64 : 58) * ratio
        const logoW = logoH
        const logoX = dividerX - 22 * ratio - logoW
        const logoY = ph + (footerH - logoH) / 2 + (multi ? 0 : 1)
        drawBrandLogo(env, logoX, logoY, logoW, logoH, theme.fg)
      }
    }
    return { x: 0, y: 0, w: pw, h: ph }
  }
}

// Template-local canvas aspect override. Fits the target aspect inside the
// cropped photo so the photo stays full-bleed at native resolution (the short
// side is never upscaled); canvasAspect <= 0 keeps the legacy crop-sized canvas.
function aspectCanvas(pw: number, ph: number, aspect: number): { W: number; H: number } {
  if (!(aspect > 0.05)) return { W: Math.max(2, Math.round(pw)), H: Math.max(2, Math.round(ph)) }
  if (pw / ph >= aspect) {
    return { W: Math.max(2, Math.round(ph * aspect)), H: Math.max(2, Math.round(ph)) }
  }
  return { W: Math.max(2, Math.round(pw)), H: Math.max(2, Math.round(pw / aspect)) }
}

const paramStack: TemplateDef = {
  id: 'paramStack',
  name: '四角 · 参数',
  en: 'PARAM STACK',
  category: '参数',
  description: '满幅照片，四行相机参数压在画面一角，原版「极简 01–04」。字体 Bebas Neue，带斜切。',
  defaults: {
    theme: 'ink',
    corner: 'tl',
    canvasAspect: 0,
    paramPadPct: 3,
    paramSizePct: 5.4,
    paramGapPct: 20,
    shadow: 0,
    radiusPct: 0,
    logoScale: 5,
    logoPos: 'none'
  },
  themes: darkThemes,
  controls: [], // A4 摘除假控件：见 _research/editing-model-hand/hand-controls-proposal.json.fakeControlInventory
  // simple-1/2/4 references are 1024x785 (1.3045), not the 4:3 audit baseline;
  // variant-scoped canvasAspect crops the export to the reference proportion.
  layout(env) {
    return aspectCanvas(env.pw, env.ph, pct(env.params.canvasAspect, 0))
  },
  render(env) {
    const { ctx, W, H } = env
    const theme = themeOf(env.params)
    drawPhotoCover(env, 0, 0, W, H, 0, 0)
    const corner = String(env.params.corner ?? 'tl')
    const right = corner.endsWith('r')
    const bottom = corner.startsWith('b')
    const S = Math.min(W, H)
    const size = (S * pct(env.params.paramSizePct, 5.4)) / 100
    const gap = (size * pct(env.params.paramGapPct, 20)) / 100
    // padX is % of W and padY % of the short side; the reference block sits at
    // 5.2 %W from the right edge but only 4.5 % of the short side from the top,
    // so each axis can be overridden independently (falls back to paramPadPct).
    const padXPct = pct(env.params.paramPadXPct, pct(env.params.paramPadPct, 3))
    const padYPct = pct(env.params.paramPadYPct, pct(env.params.paramPadPct, 3))
    const padX = (W * padXPct) / 100
    const padY = (S * padYPct) / 100
    const focal = env.exif.focal ? `${Math.round(env.exif.focal)}mm` : ''
    const aperture = formatAperture(env.exif.aperture)
    const shutter = formatShutter(env.exif.shutter).replace(/s$/, '')
    const iso = env.exif.iso ? `ISO ${env.exif.iso}` : ''
    const fallback = env.isPreview ? ['28mm', 'F2.8', '1/125', 'ISO 100'] : []
    const lines = [focal, aperture, shutter, iso].map((t, i) => t || fallback[i] || '').filter(Boolean)
    if (!lines.length) return { x: 0, y: 0, w: W, h: H }
    const blockH = lines.length * size + (lines.length - 1) * gap
    const x = right ? W - padX : padX
    let y = bottom ? H - padY - blockH + size / 2 : padY + size / 2
    ctx.save()
    ctx.fillStyle = '#ffffff'
    ctx.textBaseline = 'middle'
    ctx.textAlign = right ? 'right' : 'left'
    for (const text of lines) {
      ctx.save()
      ctx.translate(x, y)
      ctx.transform(1, 0, -0.2, 1, 0, 0)
      drawFitted(ctx, text, 0, 0, W * 0.6, size, '#ffffff', {
        family: '"Bebas Neue", "Inter", sans-serif',
        weight: 400,
        align: right ? 'right' : 'left',
        baseline: 'middle',
        tracking: 0.02,
        min: size * 0.6,
        shadow: true
      })
      ctx.restore()
      y += size + gap
    }
    ctx.restore()
    return { x: 0, y: 0, w: W, h: H }
  }
}

function profileName(env: TemplateRenderEnv): string {
  return String(env.params.profileName || env.text.title || env.text.subtitle || 'FrameMark').trim() || 'FrameMark'
}

function avatarRadius(env: TemplateRenderEnv, fallbackPct = 4.3): number {
  const S = Math.min(env.pw, env.ph)
  return (S * pct(env.params.avatarPct, fallbackPct)) / 200
}

function avatarNameSize(env: TemplateRenderEnv, fallbackPct = 2.6): number {
  const S = Math.min(env.pw, env.ph)
  return (S * pct(env.params.nameSizePct, fallbackPct)) / 100
}

function measureProfileName(env: TemplateRenderEnv, size: number): number {
  const ctx = env.ctx
  ctx.save()
  setFont(ctx, size, FONT_UI, 500)
  const w = ctx.measureText(profileName(env)).width
  ctx.restore()
  return w
}

function avatarChipWidth(env: TemplateRenderEnv, r: number, nameSize: number): number {
  return r * 2 + r * 0.9 + measureProfileName(env, nameSize)
}

function drawAvatarChip(
  env: TemplateRenderEnv,
  left: number,
  cy: number,
  r: number,
  color: string,
  nameSize: number,
  alpha = 1
): void {
  const { ctx } = env
  const theme = themeOf(env.params)
  const name = profileName(env)
  const cx = left + r
  const light = color === '#ffffff'
  ctx.save()
  ctx.globalAlpha = alpha
  ctx.beginPath()
  ctx.arc(cx, cy, r, 0, Math.PI * 2)
  ctx.clip()
  const avatar = env.images?.avatar
  if (avatar) {
    const iw = avatar.naturalWidth || avatar.width
    const ih = avatar.naturalHeight || avatar.height
    const scale = Math.max((r * 2) / iw, (r * 2) / ih)
    ctx.drawImage(avatar, cx - (iw * scale) / 2, cy - (ih * scale) / 2, iw * scale, ih * scale)
  } else {
    if (light) {
      const grad = ctx.createLinearGradient(cx - r, cy - r, cx + r, cy + r)
      grad.addColorStop(0, 'rgba(58,58,64,0.92)')
      grad.addColorStop(1, 'rgba(12,12,14,0.94)')
      ctx.fillStyle = grad
    } else {
      ctx.fillStyle = theme.fg
    }
    ctx.fillRect(cx - r, cy - r, r * 2, r * 2)
    ctx.fillStyle = light ? '#ffffff' : theme.bg
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    setFont(ctx, r * 0.96, FONT_UI, 600)
    ctx.fillText(name.slice(0, 1), cx, cy + r * 0.02)
  }
  ctx.restore()
  ctx.save()
  ctx.globalAlpha = alpha
  ctx.beginPath()
  ctx.arc(cx, cy, r, 0, Math.PI * 2)
  ctx.strokeStyle = light ? 'rgba(255,255,255,0.72)' : 'rgba(255,255,255,0.9)'
  ctx.lineWidth = Math.max(1, r * 0.09)
  ctx.stroke()
  ctx.restore()
  ctx.save()
  ctx.globalAlpha = alpha
  drawFitted(ctx, name, left + r * 2.9, cy, nameSize * 12, nameSize, color, {
    family: FONT_UI,
    weight: 500,
    baseline: 'middle',
    min: nameSize * 0.8,
    shadow: light
  })
  ctx.restore()
}

function techExifLine(env: TemplateRenderEnv): string {
  const e = env.exif
  const parts: string[] = []
  if (e.focal) parts.push(`${Math.round(e.focal)}mm`)
  if (e.shutter) parts.push(formatShutter(e.shutter))
  if (e.aperture) parts.push(formatAperture(e.aperture))
  if (e.iso) parts.push(`ISO ${e.iso}`)
  return parts.join('   ')
}

function classicExifLine(env: TemplateRenderEnv): string {
  const e = env.exif
  const parts: string[] = []
  if (e.focal) parts.push(`${Math.round(e.focal)}mm`)
  if (e.aperture) parts.push(formatAperture(e.aperture))
  if (e.iso) parts.push(`ISO${e.iso}`)
  if (e.shutter) parts.push(formatShutter(e.shutter))
  return parts.join('  ')
}

function drawTechText(
  env: TemplateRenderEnv,
  text: string,
  cx: number,
  cy: number,
  color: string,
  size: number,
  targetW: number,
  align: 'left' | 'center' | 'right' = 'center',
  alpha = 1,
  scaleX = 1
): void {
  if (!text) return
  const ctx = env.ctx
  ctx.save()
  ctx.globalAlpha = alpha
  ctx.translate(cx, cy)
  // Local per-template horizontal glyph scale (default 1 = unchanged). The
  // width clamp still applies to the un-scaled run, so scaling never shrinks
  // the font; it only widens the rendered line.
  if (scaleX !== 1) ctx.scale(scaleX, 1)
  drawFitted(ctx, text, 0, 0, targetW, size, color, {
    family: FONT_TECH,
    weight: 600,
    align,
    baseline: 'middle',
    tracking: 0.03,
    min: size * 0.7,
    shadow: color === '#ffffff'
  })
  ctx.restore()
}

function drawTechExif(
  env: TemplateRenderEnv,
  cx: number,
  cy: number,
  color: string,
  size: number,
  targetW: number,
  align: 'left' | 'center' | 'right' = 'center',
  alpha = 1,
  scaleX = 1
): void {
  drawTechText(env, techExifLine(env), cx, cy, color, size, targetW, align, alpha, scaleX)
}

const matteOverlay: TemplateDef = {
  id: 'matteOverlay',
  name: '留白 · 叠字',
  en: 'MATTE OVERLAY',
  category: '留白',
  description: '原版「白边 01–04」：照片外包一圈白边，品牌与参数压在照片内，白字带阴影。',
  defaults: {
    theme: 'white',
    marginPct: 4.5,
    radiusPct: 1.8,
    overlayMode: 'brand',
    overlayBrand: 'HASSELBLAD',
    exifAlign: 'center',
    brandWidthPct: 26.8,
    brandOffsetPct: 4.5,
    exifWidthPct: 42,
    exifSizePct: 1.55,
    exifOffsetPct: 5,
    avatarPct: 4,
    nameSizePct: 2.3,
    footerTextScale: 100,
    shadow: 0,
    logoScale: 5,
    logoPos: 'none'
  },
  themes: lightThemes,
  controls: ['margin', 'radius'], // A4 摘除假控件：见 _research/editing-model-hand/hand-controls-proposal.json.fakeControlInventory
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 4.5)) / 100
    const mt = (S * pct(env.params.marginTopPct, pct(env.params.marginPct, 4.5))) / 100
    const mb = (S * pct(env.params.marginBottomPct, pct(env.params.marginPct, 4.5))) / 100
    return { W: env.pw + 2 * m, H: env.ph + mt + mb }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = (W - pw) / 2
    const mt = (S * pct(env.params.marginTopPct, pct(env.params.marginPct, 4.5))) / 100
    const radius = (S * pct(env.params.radiusPct, 1.8)) / 100
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, mt, pw, ph, radius, 0)
    const mode = String(env.params.overlayMode ?? 'brand')
    if (mode === 'none') return { x: m, y: mt, w: pw, h: ph }
    const brandW = (S * pct(env.params.brandWidthPct, 26.8)) / 100
    const brandH = brandW / 14.63
    const brandCy = mt + ph - (S * pct(env.params.brandOffsetPct, 4.5)) / 100
    const exifSize = (S * pct(env.params.exifSizePct, 1.55)) / 100
    const exifW = (S * pct(env.params.exifWidthPct, 42)) / 100
    const exifScaleX = pct(env.params.exifScaleX, 1)
    const exifCy = mt + ph - (S * pct(env.params.exifOffsetPct, 5)) / 100
    const drawBrand = (cy: number) => {
      if (env.text.showLogo && env.images?.logo) {
        drawBrandLogo(env, W / 2 - brandW / 2, cy - brandH / 2, brandW, brandH, '#ffffff')
        return
      }
      const text = String(env.params.overlayBrand ?? '').trim()
      if (!text) return
      drawFitted(ctx, text, W / 2, cy, W * 0.8, brandH, '#ffffff', {
        family: FONT_VENDOR,
        weight: 600,
        align: 'center',
        baseline: 'middle',
        tracking: 0.18,
        min: brandH * 0.6,
        shadow: true
      })
    }
    if (mode === 'profile-chip') {
      const r = avatarRadius(env, 4)
      const nameSize = avatarNameSize(env, 2.3)
      const cw = avatarChipWidth(env, r, nameSize)
      drawAvatarChip(env, W / 2 - cw / 2, mt + S * 0.055, r, '#ffffff', nameSize)
      if (env.text.showExif) drawTechExif(env, W / 2, exifCy, '#ffffff', exifSize, exifW, 'center', 1, exifScaleX)
      return { x: m, y: mt, w: pw, h: ph }
    }
    if (mode === 'brand' || mode === 'brand-exif') drawBrand(brandCy)
    if ((mode === 'exif' || mode === 'brand-exif') && env.text.showExif) {
      const align = String(env.params.exifAlign ?? 'center') as 'left' | 'center' | 'right'
      const x = align === 'left' ? m + S * 0.04 : align === 'right' ? m + pw - S * 0.04 : W / 2
      drawTechText(env, classicExifLine(env), x, exifCy, '#ffffff', exifSize, exifW, align, 1, exifScaleX)
    }
    return { x: m, y: mt, w: pw, h: ph }
  }
}

// Optional fixed photo-window aspect (e.g. a reference card whose photo window
// is not the exported photo ratio). When `params.photoAspect` is absent the
// window equals the crop and behavior is unchanged. The window is inscribed in
// the current crop, so the photo is cover-cropped into it without upscaling.
function photoWindowOf(env: Pick<TemplateRenderEnv, 'pw' | 'ph' | 'params'>): {
  w: number
  h: number
  cover: boolean
} {
  const aspect = pct(env.params.photoAspect, 0)
  if (!(aspect > 0.05)) return { w: env.pw, h: env.ph, cover: false }
  if (aspect >= env.pw / env.ph) return { w: env.pw, h: env.pw / aspect, cover: true }
  return { w: env.ph * aspect, h: env.ph, cover: true }
}

const logoFooter: TemplateDef = {
  id: 'logoFooter',
  name: '图标 · 页脚',
  en: 'LOGO FOOTER',
  category: '卡片',
  description: '原版「卡片 01/02」：照片满宽，白色页脚居中放品牌图标，可选参数行。',
  defaults: {
    theme: 'white',
    insetPct: 0,
    footerPct: 14.8,
    radiusPct: 0,
    shadow: 0,
    logoPct: 5.5,
    exifSizePct: 2.3,
    exifWidthPct: 29.5,
    logoScale: 5,
    logoPos: 'center'
  },
  themes: lightThemes,
  controls: ['footer'],
  layout(env) {
    const { w: pw, h: ph } = photoWindowOf(env)
    const S = Math.min(pw, ph)
    const inset = (S * pct(env.params.insetPct, 0)) / 100
    const footerH = (S * pct(env.params.footerPct, 14.8)) / 100
    return { W: pw + 2 * inset, H: inset + ph + footerH }
  },
  render(env) {
    const { ctx, W, H } = env
    const { w: pw, h: ph, cover } = photoWindowOf(env)
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const inset = (W - pw) / 2
    const footerH = H - inset - ph
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    if (cover) drawPhotoCover(env, inset, inset, pw, ph, 0, 0)
    else drawPhoto(env, inset, inset, pw, ph, 0, 0)
    const showExif = env.text.showExif && Boolean(classicExifLine(env))
    const logoH = (S * pct(env.params.logoPct, 5.5)) / 100
    const logoW = logoH
    const logoCy = inset + ph + footerH * (showExif ? 0.32 : 0.5)
    if (env.text.showLogo) drawBrandLogo(env, W / 2 - logoW / 2, logoCy - logoH / 2, logoW, logoH, theme.fg)
    if (showExif) {
      drawTechText(
        env,
        classicExifLine(env),
        W / 2,
        inset + ph + footerH * 0.72,
        theme.fg,
        (S * pct(env.params.exifSizePct, 2.3)) / 100,
        (S * pct(env.params.exifWidthPct, 29.5)) / 100
      )
    }
    return { x: inset, y: inset, w: pw, h: ph }
  }
}

const matteFooter: TemplateDef = {
  id: 'matteFooter',
  name: '留白 · 页脚',
  en: 'MATTE FOOTER',
  category: '卡片',
  description: '原版「卡片 03/04」：四周细白边，照片下方留白放参数或图标字标。',
  defaults: {
    theme: 'white',
    marginPct: 1.15,
    footerPct: 9.5,
    footerOffsetPct: 0,
    radiusPct: 0,
    shadow: 0,
    logoPct: 5,
    brandTextSizePct: 2.6,
    exifSizePct: 1.9,
    exifWidthPct: 28.3,
    logoScale: 5,
    logoPos: 'center'
  },
  themes: lightThemes,
  controls: ['margin', 'footer'],
  layout(env) {
    const { w: pw, h: ph } = photoWindowOf(env)
    const S = Math.min(pw, ph)
    const m = (S * pct(env.params.marginPct, 1.15)) / 100
    const footerH = (S * pct(env.params.footerPct, 9.5)) / 100
    return { W: pw + 2 * m, H: ph + m + footerH }
  },
  render(env) {
    const { ctx, W, H } = env
    const { w: pw, h: ph, cover } = photoWindowOf(env)
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = (W - pw) / 2
    const footerH = H - ph - m
    const cy = m + ph + footerH / 2 + (S * pct(env.params.footerOffsetPct, 0)) / 100
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    if (cover) drawPhotoCover(env, m, m, pw, ph, 0, 0)
    else drawPhoto(env, m, m, pw, ph, 0, 0)
    const showExif = env.text.showExif && Boolean(classicExifLine(env))
    const brandText = String(env.params.overlayBrand ?? '').trim()
    if (env.text.showLogo && env.images?.logo && brandText) {
      const logoH = (S * pct(env.params.logoPct, 5)) / 100
      const textSize = (S * pct(env.params.brandTextSizePct, 2.6)) / 100
      ctx.save()
      setFont(ctx, textSize, FONT_VENDOR, 600)
      const textW = ctx.measureText(brandText).width
      ctx.restore()
      const gap = textSize * 1.3
      const total = logoH + gap + textW
      const x0 = W / 2 - total / 2
      drawBrandLogo(env, x0, cy - logoH / 2, logoH, logoH, theme.fg)
      drawFitted(ctx, brandText, x0 + logoH + gap, cy, textW + 2, textSize, theme.fg, {
        family: FONT_VENDOR,
        weight: 600,
        baseline: 'middle',
        tracking: 0.03,
        min: textSize * 0.6
      })
    } else if (env.text.showLogo && !brandText) {
      const logoH = showExif ? footerH * 0.45 : footerH * 0.55
      drawBrandLogo(env, W / 2 - logoH / 2, cy - logoH / 2, logoH, logoH, theme.fg)
    } else if (brandText) {
      drawFitted(ctx, brandText, W / 2, cy, W * 0.6, (S * pct(env.params.brandTextSizePct, 2.6)) / 100, theme.fg, {
        family: FONT_VENDOR,
        weight: 600,
        align: 'center',
        baseline: 'middle',
        tracking: 0.02,
        min: S * 0.015
      })
    }
    if (showExif) {
      drawTechText(
        env,
        classicExifLine(env),
        W / 2,
        cy,
        theme.fg,
        (S * pct(env.params.exifSizePct, 1.9)) / 100,
        (S * pct(env.params.exifWidthPct, 28.3)) / 100
      )
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const profileFrame: TemplateDef = {
  id: 'profileFrame',
  name: '个人 · 主页',
  en: 'PROFILE FRAME',
  category: '人物',
  description: '原版「人物 01–05」：头像/昵称与照片卡片组合，含页眉、浮层与页脚三种排布。',
  defaults: {
    theme: 'white',
    profileMode: 'header',
    profileName: '',
    marginPct: 4.2,
    headerPct: 11.7,
    footerPct: 11.3,
    radiusPct: 1.4,
    shadow: 0.8,
    brandPlacement: 'footer',
    brandOffsetPct: 8.2,
    brandCenterPct: 0,
    chipTopPct: 4.5,
    exifAlign: 'center',
    avatarPct: 4.3,
    nameSizePct: 2.6,
    brandWidthPct: 33.7,
    exifWidthPct: 40,
    exifSizePct: 1.55,
    exifOffsetPct: 3.9,
    modelSizePct: 2.6,
    footerLogoPct: 13.7,
    overlayBrand: 'HASSELBLAD',
    logoScale: 5,
    logoPos: 'center'
  },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const mode = String(env.params.profileMode ?? 'header')
    const m = (S * pct(env.params.marginPct, 4.2)) / 100
    if (mode === 'header') {
      const headerH = (S * pct(env.params.headerPct, 11.7)) / 100
      const footerH = (S * pct(env.params.footerPct, 11.3)) / 100
      return { W: env.pw + 2 * m, H: headerH + env.ph + footerH }
    }
    if (mode === 'chip') {
      const inside = String(env.params.brandPlacement ?? 'inside') !== 'footer'
      const footerH = inside ? 0 : (S * pct(env.params.footerPct, 8.4)) / 100
      // Bottom matte defaults to the uniform margin; person-3 needs 0 so the
      // ref photo sits directly on its footer band.
      const mb = (S * pct(env.params.marginBottomPct, pct(env.params.marginPct, 4.2))) / 100
      return { W: env.pw + 2 * m, H: env.ph + m + mb + footerH }
    }
    const footerH = (S * pct(env.params.footerPct, 9.8)) / 100
    return { W: env.pw + 2 * m, H: m + env.ph + footerH }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const mode = String(env.params.profileMode ?? 'header')
    const m = (S * pct(env.params.marginPct, 4.2)) / 100
    const radius = (S * pct(env.params.radiusPct, 1.4)) / 100
    const shadow = pct(env.params.shadow, 0.8)
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    const brandText = String(env.params.overlayBrand ?? 'HASSELBLAD')
    const brandW = (S * pct(env.params.brandWidthPct, 33.7)) / 100
    const brandH = brandW / 14.63
    const exifSize = (S * pct(env.params.exifSizePct, 1.55)) / 100
    const exifW = (S * pct(env.params.exifWidthPct, 40)) / 100
    const exifScaleX = pct(env.params.exifScaleX, 1)
    const exifOffset = (S * pct(env.params.exifOffsetPct, 3.9)) / 100
    const brandCenter = pct(env.params.brandCenterPct, 0)
    const footerBrandCy = (photoBottom: number, footerH: number): number =>
      brandCenter > 0 ? photoBottom + (S * brandCenter) / 100 : photoBottom + footerH / 2
    const drawBrand = (cx: number, cy: number, color: string) => {
      if (env.text.showLogo && env.images?.logo) {
        drawBrandLogo(env, cx - brandW / 2, cy - brandH / 2, brandW, brandH, color)
      } else {
        drawFitted(ctx, brandText, cx, cy, brandW * 1.1, brandH * 1.35, color, {
          family: FONT_VENDOR,
          weight: 600,
          align: 'center',
          baseline: 'middle',
          tracking: 0.18,
          min: brandH * 0.6,
          shadow: color === '#ffffff'
        })
      }
    }
    const r = avatarRadius(env, 4.3)
    const nameSize = avatarNameSize(env, 2.6)
    const chipW = avatarChipWidth(env, r, nameSize)
    if (mode === 'header') {
      const headerH = (S * pct(env.params.headerPct, 11.7)) / 100
      const footerH = (S * pct(env.params.footerPct, 11.3)) / 100
      drawPhoto(env, m, headerH, pw, ph, radius, shadow)
      drawAvatarChip(env, W / 2 - chipW / 2, headerH * 0.5, r, theme.fg, nameSize)
      if (env.text.showExif) drawTechExif(env, W / 2, headerH + ph - exifOffset, '#ffffff', exifSize, exifW, 'center', 1, exifScaleX)
      drawBrand(W / 2, footerBrandCy(headerH + ph, footerH), theme.fg)
      return { x: m, y: headerH, w: pw, h: ph }
    }
    if (mode === 'chip') {
      const inside = String(env.params.brandPlacement ?? 'inside') !== 'footer'
      const footerH = inside ? 0 : (S * pct(env.params.footerPct, 8.4)) / 100
      drawPhoto(env, m, m, pw, ph, radius, shadow)
      drawAvatarChip(env, W / 2 - chipW / 2, m + (S * pct(env.params.chipTopPct, 4.5)) / 100, r, '#ffffff', nameSize)
      if (inside) {
        drawBrand(W / 2, m + ph - (S * pct(env.params.brandOffsetPct, 8.2)) / 100, '#ffffff')
        if (env.text.showExif) drawTechExif(env, W / 2, m + ph - exifOffset, '#ffffff', exifSize, exifW, 'center', 1, exifScaleX)
      } else {
        if (env.text.showExif) drawTechExif(env, W / 2, m + ph - exifOffset, '#ffffff', exifSize, exifW, 'center', 1, exifScaleX)
        drawBrand(W / 2, footerBrandCy(m + ph, footerH), theme.fg)
      }
      return { x: m, y: m, w: pw, h: ph }
    }
    const footerH = (S * pct(env.params.footerPct, 9.8)) / 100
    drawPhoto(env, m, m, pw, ph, radius, shadow)
    const cy = m + ph + footerH / 2
    drawAvatarChip(env, m + S * 0.027, cy, r, theme.fg, nameSize)
    if (env.text.showExif) {
      const right = m + pw - S * 0.004
      drawTechExif(env, right, cy, theme.fg, exifSize, exifW, 'right', 1, exifScaleX)
      let cursor = right - exifW - S * 0.026
      ctx.save()
      ctx.strokeStyle = theme.line
      ctx.lineWidth = Math.max(1, S * 0.0015)
      ctx.beginPath()
      ctx.moveTo(cursor, cy - S * 0.014)
      ctx.lineTo(cursor, cy + S * 0.014)
      ctx.stroke()
      ctx.restore()
      cursor -= S * 0.03
      const logoW = (S * pct(env.params.footerLogoPct, 13.7)) / 100
      const logoH = logoW / 5.92
      if (env.text.showLogo && env.images?.logo) {
        drawBrandLogo(env, cursor - logoW, cy - logoH / 2, logoW, logoH, theme.fg)
        cursor -= logoW + S * 0.03
      }
      const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
      const modelSize = (S * pct(env.params.modelSizePct, 2.6)) / 100
      if (model) {
        drawFitted(ctx, model, cursor, cy, W * 0.3, modelSize, theme.fg, {
          family: FONT_UI,
          weight: 500,
          align: 'right',
          baseline: 'middle',
          min: modelSize * 0.7
        })
      }
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const overlay: TemplateDef = {
  id: 'overlay',
  name: '浮层 · 参数',
  en: 'OVERLAY',
  category: '浮层',
  description: '满幅照片，底部渐变浮层，电影字幕式参数。',
  defaults: { marginPct: 0, radiusPct: 0, shadow: 0, footerPct: 0, theme: 'ink', logoScale: 5 },
  themes: darkThemes,
  controls: [],
  layout(env) {
    return { W: env.pw, H: env.ph }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const theme = themeOf(env.params)
    drawPhoto(env, 0, 0, W, H, 0, 0)
    const grad = ctx.createLinearGradient(0, H * 0.48, 0, H)
    grad.addColorStop(0, 'rgba(0,0,0,0)')
    grad.addColorStop(0.55, 'rgba(0,0,0,0.28)')
    grad.addColorStop(1, 'rgba(0,0,0,0.82)')
    ctx.fillStyle = grad
    ctx.fillRect(0, 0, W, H)
    const x = W * 0.06
    const maxW = W * 0.84
    let y = H * 0.885
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, x, y, maxW, S * 0.015, 'rgba(255,255,255,0.68)', {
        family: FONT_MONO,
        min: S * 0.009,
        shadow: true
      })
      y -= S * 0.035
    }
    const ex = exifText(env)
    if (ex) {
      drawFitted(ctx, ex, x, y, maxW, S * 0.019, 'rgba(255,255,255,0.82)', {
        family: FONT_MONO,
        min: S * 0.01,
        shadow: true
      })
      y -= S * 0.06
    }
    const title = titleOf(env)
    drawFitted(ctx, title, x, y, maxW, S * 0.058, '#ffffff', {
      family: FONT_CN,
      weight: 400,
      min: S * 0.025,
      shadow: true
    })
    line(ctx, x, y - S * 0.10, x + S * 0.08, y - S * 0.10, theme.accent, Math.max(1, S * 0.0016))
    const cam = env.text.showMake || env.text.showModel ? cameraLine(env.exif, '') : ''
    if (cam) {
      drawFitted(ctx, cam.toUpperCase(), x, y - S * 0.075, maxW, S * 0.015, 'rgba(255,255,255,0.72)', {
        family: FONT_UI,
        weight: 600,
        tracking: 0.16,
        min: S * 0.008,
        shadow: true
      })
    }
    if (env.text.signature.trim()) {
      drawFitted(ctx, env.text.signature, W - x, H * 0.885, W * 0.35, S * 0.032, 'rgba(255,255,255,0.85)', {
        align: 'right',
        family: FONT_HAND,
        min: S * 0.016,
        shadow: true
      })
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

// Bare cover: forced 16:9 full-bleed photo, no overlay text or logo.
const bareCover: TemplateDef = {
  id: 'bareCover',
  name: '纯封面',
  en: 'BARE COVER',
  category: '精选',
  description: '满幅 16:9 纯封面，无叠加文字或图标。',
  defaults: { theme: 'ink', photoAspect: 16 / 9 },
  themes: darkThemes,
  controls: [],
  layout(env) {
    const { w, h } = photoWindowOf(env)
    return { W: w, H: h }
  },
  render(env) {
    const { ctx, W, H } = env
    ctx.fillStyle = '#000000'
    ctx.fillRect(0, 0, W, H)
    drawPhotoCover(env, 0, 0, W, H, 0, 0)
    return { x: 0, y: 0, w: W, h: H }
  }
}

const vertical: TemplateDef = {
  id: 'vertical',
  name: '浮层 · 竖排',
  en: 'VERTICAL',
  category: '浮层',
  description: '左侧竖向大字与参数，像杂志封面的一行题。',
  defaults: { marginPct: 0, radiusPct: 0, shadow: 0, footerPct: 0, theme: 'ink', logoScale: 5 },
  themes: darkThemes,
  controls: [],
  layout(env) {
    return { W: env.pw, H: env.ph }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const theme = themeOf(env.params)
    drawPhoto(env, 0, 0, W, H, 0, 0)
    const grad = ctx.createLinearGradient(0, 0, W * 0.48, 0)
    grad.addColorStop(0, 'rgba(0,0,0,0.72)')
    grad.addColorStop(0.55, 'rgba(0,0,0,0.28)')
    grad.addColorStop(1, 'rgba(0,0,0,0)')
    ctx.fillStyle = grad
    ctx.fillRect(0, 0, W, H)
    const x = W * 0.095
    const y = H * 0.88
    const maxLen = H * 0.64
    const title = titleOf(env)
    setTracking(ctx, S * 0.004)
    const titleSize = fitSize(ctx, title, maxLen, S * 0.05, S * 0.02, FONT_CN, 400, 0.04)
    setTracking(ctx, 0)
    drawVerticalText(ctx, title, x, y, titleSize, '#ffffff', { family: FONT_CN, weight: 400, tracking: 0.04 })
    const ex = exifText(env)
    if (ex) {
      const exSize = fitSize(ctx, ex, maxLen * 0.8, S * 0.016, S * 0.009, FONT_MONO, 400, 0.03)
      drawVerticalText(ctx, ex, x + titleSize * 1.9, y, exSize, 'rgba(255,255,255,0.7)', {
        family: FONT_MONO,
        weight: 400,
        tracking: 0.03
      })
    }
    line(ctx, x - S * 0.03, y, x - S * 0.03, y - maxLen, theme.accent, Math.max(1, S * 0.0015))
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, W - W * 0.06, H * 0.93, W * 0.32, S * 0.014, 'rgba(255,255,255,0.68)', {
        align: 'right',
        family: FONT_MONO,
        min: S * 0.008,
        shadow: true
      })
    }
    if (env.text.showLogo) {
      drawBrandLogo(env, W - W * 0.2, H * 0.055, W * 0.14, H * 0.035, '#ffffff')
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

const blur: TemplateDef = {
  id: 'blur',
  name: '虚化 · 背景',
  en: 'BLUR BACKDROP',
  category: '氛围',
  description: '照片本身虚化成底，前景卡片带圆角与阴影，参数居中。',
  defaults: { marginPct: 3.5, radiusPct: 2, shadow: 6, footerPct: 11, blur: 26, theme: 'paper', logoScale: 5, minimalFooter: false },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer', 'blur'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 3.5)) / 100
    const footer = Math.max((S * pct(env.params.footerPct, 11)) / 100, S * 0.12)
    return { W: env.pw + 2 * m, H: env.ph + 2 * m + footer }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = marginOf(env)
    const footer = H - ph - 2 * m
    const isDark = theme === THEMES.ink || theme === THEMES.black
    drawBlurredBackdrop(env, W, H, theme, isDark ? 0.42 : 0.34)
    drawPhoto(env, m, m, pw, ph, radiusOf(env), pct(env.params.shadow, 6))
    const cx = W / 2
    if (env.params.minimalFooter === true) {
      // Never invent a date: showDate gates the row, and when it is on we use the
      // photo's EXIF capture date only. A missing capture date renders nothing.
      const dt = dateText(env)
      drawFitted(ctx, titleOf(env).toUpperCase(), cx, m + ph + m + footer * 0.42, W - 2 * m, S * 0.019, theme.sub, {
        align: 'center',
        family: FONT_UI,
        weight: 600,
        tracking: 0.14,
        min: S * 0.01
      })
      drawFitted(ctx, dt, cx, m + ph + m + footer * 0.68, W - 2 * m, S * 0.024, theme.fg, {
        align: 'center',
        family: FONT_MONO,
        min: S * 0.012
      })
      return { x: m, y: m, w: pw, h: ph }
    }
    const titleY = m + ph + m + footer * 0.34
    let y = titleY
    drawFitted(ctx, titleOf(env).toUpperCase(), cx, y, W - 2 * m, S * 0.024, theme.fg, {
      align: 'center',
      weight: 600,
      tracking: 0.12,
      family: FONT_UI,
      min: S * 0.013
    })
    const ex = exifText(env)
    if (ex) {
      y += footer * 0.26
      y += drawFitted(ctx, ex, cx, y, W - 2 * m, S * 0.017, theme.sub, {
        align: 'center',
        family: FONT_MONO,
        min: S * 0.009
      })
    }
    const dt = dateText(env)
    if (dt) {
      y += footer * 0.2
      drawFitted(ctx, dt, cx, y, W - 2 * m, S * 0.014, theme.sub, {
        align: 'center',
        family: FONT_MONO,
        min: S * 0.008
      })
    }
    // logoPos is only read here so the mark can be parked in the footer margin.
    // 'center' (the default) keeps the historic placement byte-for-byte; 'left'
    // / 'right' clear the centred model and EXIF runs on the x axis instead of
    // sharing their centre line, and 'none' drops the mark.
    const logoPos = String(env.params.logoPos ?? 'center')
    if (env.text.showLogo && logoPos !== 'none') {
      const lw = S * 0.18
      const lh = footer * 0.18
      const lx = logoPos === 'left' ? m : logoPos === 'right' ? W - m - lw : cx - lw / 2
      const ly = logoPos === 'center' ? m + ph + m + footer * 0.08 : titleY - lh / 2
      drawBrandLogo(env, lx, ly, lw, lh, theme.fg)
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const palette: TemplateDef = {
  id: 'palette',
  name: '色卡 · 留白',
  en: 'PALETTE',
  category: '留白',
  description: '从照片自动提取五色，连同参数一起排版。',
  defaults: { marginPct: 6.5, radiusPct: 1, shadow: 2, footerPct: 19, theme: 'paper', logoScale: 5, paletteOverlay: false },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer'],
  layout(env) {
    if (env.params.paletteOverlay === true) return { W: env.pw, H: env.ph }
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 6.5)) / 100
    const bottom = Math.max((S * pct(env.params.footerPct, 19)) / 100, S * 0.22)
    return { W: env.pw + 2 * m, H: env.ph + m + bottom }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    if (env.params.paletteOverlay === true) {
      drawPhoto(env, 0, 0, W, H, 0, 0)
      const grad = ctx.createLinearGradient(0, H * 0.55, 0, H)
      grad.addColorStop(0, 'rgba(0,0,0,0)')
      grad.addColorStop(1, 'rgba(0,0,0,0.7)')
      ctx.fillStyle = grad
      ctx.fillRect(0, 0, W, H)
      drawFitted(ctx, titleOf(env), W * 0.06, H * 0.80, W * 0.6, S * 0.032, '#ffffff', {
        family: FONT_UI,
        weight: 600,
        min: S * 0.016,
        shadow: true
      })
      const ex = exifText(env)
      if (ex) {
        drawFitted(ctx, ex, W * 0.06, H * 0.85, W * 0.6, S * 0.016, 'rgba(255,255,255,0.75)', {
          family: FONT_MONO,
          min: S * 0.009,
          shadow: true
        })
      }
      const colors = extractPalette(env, 5)
      drawSwatches(env, colors, W * 0.06, H * 0.885, W * 0.55, S * 0.035, 'rgba(255,255,255,0.64)')
      return { x: 0, y: 0, w: W, h: H }
    }
    const m = marginOf(env)
    const bottom = H - ph - m
    const baseY = m + ph
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, m, pw, ph, radiusOf(env), pct(env.params.shadow, 2))
    line(ctx, m, baseY + bottom * 0.09, W - m, baseY + bottom * 0.09, theme.line, Math.max(1, S * 0.0008))
    const portrait = ph >= pw
    const titleY = baseY + bottom * 0.29
    drawFitted(ctx, titleOf(env), m, titleY, portrait ? W - 2 * m : W * 0.46, S * 0.029, theme.fg, {
      family: FONT_UI,
      weight: 600,
      tracking: 0.02,
      min: S * 0.014
    })
    const ex = exifText(env)
    if (ex) {
      drawFitted(ctx, ex, m, titleY + S * 0.03, portrait ? W - 2 * m : W * 0.46, S * 0.016, theme.sub, {
        family: FONT_MONO,
        min: S * 0.009
      })
    }
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, m, baseY + bottom * 0.85, W * 0.36, S * 0.014, theme.sub, {
        family: FONT_MONO,
        min: S * 0.008
      })
    }
    const colors = extractPalette(env, 5)
    const sw = S * (portrait ? 0.046 : 0.038)
    const swatchY = portrait ? baseY + bottom * 0.48 : baseY + bottom * 0.38
    drawSwatches(env, colors, portrait ? m : W * 0.51, swatchY, portrait ? W - 2 * m : W * 0.45, sw, theme.sub)
    if (env.text.showLogo) {
      drawBrandLogo(env, W - m - S * 0.16, baseY + bottom * 0.66, S * 0.16, S * 0.028, theme.fg)
    }
    return { x: m, y: m, w: pw, h: ph }
  }
}

const film: TemplateDef = {
  id: 'film',
  name: '胶片 · 齿孔',
  en: 'FILM',
  category: '胶片',
  description: '上下齿孔与胶片边码，带一点颗粒和曝光标记。',
  defaults: { marginPct: 2.5, radiusPct: 0, shadow: 0, footerPct: 0, theme: 'black', grain: 0.16, logoScale: 5 },
  themes: darkThemes,
  controls: ['grain'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const side = S * 0.025
    const band = S * 0.075
    return { W: env.pw + 2 * side, H: env.ph + 2 * band }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const S = Math.min(pw, ph)
    const side = S * 0.025
    const band = (H - ph) / 2
    const theme = themeOf(env.params)
    ctx.fillStyle = theme === THEMES.paper ? '#e8e1d2' : '#0b0a09'
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, side, band, pw, ph, 0, 0)
    ctx.save()
    ctx.strokeStyle = theme === THEMES.paper ? 'rgba(20,20,20,0.35)' : 'rgba(230,222,204,0.28)'
    ctx.lineWidth = Math.max(1, S * 0.0008)
    ctx.strokeRect(side, band, pw, ph)
    ctx.restore()
    const holeColor = theme === THEMES.paper ? 'rgba(20,20,20,0.55)' : '#ded5c2'
    drawSprockets(ctx, 0, 0, W, band, Math.max(6, Math.round(W / (S * 0.075))), holeColor)
    drawSprockets(ctx, 0, H - band, W, band, Math.max(6, Math.round(W / (S * 0.075))), holeColor)
    const ink = theme === THEMES.paper ? '#141414' : '#f0eadc'
    const sub = theme === THEMES.paper ? 'rgba(20,20,20,0.62)' : 'rgba(240,234,220,0.66)'
    const x0 = side + S * 0.018
    const x1 = W - side - S * 0.018
    drawFitted(ctx, 'FRAMEMARK 400', x0, band + S * 0.042, W * 0.38, S * 0.013, ink, {
      family: FONT_MONO,
      weight: 500,
      tracking: 0.1,
      min: S * 0.008,
      shadow: true
    })
    drawFitted(ctx, '36 EXP', x1, band + S * 0.042, W * 0.22, S * 0.013, sub, {
      align: 'right',
      family: FONT_MONO,
      tracking: 0.08,
      min: S * 0.008,
      shadow: true
    })
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, x0, H - band - S * 0.018, W * 0.4, S * 0.012, sub, {
        family: FONT_MONO,
        min: S * 0.008,
        shadow: true
      })
    }
    const frameNo = String(Math.abs((env.exif.dateTime || env.exif.model || 'FRAME').split('').reduce((a, c) => a * 31 + c.charCodeAt(0), 7)) % 99).padStart(2, '0')
    drawFitted(ctx, `NO. ${frameNo}`, x1, H - band - S * 0.018, W * 0.3, S * 0.012, sub, {
      align: 'right',
      family: FONT_MONO,
      min: S * 0.008,
      shadow: true
    })
    const cam = env.text.showMake || env.text.showModel ? cameraLine(env.exif, '') : ''
    if (cam) {
      drawFitted(ctx, cam.toUpperCase(), x0, H - band - S * 0.042, W * 0.55, S * 0.01, sub, {
        family: FONT_UI,
        weight: 600,
        tracking: 0.14,
        min: S * 0.007,
        shadow: true
      })
    }
    drawGrain(env, W, H, pct(env.params.grain, 0.16) * 0.55)
    return { x: side, y: band, w: pw, h: ph }
  }
}

const polaroid: TemplateDef = {
  id: 'polaroid',
  name: '拍立得',
  en: 'POLAROID',
  category: '胶片',
  description: '粗白边、手写标题与日期，像一张刚显影的相纸。',
  defaults: { marginPct: 5.5, radiusPct: 0.5, shadow: 3, footerPct: 22, theme: 'paper', grain: 0.06, logoScale: 5 },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow', 'footer', 'grain'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 5.5)) / 100
    const bottom = Math.max((S * pct(env.params.footerPct, 22)) / 100, S * 0.22)
    return { W: env.pw + 2 * m, H: env.ph + m + bottom }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = marginOf(env)
    const bottom = H - ph - m
    const baseY = m + ph
    ctx.fillStyle = theme === THEMES.paper ? '#fbf8f0' : theme.bg
    ctx.fillRect(0, 0, W, H)
    ctx.save()
    ctx.globalAlpha = 0.55
    ctx.translate(W / 2, m * 0.45)
    ctx.rotate(-0.018)
    ctx.fillStyle = 'rgba(255,250,236,0.7)'
    ctx.fillRect(-W * 0.1, -S * 0.018, W * 0.2, S * 0.036)
    ctx.strokeStyle = 'rgba(120,110,90,0.12)'
    ctx.strokeRect(-W * 0.1, -S * 0.018, W * 0.2, S * 0.036)
    ctx.restore()
    drawPhoto(env, m, m, pw, ph, radiusOf(env), pct(env.params.shadow, 3))
    ctx.save()
    ctx.strokeStyle = 'rgba(80,72,60,0.22)'
    ctx.lineWidth = Math.max(1, S * 0.0008)
    ctx.strokeRect(m, m, pw, ph)
    ctx.restore()
    const title = titleOf(env)
    drawFitted(ctx, title, m, baseY + bottom * 0.38, W - 2 * m - S * 0.16, S * 0.062, '#2a241e', {
      family: FONT_HAND,
      weight: 600,
      min: S * 0.026
    })
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, W - m, baseY + bottom * 0.38, S * 0.26, S * 0.03, '#6d6254', {
        align: 'right',
        family: FONT_HAND,
        min: S * 0.016
      })
    }
    const ex = exifText(env)
    if (ex) {
      drawFitted(ctx, ex, m, baseY + bottom * 0.53, W - 2 * m, S * 0.014, '#7b7264', {
        family: FONT_MONO,
        min: S * 0.008
      })
    }
    if (env.text.signature.trim()) {
      drawFitted(ctx, env.text.signature, m, baseY + bottom * 0.70, W - 2 * m, S * 0.039, '#3a332b', {
        family: FONT_HAND,
        min: S * 0.02
      })
    }
    drawGrain(env, W, H, pct(env.params.grain, 0.06) * 0.55)
    return { x: m, y: m, w: pw, h: ph }
  }
}

const editorial: TemplateDef = {
  id: 'editorial',
  name: '杂志 · 编辑',
  en: 'EDITORIAL',
  category: '编辑',
  description: '米白底、大标题、细分隔线与三栏参数，像一页摄影杂志。',
  defaults: { marginPct: 8.5, radiusPct: 0, shadow: 0, footerPct: 14, theme: 'cream', logoScale: 5 },
  themes: lightThemes,
  controls: ['margin'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 8.5)) / 100
    const header = Math.max(S * 0.18, S * 0.15)
    const footer = Math.max((S * pct(env.params.footerPct, 14)) / 100, S * 0.13)
    return { W: env.pw + 2 * m, H: env.ph + header + footer }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const m = marginOf(env)
    const footerH = Math.max((S * pct(env.params.footerPct, 14)) / 100, S * 0.13)
    const top = H - ph - footerH
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    const issue = `NO. ${String(Math.abs((env.exif.dateTime || '01').split('').reduce((a, c) => a * 31 + c.charCodeAt(0), 3)) % 99).padStart(2, '0')}`
    const headY = top * 0.30
    const headRun = measureFitted(ctx, 'FRAMEMARK / PHOTO ANNUAL', W * 0.6, S * 0.013, S * 0.008, FONT_MONO, 500, 0.1)
    drawFitted(ctx, 'FRAMEMARK / PHOTO ANNUAL', m, headY, W * 0.6, S * 0.013, theme.sub, {
      family: FONT_MONO,
      weight: 500,
      tracking: 0.1,
      min: S * 0.008
    })
    drawFitted(ctx, issue, W - m, headY, W * 0.22, S * 0.013, theme.sub, {
      align: 'right',
      family: FONT_MONO,
      tracking: 0.06,
      min: S * 0.008
    })
    // #2 — the headline no longer sits a fixed 0.32*top under the masthead. Its
    // em box is now placed under the masthead's em box plus a fifth of an em of
    // clearance, measured at the sizes the ink is actually painted at, so the row
    // flows instead of colliding as 字号缩放 grows (overlap-check fm-editorial
    // op0xop2: 60.85% of the masthead box eaten at 1.22). At 1.0 the nominal
    // offset is already the larger of the two, so the position is unchanged.
    const titleSize = S * 0.072
    const titleRun = measureFitted(ctx, titleOf(env), W - 2 * m, titleSize, S * 0.028, FONT_CN, 400, 0)
    const titleY = Math.max(
      top * 0.62,
      headY + headRun.size * INK_BELOW + headRun.size * 0.2 + inkTops(titleRun.size, 'alphabetic')
    )
    drawFitted(ctx, titleOf(env), m, titleY, W - 2 * m, titleSize, theme.fg, {
      family: FONT_CN,
      weight: 400,
      min: S * 0.028
    })
    const sub = subLine(env)
    if (sub) {
      drawFitted(ctx, sub, W - m, top * 0.54, W * 0.45, S * 0.016, theme.sub, {
        align: 'right',
        family: FONT_UI,
        min: S * 0.009
      })
    }
    line(ctx, m, top * 0.86, W - m, top * 0.86, theme.line, Math.max(1, S * 0.0009))
    drawPhoto(env, m, top, pw, ph, 0, 0)
    ctx.save()
    ctx.strokeStyle = theme.line
    ctx.lineWidth = Math.max(1, S * 0.0007)
    ctx.strokeRect(m, top, pw, ph)
    ctx.restore()
    const footY = top + ph + S * 0.035
    line(ctx, m, footY, W - m, footY, theme.line, Math.max(1, S * 0.0009))
    const colW = (W - 2 * m) / 3
    const labels = ['CAMERA', 'OPTICS', 'DATE']
    const values = [
      cameraLine(env.exif, 'FRAMEMARK'),
      [env.exif.lens, exifLine(env.exif)].filter(Boolean).join('  ') || '—',
      formatDateOnly(env.exif.dateTime) || '—'
    ]
    // #2 — the three footer columns flow from measured ink instead of from the
    // nominal thirds. The OPTICS value is a long lens string: its slot is
    // `colW - S*0.02`, but the fit ladder bottoms out at `min` and the ink keeps
    // spilling into the DATE column (overlap-check fm-editorial op7xop9, 34.2%,
    // new because #69 A5 let that row actually scale for the first time). Each
    // column now starts at max(nominal, the previous column's measured right edge
    // + 0.85 em of clearance) and is fitted to the slot that leaves, so nothing
    // can reach into its neighbour at any 字号缩放. At 1.0 the nominal thirds are
    // already the larger term, so the positions are the accepted baseline.
    const labelSizes = labels.map((t) =>
      measureFitted(ctx, t, colW - S * 0.02, S * 0.011, S * 0.007, FONT_MONO, 500, 0.12)
    )
    const valueSizes = values.map((t, i) =>
      measureFitted(ctx, t, colW - S * 0.02, S * 0.016, S * 0.009, i === 2 ? FONT_MONO : FONT_UI, 500, 0)
    )
    const colX: number[] = []
    for (let i = 0; i < labels.length; i++) {
      const nominal = m + i * colW
      if (i === 0) {
        colX.push(nominal)
        continue
      }
      const prev = Math.max(labelSizes[i - 1].width, valueSizes[i - 1].width)
      const gap = Math.max(valueSizes[i - 1].size, labelSizes[i - 1].size) * 0.85
      colX.push(Math.max(nominal, colX[i - 1] + prev + gap))
    }
    // the row keeps its right margin: a column the flow pushed past it moves back
    // (its own measured content is what has to stay inside), and the neighbour it
    // gave room to keeps the 0.85 em clearance it was placed with
    const last = colX.length - 1
    const rightEdge = W - m - Math.max(labelSizes[last].width, valueSizes[last].width)
    if (rightEdge > colX[last - 1]) colX[last] = Math.min(colX[last], rightEdge)
    for (let i = 0; i < labels.length; i++) {
      const x = colX[i]
      // The slot stays the declared design share (`colW - S*0.02`): with #69 A5 a
      // slot fits `slot * 字号缩放` of ink, which is exactly how much the row paints,
      // so re-declaring it here would be shrinking the type to dodge the collision.
      drawFitted(ctx, labels[i], x, footY + S * 0.03, colW - S * 0.02, S * 0.011, theme.sub, {
        family: FONT_MONO,
        weight: 500,
        tracking: 0.12,
        min: S * 0.007
      })
      drawFitted(ctx, values[i], x, footY + S * 0.06, colW - S * 0.02, S * 0.016, theme.fg, {
        family: i === 2 ? FONT_MONO : FONT_UI,
        weight: 500,
        min: S * 0.009
      })
    }
    if (env.text.signature.trim()) {
      drawFitted(ctx, env.text.signature, W - m, H - S * 0.02, W * 0.35, S * 0.028, theme.fg, {
        align: 'right',
        family: FONT_HAND,
        min: S * 0.015
      })
    }
    return { x: m, y: top, w: pw, h: ph }
  }
}

const glass: TemplateDef = {
  id: 'glass',
  name: '液态玻璃',
  en: 'LIQUID GLASS',
  category: '氛围',
  description: '满幅照片上的磨砂玻璃信息卡，带高光与细边。',
  defaults: {
    marginPct: 0,
    radiusPct: 0,
    shadow: 0,
    footerPct: 0,
    blur: 24,
    theme: 'ink',
    logoScale: 5,
    glassAlign: 'left',
    glassLayout: 'card',
    glassPalette: false
  },
  themes: darkThemes,
  controls: ['blur'],
  layout(env) {
    return { W: env.pw, H: env.ph }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const theme = themeOf(env.params)
    drawPhoto(env, 0, 0, W, H, 0, 0)
    const portrait = H > W
    const align = String(env.params.glassAlign ?? 'left')
    const layoutMode = String(env.params.glassLayout ?? 'card')
    let cardW = W * (portrait ? 0.78 : 0.62)
    let cardH = Math.max(S * 0.20, H * 0.17)
    let cardX = align === 'right' ? W - cardW - W * 0.055 : align === 'center' ? (W - cardW) / 2 : W * 0.055
    let cardY = H - cardH - H * 0.065
    let r = Math.min(cardH * 0.18, S * 0.028)
    if (layoutMode === 'pill') {
      const stack = String(env.params.pillOrder ?? '') === 'stack'
      cardW = W * (stack ? 0.94 : 0.88)
      cardH = Math.max(S * (stack ? 0.148 : 0.13), H * (stack ? 0.148 : 0.12))
      cardX = (W - cardW) / 2
      cardY = H - cardH - H * (stack ? 0.019 : 0.06)
      r = cardH / 2
    } else if (layoutMode === 'palette') {
      cardW = W * (portrait ? 0.82 : 0.66)
      cardH = Math.max(S * 0.27, H * 0.25)
      cardX = align === 'right' ? W - cardW - W * 0.055 : align === 'center' ? (W - cardW) / 2 : W * 0.055
      cardY = H - cardH - H * 0.055
      r = Math.min(cardH * 0.13, S * 0.03)
    }
    const tmpW = 360
    const tmpH = Math.max(80, Math.round(tmpW * (H / W)))
    const tmp = document.createElement('canvas')
    tmp.width = tmpW
    tmp.height = tmpH
    const tctx = tmp.getContext('2d')!
    drawPhotoCover(
      { ...env, ctx: tctx, W: tmpW, H: tmpH } as TemplateRenderEnv,
      0,
      0,
      tmpW,
      tmpH,
      0,
      0
    )
    ctx.save()
    const grad = ctx.createLinearGradient(0, cardY, 0, cardY + cardH)
    grad.addColorStop(0, 'rgba(0,0,0,0.22)')
    grad.addColorStop(1, 'rgba(0,0,0,0.4)')
    ctx.fillStyle = grad
    roundRectPath(ctx, cardX, cardY, cardW, cardH, r)
    ctx.fill()
    roundRectPath(ctx, cardX, cardY, cardW, cardH, r)
    ctx.clip()
    ctx.filter = `blur(${Math.max(1, S * 0.012 * (pct(env.params.blur, 24) / 24))}px)`
    ctx.drawImage(
      tmp,
      (cardX / W) * tmpW,
      (cardY / H) * tmpH,
      (cardW / W) * tmpW,
      (cardH / H) * tmpH,
      cardX,
      cardY,
      cardW,
      cardH
    )
    ctx.filter = 'none'
    ctx.fillStyle = 'rgba(255,255,255,0.13)'
    ctx.fillRect(cardX, cardY, cardW, cardH)
    const sheen = ctx.createLinearGradient(cardX, cardY, cardX, cardY + cardH)
    sheen.addColorStop(0, 'rgba(255,255,255,0.22)')
    sheen.addColorStop(0.35, 'rgba(255,255,255,0.04)')
    sheen.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = sheen
    ctx.fillRect(cardX, cardY, cardW, cardH)
    ctx.restore()
    ctx.save()
    roundRectPath(ctx, cardX, cardY, cardW, cardH, r)
    ctx.strokeStyle = 'rgba(255,255,255,0.42)'
    ctx.lineWidth = Math.max(1, S * 0.0009)
    ctx.stroke()
    ctx.restore()
    line(ctx, cardX + r, cardY + 1, cardX + cardW - r, cardY + 1, 'rgba(255,255,255,0.5)', Math.max(1, S * 0.001))
    const innerX = cardX + cardW * (layoutMode === 'pill' ? 0.04 : 0.055)
    if (layoutMode === 'pill') {
      // pillOrder 'stack' (glass-liquid): ref pill is brand/date left, round mark
      // centred, exif/GPS right of a thin divider - not title|exif|date|logo right.
      if (String(env.params.pillOrder ?? '') === 'stack') {
        const padX = cardX + cardW * 0.055
        const topCY = cardY + cardH * 0.16
        const botCY = cardY + cardH * 0.47
        const exifX = cardX + cardW * 0.577
        drawFitted(ctx, titleOf(env), padX, topCY, cardW * 0.4, S * 0.03, '#ffffff', {
          family: FONT_UI,
          weight: 600,
          baseline: 'middle',
          min: S * 0.014,
          shadow: true
        })
        const dt = env.text.showDate ? formatDate(env.exif.dateTime, 'dot') : ''
        if (dt) {
          drawFitted(ctx, dt, padX, botCY, cardW * 0.4, S * 0.022, 'rgba(255,255,255,0.85)', {
            family: FONT_MONO,
            baseline: 'middle',
            min: S * 0.01,
            shadow: true
          })
        }
        if (env.text.showLogo) {
          const lw = cardW * 0.075
          const lh = cardH * 0.3
          drawBrandLogo(env, cardX + cardW * 0.488 - lw / 2, cardY + cardH * 0.3 - lh / 2, lw, lh, '#ffffff')
        }
        line(
          ctx,
          cardX + cardW * 0.545,
          cardY + cardH * 0.06,
          cardX + cardW * 0.545,
          cardY + cardH * 0.53,
          'rgba(255,255,255,0.55)',
          Math.max(1, S * 0.0012)
        )
        const ex = exifText(env)
        if (ex) {
          drawFitted(ctx, ex, exifX, topCY, cardW * 0.38, S * 0.026, 'rgba(255,255,255,0.9)', {
            family: FONT_MONO,
            baseline: 'middle',
            min: S * 0.01,
            shadow: true
          })
        }
        const loc = String(env.text.location ?? '').trim()
        if (loc) {
          drawFitted(ctx, loc, exifX, botCY, cardW * 0.38, S * 0.022, 'rgba(255,255,255,0.8)', {
            family: FONT_MONO,
            baseline: 'middle',
            min: S * 0.01,
            shadow: true
          })
        }
        return { x: 0, y: 0, w: W, h: H }
      }
      drawFitted(ctx, titleOf(env), innerX, cardY + cardH * 0.58, cardW * 0.30, S * 0.023, '#ffffff', {
        family: FONT_UI,
        weight: 600,
        min: S * 0.012,
        shadow: true
      })
      const ex = exifText(env)
      if (ex) {
        drawFitted(ctx, ex, cardX + cardW * 0.40, cardY + cardH * 0.58, cardW * 0.27, S * 0.014, 'rgba(255,255,255,0.8)', {
          family: FONT_MONO,
          min: S * 0.008,
          shadow: true
        })
      }
      const dt = dateText(env)
      if (dt) {
        drawFitted(ctx, dt, cardX + cardW * 0.70, cardY + cardH * 0.58, cardW * 0.16, S * 0.012, 'rgba(255,255,255,0.65)', {
          family: FONT_MONO,
          min: S * 0.007,
          shadow: true
        })
      }
      if (env.text.showLogo) {
        const lw = cardW * 0.075
        const lh = cardH * 0.3
        drawBrandLogo(env, cardX + cardW * 0.91 - lw / 2, cardY + cardH / 2 - lh / 2, lw, lh, '#ffffff')
      }
      line(ctx, cardX + cardW * 0.86, cardY + cardH * 0.38, cardX + cardW * 0.86, cardY + cardH * 0.62, theme.accent, Math.max(1, S * 0.0014))
    } else if (layoutMode === 'palette') {
      drawFitted(ctx, 'FRAMEMARK / PALETTE', innerX, cardY + cardH * 0.20, cardW * 0.55, S * 0.011, 'rgba(255,255,255,0.66)', {
        family: FONT_MONO,
        weight: 500,
        tracking: 0.12,
        min: S * 0.007,
        shadow: true
      })
      drawFitted(ctx, titleOf(env), innerX, cardY + cardH * 0.38, cardW * 0.62, S * 0.028, '#ffffff', {
        family: FONT_UI,
        weight: 600,
        min: S * 0.014,
        shadow: true
      })
      const ex = exifText(env)
      if (ex) {
        drawFitted(ctx, ex, innerX, cardY + cardH * 0.51, cardW * 0.62, S * 0.015, 'rgba(255,255,255,0.78)', {
          family: FONT_MONO,
          min: S * 0.008,
          shadow: true
        })
      }
      const dt = dateText(env)
      if (dt) {
        drawFitted(ctx, dt, cardX + cardW - cardW * 0.055, cardY + cardH * 0.51, cardW * 0.25, S * 0.012, 'rgba(255,255,255,0.64)', {
          align: 'right',
          family: FONT_MONO,
          min: S * 0.007,
          shadow: true
        })
      }
      const colors = extractPalette(env, 5)
      drawSwatches(env, colors, cardX + cardW * 0.05, cardY + cardH * 0.62, cardW * 0.9, S * 0.03, 'rgba(255,255,255,0.62)')
      if (env.text.showLogo) {
        const lw = cardW * 0.16
        const lh = cardH * 0.15
        drawBrandLogo(env, cardX + cardW - lw - cardW * 0.05, cardY + cardH * 0.12, lw, lh, '#ffffff')
      }
    } else {
      drawFitted(ctx, 'FRAMEMARK / LIQUID GLASS', innerX, cardY + cardH * 0.24, cardW * 0.55, S * 0.011, 'rgba(255,255,255,0.66)', {
        family: FONT_MONO,
        weight: 500,
        tracking: 0.12,
        min: S * 0.007,
        shadow: true
      })
      drawFitted(ctx, titleOf(env), innerX, cardY + cardH * 0.48, cardW * 0.62, S * 0.029, '#ffffff', {
        family: FONT_UI,
        weight: 600,
        tracking: 0.01,
        min: S * 0.014,
        shadow: true
      })
      const ex = exifText(env)
      if (ex) {
        drawFitted(ctx, ex, innerX, cardY + cardH * 0.64, cardW * 0.62, S * 0.016, 'rgba(255,255,255,0.78)', {
          family: FONT_MONO,
          min: S * 0.009,
          shadow: true
        })
      }
      const dt = dateText(env)
      if (dt) {
        drawFitted(ctx, dt, innerX, cardY + cardH * 0.79, cardW * 0.55, S * 0.013, 'rgba(255,255,255,0.64)', {
          family: FONT_MONO,
          min: S * 0.008,
          shadow: true
        })
      }
      if (env.text.showLogo) {
        const lw = cardW * 0.22 * logoScale(env)
        const lh = cardH * 0.26 * logoScale(env)
        drawBrandLogo(env, cardX + cardW - lw - cardW * 0.055, cardY + cardH * 0.42, lw, lh, '#ffffff')
      }
      line(ctx, cardX + cardW - cardW * 0.055 - S * 0.02, cardY + cardH * 0.82, cardX + cardW - cardW * 0.055, cardY + cardH * 0.82, theme.accent, Math.max(1, S * 0.0018))
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

// glass-palette reference (glass_palette.webp, 1500x2000): a 3:4 portrait canvas
// - not the audit's forced 4:3 - with a full-bleed photo and one glass panel at
// x 81..1419 (89.2%W), y 1276..1870 (29.7%H, 6.5%H bottom margin). The panel holds
// six 165px (11.0%W) discs on a 3-col x 2-row grid, centres x 334/749/1167 and
// y 1400/1668 (panel-relative 18.9/49.9/81.2%W, 20.9/66.0%H), each with a centred
// hex label 29.5px (1.48%H) below the disc. The portrait canvas and the 2x3
// spread live in this def only, so no other template's geometry can move; the
// panel has no brand/exif/date slot because the reference has none.
const glassPalettePortrait: TemplateDef = {
  id: 'glassPalettePortrait',
  name: '玻璃色卡',
  en: 'GLASS PALETTE',
  category: '液态玻璃',
  description: '3:4 竖幅满幅照片，底部玻璃面板内 3×2 大色卡与十六进制色值。',
  defaults: {
    theme: 'ink',
    blur: 24,
    panelWidthPct: 89.2,
    panelHeightPct: 29.7,
    panelBottomPct: 6.5,
    panelRadiusPct: 16,
    swatchCols: 3,
    swatchRows: 2,
    swatchPct: 12.3,
    gridFirstXPct: 18.9,
    gridPitchXPct: 31.1,
    gridFirstYPct: 20.9,
    gridPitchYPct: 45.1,
    labelPct: 2.2,
    tintAlpha: 0.3
  },
  controls: ['blur'],
  layout(env) {
    const H = Math.round(Math.max(env.pw, env.ph))
    return { W: Math.round(H * (3 / 4)), H }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const cols = Math.max(1, Math.round(pct(env.params.swatchCols, 3)))
    const rows = Math.max(1, Math.round(pct(env.params.swatchRows, 2)))
    const panelW = W * (pct(env.params.panelWidthPct, 89.2) / 100)
    const panelH = H * (pct(env.params.panelHeightPct, 29.7) / 100)
    const panelX = (W - panelW) / 2
    const panelY = H - H * (pct(env.params.panelBottomPct, 6.5) / 100) - panelH
    const radius = Math.min(panelH * (pct(env.params.panelRadiusPct, 16) / 100), W * 0.09)
    drawPhotoCover(env, 0, 0, W, H, 0, 0)
    const tmpW = 360
    const tmpH = Math.max(80, Math.round(tmpW * (H / W)))
    const tmp = document.createElement('canvas')
    tmp.width = tmpW
    tmp.height = tmpH
    const tctx = tmp.getContext('2d')!
    drawPhotoCover({ ...env, ctx: tctx, W: tmpW, H: tmpH } as TemplateRenderEnv, 0, 0, tmpW, tmpH, 0, 0)
    ctx.save()
    roundRectPath(ctx, panelX, panelY, panelW, panelH, radius)
    ctx.fillStyle = `rgba(0,0,0,${Math.min(0.6, Math.max(0, pct(env.params.tintAlpha, 0.3)))})`
    ctx.fill()
    ctx.clip()
    ctx.filter = `blur(${Math.max(1, S * 0.012 * (pct(env.params.blur, 24) / 24))}px)`
    ctx.drawImage(
      tmp,
      (panelX / W) * tmpW,
      (panelY / H) * tmpH,
      (panelW / W) * tmpW,
      (panelH / H) * tmpH,
      panelX,
      panelY,
      panelW,
      panelH
    )
    ctx.filter = 'none'
    ctx.fillStyle = 'rgba(255,255,255,0.10)'
    ctx.fillRect(panelX, panelY, panelW, panelH)
    const sheen = ctx.createLinearGradient(panelX, panelY, panelX, panelY + panelH)
    sheen.addColorStop(0, 'rgba(255,255,255,0.2)')
    sheen.addColorStop(0.35, 'rgba(255,255,255,0.04)')
    sheen.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = sheen
    ctx.fillRect(panelX, panelY, panelW, panelH)
    ctx.restore()
    ctx.save()
    roundRectPath(ctx, panelX, panelY, panelW, panelH, radius)
    ctx.strokeStyle = 'rgba(255,255,255,0.42)'
    ctx.lineWidth = Math.max(1, S * 0.001)
    ctx.stroke()
    ctx.restore()
    line(ctx, panelX + radius, panelY + 1, panelX + panelW - radius, panelY + 1, 'rgba(255,255,255,0.5)', Math.max(1, S * 0.001))
    const colors = extractPalette(env, cols * rows)
    const d = panelW * (pct(env.params.swatchPct, 12.3) / 100)
    const firstX = panelX + panelW * (pct(env.params.gridFirstXPct, 18.9) / 100)
    const pitchX = panelW * (pct(env.params.gridPitchXPct, 31.1) / 100)
    const firstY = panelY + panelH * (pct(env.params.gridFirstYPct, 20.9) / 100)
    const pitchY = panelH * (pct(env.params.gridPitchYPct, 45.1) / 100)
    const labelSize = H * (pct(env.params.labelPct, 2.2) / 100)
    const labelTop = d / 2 + H * 0.0148
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const hex = colors[r * cols + c] ?? '#000000'
        const cx = firstX + c * pitchX
        const cy = firstY + r * pitchY
        ctx.save()
        ctx.beginPath()
        ctx.arc(cx, cy, d / 2, 0, Math.PI * 2)
        ctx.fillStyle = hex
        ctx.fill()
        ctx.restore()
        ctx.save()
        ctx.fillStyle = 'rgba(255,255,255,0.94)'
        ctx.textAlign = 'center'
        ctx.textBaseline = 'top'
        setFont(ctx, labelSize, FONT_MONO, 500)
        ctx.fillText(hex.toUpperCase(), cx, cy + labelTop)
        ctx.restore()
      }
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

// glass-card / glass-pill-5 reference structure: the photo is a sharp inset
// card floating on a blurred full-bleed copy of itself, and the brand plus the
// F / ISO / S chip column sit on the opposite side of the canvas. The card
// rects come from the references (glass.webp 576x432 at (383,76) of 1024x584,
// glass5.webp 419x607 at (470,38) of 1024x684); every text metric is expressed
// relative to the card so the layout survives a different canvas aspect.
// Local to these two variants only - no other template uses this def.
const glassSideCard: TemplateDef = {
  id: 'glassSideCard',
  name: '玻璃侧卡',
  en: 'GLASS SIDE CARD',
  category: '液态玻璃',
  description: '虚化底图上的内嵌玻璃照片卡，另一侧为品牌与 F/ISO/S 参数。',
  defaults: {
    theme: 'ink',
    blur: 26,
    tintAlpha: 0.2,
    canvasAspect: 0,
    cardAspect: 4 / 3,
    cardSide: 'right',
    contentSide: 'left',
    cardWidthPct: 56.25,
    cardHeightPct: 0,
    cardInsetPct: 6.35,
    cardRadiusPct: 1.6,
    cardMattePct: 1.8,
    rowSet: 'fiso',
    textInsetPct: 30.5,
    brandInsetPct: 36.8,
    brandWidthPct: 28.7,
    brandHeightPct: 17.8,
    brandTopPct: 12.27,
    rowsTopPct: 43.75,
    rowBoxWPct: 19.2,
    rowBoxHPct: 9.95,
    rowPitchPct: 16.67,
    rowValueGapPct: 6.48,
    rowLabelPct: 4.4,
    rowValuePct: 5.8
  },
  themes: darkThemes,
  controls: ['blur'],
  // glass-pill-5 reference canvas is 1024x684 (1.497), wider than the 4:3
  // audit crop; variant-scoped canvasAspect keeps the portrait card exact.
  layout(env) {
    return aspectCanvas(env.pw, env.ph, pct(env.params.canvasAspect, 0))
  },
  render(env) {
    const { ctx, W, H } = env
    const theme = themeOf(env.params)
    drawBlurredBackdrop(env, W, H, theme, pct(env.params.tintAlpha, 0.2))
    const side = String(env.params.cardSide ?? 'right') === 'left' ? 'left' : 'right'
    const contentSide = String(env.params.contentSide ?? (side === 'right' ? 'left' : 'right'))
    const aspect = Math.max(0.2, pct(env.params.cardAspect, 4 / 3))
    const inset = (W * pct(env.params.cardInsetPct, 6.35)) / 100
    const availW = Math.max(1, W - 2 * inset)
    const availH = Math.max(1, H - 2 * inset)
    let cardW: number
    let cardH: number
    const heightPct = pct(env.params.cardHeightPct, 0)
    if (heightPct > 0) {
      // Height-driven cards (glass-pill-5): the reference portrait card fills
      // 88.7%H, which matters more than its width share on a narrower canvas.
      cardH = (H * heightPct) / 100
      cardW = cardH * aspect
      if (cardW > availW) {
        cardW = availW
        cardH = cardW / aspect
      }
    } else {
      cardW = Math.min(availW, (W * pct(env.params.cardWidthPct, 56.25)) / 100)
      cardH = cardW / aspect
      if (cardH > availH) {
        cardH = availH
        cardW = cardH * aspect
      }
    }
    const cardX = side === 'right' ? W - inset - cardW : inset
    const cardY = (H - cardH) / 2
    const S = Math.min(cardW, cardH)
    const radius = (S * pct(env.params.cardRadiusPct, 1.6)) / 100
    // Frosted matte: the reference card floats on a soft light rim.
    const matte = (S * pct(env.params.cardMattePct, 1.8)) / 100
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.32)'
    ctx.shadowBlur = S * 0.07
    ctx.shadowOffsetY = S * 0.015
    ctx.fillStyle = 'rgba(255,255,255,0.09)'
    roundRectPath(ctx, cardX - matte, cardY - matte, cardW + 2 * matte, cardH + 2 * matte, radius + matte)
    ctx.fill()
    ctx.restore()
    drawPhotoCover(env, cardX, cardY, cardW, cardH, radius, 1)
    ctx.save()
    roundRectPath(ctx, cardX, cardY, cardW, cardH, radius)
    ctx.strokeStyle = 'rgba(255,255,255,0.34)'
    ctx.lineWidth = Math.max(1, S * 0.0022)
    ctx.stroke()
    ctx.restore()

    // Text column: horizontal anchors are panel-relative (the panel is the
    // canvas strip beside the card), vertical anchors are card-relative.
    const panelLeft = side === 'right' ? 0 : cardX + cardW
    const panelW = side === 'right' ? cardX : W - (cardX + cardW)
    const fromStart = contentSide === 'left'
    const anchorX = (insetPct: number, width: number) =>
      fromStart ? panelLeft + (panelW * insetPct) / 100 : panelLeft + panelW - (panelW * insetPct) / 100 - width
    const aperture = formatAperture(env.exif.aperture).replace(/^F/, '')
    const shutter = formatShutter(env.exif.shutter).replace(/s$/, '')
    const iso = env.exif.iso != null && Number.isFinite(Number(env.exif.iso)) ? String(Math.round(Number(env.exif.iso))) : ''
    const focal = env.exif.focal ? String(Math.round(env.exif.focal)) : ''
    const rows: [string, string][] =
      String(env.params.rowSet ?? 'fiso') === 'fmm'
        ? [
            ['F', aperture],
            ['MM', focal],
            ['S', shutter]
          ]
        : [
            ['F', aperture],
            ['ISO', iso],
            ['S', shutter]
          ]
    const boxW = (cardH * pct(env.params.rowBoxWPct, 19.2)) / 100
    const boxH = (cardH * pct(env.params.rowBoxHPct, 9.95)) / 100
    const pitch = (cardH * pct(env.params.rowPitchPct, 16.67)) / 100
    const valueGap = (cardH * pct(env.params.rowValueGapPct, 6.48)) / 100
    const labelSize = (cardH * pct(env.params.rowLabelPct, 4.4)) / 100
    const valueSize = (cardH * pct(env.params.rowValuePct, 5.8)) / 100
    ctx.save()
    setFont(ctx, valueSize, FONT_MONO, 400)
    let valueW = 0
    for (const [, value] of rows) valueW = Math.max(valueW, ctx.measureText(value).width)
    ctx.restore()
    const blockW = boxW + valueGap + valueW
    const blockX = anchorX(pct(env.params.textInsetPct, 30.5), blockW)
    const brandW = (panelW * pct(env.params.brandWidthPct, 28.7)) / 100
    const brandH = (cardH * pct(env.params.brandHeightPct, 17.8)) / 100
    const brandX = anchorX(pct(env.params.brandInsetPct, 36.8), brandW)
    const brandY = cardY + (cardH * pct(env.params.brandTopPct, 12.27)) / 100
    if (env.text.showLogo) drawBrandLogo(env, brandX, brandY, brandW, brandH, '#ffffff')
    const rowsTop = cardY + (cardH * pct(env.params.rowsTopPct, 43.75)) / 100
    for (let i = 0; i < rows.length; i++) {
      const [label, value] = rows[i]
      const y = rowsTop + i * pitch
      ctx.save()
      ctx.strokeStyle = 'rgba(255,255,255,0.62)'
      ctx.lineWidth = Math.max(1, S * 0.0028)
      roundRectPath(ctx, blockX, y, boxW, boxH, boxH * 0.2)
      ctx.stroke()
      ctx.restore()
      drawFitted(ctx, label, blockX + boxW / 2, y + boxH / 2, boxW * 0.72, labelSize, 'rgba(255,255,255,0.92)', {
        family: FONT_MONO,
        align: 'center',
        baseline: 'middle',
        min: labelSize * 0.5,
        shadow: true
      })
      if (value) {
        drawFitted(ctx, value, blockX + boxW + valueGap, y + boxH / 2, valueW * 1.02, valueSize, '#ffffff', {
          family: FONT_MONO,
          baseline: 'middle',
          min: valueSize * 0.45,
          shadow: true
        })
      }
    }
    return { x: cardX, y: cardY, w: cardW, h: cardH }
  }
}

const ticket: TemplateDef = {
  id: 'ticket',
  name: '票据 · 旅行',
  en: 'TICKET',
  category: '票据',
  description: '圆角票卡、撕线与条码存根，把照片做成一张纪念票。',
  defaults: { marginPct: 6, radiusPct: 2, shadow: 5, footerPct: 16, theme: 'ink', logoScale: 5 },
  themes: darkThemes,
  controls: ['margin', 'footer'], // A4 摘除假控件：见 _research/editing-model-hand/hand-controls-proposal.json.fakeControlInventory
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const outer = (S * pct(env.params.marginPct, 6)) / 100
    const header = Math.max(S * 0.10, S * 0.08)
    const stub = Math.max((S * pct(env.params.footerPct, 16)) / 100, S * 0.16)
    return { W: env.pw + 2 * outer, H: env.ph + header + stub + 2 * outer }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const theme = themeOf(env.params)
    const S = Math.min(pw, ph)
    const outer = marginOf(env)
    const header = Math.max(S * 0.10, S * 0.08)
    const stub = H - ph - header - 2 * outer
    const cardX = outer
    const cardY = outer
    const cardW = pw
    const cardH = header + ph + stub
    const radius = radiusOf(env)
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.44)'
    ctx.shadowBlur = S * 0.03
    ctx.shadowOffsetY = S * 0.012
    fillRoundRect(ctx, cardX, cardY, cardW, cardH, radius, '#f7f0e1')
    ctx.restore()
    drawPhoto(env, cardX, cardY + header, pw, ph, 0, 0)
    const ink = '#221d16'
    const sub = 'rgba(34,29,22,0.6)'
    drawFitted(ctx, 'FRAMEMARK / ADMIT ONE', cardX + S * 0.035, cardY + header * 0.62, cardW * 0.62, S * 0.014, ink, {
      family: FONT_MONO,
      weight: 600,
      tracking: 0.12,
      min: S * 0.008
    })
    const booking = String(Math.abs((env.exif.dateTime || env.exif.model || 'TICKET').split('').reduce((a, c) => a * 31 + c.charCodeAt(0), 11)) % 99999).padStart(5, '0')
    drawFitted(ctx, `NO. ${booking}`, cardX + cardW - S * 0.035, cardY + header * 0.62, cardW * 0.26, S * 0.013, sub, {
      align: 'right',
      family: FONT_MONO,
      min: S * 0.008
    })
    line(ctx, cardX + S * 0.035, cardY + header * 0.84, cardX + cardW - S * 0.035, cardY + header * 0.84, 'rgba(34,29,22,0.18)', Math.max(1, S * 0.0008))
    const perfY = cardY + header + ph
    ctx.save()
    ctx.strokeStyle = 'rgba(34,29,22,0.35)'
    ctx.lineWidth = Math.max(1, S * 0.001)
    ctx.setLineDash([S * 0.012, S * 0.01])
    ctx.beginPath()
    ctx.moveTo(cardX + S * 0.02, perfY)
    ctx.lineTo(cardX + cardW - S * 0.02, perfY)
    ctx.stroke()
    ctx.restore()
    ctx.save()
    ctx.fillStyle = theme.bg
    for (const nx of [cardX, cardX + cardW]) {
      ctx.beginPath()
      ctx.arc(nx, perfY, S * 0.018, 0, Math.PI * 2)
      ctx.fill()
    }
    ctx.restore()
    const stubY = perfY
    const title = titleOf(env)
    drawFitted(ctx, title, cardX + S * 0.035, stubY + stub * 0.38, cardW * 0.48, S * 0.028, ink, {
      family: FONT_UI,
      weight: 600,
      min: S * 0.014
    })
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, cardX + S * 0.035, stubY + stub * 0.62, cardW * 0.46, S * 0.015, sub, {
        family: FONT_MONO,
        min: S * 0.008
      })
    }
    const ex = exifText(env)
    if (ex) {
      drawFitted(ctx, ex, cardX + S * 0.035, stubY + stub * 0.79, cardW * 0.5, S * 0.012, sub, {
        family: FONT_MONO,
        min: S * 0.007
      })
    }
    const bcW = cardW * 0.26
    const bcH = stub * 0.26
    drawBarcode(ctx, cardX + cardW - bcW - S * 0.035, stubY + stub * 0.28, bcW, bcH, ink, booking)
    drawFitted(ctx, `NO. ${booking}`, cardX + cardW - bcW - S * 0.035, stubY + stub * 0.28 + bcH + S * 0.016, bcW, S * 0.009, sub, {
      family: FONT_MONO,
      tracking: 0.08,
      min: S * 0.006
    })
    if (env.text.showLogo) {
      drawBrandLogo(env, cardX + cardW * 0.58, cardY + header * 0.22, cardW * 0.16, header * 0.42, ink)
    }
    drawGrain(env, W, H, 0.05)
    return { x: cardX, y: cardY + header, w: pw, h: ph }
  }
}

const portraitTicket: TemplateDef = {
  id: 'portraitTicket',
  name: '票据 · 竖卡',
  en: 'PORTRAIT TICKET',
  category: '票据',
  description: '浅色背景中的竖票卡：照片在上，白存根在下。',
  defaults: {
    theme: 'stone',
    ticketBg: '#c7dfe6',
    ticketCard: '#ffffff',
    ticketInk: '#2d6f7c',
    stubTitle: 'QING DAO',
    stubLabel: 'GUANGYING 100'
  },
  themes: lightThemes,
  controls: [],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    return { W: S * 1.5, H: S * 2 }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(env.pw, env.ph)
    const bg = String(env.params.ticketBg ?? '#c7dfe6')
    const cardBg = String(env.params.ticketCard ?? '#ffffff')
    const ink = String(env.params.ticketInk ?? '#2d6f7c')
    const sub = 'rgba(45,111,124,0.62)'
    // Reference 1144x1552 card on 1500x2000 (76.27 %W / 77.6 %H); the photo is
    // 1059 px = 68.23 % cardH and the stub 493 px = 31.77 % cardH. 0.78/0.78
    // + photo 0.78 left a 216 px-wide aspect gap (1.081 vs 0.962).
    const cardW = W * 0.7627
    const cardH = H * 0.776
    const cardX = (W - cardW) / 2
    const cardY = (H - cardH) / 2
    const photoH = cardH * 0.6823
    const stubH = cardH - photoH
    const seam = cardY + photoH
    const radius = S * 0.025
    ctx.fillStyle = bg
    ctx.fillRect(0, 0, W, H)
    ctx.save()
    ctx.shadowColor = 'rgba(0,0,0,0.15)'
    ctx.shadowBlur = S * 0.035
    ctx.shadowOffsetY = S * 0.012
    fillRoundRect(ctx, cardX, cardY, cardW, cardH, radius, cardBg)
    ctx.restore()
    ctx.save()
    roundRectPath(ctx, cardX, cardY, cardW, cardH, radius)
    ctx.clip()
    drawPhotoCover(env, cardX, cardY, cardW, photoH, 0, 0)
    ctx.fillStyle = cardBg
    ctx.fillRect(cardX, seam, cardW, stubH)
    ctx.restore()

    const notch = S * 0.016
    for (const x of [cardX, cardX + cardW]) {
      ctx.save()
      ctx.fillStyle = bg
      ctx.beginPath()
      ctx.arc(x, seam, notch, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()
    }
    ctx.save()
    ctx.strokeStyle = 'rgba(45,111,124,0.35)'
    ctx.lineWidth = Math.max(1, S * 0.001)
    ctx.setLineDash([S * 0.012, S * 0.01])
    ctx.beginPath()
    ctx.moveTo(cardX + notch * 1.4, seam)
    ctx.lineTo(cardX + cardW - notch * 1.4, seam)
    ctx.stroke()
    ctx.restore()

    // Stub anchors are fractions of the (now 31.77 %) stub, taken from the
    // reference ink bboxes: title centre 23.7 %, date 42.2 %, label 62.4 %,
    // barcode top 69.2 % (h 14.6 %), serial 79.4 %.
    const left = cardX + cardW * 0.0908
    const title = String(env.params.stubTitle ?? 'QING DAO').toUpperCase()
    // 0.0635 cardW rendered the title ink 284 px wide on a 1200 px canvas
    // (fallback glyphs) vs the ref 25.6 %W; ticket-2 overrides stubTitleSize
    // to 0.066 (322 px loaded, ~294 px fallback, both inside 1.5pp).
    const titleSize = cardW * (Number(env.params.stubTitleSize) || 0.0635)
    drawFitted(ctx, title, left, seam + stubH * 0.2373, cardW * 0.58, titleSize, ink, {
      family: FONT_UI,
      weight: 800,
      baseline: 'middle',
      min: cardW * 0.045
    })
    const dt = dateText(env)
    const stubDate = /^\d{4}-\d{2}-\d{2}$/.test(dt) ? dt.slice(0, 7) : dt
    if (stubDate) {
      drawFitted(ctx, stubDate, left, seam + stubH * 0.4219, cardW * 0.30, cardW * 0.0374, ink, {
        family: FONT_MONO,
        weight: 500,
        baseline: 'middle',
        min: cardW * 0.024
      })
    }
    const no = `NO.${String(Math.abs((env.exif.dateTime || env.exif.model || 'TICKET').split('').reduce((a, c) => a * 31 + c.charCodeAt(0), 7)) % 99999).padStart(5, '0')}`
    drawFitted(ctx, no, left, seam + stubH * 0.7941, cardW * 0.4, cardW * 0.0297, sub, {
      family: FONT_MONO,
      baseline: 'middle',
      min: cardW * 0.018
    })
    const rightX = cardX + cardW * 0.847
    drawFitted(ctx, String(env.params.stubLabel ?? 'GUANGYING 100'), rightX, seam + stubH * 0.6237, cardW * 0.33, cardW * 0.0295, ink, {
      family: FONT_MONO,
      align: 'right',
      weight: 500,
      tracking: 0.06,
      baseline: 'middle',
      min: cardW * 0.018
    })
    drawBarcode(ctx, cardX + cardW * 0.6114, seam + stubH * 0.6917, cardW * 0.3019, stubH * 0.1461, ink, env.exif.model || 'FRAMEMARK')
    return { x: cardX, y: cardY, w: cardW, h: photoH }
  }
}

const locationCard: TemplateDef = {
  id: 'locationCard',
  name: '坐标卡',
  en: 'LOCATION CARD',
  category: '色卡',
  description: '顶部地点/时间色块，下方满幅照片。',
  defaults: {
    theme: 'paper',
    locationBg: '#405c66',
    locationFg: '#f5f2ea',
    locationTitle: '中国',
    locationTime: '08:00 AM',
    headerPct: 45
  },
  themes: lightThemes,
  controls: [],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    return { W: S * 1.5, H: S * 2 }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(env.pw, env.ph)
    const fg = String(env.params.locationFg ?? '#f5f2ea')
    const bg = String(env.params.locationBg ?? '#405c66')
    const header = (H * pct(env.params.headerPct, 45)) / 100
    ctx.fillStyle = bg
    ctx.fillRect(0, 0, W, header)
    drawFitted(ctx, String(env.params.locationTitle ?? '中国'), W / 2, header * 0.48, W * 0.5, S * 0.042, fg, {
      family: FONT_CN,
      weight: 500,
      align: 'center',
      baseline: 'middle',
      tracking: 0.12,
      min: S * 0.024
    })
    // The colour-walk reference puts the time centre at 53.6% of the header
    // (ink top 52.6%); 0.66 was 10.9 %S too low. 0.535 keeps the title at 0.48.
    drawFitted(ctx, String(env.params.locationTime ?? '08:00 AM'), W / 2, header * 0.535, W * 0.4, S * 0.018, fg, {
      family: FONT_MONO,
      weight: 400,
      align: 'center',
      baseline: 'middle',
      tracking: 0.18,
      min: S * 0.01
    })
    drawPhotoCover(env, 0, header, W, H - header, 0, 0)
    return { x: 0, y: header, w: W, h: H - header }
  }
}

const paletteCard: TemplateDef = {
  id: 'paletteCard',
  name: '色卡大样',
  en: 'PALETTE CARD',
  category: '色卡',
  description: '圆角照片 + 下方大色板与十六进制标注。',
  defaults: { theme: 'white', marginPct: 2, radiusPct: 2, shadow: 0.4, palettePct: 22 },
  themes: lightThemes,
  controls: ['margin', 'radius', 'shadow'],
  layout(env) {
    const S = Math.min(env.pw, env.ph)
    const m = (S * pct(env.params.marginPct, 2)) / 100
    const palette = (S * pct(env.params.palettePct, 22)) / 100
    return { W: env.pw + 2 * m, H: env.ph + 2 * m + palette }
  },
  render(env) {
    const { ctx, W, H, pw, ph } = env
    const S = Math.min(pw, ph)
    const theme = themeOf(env.params)
    const m = (W - pw) / 2
    const palette = H - ph - 2 * m
    const radius = (S * pct(env.params.radiusPct, 2)) / 100
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    drawPhoto(env, m, m, pw, ph, radius, pct(env.params.shadow, 0.4))
    const size = S * 0.082
    const colors = extractPalette(env, 5)
    // paletteGapPct: anchor the swatch block just below the photo (0 = flush).
    // paletteRowPct: spread the swatches across S*rowPct instead of the
    // packed, centred group; both are opt-in and leave other users unchanged.
    const gapPct = Number(env.params.paletteGapPct)
    const rowPct = Number(env.params.paletteRowPct)
    const block = size * 1.52
    const y = Number.isFinite(gapPct)
      ? m + ph + (S * gapPct) / 100
      : m + ph + m + Math.max(0, (palette - block) / 2)
    const spread = Number.isFinite(rowPct) && rowPct > 0
    const rowW = spread ? Math.min(W, (S * rowPct) / 100) : W - 2 * m
    const rowX = spread ? (W - rowW) / 2 : m
    drawSwatches(env, colors, rowX, y, rowW, size, theme.sub, spread ? { spread } : undefined)
    return { x: m, y: m, w: pw, h: ph }
  }
}

const polaroidFrame: TemplateDef = {
  id: 'polaroidFrame',
  name: 'Polaroid 整幅相框',
  en: 'POLAROID FRAME',
  category: '系统相机',
  description: '按画幅选择 Polaroid frame，照片填入透明窗口，底部写机型/参数。',
  defaults: {},
  themes: lightThemes,
  controls: [],
  async prepare(env) {
    const meta = choosePolaroidFrame(env.pw, env.ph)
    const url = POLAROID_URLS[`../assets/system/oppo/camerapro/polaroid/${meta.file}`]
    const images: Record<string, HTMLImageElement> = {}
    if (url) images.frame = await loadImage(url)
    return { images }
  },
  layout(env) {
    const meta = choosePolaroidFrame(env.pw, env.ph)
    const scale = env.crop.sw / meta.win[2]
    return { W: meta.w * scale, H: meta.h * scale }
  },
  render(env) {
    const { ctx, W, H } = env
    const meta = choosePolaroidFrame(env.pw, env.ph)
    const frame = env.images?.frame
    if (!frame) return { x: 0, y: 0, w: W, h: H }
    const k = W / meta.w
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, W, H)
    ctx.drawImage(frame, 0, 0, W, H)
    const [wx, wy, ww, wh] = meta.win
    drawPhotoCover(env, wx * k, wy * k, ww * k, wh * k, 0, 0)
    const bottom = (meta.h - wy - wh) * k
    const S = Math.min(W, H)
    const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
    const size = S * 0.022
    drawFitted(ctx, model, wx * k, wy * k + wh * k + bottom * 0.42, ww * k * 0.45, size, '#222222', {
      family: FONT_UI,
      weight: 600,
      baseline: 'middle'
    })
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, wx * k, wy * k + wh * k + bottom * 0.68, ww * k * 0.4, size * 0.8, '#77736c', {
        family: FONT_MONO,
        baseline: 'middle'
      })
    }
    const ex = classicExifLine(env)
    if (ex) {
      drawFitted(ctx, ex, (wx + ww) * k, wy * k + wh * k + bottom * 0.52, ww * k * 0.48, size * 0.8, '#77736c', {
        family: FONT_MONO,
        align: 'right',
        baseline: 'middle'
      })
    }
    return { x: wx * k, y: wy * k, w: ww * k, h: wh * k }
  }
}

const xiaomiStandard: TemplateDef = {
  id: 'xiaomiStandard',
  name: '小米标准水印',
  en: 'XIAOMI STANDARD',
  category: '系统相机',
  description: '照片内标准水印：左下品牌/机型，右侧日期，可选自定义行。',
  defaults: {
    theme: 'ink',
    standardPosition: 'bottom',
    standardLayout: 'row',
    showStandardExif: false,
    insetPct: 4.44,
    offsetPct: 3.43,
    shadow: 0.6
  },
  themes: [],
  controls: [],
  layout(env) {
    return { W: env.pw, H: env.ph }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    drawPhoto(env, 0, 0, W, H, 0, 0)
    const inset = (S * pct(env.params.insetPct, 4.44)) / 100
    const offset = (S * pct(env.params.offsetPct, 3.43)) / 100
    const pos = String(env.params.standardPosition ?? 'bottom')
    const custom = env.text.customLine.trim()
    const rowH = S * 0.05
    if (env.params.standardLayout === 'stack') {
      const logoH = S * 0.03
      const logoW = S * 0.13
      const modelSize = S * 0.025
      const lineSize = S * 0.019
      const lineGap = S * 0.022
      const exif = classicExifLine(env)
      const location = env.text.location?.trim() ?? ''
      const dt = dateText(env)
      const lines = [
        ...(custom ? [custom] : []),
        ...(env.params.showStandardExif && exif ? [exif] : []),
        ...(env.params.showStandardExif && location ? [location] : []),
        ...(dt ? [dt] : [])
      ]
      const rowHeight = logoH
      const blockH = rowHeight + lines.length * (lineSize + lineGap)
      let y = H - offset - blockH
      const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
      ctx.save()
      ctx.font = `600 ${Math.max(1, modelSize)}px ${FONT_UI}`
      const modelW = ctx.measureText(model).width
      ctx.restore()
      const showLogo = env.text.showLogo && env.images?.logo
      const groupW = (showLogo ? logoW + S * 0.018 : 0) + modelW
      let x = W / 2 - groupW / 2
      if (showLogo) {
        drawBrandLogo(env, x, y + rowHeight / 2 - logoH / 2, logoW, logoH, '#ffffff')
        x += logoW + S * 0.018
      }
      drawFitted(ctx, model, x, y + rowHeight / 2, W * 0.4, modelSize, '#ffffff', {
        family: FONT_UI,
        weight: 600,
        baseline: 'middle',
        shadow: true
      })
      y += rowHeight + lineGap
      for (const line of lines) {
        drawFitted(ctx, line, W / 2, y, W * 0.7, lineSize, 'rgba(255,255,255,0.92)', {
          family: FONT_UI,
          align: 'center',
          baseline: 'middle',
          shadow: true
        })
        y += lineSize + lineGap
      }
      return { x: 0, y: 0, w: W, h: H }
    }
    const bottomCy = custom ? H - offset - S * 0.035 : H - offset - rowH / 2
    const cy = pos === 'top' ? offset + rowH / 2 : pos === 'center' ? H / 2 : bottomCy
    let x = inset
    const logoH = S * 0.03
    const model = modelWithoutMake(env.exif.make, env.exif.model) || cameraLine(env.exif, '')
    if (env.text.showLogo && env.images?.logo) {
      const logoW = S * 0.13
      drawBrandLogo(env, x, cy - logoH / 2, logoW, logoH, '#ffffff')
      x += logoW + S * 0.018
    }
    drawFitted(ctx, model, x, cy, W * 0.4, S * 0.025, '#ffffff', {
      family: FONT_UI,
      weight: 600,
      baseline: 'middle',
      shadow: true
    })
    if (custom) {
      drawFitted(ctx, custom, inset, H - offset, W * 0.45, S * 0.021, 'rgba(255,255,255,0.9)', {
        family: FONT_UI,
        baseline: 'middle',
        shadow: true
      })
    }
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, W - inset, cy, W * 0.35, S * 0.022, 'rgba(255,255,255,0.9)', {
        family: FONT_MONO,
        align: 'right',
        baseline: 'middle',
        shadow: true
      })
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

const movie: TemplateDef = {
  id: 'movie',
  name: '电影 · 宽银幕',
  en: 'CINEMA',
  category: '电影',
  description: '上下黑边与片头字幕，带电影画幅的仪式感。',
  // photoAspect > 0 switches to the 2.34:1 cinema crop: the photo is cover-cropped
  // to that aspect inside the frame, barPct then means the bar height as a % of the
  // canvas height (movie-1 11.7 / movie-2 7.9). subtitleMode 'in-photo' burns the
  // two caption lines into the still (movie-2); 'bar' keeps them in the bottom bar.
  defaults: {
    marginPct: 0,
    radiusPct: 0,
    shadow: 0,
    footerPct: 0,
    theme: 'black',
    logoScale: 5,
    barPct: 6.5,
    logoPos: 'right',
    photoAspect: 0,
    subtitleMode: 'bar'
  },
  themes: darkThemes,
  controls: [],
  layout(env) {
    const aspect = pct(env.params.photoAspect, 0)
    if (aspect > 0) {
      const photoH = env.pw / aspect
      const bp = Math.max(0, Math.min(30, pct(env.params.barPct, 11.7)))
      const bar = (photoH * bp) / (100 - 2 * bp)
      return { W: env.pw, H: photoH + 2 * bar }
    }
    const S = Math.min(env.pw, env.ph)
    const bar = Math.max((S * pct(env.params.barPct, 8)) / 100, S * 0.05)
    return { W: env.pw, H: env.ph + 2 * bar }
  },
  render(env) {
    const { ctx, W, H } = env
    const theme = themeOf(env.params)
    const aspect = pct(env.params.photoAspect, 0)
    const wide = aspect > 0
    const photoW = wide ? W : env.pw
    const photoH = wide ? W / aspect : env.ph
    const S = Math.min(photoW, photoH)
    const bar = (H - photoH) / 2
    const bottomTop = bar + photoH
    const inPhoto = wide && String(env.params.subtitleMode ?? 'bar') === 'in-photo'
    ctx.fillStyle = theme.bg
    ctx.fillRect(0, 0, W, H)
    if (wide) drawPhotoCover(env, 0, bar, photoW, photoH, 0, 0)
    else drawPhoto(env, 0, bar, photoW, photoH, 0, 0)
    const title = titleOf(env)
    const sub = subLine(env)
    const hasSub = Boolean(sub)
    const sizeTitle = wide ? S * 0.042 : S * 0.036
    const sizeSub = wide ? S * 0.036 : S * 0.023
    if (inPhoto) {
      // subtitle over the still, lifted off the photo bottom edge
      drawFitted(ctx, title, W / 2, bottomTop - S * (hasSub ? 0.122 : 0.06), W * 0.82, sizeTitle, theme.fg, {
        align: 'center',
        family: FONT_CN,
        weight: 400,
        baseline: 'middle',
        shadow: true,
        min: S * 0.018
      })
      if (hasSub) {
        drawFitted(ctx, sub, W / 2, bottomTop - S * 0.066, W * 0.82, sizeSub, theme.fg, {
          align: 'center',
          family: FONT_UI,
          weight: 400,
          baseline: 'middle',
          shadow: true,
          min: S * 0.012
        })
      }
    } else if (wide) {
      drawFitted(ctx, title, W / 2, bottomTop + bar * (hasSub ? 0.265 : 0.5), W * 0.82, sizeTitle, theme.fg, {
        align: 'center',
        family: FONT_CN,
        weight: 400,
        baseline: 'middle',
        min: S * 0.018
      })
      if (hasSub) {
        drawFitted(ctx, sub, W / 2, bottomTop + bar * 0.634, W * 0.82, sizeSub, theme.fg, {
          align: 'center',
          family: FONT_UI,
          weight: 400,
          baseline: 'middle',
          min: S * 0.012
        })
      }
    } else {
      // legacy movie (photoAspect 0): unchanged placement and colours
      drawFitted(ctx, title, W / 2, bottomTop + bar * (hasSub ? 0.38 : 0.62), W * 0.82, S * 0.036, theme.fg, {
        align: 'center',
        family: FONT_CN,
        weight: 400,
        min: S * 0.018
      })
      if (hasSub) {
        drawFitted(ctx, sub, W / 2, bottomTop + bar * 0.76, W * 0.82, S * 0.023, theme.sub, {
          align: 'center',
          family: FONT_UI,
          weight: 400,
          min: S * 0.012
        })
      }
    }
    return { x: 0, y: bar, w: photoW, h: photoH }
  }
}

const ratioBlock: TemplateDef = {
  id: 'ratio',
  name: '比例 · 角标',
  en: 'RATIO BLOCK',
  category: '比例',
  description: '满幅照片，四角参数块，适合社交平台直出。',
  defaults: { marginPct: 0, radiusPct: 0, shadow: 0, footerPct: 0, theme: 'ink', logoScale: 5, corner: 3 },
  themes: darkThemes,
  controls: [],
  layout(env) {
    return { W: env.pw, H: env.ph }
  },
  render(env) {
    const { ctx, W, H } = env
    const S = Math.min(W, H)
    const theme = themeOf(env.params)
    drawPhoto(env, 0, 0, W, H, 0, 0)
    const corner = Math.max(0, Math.min(3, Math.round(pct(env.params.corner, 3))))
    const left = corner % 2 === 0
    const top = corner < 2
    const x = left ? W * 0.06 : W * 0.94
    let y = top ? H * 0.11 : H * 0.70
    const align: CanvasTextAlign = left ? 'left' : 'right'
    line(ctx, x - (left ? 0 : S * 0.06), y - S * 0.035, x + (left ? S * 0.06 : 0), y - S * 0.035, theme.accent, Math.max(1, S * 0.0014))
    const cam = env.text.showMake || env.text.showModel ? cameraLine(env.exif, '') : ''
    if (cam) {
      y += drawFitted(ctx, cam.toUpperCase(), x, y, W * 0.45, S * 0.017, '#ffffff', {
        align,
        family: FONT_UI,
        weight: 600,
        tracking: 0.14,
        min: S * 0.009,
        shadow: true
      })
      y += S * 0.022
    }
    const ex = exifText(env)
    if (ex) {
      y += drawFitted(ctx, ex, x, y, W * 0.5, S * 0.014, 'rgba(255,255,255,0.78)', {
        align,
        family: FONT_MONO,
        min: S * 0.008,
        shadow: true
      })
      y += S * 0.018
    }
    const dt = dateText(env)
    if (dt) {
      drawFitted(ctx, dt, x, top ? y : H * 0.88, W * 0.36, S * 0.012, 'rgba(255,255,255,0.62)', {
        align,
        family: FONT_MONO,
        min: S * 0.007,
        shadow: true
      })
    }
    if (env.text.showLogo) {
      const lw = S * 0.16
      const lh = S * 0.04
      const lx = left ? W - W * 0.06 - lw : W * 0.06
      const ly = top ? H * 0.085 : H * 0.88
      drawBrandLogo(env, lx, ly, lw, lh, '#ffffff')
    }
    return { x: 0, y: 0, w: W, h: H }
  }
}

// ratio-1 / ratio-2 reference frames are a 3:2 canvas (1024x682) holding a matte
// inset photo at the reference aspect with a centred caption underneath.
// Canvas H follows the reference aspect instead of the input crop; the photo
// window is a centre-crop of the input, so no engine/crop change is needed.
const ratioMatte: TemplateDef = {
  id: 'ratioMatte',
  name: '比例 · 衬边',
  en: 'RATIO MATTE',
  category: '比例',
  description: '模糊底衬边，内嵌照片，品牌与参数居中。',
  defaults: {
    theme: 'ink',
    canvasAspect: 1.5015,
    photoAspect: 1.485,
    sideMarginPct: 9.03,
    topMarginPct: 5.72,
    radiusPct: 0,
    blur: 24,
    tintAlpha: 0.9,
    vignette: 0.28,
    brandSizePct: 3.43,
    brandGapPct: 4.62,
    brandTracking: 0.14,
    metaSizePct: 1.8,
    metaGapPct: 8.5,
    metaAlphaPct: 80
  },
  themes: lightThemes,
  controls: [],
  layout(env) {
    const aspect = Math.max(0.5, pct(env.params.canvasAspect, 1.5015))
    return { W: env.pw, H: Math.max(2, Math.round(env.pw / aspect)) }
  },
  render(env) {
    const { ctx, W, H } = env
    const theme = themeOf(env.params)
    drawBlurredBackdrop(env, W, H, theme, Math.max(0, Math.min(1, pct(env.params.tintAlpha, 0.9))))
    const vig = Math.max(0, Math.min(1, pct(env.params.vignette, 0)))
    if (vig > 0) {
      const g = ctx.createRadialGradient(W / 2, H * 0.42, Math.min(W, H) * 0.12, W / 2, H / 2, Math.max(W, H) * 0.68)
      g.addColorStop(0, 'rgba(0,0,0,0)')
      g.addColorStop(1, `rgba(0,0,0,${vig})`)
      ctx.save()
      ctx.fillStyle = g
      ctx.fillRect(0, 0, W, H)
      ctx.restore()
    }
    const photoY = H * (pct(env.params.topMarginPct, 5.72) / 100)
    let photoW = Math.max(1, W - 2 * W * (pct(env.params.sideMarginPct, 9.03) / 100))
    let photoH = photoW / Math.max(0.2, pct(env.params.photoAspect, 1.485))
    const maxBottom = H * 0.96
    if (photoY + photoH > maxBottom) {
      photoH = Math.max(1, maxBottom - photoY)
      photoW = photoH * Math.max(0.2, pct(env.params.photoAspect, 1.485))
    }
    const photoX = (W - photoW) / 2
    const radius = Math.min(photoW, photoH) * (pct(env.params.radiusPct, 0) / 100)
    drawPhotoCover(env, photoX, photoY, photoW, photoH, radius)
    const photoBottom = photoY + photoH
    const cam = [
      env.text.showMake ? env.exif.make : '',
      env.text.showModel ? modelWithoutMake(env.exif.make, env.exif.model) : ''
    ]
      .filter(Boolean)
      .join('  ')
    const ex = exifText(env)
    const dt = dateText(env)
    const meta = ex || dt
    const maxW = photoW * 0.92
    if (cam) {
      drawFitted(ctx, cam.toUpperCase(), W / 2, photoBottom + H * (pct(env.params.brandGapPct, 4.62) / 100), maxW, H * (pct(env.params.brandSizePct, 3.43) / 100), '#ffffff', {
        align: 'center',
        baseline: 'middle',
        family: FONT_UI,
        weight: 600,
        tracking: pct(env.params.brandTracking, 0.14),
        min: H * 0.012
      })
    }
    if (meta) {
      const alpha = Math.max(0, Math.min(100, pct(env.params.metaAlphaPct, 80))) / 100
      drawFitted(ctx, meta, W / 2, photoBottom + H * (pct(env.params.metaGapPct, 8.5) / 100), maxW, H * (pct(env.params.metaSizePct, 2.4) / 100), `rgba(255,255,255,${alpha})`, {
        align: 'center',
        baseline: 'middle',
        family: FONT_MONO,
        weight: 400,
        tracking: 0.02,
        min: H * 0.009
      })
    }
    return { x: photoX, y: photoY, w: photoW, h: photoH }
  }
}

// ratio-3 reference: full-bleed blurred copy of the photo as the backdrop with a
// centred 1:1 inset (88.27% of the short side, rounded corners) and no text.
const ratioBlurSquare: TemplateDef = {
  id: 'ratioBlurSquare',
  name: '比例 · 模糊方图',
  en: 'RATIO BLUR SQUARE',
  category: '比例',
  description: '模糊底 + 居中方形照片，无文字。',
  defaults: {
    theme: 'ink',
    canvasAspect: 1.5015,
    blur: 30,
    tintAlpha: 0.18,
    squarePct: 88.27,
    radiusPct: 2.6
  },
  themes: lightThemes,
  controls: [],
  layout(env) {
    const aspect = Math.max(0.5, pct(env.params.canvasAspect, 1.5015))
    return { W: env.pw, H: Math.max(2, Math.round(env.pw / aspect)) }
  },
  render(env) {
    const { W, H } = env
    const theme = themeOf(env.params)
    drawBlurredBackdrop(env, W, H, theme, Math.max(0, Math.min(1, pct(env.params.tintAlpha, 0.18))))
    const side = Math.min(W, H) * (pct(env.params.squarePct, 88.27) / 100)
    const x = (W - side) / 2
    const y = (H - side) / 2
    const radius = side * (pct(env.params.radiusPct, 2.6) / 100)
    drawPhotoCover(env, x, y, side, side, radius)
    return { x, y, w: side, h: side }
  }
}

export const TEMPLATES: TemplateDef[] = [
  minimal,
  gallery,
  sideCard,
  specFooter,
  splitReveal,
  sideTicket,
  portraitTicket,
  poetryCard,
  locationCard,
  paletteCard,
  polaroidFrame,
  xiaomiStandard,
  brandFooter,
  gradFooter,
  personalBorder,
  vendorFooter,
  xiaomiLeica,
  paramStack,
  matteOverlay,
  logoFooter,
  matteFooter,
  profileFrame,
  overlay,
  bareCover,
  vertical,
  blur,
  palette,
  film,
  polaroid,
  editorial,
  glass,
  glassSideCard,
  glassPalettePortrait,
  ticket,
  movie,
  ratioBlock,
  ratioMatte,
  ratioBlurSquare,
  systemCamera
]

export const TEMPLATE_MAP: Record<string, TemplateDef> = Object.fromEntries(TEMPLATES.map((t) => [t.id, t]))

export function getTemplate(id: string): TemplateDef {
  return TEMPLATE_MAP[id] ?? minimal
}

export { THEMES }
