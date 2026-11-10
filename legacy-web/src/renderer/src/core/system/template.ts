import type { TemplateDef, TemplateRenderEnv } from '../../types'
import type { StockEnv } from './ir'
import { prepareStockStyle } from './assets'
import { solveStockLayout, stockReferenceWidth } from './layout'
import { paintStockLayout } from './paint'
import { getStockStyle, STOCK_STYLE_LIST } from './styles'

export const systemCamera: TemplateDef = {
  id: 'systemCamera',
  name: '系统相机',
  en: 'SYSTEM CAMERA',
  category: '系统相机',
  description: '按厂商相机/相册的原始水印配置渲染，保留真实版式与素材。',
  defaults: {
    systemStyle: STOCK_STYLE_LIST[0]?.id ?? ''
  },
  controls: [],
  async prepare(env) {
    const style = getStockStyle(String(env.params.systemStyle ?? ''))
    if (!style) return {}
    // EXIF and the Inspector's brand pick (params.brandLogoFile, written by
    // store.ts) are only available here, so the brand slots (isBrandLogo) are
    // preloaded during prepare and painted from the resolved bitmaps.
    const picked = env.params.brandLogoFile
    return {
      images: await prepareStockStyle(style, {
        exif: env.exif,
        brandLogoFile: typeof picked === 'string' && picked ? picked : undefined,
        // The explicit Logo Library pick travels through prepare so every cut
        // of that version is decoded before paint needs it.
        brandVersion: env.brandVersion
      })
    }
  },
  layout(env) {
    const style = getStockStyle(String(env.params.systemStyle ?? ''))
    if (!style) return { W: env.pw, H: env.ph }
    const layout = solveStockLayout(style, env as StockEnv)
    const k = env.pw / stockReferenceWidth(style, env)
    return { W: layout.W * k, H: layout.H * k }
  },
  render(env) {
    const style = getStockStyle(String(env.params.systemStyle ?? ''))
    if (!style) {
      return { x: 0, y: 0, w: env.pw, h: env.ph }
    }
    const layout = solveStockLayout(style, env)
    const k = env.pw / stockReferenceWidth(style, env)
    return paintStockLayout(env, style, layout, k)
  }
}

export function systemCameraStyleIds(): string[] {
  return STOCK_STYLE_LIST.map((s) => s.id)
}

export function getSystemStyle(id: string | undefined) {
  return getStockStyle(id)
}

export type { TemplateRenderEnv }
