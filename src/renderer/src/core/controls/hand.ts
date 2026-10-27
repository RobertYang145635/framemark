import type { Design } from '../../types'
import { HAND_PANELS, type HandControl, type HandPanel } from './hand.generated'

export function handPanelFor(templateId: string): HandPanel | null {
  return HAND_PANELS.find((panel) => panel.template === templateId) ?? null
}

export function handControlsFor(templateId: string, tab: 'layout' | 'text'): HandControl[] {
  return handPanelFor(templateId)?.controls.filter((control) => control.tab === tab) ?? []
}

const textKey = (param: string) => param.replace(/^text\./, '')

export function handValue(design: Design, control: HandControl): unknown {
  if (control.scope === 'design-text') {
    return (design.text as unknown as Record<string, unknown>)[textKey(control.param)]
  }
  return (design.params as Record<string, unknown>)[control.param]
}

export function handPatch(control: HandControl, value: unknown): { kind: 'param' | 'text'; patch: Record<string, unknown> } {
  if (control.scope === 'design-text') return { kind: 'text', patch: { [textKey(control.param)]: value } }
  return { kind: 'param', patch: { [control.param]: value } }
}

export function normalizeHex(value: unknown): string {
  const raw = String(value ?? '').trim()
  if (/^#[0-9a-f]{6}$/i.test(raw)) return raw.toLowerCase()
  if (/^#[0-9a-f]{3}$/i.test(raw)) {
    return ('#' + raw.slice(1).split('').map((c) => c + c).join('')).toLowerCase()
  }
  return '#000000'
}