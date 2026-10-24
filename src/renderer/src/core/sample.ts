import type { ExifData, PhotoAsset } from '../types'
import { createCanvas, loadImage } from './images'

let cached: { url: string; width: number; height: number; exif: ExifData } | null = null

function rng(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}

export function getSampleData(): { url: string; width: number; height: number; exif: ExifData } {
  if (cached) return cached
  const W = 1600
  const H = 1067
  const canvas = createCanvas(W, H)
  const ctx = canvas.getContext('2d')!
  const horizon = H * 0.62
  const rand = rng(20241018)
  const exif: ExifData = {
    make: 'LUMIX',
    model: 'DC-S1',
    lens: 'LUMIX S 24-105mm F4',
    focal: 35,
    aperture: 8,
    shutter: 1 / 125,
    iso: 640,
    dateTime: '2024-10-18 17:42:00',
    width: W,
    height: H,
    hasAny: true
  }

  const sky = ctx.createLinearGradient(0, 0, 0, horizon)
  sky.addColorStop(0, '#12202f')
  sky.addColorStop(0.38, '#29435a')
  sky.addColorStop(0.7, '#b87257')
  sky.addColorStop(1, '#f2b36e')
  ctx.fillStyle = sky
  ctx.fillRect(0, 0, W, horizon + 2)

  const glow = ctx.createRadialGradient(W * 0.72, horizon * 0.62, 4, W * 0.72, horizon * 0.62, H * 0.45)
  glow.addColorStop(0, 'rgba(255,231,184,0.95)')
  glow.addColorStop(0.2, 'rgba(255,190,120,0.5)')
  glow.addColorStop(1, 'rgba(255,160,90,0)')
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, W, horizon)

  ctx.fillStyle = 'rgba(255,242,218,0.9)'
  ctx.beginPath()
  ctx.arc(W * 0.72, horizon * 0.62, H * 0.035, 0, Math.PI * 2)
  ctx.fill()

  for (let i = 0; i < 26; i++) {
    const cx = rand() * W
    const cy = rand() * horizon * 0.7
    const rw = W * (0.04 + rand() * 0.14)
    const rh = H * (0.006 + rand() * 0.018)
    const g = ctx.createLinearGradient(cx - rw, cy, cx + rw, cy)
    g.addColorStop(0, 'rgba(255,225,200,0)')
    g.addColorStop(0.5, `rgba(255,224,201,${0.06 + rand() * 0.12})`)
    g.addColorStop(1, 'rgba(255,225,200,0)')
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.ellipse(cx, cy, rw, rh, rand() * 0.2 - 0.1, 0, Math.PI * 2)
    ctx.fill()
  }

  const layers = [
    { h: 0.14, color: '#4b5360', y: 0.03 },
    { h: 0.11, color: '#38414c', y: 0.08 },
    { h: 0.07, color: '#242d35', y: 0.14 }
  ]
  for (const layer of layers) {
    ctx.fillStyle = layer.color
    ctx.beginPath()
    ctx.moveTo(0, horizon)
    let x = 0
    const baseY = horizon - H * (layer.h - layer.y)
    ctx.lineTo(0, baseY)
    while (x < W) {
      const step = W * (0.06 + rand() * 0.12)
      const peak = baseY - H * (rand() * layer.h * 0.8)
      ctx.lineTo(x + step * 0.5, peak)
      ctx.lineTo(x + step, baseY + H * rand() * 0.01)
      x += step
    }
    ctx.lineTo(W, horizon)
    ctx.closePath()
    ctx.fill()
  }

  const water = ctx.createLinearGradient(0, horizon, 0, H)
  water.addColorStop(0, '#b27c5c')
  water.addColorStop(0.25, '#725d55')
  water.addColorStop(1, '#1b2229')
  ctx.fillStyle = water
  ctx.fillRect(0, horizon, W, H - horizon)

  for (let i = 0; i < 130; i++) {
    const y = horizon + Math.pow(rand(), 1.5) * (H - horizon)
    const alpha = Math.max(0.02, 0.18 * (1 - (y - horizon) / (H - horizon)))
    ctx.strokeStyle = `rgba(255,197,140,${alpha})`
    ctx.lineWidth = 1 + rand() * 2
    ctx.beginPath()
    const start = rand() * W
    const len = W * (0.02 + rand() * 0.22)
    ctx.moveTo(start, y)
    ctx.lineTo(start + len, y + (rand() - 0.5) * 4)
    ctx.stroke()
  }

  ctx.fillStyle = 'rgba(20,26,30,0.65)'
  ctx.beginPath()
  ctx.moveTo(0, H)
  ctx.lineTo(0, horizon + H * 0.10)
  for (let x = 0; x <= W; x += W * 0.018) {
    ctx.lineTo(x, horizon + H * 0.10 - Math.sin(x * 0.008) * H * 0.02 - rand() * H * 0.02)
  }
  ctx.lineTo(W, H)
  ctx.closePath()
  ctx.fill()

  for (let i = 0; i < 90; i++) {
    const x = rand() * W
    const h = H * (0.02 + rand() * 0.08)
    ctx.strokeStyle = `rgba(10,14,16,${0.25 + rand() * 0.4})`
    ctx.lineWidth = 1 + rand()
    ctx.beginPath()
    ctx.moveTo(x, H)
    ctx.quadraticCurveTo(x + (rand() - 0.5) * 40, H - h * 0.6, x + (rand() - 0.5) * 80, H - h)
    ctx.stroke()
  }

  const vignette = ctx.createRadialGradient(W / 2, H / 2, H * 0.25, W / 2, H / 2, H * 0.95)
  vignette.addColorStop(0, 'rgba(0,0,0,0)')
  vignette.addColorStop(1, 'rgba(0,0,0,0.35)')
  ctx.fillStyle = vignette
  ctx.fillRect(0, 0, W, H)

  const url = canvas.toDataURL('image/jpeg', 0.92)
  cached = { url, width: W, height: H, exif }
  return cached
}

export async function getSampleAsset(): Promise<PhotoAsset> {
  const d = getSampleData()
  await loadImage(d.url)
  return {
    id: 'sample-photo',
    name: 'sample-lake.jpg',
    source: { kind: 'sample' },
    previewUrl: d.url,
    previewW: d.width,
    previewH: d.height,
    width: d.width,
    height: d.height,
    exif: d.exif
  }
}
