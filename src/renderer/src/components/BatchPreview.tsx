import React, { useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, Pencil } from 'lucide-react'
import { useAppStore } from '../store'
import { Modal } from './ui'
import { renderStyledThumb } from '../core/batch-preview'

const STAGE = 1600
// Same box as the Filmstrip thumbs: the cache key includes the size, so sharing
// it means the mobile strip reuses thumbs the filmstrip already painted.
const STRIP_W = 152
const STRIP_H = 116

export function BatchPreview() {
  const photos = useAppStore((s) => s.photos)
  const index = useAppStore((s) => s.batchPreviewIndex)
  const design = useAppStore((s) => s.design)
  const close = useAppStore((s) => s.closeBatchPreview)
  const step = useAppStore((s) => s.stepBatchPreview)
  const selectPhoto = useAppStore((s) => s.selectPhoto)
  const jump = useAppStore((s) => s.openBatchPreview)

  const open = index !== null && photos.length > 0
  const clamped = open ? Math.min(index as number, photos.length - 1) : 0
  const photo = open ? photos[clamped] : null
  const [src, setSrc] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [thumbs, setThumbs] = useState<Record<string, string>>({})
  const stripRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open || !photo) return
    let cancelled = false
    setBusy(true)
    renderStyledThumb(photo, design, STAGE, STAGE)
      .then((url) => {
        if (cancelled) return
        setSrc(url)
        setBusy(false)
      })
      .catch(() => {
        if (cancelled) return
        setSrc(null)
        setBusy(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, photo, design])

  // Mobile strip: one styled thumb per photo, current one first. A photo whose
  // thumb has not been rendered yet falls back to its raw preview URL.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    const first = photos[clamped]?.id
    const order = [...photos].sort((a, b) => (a.id === first ? -1 : b.id === first ? 1 : 0))
    void (async () => {
      for (const p of order) {
        if (cancelled) return
        try {
          const url = await renderStyledThumb(p, design, STRIP_W, STRIP_H)
          if (cancelled) return
          setThumbs((prev) => (prev[p.id] === url ? prev : { ...prev, [p.id]: url }))
        } catch {
          // keep the raw previewUrl fallback for this photo
        }
      }
    })()
    return () => {
      cancelled = true
    }
    // `clamped` only seeds the order; stepping must not restart the whole queue.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, photos, design])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') step(-1)
      else if (e.key === 'ArrowRight') step(1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, step])

  useEffect(() => {
    if (!open) return
    const el = stripRef.current?.querySelector<HTMLElement>(`.batch-preview-strip-item[data-index="${clamped}"]`)
    el?.scrollIntoView({ block: 'nearest', inline: 'center' })
  }, [open, clamped, thumbs])

  return (
    <Modal
      open={open}
      onClose={close}
      title="全部预览"
      subtitle={photo ? `${clamped + 1} / ${photos.length} · ${photo.name}` : undefined}
      wide
      className="batch-preview-modal"
      footer={
        <>
          <span className="modal-note">左右方向键切换；这里和导出用的是同一套渲染。</span>
          <button
            className="btn btn-primary"
            type="button"
            onClick={() => {
              if (photo) selectPhoto(photo.id)
              close()
            }}
          >
            <Pencil size={15} />
            用这张编辑
          </button>
        </>
      }
    >
      <div className="batch-preview-stage">
        {src ? (
          <img className={busy ? 'is-busy' : undefined} src={src} alt={photo?.name ?? ''} />
        ) : (
          <span className="batch-preview-hint">正在生成预览…</span>
        )}
        {photos.length > 1 ? (
          <div className="batch-preview-nav">
            <button className="icon-btn glass-btn glass-press" onClick={() => step(-1)} title="上一张" type="button">
              <ChevronLeft size={20} />
            </button>
            <button className="icon-btn glass-btn glass-press" onClick={() => step(1)} title="下一张" type="button">
              <ChevronRight size={20} />
            </button>
          </div>
        ) : null}
      </div>
      {/* Mobile-only bottom strip (desktop CSS keeps it display:none). */}
      <div className="batch-preview-strip" ref={stripRef} role="tablist" aria-label="全部照片">
        {photos.map((p, i) => (
          <button
            key={p.id}
            type="button"
            role="tab"
            aria-selected={i === clamped}
            data-index={i}
            className={`batch-preview-strip-item ${i === clamped ? 'active' : ''}`}
            onClick={() => jump(i)}
            title={`${i + 1} / ${photos.length} · ${p.name}`}
          >
            <img src={thumbs[p.id] ?? p.previewUrl} alt={p.name} loading="lazy" />
            <span>{i + 1}</span>
          </button>
        ))}
      </div>
    </Modal>
  )
}
