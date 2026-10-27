/**
 * 8 种控件 kind 各一个受控组件（与 core/controls/schema.ts 的 ControlKind 一一对应）。
 * 全部是「受控 + 数据驱动」：值与可用范围都来自 JSON 记录，组件里不写任何具体样式的参数。
 * 非 live 的控件（消费点 pending/extend 还没在 core/system 落地）渲染成 disabled 并写明原因，
 * 不做“能操作但画面不动”的假控件。
 */
import { useEffect, useState } from 'react'
import type { ParamValue } from '../../types'
import type { ControlView } from '../../core/controls'
import type {
  AnchorGridControl,
  BrandPickerControl,
  GravitySegmentControl,
  SliderControl,
  TextControl,
  ThemeSwatchesControl,
  ToggleControl,
  XyOffsetControl
} from '../../core/controls/schema'
import { BRANDS, pickVariant } from '../../core/brands'
import { getStockStyle } from '../../core/system/styles'
import { Segmented, Slider } from '../ui'

export type ControlChange = (key: string, value: ParamValue) => void

interface Props {
  view: ControlView
  styleId: string
  onChange: ControlChange
}

function num (v: ParamValue | null, fallback: number): number {
  // null / undefined / '' / NaN ⇒ 回落 schema 声明的默认（slotAlpha 的默认是 null＝“没覆盖”，
  // 而 Number(null) === 0 会把滑杆显示成 0，那条控件就会看起来“一上来就是最小值”）。
  if (v === null || v === undefined || v === '') return fallback
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

/* ---------------- 1. toggle ---------------- */
export function ControlToggle ({ view, onChange }: Props) {
  const control = view.control as ToggleControl
  const on = view.value !== false
  return (
    <label className={`fmk-row ${view.live ? '' : 'is-blocked'}`} title={view.blockedReason ?? undefined}>
      <span className="fmk-row-label">{control.label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        className={`fmk-switch ${on ? 'on' : ''}`}
        disabled={!view.live}
        onClick={() => onChange(view.paramKey, !on)}
      >
        <i />
      </button>
    </label>
  )
}

/* ---------------- 2. text ---------------- */
export function ControlText ({ view, onChange }: Props) {
  const control = view.control as TextControl
  const current = typeof view.value === 'string' ? view.value : ''
  const [draft, setDraft] = useState<string>(current)
  useEffect(() => { setDraft(current) }, [current, view.paramKey])
  const max = control.domain.maxLength ?? 64
  const min = control.domain.minLength
  const commit = () => {
    if (draft === current) return
    onChange(view.paramKey, draft)
  }
  return (
    <label className={`fmk-field ${view.live ? '' : 'is-blocked'}`} title={view.blockedReason ?? undefined}>
      <span className="fmk-field-head">
        <span>{control.label}</span>
        <em className="fmk-limit">
          {min ? `${min}–` : '≤'}
          {max} 字
        </em>
      </span>
      <input
        value={draft}
        maxLength={max}
        disabled={!view.live}
        placeholder="留空＝厂商原版文案"
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => { if (e.key === 'Enter') commit() }}
      />
      {control.domain.multiline ? <small>支持多行</small> : null}
    </label>
  )
}

/* ---------------- 3. slider ---------------- */
export function ControlSlider ({ view, onChange }: Props) {
  const control = view.control as SliderControl
  const d = control.domain
  const fallback = control.default === null ? 1 : Number(control.default)
  return (
    <div className={view.live ? '' : 'is-blocked'} title={view.blockedReason ?? undefined}>
      <Slider
        label={control.label}
        value={num(view.value, fallback)}
        min={d.min}
        max={d.max}
        step={d.step}
        suffix={d.unit === 'multiplier' ? '×' : ''}
        disabled={!view.live}
        onChange={(v) => onChange(view.paramKey, v)}
      />
    </div>
  )
}

/* ---------------- 4. anchorGrid ---------------- */
const ANCHOR_LABEL: Record<string, string> = {
  leftTop: '左上',
  leftCenter: '左中',
  leftBottom: '左下',
  centerTop: '上中',
  center: '正中',
  centerBottom: '下中',
  rightTop: '右上',
  rightCenter: '右中',
  rightBottom: '右下'
}

/* IR 的条带键是 `b0`/`b1`…；界面上按人话说“第 N 层”。 */
function bandLabel (band: unknown): string {
  const m = /^b(\d+)$/.exec(String(band ?? ''))
  return m ? `第 ${Number(m[1]) + 1} 层` : String(band ?? '')
}

export function ControlAnchorGrid ({ view, styleId, onChange }: Props) {
  const control = view.control as AnchorGridControl
  const value = typeof view.value === 'string' ? view.value : 'vendor'
  // targetBand 是 IR 里的条带下标（'b0'…）；只有 overlay / 旋转条带有“可搬的东西”，
  // 纯边框式卡片不给九宫格，免得出现点了没反应的死控件（与 Inspector 的 stockMovable 同规则）。
  const bands = getStockStyle(styleId)?.bands ?? []
  const bandIndex = Number(String(control.targetBand).replace(/^b/, ''))
  const band = Number.isInteger(bandIndex) ? bands[bandIndex] : undefined
  const movable = Boolean(band && (band.direction === 'overlay' || Boolean(band.rotation)))
  return (
    <div className="fmk-anchor">
      <div className="fmk-field-head">
        <span>{control.label}</span>
        <em className="fmk-limit">条带 {bandLabel(control.targetBand)}</em>
      </div>
      <div className="fmk-anchor-body">
        <div className="fmk-anchor-grid" role="group" aria-label="九宫格位置" style={{ opacity: movable ? 1 : 0.5 }}>
          {control.domain.options
            .filter((o) => o !== 'vendor')
            .map((o) => (
              <button
                key={o}
                type="button"
                className={`fmk-anchor-cell ${value === o ? 'active' : ''}`}
                title={ANCHOR_LABEL[o]}
                disabled={!movable}
                onClick={() => onChange(view.paramKey, o)}
              >
                <i />
              </button>
            ))}
        </div>
        <button
          type="button"
          className={`fmk-vendor-chip ${value === 'vendor' ? 'on' : ''}`}
          onClick={() => onChange(view.paramKey, 'vendor')}
          title={movable ? '厂商原版位置（逐像素基线）' : '这套没有可移动的水印条带，位置由厂商固定'}
        >
          厂商原版
        </button>
      </div>
    </div>
  )
}

/* ---------------- 5. xyOffset ---------------- */
export function ControlXyOffset ({ view, onChange }: Props) {
  const control = view.control as XyOffsetControl
  const d = control.domain
  return (
    <Slider
      label={`${control.label}（条带 ${bandLabel(control.targetBand)}）`}
      value={num(view.value, 0)}
      min={d.min}
      max={d.max}
      step={d.step}
      suffix="%"
      disabled={!view.live}
      onChange={(v) => onChange(view.paramKey, v)}
    />
  )
}

/* ---------------- 6. themeSwatches ---------------- */
/** 厂商主题键名是内部枚举；界面只说人话，未知键仍照实显示。 */
const THEME_LABELS: Record<string, string> = {
  white: '白底',
  black: '黑底',
  light_blur: '浅色虚化',
  dark_blur: '深色虚化'
}

function themeLabel (id: string, fallback?: string): string {
  const known = THEME_LABELS[id]
  if (known) return known
  // Some vendors already ship a Chinese label; keep it instead of the enum key.
  if (fallback && /[^\x00-\x7F]/.test(fallback)) return fallback
  return id
}

export function ControlThemeSwatches ({ view, styleId, onChange }: Props) {
  const control = view.control as ThemeSwatchesControl
  const style = getStockStyle(styleId)
  const themes = style?.themes ?? []
  const current = typeof view.value === 'string' ? view.value : (style?.defaultTheme ?? control.domain.options[0] ?? '')
  return (
    <div className="fmk-swatches">
      <div className="fmk-field-head">
        <span>{control.label}</span>
        <em className="fmk-limit">{control.domain.themeCount} 套主题</em>
      </div>
      {control.domain.options.map((id) => {
        const t = themes.find((x) => x.id === id)
        const swatch = t?.swatch
        const label = themeLabel(id, t?.label)
        return (
          <button
            key={id}
            type="button"
            className={`fmk-swatch ${current === id ? 'active' : ''}`}
            onClick={() => onChange(view.paramKey, id)}
            title={label}
          >
            <i style={swatch ? { background: swatch } : undefined} />
            <span>{label}</span>
          </button>
        )
      })}
    </div>
  )
}

/* ---------------- 7. brandPicker ---------------- */
export function ControlBrandPicker ({ view, onChange }: Props) {
  const control = view.control as BrandPickerControl
  const current = typeof view.value === 'string' ? view.value : ''
  return (
    <label className="fmk-field">
      <span className="fmk-field-head">
        <span>{control.label}</span>
        <em className="fmk-limit">{control.domain.autoMatch ? '默认自动匹配' : ''}</em>
      </span>
      <select
        className="fmk-select"
        value={current}
        onChange={(e) => onChange(view.paramKey, e.target.value)}
      >
        <option value="">自动匹配（按 EXIF Make/Model）</option>
        {BRANDS.map((b) => {
          const file = pickVariant(b.key, 'light') ?? b.files[0] ?? ''
          return file ? (
            <option key={b.key} value={file}>
              {b.cn} · {b.label}
            </option>
          ) : null
        })}
      </select>
    </label>
  )
}

/* ---------------- 8. gravitySegment ---------------- */
export function ControlGravitySegment ({ view, onChange }: Props) {
  const control = view.control as GravitySegmentControl
  const current = typeof view.value === 'string' ? view.value : String(control.default ?? '')
  return (
    <div className={view.live ? '' : 'is-blocked'} title={view.blockedReason ?? undefined}>
      <div className="fmk-field-head">
        <span>{control.label}</span>
      </div>
      <Segmented
        compact
        value={current || 'vendor'}
        options={[
          { value: 'vendor', label: '原版' },
          ...control.domain.options.map((o) => ({ value: o, label: ANCHOR_LABEL[o] ?? o }))
        ]}
        // 'vendor' 是显式复位哨兵（layout.ts 的 slotGravityOverride 认它＝无覆盖），
        // 否则选过方位就再也回不到厂商原版——只有 absent 才算原版是不够的。
        // 非 live（消费点没接 / 接了但该行几何无余量）⇒ 一律不接受点击，绝不做假控件。
        onChange={(v) => { if (view.live) onChange(view.paramKey, v) }}
      />
    </div>
  )
}
