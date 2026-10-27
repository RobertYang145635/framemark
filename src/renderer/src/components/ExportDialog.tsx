import React, { useEffect, useState } from 'react'
import { FolderOpen, HardDriveDownload } from 'lucide-react'
import type { Design, ExportScope } from '../types'
import { useAppStore } from '../store'
import { Modal, Segmented, Slider, Switch } from './ui'

const FORMAT_OPTIONS: { value: Design['export']['format']; label: string; hint: string }[] = [
  { value: 'jpeg', label: 'JPG', hint: '质量最高、回写 EXIF，日常首选' },
  { value: 'png', label: 'PNG', hint: '无损；不写 EXIF，体积较大' },
  { value: 'webp', label: 'WebP', hint: '无损（桌面）/ 高质量（手机）' },
  { value: 'tiff', label: 'TIFF', hint: '仅桌面：LZW 无损归档' }
]

const LONG_EDGE_CHIPS = [0, 4096, 3072, 2048]

const TOKENS = '{name} {template} {preset} {date} {time} {index} {make} {model} {width} {height}'

export function ExportDialog() {
  const scope = useAppStore((s) => s.exportDialog)
  const close = useAppStore((s) => s.closeExportDialog)
  const design = useAppStore((s) => s.design)
  const setExport = useAppStore((s) => s.setExport)
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const job = useAppStore((s) => s.job)
  const exportCurrent = useAppStore((s) => s.exportCurrent)
  const exportAll = useAppStore((s) => s.exportAll)
  const showToast = useAppStore((s) => s.showToast)
  const [draftScope, setDraftScope] = useState<ExportScope>('current')

  const desktop = Boolean(window.framemark)
  const open = scope !== null

  useEffect(() => {
    if (scope) setDraftScope(scope)
  }, [scope])

  const selected = photos.find((p) => p.id === selectedId) ?? photos[0] ?? null
  const targetCount = draftScope === 'all' ? photos.length : selected ? 1 : 0
  const settings = design.export
  const formatOptions = desktop ? FORMAT_OPTIONS : FORMAT_OPTIONS.filter((f) => f.value !== 'tiff')
  const formatLabel = FORMAT_OPTIONS.find((f) => f.value === settings.format)?.label ?? settings.format
  const edgeLabel = settings.longEdge > 0 ? `${settings.longEdge} px` : '原始尺寸'

  const pickDir = async () => {
    if (!window.framemark) return
    const dir = await window.framemark.pickExportDir()
    if (dir) setExport({ outputDir: dir })
  }

  const start = async () => {
    if (!targetCount) {
      showToast(draftScope === 'all' ? '先添加照片' : '先添加一张照片', 'error')
      return
    }
    close()
    if (draftScope === 'all') await exportAll()
    else await exportCurrent()
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title="导出"
      subtitle="导出设置会随预设一起保存"
      footer={
        <div className="export-dialog-foot">
          <span className="hint-note">
            <HardDriveDownload size={14} />
            {targetCount ? `将导出 ${targetCount} 张 · ${formatLabel} · ${edgeLabel}` : '还没有可导出的照片'}
          </span>
          <span className="spacer" />
          <button className="btn btn-ghost btn-sm" onClick={close} type="button">
            取消
          </button>
          <button
            className="btn btn-primary btn-sm"
            onClick={() => void start()}
            disabled={!targetCount || Boolean(job?.running)}
            type="button"
          >
            开始导出
          </button>
        </div>
      }
    >
      <div className="export-dialog">
        <label className="field">
          <span>范围</span>
          <Segmented
            value={draftScope}
            options={[
              { value: 'current', label: selected ? `当前照片` : '当前照片（无）', hint: selected?.name },
              { value: 'all', label: `全部 ${photos.length} 张`, hint: '逐张套用当前模板' }
            ]}
            onChange={(v) => setDraftScope(v)}
          />
        </label>

        <label className="field">
          <span>格式</span>
          <Segmented
            value={settings.format === 'tiff' && !desktop ? 'jpeg' : settings.format}
            options={formatOptions}
            onChange={(v) => setExport({ format: v })}
          />
        </label>

        {settings.format === 'jpeg' ? (
          <Slider
            label="JPG 质量"
            value={settings.quality}
            min={60}
            max={100}
            step={1}
            onChange={(v) => setExport({ quality: v })}
            hint="100 = 4:4:4 最高质量"
          />
        ) : null}

        <label className="field">
          <span>长边限制</span>
          <div className="export-long-edge">
            {LONG_EDGE_CHIPS.map((px) => (
              <button
                key={px}
                type="button"
                className={`toggle-chip ${settings.longEdge === px ? 'on' : ''}`}
                onClick={() => setExport({ longEdge: px })}
              >
                {px === 0 ? '原始尺寸' : String(px)}
              </button>
            ))}
            <input
              className="export-long-edge-input"
              type="number"
              min={0}
              max={20000}
              step={100}
              value={settings.longEdge === 0 ? '' : settings.longEdge}
              placeholder="原始"
              onChange={(e) => setExport({ longEdge: Math.max(0, Number(e.target.value) || 0) })}
              aria-label="长边像素"
            />
            <em>px</em>
          </div>
        </label>

        <label className="field">
          <span>文件名</span>
          <input
            value={settings.filenamePattern}
            onChange={(e) => setExport({ filenamePattern: e.target.value })}
            placeholder="{name}-{template}"
          />
          <em className="field-hint">可用变量：{TOKENS}</em>
        </label>

        {desktop ? (
          <label className="field">
            <span>输出文件夹</span>
            <div className="export-dir-row">
              <input value={settings.outputDir} readOnly title={settings.outputDir} />
              <button className="btn btn-ghost btn-sm" onClick={() => void pickDir()} type="button">
                <FolderOpen size={14} />
                选择
              </button>
            </div>
          </label>
        ) : (
          <div className="field">
            <span>输出位置</span>
            <p className="hint-note">保存到系统相册 Pictures/FrameMark；单张导出后可分享。</p>
          </div>
        )}

        <Switch
          checked={settings.removeGps}
          onChange={(v) => setExport({ removeGps: v })}
          label="移除 GPS 信息"
          hint={desktop ? '导出时回写 EXIF，但清空 GPS 字段' : '仅 JPG 会回写 EXIF'}
        />
      </div>
    </Modal>
  )
}