import { contextBridge, ipcRenderer } from 'electron'

const api = {
  pickImages: (): Promise<string[]> => ipcRenderer.invoke('dialog:pickImages'),
  pickFolder: (): Promise<string | null> => ipcRenderer.invoke('dialog:pickFolder'),
  listImages: (folder: string): Promise<string[]> => ipcRenderer.invoke('folder:listImages', folder),
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
