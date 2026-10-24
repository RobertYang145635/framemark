const cache = new Map<string, Promise<HTMLImageElement>>()

export function loadImage(src: string): Promise<HTMLImageElement> {
  if (!src) return Promise.reject(new Error('图片地址为空'))
  const hit = cache.get(src)
  if (hit) return hit
  const p = new Promise<HTMLImageElement>((resolve, reject) => {
    const img = new Image()
    img.decoding = 'async'
    img.onload = () => resolve(img)
    img.onerror = () => {
      cache.delete(src)
      reject(new Error('图片加载失败'))
    }
    img.src = src
  })
  cache.set(src, p)
  return p
}

export function canvasToBlob(canvas: HTMLCanvasElement, type = 'image/png', quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob)
        else reject(new Error('画布导出失败，可能尺寸过大'))
      },
      type,
      quality
    )
  })
}

export async function canvasToArrayBuffer(canvas: HTMLCanvasElement, type = 'image/png', quality?: number): Promise<Uint8Array> {
  const blob = await canvasToBlob(canvas, type, quality)
  return new Uint8Array(await blob.arrayBuffer())
}

export function createCanvas(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = Math.max(1, Math.round(w))
  c.height = Math.max(1, Math.round(h))
  return c
}

export function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(new Error('读取文件失败'))
    r.readAsDataURL(blob)
  })
}

export async function urlToImageAsset(url: string, name: string): Promise<{ id: string; name: string; dataUrl: string; width: number; height: number }> {
  const img = await loadImage(url)
  const blob = await fetch(url).then((r) => r.blob())
  const dataUrl = await blobToDataUrl(blob)
  return {
    id: `asset-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    name,
    dataUrl,
    width: img.naturalWidth,
    height: img.naturalHeight
  }
}
