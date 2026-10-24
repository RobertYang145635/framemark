import { registerPlugin } from '@capacitor/core'

export interface GallerySaverPlugin {
  saveImage(options: { base64: string; fileName: string; mimeType: string }): Promise<{ uri: string }>
  saveDocument(options: {
    base64: string
    fileName: string
    mimeType: string
    subdir?: string
  }): Promise<{ uri: string }>
  shareImage(options: { uri: string; mimeType: string; title?: string }): Promise<void>
}

export const GallerySaver = registerPlugin<GallerySaverPlugin>('GallerySaver')

export function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const value = String(reader.result)
      resolve(value.slice(value.indexOf(',') + 1))
    }
    reader.onerror = () => reject(new Error('读取导出数据失败'))
    reader.readAsDataURL(blob)
  })
}

export async function saveTextDocument(text: string, fileName: string): Promise<string> {
  const base64 = await blobToBase64(new Blob([text], { type: 'application/json' }))
  const result = await GallerySaver.saveDocument({
    base64,
    fileName,
    mimeType: 'application/json',
    subdir: 'FrameMark'
  })
  return result.uri
}
