import React from 'react'
import { useAppStore } from '../../store'
import { Section, Segmented, Slider, Switch } from '../ui'
import { handControlsFor, handPanelFor, handPatch, handValue, normalizeHex } from '../../core/controls/hand'

/**
 * Per-card controls for the ten hand templates that used to ship zero controls.
 * The list is generated from the vetted 47-item proposal (hand.generated.ts); only
 * params with a proven read site are here. showDate is intentionally absent — the
 * positional text panel already exposes it as the date row's eye.
 */
export function HandControlsPanel({ tab }: { tab: 'layout' | 'text' }) {
  const design = useAppStore((s) => s.design)
  const setParam = useAppStore((s) => s.setParam)
  const resetParam = useAppStore((s) => s.resetParam)
  const setText = useAppStore((s) => s.setText)
  const panel = handPanelFor(design.templateId)
  const controls = handControlsFor(design.templateId, tab)
  if (!panel || !controls.length) return null

  const write = (control: Parameters<typeof handPatch>[0], value: unknown) => {
    const next = handPatch(control, value)
    if (next.kind === 'text') setText(next.patch as never)
    else setParam(control.param, value as never)
  }

  return (
    <div className="hand-controls" data-hand-panel={panel.template} data-hand-count={controls.length}>
    <Section title={`${panel.name} · 参数`} hint="只列这张卡真实消费的参数；默认值来自卡片本身">
      {controls.map((control) => {
        const value = handValue(design, control)
        const marker = (node: React.ReactNode) => (
          <div key={control.id} data-hand-control={control.id} data-param={control.param}>
            {node}
          </div>
        )
        if (control.kind === 'slider') {
          const numeric = Number(value)
          return marker(
            <Slider
              label={control.label}
              value={Number.isFinite(numeric) ? numeric : (control.min ?? 0)}
              min={control.min ?? 0}
              max={control.max ?? 100}
              step={control.step ?? 1}
              suffix={control.unit ?? ''}
              onChange={(v) => write(control, v)}
              onReset={control.scope === 'template-param' ? () => resetParam(control.param) : undefined}
            />
          )
        }
        if (control.kind === 'modeSegment') {
          return marker(
            <label className="field">
              <span>{control.label}</span>
              <Segmented
                value={String(value ?? control.options?.[0]?.value ?? '')}
                options={control.options ?? []}
                onChange={(v) => write(control, v)}
              />
            </label>
          )
        }
        if (control.kind === 'colorField') {
          return marker(
            <label className="field hand-color-field">
              <span>{control.label}</span>
              <span className="hand-color-row">
                <input
                  type="color"
                  value={normalizeHex(value)}
                  onChange={(e) => write(control, e.target.value)}
                  aria-label={control.label}
                />
                <em>{normalizeHex(value)}</em>
              </span>
            </label>
          )
        }
        if (control.kind === 'toggle') {
          return marker(
            <Switch
              checked={Boolean(value)}
              onChange={(v) => write(control, v)}
              label={control.label}
            />
          )
        }
        return marker(
          <label className="field">
            <span>{control.label}</span>
            <input
              value={String(value ?? '')}
              onChange={(e) => write(control, e.target.value)}
              placeholder={control.label}
            />
          </label>
        )
      })}
    </Section>
    </div>
  )
}