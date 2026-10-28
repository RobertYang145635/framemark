import { app, BrowserWindow, dialog, ipcMain, Menu, nativeTheme, shell } from 'electron'
import path from 'node:path'
import fs from 'node:fs/promises'
import sharp from 'sharp'
import exifr from 'exifr'
import piexif from 'piexifjs'

const SRGB_B64 =
  'AAACTGxjbXMEQAAAbW50clJHQiBYWVogB+oACgAXABEAKQASYWNzcE1TRlQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPbWAAEAAAAA0y1sY21zAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAALZGVzYwAAAQgAAAA2Y3BydAAAAUAAAABMd3RwdAAAAYwAAAAUY2hhZAAAAaAAAAAsclhZWgAAAcwAAAAUYlhZWgAAAeAAAAAUZ1hZWgAAAfQAAAAUclRSQwAAAggAAAAgZ1RSQwAAAggAAAAgYlRSQwAAAggAAAAgY2hybQAAAigAAAAkbWx1YwAAAAAAAAABAAAADGVuVVMAAAAaAAAAHABzAFIARwBCACAAYgB1AGkAbAB0AC0AaQBuAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAADAAAAAcAE4AbwAgAGMAbwBwAHkAcgBpAGcAaAB0ACwAIAB1AHMAZQAgAGYAcgBlAGUAbAB5WFlaIAAAAAAAAPbWAAEAAAAA0y1zZjMyAAAAAAABDEIAAAXe///zJQAAB5MAAP2Q///7of///aIAAAPcAADAblhZWiAAAAAAAABvoAAAOPUAAAOQWFlaIAAAAAAAACSfAAAPhAAAtsNYWVogAAAAAAAAYpcAALeHAAAY2XBhcmEAAAAAAAMAAAACZmYAAPKnAAANWQAAE9AAAApbY2hybQAAAAAAAwAAAACj1wAAVHsAAEzNAACZmgAAJmYAAA9c'

const IMAGE_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp', '.tif', '.tiff', '.heic', '.heif'])
let mainWindow: BrowserWindow | null = null
let srgbPath: string | null = null

async function getSrgbPath(): Promise<string> {
  if (!srgbPath) {
    const p = path.join(app.getPath('temp'), 'framemark-srgb.icc')
    await fs.writeFile(p, Buffer.from(SRGB_B64, 'base64'))
    srgbPath = p
  }
  return srgbPath
}

/**
 * S8: the desktop half of the ONE encode policy. Mirrors
 * `src/renderer/src/core/encode-profile.ts` → ENCODE_PROFILE (the renderer and
 * the main process are separate bundles with no shared import here, so the
 * values are duplicated). `scripts/_s8-policy-check.cjs` parses both marker
 * blocks and fails on drift; the measured basis is
 * `_research/s8/encoder-facts.json`.
 *
 * Do not tune these numbers to chase byte equality with Android: at q100 the
 * JPEG scan is already byte-identical; the remaining differences are metadata
 * and Chromium's own choices (see _research/handoff/S8.md GAP list).
 */
/* S8-POLICY-BEGIN */
const EXPORT_ENCODE = {
  jpeg: { quality: 'design', subsampling: '4:4:4', subsamplingAndroid: 'chromium-uncontrolled', lossless: false, icc: 'sRGB', iccAndroid: 'embedded-by-encoder' },
  png: { quality: null, subsampling: 'none', subsamplingAndroid: 'none', lossless: true, icc: 'sRGB', iccAndroid: 'absent', compressionLevel: 9, adaptiveFiltering: true },
  webp: { quality: null, subsampling: 'none', subsamplingAndroid: 'none', lossless: true, icc: 'sRGB', iccAndroid: 'embedded-by-encoder', effort: 5 },
  tiff: { quality: null, subsampling: 'none', subsamplingAndroid: 'none', lossless: true, icc: 'sRGB', iccAndroid: 'n/a', compression: 'lzw', predictor: 'horizontal' }
} as const
/* S8-POLICY-END */

function isImage(file: string): boolean {
  return IMAGE_EXT.has(path.extname(file).toLowerCase())
}

function isJpeg(name: string): boolean {
  const e = path.extname(name).toLowerCase()
  return e === '.jpg' || e === '.jpeg'
}

async function parseExif(source: string | Buffer): Promise<Record<string, unknown> | null> {
  try {
    const opts: any = {
      tiff: true,
      exif: true,
      gps: true,
      ifd0: true,
      interop: true,
      translateValues: true,
      reviveValues: true,
      mergeOutput: true
    }
    const out = await exifr.parse(source as any, opts)
    return (out as Record<string, unknown>) ?? null
  } catch {
    return null
  }
}

/**
 * Folder-import ceilings (PRODUCT-DEFECTS #58 / defect 1).
 *
 * The old scanner hard-coded 500 files + recursion depth 3 and silently returned a
 * shortened batch, so a photography folder with a few thousand frames lost most of its
 * photos without a word. The ceilings are now named constants with generous defaults,
 * overridable per call (`folder:listImages` options) or process-wide (env vars), and
 * every ceiling that actually bites is counted and reported back to the renderer, which
 * must surface it — a truncated import can never be silent again.
 */
const IMPORT_MAX_FILES_DEFAULT = 5000
const IMPORT_MAX_DEPTH_DEFAULT = 8
/** Safety valve for pathological trees: stop walking after this many directory entries. */
const SCAN_ENTRY_GUARD = 200_000

/**
 * Runtime feature flags (S5 tiling gate).
 *
 * Same shape as the import ceilings above: the main process is the only place
 * that reads the environment, at module load, and the preload mirrors the result
 * into `window.framemark.flags`. `'1'` is the only truthy spelling — every other
 * value (unset, `''`, `'0'`, `'true'`) reads as off, so an A run can prove that
 * a non-`'1'` value really is inert.
 *
 * `tile`       — S6 will paint the export canvas in horizontal bands.
 * `seamCanary` — S6 must deliberately skip the grain overlay (draw.ts:588) on
 *                one band so the S5 gate can prove it goes red.
 *
 * Neither flag is consumed by the renderer yet (S5 is the instrument only). On
 * Android there is no preload bridge at all, so `window.framemark` is undefined
 * and both flags are inert there by construction.
 */
const RUNTIME_FLAGS = {
  tile: process.env['FRAMEMARK_TILE'] === '1',
  seamCanary: process.env['FRAMEMARK_SEAM_CANARY'] === '1'
}

interface ImportOptions {
  maxFiles?: number
  maxDepth?: number
}

interface FolderScanResult {
  /** Images handed to the renderer, in the same pre-order traversal as before. */
  files: string[]
  /** The ceilings that were in force for this scan (so the UI can quote them). */
  maxFiles: number
  maxDepth: number
  /** Image files found but NOT returned because `maxFiles` was reached. */
  droppedFiles: number
  /** Sub-directories found but NOT entered because `maxDepth` was reached. */
  skippedDirs: number
  /** True when the safety valve stopped the walk early (counts are then a floor). */
  guardHit: boolean
}

function positiveInt(value: unknown, fallback: number): number {
  const n = typeof value === 'string' ? Number(value) : typeof value === 'number' ? value : NaN
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallback
}

function resolveImportLimits(options?: ImportOptions): { maxFiles: number; maxDepth: number } {
  const envFiles = positiveInt(process.env['FRAMEMARK_IMPORT_MAX_FILES'], Number.NaN)
  const envDepth = positiveInt(process.env['FRAMEMARK_IMPORT_MAX_DEPTH'], Number.NaN)
  return {
    maxFiles: positiveInt(options?.maxFiles, positiveInt(envFiles, IMPORT_MAX_FILES_DEFAULT)),
    maxDepth: positiveInt(options?.maxDepth, positiveInt(envDepth, IMPORT_MAX_DEPTH_DEFAULT))
  }
}

function emptyScanResult(limits: { maxFiles: number; maxDepth: number }): FolderScanResult {
  return { files: [], maxFiles: limits.maxFiles, maxDepth: limits.maxDepth, droppedFiles: 0, skippedDirs: 0, guardHit: false }
}

async function scanImages(root: string, options?: ImportOptions): Promise<FolderScanResult> {
  const limits = resolveImportLimits(options)
  const result = emptyScanResult(limits)
  let visitedEntries = 0

  const walk = async (dir: string, depth: number): Promise<void> => {
    let entries: import('node:fs').Dirent[] = []
    try {
      entries = await fs.readdir(dir, { withFileTypes: true }) as import('node:fs').Dirent[]
    } catch {
      return
    }
    for (const e of entries) {
      if (result.guardHit) return
      if (++visitedEntries > SCAN_ENTRY_GUARD) {
        result.guardHit = true
        return
      }
      if (e.name.startsWith('.')) continue
      const full = path.join(dir, e.name)
      if (e.isDirectory()) {
        // Depth is counted in nesting levels below the picked folder: the picked folder
        // itself is level 0, so maxDepth = 8 walks 8 levels of sub-folders and reports
        // every directory deeper than that instead of skipping it in silence.
        if (depth + 1 > limits.maxDepth) {
          result.skippedDirs++
          continue
        }
        await walk(full, depth + 1)
      } else if (e.isFile() && isImage(e.name)) {
        if (result.files.length < limits.maxFiles) result.files.push(full)
        else result.droppedFiles++
      }
    }
  }

  await walk(root, 0)
  return result
}

function sanitizeFilename(name: string): string {
  return (
    name
      .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '-')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 120) || 'framemark'
  )
}

async function uniqueOutputPath(dir: string, base: string, ext: string): Promise<string> {
  for (let i = 0; i < 9999; i++) {
    const suffix = i === 0 ? '' : `-${i}`
    const p = path.join(dir, `${base}${suffix}${ext}`)
    try {
      await fs.access(p)
    } catch {
      return p
    }
  }
  throw new Error('无法生成唯一文件名')
}

function buildFallbackExif(fallback: any, width: number, height: number) {
  const zeroth: Record<number, unknown> = {}
  const exif: Record<number, unknown> = {}
  if (fallback?.make) zeroth[piexif.ImageIFD.Make] = String(fallback.make)
  if (fallback?.model) zeroth[piexif.ImageIFD.Model] = String(fallback.model)
  if (fallback?.dateTime) zeroth[piexif.ImageIFD.DateTime] = String(fallback.dateTime).replace(/-/g, ':')
  if (fallback?.dateTime) exif[piexif.ExifIFD.DateTimeOriginal] = String(fallback.dateTime).replace(/-/g, ':')
  if (fallback?.lens) exif[piexif.ExifIFD.LensModel] = String(fallback.lens)
  if (Number.isFinite(fallback?.aperture)) exif[piexif.ExifIFD.FNumber] = [Math.round(Number(fallback.aperture) * 100), 100]
  if (Number.isFinite(fallback?.focal)) {
    // A 35mm-equivalent reading must never be written into the physical tag
    // (0x920A) — that would corrupt the user's metadata on re-export.
    const value = [Math.round(Number(fallback.focal) * 100), 100]
    if (fallback.__focal35) exif[piexif.ExifIFD.FocalLengthIn35mmFilm] = value
    else exif[piexif.ExifIFD.FocalLength] = value
  }
  if (Number.isFinite(fallback?.iso)) exif[piexif.ExifIFD.ISOSpeedRatings] = Math.round(Number(fallback.iso))
  if (Number.isFinite(fallback?.shutter)) {
    const s = Number(fallback.shutter)
    exif[piexif.ExifIFD.ExposureTime] = s < 1 ? [1, Math.round(1 / s)] : [Math.round(s * 100), 100]
  }
  exif[piexif.ExifIFD.PixelXDimension] = width
  exif[piexif.ExifIFD.PixelYDimension] = height
  zeroth[piexif.ImageIFD.ImageWidth] = width
  zeroth[piexif.ImageIFD.ImageLength] = height
  zeroth[piexif.ImageIFD.Orientation] = 1
  return { '0th': zeroth, Exif: exif, GPS: {}, Interop: {}, '1st': {}, thumbnail: null }
}

function injectJpegExif(jpeg: Buffer, source: Buffer | null, fallback: any, width: number, height: number, removeGps: boolean): Buffer {
  try {
    let exifObj: any
    if (source) {
      try {
        exifObj = piexif.load(source.toString('binary'))
      } catch {
        exifObj = null
      }
    }
    if (!exifObj) exifObj = buildFallbackExif(fallback, width, height)
    exifObj['0th'] = exifObj['0th'] || {}
    exifObj['Exif'] = exifObj['Exif'] || {}
    exifObj['0th'][piexif.ImageIFD.Orientation] = 1
    exifObj['0th'][piexif.ImageIFD.ImageWidth] = width
    exifObj['0th'][piexif.ImageIFD.ImageLength] = height
    exifObj['Exif'][piexif.ExifIFD.PixelXDimension] = width
    exifObj['Exif'][piexif.ExifIFD.PixelYDimension] = height
    if (removeGps) exifObj['GPS'] = {}
    const dumped = piexif.dump(exifObj)
    const inserted = piexif.insert(dumped, jpeg.toString('binary'))
    return Buffer.from(inserted, 'binary')
  } catch (err) {
    console.error('[exif] inject failed', err)
    return jpeg
  }
}

async function createWindow(): Promise<void> {
  mainWindow = new BrowserWindow({
    width: 1480,
    height: 940,
    minWidth: 1024,
    minHeight: 680,
    show: false,
    backgroundColor: '#0c0c0d',
    title: '框印 FrameMark',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true,
      backgroundThrottling: false
    }
  })

  mainWindow.once('ready-to-show', () => mainWindow?.show())

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  mainWindow.webContents.on('will-navigate', (e, url) => {
    const dev = process.env['ELECTRON_RENDERER_URL']
    if (dev && url.startsWith(dev)) return
    if (url.startsWith('file://')) return
    e.preventDefault()
  })

  if (process.env['ELECTRON_RENDERER_URL']) {
    await mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    await mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'))
  }
}

function registerIpc(): void {
  // Synchronous by design: the preload has to expose `flags` as a plain object
  // at load time. Two booleans cannot deadlock, and the handler is registered
  // in `app.whenReady()` before any window (hence any preload) exists.
  ipcMain.on('app:flags', (e) => {
    e.returnValue = RUNTIME_FLAGS
  })

  ipcMain.handle('dialog:pickImages', async () => {
    const r = await dialog.showOpenDialog({
      title: '选择照片',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: '照片', extensions: ['jpg', 'jpeg', 'png', 'webp', 'tif', 'tiff', 'heic', 'heif'] }]
    })
    return r.canceled ? [] : r.filePaths
  })

  ipcMain.handle('dialog:pickFolder', async () => {
    const r = await dialog.showOpenDialog({ title: '选择照片文件夹', properties: ['openDirectory'] })
    return r.canceled ? null : r.filePaths[0] ?? null
  })

  ipcMain.handle('folder:listImages', async (_e, folder: string, options?: ImportOptions) => {
    const limits = resolveImportLimits(options)
    if (typeof folder !== 'string' || !folder) return emptyScanResult(limits)
    return scanImages(folder, options)
  })

  ipcMain.handle('image:preview', async (_e, filePath: string) => {
    if (typeof filePath !== 'string' || !filePath) throw new Error('无效路径')
    const stat = await fs.stat(filePath)
    const meta = await sharp(filePath).metadata()
    const orientation = meta.orientation ?? 1
    const swap = [5, 6, 7, 8].includes(orientation)
    const width = (swap ? meta.height : meta.width) ?? 0
    const height = (swap ? meta.width : meta.height) ?? 0
    const { data, info } = await sharp(filePath)
      .rotate()
      .resize({ width: 2400, height: 2400, fit: 'inside', withoutEnlargement: true })
      .toColourspace('srgb')
      .jpeg({ quality: 90, chromaSubsampling: '4:4:4' })
      .toBuffer({ resolveWithObject: true })
    const exif = await parseExif(filePath)
    return {
      data: new Uint8Array(data),
      name: path.basename(filePath),
      bytes: stat.size,
      width,
      height,
      previewWidth: info.width,
      previewHeight: info.height,
      exif
    }
  })

  ipcMain.handle('image:read', async (_e, filePath: string) => {
    if (typeof filePath !== 'string' || !filePath) throw new Error('无效路径')
    const stat = await fs.stat(filePath)
    if (stat.size > 400 * 1024 * 1024) throw new Error('文件超过 400MB')
    const data = await fs.readFile(filePath)
    return { data: new Uint8Array(data), name: path.basename(filePath) }
  })

  ipcMain.handle('export:defaultDir', async () => {
    const dir = path.join(app.getPath('pictures'), 'FrameMark')
    await fs.mkdir(dir, { recursive: true })
    return dir
  })

  ipcMain.handle('preset:saveFile', async (_e, args: { content: string; filename: string }) => {
    if (typeof args?.content !== 'string') throw new Error('无效的预设内容')
    const dir = path.join(app.getPath('downloads'), 'FrameMark')
    await fs.mkdir(dir, { recursive: true })
    const base = sanitizeFilename(args.filename || 'framemark-presets').replace(/\.json$/i, '')
    const outPath = await uniqueOutputPath(dir, base, '.json')
    await fs.writeFile(outPath, args.content, 'utf8')
    return outPath
  })

  ipcMain.handle('export:pickDir', async () => {
    const r = await dialog.showOpenDialog({ title: '选择导出目录', properties: ['openDirectory', 'createDirectory'] })
    return r.canceled ? null : r.filePaths[0] ?? null
  })

  ipcMain.handle('export:showInFolder', async (_e, filePath: string) => {
    if (typeof filePath === 'string' && filePath) shell.showItemInFolder(filePath)
  })

  ipcMain.handle('app:info', async () => ({
    version: app.getVersion(),
    platform: process.platform,
    packaged: app.isPackaged
  }))

  ipcMain.handle(
    'export:save',
    async (
      _e,
      args: {
        png: Uint8Array
        format: 'jpeg' | 'png' | 'webp' | 'tiff'
        quality: number
        outputDir: string
        filename: string
        longEdge: number
        removeGps: boolean
        width: number
        height: number
        sourcePath?: string
        sourceBytes?: Uint8Array
        fallbackExif?: Record<string, unknown>
      }
    ) => {
      const png = Buffer.from(args.png)
      let pipeline = sharp(png)
      const meta = await pipeline.metadata()
      const w = meta.width ?? args.width
      const h = meta.height ?? args.height
      if (args.longEdge > 0 && Math.max(w, h) > args.longEdge) {
        pipeline = pipeline.resize({
          width: w >= h ? args.longEdge : undefined,
          height: h > w ? args.longEdge : undefined,
          fit: 'inside',
          withoutEnlargement: true
        })
      }
      const icc = await getSrgbPath()
      pipeline = pipeline.withMetadata({ icc })
      let ext = '.jpg'
      let encoded: Buffer
      if (args.format === 'jpeg') {
        const r = await pipeline.jpeg({ quality: Math.round(args.quality), chromaSubsampling: EXPORT_ENCODE.jpeg.subsampling }).toBuffer({ resolveWithObject: true })
        encoded = r.data
        ext = '.jpg'
        let source: Buffer | null = null
        if (args.sourcePath && isJpeg(args.sourcePath)) {
          try {
            const st = await fs.stat(args.sourcePath)
            if (st.size < 120 * 1024 * 1024) source = await fs.readFile(args.sourcePath)
          } catch {
            source = null
          }
        } else if (args.sourceBytes && args.sourceBytes.length > 0) {
          source = Buffer.from(args.sourceBytes)
        }
        encoded = injectJpegExif(encoded, source, args.fallbackExif ?? {}, r.info.width, r.info.height, args.removeGps !== false)
      } else if (args.format === 'png') {
        encoded = await pipeline
          .png({ compressionLevel: EXPORT_ENCODE.png.compressionLevel, adaptiveFiltering: EXPORT_ENCODE.png.adaptiveFiltering })
          .toBuffer()
        ext = '.png'
      } else if (args.format === 'webp') {
        encoded = await pipeline.webp({ lossless: EXPORT_ENCODE.webp.lossless, effort: EXPORT_ENCODE.webp.effort }).toBuffer()
        ext = '.webp'
      } else {
        encoded = await pipeline
          .tiff({ compression: EXPORT_ENCODE.tiff.compression, predictor: EXPORT_ENCODE.tiff.predictor })
          .toBuffer()
        ext = '.tiff'
      }
      const outDir = args.outputDir || path.join(app.getPath('pictures'), 'FrameMark')
      await fs.mkdir(outDir, { recursive: true })
      const base = sanitizeFilename(args.filename || 'framemark')
      const outPath = await uniqueOutputPath(outDir, base, ext)
      await fs.writeFile(outPath, encoded)
      const finalMeta = await sharp(encoded).metadata()
      return {
        path: outPath,
        bytes: encoded.length,
        width: finalMeta.width ?? args.width,
        height: finalMeta.height ?? args.height
      }
    }
  )
}

app.whenReady().then(async () => {
  nativeTheme.themeSource = 'dark'
  Menu.setApplicationMenu(null)
  registerIpc()
  await createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) void createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
