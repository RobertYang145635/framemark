import React, { useEffect, useRef, useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { App as NativeApp } from '@capacitor/app'
import { useAppStore } from './store'
import { TopBar } from './components/TopBar'
import { TemplateGallery } from './components/TemplateGallery'
import { VariantThumb } from './components/VariantThumb'
import { PreviewStage } from './components/PreviewStage'
import { Filmstrip } from './components/Filmstrip'
import { Inspector } from './components/Inspector'
import { BrandPicker } from './components/BrandPicker'
import { PresetDialog } from './components/PresetDialog'
import { ReferenceGallery } from './components/ReferenceGallery'
import { ExportOverlay } from './components/ExportOverlay'
import { Toast } from './components/Toast'
import { DebugAssets } from './components/DebugAssets'
import { Modal } from './components/ui'
import { getVariant } from './core/variants'
import { ensureFonts } from './core/fonts'

const QUICK = ['wm-classic', 'white-1', 'color-1', 'glass-card-4', 'movie-1', 'fm-polaroid']

export function App() {
  const init = useAppStore((s) => s.init)
  const initialized = useAppStore((s) => s.initialized)
  const design = useAppStore((s) => s.design)
  const setVariant = useAppStore((s) => s.setVariant)
  const addFiles = useAppStore((s) => s.addFiles)
  const [templatesOpen, setTemplatesOpen] = useState(false)
  const [referenceOpen, setReferenceOpen] = useState(false)
  const [dragging, setDragging] = useState(false)
  const dragDepth = useRef(0)
  const fileInput = useRef<HTMLInputElement>(null)
  const activeQuickCard = useRef<HTMLButtonElement>(null)
  const quickStrip = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const strip = quickStrip.current
    const card = activeQuickCard.current
    if (!strip || !card) return
    strip.scrollTo({
      left: card.offsetLeft - (strip.clientWidth - card.offsetWidth) / 2,
      behavior: 'smooth'
    })
  }, [design.variantId])

  const quickIds = design.variantId && !QUICK.includes(design.variantId) ? [design.variantId, ...QUICK] : QUICK

  useEffect(() => {
    void init()
    void ensureFonts()
  }, [init])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDragging(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return
    let remove: (() => void) | null = null
    let disposed = false
    void NativeApp.addListener('backButton', () => {
      const state = useAppStore.getState()
      if (state.job?.running) {
        state.cancelExport()
        return
      }
      if (state.presetDialogOpen) {
        state.setPresetDialogOpen(false)
        return
      }
      if (state.brandPickerOpen) {
        state.setBrandPickerOpen(false)
        return
      }
      if (templatesOpen) {
        setTemplatesOpen(false)
        return
      }
      if (referenceOpen) {
        setReferenceOpen(false)
        return
      }
      void NativeApp.exitApp()
    }).then((handle) => {
      if (disposed) void handle.remove()
      else remove = () => void handle.remove()
    })
    return () => {
      disposed = true
      remove?.()
    }
  }, [templatesOpen, referenceOpen])

  const onDragEnter = (e: React.DragEvent) => {
    e.preventDefault()
    dragDepth.current += 1
    if (e.dataTransfer.types.includes('Files')) setDragging(true)
  }
  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'copy'
  }
  const onDragLeave = (e: React.DragEvent) => {
    e.preventDefault()
    dragDepth.current = Math.max(0, dragDepth.current - 1)
    if (dragDepth.current === 0) setDragging(false)
  }
  const onDrop = (e: React.DragEvent) => {
    e.preventDefault()
    dragDepth.current = 0
    setDragging(false)
    const files = Array.from(e.dataTransfer.files)
    if (files.length) void addFiles(files)
  }

  return (
    <div
      className={`app-shell ${initialized ? 'ready' : ''}`}
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      <TopBar />
      <DebugAssets />

      <div className="quick-strip" ref={quickStrip}>
        {quickIds.map((id) => {
          const v = getVariant(id)
          return (
            <button
              key={id}
              ref={design.variantId === id ? activeQuickCard : undefined}
              className={`quick-card ${design.variantId === id ? 'active' : ''}`}
              onClick={() => setVariant(id)}
              type="button"
            >
              <VariantThumb variant={v} className="quick-canvas" />
              <em>{v.name}</em>
            </button>
          )
        })}
      </div>

      <div className="workspace">
        <aside className="sidebar templates-panel">
          <TemplateGallery onOpenReferences={() => setReferenceOpen(true)} />
        </aside>
        <main className="center-column">
          <PreviewStage />
          <Filmstrip />
        </main>
        <Inspector onOpenTemplates={() => setTemplatesOpen(true)} onOpenReferences={() => setReferenceOpen(true)} />
      </div>

      <Modal
        open={templatesOpen}
        onClose={() => setTemplatesOpen(false)}
        title="模板库"
        subtitle="原版 40+ 照片版式已全部映射为预设；左侧缩略图可直接对照原版预览。"
        wide
        className="template-modal"
      >
        <TemplateGallery
          modal
          onPick={() => setTemplatesOpen(false)}
          onOpenReferences={() => {
            setTemplatesOpen(false)
            setReferenceOpen(true)
          }}
        />
      </Modal>

      <ReferenceGallery open={referenceOpen} onClose={() => setReferenceOpen(false)} />
      <BrandPicker />
      <PresetDialog />
      <ExportOverlay />
      <Toast />

      <input
        ref={fileInput}
        id="framemark-file-input"
        className="visually-hidden"
        type="file"
        accept="image/jpeg,image/png,image/webp,image/tiff,image/heic,image/heif"
        multiple
        onChange={(e) => {
          const files = Array.from(e.target.files ?? [])
          if (files.length) void addFiles(files)
          e.target.value = ''
        }}
      />

      <div className={`drop-overlay ${dragging ? 'show' : ''}`}>
        <div>
          <strong>松开即可添加照片</strong>
          <span>JPG / PNG / WebP / TIFF · 支持一次拖入多张</span>
        </div>
      </div>
    </div>
  )
}
