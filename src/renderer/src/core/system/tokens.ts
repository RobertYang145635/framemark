import type { StockEnv } from './ir'
import { formatAperture, formatDateOnly, formatShutter, modelWithoutMake } from '../exif'
import type { StockToken } from './ir'

function stockModel(env: StockEnv): string {
  if (!env.text.showModel) return ''
  const make = env.exif.make.trim()
  const model = modelWithoutMake(make, env.exif.model).trim()
  return [make, model].filter(Boolean).join(' ') || env.text.title.trim()
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

function modelParts(env: StockEnv): { make: string; model: string; number: string; name: string } {
  const make = env.exif.make.trim()
  const model = modelWithoutMake(make, env.exif.model).trim()
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

export function stockTokenText(token: StockToken, env: StockEnv): string {
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
