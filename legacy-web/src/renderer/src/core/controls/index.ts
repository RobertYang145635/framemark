/**
 * 逐套（per-style）编辑控件的运行时入口 —— 纯数据 + 纯函数，不含 JSX、不含硬编码控件。
 *
 * 数据来源（不在这里重新发明 schema）：
 *   `_research/controls-impl/staging/controls.{oppo,xiaomi,vivo}.json`
 *   由 `framemark/scripts/system-camera/build-controls.cjs --validate` 机检后原样 vendoring 到
 *   `./data/`；类型契约见 `./schema.ts`（同一份文件，106 条记录 / 439 个控件）。
 *
 * 键解析：一条记录绑的是 IR `styleId`（如 `oppo-gallery:hassel_style_4`）。
 * 卡片（variantId）侧的既有事实是 `variant.params.systemStyle === styleId`，所以
 *   design -> styleId 走 `params.systemStyle`（权威，不靠字符串猜），
 *   design -> 参数桶 走 `presets.paramBucketKey`（variantId 优先，回落 templateId）。
 * `record.variantScope` 是数据层留的前向兼容位：给出数组时只对列出的 variantId 生效。
 */
import type { Design, ParamValue, Params } from '../../types'
import type {
  ConsumerStatus,
  StockStyleControls,
  StyleControl
} from './schema'
import { CONTROL_CONSUMERS } from './schema'
import oppoFile from './data/controls.oppo.json'
import xiaomiFile from './data/controls.xiaomi.json'
import vivoFile from './data/controls.vivo.json'

const RECORDS: StockStyleControls[] = (
  [oppoFile, xiaomiFile, vivoFile] as { records: unknown }[]
).flatMap((file) => file.records as unknown as StockStyleControls[])

const BY_STYLE_ID = new Map<string, StockStyleControls>()
for (const record of RECORDS) {
  if (!BY_STYLE_ID.has(record.styleId)) BY_STYLE_ID.set(record.styleId, record)
}

export const CONTROL_RECORD_COUNT = BY_STYLE_ID.size
export const CONTROL_ITEM_COUNT = RECORDS.reduce((n, r) => n + r.controls.length, 0)

/** 一条 IR 样式专属的控件声明；找不到返回 null（blocked 样式 / 无 IR 的纯变体）。 */
export function loadControls (styleId: string | null | undefined): StockStyleControls | null {
  if (!styleId) return null
  return BY_STYLE_ID.get(styleId) ?? null
}

/** 当前 design 指向哪套 IR 样式（systemCamera 卡片把 styleId 存在 params.systemStyle 上）。 */
export function styleIdForDesign (design: Pick<Design, 'params'>): string {
  const raw = design.params.systemStyle
  return typeof raw === 'string' ? raw : ''
}

/** 该控件读写的是 `params` 里的哪个键（与 core/system 消费方的约定逐字对齐）。 */
export function paramKeyOf (control: StyleControl): string {
  switch (control.kind) {
    case 'text':
      return `slotText:${control.targetNode}`
    case 'slider':
      return `${control.param}:${control.targetNode}`
    case 'gravitySegment':
      return `slotGravity:${control.targetNode}`
    case 'toggle':
      // params.slotOff 是 nodePath 列表；桶只装标量，所以按“槽位开关”存成布尔键，
      // 由核心侧 isSlotOff 落地时统一收口（见 core/system/layout.ts 的 pending 约定）。
      return `slotOff:${control.targetNode}`
    default:
      return control.param
  }
}

/** 控件“没被改过”时该显示的值（＝厂商原版＝当前渲染行为）。 */
function defaultOf (control: StyleControl): ParamValue | null {
  if (control.kind === 'toggle') return true
  if (control.kind === 'anchorGrid') return 'vendor'
  if (control.kind === 'xyOffset') return 0
  return control.default as ParamValue | null
}

/**
 * 控件当前的值（absent ⇒ 回落到 schema 声明的 default，即“厂商原版”）。
 * 显式复位哨兵也按“没改过”处理，这样面板不会把复位后的卡显示成已改：
 *  - gravitySegment 写 'vendor'（layout.ts/slotGravityOverride 认它＝无覆盖）
 *  - text 写空串（tokens.ts 认空串＝走原 token 逻辑）
 *  - slider(slotAlpha) 写 null（paint.ts 认它＝乘子 1）
 */
export function valueOf (params: Params, control: StyleControl): ParamValue | null {
  const raw = params[paramKeyOf(control)]
  const fallback = defaultOf(control)
  if (raw === undefined || raw === null) return fallback
  if (control.kind === 'gravitySegment' && raw === 'vendor') return fallback
  if (control.kind === 'text' && raw === '') return fallback
  return raw
}

export interface ControlView {
  control: StyleControl
  /** 具名消费点的落地状态：existing 才能真的改画面 */
  status: ConsumerStatus
  /** core/system 现函数已读这个键 ⇒ 控件是真控件 */
  live: boolean
  paramKey: string
  value: ParamValue | null
  /** 当前值 == schema 声明的默认（＝厂商原版） */
  isDefault: boolean
  /** 为什么非 live 控件不可操作（面板上原样显示，不做哑控件） */
  blockedReason: string | null
}

/**
 * 接线后"消费点存在、键也读了，但画面上看不出差别"的逐控件例外（假控件零容忍 ⇒ 保持不可操作）。
 * 判据来自 `_research/controls-live/tools/layout-diff.json`：四档 EXIF × 十档方位，solve 输出逐字节相同。
 * key = `${styleId}|${controlId}`，value = 面板上原样显示的原因。
 */
const WIRED_BUT_NO_ROOM: Record<string, string> = {
  'oppo-camerapro:liquid_glass_style_1|gravitySegment.exifCompositeLine.g0':
    '消费点已接（core/system/layout.ts::slotGravityOverride），但这条行的父组盒子尺寸恰等于它自己 ⇒ 十档方位解出的坐标完全相同，画面不会变 ⇒ 保持不可操作',
  'oppo-gallery:text_style_1|gravitySegment.modelLine.g0':
    '消费点已接（core/system/layout.ts::slotGravityOverride），但该条带在这一档里横纵向都没有余量 ⇒ 十档方位解出的坐标完全相同（_research/controls-live/tools/gravity-probe.json 逐档位移=0），保持不可操作'
}

/**
 * `existing` ⇒ 消费点已在 `core/system` 落地，控件真的会改画面。
 * 还剩 `slider.slotScale`（字号乘子）是 `extend`/`pending`：数据里目前没有这种控件，
 * 一旦出现就必须先接消费点。按 schema 的硬规矩“未落地前该控件不得在 Inspector 出现（可操作）”，
 * 这些控件渲染成 disabled + 写明缺哪一层，绝不做成“能拖但画面不动”的假控件。
 */
function blockedReason (control: StyleControl, status: ConsumerStatus): string | null {
  if (status === 'existing') return null
  const ref = CONTROL_CONSUMERS[control.consumer]
  return status === 'pending'
    ? `核心渲染层还缺少具名函数 ${ref.file}::${ref.symbol}，接线前不可操作`
    : `${ref.file}::${ref.symbol} 需新增 ${paramKeyOf(control)} 读取入口（同一 PR 内实现），当前不可操作`
}

export function inspectControl (params: Params, control: StyleControl, styleId?: string): ControlView {
  const ref = CONTROL_CONSUMERS[control.consumer]
  const status: ConsumerStatus = ref.status
  const noRoom = styleId ? WIRED_BUT_NO_ROOM[`${styleId}|${control.id}`] ?? null : null
  const live = status === 'existing' && !noRoom
  const value = valueOf(params, control)
  return {
    control,
    status,
    live,
    paramKey: paramKeyOf(control),
    value,
    isDefault: value === defaultOf(control),
    blockedReason: live ? null : (noRoom ?? blockedReason(control, status))
  }
}

/** 面板入口：给 design 就给出该套专属的控件视图；无声明 / generic ⇒ null（沿用现通用面板）。 */
export function controlsFor (design: Design): { record: StockStyleControls; views: ControlView[] } | null {
  const record = loadControls(styleIdForDesign(design))
  if (!record || record.panel !== 'declared') return null
  if (record.variantScope?.length && design.variantId && !record.variantScope.includes(design.variantId)) return null
  return {
    record,
    views: record.controls.map((control) =>
      inspectControl(design.params, { ...control, label: friendlyControlLabel(control.label) }, record.styleId)
    )
  }
}

/* Vendor control labels carry internal slot keys (`编辑「exifCompositeLine」文字`).
   Translate them for the UI so the panel never exposes schema names. */
const SLOT_LABEL: Record<string, string> = {
  exifCompositeLine: '参数行',
  exifMetricLine: '参数行',
  brandLine: '品牌',
  brand: '品牌',
  cobrandWordmark: '品牌',
  modelLine: '机型',
  model: '机型',
  dateLine: '日期',
  lunarLine: '日期',
  locationLine: '地点',
  customLine: '自定义行',
  signatureLine: '签名',
  titleLine: '标题'
}

const METRIC_LABEL: Record<string, string> = {
  focal: '焦距',
  aperture: '光圈',
  shutter: '快门',
  iso: 'ISO'
}

function friendlySlotKey (key: string): string {
  return key
    .replace(/([A-Za-z][A-Za-z0-9]*)(?:\[([^\]]+)\])?/g, (_m, base: string, metrics?: string) => {
      const name = SLOT_LABEL[base] ?? base
      if (!metrics) return name
      const list = metrics.split('+').map((m) => METRIC_LABEL[m] ?? m).join('/')
      return `${name}（${list}）`
    })
    .replace(/\+/g, '、')
}

export function friendlyControlLabel (label: string): string {
  return label.replace(/「([^」]+)」/g, (_m, key: string) => `「${friendlySlotKey(key)}」`)
}

export type { StockStyleControls, StyleControl } from './schema'
export { CONTROL_CONSUMERS, OPPO_LAYOUT_GRAVITY, TEXT_MAX_LENGTH_FALLBACK } from './schema'
