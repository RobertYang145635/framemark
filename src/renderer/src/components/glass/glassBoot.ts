/**
 * GLASS-UI G-1 · 玻璃外壳挂载点
 *
 * G-0 只交付了机制（glassTheme.ts / glassMotion.ts）并故意留空调用点，本文件就是把
 * 它们接到 App 上的那一颗螺丝。做四件事：
 *  1) 首帧前写 <html data-color-mode> + <html data-glass-quality>（防闪白，01 §6.1）。
 *     App.tsx 在本模块被 import 的瞬间调用 paintGlassChromeBeforeFirstFrame()，
 *     此时 React 还没渲染任何节点 ⇒ 等效于原型 design/index.html L9-18 的内联脚本
 *     （index.html 不在本批可改范围，所以放在渲染层入口模块的顶层）。
 *  2) 挂载光斑 / 按压 / 涟漪（attachGlassMotion，事件委托，卸载时解绑）。
 *  3) 只有存过 auto 才跟随 prefers-color-scheme（watchSystemColorScheme，01 §6.2）。
 *  4) 低端 GPU 自动降档（04 §C「自动策略」）：软渲染 / WebGL 不可用 ⇒ flat，
 *     离屏 blur 基准慢 ⇒ lite。用户显式存过档位则完全不动它。
 *
 * reduced-motion 不在这里判定：styles.css 末尾的 @media(prefers-reduced-motion) 总闸
 * 已把全部 transition/animation 归零，glassMotion.ts 每次事件都实时读 matchMedia
 * （不生成涟漪、不加 .liquid-active），所以这里无需第二个状态源。
 */

import {
  applyColorMode,
  readThemePreference,
  watchSystemColorScheme,
  THEME_STORAGE_KEY,
  type ColorMode
} from './glassTheme'
import {
  GLASS_QUALITY_KEY,
  applyGlassQuality,
  attachGlassMotion,
  prefersReducedTransparency,
  type GlassQuality
} from './glassMotion'

/** 软件渲染 / 禁用 GPU 的会话（--disable-gpu、CI、远程桌面） */
const SOFTWARE_RE = /swiftshader|llvmpipe|software|basic render|microsoft basic/i
/** 低端集显：04 §C 把它和软渲染一起列为默认 lite 的候选 */
const LOW_TIER_RE = /intel.*(hd graphics|uhd graphics|iris)/i

const read = (key: string): string | null => {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

/**
 * 读取 styles.css 里的动效令牌（如 --dur-spot）并换算成毫秒，供 JS 定时器使用。
 * 目的：JS 侧的时长与 CSS 侧共用同一个事实源，不在 TS 里写死数字（G-1 纪律 3）。
 * 取不到（变量未定义 / 非 ms 单位）时回落到 fallback，fallback 只用于兜底而非默认档。
 */
export function readCssTokenMs(token: string, fallback: number): number {
  try {
    const raw = getComputedStyle(document.documentElement).getPropertyValue(token).trim()
    if (raw.endsWith('ms')) return Number.parseFloat(raw) || fallback
    if (raw.endsWith('s')) return Math.round(Number.parseFloat(raw) * 1000) || fallback
  } catch {
    /* jsdom / 早期调用：拿不到就用兜底值 */
  }
  return fallback
}

const storedQuality = (): GlassQuality | null => {
  const v = read(GLASS_QUALITY_KEY)
  return v === 'full' || v === 'lite' || v === 'flat' ? v : null
}

/**
 * 主题默认档：01 §6 是 auto|light|dark 三态，本应用把「用户从没存过」的默认档定为 dark，
 * 因为 G-0 在 styles.css 里的约定是「data-color-mode 缺省＝暗＝现网行为」，玻璃方案的第一版
 * 设计基线也是暗档；只有用户显式存 auto 时才跟随 prefers-color-scheme（运行期由
 * watchSystemColorScheme 继续跟随）。
 */
export function bootColorMode(): ColorMode {
  // HyperOS/Miuix skin is light-first; the OPCameraPro reference is a light
  // system surface set. Keep the saved preference inert while this skin is on.
  if (document.documentElement.dataset.skin === 'hyperos') return applyColorMode('light')
  const pref = read(THEME_STORAGE_KEY) ? readThemePreference() : 'dark'
  return applyColorMode(pref)
}

/** 首帧前：主题档 + 画质档（只写属性，不写 localStorage，避免抢在自动探测前面锁定默认值） */
export function paintGlassChromeBeforeFirstFrame(): void {
  bootColorMode()
  const stored = storedQuality()
  document.documentElement.dataset.glassQuality = stored ?? (prefersReducedTransparency() ? 'flat' : 'full')
}

/** 软渲染 / 无 WebGL 判定（04 §C：--disable-gpu 会话默认降档） */
export function isSoftwareRenderer(): boolean {
  try {
    const probe = document.createElement('canvas')
    const gl = (probe.getContext('webgl2') ?? probe.getContext('webgl')) as WebGLRenderingContext | null
    if (!gl) return true
    const info = gl.getExtension('WEBGL_debug_renderer_info')
    const raw = info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL) ?? '') : ''
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    // 读不到名字（隐私屏蔽）时不据此降档，交给 blurBenchmark 的实测值
    if (!raw) return false
    return SOFTWARE_RE.test(raw)
  } catch {
    return true
  }
}

/** 低端集显名字（只作为 lite 的辅助证据，不单独决定降档） */
export function isLowTierGpu(): boolean {
  try {
    const probe = document.createElement('canvas')
    const gl = (probe.getContext('webgl2') ?? probe.getContext('webgl')) as WebGLRenderingContext | null
    if (!gl) return false
    const info = gl.getExtension('WEBGL_debug_renderer_info')
    const raw = info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL) ?? '') : ''
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    return LOW_TIER_RE.test(raw)
  } catch {
    return false
  }
}

/**
 * 离屏 blur 基准（04 §C 自动策略的第一条）：对 256×256 区域做 ctx.filter=blur(24px) 的
 * drawImage ×N 次，取每次毫秒均值。这是 CPU 侧的粗略代理指标（真正的 backdrop 采样在
 * 合成器里），够用来区分「软渲染」和「有 GPU 加速」；精确档位应由主进程
 * app.getGPUInfo() 提供 —— 留 TODO（见下方 autoDetectGlassQuality 注释）。
 */
export async function blurBenchmark(frames = 20): Promise<number> {
  const size = 256
  const src = document.createElement('canvas')
  src.width = size
  src.height = size
  const sctx = src.getContext('2d')
  const dst = document.createElement('canvas')
  dst.width = size
  dst.height = size
  const dctx = dst.getContext('2d')
  if (!sctx || !dctx) return Number.POSITIVE_INFINITY
  // 噪声源图：纯色会让模糊退化成快速路径，测不出成本
  const img = sctx.createImageData(size, size)
  for (let i = 0; i < img.data.length; i += 4) {
    const v = (i * 2654435761) & 0xff
    img.data[i] = v
    img.data[i + 1] = (v >> 1) & 0xff
    img.data[i + 2] = (v >> 2) & 0xff
    img.data[i + 3] = 255
  }
  sctx.putImageData(img, 0, 0)
  dctx.filter = 'blur(24px)'
  let total = 0
  for (let i = 0; i < frames; i++) {
    const t0 = performance.now()
    dctx.drawImage(src, 0, 0)
    dctx.getImageData(0, 0, 1, 1) // 强制这次模糊真正落地，否则可能被推迟到下一帧
    total += performance.now() - t0
  }
  await new Promise<void>((r) => window.requestAnimationFrame(() => r()))
  return total / frames
}

/** lite/flat 的阈值（ms/次，256×256 blur24）：>5ms 判定为没有像样的 GPU 合成 */
const LITE_MS = 5
const FLAT_MS = 16

/**
 * 自动降档：已存过档位（用户或上一次自动写入）就不再插手，保证可复现。
 * TODO(G-5)：档位应由主进程 `app.getGPUInfo('basic')` 的 gpuFeatureStatus 驱动
 * （src/main/** 属本批禁改范围），届时把这里的启发式降级成兜底。
 */
export async function autoDetectGlassQuality(): Promise<GlassQuality> {
  if (storedQuality()) return storedQuality() as GlassQuality
  let quality: GlassQuality = 'full'
  if (prefersReducedTransparency()) quality = 'flat'
  else if (isSoftwareRenderer()) quality = 'flat'
  else {
    const cost = await blurBenchmark()
    if (cost > FLAT_MS) quality = 'flat'
    else if (cost > LITE_MS || isLowTierGpu()) quality = 'lite'
  }
  applyGlassQuality(quality)
  return quality
}

/** React 侧挂载：动效委托 + 系统配色监听 + 首屏后自动档位。返回卸载函数。 */
export function mountGlassChrome(): () => void {
  const detachMotion = attachGlassMotion(document)
  const detachScheme = watchSystemColorScheme(() => {
    /* 模式已由 applyColorMode 写进 data-color-mode，CSS 侧自行过渡 */
  })
  let cancelled = false
  window.requestAnimationFrame(() => {
    window.setTimeout(() => {
      if (!cancelled) void autoDetectGlassQuality()
    }, 0)
  })
  return () => {
    cancelled = true
    detachMotion()
    detachScheme()
  }
}
