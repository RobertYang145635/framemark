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
import { BatchPreview } from './components/BatchPreview'
import { ExportOverlay } from './components/ExportOverlay'
import { ImportProgressOverlay } from './components/ImportProgressOverlay'
import { Toast } from './components/Toast'
import { DebugAssets } from './components/DebugAssets'
import { Modal } from './components/ui'
import { mountGlassChrome, paintGlassChromeBeforeFirstFrame } from './components/glass/glassBoot'
import { BORDER_CATEGORIES, TAXONOMY_CARDS } from './core/taxonomy.generated'
import { ensureFonts } from './core/fonts'
import * as history from './core/history'

/**
 * S2 red/green switch. `true` in shipped code; the gate flips it to `false` for
 * one throw-away build to prove the gesture seal — not the 600 ms window — is
 * what keeps a long slider drag in ONE undo step.
 */
const GESTURE_SEAL = true

/**
 * Does this element own a native text undo stack? Only text-entry controls do,
 * so Ctrl+Z inside them must not touch the design (the browser reverts the
 * text). Range/checkbox/button inputs have no text undo, so the design
 * shortcut keeps working while they are focused (e.g. right after a drag).
 */
function isTextEntryTarget (target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  if (target instanceof HTMLTextAreaElement) return true
  if (target instanceof HTMLInputElement) {
    return !['range', 'checkbox', 'radio', 'button', 'submit', 'reset', 'file', 'color', 'image'].includes(
      target.type
    )
  }
  return false
}

/** Keys a focused range slider turns into value changes (keyboard-driven sliders). */
const RANGE_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown'])

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

  /**
   * S2 · global undo/redo shortcuts (ONE listener):
   *   Ctrl/Cmd+Z          undo
   *   Ctrl/Cmd+Shift+Z    redo
   *   Ctrl+Y              redo (Windows muscle memory)
   * Rules:
   *   - text-entry controls (text-like input / textarea / contenteditable) keep
   *     their native undo: never intercept while one of them has focus;
   *   - focus inside an open `role="dialog"` is a separate context (the design
   *     behind the scrim is partly hidden and dialogs own their own state), so
   *     the shortcut is ignored there too — close the dialog to undo the design;
   *   - AltGr (Ctrl+Alt) is not a shortcut, and IME composition is left alone.
   */
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.isComposing || e.altKey) return
      if (!e.ctrlKey && !e.metaKey) return
      const key = e.key.toLowerCase()
      if (key !== 'z' && key !== 'y') return
      const active = document.activeElement
      if (isTextEntryTarget(active)) return
      if (active instanceof Element && active.closest('[role="dialog"]')) return
      e.preventDefault()
      if (key === 'y' || e.shiftKey) useAppStore.getState().redoDesign()
      else useAppStore.getState().undoDesign()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  /**
   * S2 · gesture sealing. history.ts merges same-key writes only inside a 600 ms
   * window, but a slow drag can pause longer than that and still be ONE user
   * gesture. These capture-phase listeners at the app root bracket the gesture:
   *   - pointerdown/up/cancel on `input[type=range]` (mouse + touch + pen);
   *   - focusin/focusout on text-entry fields (typing is a gesture too);
   *   - arrow/Home/End keys on a focused range, ended 350 ms after the last key
   *     so a keyboard-driven slider burst (key repeat included) is one step.
   * Sources are counted independently, so focus moving out of a text field while
   * a slider is being dragged cannot close the drag's gesture.
   */
  useEffect(() => {
    const pointers = new Set<number>()
    let focusEl: HTMLElement | null = null
    let rangeKeyOpen = false
    let rangeKeyTimer: number | null = null

    const begin = () => {
      if (GESTURE_SEAL) history.beginGesture()
    }
    const end = () => {
      if (GESTURE_SEAL) history.endGesture()
    }

    const onPointerDown = (e: PointerEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest('input[type="range"]')) return
      if (pointers.has(e.pointerId)) return
      pointers.add(e.pointerId)
      begin()
    }
    const onPointerEnd = (e: PointerEvent) => {
      if (!pointers.delete(e.pointerId)) return
      end()
    }
    const onWindowBlur = () => {
      const open = pointers.size
      pointers.clear()
      for (let i = 0; i < open; i++) end()
    }
    const onFocusIn = (e: FocusEvent) => {
      if (!isTextEntryTarget(e.target) || e.target === focusEl) return
      if (focusEl) end()
      focusEl = e.target as HTMLElement
      begin()
    }
    const onFocusOut = (e: FocusEvent) => {
      if (e.target !== focusEl) return
      focusEl = null
      end()
    }
    const endRangeKeys = () => {
      if (rangeKeyTimer !== null) {
        window.clearTimeout(rangeKeyTimer)
        rangeKeyTimer = null
      }
      if (!rangeKeyOpen) return
      rangeKeyOpen = false
      end()
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest('input[type="range"]')) return
      if (!RANGE_KEYS.has(e.key)) return
      if (!rangeKeyOpen) {
        rangeKeyOpen = true
        begin()
      }
      if (rangeKeyTimer !== null) window.clearTimeout(rangeKeyTimer)
      rangeKeyTimer = window.setTimeout(endRangeKeys, 350)
    }
    const onRangeFocusOut = (e: FocusEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest('input[type="range"]')) return
      endRangeKeys()
    }

    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('pointerup', onPointerEnd, true)
    window.addEventListener('pointercancel', onPointerEnd, true)
    window.addEventListener('blur', onWindowBlur)
    document.addEventListener('focusin', onFocusIn, true)
    document.addEventListener('focusout', onFocusOut, true)
    document.addEventListener('focusout', onRangeFocusOut, true)
    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('pointerup', onPointerEnd, true)
      window.removeEventListener('pointercancel', onPointerEnd, true)
      window.removeEventListener('blur', onWindowBlur)
      document.removeEventListener('focusin', onFocusIn, true)
      document.removeEventListener('focusout', onFocusOut, true)
      document.removeEventListener('focusout', onRangeFocusOut, true)
      document.removeEventListener('keydown', onKeyDown, true)
      const openPointers = pointers.size
      pointers.clear()
      for (let i = 0; i < openPointers; i++) end()
      if (focusEl) {
        focusEl = null
        end()
      }
      endRangeKeys()
    }
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
          <TopBar onOpenTemplates={() => setTemplatesOpen(true)} />

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
      <BatchPreview />
      <ExportOverlay />
      <ImportProgressOverlay />
      <Toast />

      <input
        ref={fileInput}
        id="framemark-file-input"
        className="visually-hidden"
        type="file"
        accept={
          window.framemark
            ? 'image/jpeg,image/png,image/webp,image/tiff,image/heic,image/heif'
            : 'image/jpeg,image/png,image/webp,image/heic,image/heif'
        }
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
