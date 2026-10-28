import React, { useEffect, useState } from 'react'
import { FolderOpen, HardDriveDownload } from 'lucide-react'
import type { Design, ExportScope } from '../types'
import { useAppStore } from '../store'
import { DEFAULT_EXPORT } from '../core/presets'
import { Modal, Segmented, Slider, Switch } from './ui'

const FORMAT_OPTIONS: { value: Design['export']['format']; label: string; hint: string }[] = [
  { value: 'jpeg', label: 'JPG', hint: '质量最高、回写 EXIF，日常首选' },
  { value: 'png', label: 'PNG', hint: '无损；不写 EXIF，体积较大' },
  // A3: Android WebP is VP8L lossless too (measured, S8); the old
  // 「无损（桌面）/ 高质量（手机）」 label advertised a lossy phone path that
  // no longer exists.
  { value: 'webp', label: 'WebP', hint: '无损（VP8L），桌面与手机相同' },
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
  const [autoSwitchedToJpeg, setAutoSwitchedToJpeg] = useState(false)

  const desktop = Boolean(window.framemark)
  const open = scope !== null

  useEffect(() => {
    if (scope) setDraftScope(scope)
  }, [scope])

  /**
   * A3: TIFF is desktop-only. The old dialog only LIED in the segmented control
   * (`value` showed JPG while `design.export.format` stayed 'tiff'), so the
   * export still ran the tiff branch and either threw (Android) or wrote a
   * WebP-in-.tiff (web). Write the unsupported choice back into the design
   * once, when the dialog opens, and say so in one line.
   */
  useEffect(() => {
    if (!scope) {
      setAutoSwitchedToJpeg(false)
      return
    }
    if (!desktop && design.export.format === 'tiff') {
      setExport({ format: 'jpeg' })
      setAutoSwitchedToJpeg(true)
    }
  }, [scope, desktop, design.export.format, setExport])

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
            value={settings.format}
            options={formatOptions}
            onChange={(v) => {
              setExport({ format: v })
              setAutoSwitchedToJpeg(false)
            }}
          />
          {autoSwitchedToJpeg ? (
            <em className="field-hint" role="status">
              当前平台不支持 TIFF，已改为 JPG 导出。
            </em>
          ) : null}
        </label>

        {settings.format === 'jpeg' ? (
          <Slider
            label="JPG 质量"
            value={settings.quality}
            min={60}
            max={100}
            step={1}
            onChange={(v) => setExport({ quality: v })}
            onReset={() => setExport({ quality: DEFAULT_EXPORT.quality })}
          />
        ) : null}
        {/* A3: measured (S8 + A2): Chromium JPEG switches to 4:2:0 for every
            quality below 100, desktop sharp stays 4:4:4. The slider stays fully
            usable — this only states the consequence. */}
        {!desktop && settings.format === 'jpeg' && settings.quality < 100 ? (
          <em className="field-hint">低于 100 会降低颜色精度（与桌面不同）</em>
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
              placeholder="自定义"
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