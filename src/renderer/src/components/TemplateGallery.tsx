import React, { useMemo, useState } from 'react'
import { Check, Images, Search } from 'lucide-react'
import { useAppStore } from '../store'
import { VARIANT_CATEGORIES, variantsByCategory } from '../core/variants'
import { BLOCKED_STYLES } from '../core/system/blocked'
import { VariantThumb } from './VariantThumb'

export function TemplateGallery({
  modal,
  onPick,
  onOpenReferences
}: {
  modal?: boolean
  onPick?: () => void
  onOpenReferences?: () => void
}) {
  const activeId = useAppStore((s) => s.design.variantId)
  const setVariant = useAppStore((s) => s.setVariant)
  const query = useAppStore((s) => s.galleryQuery)
  const setQuery = useAppStore((s) => s.setGalleryQuery)
  const [category, setCategory] = useState('全部')
  const [vendor, setVendor] = useState('全部')
  const groups = useMemo(() => variantsByCategory(), [])
  const total = useMemo(() => groups.reduce((n, g) => n + g.items.length, 0), [groups])
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return groups
      .map((g) => ({
        ...g,
        items: g.items.filter(
          (v) =>
            (category === '全部' || g.category === category) &&
            (category !== '系统相机' || vendor === '全部' || v.vendor === vendor) &&
            (!q ||
              `${v.name} ${v.category} ${v.en} ${v.badge ?? ''} ${v.vendor ?? ''} ${v.family ?? ''}`
                .toLowerCase()
                .includes(q))
        )
      }))
      .filter((g) => g.items.length > 0)
  }, [groups, query, category, vendor])
  const blocked = useMemo(() => {
    if (category !== '系统相机') return []
    const q = query.trim().toLowerCase()
    return BLOCKED_STYLES.filter(
      (b) =>
        (vendor === '全部' || b.vendor === vendor) &&
        (!q || `${b.name} ${b.vendor} ${b.reason}`.toLowerCase().includes(q))
    )
  }, [category, vendor, query])
  const blockedVivoCount = useMemo(
    () => blocked.reduce((n, b) => (b.vendor === 'vivo' ? n + (b.count ?? 1) : n), 0),
    [blocked]
  )

  return (
    <div className={`template-gallery ${modal ? 'modal-mode' : ''}`}>
      {!modal ? (
        <div className="gallery-head">
          <div>
            <h2>模板库</h2>
            <p>{total} 个版式 · 原版风格全覆盖</p>
          </div>
          {onOpenReferences ? (
            <button className="btn btn-ghost btn-sm" onClick={onOpenReferences} type="button">
              <Images size={15} />
              <span>原版</span>
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="gallery-search">
        <Search size={15} />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索模板、类别…"
          aria-label="搜索模板"
        />
      </div>
      <div className="gallery-chips">
        {['全部', ...VARIANT_CATEGORIES].map((c) => (
          <button
            key={c}
            className={category === c ? 'active' : ''}
            onClick={() => {
              setCategory(c)
              if (c !== '系统相机') setVendor('全部')
            }}
            type="button"
          >
            {c}
          </button>
        ))}
      </div>
      {category === '系统相机' ? (
        <div className="gallery-chips gallery-subchips">
          {[
            { id: '全部', label: '全部厂商' },
            { id: 'oppo', label: 'OPPO' },
            { id: 'xiaomi', label: '小米' },
            { id: 'vivo', label: 'vivo' }
          ].map((v) => (
            <button
              key={v.id}
              className={vendor === v.id ? 'active' : ''}
              onClick={() => setVendor(v.id)}
              type="button"
            >
              {v.label}
            </button>
          ))}
        </div>
      ) : null}
      <div className="gallery-scroll">
        {filtered.map((group) => (
          <section key={group.category} className="gallery-group">
            <div className="gallery-group-head">
              <h3>{group.category}</h3>
              <span>{group.items.length}</span>
            </div>
            <div className="tpl-grid">
              {group.items.map((v) => (
                <button
                  key={v.id}
                  className={`tpl-card ${activeId === v.id ? 'active' : ''}`}
                  onClick={() => {
                    setVariant(v.id)
                    onPick?.()
                  }}
                  title={`${v.name} · ${v.category}`}
                  type="button"
                >
                  <span className="tpl-art">
                    <VariantThumb variant={v} />
                    {v.badge ? <span className={`tpl-badge ${v.badge === '热门' ? 'hot' : ''}`}>{v.badge}</span> : null}
                    {activeId === v.id ? (
                      <span className="tpl-check">
                        <Check size={12} />
                      </span>
                    ) : null}
                  </span>
                  <span className="tpl-meta">
                    <strong>{v.name}</strong>
                    <em>{v.category}</em>
                  </span>
                </button>
              ))}
            </div>
          </section>
        ))}
        {filtered.length === 0 && !blocked.length ? <div className="empty-note">没有匹配的模板，换个关键词试试。</div> : null}
        {blocked.length ? (
          <details className="blocked-list">
            <summary>
              暂未接入 {blocked.length} 组{blockedVivoCount ? `（含 vivo ${blockedVivoCount} 套）` : ''}
            </summary>
            <div className="blocked-grid">
              {blocked.map((b) => (
                <div className="blocked-card" key={b.id}>
                  <strong>{b.name}</strong>
                  <em>
                    {b.vendor.toUpperCase()} · {b.count ? `${b.count} 套` : 'blocked'}
                  </em>
                  <p>{b.reason}</p>
                </div>
              ))}
            </div>
          </details>
        ) : null}
      </div>
    </div>
  )
}
