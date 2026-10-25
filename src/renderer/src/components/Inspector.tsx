import React, { useEffect, useRef, useState } from 'react'
import {
  Camera,
  Download,
  Droplets,
  FolderOpen,
  ImagePlus,
  Images,
  RotateCcw,
  Save,
  SlidersHorizontal,
  Sparkles,
  Trash2,
  Upload
} from 'lucide-react'
import type { Anchor, InspectorTab, Ratio, TextSettings } from '../types'
import { useAppStore } from '../store'
import { getTemplate } from '../core/templates'
import { getVariant } from '../core/variants'
import { BRANDS } from '../core/brands'
import { getStockStyle } from '../core/system/styles'
import type { StockStyle } from '../core/system/ir'
import { IconButton, Section, Segmented, Slider, Switch } from './ui'

const RATIO_OPTIONS: { value: Ratio; label: string }[] = [
  { value: 'original', label: '原始' },
  { value: '1:1', label: '1:1' },
  { value: '4:5', label: '4:5' },
  { value: '16:9', label: '16:9' },
  { value: '3:2', label: '3:2' }
]

const FORMATS = [
  { value: 'jpeg' as const, label: 'JPG' },
  { value: 'png' as const, label: 'PNG' },
  { value: 'webp' as const, label: 'WebP' },
  { value: 'tiff' as const, label: 'TIFF' }
]

const LONG_EDGE = [
  { value: 0, label: '原图' },
  { value: 4096, label: '4096' },
  { value: 3000, label: '3000' },
  { value: 2048, label: '2048' }
]

const TEXT_FLAGS: { key: keyof TextSettings; label: string }[] = [
  { key: 'showMake', label: '品牌' },
  { key: 'showModel', label: '机型' },
  { key: 'showLens', label: '镜头' },
  { key: 'showExif', label: '参数' },
  { key: 'showDate', label: '日期' },
  { key: 'showLogo', label: 'Logo' }
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

/**
 * Does this stock style declare a `isBrandLogo` slot node? The stock engine's
 * only logo consumer is `params.brandLogoFile`, which `assets.ts` applies solely
 * to `isBrandLogo` slots — fixed co-brand marks (Leica roundel, Hasselblad, the
 * 哈苏文字 marks) have none, so the brand picker would be inert for those cards.
 */
function stockHasBrandSlot(style: StockStyle | null): boolean {
  if (!style) return false
  const walk = (nodes: StockStyle['bands'][number]['children']): boolean =>
    nodes.some((n) => (n.kind === 'image' ? !!n.isBrandLogo : n.kind === 'group' ? walk(n.children) : false))
  return style.bands.some((b) => walk(b.children))
}

function LogoSection() {
  const design = useAppStore((s) => s.design)
  const setBrandPickerOpen = useAppStore((s) => s.setBrandPickerOpen)
  const autoMatchLogo = useAppStore((s) => s.autoMatchLogo)
  const clearLogo = useAppStore((s) => s.clearLogo)
  const setText = useAppStore((s) => s.setText)
  const setParam = useAppStore((s) => s.setParam)
  const def = getTemplate(design.templateId)
  // Stock cards (Task-2, option (b) — do not leave a control that does nothing):
  // `显示 Logo` (text.showLogo) and `Logo 大小` (params.logoScale) have no consumer
  // anywhere in core/system — the stock engine reads neither — so they are hidden
  // on stock cards rather than wired onto a path that cannot honour them without
  // breaking the vendor-faithful (0,0) render. `brandLogoFile` DOES drive
  // `isBrandLogo` slots, so the brand-picker row is kept for slot styles and the
  // whole section is dropped for stock styles that have no such node (e.g. the
  // two 哈苏文字 cards, whose logo is a fixed co-brand mark).
  const isStock = def.id === 'systemCamera'
  const stockStyle = isStock ? getStockStyle(String(design.params.systemStyle ?? '')) : null
  if (isStock && !stockHasBrandSlot(stockStyle)) return null

  return (
    <Section
      title="品牌 Logo"
      hint={`自动匹配 EXIF 品牌，或从 ${BRANDS.length} 个品牌中选择`}
      action={
        <button className="btn btn-ghost btn-sm" onClick={() => void autoMatchLogo()} type="button">
          <Sparkles size={14} />
          自动匹配
        </button>
      }
    >
      <div className="logo-row">
        <div className={`logo-current ${design.logo ? 'has' : ''}`}>
          {design.logo ? (
            <img src={design.logo.dataUrl} alt={design.logo.name} />
          ) : (
            <span className="logo-empty">
              <Camera size={20} />
            </span>
          )}
        </div>
        <div className="logo-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => setBrandPickerOpen(true)} type="button">
            <Images size={14} />
            品牌库
          </button>
          {design.logo ? (
            <button className="btn btn-ghost btn-sm" onClick={clearLogo} type="button">
              <Trash2 size={14} />
              清除
            </button>
          ) : null}
        </div>
      </div>
      {!isStock ? (
        <>
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
            disabled={!design.text.showLogo}
          />
        </>
      ) : null}
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

const PARAM_LABELS: Record<string, string> = {
  marginTopPct: '上边距',
  marginBottomPct: '下边距',
  insetPct: '卡片内缩',
  footerOffsetPct: '页脚偏移',
  overlayMode: '品牌行模式',
  overlayBrand: '品牌字',
  brandWidthPct: '品牌宽度',
  brandOffsetPct: '品牌偏移',
  brandCenterPct: '品牌居中偏移',
  brandTextSizePct: '品牌字号',
  brandPlacement: '品牌位置',
  exifWidthPct: 'EXIF 宽度',
  exifSizePct: 'EXIF 字号',
  exifOffsetPct: 'EXIF 偏移',
  exifAlign: 'EXIF 对齐',
  profileMode: '人物布局',
  profileName: '昵称',
  headerPct: '页眉高度',
  chipTopPct: '浮层顶距',
  avatarPct: '头像大小',
  nameSizePct: '昵称字号',
  modelSizePct: '机型字号',
  footerLogoPct: '页脚 Logo',
  logoPct: 'Logo 大小',
  logoPos: 'Logo 位置',
  footerAlign: '页脚对齐',
  footerTextScale: '页脚字号',
  minimalFooter: '简约日期',
  borderStyle: '照片描边',
  glassLayout: '玻璃布局',
  glassAlign: '玻璃位置',
  sideLayout: '侧栏布局',
  corner: '角标位置',
  stockAnchor: '水印锚点',
  stockOffsetXPct: '水印水平微调',
  stockOffsetYPct: '水印垂直微调'
}

const EXPOSED_PARAMS = new Set([
  'theme', 'systemStyle', 'marginPct', 'radiusPct', 'shadow', 'footerPct',
  'blur', 'grain', 'barPct', 'logoScale'
])

function AdvancedParams() {
  const design = useAppStore((s) => s.design)
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
  const setParam = useAppStore((s) => s.setParam)
  const [open, setOpen] = useState(false)
  const entries = Object.entries({ ...def.defaults, ...(variant.params ?? {}), ...design.params })
    .filter(([key, value]) => !EXPOSED_PARAMS.has(key) && value !== undefined && value !== null && typeof value !== 'object')
    .sort(([a], [b]) => (PARAM_LABELS[a] ?? a).localeCompare(PARAM_LABELS[b] ?? b, 'zh-CN'))
  if (!entries.length) return null
  return (
    <Section
      title="全部参数"
      hint={`${entries.length} 个当前模板参数，含未在常用面板暴露的细项`}
      action={
        <button className="btn btn-ghost btn-sm" onClick={() => setOpen((v) => !v)} type="button">
          <SlidersHorizontal size={14} />
          {open ? '收起' : '展开'}
        </button>
      }
    >
      {open ? (
        <div className="field-stack">
          {entries.map(([key, value]) => {
            const label = PARAM_LABELS[key] ?? key
            if (typeof value === 'boolean') {
              return (
                <label className="field compact" key={key} title={key}>
                  <span>{label}</span>
                  <input type="checkbox" checked={value} onChange={(e) => setParam(key, e.target.checked)} />
                </label>
              )
            }
            if (typeof value === 'number') {
              return (
                <label className="field compact" key={key} title={key}>
                  <span>{label}</span>
                  <input
                    type="number"
                    step={Number.isInteger(value) ? 1 : 0.1}
                    value={value}
                    onChange={(e) => {
                      const n = Number(e.target.value)
                      if (Number.isFinite(n)) setParam(key, n)
                    }}
                  />
                </label>
              )
            }
            return (
              <label className="field compact" key={key} title={key}>
                <span>{label}</span>
                <input value={String(value)} onChange={(e) => setParam(key, e.target.value)} />
              </label>
            )
          })}
        </div>
      ) : null}
    </Section>
  )
}

function TextSection() {
  const design = useAppStore((s) => s.design)
  const setText = useAppStore((s) => s.setText)
  const t = design.text
  return (
    <Section
      title="文字与参数"
      hint="留空标题时自动使用相机品牌/机型；支持 {make} {model} {lens} {date} {exif} 等变量"
    >
      <div className="field-stack">
        <label className="field">
          <span>标题</span>
          <input
            value={t.title}
            onChange={(e) => setText({ title: e.target.value })}
            placeholder="例如：海边的下午 / {make} {model}"
          />
        </label>
        <label className="field">
          <span>副标题</span>
          <input
            value={t.subtitle}
            onChange={(e) => setText({ subtitle: e.target.value })}
            placeholder="例如：2024 秋季 / {lens}"
          />
        </label>
        <label className="field">
          <span>签名</span>
          <input
            value={t.signature}
            onChange={(e) => setText({ signature: e.target.value })}
            placeholder="例如：YHY / 手写体"
          />
        </label>
        <label className="field">
          <span>自定义行</span>
          <input
            value={t.customLine}
            onChange={(e) => setText({ customLine: e.target.value })}
            placeholder="{exif} · {date}"
          />
        </label>
        <label className="field">
          <span>地点</span>
          <input
            value={t.location ?? ''}
            onChange={(e) => setText({ location: e.target.value })}
            placeholder="用于系统相机的地点 token，例如 成都 / 青岛"
          />
        </label>
      </div>
      <div className="toggle-grid">
        {TEXT_FLAGS.map((f) => (
          <button
            key={f.key}
            className={`toggle-chip ${(t as unknown as Record<string, boolean>)[f.key] ? 'on' : ''}`}
            onClick={() => setText({ [f.key]: !(t as unknown as Record<string, boolean>)[f.key] } as Partial<TextSettings>)}
            type="button"
          >
            {f.label}
          </button>
        ))}
      </div>
      <Slider
        label="字号缩放"
        value={t.fontScale}
        min={0.8}
        max={1.4}
        step={0.02}
        suffix="×"
        onChange={(v) => setText({ fontScale: v })}
      />
    </Section>
  )
}

function TemplatePanel({ onOpenTemplates, onOpenReferences }: { onOpenTemplates: () => void; onOpenReferences: () => void }) {
  const design = useAppStore((s) => s.design)
  const setParam = useAppStore((s) => s.setParam)
  const setRatio = useAppStore((s) => s.setRatio)
  const setPresetDialogOpen = useAppStore((s) => s.setPresetDialogOpen)
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
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
  const themeOptions = variant.themes?.length ? variant.themes : def.themes

  const resetVariantParams = useAppStore((s) => s.resetVariantParams)
  return (
    <div className="inspector-pane">
      <div className="current-template">
        <div>
          <span className="eyebrow">{variant.category}</span>
          <h3>{variant.name}</h3>
          <p>{variant.description ?? def.description}</p>
        </div>
        <div className="current-template-actions">
          <button className="btn btn-ghost btn-sm" onClick={onOpenReferences} type="button">
            <Images size={14} />
            原版
          </button>
          <button className="btn btn-ghost btn-sm" onClick={resetVariantParams} title="恢复此模板的默认参数" type="button">
            <RotateCcw size={14} />
            恢复
          </button>
          <button className="btn btn-ghost btn-sm" onClick={onOpenTemplates} type="button">
            更换
          </button>
        </div>
      </div>

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
          <Slider label="边距" value={Number(design.params.marginPct ?? 6)} min={0} max={15} step={0.5} suffix="%" onChange={(v) => setParam('marginPct', v)} />
        ) : null}
        {controls.includes('radius') ? (
          <Slider label="圆角" value={Number(design.params.radiusPct ?? 0)} min={0} max={10} step={0.5} suffix="%" onChange={(v) => setParam('radiusPct', v)} />
        ) : null}
        {controls.includes('shadow') ? (
          <Slider label="阴影" value={Number(design.params.shadow ?? 0)} min={0} max={10} step={0.5} onChange={(v) => setParam('shadow', v)} />
        ) : null}
        {controls.includes('footer') ? (
          <Slider label="文字区高度" value={Number(design.params.footerPct ?? 12)} min={5} max={25} step={0.5} suffix="%" onChange={(v) => setParam('footerPct', v)} />
        ) : null}
        {controls.includes('blur') ? (
          <Slider label="背景模糊" value={Number(design.params.blur ?? 26)} min={0} max={40} step={1} onChange={(v) => setParam('blur', v)} />
        ) : null}
        {controls.includes('grain') ? (
          <Slider label="颗粒" value={Number(design.params.grain ?? 0.12)} min={0} max={0.5} step={0.01} onChange={(v) => setParam('grain', v)} />
        ) : null}
        {isMovie ? (
          <Slider label="上下黑边" value={Number(design.params.barPct ?? 8)} min={3} max={16} step={0.5} suffix="%" onChange={(v) => setParam('barPct', v)} />
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
            />
            <Slider
              label="垂直微调"
              value={Number(design.params.stockOffsetYPct ?? 0)}
              min={-40}
              max={40}
              step={0.5}
              suffix="%"
              onChange={(v) => setParam('stockOffsetYPct', v)}
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
        {!controls.length && !isMovie && !stockMovable ? <p className="hint-note">这个模板以满幅/字幕为主，可用主题、Logo 和文字控制。</p> : null}
      </Section>

      {themeOptions?.length ? (
        <Section title="主题">
          <ThemeSwatches />
        </Section>
      ) : null}

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

      <LogoSection />
      <AvatarSection />
      <TextSection />
      <AdvancedParams />

      <Section title="预设">
        <div className="preset-save-row">
          <button className="btn btn-secondary block" onClick={() => setPresetDialogOpen(true)} type="button">
            <Save size={15} />
            保存为预设
          </button>
        </div>
      </Section>
    </div>
  )
}

function WatermarkPanel() {
  const design = useAppStore((s) => s.design)
  const setWatermark = useAppStore((s) => s.setWatermark)
  const setWatermarkFile = useAppStore((s) => s.setWatermarkFile)
  const fileRef = useRef<HTMLInputElement>(null)
  const wm = design.watermark
  return (
    <div className="inspector-pane">
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
        <Switch checked={wm.enabled} onChange={(v) => setWatermark({ enabled: v })} label="启用水印" />
      </Section>

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
        <Slider label="水平偏移" value={wm.offsetX} min={-40} max={40} step={0.5} suffix="%" onChange={(v) => setWatermark({ offsetX: v })} />
        <Slider label="垂直偏移" value={wm.offsetY} min={-40} max={40} step={0.5} suffix="%" onChange={(v) => setWatermark({ offsetY: v })} />
        <Slider label="宽度" value={wm.widthPct} min={2} max={60} step={0.5} suffix="%" onChange={(v) => setWatermark({ widthPct: v })} />
        <Slider label="不透明度" value={wm.opacity} min={0.05} max={1} step={0.01} onChange={(v) => setWatermark({ opacity: v })} />
        <Slider label="旋转" value={wm.rotation} min={-180} max={180} step={1} suffix="°" onChange={(v) => setWatermark({ rotation: v })} />
      </Section>

      <Section title="平铺">
        <Switch checked={wm.tile} onChange={(v) => setWatermark({ tile: v })} label="平铺整个区域" hint="适合满屏防盗图水印" />
        <Slider label="间距" value={wm.gapPct} min={0} max={30} step={0.5} suffix="%" onChange={(v) => setWatermark({ gapPct: v })} disabled={!wm.tile} />
      </Section>
      <p className="hint-note">白色水印在白底上会不可见，黑色水印同理；按模板底色选择。</p>
    </div>
  )
}

function ExportPanel() {
  const design = useAppStore((s) => s.design)
  const photos = useAppStore((s) => s.photos)
  const setExport = useAppStore((s) => s.setExport)
  const exportCurrent = useAppStore((s) => s.exportCurrent)
  const exportAll = useAppStore((s) => s.exportAll)
  const job = useAppStore((s) => s.job)
  const desktop = Boolean(window.framemark)
  const formats = desktop ? FORMATS : FORMATS.filter((f) => f.value !== 'tiff')
  const qualityVisible = design.export.format === 'jpeg'
  const changeDir = async () => {
    if (!window.framemark) return
    const dir = await window.framemark.pickExportDir()
    if (dir) setExport({ outputDir: dir })
  }
  return (
    <div className="inspector-pane">
      <Section title="导出格式">
        <Segmented
          value={design.export.format}
          options={formats}
          onChange={(v) => setExport({ format: v })}
          compact
        />
        {qualityVisible ? (
          <Slider
            label="JPEG 质量"
            value={design.export.quality}
            min={90}
            max={100}
            step={1}
            onChange={(v) => setExport({ quality: v })}
          />
        ) : null}
        <p className="hint-note">
          {desktop
            ? '桌面 JPG 使用 100 质量 + 4:4:4；PNG 与 TIFF 为真无损；WebP 使用无损编码（较慢）。'
            : 'JPG 与 WebP 使用最高质量；PNG 为真无损。Android 的 PNG/WebP 不写入 EXIF。'}
        </p>
      </Section>

      <Section title="尺寸">
        <label className="field compact">
          <span>长边限制</span>
          <Segmented
            value={String(design.export.longEdge)}
            options={LONG_EDGE.map((o) => ({ value: String(o.value), label: o.label }))}
            onChange={(v) => setExport({ longEdge: Number(v) })}
            compact
          />
        </label>
        <p className="hint-note">
          {design.export.longEdge
            ? `会重采样到长边 ${design.export.longEdge}px，属于有损缩放。`
            : '默认按原图分辨率输出，不缩放。24MP 以上请预留内存。'}
        </p>
      </Section>

      <Section title="元数据">
        <Switch checked={design.export.removeGps} onChange={(v) => setExport({ removeGps: v })} label="移除 GPS 位置" hint="默认开启，保护隐私" />
        <label className="field">
          <span>文件名模板</span>
          <input
            value={design.export.filenamePattern}
            onChange={(e) => setExport({ filenamePattern: e.target.value })}
            placeholder="{name}-{template}"
          />
        </label>
        <p className="token-hint">{'{name} {template} {preset} {date} {time} {index} {make} {model}'}</p>
      </Section>

      <Section title="输出位置">
        {desktop ? (
          <div className="output-dir">
            <code title={design.export.outputDir}>{design.export.outputDir || '尚未选择'}</code>
            <button className="btn btn-ghost btn-sm" onClick={() => void changeDir()} type="button">
              <FolderOpen size={14} />
              更改
            </button>
          </div>
        ) : (
          <div className="output-dir">
            <code>系统相册 / Pictures/FrameMark</code>
          </div>
        )}
      </Section>

      <div className="export-actions">
        <button className="btn btn-primary block" onClick={() => void exportCurrent()} disabled={!photos.length || job?.running} type="button">
          <Download size={16} />
          导出当前
        </button>
        <button className="btn btn-secondary block" onClick={() => void exportAll()} disabled={!photos.length || job?.running} type="button">
          <Download size={16} />
          导出全部（{photos.length}）
        </button>
        <p className="hint-note center">JPG 会回写相机 EXIF，方向自动校正；PNG/WebP/TIFF 不写入 EXIF。</p>
      </div>
    </div>
  )
}

export function Inspector({ onOpenTemplates, onOpenReferences }: { onOpenTemplates: () => void; onOpenReferences: () => void }) {
  const tab = useAppStore((s) => s.tab)
  const setTab = useAppStore((s) => s.setTab)
  const watermarkEnabled = useAppStore((s) => s.design.watermark.enabled)
  const scrollRef = useRef<HTMLDivElement>(null)
  const [sheetCollapsed, setSheetCollapsed] = useState(false)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0, behavior: 'auto' })
  }, [tab])

  const tabs: { id: InspectorTab; label: string; icon: React.ReactNode }[] = [
    { id: 'template', label: '模板', icon: <SlidersHorizontal size={16} /> },
    { id: 'watermark', label: '水印', icon: <Droplets size={16} /> },
    { id: 'export', label: '导出', icon: <Download size={16} /> }
  ]
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
      <div className="inspector-tabs">
        {tabs.map((t) => (
          <button key={t.id} className={tab === t.id ? 'active' : ''} onClick={() => setTab(t.id)} type="button">
            {t.icon}
            <span>{t.label}</span>
            {t.id === 'watermark' && watermarkEnabled ? <i className="tab-dot" /> : null}
          </button>
        ))}
      </div>
      <div className="inspector-scroll" ref={scrollRef}>
        {tab === 'template' ? <TemplatePanel onOpenTemplates={onOpenTemplates} onOpenReferences={onOpenReferences} /> : null}
        {tab === 'watermark' ? <WatermarkPanel /> : null}
        {tab === 'export' ? <ExportPanel /> : null}
      </div>
    </aside>
  )
}
