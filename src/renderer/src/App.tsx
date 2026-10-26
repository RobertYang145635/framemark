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
import { mountGlassChrome, paintGlassChromeBeforeFirstFrame } from './components/glass/glassBoot'
import { getVariant, TEMPLATE_VARIANTS } from './core/variants'
import { STOCK_STYLE_LIST } from './core/system/styles'
import { ensureFonts } from './core/fonts'

/**
 * GLASS-UI G-1 · 玻璃外壳挂载（G-0 交付机制、本批接调用点）。
 * 放在模块顶层而不是 useEffect：本文件被 main.tsx import 的瞬间就执行，React 还没
 * 渲染任何节点，等效于原型 design/index.html L9-18 的首帧前内联脚本（index.html 属本批
 * 禁改范围），避免亮/暗切换与画质降档时的闪白。
 */
paintGlassChromeBeforeFirstFrame()

const QUICK = ['wm-classic', 'white-1', 'color-1', 'glass-card-4', 'movie-1', 'fm-polaroid']

/**
 * The 模板库 dialog used to claim "原版 40+", a number that stopped describing the app
 * long ago (PRODUCT-DEFECTS #58 / defect 3). Both figures are derived from the live
 * registries instead of being typed in again: `STOCK_STYLE_LIST` is the built-in vendor
 * style registry and `TEMPLATE_VARIANTS` is exactly what the gallery renders as cards.
 */
const BUILT_IN_STYLE_COUNT = new Set(STOCK_STYLE_LIST.map((s) => s.id)).size
const GALLERY_CARD_COUNT = new Set(TEMPLATE_VARIANTS.map((v) => v.id)).size
const TEMPLATE_MODAL_SUBTITLE = `内置 ${BUILT_IN_STYLE_COUNT} 套厂商样式 · ${GALLERY_CARD_COUNT} 张版式卡片，全部可直接套用；左侧缩略图可对照原版预览。`

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

  /**
   * GLASS-UI G-1 · 动效与主题跟随：
   *  - attachGlassMotion 事件委托（光斑/按压/涟漪），卸载时解绑；
   *  - watchSystemColorScheme 只在用户存了 auto 时响应 prefers-color-scheme（01 §6.2）；
   *  - 首屏之后跑一次离屏 blur 基准做低端 GPU 自动降档（04 §C）。
   * reduced-motion 不需要 JS 状态源：styles.css 末尾的总闸 + glassMotion 每次事件实时读 matchMedia。
   */
  useEffect(() => mountGlassChrome(), [])

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
        <aside className="sidebar templates-panel glass">
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
        subtitle={TEMPLATE_MODAL_SUBTITLE}
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
