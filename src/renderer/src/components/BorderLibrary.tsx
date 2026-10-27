import React, { useEffect, useMemo, useRef, useState } from 'react'
import { Search, X } from 'lucide-react'
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

interface CardGroup {
  key: string
  label: string
  cards: LibraryCard[]
}

/** Cards shown per section before "查看全部" — the rest stay in the DOM, hidden. */
const CARD_LIMIT = 8
/** How many cards a series shows before its own "查看全部". */
const PEEK = 4

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

function cardMatches(item: LibraryCard, section: LibrarySection, query: string, brand: string | null): boolean {
  if (query) {
    const haystack = `${item.card.name} ${item.card.brand ?? ''} ${section.name}`.toLowerCase()
    return haystack.includes(query.toLowerCase())
  }
  if (section.key === 'vendor' && brand !== null && item.card.brand !== brand) return false
  return true
}

function groupCards(section: LibrarySection, brand: string | null, query: string): CardGroup[] {
  const groups: CardGroup[] = []
  const index = new Map<string, CardGroup>()
  const by = (item: LibraryCard): string => {
    if (query) return seriesOf(item.card.name)
    if (section.key === 'vendor' && brand === null) return item.card.brand ?? '其他'
    if (section.key === 'vendor') return item.card.brand === brand ? seriesOf(item.card.name) : '__other'
    return seriesOf(item.card.name)
  }
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
  // Hide the "other brand" bucket when it has no visible cards, but keep it in
  // the DOM so the 175-card invariant survives.
  return groups
}

function BorderSection({
  section,
  activeId,
  brand,
  query,
  expanded,
  expandedGroups,
  visible,
  modal,
  onToggle,
  onToggleGroup,
  onPick,
  vendorChips
}: {
  section: LibrarySection
  activeId: string
  brand: string | null
  query: string
  expanded: boolean
  expandedGroups: Record<string, boolean>
  visible: boolean
  modal: boolean
  onToggle: (key: BorderCategoryKey) => void
  onToggleGroup: (key: string) => void
  onPick?: () => void
  vendorChips?: React.ReactNode
}) {
  const setVariant = useAppStore((s) => s.setVariant)
  const searching = query.trim().length > 0
  const matching = section.cards.filter((item) => cardMatches(item, section, query, brand))
  const matchingIds = new Set(matching.map(({ card }) => card.variantId))
  const groups = groupCards(section, brand, query)
  const limit = searching || (brand !== null && section.key === 'vendor') ? Number.POSITIVE_INFINITY : CARD_LIMIT
  const peekLimit = searching
    ? Number.POSITIVE_INFINITY
    : section.key === 'vendor' && brand === null
      ? CARD_LIMIT
      : PEEK
  const shownOf = (cards: LibraryCard[], groupKey: string) => {
    const selectable = cards.filter(({ card }) => matchingIds.has(card.variantId))
    const all = expanded || expandedGroups[groupKey]
    const visibleCount = all ? selectable.length : Math.min(limit, selectable.length)
    return new Set(selectable.slice(0, visibleCount).map(({ card }) => card.variantId))
  }
  const peekOf = (cards: LibraryCard[], groupKey: string) => {
    const selectable = cards.filter(({ card }) => matchingIds.has(card.variantId))
    const all = searching || expanded || expandedGroups[groupKey]
    return new Set(selectable.slice(0, all ? selectable.length : peekLimit).map(({ card }) => card.variantId))
  }

  return (
    <section
      className={`border-section ${visible ? 'border-section-active' : ''}`}
      data-category={section.key}
      aria-label={section.name}
    >
      <div className="border-section-head">
        <h3>{section.name}</h3>
        <span>{section.count}</span>
      </div>
      {section.key === 'vendor' && !searching ? vendorChips : null}
      {groups.map((group) => {
        const groupKey = `${section.key}:${group.key}`
        const shown = shownOf(group.cards, groupKey)
        const peek = peekOf(group.cards, groupKey)
        const selectable = group.cards.filter(({ card }) => matchingIds.has(card.variantId))
        const groupOpen = expanded || expandedGroups[groupKey]
        const hasMore = !searching && (selectable.length > peekLimit || selectable.length > limit)
        return (
          <div className={`border-series ${selectable.length === 0 ? 'border-series-empty' : ''}`} key={group.key}>
            <div className="border-series-head" aria-label={`${group.label} 系列`}>
              <strong>{group.label}</strong>
              <em>{group.cards.length}</em>
            </div>
            <div className="tpl-grid border-grid">
              {group.cards.map(({ card, variant }) => {
                const isActive = activeId === card.variantId
                const hidden = !matchingIds.has(card.variantId) || !shown.has(card.variantId)
                const peeked = !hidden && !peek.has(card.variantId)
                return (
                  <button
                    key={card.variantId}
                    className={`tpl-card border-card glass-hover glass-press ${isActive ? 'active' : ''} ${hidden ? 'border-card-hidden' : ''} ${peeked ? 'border-card-peek' : ''}`}
                    onClick={() => {
                      setVariant(card.variantId)
                      onPick?.()
                    }}
                    title={card.name}
                    type="button"
                    aria-pressed={isActive}
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
            {hasMore ? (
              <div className="border-series-more-row">
                <button
                  className="border-series-more glass-btn glass-spot glass-ripple glass-press"
                  onClick={() => onToggleGroup(groupKey)}
                  type="button"
                >
                  {groupOpen ? '收起这个系列' : `查看这个系列全部 ${selectable.length} 套`}
                </button>
              </div>
            ) : null}
          </div>
        )
      })}
      {!searching && limit !== Number.POSITIVE_INFINITY && matching.length > CARD_LIMIT ? (
        <div className="border-section-more-row">
          <button
            className="border-section-more glass-btn glass-spot glass-ripple glass-press"
            onClick={() => onToggle(section.key)}
            type="button"
          >
            {expanded ? '收起全部' : `展开全部 ${matching.length} 套`}
          </button>
        </div>
      ) : null}
    </section>
  )
}

/**
 * Border library — one category at a time, grouped by vendor/series, with a
 * global search. All 175 `.tpl-card` nodes stay mounted; only the active
 * section is painted. Series show PEEK cards before their own 查看全部.
 */
export function BorderLibrary({ modal = false, onPick }: { modal?: boolean; onPick?: () => void }) {
  const activeId = useAppStore((s) => s.design.variantId) ?? ''
  const [brand, setBrand] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Partial<Record<BorderCategoryKey, boolean>>>({})
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({})
  const [activeCategory, setActiveCategory] = useState<BorderCategoryKey>('vendor')
  const [query, setQuery] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)

  const searchCount = useMemo(
    () => (query.trim() ? TAXONOMY_CARDS.filter((card) => `${card.name} ${card.brand ?? ''}`.toLowerCase().includes(query.toLowerCase())).length : 0),
    [query]
  )

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 })
  }, [activeCategory, query])

  const jumpTo = (key: BorderCategoryKey) => {
    setQuery('')
    setActiveCategory(key)
  }

  const selectBrand = (next: string | null) => {
    setBrand(next)
    setActiveCategory('vendor')
  }

  return (
    <div className={`border-library ${modal ? 'border-library-inline' : ''} ${query.trim() ? 'border-library-searching' : ''}`}>
      {!modal ? (
        <>
          <header className="border-library-head">
            <h2>边框</h2>
            <span className="border-library-total">{TOTAL_CARDS} 套</span>
          </header>
          <div className="border-search">
            <Search size={14} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索模板、品牌或系列"
              aria-label="搜索边框模板"
            />
            {query ? (
              <button className="border-search-clear" onClick={() => setQuery('')} type="button" aria-label="清除搜索">
                <X size={13} />
              </button>
            ) : null}
          </div>
          {query.trim() ? <p className="border-search-summary">找到 {searchCount} 套</p> : null}
          <nav className="border-category-nav" aria-label="边框分类">
            {SECTIONS.map((section) => (
              <button
                key={section.key}
                className={`border-category-btn glass-ripple glass-press ${!query.trim() && activeCategory === section.key ? 'active' : ''}`}
                data-category={section.key}
                onClick={() => jumpTo(section.key)}
                type="button"
                aria-current={!query.trim() && activeCategory === section.key ? 'true' : undefined}
              >
                <span>{section.name}</span>
                <em>{section.count}</em>
              </button>
            ))}
          </nav>
        </>
      ) : null}

      <div className={`border-scroll ${modal ? 'border-scroll-modal' : ''}`} ref={scrollRef}>
        {SECTIONS.map((section) => {
          const sectionVisible = modal
            ? true
            : query.trim()
              ? section.cards.some((item) => cardMatches(item, section, query, brand))
              : activeCategory === section.key
          return (
            <BorderSection
              key={section.key}
              section={section}
              activeId={activeId}
              brand={brand}
              query={query}
              expanded={!!expanded[section.key]}
              expandedGroups={expandedGroups}
              visible={sectionVisible}
              modal={modal}
              onToggle={(key) => setExpanded((prev) => ({ ...prev, [key]: !prev[key] }))}
              onToggleGroup={(key) => setExpandedGroups((prev) => ({ ...prev, [key]: !prev[key] }))}
              onPick={onPick}
              vendorChips={section.key === 'vendor' ? <VendorChips brand={brand} onSelect={selectBrand} /> : null}
            />
          )
        })}
      </div>
    </div>
  )
}
