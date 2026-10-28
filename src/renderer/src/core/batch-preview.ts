import { Capacitor } from '@capacitor/core'
import type { Design, PhotoAsset } from '../types'
import { renderDesign } from './scene'
import { blobToDataUrl, canvasToBlob, createCanvas, loadImage } from './images'

/**
 * Styled-preview cache for the filmstrip and the 1/N batch viewer. Rendering is
 * done at the 1800px proxy size (same stage the main preview uses), then scaled
 * down — so a thumb is compositionally identical to what the editor shows.
 *
 * Android WebViews are much tighter on memory: 60 entries is enough for the
 * filmstrip's visible window, while desktop keeps the full 320.
 */
const CACHE_LIMIT = Capacitor.isNativePlatform() ? 60 : 320
const cache = new Map<string, Promise<string>>()

/**
 * `design` carries base64 logo / watermark / avatar payloads. Hashing them
 * verbatim would rebuild a multi-megabyte string on every slider tick, so long
 * strings are folded to `length:hash` — enough to tell two logos apart.
 */
function foldLong(value: string): string {
  if (value.length <= 96) return value
  let h = 0x811c9dc5
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return `«${value.length}:${(h >>> 0).toString(36)}»`
}

export function designKey(design: Design): string {
  return JSON.stringify(design, (_k, v) => (typeof v === 'string' ? foldLong(v) : v))
}

export async function renderStyledThumb(
  photo: PhotoAsset,
  design: Design,
  maxW: number,
  maxH: number
): Promise<string> {
  const key = `${photo.id}|${maxW}x${maxH}|${designKey(design)}`
  const hit = cache.get(key)
  if (hit) return hit

  const task = (async () => {
    const source = await loadImage(photo.previewUrl)
    const stage = createCanvas(2, 2)
    const rendered = await renderDesign(
      stage,
      design,
      photo,
      source,
      source.naturalWidth,
      source.naturalHeight,
      { preview: true }
    )
    const scale = Math.min(maxW / rendered.W, maxH / rendered.H, 1)
    const out = createCanvas(Math.max(1, Math.round(rendered.W * scale)), Math.max(1, Math.round(rendered.H * scale)))
    const ctx = out.getContext('2d', { alpha: false })
    if (!ctx) throw new Error('无法创建预览画布')
    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(stage, 0, 0, out.width, out.height)
    const blob = await canvasToBlob(out, 'image/webp', 0.88)
    return blobToDataUrl(blob)
  })()

  cache.set(key, task)
  while (cache.size > CACHE_LIMIT) {
    const oldest = cache.keys().next().value
    if (oldest === undefined) break
    cache.delete(oldest)
  }
  task.catch(() => cache.delete(key))
  return task
}
