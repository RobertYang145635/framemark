/**
 * Phase 3 editor shell — which editor tab owns which control, and which tabs a
 * design can actually fill.
 *
 * The 439 per-style controls (106 records, `core/controls`) are partitioned here
 * exactly once: every `ControlView` maps to one tab, no control is repeated in a
 * second tab, and no tab is invented for a control family we do not have.
 *
 * `layout` is always available because 画幅 + 版式参数 apply to every template.
 * The other three are data-driven — a tab that would be empty is not rendered.
 */
import type { Design, EditorTab } from '../../types'
import type { ControlView } from '../controls'
import { controlsFor } from '../controls'
import { getTemplate } from '../templates'
import { getVariant } from '../variants'
import { getStockStyle } from '../system/styles'
import type { StockStyle } from '../system/ir'

/** Display order of the four border editor tabs. */
export const EDITOR_TABS: readonly EditorTab[] = ['logo', 'text', 'layout', 'theme']

/**
 * One control ⇒ exactly one tab.
 *
 *  - brandPicker   → logo   (brand slot mark)
 *  - themeSwatches → theme  (per-style colour themes)
 *  - text          → text   (slot text input)
 *  - everything else follows the record's own group: content ⇒ text,
 *    layout/detail ⇒ layout (toggles of content slots are wording, toggles of
 *    layout slots are geometry).
 */
export function editorTabForControl(view: ControlView): EditorTab {
  const control = view.control
  switch (control.kind) {
    case 'brandPicker':
      return 'logo'
    case 'themeSwatches':
      return 'theme'
    case 'text':
      return 'text'
    case 'toggle':
    case 'slider':
    case 'anchorGrid':
    case 'xyOffset':
    case 'gravitySegment':
      return control.group === 'content' ? 'text' : 'layout'
    default: {
      // Exhaustiveness guard: a new ControlKind must decide its tab here instead
      // of silently landing nowhere (the partition script asserts this too).
      const never: never = control
      throw new Error(`unmapped control kind: ${JSON.stringify(never)}`)
    }
  }
}

/**
 * Does this stock style declare an `isBrandLogo` slot node? The stock engine's
 * only logo consumer is `params.brandLogoFile`, which `assets.ts` applies solely
 * to `isBrandLogo` slots — fixed co-brand marks (Leica roundel, Hasselblad, the
 * 哈苏文字 marks) have none, so the brand picker would be inert for those cards.
 */
export function stockHasBrandSlot(style: StockStyle | null): boolean {
  if (!style) return false
  const walk = (nodes: StockStyle['bands'][number]['children']): boolean =>
    nodes.some((n) => (n.kind === 'image' ? !!n.isBrandLogo : n.kind === 'group' ? walk(n.children) : false))
  return style.bands.some((b) => walk(b.children))
}

/** Hand templates carry generic text/logo sections; stock cards use their own records. */
export function isHandTemplate(design: Pick<Design, 'templateId'>): boolean {
  return getTemplate(design.templateId).id !== 'systemCamera'
}

/**
 * The editor tabs this design can fill, in display order.
 *
 *  - logo:  LogoSection would render (hand template or stock brand slot),
 *           or the style declares a brandPicker control.
 *  - text:  hand template (generic TextSection) or any text-tab control.
 *  - 布局:  always (画幅 + 版式参数).
 *  - theme: variant/def themes exist or any theme-tab control.
 */
export function availableEditorTabs(design: Design): EditorTab[] {
  const def = getTemplate(design.templateId)
  const variant = getVariant(design.variantId)
  const views = controlsFor(design)?.views ?? []
  const hand = def.id !== 'systemCamera'
  const style = hand ? null : getStockStyle(String(design.params.systemStyle ?? ''))
  const themes = variant.themes?.length ? variant.themes : def.themes

  const has = (tab: EditorTab) => views.some((view) => editorTabForControl(view) === tab)
  const available: EditorTab[] = []
  if (hand || stockHasBrandSlot(style) || has('logo')) available.push('logo')
  if (hand || has('text')) available.push('text')
  available.push('layout')
  if (themes?.length || has('theme')) available.push('theme')
  return available
}

/** Control counts per editor tab (all views, live or not; used by the partition audit). */
export function editorControlCounts(views: readonly ControlView[]): Record<EditorTab, number> {
  const counts: Record<EditorTab, number> = { logo: 0, text: 0, layout: 0, theme: 0 }
  for (const view of views) counts[editorTabForControl(view)] += 1
  return counts
}
