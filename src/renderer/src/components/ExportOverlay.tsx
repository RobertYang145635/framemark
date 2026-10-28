import React, { useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { AlertTriangle, Check, Download, FolderOpen, Loader2, RotateCcw, Share2, X } from 'lucide-react'
import { useAppStore } from '../store'
import { GallerySaver } from '../core/gallery'

function shareMime(name: string): string {
  if (/\.png$/i.test(name)) return 'image/png'
  if (/\.webp$/i.test(name)) return 'image/webp'
  return 'image/jpeg'
}

export function ExportOverlay() {
  const job = useAppStore((s) => s.job)
  const resumable = useAppStore((s) => s.resumable)
  const cancel = useAppStore((s) => s.cancelExport)
  const close = useAppStore((s) => s.closeJob)
  const resumeExport = useAppStore((s) => s.resumeExport)
  const retryFailed = useAppStore((s) => s.retryFailed)
  const dismissJob = useAppStore((s) => s.dismissJob)
  const showToast = useAppStore((s) => s.showToast)
  // 「稍后」 only hides THIS card; the task itself stays persisted and resumable.
  const [hiddenResumeId, setHiddenResumeId] = useState<string | null>(null)

  if (!job) {
    if (!resumable || resumable.id === hiddenResumeId) return null
    const resumePct = resumable.total ? Math.round((resumable.done / resumable.total) * 100) : 0
    return (
      <div className="export-overlay">
        <div className="export-card glass">
          <div className="export-card-head">
            <span className="export-icon">
              <Download size={22} />
            </span>
            <div>
              <h3>有未完成的导出</h3>
              <p>
                上次导到 {resumable.done}/{resumable.total}，可继续
              </p>
            </div>
            <button className="icon-btn" onClick={() => setHiddenResumeId(resumable.id)} title="稍后">
              <X size={18} />
            </button>
          </div>
          <div className="progress-track">
            <span style={{ width: `${Math.max(4, resumePct)}%` }} />
          </div>
          <div className="export-card-actions">
            <button
              className="btn btn-primary glass-btn glass-spot glass-ripple glass-press"
              onClick={() => void resumeExport(resumable.id)}
              type="button"
            >
              继续
            </button>
            <span className="spacer" />
            <button
              className="btn btn-ghost glass-btn glass-ripple glass-press"
              onClick={() => setHiddenResumeId(resumable.id)}
              type="button"
            >
              稍后
            </button>
          </div>
        </div>
      </div>
    )
  }

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
  const skipped = job.skipped ?? 0
  const success = Math.max(0, job.done - job.errors.length - skipped)
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
                : `成功 ${success} 张${skipped ? ` · 跳过 ${skipped} 张（之前已导出）` : ''}${failed ? ` · 失败 ${job.errors.length} 张` : ''}`}
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
          <>
            <div className="export-card-actions">
              <button className="btn btn-ghost glass-btn glass-ripple glass-press" onClick={cancel} disabled={job.cancelled} type="button">
                {job.cancelled ? '正在取消…' : '取消导出'}
              </button>
              <span className="progress-num">{pct}%</span>
            </div>
            {Capacitor.isNativePlatform() ? (
              <span className="hint-note">锁屏或切到后台时导出可能变慢，但不会中断</span>
            ) : null}
          </>
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
              {failed && job.taskId ? (
                <button
                  className="btn btn-ghost glass-btn glass-ripple glass-press"
                  onClick={() => void retryFailed(job.taskId!)}
                  type="button"
                >
                  <RotateCcw size={15} />
                  重试失败项
                </button>
              ) : null}
              {!failed && skipped > 0 && job.taskId ? (
                <button
                  className="btn btn-ghost glass-btn glass-ripple glass-press"
                  onClick={() => void dismissJob()}
                  type="button"
                  title="这批照片已记入导出历史；想用当前设计重新导出，请先新建任务"
                >
                  新建任务
                </button>
              ) : null}
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
