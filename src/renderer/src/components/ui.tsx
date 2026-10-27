import React, { useEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  wide,
  className
}: {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
  className?: string
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  return createPortal(
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal glass ${wide ? 'modal-wide' : ''} ${className ?? ''}`} role="dialog" aria-modal="true">
        <header className="modal-head">
          <div>
            <h2>{title}</h2>
            {subtitle ? <p>{subtitle}</p> : null}
          </div>
          <button className="icon-btn glass-btn glass-spot glass-ripple glass-press" onClick={onClose} title="关闭">
            <X size={18} />
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer ? <footer className="modal-foot">{footer}</footer> : null}
      </div>
    </div>,
    document.body
  )
}

export function Slider({
  label,
  value,
  min,
  max,
  step = 1,
  suffix = '',
  onChange,
  disabled,
  hint
}: {
  label: string
  value: number
  min: number
  max: number
  step?: number
  suffix?: string
  onChange: (v: number) => void
  disabled?: boolean
  hint?: string
}) {
  const display = step < 0.1 ? value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '') : String(value)
  return (
    <label className={`slider-row ${disabled ? 'disabled' : ''}`}>
      <span className="slider-label">
        {label}
        {hint ? <em>{hint}</em> : null}
      </span>
      <span className="slider-value">
        {display}
        {suffix}
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
      />
    </label>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  compact
}: {
  value: T
  options: { value: T; label: string; hint?: string }[]
  onChange: (v: T) => void
  compact?: boolean
}) {
  return (
    <div className={`segmented ${compact ? 'compact' : ''}`}>
      {options.map((o) => (
        <button
          key={o.value}
          className={`glass-press ${value === o.value ? 'active' : ''}`}
          onClick={() => onChange(o.value)}
          title={o.hint}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Switch({
  checked,
  onChange,
  label,
  hint,
  disabled
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  hint?: string
  disabled?: boolean
}) {
  return (
    <button className="switch-row" onClick={() => onChange(!checked)} type="button" disabled={disabled}>
      <span>
        <strong>{label}</strong>
        {hint ? <em>{hint}</em> : null}
      </span>
      <span className={`switch ${checked ? 'on' : ''}`} aria-checked={checked} role="switch">
        <span />
      </span>
    </button>
  )
}

export function Section({
  title,
  hint,
  action,
  children
}: {
  title: string
  hint?: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="panel-section">
      <div className="section-head">
        <div>
          <h3>{title}</h3>
          {hint ? <p>{hint}</p> : null}
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

export function IconButton({
  title,
  onClick,
  children,
  active,
  danger,
  disabled,
  className
}: {
  title: string
  onClick?: () => void
  children: ReactNode
  active?: boolean
  danger?: boolean
  disabled?: boolean
  className?: string
}) {
  return (
    <button
      className={`icon-btn glass-btn glass-ripple glass-press ${active ? 'active' : ''} ${danger ? 'danger' : ''} ${className ?? ''}`}
      title={title}
      aria-label={title}
      onClick={onClick}
      disabled={disabled}
      type="button"
    >
      {children}
    </button>
  )
}

export function ToggleGrid({
  options,
  value,
  onChange
}: {
  options: { value: string; label: string }[]
  value: Record<string, boolean>
  onChange: (key: string, v: boolean) => void
}) {
  return (
    <div className="toggle-grid">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={`toggle-chip ${value[o.value] ? 'on' : ''}`}
          onClick={() => onChange(o.value, !value[o.value])}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function LogoChecker({ src, alt }: { src: string; alt: string }) {
  return (
    <span className="logo-checker">
      <img src={src} alt={alt} />
    </span>
  )
}
