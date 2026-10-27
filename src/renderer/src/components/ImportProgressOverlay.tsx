import React from 'react'
import { Loader2 } from 'lucide-react'
import { useAppStore } from '../store'

export function ImportProgressOverlay() {
  const progress = useAppStore((s) => s.importProgress)
  if (!progress) return null
  const hasCount = progress.total > 0
  const pct = hasCount
    ? Math.min(100, Math.max(0, Math.round(((progress.done + progress.filePct) / progress.total) * 100)))
    : 0
  const currentIndex = hasCount ? Math.min(progress.total, progress.done + 1) : 0
  return (
    <div
      className="import-progress-layer"
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label={hasCount ? `正在导入 ${progress.total} 张照片` : '正在扫描文件夹'}
    >
      <div className="import-progress-card">
        <div className="import-progress-head">
          <span className="import-progress-icon">
            <Loader2 size={20} className="spin" />
          </span>
          <div className="import-progress-copy">
            <strong>{hasCount ? `正在导入 ${progress.total} 张照片` : '正在扫描文件夹'}</strong>
            <span>
              {progress.phase}
              {progress.current ? ` · ${progress.current}` : ''}
            </span>
          </div>
          {hasCount ? <em className="import-progress-pct">{pct}%</em> : null}
        </div>
        <div className={`progress-track import-progress-track ${hasCount ? '' : 'is-indeterminate'}`}>
          <span style={{ width: hasCount ? `${Math.max(4, pct)}%` : '38%' }} />
        </div>
        <div className="import-progress-foot">
          {hasCount ? `${currentIndex} / ${progress.total} · 照片只在本机处理` : '正在读取目录，请稍候…'}
        </div>
      </div>
    </div>
  )
}
