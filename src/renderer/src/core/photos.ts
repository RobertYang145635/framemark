import exifr from 'exifr'
import type { PhotoAsset } from '../types'
import { normalizeExif } from './exif'
import { blobToDataUrl, canvasToBlob, createCanvas, loadImage } from './images'
import type { PreviewImageResult } from '../global'

const EXIF_OPTS = {
  tiff: true,
  exif: true,
  gps: true,
  ifd0: true,
  interop: true,
  translateValues: true,
  reviveValues: true,
  mergeOutput: true
}

function uid(): string {
  return `photo-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function orientedSize(raw: Record<string, any> | null): { w: number; h: number } | null {
  if (!raw) return null
  let w = Number(raw.ExifImageWidth || raw.ImageWidth || raw.PixelXDimension || raw.ExifImageWidth)
  let h = Number(raw.ExifImageHeight || raw.ImageLength || raw.PixelYDimension || raw.ExifImageHeight)
  if (!w || !h) return null
  const orientation = Number(raw.Orientation || 1)
  if (orientation >= 5 && orientation <= 8) [w, h] = [h, w]
  return { w, h }
}

async function makePreviewFromBitmap(bitmap: ImageBitmap, max = 1800): Promise<{ canvas: HTMLCanvasElement; url: string }> {
  const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height))
  const w = Math.max(1, Math.round(bitmap.width * scale))
  const h = Math.max(1, Math.round(bitmap.height * scale))
  const canvas = createCanvas(w, h)
  const ctx = canvas.getContext('2d', { alpha: false })!
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(bitmap, 0, 0, w, h)
  const blob = await canvasToBlob(canvas, 'image/jpeg', 0.9)
  return { canvas, url: URL.createObjectURL(blob) }
}

export type ImportPhase = (phase: string, filePct: number) => void

export async function photoFromFile(file: File, onPhase?: ImportPhase): Promise<PhotoAsset> {
  onPhase?.('读取照片信息', 0.08)
  let raw: Record<string, any> | null = null
  try {
    raw = ((await exifr.parse(file, EXIF_OPTS as any)) as Record<string, any>) ?? null
  } catch {
    raw = null
  }
  const known = orientedSize(raw)
  onPhase?.('解码照片', 0.35)
  let bitmap: ImageBitmap
  try {
    if (known) {
      const scale = Math.min(1, 1800 / Math.max(known.w, known.h))
      if (scale < 0.999) {
        bitmap = await createImageBitmap(file, {
          imageOrientation: 'from-image',
          resizeWidth: Math.max(1, Math.round(known.w * scale)),
          resizeHeight: Math.max(1, Math.round(known.h * scale)),
          resizeQuality: 'high'
        })
      } else {
        bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
      }
    } else {
      bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
    }
  } catch {
    bitmap = await createImageBitmap(file)
  }
  onPhase?.('生成预览', 0.72)
  const preview = await makePreviewFromBitmap(bitmap)
  const originalW = known?.w ?? bitmap.width
  const originalH = known?.h ?? bitmap.height
  bitmap.close()
  const exif = normalizeExif(raw, originalW, originalH, file.name)
  onPhase?.('准备完成', 1)
  return {
    id: uid(),
    name: file.name,
    source: { kind: 'file', file },
    previewUrl: preview.url,
    previewW: preview.canvas.width,
    previewH: preview.canvas.height,
    width: originalW,
    height: originalH,
    exif,
    bytes: file.size
  }
}

export function photoFromPath(result: PreviewImageResult, path: string): PhotoAsset {
  const copy = new Uint8Array(result.data)
  const blob = new Blob([copy.buffer as ArrayBuffer], { type: 'image/jpeg' })
  const url = URL.createObjectURL(blob)
  const exif = normalizeExif(result.exif, result.width, result.height, result.name)
  return {
    id: uid(),
    name: result.name,
    source: { kind: 'path', path },
    previewUrl: url,
    previewW: result.previewWidth,
    previewH: result.previewHeight,
    width: result.width,
    height: result.height,
    exif,
    bytes: result.bytes
  }
}

export async function photoFromUrl(url: string, name: string): Promise<PhotoAsset> {
  const img = await loadImage(url)
  const canvas = createCanvas(img.naturalWidth, img.naturalHeight)
  const ctx = canvas.getContext('2d')!
  ctx.drawImage(img, 0, 0)
  const blob = await canvasToBlob(canvas, 'image/jpeg', 0.95)
  const file = new File([blob], name, { type: 'image/jpeg' })
  return photoFromFile(file)
}

export async function loadFullBitmap(asset: PhotoAsset): Promise<{
  image: ImageBitmap
  width: number
  height: number
  bytes?: Uint8Array
}> {
  let bytes: Uint8Array
  if (asset.source.kind === 'path') {
    if (!window.framemark) throw new Error('桌面文件接口不可用')
    const r = await window.framemark.readImage(asset.source.path)
    bytes = r.data
  } else if (asset.source.kind === 'file') {
    bytes = new Uint8Array(await asset.source.file.arrayBuffer())
  } else {
    throw new Error('示例图不可导出，请先添加照片')
  }
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  const blob = new Blob([copy.buffer as ArrayBuffer])
  const image = await createImageBitmap(blob, { imageOrientation: 'from-image' })
  return { image, width: image.width, height: image.height, bytes: copy }
}

export async function imageAssetFromFile(file: File): Promise<{ id: string; name: string; dataUrl: string; width: number; height: number }> {
  const dataUrl = await blobToDataUrl(file)
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' })
  const result = { id: uid(), name: file.name, dataUrl, width: bmp.width, height: bmp.height }
  bmp.close()
  return result
}

export function revokePhoto(asset: PhotoAsset): void {
  if (asset.previewUrl.startsWith('blob:')) URL.revokeObjectURL(asset.previewUrl)
}
