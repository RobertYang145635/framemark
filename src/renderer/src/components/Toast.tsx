import React from 'react'
import { AlertTriangle, Check, Info } from 'lucide-react'
import { useAppStore } from '../store'

export function Toast() {
  const toast = useAppStore((s) => s.toast)
  if (!toast) return null
  return (
    <div className={`toast ${toast.type}`} role="status">
      <span className="toast-icon">
        {toast.type === 'success' ? <Check size={16} /> : toast.type === 'error' ? <AlertTriangle size={16} /> : <Info size={16} />}
      </span>
      <span>{toast.message}</span>
    </div>
  )
}
