import { Capacitor } from '@capacitor/core'
import piexif from 'piexifjs'
import type { Design, PhotoAsset } from '../types'
import { getTemplate } from './templates'
import { getVariant } from './variants'
import { loadFullBitmap } from './photos'
import { renderDesign } from './scene'
import { canvasToArrayBuffer, canvasToBlob, createCanvas } from './images'
import { formatDateOnly } from './exif'
import { blobToBase64, GallerySaver } from './gallery'

const isAndroid = Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'

export interface ExportResult {
  path: string
  width: number
  height: number
  bytes: number
}

export interface BatchOptions {
  photos: PhotoAsset[]
  design: Design
  presetName?: string
  onProgress?: (done: number, total: number, current: string) => void
  isCancelled?: () => boolean
}

export interface BatchResult {
  results: ExportResult[]
  errors: { name: string; message: string }[]
  cancelled: boolean
}

function pad(n: number, len = 2): string {
  return String(n).padStart(len, '0')
}

function extension(format: Design['export']['format']): string {
  return format === 'jpeg' ? '.jpg' : format === 'tiff' ? '.tiff' : `.${format}`
}

function sanitizeName(s: string): string {
  return s.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '-').replace(/\s+/g, ' ').trim().slice(0, 120) || 'framemark'
}

function buildName(photo: PhotoAsset, design: Design, index: number, presetName?: string): string {
  const variant = design.variantId ? getVariant(design.variantId) : null
  const templateName = variant?.name ?? getTemplate(design.templateId).name
  const base = photo.name.replace(/\.[^.]+$/, '')
  const exifDate = photo.exif.dateTime ? new Date(photo.exif.dateTime.replace(' ', 'T')) : new Date()
  const date = Number.isNaN(exifDate.getTime()) ? new Date() : exifDate
  const map: Record<string, string> = {
    name: base,
    template: templateName,
    preset: presetName || templateName,
    date: `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`,
    time: `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`,
    index: pad(index + 1, 3),
    make: photo.exif.make,
    model: photo.exif.model,
    width: String(photo.width),
    height: String(photo.height)
  }
  let out = design.export.filenamePattern || '{name}-{template}'
  out = out.replace(/\{(\w+)\}/g, (_m, k: string) => map[k] ?? '')
  return sanitizeName(out)
}

function bytesToBinary(bytes: Uint8Array): string {
  let out = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    out += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunk)) as unknown as number[])
  }
  return out
}

function binaryToBytes(bin: string): Uint8Array {
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 0xff
  return out
}

function isJpegName(name: string): boolean {
  return /\.(jpe?g)$/i.test(name)
}

function injectExifOnDevice(jpeg: Blob, sourceBytes: Uint8Array | undefined, photo: PhotoAsset, width: number, height: number, removeGps: boolean): Promise<Blob> {
  return (async () => {
    if (!sourceBytes || !isJpegName(photo.name)) return jpeg
    try {
      const exifObj = piexif.load(bytesToBinary(sourceBytes))
      exifObj['0th'] = exifObj['0th'] || {}
      exifObj['Exif'] = exifObj['Exif'] || {}
      exifObj['0th'][piexif.ImageIFD.Orientation] = 1
      exifObj['0th'][piexif.ImageIFD.ImageWidth] = width
      exifObj['0th'][piexif.ImageIFD.ImageLength] = height
      exifObj['Exif'][piexif.ExifIFD.PixelXDimension] = width
      exifObj['Exif'][piexif.ExifIFD.PixelYDimension] = height
      if (removeGps) exifObj['GPS'] = {}
      const jpegBytes = new Uint8Array(await jpeg.arrayBuffer())
      const inserted = piexif.insert(piexif.dump(exifObj), bytesToBinary(jpegBytes))
      return new Blob([binaryToBytes(inserted).buffer as ArrayBuffer], { type: 'image/jpeg' })
    } catch {
      return jpeg
    }
  })()
}

async function exportAndroid(
  canvas: HTMLCanvasElement,
  photo: PhotoAsset,
  design: Design,
  fullBytes: Uint8Array | undefined,
  name: string
): Promise<ExportResult> {
  const format = design.export.format === 'tiff' ? 'jpeg' : design.export.format
  const mime = format === 'jpeg' ? 'image/jpeg' : format === 'png' ? 'image/png' : 'image/webp'
  let blob = await canvasToBlob(canvas, mime, format === 'jpeg' ? design.export.quality / 100 : 1)
  if (format === 'jpeg') {
    blob = await injectExifOnDevice(blob, fullBytes, photo, canvas.width, canvas.height, design.export.removeGps)
  }
  const base64 = await blobToBase64(blob)
  const ext = extension(format)
  const result = await GallerySaver.saveImage({ base64, fileName: `${name}${ext}`, mimeType: mime })
  return { path: result.uri, width: canvas.width, height: canvas.height, bytes: blob.size }
}

function downloadBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 3000)
}

/**
 * Raw IPC errors (ENOTDIR/EEXIST/EACCES with an internal path) are accurate but
 * not actionable for the user. Keep the cause class, drop the stack/path noise.
 */
export function friendlyExportError(message: string): string {
  if (/ENOTDIR|EEXIST|ENOENT/.test(message)) return '输出文件夹不可用或不是一个文件夹，请在导出设置里重新选择'
  if (/EACCES|EPERM/.test(message)) return '没有写入权限，请换一个输出文件夹'
  if (/ENOSPC/.test(message)) return '磁盘空间不足，请清理后重试'
  if (/示例图不可导出/.test(message)) return message
  const clean = message.replace(/^Error invoking remote method '[^']+':s*/, '').replace(/^Error:s*/, '')
  return clean.length > 160 ? clean.slice(0, 160) + '…' : clean
}

export async function exportOne(
  photo: PhotoAsset,
  design: Design,
  index: number,
  presetName?: string
): Promise<ExportResult> {
  if (photo.source.kind === 'sample') throw new Error('示例图不可导出')
  const full = await loadFullBitmap(photo)
  try {
    const canvas = document.createElement('canvas')
    const rendered = await renderDesign(canvas, design, photo, full.image, full.width, full.height, { preview: false })
    let out = canvas
    const longEdge = Number(design.export.longEdge) || 0
    if (longEdge > 0 && Math.max(rendered.W, rendered.H) > longEdge) {
      const scale = longEdge / Math.max(rendered.W, rendered.H)
      const scaled = createCanvas(rendered.W * scale, rendered.H * scale)
      const sctx = scaled.getContext('2d', { alpha: false })!
      sctx.imageSmoothingQuality = 'high'
      sctx.drawImage(canvas, 0, 0, scaled.width, scaled.height)
      out = scaled
    }
    const baseName = buildName(photo, design, index, presetName)
    if (!isAndroid && window.framemark) {
      let outputDir = design.export.outputDir
      if (!outputDir) outputDir = await window.framemark.defaultExportDir()
      const png = await canvasToArrayBuffer(out, 'image/png')
      const source = photo.source
      const result = await window.framemark.exportImage({
        png,
        format: design.export.format,
        quality: design.export.quality,
        outputDir,
        filename: baseName,
        longEdge,
        removeGps: design.export.removeGps,
        width: out.width,
        height: out.height,
        sourcePath: source.kind === 'path' ? source.path : undefined,
        sourceBytes:
          source.kind === 'file' && isJpegName(photo.name) && full.bytes && full.bytes.length < 120 * 1024 * 1024
            ? full.bytes
            : undefined,
        fallbackExif: photo.exif.raw
      })
      return result
    }
    if (isAndroid) {
      return await exportAndroid(out, photo, design, full.bytes, baseName)
    }
    const mime = design.export.format === 'jpeg' ? 'image/jpeg' : design.export.format === 'png' ? 'image/png' : 'image/webp'
    const blob = await canvasToBlob(out, mime, design.export.quality / 100)
    const name = `${baseName}${extension(design.export.format)}`
    downloadBlob(blob, name)
    return { path: name, width: out.width, height: out.height, bytes: blob.size }
  } finally {
    full.image.close()
  }
}

export async function exportBatch(options: BatchOptions): Promise<BatchResult> {
  const { photos, design, presetName, onProgress, isCancelled } = options
  const results: ExportResult[] = []
  const errors: { name: string; message: string }[] = []
  let cancelled = false
  for (let i = 0; i < photos.length; i++) {
    if (isCancelled?.()) {
      cancelled = true
      break
    }
    const photo = photos[i]
    onProgress?.(i, photos.length, photo.name)
    try {
      const r = await exportOne(photo, design, i, presetName)
      results.push(r)
    } catch (e) {
      errors.push({ name: photo.name, message: friendlyExportError(e instanceof Error ? e.message : String(e)) })
    }
    onProgress?.(i + 1, photos.length, photo.name)
    await new Promise((r) => setTimeout(r, 16))
  }
  return { results, errors, cancelled }
}

export function exportDateLabel(design: Design): string {
  return formatDateOnly(new Date())
}
