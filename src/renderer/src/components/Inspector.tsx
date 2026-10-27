import React, { useEffect, useRef, useState } from 'react'
import {
  Camera,
  Droplets,
  Eye,
  EyeOff,
  Frame,
  Image as ImageIcon,
  ImagePlus,
  Images,
  LayoutGrid,
  Palette,
  RotateCcw,
  Sparkles,
  Trash2,
  Type,
  Upload
} from 'lucide-react'
import type { Anchor, EditorTab, Ratio, TextSettings, TextSlot, TextToken } from '../types'
import { useAppStore } from '../store'
import { getTemplate } from '../core/templates'
import { getVariant } from '../core/variants'
import { LOGO_VERSION_BY_ID } from '../core/logo-catalog'
import { DISPLAY_LOGO_BRAND_COUNT, SELECTED_LOGO_VERSION_COUNT } from '../core/logo-selection.generated'
import { cutForVersionSurface, surfaceForDesign } from '../core/logo-compat'
import { getStockStyle } from '../core/system/styles'
import { controlsFor } from '../core/controls'
import type { ControlView } from '../core/controls'
import {
  availableEditorTabs,
  editorTabForControl,
  isHandTemplate,
  stockHasBrandSlot
} from '../core/editor/capabilities'
import { Section, Segmented, Slider, Switch } from './ui'
import { StyleControlsPanel } from './controls/StyleControlsPanel'
import { HandControlsPanel } from './controls/HandControlsPanel'
import { handControlsFor } from '../core/controls/hand'
import { declaredSlotKey, declaredTextSlots } from '../core/text-slots'
import { DEFAULT_WATERMARK } from '../core/presets'

const RATIO_OPTIONS: { value: Ratio; label: string }[] = [
  { value: 'original', label: '原始' },
  { value: '1:1', label: '1:1' },
  { value: '4:5', label: '4:5' },
  { value: '16:9', label: '16:9' },
  { value: '3:2', label: '3:2' }
]

/**
 * The nine-grid position picker the watermark layer already uses (`.anchor-grid`
 * in styles.css). Exported within this file so stock-style cards reuse the SAME
 * visual control and the SAME labels — one mental model for "位置可调".
 */
const ANCHOR_OPTIONS: { value: Anchor; label: string }[] = [
  { value: 'top-left', label: '左上' },
  { value: 'top-center', label: '上中' },
  { value: 'top-right', label: '右上' },
  { value: 'middle-left', label: '左中' },
  { value: 'center', label: '居中' },
  { value: 'middle-right', label: '右中' },
  { value: 'bottom-left', label: '左下' },
  { value: 'bottom-center', label: '下中' },
  { value: 'bottom-right', label: '右下' }
]

function AnchorGrid({ value, onSelect }: { value: string; onSelect: (a: Anchor) => void }) {
  return (
    <div className="anchor-grid">
      {ANCHOR_OPTIONS.map((a) => (
        <button
          key={a.value}
          type="button"
          className={value === a.value ? 'active' : ''}
          onClick={() => onSelect(a.value)}
          title={a.label}
        >
          <span />
        </button>
      ))}
    </div>
  )
}

function ThemeSwatches() {
  const design = useAppStore((s) => s.design)
  const setParam = useAppStore((s) => s.setParam)
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
  const themes = variant?.themes?.length ? variant.themes : def.themes
  if (!themes?.length) return null
  const active = String(design.params.theme ?? variant?.defaultTheme ?? themes[0].id)
  return (
    <div className="theme-swatches">
      {themes.map((t) => (
        <button
          key={t.id}
          className={`theme-swatch ${active === t.id ? 'active' : ''}`}
          onClick={() => setParam('theme', t.id)}
          title={t.label}
          type="button"
        >
          <span style={{ background: t.swatch }} />
          <em>{t.label}</em>
        </button>
      ))}
    </div>
  )
}

function LogoSection() {
  const design = useAppStore((s) => s.design)
  const setBrandPickerOpen = useAppStore((s) => s.setBrandPickerOpen)
  const autoMatchLogo = useAppStore((s) => s.autoMatchLogo)
  const clearLogo = useAppStore((s) => s.clearLogo)
  const setText = useAppStore((s) => s.setText)
  const setParam = useAppStore((s) => s.setParam)
  const resetParam = useAppStore((s) => s.resetParam)
  const def = getTemplate(design.templateId)
  // Stock cards (Task-2, option (b) — do not leave a control that does nothing):
  // `显示 Logo` (text.showLogo) and `Logo 大小` (params.logoScale) are now honored
  // by the stock engine for brands slots and by the hand templates, so both are
  // shown. `brandLogoFile` drives `isBrandLogo` slots only, so stock styles with
  // no such node (e.g. the two 哈苏文字 cards, whose logo is a fixed co-brand
  // mark) still drop the whole section.
  const isStock = def.id === 'systemCamera'
  const stockStyle = isStock ? getStockStyle(String(design.params.systemStyle ?? '')) : null
  if (isStock && !stockHasBrandSlot(stockStyle)) return null

  const surface = surfaceForDesign(design)
  const version = design.brandVersion ? LOGO_VERSION_BY_ID[design.brandVersion.versionId] ?? null : null
  const title = version?.name ?? design.logo?.name ?? '未选择'
  const unavailable = Boolean(version && !cutForVersionSurface(version, surface))

  return (
    <Section
      title="品牌 Logo"
      hint={`自动匹配 EXIF 品牌，或从 ${DISPLAY_LOGO_BRAND_COUNT} 个品牌 / ${SELECTED_LOGO_VERSION_COUNT} 个已选版本中选择`}
      action={
        <button className="btn btn-ghost btn-sm" onClick={() => void autoMatchLogo()} type="button">
          <Sparkles size={14} />
          自动匹配
        </button>
      }
    >
      <div className="logo-row">
        <div className={`logo-section-preview on-${surface} ${unavailable ? 'unavailable' : ''}`}>
          {design.logo ? (
            <img src={design.logo.dataUrl} alt={design.logo.name} />
          ) : (
            <span className="logo-empty">
              <Camera size={20} />
            </span>
          )}
        </div>
        <div className="logo-section-meta">
          <strong className="logo-section-name" title={title}>{title}</strong>
          {version ? <em className="logo-section-years">{version.years}</em> : null}
          {unavailable ? (
            <span className="logo-section-warn">
              当前{surface === 'dark' ? '深色' : '浅色'}底不显示这个版本，请更换或切换底色
            </span>
          ) : null}
          <div className="logo-actions">
            <button className="btn btn-ghost btn-sm" onClick={() => setBrandPickerOpen(true)} type="button">
              <Images size={14} />
              更换
            </button>
            {design.logo ? (
              <button className="btn btn-ghost btn-sm" onClick={clearLogo} type="button">
                <Trash2 size={14} />
                清除
              </button>
            ) : null}
          </div>
        </div>
      </div>
      <Switch
        checked={design.text.showLogo}
        onChange={(v) => setText({ showLogo: v })}
        label="显示 Logo"
        hint="选中品牌后再决定是否显示"
      />
      <Slider
        label="Logo 大小"
        value={Number(design.params.logoScale ?? 5)}
        min={0}
        max={10}
        step={0.5}
        onChange={(v) => setParam('logoScale', v)}
        onReset={() => resetParam('logoScale')}
        disabled={!design.text.showLogo}
      />
    </Section>
  )
}

function AvatarSection() {
  const design = useAppStore((s) => s.design)
  const setAvatarFile = useAppStore((s) => s.setAvatarFile)
  const clearAvatar = useAppStore((s) => s.clearAvatar)
  const inputRef = useRef<HTMLInputElement>(null)
  if (design.templateId !== 'profileFrame') return null
  return (
    <Section title="人物头像" hint="人物模板使用；不上传时显示昵称首字占位">
      <div className="logo-row">
        <div className={`logo-current ${design.avatar ? 'has' : ''}`}>
          {design.avatar ? (
            <img src={design.avatar.dataUrl} alt={design.avatar.name} />
          ) : (
            <span className="logo-empty">
              <Camera size={20} />
            </span>
          )}
        </div>
        <div className="logo-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => inputRef.current?.click()} type="button">
            <ImagePlus size={14} />
            上传头像
          </button>
          {design.avatar ? (
            <button className="btn btn-ghost btn-sm" onClick={clearAvatar} type="button">
              <Trash2 size={14} />
              清除
            </button>
          ) : null}
        </div>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/png,image/webp,image/jpeg"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) void setAvatarFile(file)
        }}
      />
    </Section>
  )
}

function TextSection() {
  const design = useAppStore((s) => s.design)
  const setText = useAppStore((s) => s.setText)
  const slots = useAppStore((s) => s.textSlots)
  const t = design.text
  const hiddenTokens = new Set(t.hidden ?? [])
  const flagOff = ([
    { key: 'showMake', label: '品牌' },
    { key: 'showModel', label: '机型' },
    { key: 'showLens', label: '镜头' },
    { key: 'showExif', label: '参数' },
    { key: 'showDate', label: '日期' }
  ] as const).filter((f) => !t[f.key])
  const toggleHidden = (token: TextToken) => {
    const next = hiddenTokens.has(token)
      ? [...hiddenTokens].filter((item) => item !== token)
      : [...hiddenTokens, token]
    setText({ hidden: next })
  }
  const tokenSources: Record<string, TextToken> = {
    title: 'title',
    subtitle: 'subtitle',
    signature: 'signature',
    customLine: 'customLine',
    location: 'location'
  }
  const groups = SLOT_ORDER.map((anchor) => ({
    anchor,
    rows: slots.filter((slot) => slot.anchor === anchor)
  })).filter((group) => group.rows.length)

  const lineText = t.lineText ?? {}
  const hiddenLines = new Set(t.hiddenLines ?? [])
  const slotValues = t.slotValues ?? {}
  const slotSizes = t.slotSizes ?? {}
  const lineSizes = t.lineSizes ?? {}
  const sizeFor = (row: { key: string; declared: boolean }) =>
    row.declared ? slotSizes[row.key] ?? 1 : lineSizes[row.key] ?? 1
  const setSize = (row: { key: string; declared: boolean }, value: number) => {
    const next = { ...(row.declared ? slotSizes : lineSizes) }
    if (Math.abs(value - 1) < 0.001) delete next[row.key]
    else next[row.key] = value
    setText(row.declared ? { slotSizes: next } : { lineSizes: next })
  }
  const declared = declaredTextSlots(design)
  const declaredRows = declared.map((slot) => {
    const key = declaredSlotKey(design, slot.id)
    const captured = slots.find((row) => row.source === slot.source && row.anchor === slot.anchor)
    return {
      key,
      anchor: slot.anchor,
      value: slotValues[key] ?? '',
      slotText: captured?.text ?? '',
      placeholder: captured?.text || slot.hint,
      source: slot.source,
      hidden: !t[flagOf(slot.id)],
      declared: true
    }
  })
  const declaredGroups = SLOT_ORDER.map((anchor) => ({
    anchor,
    rows: declaredRows.filter((row) => row.anchor === anchor)
  })).filter((group) => group.rows.length)
  const effectiveGroups = declaredGroups.length
    ? declaredGroups
    : groups.map((group) => ({
        anchor: group.anchor,
        rows: group.rows.map((slot) => {
          const token = tokenSources[slot.source]
          return {
            key: slot.key,
            anchor: slot.anchor,
            value: lineText[slot.key] ?? '',
            slotText: slot.text,
            placeholder: slot.text || '（空）',
            source: slot.source,
            hidden: slot.hidden || (token ? hiddenTokens.has(token) : false),
            declared: false
          }
        })
      }))
  const setSlot = (key: string, value: string) => {
    const next = { ...slotValues }
    if (value) next[key] = value
    else delete next[key]
    setText({ slotValues: next })
  }
  const setLine = (key: string, value: string) => {
    const next = { ...lineText }
    if (value) next[key] = value
    else delete next[key]
    setText({ lineText: next })
  }
  const toggleLine = (key: string) => {
    const next = hiddenLines.has(key)
      ? [...hiddenLines].filter((item) => item !== key)
      : [...hiddenLines, key]
    setText({ hiddenLines: next })
  }
  const toggleFlag = (source: TextSlot['source']) => {
    const key =
      source === 'make' ? 'showMake'
        : source === 'model' ? 'showModel'
          : source === 'lens' ? 'showLens'
            : source === 'exif' ? 'showExif'
              : 'showDate'
    setText({ [key]: !t[key] } as Partial<TextSettings>)
  }

  return (
    <Section title="文字" hint="按画面位置直接编辑；每行可单独调字号，照片信息从 EXIF 自动填充，不改照片">
      {effectiveGroups.length ? (
        <div className="text-slot-list">
          {effectiveGroups.map((group) => (
            <div className="text-slot-group" key={group.anchor}>
              <span className="text-slot-anchor">{SLOT_LABEL[group.anchor]}</span>
              <div className="text-slot-rows">
                {group.rows.map((row) => {
                  const token = tokenSources[row.source]
                  const size = sizeFor(row)
                  return (
                    <React.Fragment key={row.key}>
                      <div className={`text-slot-row ${row.hidden ? 'is-hidden' : ''}`}>
                        <input
                          value={row.value}
                          placeholder={row.placeholder || '（空）'}
                          disabled={row.hidden}
                          onChange={(e) =>
                            row.declared ? setSlot(row.key, e.target.value) : setLine(row.key, e.target.value)
                          }
                        />
                        {!row.declared && row.source !== 'photo' && !token ? (
                          <span className="text-slot-readonly" title="照片信息">
                            <em>照片信息</em>
                          </span>
                        ) : null}
                        <button
                          type="button"
                          className="icon-btn text-slot-eye"
                          title={row.hidden ? '恢复这一行' : '隐藏这一行'}
                          onClick={() => {
                            if (row.declared) toggleFlag(row.source)
                            else if (token) toggleHidden(token)
                            else if (row.source === 'photo') toggleLine(row.key)
                            else toggleFlag(row.source)
                          }}
                        >
                          {row.hidden ? <EyeOff size={14} /> : <Eye size={14} />}
                        </button>
                      </div>
                      <div className={`text-slot-size-panel ${row.hidden ? 'is-hidden' : ''}`} title="双击滑杆复位到 100%">
                        <span>字号</span>
                        <input
                          type="range"
                          min={0.6}
                          max={1.6}
                          step={0.02}
                          value={size}
                          disabled={row.hidden}
                          onChange={(e) => setSize(row, Number(e.target.value))}
                          onDoubleClick={() => setSize(row, 1)}
                        />
                        <button
                          type="button"
                          className="text-slot-size-value"
                          onClick={() => setSize(row, 1)}
                          title="点击复位到 100%"
                        >
                          {Math.round(size * 100)}%
                        </button>
                      </div>
                    </React.Fragment>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <p className="hint-note">这个模板当前没有可编辑文字。</p>
      )}
      {hiddenTokens.size || flagOff.length ? (
        <div className="text-hidden-row">
          <em>已隐藏</em>
          {[...hiddenTokens].map((token) => (
            <button key={token} className="toggle-chip" onClick={() => toggleHidden(token)} type="button">
              {TOKEN_LABEL[token]} ↺
            </button>
          ))}
          {flagOff.map((flag) => (
            <button
              key={flag.key}
              className="toggle-chip"
              onClick={() => setText({ [flag.key]: true } as Partial<TextSettings>)}
              type="button"
            >
              {flag.label} ↺
            </button>
          ))}
        </div>
      ) : null}
      {t.fontScale !== 1 ? (
        <div className="text-hidden-row">
          <em>预设整体字号</em>
          <button className="toggle-chip" onClick={() => setText({ fontScale: 1 })} type="button">
            ×{t.fontScale.toFixed(2)} 复位
          </button>
        </div>
      ) : null}
    </Section>
  )
}

const SLOT_ORDER: Anchor[] = [
  'top-left', 'top-center', 'top-right',
  'middle-left', 'center', 'middle-right',
  'bottom-left', 'bottom-center', 'bottom-right'
]

const SLOT_LABEL: Record<Anchor, string> = {
  'top-left': '左上',
  'top-center': '中上',
  'top-right': '右上',
  'middle-left': '左中',
  center: '居中',
  'middle-right': '右中',
  'bottom-left': '左下',
  'bottom-center': '中下',
  'bottom-right': '右下'
}

const FLAG_OF_SLOT = {
  brand: 'showMake',
  model: 'showModel',
  exif: 'showExif',
  date: 'showDate'
} as const

const flagOf = (id: keyof typeof FLAG_OF_SLOT) => FLAG_OF_SLOT[id]

const TOKEN_LABEL: Record<TextToken, string> = {
  title: '标题',
  subtitle: '副标题',
  signature: '签名',
  customLine: '自定义行',
  location: '地点'
}

/**
 * Phase 3: the active editor tab's per-style controls. The capability function
 * decides which tab owns a control, so this list and the other tabs can never
 * show the same control twice.
 */
function EditorControlList({
  tab,
  filter
}: {
  tab: EditorTab
  filter?: (view: ControlView) => boolean
}) {
  return (
    <StyleControlsPanel
      compact
      filter={(view) => editorTabForControl(view) === tab && (!filter || filter(view))}
    />
  )
}

function CurrentTemplateHeader({ onOpenReferences }: { onOpenReferences: () => void }) {
  const design = useAppStore((s) => s.design)
  const resetVariantParams = useAppStore((s) => s.resetVariantParams)
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
  return (
    <div className="current-template">
      <div>
        <span className="eyebrow">{variant.category}</span>
        <h3>{variant.name}</h3>
        <p>{variant.description ?? def.description}</p>
      </div>
      <div className="current-template-actions">
        <button className="btn btn-ghost btn-sm" onClick={onOpenReferences} title="查看这个模板对应的原版参考图" type="button">
          <Images size={14} />
          参考图
        </button>
        <button className="btn btn-ghost btn-sm" onClick={resetVariantParams} title="恢复此模板的默认参数" type="button">
          <RotateCcw size={14} />
          重置
        </button>
      </div>
    </div>
  )
}

function LogoEditor() {
  const design = useAppStore((s) => s.design)
  const stock = !isHandTemplate(design)
  const stockStyle = stock ? getStockStyle(String(design.params.systemStyle ?? '')) : null
  // LogoSection 已经覆盖了 stock 的 `brandLogoFile`（含自动匹配/显隐/大小），
  // 逐套的 brandPicker 控件会变成第二个入口，因此只在没有通用区时保留它。
  const hasLogoSection = !stock || stockHasBrandSlot(stockStyle)
  return (
    <>
      <LogoSection />
      <AvatarSection />
      <EditorControlList
        tab="logo"
        filter={hasLogoSection ? (view) => view.control.kind !== 'brandPicker' : undefined}
      />
    </>
  )
}

function TextEditor() {
  const design = useAppStore((s) => s.design)
  // Stock cards get their own per-slot text controls; the generic title/subtitle
  // fields would be abstract, inert-looking inputs for them.
  const hand = isHandTemplate(design)
  return (
    <>
      {hand ? (
        <>
          <TextSection />
          <HandControlsPanel tab="text" />
        </>
      ) : null}
      <EditorControlList tab="text" />
    </>
  )
}

function LayoutEditor() {
  const design = useAppStore((s) => s.design)
  const setParam = useAppStore((s) => s.setParam)
  const resetParam = useAppStore((s) => s.resetParam)
  const setRatio = useAppStore((s) => s.setRatio)
  const def = getTemplate(design.templateId)
  const controls = def.controls ?? []
  const isMovie = def.id === 'movie'
  const isMinimal = def.id === 'minimal'
  const isFooter = def.id === 'brandFooter'
  const isGlass = def.id === 'glass'
  const isRatio = def.id === 'ratio'
  const isPalette = def.id === 'palette'
  const isBlur = def.id === 'blur'
  const isSystemCamera = def.id === 'systemCamera'
  // A stock card exposes the nine-grid position picker (and the X/Y fine-tune)
  // only when it actually has a movable watermark group (an on-photo overlay,
  // or a rotated edge strip); pure border-frame stock cards have nothing to
  // move and stay honest (no dead control). Same rule as the hidden Logo
  // controls above. See `core/system/layout.ts/solveStockLayout`.
  const stockMovable =
    isSystemCamera &&
    !!getStockStyle(String(design.params.systemStyle ?? ''))?.bands.some((b) => b.direction === 'overlay' || !!b.rotation)
  // 'vendor' = the style's own committed position (pixel-identical default).
  const stockAnchor = String(design.params.stockAnchor ?? 'vendor')

  return (
    <>
      <Section title="画幅">
        <Segmented
          value={design.ratio}
          options={RATIO_OPTIONS}
          onChange={(v) => setRatio(v)}
          compact
        />
        {design.ratio !== 'original' ? (
          <p className="warn-note">非原始画幅会居中裁切照片；批量导出时同样生效。</p>
        ) : null}
      </Section>

      <Section title="版式参数">
        {controls.includes('margin') ? (
          <Slider label="边距" value={Number(design.params.marginPct ?? 6)} min={0} max={15} step={0.5} suffix="%" onChange={(v) => setParam('marginPct', v)} onReset={() => resetParam('marginPct')} />
        ) : null}
        {controls.includes('radius') ? (
          <Slider label="圆角" value={Number(design.params.radiusPct ?? 0)} min={0} max={10} step={0.5} suffix="%" onChange={(v) => setParam('radiusPct', v)} onReset={() => resetParam('radiusPct')} />
        ) : null}
        {controls.includes('shadow') ? (
          <Slider label="阴影" value={Number(design.params.shadow ?? 0)} min={0} max={10} step={0.5} onChange={(v) => setParam('shadow', v)} onReset={() => resetParam('shadow')} />
        ) : null}
        {controls.includes('footer') ? (
          <Slider label="文字区高度" value={Number(design.params.footerPct ?? 12)} min={5} max={25} step={0.5} suffix="%" onChange={(v) => setParam('footerPct', v)} onReset={() => resetParam('footerPct')} />
        ) : null}
        {controls.includes('blur') ? (
          <Slider label="背景模糊" value={Number(design.params.blur ?? 26)} min={0} max={40} step={1} onChange={(v) => setParam('blur', v)} onReset={() => resetParam('blur')} />
        ) : null}
        {controls.includes('grain') ? (
          <Slider label="颗粒" value={Number(design.params.grain ?? 0.12)} min={0} max={0.5} step={0.01} onChange={(v) => setParam('grain', v)} onReset={() => resetParam('grain')} />
        ) : null}
        {isMovie ? (
          <Slider label="上下黑边" value={Number(design.params.barPct ?? 8)} min={3} max={16} step={0.5} suffix="%" onChange={(v) => setParam('barPct', v)} onReset={() => resetParam('barPct')} />
        ) : null}
        {stockMovable ? (
          <div className="field-stack">
            <label className="field compact">
              <span>水印位置</span>
              <button
                className={`toggle-chip ${stockAnchor === 'vendor' ? 'on' : ''}`}
                onClick={() => setParam('stockAnchor', 'vendor')}
                type="button"
                title="厂商原版位置"
              >
                厂商原版
              </button>
            </label>
            <AnchorGrid value={stockAnchor} onSelect={(a) => setParam('stockAnchor', a)} />
            <Slider
              label="水平微调"
              value={Number(design.params.stockOffsetXPct ?? 0)}
              min={-40}
              max={40}
              step={0.5}
              suffix="%"
              onChange={(v) => setParam('stockOffsetXPct', v)}
              onReset={() => resetParam('stockOffsetXPct')}
            />
            <Slider
              label="垂直微调"
              value={Number(design.params.stockOffsetYPct ?? 0)}
              min={-40}
              max={40}
              step={0.5}
              suffix="%"
              onChange={(v) => setParam('stockOffsetYPct', v)}
              onReset={() => resetParam('stockOffsetYPct')}
            />
            <button
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setParam('stockAnchor', 'vendor')
                setParam('stockOffsetXPct', 0)
                setParam('stockOffsetYPct', 0)
              }}
              type="button"
            >
              <RotateCcw size={14} />
              复位为厂商位置
            </button>
          </div>
        ) : null}
        {!controls.length && !isMovie && !stockMovable && !handControlsFor(design.templateId, 'layout').length ? <p className="hint-note">这个版式没有可调的布局参数；文字内容在「文字」页调整。</p> : null}
      </Section>

      {(isMinimal || isFooter) ? (
        <Section title="细节">
          {isMinimal ? (
            <>
              <label className="field compact">
                <span>文字对齐</span>
                <Segmented
                  value={String(design.params.footerAlign ?? 'center') as 'left' | 'center' | 'right'}
                  options={[
                    { value: 'left', label: '左' },
                    { value: 'center', label: '中' },
                    { value: 'right', label: '右' }
                  ]}
                  onChange={(v) => setParam('footerAlign', v)}
                  compact
                />
              </label>
              <label className="field compact">
                <span>Logo 位置</span>
                <Segmented
                  value={String(design.params.logoPos ?? 'none')}
                  options={[
                    { value: 'none', label: '无' },
                    { value: 'footer-left', label: '左下' },
                    { value: 'footer-center', label: '中下' },
                    { value: 'footer-right', label: '右下' }
                  ]}
                  onChange={(v) => setParam('logoPos', v)}
                  compact
                />
              </label>
              <Switch
                checked={String(design.params.borderStyle ?? 'none') === 'thin'}
                onChange={(v) => setParam('borderStyle', v ? 'thin' : 'none')}
                label="照片描边"
              />
            </>
          ) : (
            <label className="field compact">
              <span>Logo 位置</span>
              <Segmented
                value={String(design.params.logoPos ?? 'center')}
                options={[
                  { value: 'left', label: '左' },
                  { value: 'center', label: '中' },
                  { value: 'right', label: '右' }
                ]}
                onChange={(v) => setParam('logoPos', v)}
                compact
              />
            </label>
          )}
        </Section>
      ) : null}

      {isGlass ? (
        <Section title="玻璃卡片">
          <label className="field compact">
            <span>布局</span>
            <Segmented
              value={String(design.params.glassLayout ?? 'card')}
              options={[
                { value: 'card', label: '卡片' },
                { value: 'pill', label: '胶囊' },
                { value: 'palette', label: '色卡' }
              ]}
              onChange={(v) => setParam('glassLayout', v)}
              compact
            />
          </label>
          <label className="field compact">
            <span>位置</span>
            <Segmented
              value={String(design.params.glassAlign ?? 'left')}
              options={[
                { value: 'left', label: '左' },
                { value: 'center', label: '中' },
                { value: 'right', label: '右' }
              ]}
              onChange={(v) => setParam('glassAlign', v)}
              compact
            />
          </label>
        </Section>
      ) : null}

      {isRatio ? (
        <Section title="角标位置">
          <Segmented
            value={String(Number(design.params.corner ?? 3))}
            options={[
              { value: '0', label: '左上' },
              { value: '1', label: '右上' },
              { value: '2', label: '左下' },
              { value: '3', label: '右下' }
            ]}
            onChange={(v) => setParam('corner', Number(v))}
            compact
          />
        </Section>
      ) : null}

      {isPalette ? (
        <Section title="色卡">
          <Switch
            checked={design.params.paletteOverlay === true}
            onChange={(v) => setParam('paletteOverlay', v)}
            label="满幅色卡模式"
            hint="关闭时为白边色卡"
          />
        </Section>
      ) : null}

      {isBlur ? (
        <Section title="底部">
          <Switch
            checked={design.params.minimalFooter === true}
            onChange={(v) => setParam('minimalFooter', v)}
            label="简约日期"
            hint="只显示品牌与日期，隐藏参数行"
          />
        </Section>
      ) : null}

      <HandControlsPanel tab="layout" />

      {/* 逐套差异化控件里归到「布局」的那部分（锚点 / 微调 / 方位）。 */}
      <EditorControlList tab="layout" />
    </>
  )
}

function ThemeEditor() {
  const design = useAppStore((s) => s.design)
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
  const themes = variant?.themes?.length ? variant.themes : def.themes
  // 逐套 themeSwatches 读的也是 params.theme，但选项来自该厂商样式的完整主题
  // （例如 xiaomi 的 white/black/light_blur/dark_blur）；再叠一个通用色卡就是
  // 第二份入口，且选到 blur 主题时通用色卡会全灭。
  const hasStyleTheme = (controlsFor(design)?.views ?? []).some(
    (view) => view.live && editorTabForControl(view) === 'theme'
  )
  return (
    <>
      {themes?.length && !hasStyleTheme ? (
        <Section title="主题">
          <ThemeSwatches />
        </Section>
      ) : null}
      <EditorControlList tab="theme" />
    </>
  )
}

function WatermarkPanel() {
  const design = useAppStore((s) => s.design)
  const setWatermark = useAppStore((s) => s.setWatermark)
  const setWatermarkFile = useAppStore((s) => s.setWatermarkFile)
  const fileRef = useRef<HTMLInputElement>(null)
  const wm = design.watermark
  return (
    <div className="inspector-pane wm-pane">
      <Section title="水印图层" hint="支持透明 PNG/WebP；默认压在照片区域内">
        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/webp,image/jpeg"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) void setWatermarkFile(f)
            e.target.value = ''
          }}
        />
        {wm.asset ? (
          <div className="wm-asset">
            <div className="wm-preview">
              <img src={wm.asset.dataUrl} alt={wm.asset.name} />
            </div>
            <div className="wm-info">
              <strong>{wm.asset.name}</strong>
              <em>
                {wm.asset.width} × {wm.asset.height}
              </em>
              <div className="wm-buttons">
                <button className="btn btn-ghost btn-sm" onClick={() => fileRef.current?.click()} type="button">
                  <Upload size={14} />
                  更换
                </button>
                <button className="btn btn-ghost btn-sm" onClick={() => setWatermark({ asset: null, enabled: false })} type="button">
                  <Trash2 size={14} />
                  移除
                </button>
              </div>
            </div>
          </div>
        ) : (
          <button className="wm-drop" onClick={() => fileRef.current?.click()} type="button">
            <ImagePlus size={22} />
            <strong>选择水印 PNG / WebP</strong>
            <span>推荐带透明背景的白色或黑色 Logo</span>
          </button>
        )}
        <Switch
          checked={wm.enabled && Boolean(wm.asset)}
          onChange={(v) => setWatermark({ enabled: v })}
          label="启用水印"
          hint={wm.asset ? undefined : '先选择一张水印图片'}
          disabled={!wm.asset}
        />
      </Section>

      {wm.asset && wm.enabled ? (
        <>
          <Section title="位置">
            <label className="field compact">
              <span>叠加区域</span>
              <Segmented
                value={wm.target}
                options={[
                  { value: 'photo', label: '照片内' },
                  { value: 'canvas', label: '整张画布' }
                ]}
                onChange={(v) => setWatermark({ target: v })}
                compact
              />
            </label>
            <AnchorGrid value={wm.anchor} onSelect={(a) => setWatermark({ anchor: a })} />
            <Slider label="水平偏移" value={wm.offsetX} min={-40} max={40} step={0.5} suffix="%" onChange={(v) => setWatermark({ offsetX: v })} onReset={() => setWatermark({ offsetX: DEFAULT_WATERMARK.offsetX })} />
            <Slider label="垂直偏移" value={wm.offsetY} min={-40} max={40} step={0.5} suffix="%" onChange={(v) => setWatermark({ offsetY: v })} onReset={() => setWatermark({ offsetY: DEFAULT_WATERMARK.offsetY })} />
            <Slider label="宽度" value={wm.widthPct} min={2} max={60} step={0.5} suffix="%" onChange={(v) => setWatermark({ widthPct: v })} onReset={() => setWatermark({ widthPct: DEFAULT_WATERMARK.widthPct })} />
            <Slider label="不透明度" value={wm.opacity} min={0.05} max={1} step={0.01} onChange={(v) => setWatermark({ opacity: v })} onReset={() => setWatermark({ opacity: DEFAULT_WATERMARK.opacity })} />
            <Slider label="旋转" value={wm.rotation} min={-180} max={180} step={1} suffix="°" onChange={(v) => setWatermark({ rotation: v })} onReset={() => setWatermark({ rotation: DEFAULT_WATERMARK.rotation })} />
          </Section>

          <Section title="平铺">
            <Switch checked={wm.tile} onChange={(v) => setWatermark({ tile: v })} label="平铺整个区域" hint="适合满屏防盗图水印" />
            <Slider label="间距" value={wm.gapPct} min={0} max={30} step={0.5} suffix="%" onChange={(v) => setWatermark({ gapPct: v })} onReset={() => setWatermark({ gapPct: DEFAULT_WATERMARK.gapPct })} disabled={!wm.tile} />
          </Section>
        </>
      ) : (
        <p className="hint-note">
          {wm.asset ? '水印已关闭；打开「启用水印」后才能调整位置与透明度。' : '先选择一张水印图片；位置、透明度和平铺参数在有图之后才会出现。'}
        </p>
      )}
      <p className="hint-note">白色水印在白底上会不可见，黑色水印同理；按模板底色选择。</p>
    </div>
  )
}

const EDITOR_TAB_META: Record<EditorTab, { label: string; icon: React.ReactNode }> = {
  logo: { label: 'Logo', icon: <ImageIcon size={15} /> },
  text: { label: '文字', icon: <Type size={15} /> },
  layout: { label: '布局', icon: <LayoutGrid size={15} /> },
  theme: { label: '主题', icon: <Palette size={15} /> }
}

export function Inspector({ onOpenTemplates, onOpenReferences }: { onOpenTemplates: () => void; onOpenReferences: () => void }) {
  const design = useAppStore((s) => s.design)
  const tool = useAppStore((s) => s.tool)
  const setTool = useAppStore((s) => s.setTool)
  const editorTab = useAppStore((s) => s.editorTab)
  const setEditorTab = useAppStore((s) => s.setEditorTab)
  const watermarkEnabled = useAppStore((s) => s.design.watermark.enabled)
  const previewMeta = useAppStore((s) => s.previewMeta)
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [sheetCollapsed, setSheetCollapsed] = useState(false)

  const available = availableEditorTabs(design)
  // A tab that this design cannot fill must never stay selected (e.g. after a
  // style switch); render the first available tab and settle the store below.
  const activeTab: EditorTab = available.includes(editorTab) ? editorTab : available[0] ?? 'layout'

  useEffect(() => {
    if (tool === 'border' && editorTab !== activeTab) setEditorTab(activeTab)
  }, [tool, editorTab, activeTab, setEditorTab])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0, behavior: 'auto' })
  }, [tool, activeTab])

  return (
    <aside className={`sidebar inspector-panel ${sheetCollapsed ? 'collapsed' : ''}`}>
      <button
        type="button"
        className="sheet-grabber"
        onClick={() => setSheetCollapsed((v) => !v)}
        aria-label={sheetCollapsed ? '展开参数面板' : '收起参数面板'}
        aria-expanded={!sheetCollapsed}
      >
        <span />
      </button>

      {/* Desktop tool switch: 边框 / 水印. On mobile the bottom bar owns this. */}
      <div className="inspector-tool-switch" role="group" aria-label="编辑工具">
        <button
          type="button"
          className={tool === 'border' ? 'active' : ''}
          aria-pressed={tool === 'border'}
          onClick={() => setTool('border')}
        >
          <Frame size={15} />
          <span>边框</span>
        </button>
        <button
          type="button"
          className={tool === 'watermark' ? 'active' : ''}
          aria-pressed={tool === 'watermark'}
          onClick={() => setTool('watermark')}
        >
          <Droplets size={15} />
          <span>水印</span>
          {watermarkEnabled ? <i className="tab-dot" /> : null}
        </button>
      </div>

      {tool === 'border' ? (
        <div className="editor-tabs" role="group" aria-label="边框编辑">
          {available.map((tab) => (
            <button
              key={tab}
              type="button"
              data-editor-tab={tab}
              className={activeTab === tab ? 'active' : ''}
              aria-pressed={activeTab === tab}
              onClick={() => setEditorTab(tab)}
            >
              {EDITOR_TAB_META[tab].icon}
              <span>{EDITOR_TAB_META[tab].label}</span>
            </button>
          ))}
        </div>
      ) : null}

      <div className="inspector-scroll" ref={scrollRef}>
        {tool === 'border' ? (
          <div className="inspector-pane">
            <CurrentTemplateHeader onOpenReferences={onOpenReferences} />
            {/* key re-mounts the pane on tab change so the CSS enter motion actually runs */}
            <div className="editor-pane" data-editor-pane={activeTab} key={activeTab}>
              {activeTab === 'logo' ? <LogoEditor /> : null}
              {activeTab === 'text' ? <TextEditor /> : null}
              {activeTab === 'layout' ? <LayoutEditor /> : null}
              {activeTab === 'theme' ? <ThemeEditor /> : null}
            </div>
          </div>
        ) : (
          <WatermarkPanel />
        )}
      </div>

      <div className="inspector-foot">
        <span>输出</span>
        <strong>{previewMeta ? `${previewMeta.outW} × ${previewMeta.outH}` : '—'}</strong>
        <em>
          {(photos.find((p) => p.id === selectedId) ?? photos[0])?.name ?? ''}
          {design.ratio !== 'original' ? ` · ${design.ratio}` : ''}
        </em>
      </div>

      {/* Mobile bottom bar: exactly 边框 / 水印. Desktop hides it. */}
      <div className="mobile-editor-bar" role="group" aria-label="编辑工具">
        <button
          type="button"
          className={tool === 'border' ? 'active' : ''}
          aria-pressed={tool === 'border'}
          onClick={() => {
            if (tool === 'border') onOpenTemplates()
            else setTool('border')
          }}
        >
          <Frame size={20} />
          <span>边框</span>
        </button>
        <button
          type="button"
          className={tool === 'watermark' ? 'active' : ''}
          aria-pressed={tool === 'watermark'}
          onClick={() => setTool('watermark')}
        >
          <Droplets size={20} />
          <span>水印</span>
          {watermarkEnabled ? <i className="tab-dot" /> : null}
        </button>
      </div>
    </aside>
  )
}
