/**
 * 逐套差异化面板：给一条 design（当前卡片），按 `core/controls` 的 JSON 记录渲染它专属的控件集合。
 *  - 分组折叠：content / layout / detail（玻璃原型 L2 的三档披露）
 *  - provenance 可见：厂商授权 / IR 推断 / 本项目补充，三类一眼能分
 *  - fidelityRisk 控件额外提示“改它会离开厂商原版版式”（R0–R11 清单要求逐套交代）
 *  - 读写只走 store.setParam ⇒ `design.paramOverrides[variantId]`（参数分桶），不直接写别的样式
 */
import './controls.css'
import { ChevronDown, RotateCcw, ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import type { ParamValue } from '../../types'
import { controlsFor, paramKeyOf, styleIdForDesign } from '../../core/controls'
import type { ControlView } from '../../core/controls'
import type { ControlGroup, ControlProvenance } from '../../core/controls/schema'
import { useAppStore } from '../../store'
import {
  ControlAnchorGrid,
  ControlBrandPicker,
  ControlGravitySegment,
  ControlSlider,
  ControlText,
  ControlThemeSwatches,
  ControlToggle,
  ControlXyOffset
} from './KindControls'

const PROV_LABEL: Record<ControlProvenance, { text: string; cls: string }> = {
  vendor: { text: '厂商授权', cls: 'vendor' },
  inferred: { text: 'IR 推断（非官方）', cls: 'inferred' },
  manual: { text: '本项目补充（非官方）', cls: 'manual' }
}

const GROUP_LABEL: Record<ControlGroup, string> = {
  content: '内容',
  layout: '版式',
  detail: '细节'
}

const GROUP_ORDER: ControlGroup[] = ['content', 'layout', 'detail']

function renderView (view: ControlView, styleId: string, onChange: (key: string, value: ParamValue) => void) {
  const props = { view, styleId, onChange }
  switch (view.control.kind) {
    case 'toggle': return <ControlToggle {...props} />
    case 'text': return <ControlText {...props} />
    case 'slider': return <ControlSlider {...props} />
    case 'anchorGrid': return <ControlAnchorGrid {...props} />
    case 'xyOffset': return <ControlXyOffset {...props} />
    case 'themeSwatches': return <ControlThemeSwatches {...props} />
    case 'brandPicker': return <ControlBrandPicker {...props} />
    case 'gravitySegment': return <ControlGravitySegment {...props} />
    default: return null
  }
}

function Provenance ({ value }: { value: ControlProvenance }) {
  const p = PROV_LABEL[value]
  return <span className={`fmk-prov ${p.cls}`} title={value === 'vendor' ? '厂商配置里就有这一行的可编辑/可关标志' : '厂商没有授权，由本项目推出'}>{p.text}</span>
}

export function StyleControlsPanel ({
  filter,
  compact = false
}: {
  /** Optional allow-list (Phase 3 tabs). Only applied in compact mode. */
  filter?: (view: ControlView) => boolean
  /** Flat, live-only rendering for the Logo/文字/布局/主题 editor tabs. */
  compact?: boolean
} = {}) {
  const design = useAppStore((s) => s.design)
  const setParam = useAppStore((s) => s.setParam)
  const resetVariantParams = useAppStore((s) => s.resetVariantParams)
  const [open, setOpen] = useState<Record<string, boolean>>({ content: true, layout: true, detail: false })

  const found = controlsFor(design)
  if (!found) return null
  const { record, views } = found
  const styleId = styleIdForDesign(design)
  const liveCount = views.filter((v) => v.live).length

  const onChange = (key: string, value: ParamValue) => setParam(key, value)

  if (compact) {
    // Phase 3 tabs: one flat list, live controls only. Provenance / evidence /
    // 保真击穿 tags and group headers stay out of the user-facing tabs; the full
    // panel below remains for dev/legacy. An empty result renders nothing so a
    // tab never shows a fake control row.
    const list = views.filter((v) => v.live && (!filter || filter(v)))
    if (!list.length) return null
    return (
      <section className="panel-section fmk-style-controls editor-controls" data-style={record.styleId}>
        <div className="editor-controls-head">
          <button
            className="btn btn-ghost btn-sm"
            type="button"
            onClick={resetVariantParams}
            title="只清空这一套的改动，其它套不受影响"
          >
            <RotateCcw size={14} />
            全部原版
          </button>
        </div>
        <div className="editor-controls-list">
          {list.map((view) => (
            <div
              className="editor-controls-item"
              key={`${view.control.id}:${paramKeyOf(view.control)}`}
              data-control-id={view.control.id}
            >
              {renderView(view, styleId, onChange)}
            </div>
          ))}
        </div>
      </section>
    )
  }

  return (
    <section className="panel-section fmk-style-controls" data-style={record.styleId}>
      <div className="section-head">
        <div>
          <h3>本套专属控件</h3>
          <p>
            {record.name} · {record.family} · {views.length} 个控件（{liveCount} 个已接入渲染）
          </p>
        </div>
        <button className="btn btn-ghost btn-sm" type="button" onClick={resetVariantParams} title="只清空这一套的改动，其它套不受影响">
          <RotateCcw size={14} />
          全部原版
        </button>
      </div>

      <div className="fmk-legend">
        <Provenance value="vendor" />
        <Provenance value="inferred" />
        <Provenance value="manual" />
        <span className="fmk-prov pending">未接线</span>
      </div>

      {GROUP_ORDER.map((group) => {
        const inGroup = views.filter((v) => v.control.group === group)
        if (!inGroup.length) return null
        const expanded = open[group] ?? false
        return (
          <div className={`fmk-group ${expanded ? 'open' : ''}`} key={group}>
            <button type="button" className="fmk-group-head" onClick={() => setOpen((o) => ({ ...o, [group]: !expanded }))}>
              <ChevronDown size={14} className={expanded ? 'rot' : ''} />
              <span>{GROUP_LABEL[group]}</span>
              <em>{inGroup.filter((v) => v.live).length}/{inGroup.length}</em>
            </button>
            {expanded ? (
              <div className="fmk-group-body">
                {inGroup.map((view) => (
                  <div className="fmk-item" key={`${view.control.id}:${paramKeyOf(view.control)}`}>
                    <div className="fmk-item-controls">
                      {renderView(view, styleId, onChange)}
                      {!view.live ? <p className="fmk-block-note">{view.blockedReason}</p> : null}
                    </div>
                    <div className="fmk-item-meta">
                      <Provenance value={view.control.provenance} />
                      <span className={`fmk-prov ${view.live ? 'live' : 'pending'}`}>{view.live ? '已接入渲染' : '未接线'}</span>
                      {view.control.fidelityRisk ? (
                        <span className="fmk-prov risk" title="改动会离开厂商原版版式（见 risks.md R0–R11）">
                          <ShieldAlert size={11} /> 保真击穿
                        </span>
                      ) : null}
                      {view.status === 'existing' && !view.isDefault ? <span className="fmk-prov dirty">已改动</span> : null}
                    </div>
                    <code className="fmk-evidence" title={`${view.control.evidence.ref}\n${view.control.evidence.detail}`}>
                      {view.control.evidence.source} · {view.control.evidence.match}
                    </code>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        )
      })}

      {record.notes ? <p className="fmk-notes">{record.notes}</p> : null}
    </section>
  )
}
