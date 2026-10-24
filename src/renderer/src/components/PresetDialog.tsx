import React, { useEffect, useRef, useState } from 'react'
import { Check, Download, FileJson, Trash2, Upload } from 'lucide-react'
import { useAppStore } from '../store'
import { Modal } from './ui'

export function PresetDialog() {
  const open = useAppStore((s) => s.presetDialogOpen)
  const setOpen = useAppStore((s) => s.setPresetDialogOpen)
  const presets = useAppStore((s) => s.presets)
  const activePresetId = useAppStore((s) => s.activePresetId)
  const savePreset = useAppStore((s) => s.savePreset)
  const applyPreset = useAppStore((s) => s.applyPreset)
  const deletePreset = useAppStore((s) => s.deletePreset)
  const importPresetFile = useAppStore((s) => s.importPresetFile)
  const exportPresetFile = useAppStore((s) => s.exportPresetFile)
  const design = useAppStore((s) => s.design)
  const [name, setName] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open && !name) {
      const d = new Date()
      setName(`我的预设 ${d.getMonth() + 1}-${d.getDate()}`)
    }
  }, [open, name])

  return (
    <Modal
      open={open}
      onClose={() => setOpen(false)}
      title="预设"
      subtitle="保存当前模板、Logo、水印与导出设置，或导入另一台设备的预设。"
      footer={
        <div className="preset-foot">
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0]
              if (f) void importPresetFile(f)
              e.target.value = ''
            }}
          />
          <button className="btn btn-ghost btn-sm" onClick={() => fileRef.current?.click()} type="button">
            <Upload size={14} />
            导入预设
          </button>
          <button className="btn btn-ghost btn-sm" onClick={exportPresetFile} type="button">
            <Download size={14} />
            导出预设
          </button>
          <span className="spacer" />
          <button className="btn btn-primary btn-sm" onClick={() => setOpen(false)} type="button">
            完成
          </button>
        </div>
      }
    >
      <div className="preset-save-box">
        <label className="field">
          <span>预设名称</span>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：Panasonic 白边" />
        </label>
        <button
          className="btn btn-primary"
          onClick={async () => {
            await savePreset(name || design.templateId)
            setName('')
            setOpen(false)
          }}
          type="button"
        >
          <FileJson size={15} />
          保存当前设计
        </button>
      </div>
      <div className="preset-list">
        {presets.length ? (
          presets.map((p) => (
            <div key={p.id} className={`preset-row ${p.id === activePresetId ? 'active' : ''}`}>
              <div>
                <strong>{p.name}</strong>
                <em>{new Date(p.updatedAt).toLocaleString()}</em>
              </div>
              <div className="preset-row-actions">
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => {
                    applyPreset(p.id)
                    setOpen(false)
                  }}
                  type="button"
                >
                  <Check size={14} />
                  应用
                </button>
                <button className="icon-btn danger" onClick={() => void deletePreset(p.id)} title="删除预设" type="button">
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
          ))
        ) : (
          <div className="empty-note">还没有用户预设。保存当前设计后会出现在这里，也会出现在顶部下拉框。</div>
        )}
      </div>
    </Modal>
  )
}
