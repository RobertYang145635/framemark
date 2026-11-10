import { Capacitor } from '@capacitor/core'
import piexif from 'piexifjs'
import type { Design, PhotoAsset } from '../types'
import { getTemplate } from './templates'
import { getVariant } from './variants'
import { loadFullBitmap } from './photos'
import { renderDesign, prepareScene, paintScene } from './scene'
import { canvasToArrayBuffer, canvasToBlob, createCanvas } from './images'
import { ENCODE_PROFILE, ANDROID_WEBP_LOSSY, webpBitstreamKind } from './encode-profile'
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
  /**
   * S3: task keys parallel to `photos` (one per photo). When present the batch
   * reports per-item outcomes so the caller can persist a resumable task.
   */
  keys?: string[]
  /**
   * S3: keys the persisted task already finished. Those photos are never
   * re-exported (a re-export would land as `(1)`), but they still advance
   * `onProgress` so the bar covers the whole task.
   */
  skip?: Set<string>
  onProgress?: (done: number, total: number, current: string) => void
  /**
   * Awaited when it returns a promise: the caller persists the per-item outcome
   * BEFORE the next photo starts, so a kill cannot leave a written file without
   * its history row (which would re-export as `(1)`).
   */
  onItemDone?: (key: string, result: ExportResult) => void | Promise<void>
  onItemError?: (key: string, message: string) => void | Promise<void>
  isCancelled?: () => boolean
}

export interface BatchResult {
  results: ExportResult[]
  errors: { name: string; message: string }[]
  cancelled: boolean
  /** Items recognised as already exported and therefore not written again. */
  skipped: number
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
  // S8: one policy for both ends. The numbers and their measured justification
  // live in core/encode-profile.ts; this function only applies them.
  const rule = ENCODE_PROFILE.formats[design.export.format]
  if (!rule) throw new Error(`不支持的导出格式：${String(design.export.format)}`)
  if (rule.android === 'unsupported') throw new Error(rule.androidMessage)
  // The policy says exportOne already applied longEdge before this branch.
  // Verify it here so a regression cannot silently export an oversized image.
  const longEdge = Number(design.export.longEdge) || 0
  if (ENCODE_PROFILE.longEdge.appliedBeforeEncode && longEdge > 0 && Math.max(canvas.width, canvas.height) > longEdge) {
    throw new Error('长边限制未在编码前应用（内部错误）')
  }
  const quality =
    rule.androidQuality === 'design'
      ? Math.min(1, Math.max(0, design.export.quality / 100))
      : rule.androidQuality ?? undefined
  let blob = await canvasToBlob(canvas, rule.mime, quality)
  if (rule.lossless && rule.mime === 'image/webp') {
    // Measured VP8L at quality 1, but the device's WebView is not this
    // Chromium. Refuse a lossy bitstream instead of shipping it silently.
    if ((await webpBitstreamKind(blob)) === 'lossy') throw new Error(ANDROID_WEBP_LOSSY)
  }
  if (rule.exif) {
    const removeGps =
      typeof design.export.removeGps === 'boolean' ? design.export.removeGps : ENCODE_PROFILE.gps.removeByDefault
    blob = await injectExifOnDevice(blob, fullBytes, photo, canvas.width, canvas.height, removeGps)
  }
  const base64 = await blobToBase64(blob)
  const result = await GallerySaver.saveImage({ base64, fileName: `${name}${rule.extension}`, mimeType: rule.mime })
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
  const clean = message.replace(/^Error invoking remote method '[^']+':\s*/, '').replace(/^Error:\s*/, '')
  return clean.length > 160 ? clean.slice(0, 160) + '…' : clean
}

type RenderOut = Awaited<ReturnType<typeof renderDesign>>

const BAND_MIN = 256
const BAND_MAX = 8192

/**
 * S6b overlap margin: each band is painted into a strip that also holds M extra
 * rows above and below it; only the band's own rows are copied into the full
 * canvas. M=64 is 5x the widest boundary-local footprint measured in
 * _research/s6b/mech-probe (white-2's 4-px path edge at row 2048, which spans 6
 * rows either side of the seam): with M=64 white-2 went from 38 maxAbs to 0.
 * It costs 2*M extra rows per strip.
 *
 * It does NOT help the surface-height-dependent classes (see BAND_UNSAFE_IDS):
 * those differ over the whole affected draw, not only at the seam, and stay
 * different at M = 0..256.
 */
const BAND_MARGIN = 64

/**
 * S6b: templates that must be painted in ONE full-stage pass.
 *
 * The S6a inventory split the seams into three classes; the S6b mechanism
 * probes (_research/s6b/mech-probe/result*.json) showed two of them are the
 * same Chromium property: canvas2d rasterizer output depends on the HEIGHT of
 * the destination surface, over the whole affected draw, not just at the seam.
 *   - ctx.filter = blur(...) : 5M of 12M px differ at band 512, rows 0..H;
 *                              a full-height pre-paint matches exactly, a
 *                              band-sized surface never does (default OR
 *                              willReadFrequently).
 *   - shadow* on a drawImage : ±1 over the whole shadowed image (21k-45k px),
 *                              unchanged by M = 0..256.
 *   - clip + drawImage AA    : ±1 over the clipped image area (ticket-1,
 *                              stock IR), unchanged by M.
 * The third class, a 1-px AA pair ON a seam (white-2), IS absorbed by M=64 and
 * stays banded.
 *
 * The opt-in field is TemplateDef.bandUnsafe; the ids below cover templates
 * declared in core/templates.ts (S6b may only touch its blur blocks) and the
 * stock painter in core/system/template.ts (out of scope). Both are checked.
 */
const BAND_UNSAFE_IDS = new Set<string>([
  'blur', // draw.ts drawBlurredBackdrop: ctx.filter = blur()
  'glass', // templates.ts:3211 clipped card, ctx.filter = blur()
  'glassPalettePortrait', // templates.ts:3466 clipped panel, ctx.filter = blur()
  'glassSideCard', // drawBlurredBackdrop (same filter class as blur)
  'profileFrame', // shadow gradient around the photo, ±1..6 across its full width
  'sideTicket', // clipped photo + card shadow: ±1 over the whole card
  'film', // sprocket-hole / glyph edges, maxAbs 28, away from any seam
  'systemCamera' // stock IR painter: clipped crop/path ops the rasterizer varies
])

/** S6a: `flags.band` (preload, from FRAMEMARK_BAND), default 1024, clamp 256..8192. */
function bandHeightOf(flags: { band?: unknown } | undefined): number {
  const raw = Number(flags?.band)
  if (!Number.isFinite(raw) || raw <= 0) return 1024
  return Math.min(BAND_MAX, Math.max(BAND_MIN, Math.round(raw)))
}

/** The S5 harness's in-page op recorder (`window.__fmOps`), when it is installed. */
interface OpRecorder {
  reset(opts: { yOffset: number }): void
  stop?: () => void
}

/**
 * S6a banded paint of ONE export: prepare once, then paint ceil(H/band) strips
 * into their own canvases and copy each into the full-size canvas. The caller
 * gets back the same shape `renderDesign` returns, so the longEdge path below
 * cannot tell the two apart. Every strip is released (1x1) right after the copy.
 *
 * `seamCanary` drops the grain overlay in exactly one band (index 1). It exists
 * only to prove the gate can see a seam; it is never enabled by default.
 */
async function paintBanded(
  canvas: HTMLCanvasElement,
  design: Design,
  photo: PhotoAsset,
  image: CanvasImageSource,
  imageW: number,
  imageH: number,
  flags: { seamCanary?: unknown; band?: unknown }
): Promise<RenderOut> {
  const scene = await prepareScene(design, photo, image, imageW, imageH, { preview: false })
  // S6b: a template whose painter is not surface-size invariant is painted in
  // ONE pass over the whole canvas. Pixel-identical or not banded — no tolerance.
  if (scene.template.bandUnsafe === true || BAND_UNSAFE_IDS.has(scene.template.id)) {
    const painted = paintScene(canvas, scene)
    console.info(
      `[framemark band] ${scene.W}x${scene.H} bandUnsafe template=${scene.template.id} -> single pass`
    )
    const dbg = window as unknown as { __fmBandLog?: unknown[] }
    dbg.__fmBandLog = [
      ...(Array.isArray(dbg.__fmBandLog) ? dbg.__fmBandLog : []),
      { W: scene.W, H: scene.H, bandUnsafe: true, template: scene.template.id }
    ].slice(-50)
    return painted
  }
  const band = bandHeightOf(flags)
  canvas.width = scene.W
  canvas.height = scene.H
  const fullCtx = canvas.getContext('2d', { alpha: false })
  if (!fullCtx) throw new Error('无法创建画布')
  fullCtx.imageSmoothingEnabled = true
  fullCtx.imageSmoothingQuality = 'high'
  const canaryBand = flags.seamCanary === true ? 1 : -1
  // The op recorder may be absent (normal app run); when present, its stream is
  // band-local until it is told the band's origin, hence the reset per band.
  const recorder = (window as unknown as { __fmOps?: OpRecorder }).__fmOps
  const ys: number[] = []
  let photoRect: RenderOut['photoRect'] | null = null
  const textSlots: RenderOut['textSlots'] = []
  let resetCount = 0
  for (let y = 0; y < scene.H; y += band) {
    const h = Math.min(band, scene.H - y)
    // S6b overlap: the strip also holds M rows above/below the band so a
    // boundary-local effect (clip/path AA) is painted with its real neighbours.
    // Clamped at the canvas edges — there the unbanded pass has the same edge.
    const index = ys.length
    // The seam canary takes the overlap margin away from EVERY band: that is
    // exactly the boundary-AA defect M=64 exists to absorb (white-2: 4 px,
    // maxAbs 38 at the seam), so a green gate with the canary on would mean the
    // gate is blind. Grain suppression rides along on ONE band for templates
    // that draw the grain overlay (the original S6a canary).
    const canary = canaryBand >= 0
    const margin = canary ? 0 : BAND_MARGIN
    const s = Math.max(0, y - margin)
    const e = Math.min(scene.H, y + h + margin)
    ys.push(y)
    if (recorder && typeof recorder.reset === 'function') {
      // The strip's canvas origin is `s`, so its local y + s is the stage y.
      recorder.reset({ yOffset: s })
      resetCount++
    }
    const strip = document.createElement('canvas')
    const painted = paintScene(strip, scene, {
      band: { y: s, h: e - s },
      suppressGrain: canary && index === canaryBand
    })
    // Copy ONLY the band's own rows: the overlap rows belong to the neighbour
    // band, which paints them with its own (equally complete) neighbourhood.
    fullCtx.drawImage(strip, 0, y - s, scene.W, h, 0, y, scene.W, h)
    strip.width = 1
    strip.height = 1
    photoRect = painted.photoRect
    for (const slot of painted.textSlots) {
      if (!textSlots.some((s) => s.key === slot.key)) textSlots.push(slot)
    }
  }
  if (resetCount > 0 && recorder?.stop) recorder.stop()
  const entry = {
    W: scene.W,
    H: scene.H,
    band,
    bands: ys.length,
    ys,
    canaryBand,
    margin: canaryBand >= 0 ? 0 : BAND_MARGIN
  }
  // Evidence hook for _research/s6a (and a human reading devtools). Last 50
  // exports only: a batch of 200 photos must not accumulate a log forever.
  const dbg = window as unknown as { __fmBandLog?: unknown[] }
  dbg.__fmBandLog = [...(Array.isArray(dbg.__fmBandLog) ? dbg.__fmBandLog : []), entry].slice(-50)
  console.info(
    `[framemark band] ${scene.W}x${scene.H} band=${band} bands=${ys.length} margin=${canaryBand >= 0 ? 0 : BAND_MARGIN} ys=${ys.join(',')}${canaryBand >= 0 ? ` canary=band${canaryBand}(margin off)` : ''}`
  )
  return { W: scene.W, H: scene.H, photoRect: photoRect ?? { x: 0, y: 0, w: scene.W, h: scene.H }, textSlots }
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
    // S6a: banded painting is opt-in and desktop-only (Android never runs the
    // preload, so `flags` is undefined there). With the flag off this is the
    // exact call it always was — renderDesign keeps its signature and output.
    const flags = (
      window as unknown as {
        framemark?: { flags?: { tile?: unknown; seamCanary?: unknown; band?: unknown } }
      }
    ).framemark?.flags
    const rendered =
      flags?.tile === true && !isAndroid
        ? await paintBanded(canvas, design, photo, full.image, full.width, full.height, flags)
        : await renderDesign(canvas, design, photo, full.image, full.width, full.height, { preview: false })
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
        fallbackExif: { ...photo.exif.raw, __focal35: photo.exif.focalEquivalent === true }
      })
      return result
    }
    if (isAndroid) {
      return await exportAndroid(out, photo, design, full.bytes, baseName)
    }
    // A3: the plain-web branch has no TIFF encoder either. It used to fall
    // through to WebP while `extension()` kept `.tiff`, so the user got a WebP
    // file wearing a .tiff name. Refuse with the same explicit text the Android
    // policy uses instead of silently changing the format.
    if (design.export.format === 'tiff') throw new Error(ENCODE_PROFILE.formats.tiff.androidMessage)
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
  const { photos, design, presetName, keys, skip, onProgress, onItemDone, onItemError, isCancelled } = options
  const results: ExportResult[] = []
  const errors: { name: string; message: string }[] = []
  let cancelled = false
  let skipped = 0
  for (let i = 0; i < photos.length; i++) {
    if (isCancelled?.()) {
      cancelled = true
      break
    }
    const photo = photos[i]
    const key = keys?.[i]
    if (key && skip?.has(key)) {
      // Already exported by an earlier run of this task: counting it as progress
      // but not calling exportOne keeps the output folder free of `(1)` copies.
      skipped++
      onProgress?.(i + 1, photos.length, photo.name)
      continue
    }
    onProgress?.(i, photos.length, photo.name)
    try {
      const r = await exportOne(photo, design, i, presetName)
      results.push(r)
      if (key) await onItemDone?.(key, r)
    } catch (e) {
      const message = friendlyExportError(e instanceof Error ? e.message : String(e))
      errors.push({ name: photo.name, message })
      if (key) await onItemError?.(key, message)
    }
    onProgress?.(i + 1, photos.length, photo.name)
    await new Promise((r) => setTimeout(r, 16))
  }
  return { results, errors, cancelled, skipped }
}

export function exportDateLabel(design: Design): string {
  return formatDateOnly(new Date())
}
