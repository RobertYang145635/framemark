import React, { useEffect, useMemo, useState } from 'react'
import { Lock, Search, Sparkles } from 'lucide-react'
import { useAppStore } from '../store'
import { logoUrlFor } from '../core/brands'
import {
  LOGO_BRANDS,
  LOGO_VERSIONS,
  LOGO_VERSION_BY_ID,
  type LogoCut,
  type LogoVersion
} from '../core/logo-catalog'
import {
  DISPLAY_LOGO_BRAND_COUNT,
  LOGO_DISPLAY_BRAND_MAP,
  LOGO_HIDDEN_DISPLAY_BRANDS,
  SELECTED_LOGO_VERSION_COUNT,
  SELECTED_LOGO_VERSION_ID_SET
} from '../core/logo-selection.generated'
import { cutForVersionSurface, surfaceForDesign, type Surface } from '../core/logo-compat'
import { Modal, Segmented } from './ui'

const ERA_ORDER: { key: LogoVersion['era']; label: string }[] = [
  { key: 'current', label: '现行' },
  { key: 'classic', label: '经典' },
  { key: 'historical', label: '历史' },
  { key: 'legacy', label: '既有' }
]

const surfaceLabel = (surface: Surface): string => (surface === 'dark' ? '深色' : '浅色')

/** The surface a version *requires* when it cannot live on the current one. */
const ownSurface = (version: LogoVersion): Surface => (version.surface === 'dark' ? 'dark' : 'light')

/**
 * Preview cut for a version: the current surface's cut when one fits, otherwise
 * the cut of the surface the version requires. Falls back to the first cut so a
 * locked card still shows what it is (it is never selectable).
 */
function previewCut(version: LogoVersion, fit: Surface | null): LogoCut | null {
  if (fit) {
    const cut = cutForVersionSurface(version, fit)
    if (cut) return cut
  }
  return cutForVersionSurface(version, ownSurface(version)) ?? version.cuts[0] ?? null
}

function surfaceBadge(version: LogoVersion): string {
  if (version.surface === 'both') return '黑白双版'
  if (version.surface === 'dark') return '仅深底'
  return '仅浅底'
}

interface DisplayBrand {
  key: string
  label: string
  cn: string
  group: string
  canonicalVersion: string
  versions: LogoVersion[]
  merged: string[]
}

export function BrandPicker() {
  const open = useAppStore((s) => s.brandPickerOpen)
  const setOpen = useAppStore((s) => s.setBrandPickerOpen)
  const setLogoVersion = useAppStore((s) => s.setLogoVersion)
  const autoMatchLogo = useAppStore((s) => s.autoMatchLogo)
  const showToast = useAppStore((s) => s.showToast)
  const design = useAppStore((s) => s.design)
  const [brandKey, setBrandKey] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [group, setGroup] = useState<'all' | 'camera' | 'phone'>('all')

  const surface = surfaceForDesign(design)
  // User-selected versions only, with brand ownership folded in:
  // LUMIX belongs to Panasonic, LUMO to OPPO. Their version ids keep the source
  // brand key, but the picker presents one group per real brand.
  const displayBrands = useMemo(() => {
    const metaByKey = new Map(LOGO_BRANDS.map((b) => [b.key, b]))
    const hidden = new Set(LOGO_HIDDEN_DISPLAY_BRANDS)
    const groups = new Map<string, DisplayBrand>()
    for (const version of LOGO_VERSIONS) {
      if (!SELECTED_LOGO_VERSION_ID_SET.has(version.id)) continue
      const displayKey = LOGO_DISPLAY_BRAND_MAP[version.brandKey] ?? version.brandKey
      if (hidden.has(version.brandKey) && !LOGO_DISPLAY_BRAND_MAP[version.brandKey]) continue
      const meta = metaByKey.get(displayKey) ?? metaByKey.get(version.brandKey)
      if (!meta) continue
      let entry = groups.get(displayKey)
      if (!entry) {
        entry = {
          key: displayKey,
          label: meta.label,
          cn: meta.cn,
          group: meta.group,
          canonicalVersion: meta.canonicalVersion,
          versions: [],
          merged: []
        }
        groups.set(displayKey, entry)
      }
      entry.versions.push(version)
      if (version.brandKey !== displayKey) {
        const sourceLabel = metaByKey.get(version.brandKey)?.label ?? version.brandKey
        if (!entry.merged.includes(sourceLabel)) entry.merged.push(sourceLabel)
      }
    }
    const order = new Map(LOGO_BRANDS.map((b, i) => [b.key, i]))
    return [...groups.values()].sort(
      (a, b) => (order.get(a.key) ?? 999) - (order.get(b.key) ?? 999)
    )
  }, [])

  useEffect(() => {
    if (!open) {
      setBrandKey(null)
      setQuery('')
    }
  }, [open])

  const close = () => {
    setOpen(false)
    setBrandKey(null)
    setQuery('')
  }

  const brands = useMemo(() => {
    const q = query.trim().toLowerCase()
    return displayBrands.filter((brand) => {
      if (group !== 'all' && brand.group !== group) return false
      if (!q) return true
      const haystack = `${brand.label} ${brand.cn} ${brand.key} ${brand.merged.join(' ')} ${brand.versions.map((v) => v.name).join(' ')}`
      return haystack.toLowerCase().includes(q)
    })
  }, [displayBrands, group, query])

  const brand: DisplayBrand | null = brandKey
    ? displayBrands.find((b) => b.key === brandKey) ?? null
    : null
  const versions = brand ? brand.versions : []
  const compatibleCount = versions.filter((v) => Boolean(cutForVersionSurface(v, surface))).length

  const grouped = useMemo(() => {
    const buckets = new Map<LogoVersion['era'], LogoVersion[]>()
    for (const version of versions) {
      const list = buckets.get(version.era)
      if (list) list.push(version)
      else buckets.set(version.era, [version])
    }
    return ERA_ORDER.map((era) => ({ ...era, versions: buckets.get(era.key) ?? [] })).filter(
      (era) => era.versions.length > 0
    )
  }, [versions])

  /** One-version brands apply directly; everything else opens the version page. */
  const chooseBrand = async (target: DisplayBrand) => {
    const list = target.versions
    if (list.length === 1 && cutForVersionSurface(list[0], surface)) {
      await setLogoVersion(list[0].id)
      if (useAppStore.getState().design.brandVersion?.versionId === list[0].id) close()
      return
    }
    setBrandKey(target.key)
  }

  const chooseVersion = async (version: LogoVersion) => {
    await setLogoVersion(version.id)
    if (useAppStore.getState().design.brandVersion?.versionId === version.id) close()
  }

  const canonicalOf = (target: DisplayBrand): LogoVersion | null => {
    const declared = LOGO_VERSION_BY_ID[target.canonicalVersion]
    if (declared && target.versions.some((v) => v.id === declared.id)) return declared
    return target.versions[0] ?? null
  }

  const brandPreview = (target: DisplayBrand) => {
    const canonical = canonicalOf(target)
    const cut = canonical ? previewCut(canonical, null) : null
    const url = cut ? logoUrlFor(cut.assetKey) : null
    return (
      <span className={`logo-brand-preview ${cut?.previewSurface ?? 'light'}`}>
        {url ? <img src={url} alt={target.label} loading="lazy" decoding="async" /> : null}
      </span>
    )
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title={brand ? '选择 Logo 版本' : '品牌 Logo 库'}
      subtitle={
        brand
          ? `${brand.label} · ${brand.cn} · ${versions.length} 个版本${brand.merged.length ? ' · 含 ' + brand.merged.join(' / ') : ''} · 当前底色：${surfaceLabel(surface)}`
          : `${DISPLAY_LOGO_BRAND_COUNT} 个品牌 · 仅显示已勾选 ${SELECTED_LOGO_VERSION_COUNT} 个版本 · 当前底色：${surfaceLabel(surface)}`
      }
      wide
      className="logo-library-modal"
      footer={
        brand ? null : (
          <span className="modal-note">
            品牌归属：LUMIX → Panasonic，LUMO → OPPO。黑标只用于浅色底，白标只用于深色底；不兼容的版本会锁定。
          </span>
        )
      }
    >
      {brand ? (
        <div className="logo-library">
          <button className="logo-library-back" onClick={() => setBrandKey(null)} type="button">
            ← 返回品牌
          </button>
          <div className="logo-library-head">
            {brandPreview(brand)}
            <div className="logo-library-head-meta">
              <strong>
                {brand.label} · {brand.cn}
              </strong>
              <em>
                {versions.length} 个版本 · 当前底色：{surfaceLabel(surface)}
              </em>
            </div>
          </div>

          <div className="logo-version-count">
            {versions.length} 个版本 · 当前底色可用 {compatibleCount} 个
          </div>

          {grouped.map((era) => (
            <section className="logo-era" key={era.key}>
              <h3 className="logo-era-head">
                {era.label}
                <em>{era.versions.length}</em>
              </h3>
              <div className="logo-version-grid">
                {era.versions.map((version) => {
                  const fitCut = cutForVersionSurface(version, surface)
                  const locked = !fitCut
                  const selected = design.brandVersion?.versionId === version.id
                  const cut = fitCut ?? previewCut(version, null)
                  const url = cut ? logoUrlFor(cut.assetKey) : null
                  return (
                    <button
                      key={version.id}
                      type="button"
                      className={`logo-version-card ${selected ? 'selected' : ''} ${locked ? 'locked' : ''}`}
                      aria-pressed={selected}
                      aria-disabled={locked ? 'true' : undefined}
                      title={version.name}
                      onClick={() => {
                        if (locked) {
                          const need = version.surface === 'dark' ? '深色底' : '浅色底'
                          showToast(`这个版本只适用于${need}`, 'error')
                          return
                        }
                        void chooseVersion(version)
                      }}
                    >
                      <span className={`logo-version-preview ${cut?.previewSurface ?? 'light'}`}>
                        {url ? (
                          <img src={url} alt={version.name} loading="lazy" decoding="async" />
                        ) : null}
                      </span>
                      <span className="logo-version-body">
                        <span className="logo-version-tags">
                          <span className={`logo-version-badge ${selected ? 'selected' : ''} ${selected && locked ? 'unavailable' : ''}`}>
                            {selected ? (locked ? '已选 · 当前不显示' : '已选') : surfaceBadge(version)}
                          </span>
                          {locked ? (
                            <span className="logo-version-lock">
                              <Lock size={11} />
                              当前底色不可用
                            </span>
                          ) : null}
                        </span>
                        <span className="logo-version-title">{version.name}</span>
                        <span className="logo-version-meta">
                          {era.label} · {version.years}
                        </span>
                        {version.aliases.length ? (
                          <span className="logo-version-alias">{version.aliases.length} 个同源文件</span>
                        ) : null}
                      </span>
                    </button>
                  )
                })}
              </div>
            </section>
          ))}
        </div>
      ) : (
        <>
          <div className="logo-library-toolbar">
            <div className="gallery-search">
              <Search size={15} />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="搜索品牌，如 FUJIFILM / Hasselblad / 柯达…"
              />
            </div>
            <Segmented
              value={group}
              options={[
                { value: 'all', label: '全部' },
                { value: 'camera', label: '相机' },
                { value: 'phone', label: '手机' }
              ]}
              onChange={setGroup}
              compact
            />
            <button className="btn btn-ghost btn-sm" onClick={() => void autoMatchLogo()} type="button">
              <Sparkles size={14} />
              自动匹配
            </button>
          </div>
          <div className="logo-brand-grid">
            {brands.map((item) => {
              const count = item.versions.length
              const active = Boolean(
                design.brandVersion && item.versions.some((v) => v.id === design.brandVersion?.versionId)
              )
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`logo-brand-card ${active ? 'active' : ''}`}
                  onClick={() => void chooseBrand(item)}
                >
                  {brandPreview(item)}
                  <span className="logo-brand-meta">
                    <strong className="logo-brand-name">{item.label}</strong>
                    <em className="logo-brand-sub">
                      {item.cn} · {count} 个版本{item.merged.length ? ' · 含 ' + item.merged.join(' / ') : ''}
                    </em>
                  </span>
                </button>
              )
            })}
          </div>
          {!brands.length ? <div className="empty-note">没有匹配的品牌。</div> : null}
        </>
      )}
    </Modal>
  )
}
