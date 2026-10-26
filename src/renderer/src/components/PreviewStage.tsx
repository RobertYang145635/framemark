import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Eye, ImagePlus, Loader2, Maximize2, Sparkles } from 'lucide-react'
import type { PhotoAsset } from '../types'
import { useAppStore } from '../store'
import { getSampleAsset } from '../core/sample'
import { loadImage } from '../core/images'
import { renderDesign } from '../core/scene'
import { ensureFonts } from '../core/fonts'
import { getVariant } from '../core/variants'

const sampleModules = import.meta.glob('../assets/samples/*.jpg', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>
const sampleUrls = Object.values(sampleModules)

export function PreviewStage() {
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const design = useAppStore((s) => s.design)
  const busy = useAppStore((s) => s.busy)
  const addSample = useAppStore((s) => s.addSample)
  const pickPhotos = useAppStore((s) => s.pickPhotos)
  const [sample, setSample] = useState<PhotoAsset | null>(null)
  const [image, setImage] = useState<HTMLImageElement | null>(null)
  const [fontReady, setFontReady] = useState(false)
  const [zoom, setZoom] = useState<'fit' | 'actual'>('fit')
  const [compare, setCompare] = useState(false)
  const [meta, setMeta] = useState<{ W: number; H: number; outW: number; outH: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const variant = getVariant(design.variantId)
  const selected = photos.find((p) => p.id === selectedId) ?? photos[0] ?? sample
  const isSample = !photos.length

  useEffect(() => {
    void getSampleAsset().then(setSample)
    void ensureFonts().then(() => setFontReady(true))
  }, [])

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    void loadImage(selected.previewUrl)
      .then((img) => {
        if (!cancelled) setImage(img)
      })
      .catch(() => {
        if (!cancelled) setImage(null)
      })
    return () => {
      cancelled = true
    }
  }, [selected])

  useEffect(() => {
    if (!selected || !image || !canvasRef.current) return
    let cancelled = false
    const timer = setTimeout(() => {
      void (async () => {
        try {
          await ensureFonts()
          if (cancelled || !canvasRef.current) return
          const result = await renderDesign(canvasRef.current, design, selected, image, image.naturalWidth, image.naturalHeight, {
            preview: true
          })
          if (cancelled) return
          const scale = selected.width / Math.max(1, image.naturalWidth)
          setMeta({
            W: result.W,
            H: result.H,
            outW: Math.round(result.W * scale),
            outH: Math.round(result.H * scale)
          })
          setError(null)
        } catch (e) {
          if (!cancelled) setError(e instanceof Error ? e.message : '渲染失败')
        }
      })()
    }, 60)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [design, selected, image, fontReady])

  const sampleUrl = useMemo(() => sampleUrls[Math.floor(Math.random() * sampleUrls.length)], [])
  const addRandomSample = () => {
    if (!sampleUrl) return
    const name = sampleUrl.split('/').pop() ?? 'sample.jpg'
    void addSample(sampleUrl, name)
  }

  return (
    <section className="preview-stage">
      <div className="stage-topline">
        <div className="stage-title">
          <strong>{variant.name}</strong>
          <span className="stage-meta">
            {isSample ? '示例照片' : selected?.name}
            {meta ? ` · ${meta.outW} × ${meta.outH}` : ''}
            {design.ratio !== 'original' ? ` · ${design.ratio}` : ''}
          </span>
        </div>
        <div className="stage-tools">
          <button
            className={`btn btn-ghost btn-sm glass-btn glass-ripple glass-press ${compare ? 'active' : ''}`}
            onPointerDown={() => setCompare(true)}
            onPointerUp={() => setCompare(false)}
            onPointerLeave={() => setCompare(false)}
            onPointerCancel={() => setCompare(false)}
            type="button"
            title="按住查看原图"
          >
            <Eye size={15} />
            <span>按住看原图</span>
          </button>
          <button
            className={`btn btn-ghost btn-sm glass-btn glass-ripple glass-press ${zoom === 'actual' ? 'active' : ''}`}
            onClick={() => setZoom((z) => (z === 'fit' ? 'actual' : 'fit'))}
            type="button"
            title={zoom === 'fit' ? '按实际预览像素显示' : '适应窗口'}
          >
            <Maximize2 size={15} />
            <span>{zoom === 'fit' ? '适应' : '100%'}</span>
          </button>
        </div>
      </div>

      <div className={`canvas-viewport ${zoom}`}>
        {isSample && selected ? (
          <div className="sample-banner">
            <span className="sample-icon"><Sparkles size={16} /></span>
            <span>
              <strong>正在预览示例</strong>
              <em>添加你的照片后，参数会自动读取 EXIF</em>
            </span>
            <button className="btn btn-primary btn-sm" onClick={() => void pickPhotos()} type="button">
              <ImagePlus size={15} />
              添加照片
            </button>
            <button className="btn btn-ghost btn-sm glass-btn glass-ripple glass-press" onClick={addRandomSample} type="button">
              载入示例图
            </button>
          </div>
        ) : null}
        <div className="canvas-frame">
          <canvas ref={canvasRef} aria-label="模板预览" />
          {compare && selected ? <img className="compare-overlay" src={selected.previewUrl} alt="原图" /> : null}
          {compare ? <span className="compare-label">原图</span> : null}
          {error ? <div className="render-error">{error}</div> : null}
        </div>
        {busy ? (
          <div className="busy-pill">
            <Loader2 size={14} className="spin" />
            {busy}
          </div>
        ) : null}
      </div>
      <div className="stage-foot">
        <span>拖入照片即可开始 · JPG / PNG / WebP / TIFF</span>
        <span className="stage-foot-right">sRGB · 原始分辨率优先</span>
      </div>
    </section>
  )
}
