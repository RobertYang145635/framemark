/**
 * GLASS-UI G-0 · 亮/暗主题机制（纯浏览器 API，不引第三方主题库）
 *
 * 对应 01 §6 的做法：
 *  1) 首帧前把 localStorage['framemark.ui.theme']（auto|light|dark）解析成
 *     <html data-color-mode="light|dark">，防闪白（原型 design/index.html L9-18）。
 *  2) 运行期只有 auto 才跟随 prefers-color-scheme 变化（原型 L2159-2161）。
 *  3) styles.css 只认 html[data-color-mode="light"]；属性缺省＝暗（现网行为）。
 *
 * G-0 只提供机制、不挂载：本文件不被任何组件引用（App.tsx / index.html 属禁改范围），
 * G-1 起在 App.tsx 之外（index.html 内联脚本 + React 侧 watch）接上调用点。
 */

export type ThemePreference = 'auto' | 'light' | 'dark'
export type ColorMode = 'light' | 'dark'

/** 与 01 §6 建议的键名一致；旧键不存在，首次读取即写入默认值 */
export const THEME_STORAGE_KEY = 'framemark.ui.theme'

const DARK_QUERY = '(prefers-color-scheme: dark)'

const isPreference = (value: string | null): value is ThemePreference =>
  value === 'auto' || value === 'light' || value === 'dark'

export function readThemePreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
    return isPreference(stored) ? stored : 'auto'
  } catch {
    return 'auto'
  }
}

/** nativeTheme.shouldUseDarkColors 在 Electron 渲染层等价于 prefers-color-scheme */
export function systemColorMode(): ColorMode {
  return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light'
}

export function resolveColorMode(preference: ThemePreference = readThemePreference()): ColorMode {
  return preference === 'auto' ? systemColorMode() : preference
}

/**
 * 写 <html data-color-mode>。返回最终生效的模式。
 * @param preference 省略时读存储的用户偏好
 * @param root 目标根元素，默认 document.documentElement
 */
export function applyColorMode(
  preference: ThemePreference = readThemePreference(),
  root: HTMLElement = document.documentElement
): ColorMode {
  const mode = resolveColorMode(preference)
  root.dataset.colorMode = mode
  root.style.colorScheme = mode
  return mode
}

export function setThemePreference(
  preference: ThemePreference,
  root: HTMLElement = document.documentElement
): ColorMode {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, preference)
  } catch {
    /* 存储不可用（隐私模式）时仍切换本次会话 */
  }
  return applyColorMode(preference, root)
}

/**
 * 只有 auto 才响应系统切换（用户手工选 light/dark 后不再被系统牵着走）。
 * 返回解除监听的函数。
 */
export function watchSystemColorScheme(
  onChange: (mode: ColorMode) => void,
  root: HTMLElement = document.documentElement
): () => void {
  const mq = window.matchMedia(DARK_QUERY)
  const handler = () => {
    if (readThemePreference() !== 'auto') return
    onChange(applyColorMode('auto', root))
  }
  mq.addEventListener('change', handler)
  return () => mq.removeEventListener('change', handler)
}
