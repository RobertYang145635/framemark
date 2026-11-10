export interface PreviewImageResult {
  data: Uint8Array
  name: string
  bytes: number
  width: number
  height: number
  previewWidth: number
  previewHeight: number
  exif: Record<string, unknown> | null
}

export interface FolderScanResult {
  files: string[]
  maxFiles: number
  maxDepth: number
  droppedFiles: number
  skippedDirs: number
  guardHit: boolean
}

export interface FramemarkAPI {
  pickImages: () => Promise<string[]>
  pickFolder: () => Promise<string | null>
  listImages: (folder: string, options?: { maxFiles?: number; maxDepth?: number }) => Promise<FolderScanResult>
  previewImage: (filePath: string) => Promise<PreviewImageResult>
  readImage: (filePath: string) => Promise<{ data: Uint8Array; name: string }>
  defaultExportDir: () => Promise<string>
  savePresetFile: (content: string, filename: string) => Promise<string>
  pickExportDir: () => Promise<string | null>
  showInFolder: (filePath: string) => Promise<void>
  getInfo: () => Promise<{ version: string; platform: string; packaged: boolean }>
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
  }) => Promise<{ path: string; bytes: number; width: number; height: number }>
}

declare global {
  interface Window {
    framemark?: FramemarkAPI
  }
}
