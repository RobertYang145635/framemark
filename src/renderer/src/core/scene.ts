import type {
  CropInfo,
  Design,
  Params,
  PhotoAsset,
  Ratio,
  Rect,
  TemplateDef,
  TemplateRenderEnv,
  TextSettings,
  TextSlot,
  WatermarkSettings
} from '../types'
import { getTemplate } from './templates'
import { VARIANT_MAP } from './variants'
import { loadImage } from './images'
import { setGrainSuppressed, textScaleOf } from './draw'
import { drawWatermark } from './watermark'
import { resolveTokens } from './exif'
import { logoAsset, logoCutForFile } from './brands'
import { surfaceForDesign } from './logo-compat'
import { styleBrandFile } from './system/assets'
import { attachTextRecorder, attributeTextSlots, type TextDraw } from './text-slots'

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
  const hidden = new Set(text.hidden ?? [])
  return {
    ...text,
    title: hidden.has('title') ? '' : resolveTokens(text.title, asset.exif, vars),
    subtitle: hidden.has('subtitle') ? '' : resolveTokens(text.subtitle, asset.exif, vars),
    signature: hidden.has('signature') ? '' : resolveTokens(text.signature, asset.exif, vars),
    customLine: hidden.has('customLine') ? '' : resolveTokens(text.customLine, asset.exif, vars),
    location: hidden.has('location') ? '' : text.location
  }
}

/**
 * A band of the stage to paint. `y` is the band's top in FULL-stage pixels and
 * `h` its height. paintScene translates the context by (0, -y), so every
 * template keeps painting in stage coordinates while the canvas only holds
 * rows [y, y+h).
 */
export interface BandSpec {
  y: number
  h: number
}

export interface PaintSceneOptions {
  /** Omitted = paint the whole stage (today's unbanded path). */
  band?: BandSpec
  /**
   * S6a seam canary: drop the grain overlay for THIS paint only. The exporter
   * asks exactly one band to skip it, so a gate that stays green proves the
   * gate cannot see a seam (i.e. the gate is fake). Never on by default.
   */
  suppressGrain?: boolean
}

export interface PaintResult {
  W: number
  H: number
  photoRect: Rect
  textSlots: TextSlot[]
}

/**
 * Everything a paint needs that does not depend on the canvas size or on a
 * band: layout, resolved params/text, the brand wordmark and avatar bitmaps,
 * the optional watermark bitmap, and the text-recorder scope.
 *
 * `W`/`H` are the FULL stage (layout), never a band's — the ~9 template sites
 * that derive geometry from `Math.min(W, H)` must keep seeing the full card.
 * prepare() runs once per render, so band painting never re-runs it (a prepare
 * that draws offscreen would otherwise be repeated N times per export).
 */
export interface Scene {
  template: TemplateDef
  design: Design
  asset: PhotoAsset
  image: CanvasImageSource
  crop: CropInfo
  params: Params
  text: TextSettings
  W: number
  H: number
  logo?: HTMLImageElement
  avatar?: HTMLImageElement
  preparedImages: Record<string, HTMLImageElement>
  preview: boolean
  handCapture: boolean
  scope: string
  watermark: { settings: WatermarkSettings; image: HTMLImageElement } | null
}

/**
 * Steps that do not touch a canvas: crop/params/layout + the async bitmap loads.
 * Behaviourally identical to the head of the old renderDesign body.
 */
export async function prepareScene(
  design: Design,
  asset: PhotoAsset,
  image: CanvasImageSource,
  imageW: number,
  imageH: number,
  opts: { preview: boolean }
): Promise<Scene> {
  const template = getTemplate(design.templateId)
  const crop = computeCrop(imageW, imageH, design.ratio)
  const params = mergeParams(design, template.defaults)
  // The surface the painted card actually is — the SAME rule `paint.ts` uses
  // for `brandDark` (simple luma > 0.7, alpha < 0.4 ⇒ dark), so the wordmark
  // tone chosen here can never disagree with the tone the painter assumes.
  const surface = surfaceForDesign(design)
  const prepareEnv = {
    pw: crop.sw,
    ph: crop.sh,
    crop,
    exif: asset.exif,
    params,
    text: design.text,
    images: {},
    brandVersion: design.brandVersion
  }
  const prepared = template.prepare ? await template.prepare(prepareEnv) : undefined
  const layout = template.layout(prepareEnv)
  const W = Math.max(2, Math.round(layout.W))
  const H = Math.max(2, Math.round(layout.H))
  // Chromium's real canvas area cap is ~268MP (the old 320MP guard let W*H
  // through to an allocation that silently produced a blank canvas).
  if (W > 32000 || H > 32000 || W * H > 260 * 1000 * 1000) {
    throw new Error(`画布过大：${W} × ${H}`)
  }
  const text = resolveText(design.text, asset, template.name)
  // FIX #64 (second iteration): the brand wordmark comes from THIS style's own
  // declaration — its bucketed pick, its `vendorLogo`, its `autoLogo` — and from
  // the photo's EXIF. `design.logo` is a GLOBAL display mirror (Inspector
  // thumbnail, presets) that survives a variant switch, which is what painted the
  // previous card's mark on the next one. See core/system/assets.ts "Hand-card
  // brand wordmarks". A style with no declaration gets no wordmark — nothing
  // invented. The bitmap is loaded through `logoAsset()` (the registry's own
  // loader), so a style with no declaration cannot paint anything, and the
  // decoded mark keeps the same PNG path the accepted renders used.
  const variant = design.variantId ? VARIANT_MAP[design.variantId] : undefined
  // S9: the brand picker's choice must reach the pixels through ONE carrier —
  // `params.brandLogoFile` resolved by `styleBrandFile()` — never a second paint
  // path. Two gaps are closed here:
  //   1. `design.logo` (the bitmap every logo action writes) is routed INTO the
  //      same pick slot, mapped back to its registry file name. Only a
  //      CATALOGUED cut qualifies, so `styleBrandFile()` keeps its surface-fit
  //      check (a black mark on a dark card stays hidden, never re-toned), and
  //      only on a card that owns a brand slot, so a logo-less card cannot grow
  //      a mark from the global mirror. An explicit `params.brandLogoFile` —
  //      even the empty '' of 「自动匹配」 — always wins over the mirror.
  //   2. An explicitly reset design (`brandVersion: null` as written by
  //      `setVariant`/`clearLogo`, no bitmap, no pick) must not resurrect the
  //      variant's declared default: that state is "no logo", so 「清除」 and the
  //      picker's null pick now actually remove the mark. `brandVersion:
  //      undefined` (fresh design, gallery thumbnail, legacy preset) still takes
  //      the variant declaration, so thumbnails and untouched cards keep their
  //      vendor mark.
  const ownsBrandSlot = Boolean(variant?.vendorLogo || variant?.autoLogo)
  const mirrorName = ownsBrandSlot ? design.logo?.name : undefined
  // The variant's OWN declared bitmap keeps its historical path (`vendorLogo`,
  // painted even when the catalog has no surface metadata for it): routing the
  // identical file through the pick slot would add a surface check the card
  // never had and silently hide marks like `white-2`'s. Only a mark that is
  // NOT the card's declared default is a choice and goes through the pick.
  const mirrorPick =
    mirrorName && mirrorName !== variant?.vendorLogo && logoCutForFile(mirrorName)
      ? mirrorName
      : undefined
  const pickedParam = typeof params.brandLogoFile === 'string' ? params.brandLogoFile : undefined
  // `version` is the explicit Logo Library pick: `styleBrandFile()` then draws
  // exactly that version's cut for this surface and returns null when the
  // version has none — never the old black file.
  const brandCleared =
    design.logo == null && design.brandVersion === null && pickedParam === undefined
  const brandFile = brandCleared
    ? null
    : styleBrandFile(
        {
          pick: pickedParam !== undefined ? pickedParam : mirrorPick,
          vendorLogo: variant?.vendorLogo,
          autoLogo: variant?.autoLogo,
          version: design.brandVersion
        },
        asset.exif,
        surface
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
  // The watermark bitmap used to be decoded after the template render; loading
  // it here lets every band reuse one decode. Failure still means "no mark".
  let watermark: Scene['watermark'] = null
  if (design.watermark.enabled && design.watermark.asset) {
    try {
      watermark = { settings: design.watermark, image: await loadImage(design.watermark.asset.dataUrl) }
    } catch {
      watermark = null
    }
  }
  return {
    template,
    design,
    asset,
    image,
    crop,
    params,
    text,
    W,
    H,
    logo,
    avatar,
    preparedImages: prepared?.images ?? {},
    preview: opts.preview,
    // Hand cards: record every text line the template paints so the 文字 pane can
    // list them by position. Stock cards draw through their own per-slot IR path.
    handCapture: design.templateId !== 'systemCamera',
    scope: design.variantId ?? design.templateId,
    watermark
  }
}

/** paintScene diagnostics: transform stacks that needed resetting (should stay 0). */
export const bandStats = { paints: 0, transformResets: 0 }

/**
 * Paint one scene onto `canvas`: the whole stage when `opts.band` is absent,
 * otherwise just rows [y, y+h) of it. Returns full-stage W/H and photoRect.
 */
export function paintScene(canvas: HTMLCanvasElement, scene: Scene, opts: PaintSceneOptions = {}): PaintResult {
  const { W, H } = scene
  const band = opts.band
  const y = band ? Math.max(0, Math.min(H, Math.floor(band.y))) : 0
  const h = band ? Math.max(1, Math.min(Math.round(band.h), H - y)) : H
  canvas.width = W
  canvas.height = h
  const ctx = canvas.getContext('2d', { alpha: false })
  if (!ctx) throw new Error('无法创建画布')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  if (y > 0) ctx.translate(0, -y)
  const draws: TextDraw[] = []
  const restoreRecorder = scene.handCapture ? attachTextRecorder(ctx, draws, scene.text, scene.asset.exif, scene.scope) : null
  const renderCtx = scaledContext(ctx, scene.text.fontScale)
  const env: TemplateRenderEnv = {
    ctx: renderCtx,
    W,
    H,
    pw: scene.crop.sw,
    ph: scene.crop.sh,
    photo: scene.image,
    crop: scene.crop,
    exif: scene.asset.exif,
    params: scene.params,
    text: scene.text,
    design: scene.design,
    images: { logo: scene.logo, avatar: scene.avatar, ...scene.preparedImages },
    isPreview: scene.preview
  }
  let photoRect: Rect
  try {
    if (opts.suppressGrain) setGrainSuppressed(true)
    // `W`/`H` in env are the FULL stage even in band mode: the band is a canvas
    // clip plus the translate above, never a smaller coordinate system.
    photoRect = scene.template.render(env)
  } finally {
    if (opts.suppressGrain) setGrainSuppressed(false)
    restoreRecorder?.()
  }
  if (scene.watermark) {
    try {
      drawWatermark(ctx, scene.watermark.settings, photoRect, W, H, scene.watermark.image)
    } catch {
      // watermark failure should not kill the whole preview
    }
  }
  bandStats.paints++
  if (band) {
    // The template must leave the transform where the band translate put it
    // (a=1,b=0,c=0,d=1,e=0,f=-y). If it did not, restore it so the caller can
    // copy the band out and keep painting bands on a known state.
    const m = ctx.getTransform()
    if (m.a !== 1 || m.b !== 0 || m.c !== 0 || m.d !== 1 || m.e !== 0 || m.f !== -y) {
      ctx.setTransform(1, 0, 0, 1, 0, -y)
      bandStats.transformResets++
    }
  }
  // TextDraw.y is device/stage y already (recordTextDraw folds getTransform),
  // i.e. band-local minus the band origin. Fold it back for the 文字 pane.
  if (y > 0) for (const d of draws) d.y += y
  const textSlots = scene.handCapture ? attributeTextSlots(draws, W, H) : []
  return { W, H, photoRect, textSlots }
}

/**
 * Today's public entry point, unchanged in signature and behaviour: prepare,
 * then paint the whole stage in one pass.
 */
export async function renderDesign(
  canvas: HTMLCanvasElement,
  design: Design,
  asset: PhotoAsset,
  image: CanvasImageSource,
  imageW: number,
  imageH: number,
  opts: { preview: boolean }
): Promise<{ W: number; H: number; photoRect: Rect; textSlots: TextSlot[] }> {
  const scene = await prepareScene(design, asset, image, imageW, imageH, opts)
  return paintScene(canvas, scene)
}
