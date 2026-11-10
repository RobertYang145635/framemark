import { Capacitor, registerPlugin } from '@capacitor/core'

/**
 * S4 — Android export foreground bridge.
 *
 * While a batch export runs on Android the renderer asks the native side for a
 * `dataSync` foreground service + PARTIAL_WAKE_LOCK, so locking the screen or
 * switching apps does not freeze the WebView (and with it the export loop).
 *
 * Every function here is fire-and-forget and defensive on purpose: a service
 * failure must never throw into the export loop. On desktop (Electron) and on
 * the web all three are no-ops — `Capacitor.isNativePlatform()` is false, so
 * the plugin proxy is never even constructed.
 */

interface ExportForegroundBridge {
  start(options: { total: number; current?: string }): Promise<unknown>
  update(options: { done: number; total: number; current?: string }): Promise<unknown>
  stop(): Promise<unknown>
}

/**
 * Gate-4 red run: set to `false` and rebuild to prove the screen-off gate can
 * fail (the export then has no foreground service and no wake lock).
 */
const FOREGROUND_ENABLED = true

let bridge: ExportForegroundBridge | null = null

function nativeBridge(): ExportForegroundBridge | null {
  if (!FOREGROUND_ENABLED) return null
  if (!Capacitor.isNativePlatform()) return null
  if (Capacitor.getPlatform() !== 'android') return null
  if (!bridge) bridge = registerPlugin<ExportForegroundBridge>('ExportForeground')
  return bridge
}

/** Never let a rejected plugin promise surface as an unhandled rejection. */
function fire(run: () => Promise<unknown>): void {
  try {
    const pending = run()
    if (pending && typeof pending.catch === 'function') pending.catch(() => undefined)
  } catch {
    // no native implementation / bridge gone: the export continues regardless
  }
}

let lastDone = -1
let lastTotal = -1
let lastCurrent = ''

export function startExportForeground(total: number, current = ''): void {
  const api = nativeBridge()
  if (!api) return
  lastDone = 0
  lastTotal = total
  lastCurrent = current
  fire(() => api.start({ total, current }))
}

export function updateExportForeground(done: number, total: number, current = ''): void {
  const api = nativeBridge()
  if (!api) return
  // onProgress fires twice per item (before/after) and for skipped items;
  // identical snapshots would only churn the notification.
  if (done === lastDone && total === lastTotal && current === lastCurrent) return
  lastDone = done
  lastTotal = total
  lastCurrent = current
  fire(() => api.update({ done, total, current }))
}

export function stopExportForeground(): void {
  const api = nativeBridge()
  if (!api) return
  lastDone = -1
  lastTotal = -1
  lastCurrent = ''
  fire(() => api.stop())
}

/** True when this runtime is the Android app (used by the overlay copy). */
export function isAndroidNative(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}
