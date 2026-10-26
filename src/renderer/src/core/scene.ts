import type { CropInfo, Design, Params, PhotoAsset, Ratio, Rect, TemplateRenderEnv, TextSettings } from '../types'
import { getTemplate } from './templates'
import { VARIANT_MAP } from './variants'
import { loadImage } from './images'
import { textScaleOf } from './draw'
import { drawWatermark } from './watermark'
import { resolveTokens } from './exif'
import { logoAsset } from './brands'
import { styleBrandFile, themeIsDark } from './system/assets'

const RATIOS: Record<Exclude<Ratio, 'original'>, number> = {
  '1:1': 1,
  '4:5': 4 / 5,
  '16:9': 16 / 9,
  '3:2': 3 / 2
}

export function computeCrop(imageW: number, imageH: number, ratio: Ratio): CropInfo {
  if (ratio === 'original') {
    return { sx: 0, sy: 0, sw: imageW, sh: imageH, pw: imageW, ph: imageH }
  }
  const target = RATIOS[ratio]
  let sw = imageW
  let sh = imageH
  let sx = 0
  let sy = 0
  if (imageW / imageH > target) {
    sw = imageH * target
    sx = (imageW - sw) / 2
  } else {
    sh = imageW / target
    sy = (imageH - sh) / 2
  }
  return { sx, sy, sw, sh, pw: sw, ph: sh }
}

function mergeParams(design: Design, defaults: Record<string, unknown>): Params {
  return { ...defaults, ...design.params } as Params
}

function scaledContext(ctx: CanvasRenderingContext2D, scale: number): CanvasRenderingContext2D {
  const s = textScaleOf(scale)
  if (s === 1) return ctx
  return new Proxy(ctx, {
    set(target, prop, value) {
      if (prop === 'font' && typeof value === 'string') {
        value = value.replace(/(\d*\.?\d+)px/, (_m, n: string) => `${Math.max(1, parseFloat(n) * s)}px`)
      }
      ;(target as unknown as Record<string | symbol, unknown>)[prop] = value
      return true
    },
    get(target, prop) {
      const v = (target as unknown as Record<string | symbol, unknown>)[prop]
      return typeof v === 'function' ? (v as (...args: unknown[]) => unknown).bind(target) : v
    }
  }) as CanvasRenderingContext2D
}

function resolveText(text: TextSettings, asset: PhotoAsset, templateName: string): TextSettings {
  const vars = {
    filename: asset.name.replace(/\.[^.]+$/, ''),
    width: asset.width,
    height: asset.height,
    templateName,
    date: new Date()
  }
  return {
    ...text,
    title: resolveTokens(text.title, asset.exif, vars),
    subtitle: resolveTokens(text.subtitle, asset.exif, vars),
    signature: resolveTokens(text.signature, asset.exif, vars),
    customLine: resolveTokens(text.customLine, asset.exif, vars)
  }
}

export async function renderDesign(
  canvas: HTMLCanvasElement,
  design: Design,
  asset: PhotoAsset,
  image: CanvasImageSource,
  imageW: number,
  imageH: number,
  opts: { preview: boolean }
): Promise<{ W: number; H: number; photoRect: Rect }> {
  const template = getTemplate(design.templateId)
  const crop = computeCrop(imageW, imageH, design.ratio)
  const params = mergeParams(design, template.defaults)
  const prepareEnv = {
    pw: crop.sw,
    ph: crop.sh,
    crop,
    exif: asset.exif,
    params,
    text: design.text,
    images: {}
  }
  const prepared = template.prepare ? await template.prepare(prepareEnv) : undefined
  const layout = template.layout(prepareEnv)
  const W = Math.max(2, Math.round(layout.W))
  const H = Math.max(2, Math.round(layout.H))
  if (W > 32000 || H > 32000 || W * H > 320 * 1000 * 1000) {
    throw new Error(`画布过大：${W} × ${H}`)
  }
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d', { alpha: false })
  if (!ctx) throw new Error('无法创建画布')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  const text = resolveText(design.text, asset, template.name)
  // FIX #64 (second iteration): the brand wordmark comes from THIS style's own
  // declaration — its bucketed pick, its `vendorLogo`, its `autoLogo` — and from
  // the photo's EXIF, never from `design.logo`. `design.logo` is a global field
  // that survives a variant switch, which is what painted the previous card's
  // mark on the next one; clearing it (store.ts `setVariant`) then left the hand
  // family with no bitmap at all. See core/system/assets.ts "Hand-card brand
  // wordmarks". A style with no declaration gets no wordmark — nothing invented.
  // The bitmap is loaded through `logoAsset()` (the registry's own loader) rather
  // than an arbitrary user image, so a style with no declaration cannot paint
  // anything, and the decoded mark keeps the same PNG path the accepted renders used.
  const variant = design.variantId ? VARIANT_MAP[design.variantId] : undefined
  const brandFile = styleBrandFile(
    { pick: params.brandLogoFile, vendorLogo: variant?.vendorLogo, autoLogo: variant?.autoLogo },
    asset.exif,
    themeIsDark(params)
  )
  let logo: HTMLImageElement | undefined
  if (brandFile) {
    try {
      const assetLogo = await logoAsset(brandFile)
      logo = await loadImage(assetLogo.dataUrl)
    } catch {
      logo = undefined // no real asset for that brand => the slot stays empty
    }
  }
  const avatar = design.avatar ? await loadImage(design.avatar.dataUrl) : undefined
  const env: TemplateRenderEnv = {
    ctx: scaledContext(ctx, text.fontScale),
    W,
    H,
    pw: crop.sw,
    ph: crop.sh,
    photo: image,
    crop,
    exif: asset.exif,
    params,
    text,
    design,
    images: { logo, avatar, ...(prepared?.images ?? {}) },
    isPreview: opts.preview
  }
  const photoRect = template.render(env)
  if (design.watermark.enabled && design.watermark.asset) {
    try {
      const wm = await loadImage(design.watermark.asset.dataUrl)
      drawWatermark(ctx, design.watermark, photoRect, W, H, wm)
    } catch {
      // watermark failure should not kill the whole preview
    }
  }
  return { W, H, photoRect }
}
