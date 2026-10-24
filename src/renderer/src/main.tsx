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
import type { Design, PhotoAsset } from './types'

const THUMB_W = 480
const THUMB_H = 360

async function renderStockPreview(id: string): Promise<{ data: string; W: number; H: number }> {
  const style = STOCK_STYLE_LIST.find((s) => s.id === id)
  if (!style) throw new Error(`unknown stock style: ${id}`)
  const base = await getSampleAsset()
  const image = await loadImage(base.previewUrl)
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
  const asset: PhotoAsset = { ...base, width: image.width, height: image.height, exif }
  const design: Design = {
    templateId: 'systemCamera',
    ratio: 'original',
    params: { systemStyle: id },
    text: { ...DEFAULT_TEXT },
    watermark: { ...DEFAULT_WATERMARK },
    logo: null,
    avatar: null,
    export: { ...DEFAULT_EXPORT }
  }
  const canvas = document.createElement('canvas')
  await renderDesign(canvas, design, asset, image, image.width, image.height, { preview: true })
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
  return { data: out.toDataURL('image/webp', 0.86), W: canvas.width, H: canvas.height }
}

async function renderVariantPreview(id: string): Promise<{ data: string; W: number; H: number }> {
  const variant = TEMPLATE_VARIANTS.find(
    (v) =>
      v.id === id &&
      (v.category !== '系统相机' || v.base === 'polaroidFrame' || v.base === 'xiaomiStandard')
  )
  if (!variant) throw new Error(`unknown template variant: ${id}`)
  const base = await getSampleAsset()
  const image = await loadImage(base.previewUrl)
  const asset: PhotoAsset = { ...base, width: image.width, height: image.height }
  const design = makeDesignFromVariant(variant)
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
  const canvas = document.createElement('canvas')
  await renderDesign(canvas, design, asset, image, image.width, image.height, { preview: true })
  const out = document.createElement('canvas')
  out.width = THUMB_W
  out.height = THUMB_H
  const ctx = out.getContext('2d')!
  ctx.fillStyle = '#0b0b0c'
  ctx.fillRect(0, 0, THUMB_W, THUMB_H)
  const scale = Math.min(THUMB_W / canvas.width, THUMB_H / canvas.height)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(canvas, (THUMB_W - canvas.width * scale) / 2, (THUMB_H - canvas.height * scale) / 2, canvas.width * scale, canvas.height * scale)
  return { data: out.toDataURL('image/webp', 0.86), W: canvas.width, H: canvas.height }
}

;(window as unknown as {
  __fmStock: {
    list: () => { id: string; name: string; vendor: string; family: string; status: string }[]
    render: (id: string) => Promise<{ data: string; W: number; H: number }>
  }
  __fmVariants: {
    list: () => { id: string; name: string; category: string }[]
    render: (id: string) => Promise<{ data: string; W: number; H: number }>
  }
}).__fmStock = {
  list: () =>
    STOCK_STYLE_LIST.map((s) => ({
      id: s.id,
      name: s.name,
      vendor: s.vendor,
      family: s.family,
      status: s.audit.status
    })),
  render: renderStockPreview
}



createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
