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

const TIFF_NAME = /\.tiff?$/i

/**
 * A3: a TIFF source is decodable on desktop only, and only through the main
 * process (`image:read` transcodes it to PNG). Chromium's createImageBitmap has
 * no TIFF decoder, so every renderer-side attempt fails with a raw
 * `InvalidStateError: The source image could not be decoded.` — that is not a
 * message a user can act on. These texts replace it.
 */
const TIFF_NO_PLATFORM =
  '当前平台不支持 TIFF 照片（TIFF 仅桌面版支持），请改用 JPG/PNG 重新导入后再导出。'
const TIFF_DROPPED =
  '拖入的 TIFF 照片无法解码，请用桌面「选择照片」或「从文件夹导入」（会自动转为 PNG），或改用 JPG/PNG。'
const TIFF_TRANSCODE_FAILED =
  '这张 TIFF 无法在导出前转码为 PNG，请用桌面「选择照片」或「从文件夹导入」重新添加，或改用 JPG/PNG。'

/** TIFF starts with II*\0 / MM\0* (classic) or II+\0 / MM\0+ (BigTIFF). */
function looksLikeTiff(bytes: Uint8Array): boolean {
  if (bytes.length < 4) return false
  const classicOrBig = (v: number) => v === 0x2a || v === 0x2b
  return (
    (bytes[0] === 0x49 && bytes[1] === 0x49 && classicOrBig(bytes[2]) && bytes[3] === 0x00) ||
    (bytes[0] === 0x4d && bytes[1] === 0x4d && bytes[2] === 0x00 && classicOrBig(bytes[3]))
  )
}

export async function loadFullBitmap(asset: PhotoAsset): Promise<{
  image: ImageBitmap
  width: number
  height: number
  bytes?: Uint8Array
}> {
  let bytes: Uint8Array
  let tiff = TIFF_NAME.test(asset.name)
  if (asset.source.kind === 'path') {
    if (!window.framemark) {
      if (tiff) throw new Error(TIFF_NO_PLATFORM)
      throw new Error('桌面文件接口不可用')
    }
    if (TIFF_NAME.test(asset.source.path)) tiff = true
    // The main process transcodes TIFF to PNG (src/main/index.ts image:read);
    // every other extension comes back byte-for-byte.
    const r = await window.framemark.readImage(asset.source.path)
    bytes = r.data
  } else if (asset.source.kind === 'file') {
    bytes = new Uint8Array(await asset.source.file.arrayBuffer())
    if (looksLikeTiff(bytes)) tiff = true
    if (tiff) throw new Error(window.framemark ? TIFF_DROPPED : TIFF_NO_PLATFORM)
  } else {
    throw new Error('示例图不可导出，请先添加照片')
  }
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  const blob = new Blob([copy.buffer as ArrayBuffer])
  let image: ImageBitmap
  try {
    image = await createImageBitmap(blob, { imageOrientation: 'from-image' })
  } catch (e) {
    if (tiff) throw new Error(window.framemark ? TIFF_TRANSCODE_FAILED : TIFF_NO_PLATFORM)
    throw e
  }
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
