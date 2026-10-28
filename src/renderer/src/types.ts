export type TemplateId =
  | 'minimal'
  | 'gallery'
  | 'sideCard'
  | 'specFooter'
  | 'splitReveal'
  | 'sideTicket'
  | 'portraitTicket'
  | 'poetryCard'
  | 'locationCard'
  | 'paletteCard'
  | 'polaroidFrame'
  | 'xiaomiStandard'
  | 'brandFooter'
  | 'gradFooter'
  | 'personalBorder'
  | 'vendorFooter'
  | 'xiaomiLeica'
  | 'paramStack'
  | 'matteOverlay'
  | 'logoFooter'
  | 'matteFooter'
  | 'profileFrame'
  | 'overlay'
  | 'bareCover'
  | 'vertical'
  | 'blur'
  | 'palette'
  | 'film'
  | 'polaroid'
  | 'editorial'
  | 'glass'
  | 'glassSideCard'
  | 'glassPalettePortrait'
  | 'ticket'
  | 'movie'
  | 'ratio'
  | 'ratioMatte'
  | 'ratioBlurSquare'
  | 'systemCamera'

export type Ratio = 'original' | '1:1' | '4:5' | '16:9' | '3:2'
export type Anchor =
  | 'top-left' | 'top-center' | 'top-right'
  | 'middle-left' | 'center' | 'middle-right'
  | 'bottom-left' | 'bottom-center' | 'bottom-right'

export interface ExifData {
  make: string
  model: string
  lens: string
  focal: number | null
  /** True when `focal` came from the 35mm-equivalent tag (0xA405). */
  focalEquivalent?: boolean
  aperture: number | null
  shutter: number | null
  iso: number | null
  dateTime: string
  width: number
  height: number
  hasAny: boolean
  raw?: Record<string, unknown>
}

export type PhotoSource =
  | { kind: 'path'; path: string }
  | { kind: 'file'; file: File }
  | { kind: 'sample' }

export interface PhotoAsset {
  id: string
  name: string
  source: PhotoSource
  previewUrl: string
  previewW: number
  previewH: number
  width: number
  height: number
  exif: ExifData
  bytes?: number
}

export interface ImageAsset {
  id: string
  name: string
  dataUrl: string
  width: number
  height: number
}

export interface WatermarkSettings {
  enabled: boolean
  asset: ImageAsset | null
  anchor: Anchor
  offsetX: number
  offsetY: number
  widthPct: number
  opacity: number
  rotation: number
  tile: boolean
  gapPct: number
  target: 'photo' | 'canvas'
}

export interface TextSettings {
  title: string
  subtitle: string
  signature: string
  customLine: string
  location?: string
  showMake: boolean
  showModel: boolean
  showLens: boolean
  showExif: boolean
  showDate: boolean
  showLogo: boolean
  fontScale: number
  /** User-hidden text tokens (v4 text-by-position editor). */
  hidden?: TextToken[]
  /** Per-line text overrides, keyed by `<source>#<occurrence>` from the render probe. */
  lineText?: Record<string, string>
  /** Per-line hidden state, same keys as `lineText`. */
  hiddenLines?: string[]
  /** Declared-slot content overrides (footer rows typed without EXIF). */
  slotValues?: Record<string, string>
  /** Per-declared-slot font multiplier (0.6–1.6), key matches `slotValues`. */
  slotSizes?: Record<string, number>
  /** Per-line font multiplier for captured lines, same keys as `lineText`. */
  lineSizes?: Record<string, number>
}

export type TextToken = 'title' | 'subtitle' | 'signature' | 'customLine' | 'location'

export type ParamValue = string | number | boolean
export type Params = Record<string, ParamValue>
/**
 * Sparse per-style parameter overrides. A key is present ONLY when the user
 * changed it away from what the variant declares, so an untouched bucket is
 * absent and rendering falls through to the template/variant defaults —
 * which is what keeps the 175-card byte baseline identical to pre-patch.
 * Keyed by the style identity (`variantId`, falling back to `templateId`).
 */
export type ParamBucket = Record<string, ParamValue>
export type ParamOverrides = Record<string, ParamBucket>

export interface ExportSettings {
  format: 'jpeg' | 'png' | 'webp' | 'tiff'
  quality: number
  longEdge: number
  removeGps: boolean
  filenamePattern: string
  outputDir: string
}

/** Which photos the Phase 4 export dialog will write. */
export type ExportScope = 'current' | 'all'

/**
 * Pointer at a specific Logo Library version/cut picked for a design. Additive
 * metadata for the logo overhaul: it is absent on every design/preset written by
 * older builds, and readers must treat `undefined` as "no version selected"
 * (fall back to the legacy `logo` asset). `defaultDesign()` and
 * `makeDesignFromVariant()` never emit it.
 */
export interface BrandVersionRef {
  brandKey: string
  versionId: string
  cutId: string
  /** Catalog asset key the bitmap was loaded from (`ver:<brand>:<cut>` or a legacy file stem). */
  assetKey: string
  /** Kept for compatibility with the Phase 0 shape; always equal to `assetKey`. */
  file: string
  ink: 'dark' | 'light' | 'color' | 'ambiguous'
  surface?: 'light' | 'dark' | 'both' | 'none'
  name?: string
}

export interface Design {
  templateId: TemplateId
  variantId?: string
  ratio: Ratio
  params: Params
  /**
   * Per-style user overrides layered on top of `params`. Optional and sparse:
   * absent/empty means "nothing was overridden", in which case `params` is
   * byte-for-byte what it was before bucketing existed.
   */
  paramOverrides?: ParamOverrides
  text: TextSettings
  watermark: WatermarkSettings
  logo: ImageAsset | null
  avatar?: ImageAsset | null
  /**
   * Additive metadata: which Logo Library version the user picked. Optional and
   * absent on old designs/presets — `defaultDesign()` and
   * `makeDesignFromVariant()` must not emit it, so their output stays unchanged.
   * A reference only: `logo` keeps carrying the pixel data.
   */
  brandVersion?: BrandVersionRef | null
  export: ExportSettings
}

export interface Preset {
  id: string
  name: string
  design: Design
  createdAt: number
  updatedAt: number
  builtin?: boolean
}

export interface TemplateVariant {
  id: string
  name: string
  en: string
  category: string
  base: TemplateId
  description?: string
  params?: Partial<Params>
  text?: Partial<TextSettings>
  preview?: string
  renderedPreview?: string
  badge?: string
  themes?: { id: string; label: string; swatch: string }[]
  defaultTheme?: string
  vendorLogo?: string
  autoLogo?: boolean
  vendor?: 'oppo' | 'xiaomi' | 'vivo'
  family?: string
  fidelity?: 'verified' | 'approx' | 'blocked'
}

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

/**
 * One painted text line of a hand template, captured during a real render.
 * The v4 文字 pane lists these by canvas position instead of abstract fields.
 * `source` says what feeds the line: an editable token, one EXIF flag, or a
 * composite "photo" row that is display-only.
 */
export interface TextSlot {
  id: string
  /** Stable line key `<source>#<occurrence>` used by lineText/hiddenLines. */
  key: string
  anchor: Anchor
  text: string
  source: TextToken | 'make' | 'model' | 'lens' | 'exif' | 'date' | 'photo'
  hidden: boolean
  x: number
  y: number
  w: number
  h: number
}

export interface CropInfo {
  sx: number
  sy: number
  sw: number
  sh: number
  pw: number
  ph: number
}

export interface TemplateRenderEnv {
  ctx: CanvasRenderingContext2D
  W: number
  H: number
  pw: number
  ph: number
  photo: CanvasImageSource
  crop: CropInfo
  exif: ExifData
  params: Params
  text: TextSettings
  design: Design
  images: { logo?: HTMLImageElement; [key: string]: HTMLImageElement | undefined }
  isPreview: boolean
}

export type TemplatePrepareEnv = Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'> & {
  /**
   * Additive: the Logo Library version the user picked. Prepare has no `design`,
   * so this is the only way `systemCamera.prepare` can preload the version's
   * cuts for the stock brand slots.
   */
  brandVersion?: BrandVersionRef | null
}

export interface TemplateLayout {
  W: number
  H: number
}

export interface TemplateDef {
  id: TemplateId
  name: string
  en: string
  category: string
  description: string
  defaults: Params
  themes?: { id: string; label: string; swatch: string }[]
  controls?: ('margin' | 'radius' | 'shadow' | 'footer' | 'blur' | 'grain' | 'sprocket')[]
  prepare?: (env: TemplatePrepareEnv) => Promise<{ images?: Record<string, HTMLImageElement> }>
  layout: (env: Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>) => TemplateLayout
  render: (env: TemplateRenderEnv) => Rect
}

export type ExportJob = {
  running: boolean
  total: number
  done: number
  current: string
  cancelled: boolean
  errors: { name: string; message: string }[]
  lastPath?: string
  /** S3: the persisted export-history task this run belongs to (retry / 新建任务). */
  taskId?: string
  /** S3: photos skipped because the same photo was already exported before. */
  skipped?: number
}

export type InspectorTab = 'template' | 'watermark' | 'export'

/**
 * Phase 3 editor shell: the Inspector has two top-level tools. `border` shows
 * the four per-style editor tabs, `watermark` shows the uploaded PNG layer.
 * `InspectorTab` above is kept only for compatibility (presets/legacy callers);
 * the Inspector itself no longer reads it.
 */
export type InspectorTool = 'border' | 'watermark'

/** Editor tabs inside the `border` tool, in display order. */
export type EditorTab = 'logo' | 'text' | 'layout' | 'theme'
