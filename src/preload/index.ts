import { contextBridge, ipcRenderer } from 'electron'

/** Mirrors FolderScanResult in the main process: files plus the ceilings that were applied. */
interface FolderScanResult {
  files: string[]
  maxFiles: number
  maxDepth: number
  droppedFiles: number
  skippedDirs: number
  guardHit: boolean
}

interface ImportOptions {
  maxFiles?: number
  maxDepth?: number
}

/** Mirrors `RUNTIME_FLAGS` in src/main/index.ts (env read there, not here). */
interface RuntimeFlags {
  tile: boolean
  seamCanary: boolean
  /**
   * S6a: band height in stage pixels for the tiled export painter. Read here
   * from `FRAMEMARK_BAND` (main's `app:flags` payload predates S6 and cannot be
   * extended without touching the S5 gate's expected shape), default 1024,
   * clamped to 256..8192.
   */
  band: number
  /**
   * S5's gate compares `JSON.stringify(window.framemark.flags)` EXACTLY against
   * `{tile, seamCanary}`; `band` is additive and must stay invisible to that
   * serialization or every pass of scripts/tile-ab.cjs would fail at assertFlags.
   */
  toJSON(): { tile: boolean; seamCanary: boolean }
}

function readBand(): number {
  const raw = Number(process.env['FRAMEMARK_BAND'])
  if (!Number.isFinite(raw) || raw <= 0) return 1024
  return Math.min(8192, Math.max(256, Math.round(raw)))
}

/**
 * Read the flags from the main process. `sendSync` because `window.framemark`
 * must carry a plain object (`flags: { tile, seamCanary }`) — an async handle
 * would expose a Promise. If the handler is somehow absent, both flags fall
 * back to off; Android never runs this preload at all, so the bridge is the only
 * source of truth for the renderer's tile/seam switches.
 */
function readRuntimeFlags(): RuntimeFlags {
  let tile = false
  let seamCanary = false
  try {
    const raw = ipcRenderer.sendSync('app:flags') as { tile?: unknown; seamCanary?: unknown } | null
    tile = raw?.tile === true
    seamCanary = raw?.seamCanary === true
  } catch {
    /* no main handler => both off */
  }
  return {
    tile,
    seamCanary,
    band: readBand(),
    toJSON() {
      return { tile: this.tile, seamCanary: this.seamCanary }
    }
  }
}

const api = {
  flags: readRuntimeFlags() as RuntimeFlags,
  pickImages: (): Promise<string[]> => ipcRenderer.invoke('dialog:pickImages'),
  pickFolder: (): Promise<string | null> => ipcRenderer.invoke('dialog:pickFolder'),
  listImages: (folder: string, options?: ImportOptions): Promise<FolderScanResult> =>
    ipcRenderer.invoke('folder:listImages', folder, options),
  previewImage: (filePath: string) => ipcRenderer.invoke('image:preview', filePath),
  readImage: (filePath: string) => ipcRenderer.invoke('image:read', filePath),
  defaultExportDir: (): Promise<string> => ipcRenderer.invoke('export:defaultDir'),
  savePresetFile: (content: string, filename: string): Promise<string> => ipcRenderer.invoke('preset:saveFile', { content, filename }),
  pickExportDir: (): Promise<string | null> => ipcRenderer.invoke('export:pickDir'),
  showInFolder: (filePath: string): Promise<void> => ipcRenderer.invoke('export:showInFolder', filePath),
  getInfo: (): Promise<{ version: string; platform: string; packaged: boolean }> => ipcRenderer.invoke('app:info'),
  exportImage: (args: {
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
  }): Promise<{ path: string; bytes: number; width: number; height: number }> => ipcRenderer.invoke('export:save', args)
}

contextBridge.exposeInMainWorld('framemark', api)
