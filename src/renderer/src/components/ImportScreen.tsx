import React, { useEffect, useState } from 'react'
import { FolderOpen, ImagePlus, Loader2, Sparkles } from 'lucide-react'
import type { PhotoAsset } from '../types'
import { useAppStore } from '../store'
import { getSampleAsset } from '../core/sample'
import { BORDER_CATEGORIES, TAXONOMY_CARDS } from '../core/taxonomy.generated'
import { DISPLAY_LOGO_BRAND_COUNT } from '../core/logo-selection.generated'

/**
 * Photo-first home (v4 UI, phase 2).
 *
 * The app used to open on a 175-card template wall and render a sample photo
 * behind it. Now an empty library gets one job: import a photo. The sample stays
 * available as an explicit button so nothing is auto-rendered into the workspace.
 */
export function ImportScreen() {
  const pickPhotos = useAppStore((s) => s.pickPhotos)
  const addFolder = useAppStore((s) => s.addFolder)
  const addSample = useAppStore((s) => s.addSample)
  const [sample, setSample] = useState<PhotoAsset | null>(null)
  const [sampleBusy, setSampleBusy] = useState(false)
  const hasDesktop = typeof window !== 'undefined' && !!window.framemark

  useEffect(() => {
    let cancelled = false
    void getSampleAsset()
      .then((asset) => {
        if (!cancelled) setSample(asset)
      })
      .catch(() => {
        if (!cancelled) setSample(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const useSample = async () => {
    if (!sample || sampleBusy) return
    setSampleBusy(true)
    try {
      await addSample(sample.previewUrl, sample.name)
    } finally {
      setSampleBusy(false)
    }
  }

  return (
    <section className="import-screen" aria-label="导入照片">
      <header className="import-brand">
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
      </header>

      <div className="import-stage">
        <div className="import-panel glass">
          <div className={`import-visual ${sample ? 'has-sample' : ''}`}>
            {sample ? <img src={sample.previewUrl} alt="示例照片预览" /> : <span className="import-visual-empty" />}
            <span className="import-format">JPG / PNG / WebP / TIFF · 可多选</span>
          </div>

          <div className="import-copy">
            <h1>导入照片</h1>
            <p className="import-sub">为照片加上边框、文字与水印</p>

            <div className="import-actions">
              <button
                className="btn btn-primary import-primary glass-btn glass-spot glass-ripple glass-press"
                onClick={() => void pickPhotos()}
                type="button"
              >
                <ImagePlus size={19} />
                选择照片
              </button>

              <div className="import-secondary">
                {hasDesktop ? (
                  <button
                    className="btn btn-ghost import-secondary-btn glass-btn glass-spot glass-ripple glass-press"
                    onClick={() => void addFolder()}
                    type="button"
                  >
                    <FolderOpen size={16} />
                    从文件夹导入
                  </button>
                ) : null}
                <button
                  className="btn btn-ghost import-secondary-btn glass-btn glass-spot glass-ripple glass-press"
                  onClick={() => void useSample()}
                  disabled={!sample || sampleBusy}
                  type="button"
                >
                  {sampleBusy ? <Loader2 size={16} className="spin" /> : <Sparkles size={16} />}
                  {sampleBusy ? '正在载入…' : '使用示例照片'}
                </button>
              </div>
            </div>

            <p className="import-hint">也可以把照片直接拖进窗口</p>
          </div>
        </div>
      </div>

      <footer className="import-foot">
        {BORDER_CATEGORIES.length} 类边框 · {TAXONOMY_CARDS.length} 套 · {DISPLAY_LOGO_BRAND_COUNT} 个品牌
      </footer>
    </section>
  )
}
