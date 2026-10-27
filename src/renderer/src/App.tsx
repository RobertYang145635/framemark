import React, { useEffect, useRef, useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { App as NativeApp } from '@capacitor/app'
import { useAppStore } from './store'
import { TopBar } from './components/TopBar'
import { ImportScreen } from './components/ImportScreen'
import { BorderLibrary } from './components/BorderLibrary'
import { PreviewStage } from './components/PreviewStage'
import { Filmstrip } from './components/Filmstrip'
import { Inspector } from './components/Inspector'
import { BrandPicker } from './components/BrandPicker'
import { PresetDialog } from './components/PresetDialog'
import { ReferenceGallery } from './components/ReferenceGallery'
import { ExportDialog } from './components/ExportDialog'
import { ExportOverlay } from './components/ExportOverlay'
import { Toast } from './components/Toast'
import { DebugAssets } from './components/DebugAssets'
import { Modal } from './components/ui'
import { mountGlassChrome, paintGlassChromeBeforeFirstFrame } from './components/glass/glassBoot'
import { BORDER_CATEGORIES, TAXONOMY_CARDS } from './core/taxonomy.generated'
import { ensureFonts } from './core/fonts'

/**
 * GLASS-UI G-1 · 玻璃外壳挂载（G-0 交付机制、本批接调用点）。
 * 放在模块顶层而不是 useEffect：本文件被 main.tsx import 的瞬间就执行，React 还没
 * 渲染任何节点，等效于原型 design/index.html L9-18 的首帧前内联脚本（index.html 属本批
 * 禁改范围），避免亮/暗切换与画质降档时的闪白。
 */
paintGlassChromeBeforeFirstFrame()

const BORDER_MODAL_SUBTITLE = `${BORDER_CATEGORIES.length} 类 · ${TAXONOMY_CARDS.length} 套`

export function App() {
  const init = useAppStore((s) => s.init)
  const initialized = useAppStore((s) => s.initialized)
  const photos = useAppStore((s) => s.photos)
  const addFiles = useAppStore((s) => s.addFiles)
  const [templatesOpen, setTemplatesOpen] = useState(false)
  const [referenceOpen, setReferenceOpen] = useState(false)
  const [dragging, setDragging] = useState(false)
  const dragDepth = useRef(0)
  const fileInput = useRef<HTMLInputElement>(null)

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
      if (state.exportDialog) {
        state.closeExportDialog()
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
      <DebugAssets />

      {photos.length === 0 ? (
        <ImportScreen />
      ) : (
        <>
          <TopBar />

          <div className="workspace">
            <aside className="sidebar templates-panel glass">
              <BorderLibrary />
            </aside>
            <main className="center-column">
              <PreviewStage />
              <Filmstrip />
            </main>
            <Inspector onOpenTemplates={() => setTemplatesOpen(true)} onOpenReferences={() => setReferenceOpen(true)} />
          </div>
        </>
      )}

      <Modal
        open={templatesOpen}
        onClose={() => setTemplatesOpen(false)}
        title="边框"
        subtitle={BORDER_MODAL_SUBTITLE}
        wide
        className="border-library-modal"
      >
        <BorderLibrary modal onPick={() => setTemplatesOpen(false)} />
      </Modal>

      <ReferenceGallery open={referenceOpen} onClose={() => setReferenceOpen(false)} />
      <BrandPicker />
      <PresetDialog />
      <ExportDialog />
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
