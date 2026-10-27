import React, { useEffect, useRef, useState } from 'react'
import type { TemplateVariant } from '../types'
import {
  BORDER_CATEGORIES,
  TAXONOMY_CARDS,
  type BorderCategoryKey,
  type TaxonomyCard
} from '../core/taxonomy.generated'
import { getVariant } from '../core/variants'
import { useAppStore } from '../store'
import { VariantThumb } from './VariantThumb'

interface LibraryCard {
  card: TaxonomyCard
  variant: TemplateVariant
}

interface LibrarySection {
  key: BorderCategoryKey
  name: string
  count: number
  cards: LibraryCard[]
}

/** Cards shown per section before "查看全部" — the rest stay in the DOM, hidden. */
const CARD_LIMIT = 8

/**
 * One card belongs to exactly one of the seven categories; the order inside a
 * category is the approved prototype order. The registry is static, so build the
 * sections once instead of filtering 175 entries on every render.
 */
const SECTIONS: LibrarySection[] = BORDER_CATEGORIES.map((category) => ({
  key: category.key,
  name: category.name,
  count: category.count,
  cards: TAXONOMY_CARDS.filter((card) => card.category === category.key).map((card) => ({
    card,
    variant: getVariant(card.variantId)
  }))
}))

const VENDOR_BRANDS = BORDER_CATEGORIES.find((category) => category.key === 'vendor')?.brands ?? []
const TOTAL_CARDS = TAXONOMY_CARDS.length

/** Two top-level groups so the 7 categories read as brand watermarks vs styles. */
const NAV_GROUPS: Partial<Record<BorderCategoryKey, string>> = {
  vendor: '相机厂商水印',
  text: '设计样式'
}

function VendorChips({
  brand,
  onSelect
}: {
  brand: string | null
  onSelect: (brand: string | null) => void
}) {
  return (
    <div className="border-vendor-chips" role="group" aria-label="厂商筛选">
      <button
        className={`border-chip glass-ripple glass-press ${brand === null ? 'active' : ''}`}
        onClick={() => onSelect(null)}
        type="button"
        aria-pressed={brand === null}
      >
        全部
      </button>
      {VENDOR_BRANDS.map((vendor) => (
        <button
          key={vendor.key}
          className={`border-chip glass-ripple glass-press ${brand === vendor.key ? 'active' : ''}`}
          onClick={() => onSelect(brand === vendor.key ? null : vendor.key)}
          type="button"
          aria-pressed={brand === vendor.key}
        >
          {vendor.name} <em>{vendor.count}</em>
        </button>
      ))}
    </div>
  )
}

function seriesOf(name: string): string {
  const head = name.split('·')[0]?.trim() ?? ''
  return head || name
}

function BorderSection({
  section,
  activeId,
  brand,
  modal,
  expanded,
  onToggle,
  onPick,
  setSectionRef,
  vendorChips
}: {
  section: LibrarySection
  activeId: string
  brand: string | null
  modal: boolean
  expanded: boolean
  onToggle: (key: BorderCategoryKey) => void
  onPick?: () => void
  setSectionRef: (el: HTMLElement | null) => void
  vendorChips?: React.ReactNode
}) {
  const setVariant = useAppStore((s) => s.setVariant)
  const matching = section.cards.filter(
    ({ card }) => section.key !== 'vendor' || brand === null || card.brand === brand
  )
  // 分组只决定展示顺序，不决定是否渲染：175 张卡必须始终在 DOM 里（筛选/折叠
  // 只加 .border-card-hidden）。厂商段未选厂商时按厂商分组，选中后按系列分组，
  // 其它厂商的卡归入隐藏的「其他」组以保持 DOM 契约。
  const groups: { key: string; label: string; cards: LibraryCard[] }[] = []
  if (section.key === 'vendor') {
    const by = brand === null
      ? (card: LibraryCard) => card.card.brand ?? '其他'
      : (card: LibraryCard) =>
          card.card.brand === brand ? seriesOf(card.card.name) : '__other'
    const index = new Map<string, { key: string; label: string; cards: LibraryCard[] }>()
    for (const item of section.cards) {
      const key = by(item)
      let group = index.get(key)
      if (!group) {
        group = { key, label: key === '__other' ? '其他' : key, cards: [] }
        index.set(key, group)
        groups.push(group)
      }
      group.cards.push(item)
    }
  } else {
    groups.push({ key: section.key, label: section.name, cards: section.cards })
  }
  const matchingIds = new Set(matching.map(({ card }) => card.variantId))
  const limit = brand !== null && section.key === 'vendor' ? Number.POSITIVE_INFINITY : CARD_LIMIT
  const shownOf = (cards: LibraryCard[]) => {
    const selectable = cards.filter(({ card }) => matchingIds.has(card.variantId))
    const visible = expanded ? selectable.length : Math.min(limit, selectable.length)
    return new Set(selectable.slice(0, visible).map(({ card }) => card.variantId))
  }

  return (
    <section className="border-section" data-category={section.key} ref={setSectionRef} aria-label={section.name}>
      <div className="border-section-head">
        <h3>{section.name}</h3>
        <span>{section.count}</span>
      </div>
      {vendorChips}
      {groups.map((group) => {
        const shown = shownOf(group.cards)
        return (
          <div className="border-series" key={group.key}>
            {groups.length > 1 ? (
              <div className="border-series-head" aria-label={`${group.label} 系列`}>
                <strong>{group.label}</strong>
                <em>{group.cards.length}</em>
              </div>
            ) : null}
            <div className="tpl-grid border-grid">
              {group.cards.map(({ card, variant }) => {
                const active = activeId === card.variantId
                const hidden = !matchingIds.has(card.variantId) || !shown.has(card.variantId)
                return (
                  <button
                    key={card.variantId}
                    className={`tpl-card border-card glass-hover glass-press ${active ? 'active' : ''} ${hidden ? 'border-card-hidden' : ''}`}
                    onClick={() => {
                      setVariant(card.variantId)
                      onPick?.()
                    }}
                    title={card.name}
                    type="button"
                    aria-pressed={active}
                  >
                    <span className="tpl-art">
                      <VariantThumb variant={variant} />
                    </span>
                    <span className="tpl-meta">
                      <strong>{card.name}</strong>
                    </span>
                  </button>
                )
              })}
            </div>
          </div>
        )
      })}
      {matching.length > CARD_LIMIT ? (
        <div className="border-section-more-row">
          <button
            className="border-section-more glass-btn glass-spot glass-ripple glass-press"
            onClick={() => onToggle(section.key)}
            type="button"
          >
            {expanded ? '收起' : `展开全部 ${matching.length} 套`}
          </button>
        </div>
      ) : null}
    </section>
  )
}

/**
 * Border library (v4 UI, phase 2) — replaces the old 16-category TemplateGallery.
 *
 * Sidebar mode renders all seven sections so the DOM always holds the 175
 * `.tpl-card` nodes; category buttons are navigation (scroll), the vendor chips
 * are the only filter and they only narrow the vendor section. Modal mode keeps
 * every card mounted as well and hides the tail of each section until "查看全部".
 */
export function BorderLibrary({ modal = false, onPick }: { modal?: boolean; onPick?: () => void }) {
  const activeId = useAppStore((s) => s.design.variantId) ?? ''
  const [brand, setBrand] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Partial<Record<BorderCategoryKey, boolean>>>({})
  const [activeCategory, setActiveCategory] = useState<BorderCategoryKey>('vendor')
  const sectionRefs = useRef<Partial<Record<BorderCategoryKey, HTMLElement | null>>>({})
  const scrollRef = useRef<HTMLDivElement>(null)

  // The category nav follows the section actually under the rail's top edge.
  // It used to highlight the active template's category while showing vendor at
  // the top of the scroll, which made the nav lie about what the user was seeing.
  useEffect(() => {
    const scroller = scrollRef.current
    if (!scroller) return
    const onScroll = () => {
      const top = scroller.getBoundingClientRect().top + 24
      let current: BorderCategoryKey = 'vendor'
      for (const section of SECTIONS) {
        const node = sectionRefs.current[section.key]
        if (node && node.getBoundingClientRect().top <= top) current = section.key
      }
      setActiveCategory(current)
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    onScroll()
    return () => scroller.removeEventListener('scroll', onScroll)
  }, [])

  const jumpTo = (key: BorderCategoryKey) => {
    setActiveCategory(key)
    sectionRefs.current[key]?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const selectBrand = (next: string | null) => {
    setBrand(next)
    if (modal) return
    setActiveCategory('vendor')
    sectionRefs.current.vendor?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <div className={`border-library ${modal ? 'border-library-inline' : ''}`}>
      {!modal ? (
        <>
          <header className="border-library-head">
            <h2>边框</h2>
            <span className="border-library-total">{TOTAL_CARDS} 套</span>
          </header>
          <nav className="border-category-nav" aria-label="边框分类">
            {SECTIONS.map((section) => (
              <React.Fragment key={section.key}>
                {NAV_GROUPS[section.key] ? (
                  <span className="border-nav-group">{NAV_GROUPS[section.key]}</span>
                ) : null}
                <button
                  className={`border-category-btn glass-ripple glass-press ${activeCategory === section.key ? 'active' : ''}`}
                  data-category={section.key}
                  onClick={() => jumpTo(section.key)}
                  type="button"
                  aria-current={activeCategory === section.key ? 'true' : undefined}
                >
                  <span>{section.name}</span>
                  <em>{section.count}</em>
                </button>
              </React.Fragment>
            ))}
          </nav>
        </>
      ) : null}

      <div className={`border-scroll ${modal ? 'border-scroll-modal' : ''}`} ref={scrollRef}>
        {SECTIONS.map((section) => (
          <BorderSection
            key={section.key}
            section={section}
            activeId={activeId}
            brand={brand}
            modal={modal}
            expanded={!!expanded[section.key]}
            onToggle={(key) => setExpanded((prev) => ({ ...prev, [key]: !prev[key] }))}
            onPick={onPick}
            vendorChips={section.key === 'vendor' ? <VendorChips brand={brand} onSelect={selectBrand} /> : null}
            setSectionRef={(el) => {
              sectionRefs.current[section.key] = el
            }}
          />
        ))}
      </div>
    </div>
  )
}
