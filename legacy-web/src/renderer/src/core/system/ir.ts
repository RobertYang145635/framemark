import type { BrandVersionRef, ExifData, Params, TextSettings } from '../../types'

export type StockEnv = {
  pw: number
  ph: number
  exif: ExifData
  params: Params
  text: TextSettings
}

/**
 * What a brand slot needs from the render environment: the photo's EXIF
 * make/model, which `core/brands.ts` turns into a brand key, plus the optional
 * Inspector pick (`brandLogoFile`, a logo-registry file name written into
 * `design.params` by `store.ts`). `prepare()` gets this subset (it has no
 * canvas), `paint.ts` gets the full env.
 */
export type StockBrandEnv = Pick<StockEnv, 'exif'> & {
  /** logo registry file the user picked in the Inspector, e.g. `leica_white` */
  brandLogoFile?: string
  /**
   * Additive: the explicit Logo Library version picked for this design. When
   * present it wins over `brandLogoFile`/EXIF and every cut of the version is
   * preloaded, so a theme change can swap light↔dark without a reload.
   */
  brandVersion?: BrandVersionRef | null
}

export type StockVendor = 'oppo' | 'xiaomi' | 'vivo'

export type StockGravity =
  | 'leftTop'
  | 'leftVerticalCenter'
  | 'leftBottom'
  | 'topHorCenter'
  | 'horCenter'
  | 'center'
  | 'verticalCenter'
  | 'rightTop'
  | 'rightVerticalCenter'
  | 'rightBottom'
  | 'centerBottom'

export interface StockMargins {
  left?: number
  top?: number
  right?: number
  bottom?: number
}

export type StockTokenKind =
  | 'model'
  | 'imageParams'
  | 'time'
  | 'timeLocation'
  | 'location'
  | 'greeting'
  | 'custom'
  | 'filter'
  | 'literal'
  | 'template'

export interface StockToken {
  kind: StockTokenKind
  value?: string
  /** vendor-declared per-photo parameter key (OPPO `content.extendParamsType`), e.g.
   *  `master_model_param_filter`. Present means: the element is an input slot for the
   *  controls layer, and the vendor's own `content` string is only a sample value. */
  slot?: string
}

export interface StockTextOutline {
  paddingX?: number
  paddingY?: number
  width?: number
  height?: number
  radius?: number
  strokeWidth: number
  strokeColor?: string
  strokeColorIndex?: number
}

export interface StockTextNode {
  kind: 'text'
  token: StockToken
  font: string
  size: number
  weight: number
  color?: string
  colorIndex?: number
  alpha?: number
  tracking?: number
  lineHeight?: number
  align?: 'left' | 'center' | 'right'
  gravity?: StockGravity
  margins?: StockMargins
  width?: number
  optional?: boolean
  shadow?: { color: string; blur: number; x: number; y: number }
  outline?: StockTextOutline
}

export interface StockImageNode {
  kind: 'image'
  asset: string
  themeAssets?: Record<string, string>
  colorIndex?: number
  width: number
  height: number
  scale: 'contain' | 'cover' | 'fill'
  gravity?: StockGravity
  margins?: StockMargins
  tint?: string
  alpha?: number
  /**
   * This node is a phone-brand *slot*, not fixed art: the vendor fills it at
   * runtime, so `assets.ts` swaps in the photo's own brand wordmark (or a
   * brand picked in the Inspector) and `paint.ts` drops the node when the
   * photo's brand has no usable wordmark. Co-brand marks (Leica roundel,
   * Hasselblad, Zeiss, "Camera powered by RICOH GR") must stay `false`.
   */
  isBrandLogo?: boolean
}

export interface StockShapeNode {
  kind: 'shape'
  shape: 'circle' | 'rect' | 'line'
  width: number
  height: number
  color: string
  gravity?: StockGravity
  margins?: StockMargins
  alpha?: number
  radius?: number
  /**
   * Additive vector node: key into the generated VectorDrawable art
   * (`assets/system/vivo/vectorArt.generated.ts`, produced by
   * `scripts/system-camera/adapters/vivo-vector.cjs` from the vendor APKs).
   * When set, `paint.ts` fills/strokes the vendor's own path data inside this
   * node's box instead of the primitive — the box keeps normal layout/gravity
   * behaviour. Nodes without it render byte-identically to before.
   */
  vectorKey?: string
}

export interface StockSpaceNode {
  kind: 'space'
  weight?: number
  width?: number
  height?: number
}

export interface StockGroupNode {
  kind: 'group'
  orientation: 'row' | 'column'
  width?: number
  height?: number
  matchWidth?: boolean
  matchHeight?: boolean
  gravity?: StockGravity
  margins?: StockMargins
  gap?: number
  background?: string
  children: StockNode[]
}

export type StockNode =
  | StockTextNode
  | StockImageNode
  | StockShapeNode
  | StockSpaceNode
  | StockGroupNode

export interface StockBand {
  direction: 'top' | 'bottom' | 'left' | 'right' | 'overlay'
  /**
   * Degrees. Whole-band rotation painted about the band box centre, matching
   * the vendor's band-level `bitmaps[i].rotation` (OPPO ships only 90: a
   * full-length 50dp strip laid out horizontally, then turned onto its side —
   * text and child images rotate with it). Undefined/0 leaves a band untouched;
   * for ±90 the children are laid out in the axis-swapped box sharing the same
   * centre, so the vendor's aspect-preserving fit rules still apply.
   */
  rotation?: number
  thickness?: number
  width?: number
  radius?: number
  offset?: { x?: number; y?: number }
  gravity?: StockGravity
  background?: string
  drawFirst?: boolean
  children: StockNode[]
}

export interface StockTheme {
  id: string
  label: string
  swatch: string
  background: string
  foreground: string[]
}

export interface StockAsset {
  file: string
  width: number
  height: number
}

export interface StockStyle {
  id: string
  vendor: StockVendor
  family: string
  name: string
  nameEn: string
  placement: 'out' | 'in' | 'with' | 'overlay'
  referenceWidth: number
  /**
   * What `referenceWidth` measures. Gallery/Xiaomi configs declare a photo
   * width; the CameraPro pack declares `baseImageSize` as the photo SHORT edge
   * (360), so a 4:3 landscape lays out on 480 units, not 360. The extra unit
   * width is derived from the render aspect in `stockReferenceWidth()`.
   */
  referenceWidthMode?: 'width' | 'shortEdge'
  margins: { left: number; top: number; right: number; bottom: number }
  background: string
  transparentBackground?: boolean
  themes?: StockTheme[]
  defaultTheme?: string
  bands: StockBand[]
  assets: Record<string, StockAsset>
  audit: {
    status: 'verified' | 'approx' | 'blocked'
    notes?: string
    unsupported?: string[]
  }
}
