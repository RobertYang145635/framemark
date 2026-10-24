import type { Rect, WatermarkSettings } from '../types'

export function drawWatermark(
  ctx: CanvasRenderingContext2D,
  settings: WatermarkSettings,
  photoRect: Rect,
  canvasW: number,
  canvasH: number,
  img: HTMLImageElement
): void {
  if (!settings.enabled || !settings.asset) return
  const area: Rect = settings.target === 'canvas' ? { x: 0, y: 0, w: canvasW, h: canvasH } : photoRect
  if (area.w <= 1 || area.h <= 1) return
  const iw = img.naturalWidth || img.width || 1
  const ih = img.naturalHeight || img.height || 1
  const opacity = Math.max(0.02, Math.min(1, settings.opacity))
  const rotation = (settings.rotation * Math.PI) / 180

  ctx.save()
  ctx.beginPath()
  ctx.rect(area.x, area.y, area.w, area.h)
  ctx.clip()
  ctx.globalAlpha = opacity
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'

  if (settings.tile) {
    const tileW = Math.max(12, (area.w * Math.max(2, settings.widthPct)) / 100)
    const tileH = (tileW * ih) / iw
    const gapX = (area.w * Math.max(0, settings.gapPct)) / 100
    const gapY = (area.h * Math.max(0, settings.gapPct)) / 100
    const stepX = tileW + gapX
    const stepY = tileH + gapY
    const cx = area.x + area.w / 2
    const cy = area.y + area.h / 2
    ctx.translate(cx, cy)
    ctx.rotate(rotation)
    const reach = Math.hypot(area.w, area.h) / 2 + Math.max(stepX, stepY)
    const cols = Math.ceil(reach / stepX)
    const rows = Math.ceil(reach / stepY)
    for (let r = -rows; r <= rows; r++) {
      for (let c = -cols; c <= cols; c++) {
        const x = c * stepX - tileW / 2
        const y = r * stepY - tileH / 2
        ctx.drawImage(img, x, y, tileW, tileH)
      }
    }
    ctx.restore()
    return
  }

  const w = Math.max(8, (area.w * Math.max(1, settings.widthPct)) / 100)
  const h = (w * ih) / iw
  const dx = (area.w * settings.offsetX) / 100
  const dy = (area.h * settings.offsetY) / 100
  let x = area.x + dx
  let y = area.y + dy
  if (settings.anchor.includes('right')) x = area.x + area.w - w - dx
  else if (settings.anchor.includes('center') || settings.anchor === 'center') x = area.x + area.w / 2 - w / 2 + dx
  if (settings.anchor.startsWith('bottom')) y = area.y + area.h - h - dy
  else if (settings.anchor.startsWith('middle') || settings.anchor === 'center') y = area.y + area.h / 2 - h / 2 + dy

  ctx.translate(x + w / 2, y + h / 2)
  ctx.rotate(rotation)
  ctx.drawImage(img, -w / 2, -h / 2, w, h)
  ctx.restore()
}
