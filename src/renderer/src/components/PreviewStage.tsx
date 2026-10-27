import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Eye, ImagePlus, Loader2, Maximize2, Minus, Plus, Sparkles } from 'lucide-react'
import type { PhotoAsset, Rect } from '../types'
import { useAppStore } from '../store'
import { getSampleAsset } from '../core/sample'
import { loadImage } from '../core/images'
import { renderDesign } from '../core/scene'
import { ensureFonts } from '../core/fonts'
import { getVariant } from '../core/variants'
import { WatermarkPlacement } from './WatermarkPlacement'

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
  const setPreviewMeta = useAppStore((s) => s.setPreviewMeta)
  const pickPhotos = useAppStore((s) => s.pickPhotos)
  const [sample, setSample] = useState<PhotoAsset | null>(null)
  const [image, setImage] = useState<HTMLImageElement | null>(null)
  const [fontReady, setFontReady] = useState(false)
  const [zoom, setZoom] = useState(1)
  const [viewport, setViewport] = useState({ w: 0, h: 0 })
  const [compare, setCompare] = useState(false)
  const [meta, setMeta] = useState<{
    W: number
    H: number
    outW: number
    outH: number
    photoRect: Rect
  } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const viewportRef = useRef<HTMLDivElement>(null)
  /** 滚轮缩放后要让光标下的画面点保持不动。 */
  const wheelAnchorRef = useRef<{ x: number; y: number; fx: number; fy: number; at: number } | null>(null)
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
            outH: Math.round(result.H * scale),
            photoRect: result.photoRect
          })
          setPreviewMeta({
            W: result.W,
            H: result.H,
            outW: Math.round(result.W * scale),
            outH: Math.round(result.H * scale)
          })
          const slots = result.textSlots ?? []
          const prevSlots = useAppStore.getState().textSlots
          if (JSON.stringify(prevSlots) !== JSON.stringify(slots)) {
            useAppStore.getState().setTextSlots(slots)
          }
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

  useEffect(() => {
    const el = viewportRef.current
    if (!el) return
    const ro = new ResizeObserver(([entry]) => {
      setViewport({ w: entry.contentRect.width, h: entry.contentRect.height })
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const fit = useMemo(() => {
    if (!meta || !viewport.w || !viewport.h) return null
    const availW = Math.max(80, viewport.w - 36)
    const availH = Math.max(80, viewport.h - 36)
    const scale = Math.min(availW / meta.W, availH / meta.H, 1)
    return { w: meta.W * scale, h: meta.H * scale }
  }, [meta, viewport])

  const shownW = fit ? Math.round(fit.w * zoom) : null
  const shownH = fit ? Math.round(fit.h * zoom) : null
  const clampZoom = (z: number) => Math.min(4, Math.max(0.5, Math.round(z * 1000) / 1000))

  useEffect(() => {
    const el = viewportRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      const canvas = canvasRef.current
      if (!canvas || !canvas.getBoundingClientRect().width) return
      e.preventDefault()
      const rect = canvas.getBoundingClientRect()
      wheelAnchorRef.current = {
        x: e.clientX,
        y: e.clientY,
        fx: (e.clientX - rect.left) / rect.width,
        fy: (e.clientY - rect.top) / rect.height,
        at: performance.now()
      }
      const step = Math.max(-240, Math.min(240, e.deltaY))
      setZoom((z) => clampZoom(z * Math.exp(-step * 0.0016)))
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  useLayoutEffect(() => {
    const anchor = wheelAnchorRef.current
    const el = viewportRef.current
    const canvas = canvasRef.current
    if (!anchor || !el || !canvas) return
    wheelAnchorRef.current = null
    // 只在滚轮触发的重绘里校正滚动；别把陈旧锚点用到按钮缩放上。
    if (performance.now() - anchor.at > 250) return
    const rect = canvas.getBoundingClientRect()
    el.scrollLeft += rect.left + anchor.fx * rect.width - anchor.x
    el.scrollTop += rect.top + anchor.fy * rect.height - anchor.y
  }, [zoom])

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
          <div className="zoom-cluster">
            <button
              className="zoom-step"
              onClick={() => setZoom((z) => clampZoom(z / 1.25))}
              type="button"
              title="缩小"
              aria-label="缩小"
            >
              <Minus size={14} />
            </button>
            <button
              className="zoom-readout"
              onClick={() => setZoom(1)}
              onDoubleClick={() => setZoom(1)}
              type="button"
              title="适应窗口（画面内滚轮缩放）"
            >
              {Math.round(zoom * 100)}%
            </button>
            <button
              className="zoom-step"
              onClick={() => setZoom((z) => clampZoom(z * 1.25))}
              type="button"
              title="放大"
              aria-label="放大"
            >
              <Plus size={14} />
            </button>
            <button
              className="zoom-fit"
              onClick={() => setZoom(1)}
              type="button"
              title="适应窗口"
              aria-label="适应窗口"
              disabled={zoom === 1}
            >
              <Maximize2 size={14} />
            </button>
          </div>
        </div>
      </div>

      <div ref={viewportRef} className={`canvas-viewport ${zoom > 1 ? "zoomed" : ""}`}>
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
        <div className="canvas-frame" style={fit && zoom > 1 ? { width: shownW! + 36, height: shownH! + 36 } : undefined}>
          <canvas
            ref={canvasRef}
            aria-label="模板预览"
            style={shownW ? { width: `${shownW}px`, height: `${shownH}px` } : undefined}
          />
          {!compare && meta ? (
            <WatermarkPlacement canvas={canvasRef.current} photoRect={meta.photoRect} W={meta.W} H={meta.H} />
          ) : null}
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
        <span>{photos.length ? '滚轮缩放画面 · 单击百分比复位' : '拖入照片即可开始 · JPG / PNG / WebP / TIFF'}</span>
        <span className="stage-foot-right">sRGB · 原始分辨率优先</span>
      </div>
    </section>
  )
}
