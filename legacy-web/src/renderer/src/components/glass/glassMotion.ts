/**
 * GLASS-UI G-0 · 动效基元的 JS 侧搭档（配合 styles.css 的 .glass-* 类）
 *
 * 只做三件事，全部是 transform/opacity 级别、可整机关闭：
 *  - 光斑跟随：pointermove 写 --mx/--my（04-A「中」风险 ⇒ 仅按钮/胶囊级小面，
 *    大面板禁止做跟随折射，见 04-B9）。
 *  - 按压回弹：pointerdown/up 挂 .liquid-active（scale(.96) + 过冲曲线）。
 *  - 涟漪：click 时在指针位置插入 .glass-ripple-dot，animationend 立即移除，
 *    避免列表连点堆积（04-A 涟漪条目）。
 *
 * 三闸联动（04-B10）：prefers-reduced-motion 下不生成涟漪、不加 .liquid-active；
 * prefers-reduced-transparency 下不更新光斑变量。
 * 同样：G-0 只提供基元、不改任何组件 DOM，G-1 起才有调用点。
 */

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'
const REDUCED_TRANSPARENCY = '(prefers-reduced-transparency: reduce)'

export type GlassQuality = 'full' | 'lite' | 'flat'
export const GLASS_QUALITY_KEY = 'framemark.ui.glassQuality'

export const prefersReducedMotion = (): boolean => window.matchMedia(REDUCED_MOTION).matches
export const prefersReducedTransparency = (): boolean => window.matchMedia(REDUCED_TRANSPARENCY).matches

/** 把指针位置换算成元素本地百分比，写入 --mx/--my（04-A 光斑跟随） */
export function setGlassSpot(target: HTMLElement, clientX: number, clientY: number): void {
  const rect = target.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return
  const x = ((clientX - rect.left) / rect.width) * 100
  const y = ((clientY - rect.top) / rect.height) * 100
  target.style.setProperty('--mx', `${x.toFixed(2)}%`)
  target.style.setProperty('--my', `${y.toFixed(2)}%`)
}

/** 点击涟漪：直径取元素长边 ×2，中心对齐指针 */
export function spawnGlassRipple(target: HTMLElement, clientX?: number, clientY?: number): void {
  if (prefersReducedMotion()) return
  const rect = target.getBoundingClientRect()
  const size = Math.max(rect.width, rect.height) * 2
  const dot = document.createElement('span')
  dot.className = 'glass-ripple-dot'
  dot.setAttribute('aria-hidden', 'true')
  dot.style.width = `${size}px`
  dot.style.height = `${size}px`
  dot.style.left = `${(clientX === undefined ? rect.width / 2 : clientX - rect.left) - size / 2}px`
  dot.style.top = `${(clientY === undefined ? rect.height / 2 : clientY - rect.top) - size / 2}px`
  dot.addEventListener('animationend', () => dot.remove(), { once: true })
  target.appendChild(dot)
}

/** 玻璃质量三档（04 §C）：写 <html data-glass-quality>，CSS 侧降级 blur */
export function applyGlassQuality(
  quality: GlassQuality,
  root: HTMLElement = document.documentElement
): void {
  root.dataset.glassQuality = quality
  try {
    window.localStorage.setItem(GLASS_QUALITY_KEY, quality)
  } catch {
    /* 忽略存储不可用 */
  }
}

export function readGlassQuality(root: HTMLElement = document.documentElement): GlassQuality {
  try {
    const stored = window.localStorage.getItem(GLASS_QUALITY_KEY)
    if (stored === 'full' || stored === 'lite' || stored === 'flat') return stored
  } catch {
    /* fallthrough */
  }
  return prefersReducedTransparency() ? 'flat' : 'full'
}

/**
 * 事件委托式挂载：给 root 内所有 .glass-spot / .glass-ripple / .glass-press 加交互。
 * pointermove 用 rAF 合帧，杜绝每帧写 CSS 变量导致的重复光栅化。
 * 返回卸载函数（组件卸载时必须调用，否则监听器泄漏）。
 */
export function attachGlassMotion(root: HTMLElement | Document = document): () => void {
  const scope: HTMLElement | Document = root
  let pending: { el: HTMLElement; x: number; y: number } | null = null
  let frame = 0

  const flush = () => {
    frame = 0
    if (!pending) return
    const { el, x, y } = pending
    pending = null
    if (!prefersReducedTransparency()) setGlassSpot(el, x, y)
  }

  const closestFrom = (target: EventTarget | null, selector: string): HTMLElement | null => {
    if (!(target instanceof Element)) return null
    const inRoot = root instanceof HTMLElement && !root.contains(target)
    return inRoot ? null : target.closest(selector) as HTMLElement | null
  }

  const onMove = (e: PointerEvent) => {
    const el = closestFrom(e.target, '.glass-spot')
    if (!el) return
    pending = { el, x: e.clientX, y: e.clientY }
    if (!frame) frame = window.requestAnimationFrame(flush)
  }
  const onDown = (e: PointerEvent) => {
    if (prefersReducedMotion()) return
    const el = closestFrom(e.target, '.glass-press, .glass-ripple')
    if (el) el.classList.add('liquid-active')
  }
  const onUp = () => {
    scope.querySelectorAll('.liquid-active').forEach((el) => el.classList.remove('liquid-active'))
  }
  const onClick = (e: MouseEvent) => {
    const el = closestFrom(e.target, '.glass-ripple')
    if (el) spawnGlassRipple(el, e.clientX, e.clientY)
  }

  scope.addEventListener('pointermove', onMove as EventListener)
  scope.addEventListener('pointerdown', onDown as EventListener)
  scope.addEventListener('pointerup', onUp as EventListener)
  scope.addEventListener('pointercancel', onUp as EventListener)
  scope.addEventListener('click', onClick as EventListener)

  return () => {
    if (frame) window.cancelAnimationFrame(frame)
    frame = 0
    pending = null
    scope.removeEventListener('pointermove', onMove as EventListener)
    scope.removeEventListener('pointerdown', onDown as EventListener)
    scope.removeEventListener('pointerup', onUp as EventListener)
    scope.removeEventListener('pointercancel', onUp as EventListener)
    scope.removeEventListener('click', onClick as EventListener)
  }
}
