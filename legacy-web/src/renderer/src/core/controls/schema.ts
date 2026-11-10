/**
 * FrameMark 系统相机（stock）逐套编辑控件 —— 类型定稿
 *
 * schema: framemark-stock-style-controls/v1
 * 目标落点（本文件当前只在 staging，不进 src/，不参与 tsconfig.web typecheck）：
 *   framemark/src/renderer/src/core/system/controls/schema.ts
 *
 * 设计约束（全部由 `framemark/scripts/system-camera/build-controls.cjs --validate` 机检）：
 *  1. 一套一条记录，`styleId` 与 IR `STOCK_STYLES` 的 id 一一对应（106 条，不多不少；
 *     无 IR 的 blocked 样式与仅变体的 Polaroid 不得出现在记录里）。
 *  2. 每条控件必须有 `consumer`（具名消费函数，见 CONTROL_CONSUMERS）——
 *     "假控件零容忍"：指不到真实/待建具名函数的控件一律拒收。
 *  3. `default` 必须等于当前 IR 行为：键 absent 或等于 default ⇒ 条件分支整体跳过
 *     （slotOff=[]、乘子=1、覆盖=undefined、anchor='vendor'、offset=0），
 *     solve/commit 不进入新代码路径 ⇒ 175 张桌面基线逐字节不变。
 *  4. `targetNode` 必须是该 styleId 真实存在的 IR 节点路径（路径域来自
 *     staging/ir-index.json，由 esbuild 直接加载 styles.ts 生成），否则报越界。
 *  5. `provenance` 逐条标注三态，不许把 inferred 伪装成 vendor：
 *     - 'vendor'   厂商自家配置里就有该行的可编辑/可关标志（小米 config `optional:true`、
 *                  OPPO style JSON `editable:true`、vivo `temp.json` subCard key）
 *     - 'inferred' 厂商未授权，由本项目 IR 启发式（适配器文本正则）或通用能力推出
 *     - 'manual'   手工样式（styles.ts 里被人工覆盖、不走 adapter 重导）的显式声明
 *     每条都带 `evidence`（厂商文件相对路径 + 原始标志文本），可回溯。
 */

/** 与 IR 一致的品牌口径（slot-inventory.json 的 vendor 字段） */
export type StockVendor = 'oppo' | 'xiaomi' | 'vivo'

/** 面板分组：内容 / 版式 / 细节（玻璃原型 L2 的三组披露） */
export type ControlGroup = 'content' | 'layout' | 'detail'

/** 取值来源三态（不许洗白） */
export type ControlProvenance = 'vendor' | 'inferred' | 'manual'

/** 8 种控件 kind —— 每一种必须指向 CONTROL_CONSUMERS 里的一个具名函数 */
export type ControlKind =
  | 'toggle'
  | 'text'
  | 'slider'
  | 'anchorGrid'
  | 'xyOffset'
  | 'themeSwatches'
  | 'brandPicker'
  | 'gravitySegment'

/** 具名消费函数的键（值见 CONTROL_CONSUMERS） */
export type ConsumerKey =
  | 'toggle.slotOff'
  | 'text.slotText'
  | 'slider.slotScale'
  | 'slider.slotAlpha'
  | 'anchorGrid.stockAnchor'
  | 'xyOffset.stockOffset'
  | 'theme.theme'
  | 'brand.brandLogoFile'
  | 'gravity.slotGravity'

export type ConsumerStatus =
  /** 现函数已实现，控件默认值即当前行为，接参即可 */
  | 'existing'
  /** 现函数存在，但需要新增一个 params 覆盖入口（同一 PR 内实现） */
  | 'extend'
  /** 需要新增具名函数；未落地前该控件不得在 Inspector 出现 */
  | 'pending'

export interface ConsumerRef {
  /** core/system 内的文件（不含路径前缀） */
  file: 'layout.ts' | 'paint.ts' | 'tokens.ts' | 'assets.ts'
  /** 函数名：exported 为模块导出，internal 为模块内私有（需在导出层加参数） */
  symbol: string
  visibility: 'exported' | 'internal'
  status: ConsumerStatus
  /** 默认值路径为什么不变（写清楚，验收时对着这句看基线） */
  noOpWhenDefault: string
}

/** CONTROL_CONSUMERS：8 kind ⇉ 9 个具名消费点（已用 grep 核对过 src/renderer/src/core/system） */
export const CONTROL_CONSUMERS = {
  'toggle.slotOff': {
    file: 'layout.ts',
    symbol: 'isSlotOff',
    visibility: 'internal',
    status: 'existing',
    noOpWhenDefault:
      '已落地：layout.ts 的 isSlotOff(env, irPath) 只在 params[`slotOff:<irPath>`] === false 时返回 true；' +
      '键 absent/true ⇒ visiblePairs() 与原 node.children.filter() 逐条同序，条带单子节点分支同样短路 ⇒ solve 结果不变'
  },
  'text.slotText': {
    file: 'tokens.ts',
    symbol: 'stockTokenText',
    visibility: 'exported',
    status: 'existing',
    noOpWhenDefault:
      '已落地：stockTokenText(token, env, nodePath) 先查 env.params[`slotText:${nodePath}`]，' +
      'absent/空串 ⇒ 走原 token 逻辑；layout.ts 遍历时按 ir-index 同约定算出 nodePath 并传入（含 optional 剔除分支，两处同一函数 ⇒ 不会一边显示一边隐藏）'
  },
  'slider.slotScale': {
    file: 'layout.ts',
    symbol: 'textMeasured',
    visibility: 'internal',
    status: 'extend',
    noOpWhenDefault: 'node.size × (slotScale ?? 1)；乘子 1 时与 fontScale 同一 clamp 路径，测量结果逐字节相同（L156）'
  },
  'slider.slotAlpha': {
    file: 'paint.ts',
    symbol: 'paintStockLayout',
    visibility: 'exported',
    status: 'existing',
    noOpWhenDefault:
      '已落地：paintItem 用 slotAlphaMultiplier(env, item.path)（键 slotAlpha:<irPath>）乘在原 alpha 上，' +
      'absent/非法 ⇒ 乘子恰为 1 ⇒ ctx.globalAlpha 与原值浮点全同；layout 侧不受影响（透明度不参与测量）'
  },
  'anchorGrid.stockAnchor': {
    file: 'layout.ts',
    symbol: 'stockAnchorAxes',
    visibility: 'internal',
    status: 'existing',
    noOpWhenDefault: "stockAnchor==='vendor'/absent ⇒ anchorGroup 位移 (0,0)，已实现（L671/L691）"
  },
  'xyOffset.stockOffset': {
    file: 'layout.ts',
    symbol: 'nudgeBand',
    visibility: 'internal',
    status: 'existing',
    noOpWhenDefault: 'stockOffsetXPct/YPct===0 ⇒ nudgeBand 原样返回（L640），与 anchorGroup 同一 clamp'
  },
  'theme.theme': {
    file: 'paint.ts',
    symbol: 'themeAssets',
    visibility: 'internal',
    status: 'existing',
    noOpWhenDefault: 'theme absent ⇒ 用 style.themes 首项/无主题原色（paint.ts L544 + assets.ts themeAssets）'
  },
  'brand.brandLogoFile': {
    file: 'assets.ts',
    symbol: 'chooseBrandSlot',
    visibility: 'exported',
    status: 'existing',
    noOpWhenDefault: 'brandLogoFile absent ⇒ 现有品牌自动匹配（assets.ts L212），仅 isBrandLogo 节点参与（L64 collectBrandNodes）'
  },
  'gravity.slotGravity': {
    file: 'layout.ts',
    symbol: 'gravityParts',
    visibility: 'internal',
    status: 'existing',
    noOpWhenDefault:
      '已落地：layout.ts 的 slotGravityOverride(env, irPath)（键 slotGravity:<irPath>，值 \'vendor\'/absent ⇒ 无覆盖）' +
      '在 gravityParts 之前生效，覆盖点＝行内左右分桶、cross 轴对齐、组内单子节点、条带单子节点四处，全部早于 finishBand/fitBandItems'
  }
} as const satisfies Record<ConsumerKey, ConsumerRef>

/* ---------------- 厂商证据 ---------------- */

export type EvidenceSource =
  /** 小米 NeoHyper config.json 视图 optional:true */
  | 'xiaomi-config-optional'
  /** 小米 meta.json 的 limitation.name_length_limitation（文字长度上限） */
  | 'xiaomi-meta-limitation'
  /** OPPO 相册 watermark_master_styles/*.json 逐元素 editable:true */
  | 'oppo-style-editable'
  /** OPPO 相册 style JSON 的 visible:false 备用元素（厂商自带换行位） */
  | 'oppo-style-hidden'
  /** OPPO 相册 style JSON 的 position.layoutGravity 枚举 */
  | 'oppo-style-gravity'
  /** OPPO CameraPro assets/watermark/*.json editable:true */
  | 'oppo-camerapro-editable'
  /** vivo temp.json subCard[] 偏好组 key */
  | 'vivo-subcard'
  /** vivo temp.json un_support_edit */
  | 'vivo-unsupport-edit'
  /** 本项目 IR 适配器文本正则启发式（非厂商） */
  | 'ir-optional-heuristic'
  /** 由 IR 结构本身推出（overlay/rotation 条带、themes 数量、isBrandLogo 节点） */
  | 'ir-slot-structure'
  /** 值域来自厂商枚举（如 OPPO layoutGravity 10 值），但该行的可改性厂商没授权 */
  | 'vendor-enum-universe'
  /** styles.ts / vivoManual.ts 人工覆盖 */
  | 'manual-override'

export interface Evidence {
  source: EvidenceSource
  /** 厂商文件相对路径（相对 D:/border&watermark） */
  ref: string
  /** 厂商原始标志文本，例如 "@time=@wm_time_yyyy_MM_dd" 或 "editable:true textSource:1" */
  detail: string
  /** 匹配到 IR 节点的判据（tokenValue 全等 / tokenKind 唯一 / 文档顺序对齐） */
  match: 'tokenValue-exact' | 'tokenKind-unique' | 'slotType-unique' | 'order-positional' | 'band-gate' | 'manual'
}

/* ---------------- 控件（按 kind 可辨识联合） ---------------- */

interface ControlBase {
  /** styleId 内稳定唯一；命名规则：<kind>.<slotType>.<nodePath 末段> */
  id: string
  kind: ControlKind
  /** Inspector 显示的中文标签（本项目 UI 语言） */
  label: string
  group: ControlGroup
  /** 硬约束：必须能在 CONTROL_CONSUMERS 里查到 */
  consumer: ConsumerKey
  provenance: ControlProvenance
  evidence: Evidence
  /** true ⇒ 关掉/改动该行会离开"厂商原版"版式（保真风险，进 risks.md 清单） */
  fidelityRisk?: boolean
}

/** 行开关：params.slotOff: string[]（nodePath 列表），默认不含本节点 */
export interface ToggleControl extends ControlBase {
  kind: 'toggle'
  param: 'slotOff'
  /** 绑定的 IR 节点路径（必须存在于 ir-index.json） */
  targetNode: string
  /** 默认 on（节点可见）＝当前行为 */
  default: 'on'
  domain: { on: true; off: true }
}

/** 文字输入：params[`slotText:${targetNode}`]: string */
export interface TextControl extends ControlBase {
  kind: 'text'
  param: 'slotText'
  targetNode: string
  /** null ⇒ 回落 token 原值（当前行为） */
  default: null
  domain: {
    maxLength: number | null
    /** 厂商给的是长度窗口 [min,max]：min>0 时是真下界（如 [17,100]），null ⇒ 无下界约束 */
    minLength: number | null
    /** 长度窗口来源；null ⇒ 厂商未给，按 UI 兜底 64 且 provenance 必须是 inferred */
    maxLengthSource: 'xiaomi-meta-limitation' | 'fallback' | null
    multiline: boolean
  }
}

/** 数值滑杆：slotScale:<nodePath>（字号乘子）/ slotAlpha:<nodePath>（透明度） */
export interface SliderControl extends ControlBase {
  kind: 'slider'
  param: 'slotScale' | 'slotAlpha'
  targetNode: string
  /** slotScale 默认 1；slotAlpha 默认 null（absent＝沿用 IR alpha） */
  default: 1 | null
  domain: { min: number; max: number; step: number; unit: 'multiplier' | 'alpha' }
}

/** 九宫格整体锚点（第 10 档 'vendor' ＝厂商原版） */
export interface AnchorGridControl extends ControlBase {
  kind: 'anchorGrid'
  param: 'stockAnchor'
  /** 绑到条带（band 索引路径，如 'b0'）；ir-index.json 的 movableBands 校验 */
  targetBand: string
  default: 'vendor'
  domain: {
    options: ['vendor', 'leftTop', 'leftCenter', 'leftBottom', 'centerTop', 'center', 'centerBottom', 'rightTop', 'rightCenter', 'rightBottom']
  }
}

/** 锚点之后叠加的水平/垂直微调 */
export interface XyOffsetControl extends ControlBase {
  kind: 'xyOffset'
  param: 'stockOffsetXPct' | 'stockOffsetYPct'
  targetBand: string
  default: 0
  domain: { min: number; max: number; step: number; unit: 'pctOfLongEdge' }
}

/** 主题色组（仅 style.themes 非空的 38 套成立） */
export interface ThemeSwatchesControl extends ControlBase {
  kind: 'themeSwatches'
  param: 'theme'
  /** null ⇒ 无主题/默认主题＝当前行为 */
  default: null
  domain: { options: string[]; themeCount: number }
}

/** 字标选择（仅 37 个 brandSlot 套；chooseBrandSlot 只吃 isBrandLogo 节点） */
export interface BrandPickerControl extends ControlBase {
  kind: 'brandPicker'
  param: 'brandLogoFile'
  targetNode: string
  default: null
  domain: { brandLibrary: 'core/brands(62)' ; autoMatch: true }
}

/** 单行对齐方位枚举（值域必须是 OPPO layoutGravity 全集的子集） */
export interface GravitySegmentControl extends ControlBase {
  kind: 'gravitySegment'
  param: 'slotGravity'
  targetNode: string
  /** null ⇒ 用节点原 gravity＝当前行为 */
  default: null
  domain: { options: string[] }
}

export type StyleControl =
  | ToggleControl
  | TextControl
  | SliderControl
  | AnchorGridControl
  | XyOffsetControl
  | ThemeSwatchesControl
  | BrandPickerControl
  | GravitySegmentControl

/* ---------------- 每套一条记录 ---------------- */

export type RecordSource = 'baseline' | 'manual'

export interface StockStyleControls {
  schemaVersion: 'framemark-stock-style-controls/v1'
  /** 与 IR STOCK_STYLES 的 id 一一对应 */
  styleId: string
  /**
   * 前向兼容位（不参与本轮证据计算）：项目已定案"编辑面可按 variantId 声明"。
   * 缺省 undefined ⇒ 该声明对这套的全部变体生效；给出数组 ⇒ 只对列出的 variantId 生效。
   * 现在的数据全是 styleId 级、无任何变体假设，所以迁移只是加字段，不用重算证据。
   */
  variantScope?: string[]
  vendor: StockVendor
  family: string
  name: string
  placement: 'out' | 'with' | 'in' | 'overlay'
  rotatedBand: boolean
  /** 结构指纹（placement|rot|themes|排序后的槽位类型集），61 种，用于批量审独例 */
  fingerprint: string
  /** 'manual' ⇒ styles.ts/vivoManual.ts 人工覆盖过、不走 adapter 重导，控件必须常驻 */
  source: RecordSource
  /**
   * 面板策略：
   * - 'declared'   按 controls 顺序渲染差异化面板
   * - 'generic'    controls 为空 ⇒ 沿用现通用面板（行为零变化，不编控件）
   */
  panel: 'declared' | 'generic'
  controls: StyleControl[]
  /** panel='generic' 时必填：为什么发不出控件（厂商无授权 + IR 无槽位） */
  noControlsReason?: string
  /**
   * 厂商声明了可编辑元素、但我们绑不上 IR 节点（结构缺口 / 候选不唯一 / 未绘制）。
   * 逐条留痕而不是硬发控件 —— risks.md 的"保真缺口"清单从这里统计。
   */
  unmappedVendor?: { element?: number | null; key?: string; textSource?: string; view?: string; text?: string; why: string }[]
  notes?: string
}

export type ControlsFile = {
  schema: 'framemark-stock-style-controls/v1'
  family: 'xiaomi' | 'oppo' | 'vivo'
  generatedBy: 'scripts/system-camera/build-controls.cjs'
  inputs: string[]
  records: StockStyleControls[]
}

/* ---------------- 生成侧用到的常量 ---------------- */

/** OPPO 相册 style JSON 的 layoutGravity 全集（10 值，厂商实测） */
export const OPPO_LAYOUT_GRAVITY = [
  'leftTop',
  'leftCenter',
  'leftBottom',
  'centerTop',
  'center',
  'centerBottom',
  'rightTop',
  'rightCenter',
  'rightBottom',
  'bottomHorCenter'
] as const

/** 文字输入兜底上限（厂商未给 limitation 时；此时 provenance 必须是 inferred） */
export const TEXT_MAX_LENGTH_FALLBACK = 64

/** vivo subCard pref key ⇉ 控件映射表（Step 3 vivo 侧消费） */
export const VIVO_SUBCARD_BINDING = {
  pref_camera_watermark_time: { kind: 'toggle', slotTypes: ['dateLine'] },
  pref_camera_watermark_location: { kind: 'toggle', slotTypes: ['locationLine', 'dateLine+locationLine'] },
  pref_camera_watermark_param: { kind: 'toggle', slotTypes: ['exifCompositeLine', 'exifMetricLine[focal]', 'exifMetricLine[aperture]', 'exifMetricLine[shutter]', 'exifMetricLine[iso]'] },
  pref_camera_water_mark_logo: { kind: 'toggle', slotTypes: ['modelLine'] },
  brand_icon: { kind: 'toggle', slotTypes: ['houseWordmark', 'cobrandWordmark'] },
  pref_camera_watermark_signature_opacity: { kind: 'slider-alpha', slotTypes: ['customLine', 'literalText'] },
  SCALE_LEVEL: { kind: 'slider-scale', slotTypes: ['*'] },
  pref_camera_watermark_custom_signature_type: { kind: 'text', slotTypes: ['customLine', 'literalText'] },
  pref_camera_watermark_signature_type: { kind: 'toggle', slotTypes: ['customLine', 'literalText'] },
  pref_camera_watermark_user_content: { kind: 'text', slotTypes: ['customLine', 'literalText'] },
  pref_camera_watermark_personal_graphic: { kind: 'none', slotTypes: [] },
  pref_camera_watermark_qrcode: { kind: 'none', slotTypes: [] },
  pref_camera_watermark_live: { kind: 'none', slotTypes: [] },
  support_text: { kind: 'text', slotTypes: ['literalText'] }
} as const

/** 没有 IR 对应物 ⇒ 明确不发控件（qrcode/live/personal_graphic 我们没渲染） */
export const VIVO_NO_CONTROL_KEYS = ['pref_camera_watermark_personal_graphic', 'pref_camera_watermark_qrcode', 'pref_camera_watermark_live'] as const
