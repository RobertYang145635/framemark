import React from 'react'
import { Download, ImagePlus, Loader2, Save } from 'lucide-react'
import { useAppStore } from '../store'
import { getVariant } from '../core/variants'

export function TopBar() {
  const design = useAppStore((s) => s.design)
  const presets = useAppStore((s) => s.presets)
  const activePresetId = useAppStore((s) => s.activePresetId)
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const busy = useAppStore((s) => s.busy)
  const job = useAppStore((s) => s.job)
  const applyPreset = useAppStore((s) => s.applyPreset)
  const pickPhotos = useAppStore((s) => s.pickPhotos)
  const setPresetDialogOpen = useAppStore((s) => s.setPresetDialogOpen)
  const openExportDialog = useAppStore((s) => s.openExportDialog)

  const variant = getVariant(design.variantId)
  const active = activePresetId && presets.some((p) => p.id === activePresetId) ? activePresetId : ''
  // The primary top-bar button opens the export dialog scoped to ONE file (store
  // `exportCurrent` -> the selected photo); the dialog itself can switch to 全部 N 张.
  // Batch entries stay in the Filmstrip so "导出当前" can never read as "export batch".
  const currentPhoto = photos.find((p) => p.id === selectedId) ?? photos[0] ?? null
  const exportTitle = currentPhoto
    ? `打开导出设置，默认只导出当前这 1 张：${currentPhoto.name}（共 ${photos.length} 张在册）。可在对话框里改成导出全部。`
    : '打开导出设置（还没有照片，请先添加）。'

  return (
    <header className="topbar glass-strong">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 32 32" fill="none">
            <path d="M4 11V5.5A1.5 1.5 0 0 1 5.5 4H11" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M21 4h5.5A1.5 1.5 0 0 1 28 5.5V11" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M28 21v5.5a1.5 1.5 0 0 1-1.5 1.5H21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <path d="M11 28H5.5A1.5 1.5 0 0 1 4 26.5V21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <rect x="10" y="10" width="12" height="12" rx="2" stroke="currentColor" strokeWidth="1.5" opacity=".55" />
          </svg>
        </span>
        <span className="brand-text">
          <strong>框印</strong>
          <em>FrameMark</em>
        </span>
      </div>

      <div className="topbar-center">
        <div className="preset-select-wrap">
          <select
            className="preset-select"
            value={active}
            onChange={(e) => {
              if (e.target.value) applyPreset(e.target.value)
            }}
            title="应用预设"
          >
            <option value="">当前模板 · {variant.name}</option>
            {presets.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
          <span className="preset-caption">
            {active ? '用户预设' : '模板默认'} · {photos.length} 张照片
          </span>
        </div>
      </div>

      <div className="topbar-actions">
        {busy || (job?.running ?? false) ? (
          <span className="topbar-busy">
            <Loader2 size={15} className="spin" />
            <span className="hide-sm">{busy ?? '导出中'}</span>
          </span>
        ) : null}
        <button className="btn btn-ghost topbar-action glass-btn glass-spot glass-ripple glass-press" onClick={() => void pickPhotos()} type="button">
          <ImagePlus size={17} />
          <span>添加</span>
        </button>
        <button className="btn btn-ghost topbar-action glass-btn glass-spot glass-ripple glass-press" onClick={() => setPresetDialogOpen(true)} type="button">
          <Save size={17} />
          <span>预设</span>
        </button>
        <button
          className="btn btn-primary glass-btn glass-spot glass-ripple glass-press"
          onClick={() => openExportDialog('current')}
          type="button"
          title={exportTitle}
          aria-label="打开导出设置（默认当前照片）"
        >
          <Download size={17} />
          <span>导出当前</span>
        </button>
      </div>
    </header>
  )
}
