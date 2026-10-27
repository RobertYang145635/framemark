import React from 'react'
import { Download, FolderOpen, ImagePlus, X } from 'lucide-react'
import { useAppStore } from '../store'
import { IconButton } from './ui'

export function Filmstrip() {
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const selectPhoto = useAppStore((s) => s.selectPhoto)
  const removePhoto = useAppStore((s) => s.removePhoto)
  const pickPhotos = useAppStore((s) => s.pickPhotos)
  const addFolder = useAppStore((s) => s.addFolder)
  const openExportDialog = useAppStore((s) => s.openExportDialog)
  const job = useAppStore((s) => s.job)
  const desktop = Boolean(window.framemark)

  return (
    <section className="filmstrip glass">
      <div className="filmstrip-head">
        <div className="filmstrip-title">
          <strong>照片</strong>
          <span>{photos.length ? `${photos.length} 张` : '空'}</span>
          {job?.running ? <span className="job-inline">正在导出 {job.done}/{job.total}</span> : null}
        </div>
        <div className="filmstrip-actions">
          {desktop ? (
            <button className="btn btn-ghost btn-sm hide-sm glass-btn glass-ripple glass-press" onClick={() => void addFolder()} type="button">
              <FolderOpen size={15} />
              文件夹
            </button>
          ) : null}
          <button className="btn btn-ghost btn-sm glass-btn glass-ripple glass-press" onClick={() => void pickPhotos()} type="button">
            <ImagePlus size={15} />
            添加
          </button>
          {photos.length > 1 ? (
            <button
              className="btn btn-ghost btn-sm glass-btn glass-spot glass-ripple glass-press"
              onClick={() => openExportDialog('all')}
              type="button"
              title={`打开导出设置并默认选择全部 ${photos.length} 张（当前模板套用到每张）。顶栏的「导出当前」只写 1 张。`}
            >
              <Download size={15} />
              批量导出
            </button>
          ) : null}
        </div>
      </div>
      <div className="thumbs">
        {photos.map((p, i) => (
          <div key={p.id} className={`thumb ${p.id === selectedId ? 'active' : ''}`}>
            <button className="thumb-main glass-press" onClick={() => selectPhoto(p.id)} title={p.name} type="button">
              <img src={p.previewUrl} alt={p.name} loading="lazy" />
              <span className="thumb-index">{String(i + 1).padStart(2, '0')}</span>
            </button>
            <button
              className="thumb-remove"
              onClick={() => removePhoto(p.id)}
              title="移除照片"
              type="button"
            >
              <X size={12} />
            </button>
          </div>
        ))}
        <button className="thumb-add glass-ripple glass-press" onClick={() => void pickPhotos()} type="button" title="添加照片">
          <ImagePlus size={20} />
          <span>添加</span>
        </button>
        {!photos.length ? <span className="filmstrip-empty">还没有照片。先添加一张，或拖到窗口里。</span> : null}
      </div>
    </section>
  )
}
