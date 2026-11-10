import React, { useEffect, useMemo, useRef, useState } from 'react'
import { ChevronDown, Search, X } from 'lucide-react'
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

const OPPO_SERIES = ['GR', '哈苏', '个性画框', '文字水印']

/** OPPO 相册自己的四类：GR / 哈苏 / 个性画框 / 文字水印。 */
function oppoSeries(card: TaxonomyCard): string {
  const id = card.variantId.toLowerCase()
  const name = card.name
  if (id.includes('gr_style') || id.includes('camerapro-gr') || name.startsWith('理光 GR')) return 'GR'
  if (id.includes('hassel') || name.includes('哈苏')) return '哈苏'
  if (id.includes('text_style') || name.includes('文字')) return '文字水印'
  return '个性画框'
}

function seriesFor(brand: string | null, card: TaxonomyCard): string {
  return brand === 'OPPO' ? oppoSeries(card) : seriesOf(card.name)
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
    if (query) return seriesFor(item.card.brand ?? null, item.card)
    if (section.key === 'vendor' && brand === null) return item.card.brand ?? '其他'
    if (section.key === 'vendor') return item.card.brand === brand ? seriesFor(brand, item.card) : '__other'
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
  // OPPO always presents the vendor's own four buckets in a fixed order.
  if (section.key === 'vendor' && brand === 'OPPO') {
    groups.sort((a, b) => OPPO_SERIES.indexOf(a.label) - OPPO_SERIES.indexOf(b.label))
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
  foldedGroups,
  visible,
  modal,
  onToggle,
  onToggleGroup,
  onToggleFold,
  onPick,
  vendorChips
}: {
  section: LibrarySection
  activeId: string
  brand: string | null
  query: string
  expanded: boolean
  expandedGroups: Record<string, boolean>
  foldedGroups: Record<string, boolean>
  visible: boolean
  modal: boolean
  onToggle: (key: BorderCategoryKey) => void
  onToggleGroup: (key: string) => void
  onToggleFold: (key: string) => void
  onPick?: () => void
  vendorChips?: React.ReactNode
}) {
  const setVariant = useAppStore((s) => s.setVariant)
  const searching = query.trim().length > 0
  const matching = section.cards.filter((item) => cardMatches(item, section, query, brand))
  const matchingIds = new Set(matching.map(({ card }) => card.variantId))
  const groups = groupCards(section, brand, query)
  // Non-vendor categories share one 8-card first screen across all series;
  // vendor groups keep 8 per brand so choosing a brand shows its series split.
  const categoryCap =
    !searching && section.key !== 'vendor' && !expanded
      ? new Set(matching.slice(0, CARD_LIMIT).map(({ card }) => card.variantId))
      : null
  const limit =
    searching || (section.key === 'vendor' && brand !== null)
      ? Number.POSITIVE_INFINITY
      : section.key === 'vendor'
        ? CARD_LIMIT
        : Number.POSITIVE_INFINITY
  const peekLimit = searching
    ? Number.POSITIVE_INFINITY
    : section.key === 'vendor' && brand === null
      ? CARD_LIMIT
      : PEEK
  const shownOf = (cards: LibraryCard[], groupKey: string) => {
    const selectable = cards.filter(({ card }) => matchingIds.has(card.variantId))
    const all = expanded || expandedGroups[groupKey]
    const visibleCount = all ? selectable.length : Math.min(limit, selectable.length)
    return new Set(
      selectable
        .slice(0, visibleCount)
        .map(({ card }) => card.variantId)
        .filter((id) => !categoryCap || categoryCap.has(id))
    )
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
        const folded = !!foldedGroups[groupKey]
        const hasMore = !searching && !expanded && (selectable.length > peekLimit || selectable.length > limit)
        const seriesHidden =
          selectable.length === 0 || (!!categoryCap && !expanded && !expandedGroups[groupKey] && shown.size === 0)
        return (
          <div
            className={`border-series ${seriesHidden ? 'border-series-empty' : ''} ${folded ? 'border-series-folded' : ''}`}
            key={group.key}
          >
            <button
              className="border-series-head"
              type="button"
              aria-label={`${group.label} 系列`}
              aria-expanded={!folded}
              title={folded ? `展开 ${group.label}` : `收起 ${group.label}`}
              onClick={() => onToggleFold(groupKey)}
            >
              <strong>{group.label}</strong>
              <span className="border-series-meta">
                <em>{group.cards.length}</em>
                <ChevronDown size={12} className="border-series-chevron" />
              </span>
            </button>
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
                  {groupOpen ? `只看前 ${peekLimit} 套` : `查看这个系列全部 ${selectable.length} 套`}
                </button>
              </div>
            ) : null}
          </div>
        )
      })}
      {!searching && matching.length > CARD_LIMIT && (section.key !== 'vendor' || limit !== Number.POSITIVE_INFINITY) ? (
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
  const [foldedGroups, setFoldedGroups] = useState<Record<string, boolean>>({})
  const [activeCategory, setActiveCategory] = useState<BorderCategoryKey>('vendor')
  const [navOpen, setNavOpen] = useState(true)
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
          <button
            className={`border-nav-toggle ${navOpen ? '' : 'border-nav-toggle-closed'}`}
            type="button"
            aria-expanded={navOpen}
            aria-controls="border-category-nav"
            onClick={() => setNavOpen((prev) => !prev)}
          >
            <span>分类</span>
            <em>
              {SECTIONS.find((section) => section.key === activeCategory)?.name} ·{' '}
              {SECTIONS.find((section) => section.key === activeCategory)?.count}
            </em>
            <ChevronDown size={13} className="border-nav-chevron" />
          </button>
          <nav
            id="border-category-nav"
            className={`border-category-nav ${navOpen ? '' : 'border-category-nav-closed'}`}
            aria-label="边框分类"
            aria-hidden={!navOpen}
          >
            {SECTIONS.map((section) => (
              <button
                key={section.key}
                className={`border-category-btn glass-ripple glass-press ${!query.trim() && activeCategory === section.key ? 'active' : ''}`}
                data-category={section.key}
                onClick={() => jumpTo(section.key)}
                type="button"
                aria-current={!query.trim() && activeCategory === section.key ? 'true' : undefined}
                tabIndex={navOpen ? 0 : -1}
              >
                <span>{section.name}</span>
                <em>{section.count}</em>
              </button>
            ))}
          </nav>
          {/* Level-2 vendor chips live OUTSIDE .border-scroll (same as the level-1
              nav above) so they stay pinned: the card list is the only scroller and
              nothing can paint behind them. Kept in-section only for the modal. */}
          {activeCategory === 'vendor' && !query.trim() ? (
            <div className="border-vendor-dock">
              <VendorChips brand={brand} onSelect={selectBrand} />
            </div>
          ) : null}
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
              foldedGroups={foldedGroups}
              visible={sectionVisible}
              modal={modal}
              onToggle={(key) => setExpanded((prev) => ({ ...prev, [key]: !prev[key] }))}
              onToggleGroup={(key) => setExpandedGroups((prev) => ({ ...prev, [key]: !prev[key] }))}
              onToggleFold={(key) => setFoldedGroups((prev) => ({ ...prev, [key]: !prev[key] }))}
              onPick={onPick}
              vendorChips={modal && section.key === 'vendor' ? <VendorChips brand={brand} onSelect={selectBrand} /> : null}
            />
          )
        })}
      </div>
    </div>
  )
}
