import React from 'react'
import { Capacitor } from '@capacitor/core'
import { AlertTriangle, Check, Download, FolderOpen, Loader2, Share2, X } from 'lucide-react'
import { useAppStore } from '../store'
import { GallerySaver } from '../core/gallery'

function shareMime(name: string): string {
  if (/\.png$/i.test(name)) return 'image/png'
  if (/\.webp$/i.test(name)) return 'image/webp'
  return 'image/jpeg'
}

export function ExportOverlay() {
  const job = useAppStore((s) => s.job)
  const cancel = useAppStore((s) => s.cancelExport)
  const close = useAppStore((s) => s.closeJob)
  const showToast = useAppStore((s) => s.showToast)
  if (!job) return null
  const canShare =
    !job.running &&
    !job.cancelled &&
    job.errors.length === 0 &&
    job.total === 1 &&
    Capacitor.isNativePlatform() &&
    Boolean(job.lastPath?.startsWith('content://'))
  const pct = job.total ? Math.round((job.done / job.total) * 100) : 0
  const done = !job.running
  const failed = job.errors.length > 0
  return (
    <div className="export-overlay">
      <div className="export-card glass">
        <div className="export-card-head">
          <span className={`export-icon ${done ? `glass-animate-pop ${failed ? 'warn' : 'ok'}` : ''}`}>
            {job.running ? <Loader2 size={22} className="spin" /> : failed ? <AlertTriangle size={22} /> : <Check size={22} />}
          </span>
          <div>
            <h3>{job.running ? '正在导出' : job.cancelled ? '已取消' : failed ? '导出完成，但有失败' : '导出完成'}</h3>
            <p>
              {job.running
                ? `${job.done} / ${job.total} · ${job.current || '准备中'}`
                : `成功 ${Math.max(0, job.done - job.errors.length)} 张${failed ? ` · 失败 ${job.errors.length} 张` : ''}`}
            </p>
          </div>
          {!job.running ? (
            <button className="icon-btn" onClick={close} title="关闭">
              <X size={18} />
            </button>
          ) : null}
        </div>

        <div className="progress-track">
          <span style={{ width: `${job.running ? Math.max(4, pct) : pct}%` }} />
        </div>

        {job.running ? (
          <div className="export-card-actions">
            <button className="btn btn-ghost glass-btn glass-ripple glass-press" onClick={cancel} disabled={job.cancelled} type="button">
              {job.cancelled ? '正在取消…' : '取消导出'}
            </button>
            <span className="progress-num">{pct}%</span>
          </div>
        ) : (
          <>
            {failed ? (
              <div className="export-errors">
                {job.errors.map((e) => (
                  <div key={e.name}>
                    <strong>{e.name}</strong>
                    <span>{e.message}</span>
                  </div>
                ))}
              </div>
            ) : null}
            <div className="export-card-actions">
              {job.lastPath && window.framemark ? (
                <button className="btn btn-ghost glass-btn glass-ripple glass-press" onClick={() => void window.framemark!.showInFolder(job.lastPath!)} type="button">
                  <FolderOpen size={15} />
                  在文件夹中显示
                </button>
              ) : null}
              {job.lastPath && !window.framemark ? (
                <span className="hint-note">
                  <Download size={14} /> 已保存到系统相册
                </span>
              ) : null}
              <span className="spacer" />
              {canShare ? (
                <button
                  className="btn btn-ghost glass-btn glass-ripple glass-press"
                  onClick={() => {
                    void GallerySaver.shareImage({
                      uri: job.lastPath!,
                      mimeType: shareMime(job.lastPath!),
                      title: '分享照片'
                    }).catch((error: unknown) => {
                      showToast(error instanceof Error ? error.message : '分享失败', 'error')
                    })
                  }}
                  type="button"
                >
                  <Share2 size={15} />
                  分享
                </button>
              ) : null}
              <button className="btn btn-primary glass-btn glass-spot glass-ripple glass-press" onClick={close} type="button">
                完成
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
