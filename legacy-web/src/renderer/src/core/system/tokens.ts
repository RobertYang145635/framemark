import type { StockEnv } from './ir'
import type { ExifData } from '../../types'
import { formatAperture, formatDateOnly, formatShutter, modelWithoutMake } from '../exif'
import type { StockToken } from './ir'

/**
 * U2 · 机型 override key. Identical convention to the U1 size multiplier
 * (`paint.ts/modelScaleOf`): `design.text.lineText[<styleId>#model]`, where
 * `<styleId>` is the value `params.systemStyle` selects. Hand cards keep their
 * own `<source>#<occurrence>` keys in the same map; stock style ids are
 * namespaced (`vendor:style`), so the two key spaces cannot collide.
 */
export function stockModelKey(styleId: string): string {
  return `${styleId}#model`
}

/** The style the stock env renders ('' when the design is not a stock card). */
function stockStyleId(env: StockEnv): string {
  const id = env.params?.systemStyle
  return typeof id === 'string' ? id : ''
}

/**
 * The user's own 机型 text, if they ever typed one:
 *   - key absent  ⇒ `undefined` ⇒ the line is derived from EXIF;
 *   - key present ⇒ that string wins over EXIF verbatim, and `''` means
 *     "deliberately blank" — the line disappears and EXIF must NOT come back.
 * The distinction is the whole point: `undefined` and `''` paint differently
 * (derived vs empty), so the key is probed, never defaulted.
 */
export function stockModelOverride(env: StockEnv): string | undefined {
  const styleId = stockStyleId(env)
  const text = env.text?.lineText
  if (!styleId || !text) return undefined
  const key = stockModelKey(styleId)
  if (!(key in text)) return undefined
  const raw = text[key]
  return typeof raw === 'string' ? raw : undefined
}

/**
 * What the 机型 line shows when no override is set: EXIF make + model, with the
 * make prefix stripped from the model when the camera repeats it. Empty when
 * the photo carries neither — there is deliberately NO title fallback any more
 * (U2), because a title is not a model: an EXIF-less photo must leave the line
 * blank and let the user type one instead.
 */
export function derivedModelText(exif: Pick<ExifData, 'make' | 'model'>): string {
  const make = String(exif.make ?? '').trim()
  const model = modelWithoutMake(make, exif.model ?? '').trim()
  return [make, model].filter(Boolean).join(' ')
}

function stockModel(env: StockEnv): string {
  if (!env.text.showModel) return ''
  const override = stockModelOverride(env)
  if (override !== undefined) return override.trim() ? override : ''
  return derivedModelText(env.exif)
}

function stockImageParams(env: StockEnv): string {
  if (!env.text.showExif) return ''
  const parts: string[] = []
  if (env.exif.focal) parts.push(`${Math.round(env.exif.focal)}mm`)
  if (env.exif.aperture) parts.push(`f/${formatAperture(env.exif.aperture).replace(/^F/i, '')}`)
  if (env.exif.shutter) parts.push(formatShutter(env.exif.shutter))
  if (env.exif.iso) parts.push(`ISO${env.exif.iso}`)
  return parts.join('  ')
}

function stockTime(env: StockEnv): string {
  if (!env.text.showDate) return ''
  return formatDateOnly(env.exif.dateTime).replace(/-/g, '.')
}

function shutterPart(value: number | null): string {
  if (!value || !Number.isFinite(value)) return ''
  if (value >= 1) return Number.isInteger(value) ? String(value) : value.toFixed(1)
  return `1/${Math.round(1 / value)}`
}

/**
 * The split path (`@{series}`, `@{versionNumber}`, `@{versionName}`) must agree
 * with `stockModel()`: when the user typed an override, the split is taken from
 * that string, not from EXIF. The invariant is `[make, model].join(' ')` ===
 * what the model token paints — the typed line is split around the EXIF make
 * only when it actually starts with it, otherwise the whole line is the model
 * part (typing a line must never be re-prefixed with a brand the user dropped).
 */
function modelParts(env: StockEnv): { make: string; model: string; number: string; name: string } {
  const override = env.text.showModel ? stockModelOverride(env) : undefined
  let make: string
  let model: string
  if (override !== undefined) {
    const full = override.trim()
    if (!full) return { make: '', model: '', number: '', name: '' }
    const exifMake = env.exif.make.trim()
    const rest = modelWithoutMake(exifMake, full)
    make = rest === full ? '' : exifMake
    model = rest
  } else {
    make = env.exif.make.trim()
    model = modelWithoutMake(make, env.exif.model).trim()
  }
  const m = /^([A-Za-z]*\d+[A-Za-z0-9]*)(?:\s+(.+))?$/.exec(model)
  return {
    make,
    model,
    number: m?.[1] ?? model,
    name: m?.[2]?.trim() ?? ''
  }
}

function templateText(template: string, env: StockEnv): string {
  const parts = modelParts(env)
  const full = [parts.make, parts.model].filter(Boolean).join(' ')
  const date = formatDateOnly(env.exif.dateTime).replace(/-/g, '.')
  const rawTime = String(env.exif.dateTime || '').match(/[ T](\d{2}):(\d{2})/)
  const time = rawTime ? `${rawTime[1]}:${rawTime[2]}` : ''
  const dateObj = new Date(String(env.exif.dateTime || '').replace(' ', 'T'))
  const validDate = !Number.isNaN(dateObj.getTime())
  const pad = (n: number) => String(n).padStart(2, '0')
  const weekday = validDate ? ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][dateObj.getDay()] : ''
  const month = validDate ? pad(dateObj.getMonth() + 1) : date.slice(5, 7)
  const day = validDate ? pad(dateObj.getDate()) : date.slice(8, 10)
  let s = template.trim()
  s = s.replace(/^@(model|exif|time|location|custom|filter)=/, '')
  s = s.replace(/@{series}@{versionNumber}@{versionName}/g, full)
  s = s.replace(/@{series}@{versionNumber}/g, [parts.make, parts.number].filter(Boolean).join(' '))
  s = s.replace(/@{versionNumber}@{versionName}/g, [parts.number, parts.name].filter(Boolean).join(' '))
  s = s.replace(/@{versionName}/g, parts.name)
  s = s.replace(/@{versionNumber}/g, parts.number)
  s = s.replace(/@{series}/g, parts.make)
  s = s.replace(/@{focal}/g, env.exif.focal ? String(Math.round(env.exif.focal)) : '')
  s = s.replace(/@{aperture}/g, env.exif.aperture ? formatAperture(env.exif.aperture).replace(/^F/i, '') : '')
  s = s.replace(/@{speed}/g, shutterPart(env.exif.shutter))
  s = s.replace(/@{iso}/g, env.exif.iso ? String(env.exif.iso) : '')
  s = s.replace(/@{lens}/g, env.exif.lens)
  s = s.replace(/@wm_time_yyyy_MM_dd_HH_mm/g, [date, time].filter(Boolean).join(' '))
  s = s.replace(/@wm_time_yyyy_MM_dd/g, date)
  s = s.replace(/@wm_time_yyyy_MM/g, date.slice(0, 7))
  s = s.replace(/@wm_time_HH_mm/g, time)
  s = s.replace(/@wm_time_week/g, weekday)
  s = s.replace(/@wm_time_MM/g, month)
  s = s.replace(/@wm_time_dd/g, day)
  s = s.replace(/location_address_list|location_address|location_latlng_switch|location_latlng/g, env.text.location?.trim() ?? '')
  s = s.replace(/@locationLimit=[\d.]+/g, '')
  s = s.replace(/@locationMaxLines=\d+/g, '')
  s = s.replace(/@LOC=[A-Za-z]+/g, '')
  s = s.replace(/[;；]\s*[;；]/g, ';').replace(/^[;；\s]+|[;；\s]+$/g, '')
  return s.trim()
}

/**
 * 控件层（`core/controls`，逐套编辑面板）写进 `design.params` 的逐节点文字覆盖。
 * 键名与 `core/controls/index.ts/paramKeyOf` 逐字对齐：`slotText:<IR 节点路径>`。
 * absent / 空串 ⇒ null ⇒ 走原 token 逻辑（默认态逐字节不变）。
 */
function slotTextOverride (env: StockEnv, nodePath: string | undefined): string | null {
  if (!nodePath || !env.params) return null
  const raw = env.params[`slotText:${nodePath}`]
  return typeof raw === 'string' && raw.length > 0 ? raw : null
}

/**
 * 一个 token 在这张照片上该显示什么文字。
 * `nodePath` 是该节点在 IR 里的路径（`b0/bottom0/g2` … 与
 * `scripts/system-camera/build-controls.cjs` 的路径域同一套约定），
 * 由 `layout.ts` 在遍历时算出来并传进来；不传（老调用点）⇒ 行为与今天完全一致。
 */
export function stockTokenText(token: StockToken, env: StockEnv, nodePath?: string): string {
  const override = slotTextOverride(env, nodePath)
  if (override !== null) return override
  switch (token.kind) {
    case 'literal':
      return token.value ?? ''
    case 'model':
      return stockModel(env)
    case 'imageParams':
      return stockImageParams(env)
    case 'time':
      return stockTime(env)
    case 'timeLocation': {
      const t = stockTime(env)
      const l = env.text.location?.trim() ?? ''
      return [t, l].filter(Boolean).join('\n')
    }
    case 'location':
      return env.text.location?.trim() ?? ''
    case 'greeting':
    case 'custom':
      return env.text.customLine.trim()
    case 'filter':
      return token.value ?? ''
    case 'template':
      return templateText(token.value ?? '', env)
    default:
      return ''
  }
}
