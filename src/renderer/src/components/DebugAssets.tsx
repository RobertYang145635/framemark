import React, { useEffect, useState } from 'react'
import { useAppStore } from '../store'

const PHOTOS = ['p1170883.jpg', 'p1181817.jpg', 'vertical.jpg']

async function fetchAsFile(name: string, type: string): Promise<File> {
  const response = await fetch(`./test-assets/${name}`, { cache: 'no-store' })
  if (!response.ok) throw new Error(`无法读取 ${name}`)
  return new File([await response.blob()], name, { type })
}

export function DebugAssets() {
  const addFiles = useAppStore((s) => s.addFiles)
  const setWatermarkFile = useAppStore((s) => s.setWatermarkFile)
  const [available, setAvailable] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (window.framemark) return
    fetch('./test-assets/manifest.json', { cache: 'no-store' })
      .then((r) => setAvailable(r.ok))
      .catch(() => setAvailable(false))
  }, [])

  if (!available) return null

  const loadPhotos = async () => {
    setBusy(true)
    try {
      const files = await Promise.all(PHOTOS.map((name) => fetchAsFile(name, 'image/jpeg')))
      await addFiles(files)
    } finally {
      setBusy(false)
    }
  }

  const loadWatermark = async (name: string) => {
    setBusy(true)
    try {
      await setWatermarkFile(await fetchAsFile(name, 'image/png'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="debug-assets">
      <span>DEBUG</span>
      <button onClick={() => void loadPhotos()} disabled={busy} type="button">
        载入测试照片
      </button>
      <button onClick={() => void loadWatermark('wm-black.png')} disabled={busy} type="button">
        黑水印
      </button>
      <button onClick={() => void loadWatermark('wm-white.png')} disabled={busy} type="button">
        白水印
      </button>
    </div>
  )
}
