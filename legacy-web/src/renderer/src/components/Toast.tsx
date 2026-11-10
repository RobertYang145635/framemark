import React, { useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, Info } from 'lucide-react'
import { useAppStore } from '../store'
import { readCssTokenMs } from './glass/glassBoot'

type ToastFrame = { message: string; type: string; seq: number; leaving: boolean }

/**
 * GLASS-UI G-1 · Toast 玻璃皮 + 进/出动画（04-B7：进 240ms 弹簧 / 出 180ms ease-in）。
 *
 * 约束与做法：
 *  - store 的 toast 只有 { message, type }，出现与清除时机由 store 决定，本组件不改它；
 *    只是在 store 清空之后自行多留一帧退出动画，退出时长从 CSS 令牌 --dur-spot 实时读取
 *    （readCssTokenMs），不在 TS 里写死数字。
 *  - DOM 语义结构与可访问性属性不动：role="status"、.toast-icon、文案 span 与迁移前一致，
 *    e2e/workflow 读的就是 .toast 的 textContent（'已保存' / '已应用「X」' / '预设已保存到'
 *    / '已导入 1 个预设' 四段文案由 store 产出，本文件不碰）。
 *  - 退出那一帧带 aria-hidden，不会被读屏重复播报；key 里的 seq 让连续两条 Toast 各自
 *    重放进入动画（React 复用同一 DOM 节点时 animation 不会自动重启）。
 */
export function Toast() {
  const toast = useAppStore((s) => s.toast)
  const [frame, setFrame] = useState<ToastFrame | null>(null)
  const seq = useRef(0)

  useEffect(() => {
    if (toast) {
      seq.current += 1
      setFrame({ message: toast.message, type: toast.type, seq: seq.current, leaving: false })
    } else {
      setFrame((f) => (f && !f.leaving ? { ...f, leaving: true } : f))
    }
  }, [toast])

  useEffect(() => {
    if (!frame?.leaving) return
    const timer = window.setTimeout(() => setFrame(null), readCssTokenMs('--dur-spot', 180))
    return () => window.clearTimeout(timer)
  }, [frame])

  if (!frame) return null
  const icon =
    frame.type === 'success' ? (
      <Check size={16} />
    ) : frame.type === 'error' ? (
      <AlertTriangle size={16} />
    ) : (
      <Info size={16} />
    )

  return (
    <div
      key={`${frame.seq}-${frame.leaving ? 'out' : 'in'}`}
      className={`toast ${frame.type} ${frame.leaving ? 'glass-animate-exit' : 'glass-animate-enter'}`}
      role="status"
      aria-hidden={frame.leaving ? true : undefined}
    >
      <span className="toast-icon">{icon}</span>
      <span>{frame.message}</span>
    </div>
  )
}
