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
  | 'personalBorder'
  | 'vendorFooter'
  | 'xiaomiLeica'
  | 'paramStack'
  | 'matteOverlay'
  | 'logoFooter'
  | 'matteFooter'
  | 'profileFrame'
  | 'overlay'
  | 'vertical'
  | 'blur'
  | 'palette'
  | 'film'
  | 'polaroid'
  | 'editorial'
  | 'glass'
  | 'ticket'
  | 'movie'
  | 'ratio'
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
}

export type ParamValue = string | number | boolean
export type Params = Record<string, ParamValue>

export interface ExportSettings {
  format: 'jpeg' | 'png' | 'webp' | 'tiff'
  quality: number
  longEdge: number
  removeGps: boolean
  filenamePattern: string
  outputDir: string
}

export interface Design {
  templateId: TemplateId
  variantId?: string
  ratio: Ratio
  params: Params
  text: TextSettings
  watermark: WatermarkSettings
  logo: ImageAsset | null
  avatar?: ImageAsset | null
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

export type TemplatePrepareEnv = Omit<TemplateRenderEnv, 'ctx' | 'W' | 'H' | 'photo' | 'design' | 'isPreview'>

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
}

export type InspectorTab = 'template' | 'watermark' | 'export'
