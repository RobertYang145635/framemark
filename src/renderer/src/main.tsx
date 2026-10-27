import React from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import '@fontsource/bebas-neue/400.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/caveat/400.css'
import '@fontsource/caveat/600.css'
import './styles.css'
import { App } from './App'
import { getSampleAsset } from './core/sample'
import { loadImage } from './core/images'
import { renderDesign } from './core/scene'
import { DEFAULT_EXPORT, DEFAULT_TEXT, DEFAULT_WATERMARK, makeDesignFromVariant } from './core/presets'
import { autoLogo, logoAsset } from './core/brands'
import { TEMPLATE_VARIANTS } from './core/variants'
import { STOCK_STYLE_LIST } from './core/system/styles'
import { collectBrandNodes } from './core/system/assets'
import type { BrandVersionRef, Design, PhotoAsset, Rect, TemplateVariant } from './types'

const THUMB_W = 480
const THUMB_H = 360

interface DebugRenderResult {
  data: string
  W: number
  H: number
  photoRect?: Rect
}

/**
 * Debug-only render options. Normal UI never passes these; omitting every field
 * keeps `__fmStock.render` / `__fmVariants.render` byte-identical to before.
 */
interface DebugRenderOptions {
  ratio?: string
  maxEdge?: number
  /** Overrides `design.params.theme` (stock theme id / hand palette id). */
  theme?: string
  /** Explicit Logo Library version, or null to clear it. */
  brandVersion?: BrandVersionRef | null
  /** `false` silences every logo path (brand slots included). */
  showLogo?: boolean
}

// The debug API mirrors what the gallery can actually render: every hand-written
// variant plus the manually registered stock variant (oppo-polaroid). Xiaomi
// standard 6/7/8/107 moved to the generated IR in P2 and render through
// window.__fmStock instead.
const RENDERABLE_VARIANTS = TEMPLATE_VARIANTS.filter(
  (v) => v.category !== '系统相机' || v.base === 'polaroidFrame'
)
if (RENDERABLE_VARIANTS.length !== 69) {
  console.warn(`__fmVariants: expected 69 renderable variants, got ${RENDERABLE_VARIANTS.length}`)
}

async function applyVariantLogo(design: Design, variant: TemplateVariant, asset: PhotoAsset): Promise<void> {
  if (variant.vendorLogo) {
    try {
      design.logo = await logoAsset(variant.vendorLogo)
    } catch {
      design.logo = null
    }
  } else if (variant.autoLogo) {
    const dark = ['ink', 'black'].includes(String(design.params.theme))
    const file = autoLogo(asset.exif.make, asset.exif.model, dark)
    if (file) {
      try {
        design.logo = await logoAsset(file)
      } catch {
        design.logo = null
      }
    }
  }
}

function parseAspect(ratio: string | undefined): number | null {
  if (!ratio || ratio === 'original') return null
  const m = /^(\d+(?:\.\d+)?)\s*[:/x]\s*(\d+(?:\.\d+)?)$/i.exec(ratio.trim())
  if (!m) throw new Error(`unsupported ratio: ${ratio}`)
  const value = Number(m[1]) / Number(m[2])
  if (!Number.isFinite(value) || value <= 0) throw new Error(`unsupported ratio: ${ratio}`)
  return value
}

// Center-crop the sample photo to the requested aspect before layout, so the
// exported canvas is exactly the requested ratio without touching app behavior.
function cropToAspect(image: HTMLImageElement, aspect: number): { canvas: HTMLCanvasElement; w: number; h: number } {
  const iw = image.naturalWidth || image.width
  const ih = image.naturalHeight || image.height
  let sw = iw
  let sh = ih
  let sx = 0
  let sy = 0
  if (iw / ih > aspect) {
    sw = ih * aspect
    sx = (iw - sw) / 2
  } else {
    sh = iw / aspect
    sy = (ih - sh) / 2
  }
  const w = Math.max(1, Math.round(sw))
  const h = Math.max(1, Math.round(sh))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')!
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(image, sx, sy, sw, sh, 0, 0, w, h)
  return { canvas, w, h }
}

async function renderStockPreview(id: string, opts?: DebugRenderOptions): Promise<DebugRenderResult> {
  const style = STOCK_STYLE_LIST.find((s) => s.id === id)
  if (!style) throw new Error(`unknown stock style: ${id}`)
  const base = await getSampleAsset()
  const source = await loadImage(base.previewUrl)
  const aspect = parseAspect(opts?.ratio)
  const cropped = aspect ? cropToAspect(source, aspect) : null
  const image: CanvasImageSource = cropped ? cropped.canvas : source
  const imageW = cropped ? cropped.w : source.naturalWidth || source.width
  const imageH = cropped ? cropped.h : source.naturalHeight || source.height
  const isXiaomi = style.vendor === 'xiaomi'
  const isVivo = style.vendor === 'vivo'
  const exif = {
    ...base.exif,
    make: isXiaomi ? 'Xiaomi' : isVivo ? 'vivo' : 'OPPO',
    model: isXiaomi ? '15 Ultra' : isVivo ? 'X500 Pro Max' : 'Find X9 Ultra',
    focal: 70,
    aperture: 3.2,
    shutter: 1 / 120,
    iso: 640,
    hasAny: true
  }
  const asset: PhotoAsset = { ...base, width: imageW, height: imageH, exif }
  const design: Design = {
    templateId: 'systemCamera',
    // The 4:3 sample path mirrors __fmVariants: the photo is center-cropped to the
    // requested aspect, the design ratio itself stays 'original'.
    ratio: 'original',
    params: { systemStyle: id },
    text: { ...DEFAULT_TEXT },
    watermark: { ...DEFAULT_WATERMARK },
    logo: null,
    avatar: null,
    export: { ...DEFAULT_EXPORT }
  }
  // Debug-only overrides (the normal app never calls this API).
  if (opts?.theme !== undefined) design.params.theme = opts.theme
  design.brandVersion = opts?.brandVersion ?? null
  if (opts?.showLogo === false) design.text.showLogo = false
  const canvas = document.createElement('canvas')
  const exportMode = Boolean(opts && (opts.ratio || opts.maxEdge))
  const rendered = await renderDesign(canvas, design, asset, image, imageW, imageH, { preview: !exportMode })
  if (!exportMode) {
    const out = document.createElement('canvas')
    out.width = THUMB_W
    out.height = THUMB_H
    const ctx = out.getContext('2d')!
    ctx.fillStyle = '#0b0b0c'
    ctx.fillRect(0, 0, THUMB_W, THUMB_H)
    const scale = Math.min(THUMB_W / canvas.width, THUMB_H / canvas.height)
    const dw = canvas.width * scale
    const dh = canvas.height * scale
    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(canvas, (THUMB_W - dw) / 2, (THUMB_H - dh) / 2, dw, dh)
    return { data: out.toDataURL('image/webp', 0.86), W: rendered.W, H: rendered.H, photoRect: rendered.photoRect }
  }
  const maxEdge = Math.max(0, Math.floor(Number(opts?.maxEdge) || 0))
  const scale = maxEdge > 0 ? Math.min(1, maxEdge / Math.max(canvas.width, canvas.height)) : 1
  if (scale >= 1) {
    return { data: canvas.toDataURL('image/png'), W: canvas.width, H: canvas.height, photoRect: rendered.photoRect }
  }
  const outW = Math.max(1, Math.round(canvas.width * scale))
  const outH = Math.max(1, Math.round(canvas.height * scale))
  const out = document.createElement('canvas')
  out.width = outW
  out.height = outH
  const ctx = out.getContext('2d', { alpha: false })!
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(canvas, 0, 0, outW, outH)
  const photoRect: Rect = {
    x: rendered.photoRect.x * scale,
    y: rendered.photoRect.y * scale,
    w: rendered.photoRect.w * scale,
    h: rendered.photoRect.h * scale
  }
  return { data: out.toDataURL('image/png'), W: outW, H: outH, photoRect }
}

async function renderVariantPreview(
  id: string,
  opts?: DebugRenderOptions
): Promise<DebugRenderResult> {
  const variant = RENDERABLE_VARIANTS.find((v) => v.id === id)
  if (!variant) throw new Error(`unknown template variant: ${id}`)
  const base = await getSampleAsset()
  const source = await loadImage(base.previewUrl)
  const aspect = parseAspect(opts?.ratio)
  const cropped = aspect ? cropToAspect(source, aspect) : null
  const image: CanvasImageSource = cropped ? cropped.canvas : source
  const imageW = cropped ? cropped.w : source.naturalWidth || source.width
  const imageH = cropped ? cropped.h : source.naturalHeight || source.height
  const asset: PhotoAsset = { ...base, width: imageW, height: imageH }
  const design = makeDesignFromVariant(variant)
  // Debug-only overrides; `applyVariantLogo` runs after the theme is in place so
  // an autoLogo's light/dark choice follows the overridden palette.
  if (opts?.theme !== undefined) design.params.theme = opts.theme
  design.brandVersion = opts?.brandVersion ?? null
  if (opts?.showLogo === false) design.text.showLogo = false
  await applyVariantLogo(design, variant, asset)

  const canvas = document.createElement('canvas')
  const exportMode = Boolean(opts && (opts.ratio || opts.maxEdge))
  const rendered = await renderDesign(canvas, design, asset, image, imageW, imageH, { preview: !exportMode })

  if (!exportMode) {
    const out = document.createElement('canvas')
    out.width = THUMB_W
    out.height = THUMB_H
    const ctx = out.getContext('2d')!
    ctx.fillStyle = '#0b0b0c'
    ctx.fillRect(0, 0, THUMB_W, THUMB_H)
    const scale = Math.min(THUMB_W / canvas.width, THUMB_H / canvas.height)
    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(
      canvas,
      (THUMB_W - canvas.width * scale) / 2,
      (THUMB_H - canvas.height * scale) / 2,
      canvas.width * scale,
      canvas.height * scale
    )
    return { data: out.toDataURL('image/webp', 0.86), W: rendered.W, H: rendered.H, photoRect: rendered.photoRect }
  }

  const maxEdge = Math.max(0, Math.floor(Number(opts?.maxEdge) || 0))
  const scale = maxEdge > 0 ? Math.min(1, maxEdge / Math.max(canvas.width, canvas.height)) : 1
  if (scale >= 1) {
    return { data: canvas.toDataURL('image/png'), W: canvas.width, H: canvas.height, photoRect: rendered.photoRect }
  }
  const outW = Math.max(1, Math.round(canvas.width * scale))
  const outH = Math.max(1, Math.round(canvas.height * scale))
  const out = document.createElement('canvas')
  out.width = outW
  out.height = outH
  const ctx = out.getContext('2d', { alpha: false })!
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(canvas, 0, 0, outW, outH)
  const photoRect: Rect = {
    x: rendered.photoRect.x * scale,
    y: rendered.photoRect.y * scale,
    w: rendered.photoRect.w * scale,
    h: rendered.photoRect.h * scale
  }
  return { data: out.toDataURL('image/png'), W: outW, H: outH, photoRect }
}

interface VariantDebugApi {
  list: () => { id: string; name: string; category: string }[]
  render: (id: string, opts?: DebugRenderOptions) => Promise<DebugRenderResult>
}

const debugWindow = window as unknown as {
  __fmStock: {
    list: () => {
      id: string
      name: string
      vendor: string
      family: string
      status: string
      themes: string[]
      themeBackgrounds: Record<string, string>
      brandSlots: number
    }[]
    render: (id: string, opts?: DebugRenderOptions) => Promise<DebugRenderResult>
  }
  __fmVariants: VariantDebugApi
}

debugWindow.__fmStock = {
  list: () =>
    STOCK_STYLE_LIST.map((s) => ({
      id: s.id,
      name: s.name,
      vendor: s.vendor,
      family: s.family,
      status: s.audit.status,
      // Ids and the background each one paints on: enough for an audit script
      // to pick a light and a dark surface without reading style data.
      themes: (s.themes ?? []).map((t) => t.id),
      themeBackgrounds: Object.fromEntries((s.themes ?? []).map((t) => [t.id, t.background])),
      brandSlots: collectBrandNodes(s).length
    })),
  render: renderStockPreview
}

debugWindow.__fmVariants = {
  list: () => RENDERABLE_VARIANTS.map((v) => ({ id: v.id, name: v.name, category: v.category })),
  render: renderVariantPreview
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
