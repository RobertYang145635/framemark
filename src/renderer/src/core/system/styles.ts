import type { StockNode, StockStyle } from './ir'
import { OPPO_GALLERY_STYLES } from './generated/oppoGallery'
import { OPPO_CAMERAPRO_STYLES } from './generated/oppoCameraPro'
import { XIAOMI_STYLES } from './generated/xiaomiStyles'
import { VIVO_STYLES } from './vivoManual'

const HASSEL: StockStyle = {
  id: 'oppo-gallery:hassel_style_1',
  vendor: 'oppo',
  family: 'OPPO 相册',
  name: '哈苏 · 页脚',
  nameEn: 'HASSELBLAD FOOTER',
  placement: 'out',
  referenceWidth: 360,
  margins: { left: 0, top: 0, right: 0, bottom: 60 },
  background: '#FFFFFF',
  bands: [
    {
      direction: 'bottom',
      thickness: 60,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 360,
          height: 60,
          gravity: 'center',
          children: [
            {
              kind: 'group',
              orientation: 'column',
              width: 190,
              gravity: 'leftVerticalCenter',
              children: [
                {
                  kind: 'text',
                  token: { kind: 'model' },
                  font: 'sys',
                  size: 13,
                  weight: 700,
                  color: '#000000',
                  tracking: 0.05,
                  lineHeight: 15.24,
                  align: 'left',
                  margins: { left: 24 }
                }
              ]
            },
            { kind: 'space', weight: 1 },
            {
              kind: 'group',
              orientation: 'column',
              width: 148,
              gravity: 'rightVerticalCenter',
              margins: { right: 24 },
              children: [
                {
                  kind: 'image',
                  asset: 'hasselblad_watermark_dark',
                  width: 112.8,
                  height: 8.96,
                  scale: 'contain',
                  gravity: 'rightTop'
                },
                {
                  kind: 'group',
                  orientation: 'row',
                  gravity: 'rightBottom',
                  margins: { top: 8.94 },
                  children: [
                    {
                      kind: 'shape',
                      shape: 'circle',
                      width: 7.2,
                      height: 7.2,
                      color: '#FF8933'
                    },
                    {
                      kind: 'text',
                      token: { kind: 'imageParams' },
                      font: 'sys',
                      size: 7.2,
                      weight: 700,
                      color: '#000000',
                      alpha: 0.7,
                      tracking: 0.03,
                      lineHeight: 8.44,
                      align: 'left',
                      margins: { left: 6.6 }
                    }
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  assets: {
    hasselblad_watermark_dark: { file: 'hasselblad_watermark_dark.webp', width: 831, height: 73 }
  },
  audit: {
    status: 'approx',
    notes: 'Hand-authored from OPPO Gallery hassel_style_1.json; pending visual audit.'
  }
}

const LUMO: StockStyle = {
  id: 'oppo-gallery:lumo_style_1',
  vendor: 'oppo',
  family: 'OPPO 相册',
  name: 'LUMO · 页脚',
  nameEn: 'LUMO FOOTER',
  placement: 'out',
  referenceWidth: 360,
  margins: { left: 0, top: 0, right: 0, bottom: 60 },
  background: '#FFFFFF',
  bands: [
    {
      direction: 'bottom',
      thickness: 60,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 360,
          height: 60,
          gravity: 'center',
          children: [
            {
              kind: 'group',
              orientation: 'column',
              gravity: 'leftVerticalCenter',
              margins: { left: 20 },
              children: [
                {
                  kind: 'text',
                  token: { kind: 'model' },
                  font: 'sys',
                  size: 12,
                  weight: 600,
                  color: '#000000',
                  tracking: 0.04,
                  lineHeight: 14,
                  align: 'left'
                },
                {
                  kind: 'text',
                  token: { kind: 'imageParams' },
                  font: 'sys',
                  size: 7,
                  weight: 400,
                  color: '#000000',
                  alpha: 0.54,
                  tracking: 0.08,
                  lineHeight: 8,
                  align: 'left',
                  margins: { top: 1 }
                }
              ]
            },
            { kind: 'space', weight: 1 },
            {
              kind: 'image',
              asset: 'lumo_watermark_logo_dark',
              width: 52,
              height: 32,
              scale: 'contain',
              gravity: 'rightVerticalCenter',
              margins: { right: 16 }
            }
          ]
        }
      ]
    }
  ],
  assets: {
    lumo_watermark_logo_dark: { file: 'lumo_watermark_logo_dark.webp', width: 386, height: 120 }
  },
  audit: {
    status: 'approx',
    notes: 'Hand-authored from OPPO Gallery lumo_style_1.json; pending visual audit.'
  }
}

const GR5_LABELS = [
  'Style', 'Saturation', 'Hue', 'Tone', 'Contrast', 'Highlight', 'Shadow',
  'Sharpness', 'Clarity', 'Grain Intensity', 'Grain Size'
]

function gr5Row(label: string, first = false): StockNode {
  return {
    kind: 'group',
    orientation: 'row',
    width: 82,
    children: [
      {
        kind: 'text',
        token: { kind: 'literal', value: label },
        font: first ? 'avenir' : 'sys',
        size: 5,
        weight: first ? 700 : 400,
        color: '#848484',
        tracking: 0.04,
        lineHeight: 6.4,
        width: 41,
        align: 'left'
      },
      { kind: 'space', weight: 1 },
      {
        kind: 'text',
        token: { kind: 'literal', value: '—' },
        font: 'avenir',
        size: 5,
        weight: first ? 700 : 400,
        color: '#848484',
        tracking: 0.04,
        lineHeight: 6.4,
        align: 'left',
        gravity: 'rightVerticalCenter'
      }
    ]
  }
}

const GR5: StockStyle = {
  id: 'oppo-camerapro:gr_style_5',
  vendor: 'oppo',
  family: 'OPPO 相机',
  name: '理光 GR · 参数边栏',
  nameEn: 'RICOH GR · SIDEBAR',
  placement: 'with',
  referenceWidth: 360,
  margins: { left: 6, top: 6, right: 120, bottom: 6 },
  background: '#FFFFFF',
  bands: [
    {
      direction: 'right',
      thickness: 120,
      children: [
        {
          kind: 'group',
          orientation: 'column',
          width: 120,
          matchHeight: true,
          gravity: 'leftTop',
          children: [
            {
              kind: 'text',
              token: { kind: 'model' },
              font: 'sys',
              size: 8,
              weight: 600,
              color: '#000000',
              tracking: 0.04,
              lineHeight: 9.6,
              width: 120,
              align: 'center',
              gravity: 'topHorCenter',
              optional: true,
              margins: { top: 6 }
            },
            {
              kind: 'group',
              orientation: 'column',
              width: 82,
              gap: 3,
              gravity: 'leftTop',
              margins: { left: 31, top: 12 },
              children: GR5_LABELS.map((label, i) => gr5Row(label, i === 0))
            },
            { kind: 'space', weight: 1 },
            {
              kind: 'image',
              asset: 'gr_style_5-realme_gr_logo_dark',
              width: 56,
              height: 18,
              scale: 'contain',
              gravity: 'topHorCenter',
              margins: { bottom: 6 }
            }
          ]
        }
      ]
    }
  ],
  assets: {
    'gr_style_5-realme_gr_logo_dark': { file: 'oppo/camerapro/realme_gr_logo_dark.webp', width: 56, height: 18 }
  },
  audit: {
    status: 'approx',
    notes: 'Hand-authored GR5 right sidebar; filter values are not stored in EXIF and render as dashes.'
  }
}

function oppoSideStyle(id: string, name: string, assetKey: string, file: string, w: number, h: number, dims: { width: number; height: number }): StockStyle {
  return {
    id,
    vendor: 'oppo',
    family: 'OPPO 相册',
    name,
    nameEn: id.replace('oppo-gallery:', ''),
    placement: 'with',
    referenceWidth: 360,
    margins: { left: 0, top: 0, right: 150, bottom: 0 },
    background: '#FFFFFF',
    bands: [
      {
        direction: 'right',
        thickness: 150,
        background: '#FFFFFF',
        children: [
          {
            kind: 'group',
            orientation: 'column',
            width: 142,
            matchHeight: true,
            margins: { left: 4, right: 4, top: 18, bottom: 18 },
            children: [
              {
                kind: 'text',
                token: { kind: 'model' },
                font: 'sys',
                size: 10,
                weight: 600,
                color: '#222222',
                tracking: 0.03,
                lineHeight: 13,
                width: 134,
                align: 'left',
                optional: false
              },
              {
                kind: 'text',
                token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' },
                font: 'sys',
                size: 8,
                weight: 400,
                color: '#77716A',
                tracking: 0.02,
                lineHeight: 11,
                width: 134,
                align: 'left',
                margins: { top: 8 }
              },
              {
                kind: 'text',
                token: { kind: 'location' },
                font: 'sys',
                size: 8,
                weight: 400,
                color: '#77716A',
                tracking: 0.02,
                lineHeight: 11,
                width: 134,
                align: 'left',
                optional: true,
                margins: { top: 3 }
              },
              { kind: 'space', weight: 1 },
              {
                kind: 'image',
                asset: assetKey,
                width: w,
                height: h,
                scale: 'contain',
                gravity: 'topHorCenter'
              }
            ]
          }
        ]
      }
    ],
    assets: { [assetKey]: { file, width: dims.width, height: dims.height } },
    audit: { status: 'approx', notes: 'Side panel reflowed to prevent clipping; source config uses a 50dp panel but a 97dp logo. Pending visual audit.' }
  }
}

const OPPO_FILM_2: StockStyle = oppoSideStyle(
  'oppo-gallery:personalize_film_2', '胶片 · 2', 'film_daylight_vertical_dark', 'film_daylight_vertical_dark.webp', 100, 68,
  { width: 400, height: 272 }
)
const OPPO_MASTERSIGN_3: StockStyle = oppoSideStyle(
  'oppo-gallery:personalize_masterSign_3', '大师签名 · 3', 'master_sign_3_dark', 'master_sign_3_dark.webp', 110, 30,
  { width: 750, height: 204 }
)

const OPPO_RETRO_5: StockStyle = {
  id: 'oppo-gallery:personalize_retroCamera_5',
  vendor: 'oppo',
  family: 'OPPO 相册',
  name: '复古相机 · 5',
  nameEn: 'personalize_retroCamera_5',
  placement: 'with',
  referenceWidth: 360,
  margins: { left: 10, top: 10, right: 162, bottom: 10 },
  background: '#FFFFFF',
  bands: [
    {
      direction: 'right',
      thickness: 162,
      background: '#FFFFFF',
      children: [
        {
          kind: 'group',
          orientation: 'column',
          width: 142,
          matchHeight: true,
          margins: { left: 10, right: 10, top: 10, bottom: 10 },
          children: [
            ...[
              ['@exif=@{iso}', 'ISO'],
              ['@exif=@{aperture}', 'F'],
              ['@exif=@{focal}', 'mm'],
              ['@exif=@{speed}', 's']
            ].map(([value, label]) => ({
              kind: 'group' as const,
              orientation: 'row' as const,
              gravity: 'leftVerticalCenter' as const,
              margins: { top: 12 },
              children: [
                {
                  kind: 'text' as const,
                  token: { kind: 'template' as const, value },
                  font: 'butler',
                  size: 12,
                  weight: 500,
                  color: '#111111',
                  tracking: 0.05,
                  lineHeight: 15,
                  align: 'left' as const
                },
                {
                  kind: 'text' as const,
                  token: { kind: 'literal' as const, value: label },
                  font: 'butler',
                  size: 8,
                  weight: 500,
                  color: '#77716A',
                  tracking: 0.08,
                  lineHeight: 11,
                  align: 'left' as const,
                  margins: { left: 6 }
                }
              ]
            })),
            { kind: 'space', weight: 1 },
            {
              kind: 'image',
              asset: 'classic_double_reverse2_dark',
              width: 68,
              height: 40,
              scale: 'contain',
              gravity: 'topHorCenter'
            }
          ]
        }
      ]
    }
  ],
  assets: {
    classic_double_reverse2_dark: { file: 'classic_double_reverse2_dark.webp', width: 320, height: 321 }
  },
  audit: { status: 'approx', notes: 'Side panel reflowed into four compact metric rows to avoid top clipping at 4:3. Pending visual audit.' }
}


const XIAOMI_FILM_15: StockStyle = {
  id: 'xiaomi-film:15',
  vendor: 'xiaomi',
  family: '小米相机',
  name: '胶片 · 15',
  nameEn: 'film_15',
  placement: 'out',
  referenceWidth: 1080,
  margins: { left: 80, top: 80, right: 80, bottom: 80 },
  background: '#141414',
  bands: [
    {
      direction: 'top',
      thickness: 80,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          matchWidth: true,
          matchHeight: true,
          margins: { left: 80, right: 80 },
          children: [
            {
              kind: 'image',
              asset: 'film-15-icon_triangle',
              width: 80,
              height: 17.9,
              scale: 'contain',
              gravity: 'leftVerticalCenter'
            },
            {
              kind: 'text',
              token: { kind: 'location' },
              font: 'misans',
              size: 17,
              weight: 500,
              color: '#CE9238',
              tracking: 0.04,
              lineHeight: 20,
              gravity: 'rightVerticalCenter',
              optional: true
            }
          ]
        }
      ]
    },
    {
      direction: 'bottom',
      thickness: 80,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          matchWidth: true,
          matchHeight: true,
          margins: { left: 80, right: 80 },
          children: [
            {
              kind: 'text',
              token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' },
              font: 'misans',
              size: 17,
              weight: 500,
              color: '#CE9238',
              tracking: 0.03,
              lineHeight: 20,
              gravity: 'leftVerticalCenter'
            },
            {
              kind: 'group',
              orientation: 'row',
              gravity: 'center',
              children: [
                {
                  kind: 'image',
                  asset: 'film-15-logo-xiaomi_CE9238',
                  width: 109.4,
                  height: 26,
                  scale: 'contain',
                  margins: { right: 8 }
                },
                {
                  kind: 'text',
                  token: { kind: 'template', value: '@model=@{versionNumber}' },
                  font: 'misans',
                  size: 24,
                  weight: 500,
                  color: '#CE9238',
                  lineHeight: 28,
                  align: 'left'
                },
                {
                  kind: 'text',
                  token: { kind: 'template', value: '@model=@{versionName}' },
                  font: 'misans',
                  size: 15,
                  weight: 400,
                  color: '#CE9238',
                  tracking: 0.028,
                  lineHeight: 22,
                  align: 'center',
                  optional: true,
                  margins: { left: 4 },
                  outline: { paddingX: 6, height: 22, radius: 5.6, strokeWidth: 1.8, strokeColor: '#CE9238' }
                }
              ]
            },
            {
              kind: 'text',
              token: { kind: 'template', value: '@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}' },
              font: 'misans',
              size: 17,
              weight: 500,
              color: '#CE9238',
              tracking: 0.03,
              lineHeight: 20,
              gravity: 'rightVerticalCenter'
            }
          ]
        }
      ]
    }
  ],
  assets: {
    'film-15-icon_triangle': { file: 'xiaomi/film/15/icon_triangle.webp', width: 161, height: 36 },
    'film-15-logo-xiaomi_CE9238': { file: 'xiaomi/film/15/xiaomi_CE9238.webp', width: 673, height: 160 }
  },
  audit: { status: 'approx', notes: 'NeoHyper 17 Ultra film/15: 80dp bands with 80dp side inset, top triangle+location row, bottom time+brand+EXIF row, #CE9238. Config-derived; no vendor-rendered 4:3 frame.' }
}

function xiaomiInnerSidebar(
  id: string,
  name: string,
  nameEn: string,
  logoH: number,
  modelSize: number,
  lineSize: number,
  versionSize: number
): StockStyle {
  const logoW = Math.round(((logoH * 673) / 160) * 100) / 100
  const logoBlack = `${nameEn}-logo-black`
  const logoWhite = `${nameEn}-logo-white`
  const themes = [
    {
      id: 'white',
      label: 'white',
      swatch: '#FFFFFFFF',
      background: '#FFFFFFFF',
      foreground: ['#FF000000', '#80000000', '#BF000000', 'black']
    },
    {
      id: 'black',
      label: 'black',
      swatch: '#FF141414',
      background: '#FF141414',
      foreground: ['#FFFFFFFF', '#80FFFFFF', '#CCFFFFFF', 'white']
    }
  ]
  return {
    id,
    vendor: 'xiaomi',
    family: '小米相机',
    name,
    nameEn,
    placement: 'with',
    referenceWidth: 1080,
    margins: { left: 48, top: 48, right: 560, bottom: 48 },
    background: '#FFFFFF',
    themes,
    defaultTheme: 'white',
    assets: {
      [logoBlack]: { file: 'xiaomi/classic/17/xiaomi_black.webp', width: 673, height: 160 },
      [logoWhite]: { file: 'xiaomi/classic/17/xiaomi_white.webp', width: 673, height: 160 }
    },
    bands: [
      {
        direction: 'right',
        thickness: 560,
        children: [
          {
            kind: 'group',
            orientation: 'column',
            width: 560,
            matchHeight: true,
            gravity: 'center',
            children: [
              {
                kind: 'group',
                orientation: 'column',
                width: 560,
                gravity: 'horCenter',
                children: [
                  {
                    kind: 'group',
                    orientation: 'row',
                    gravity: 'horCenter',
                    children: [
                      {
                        kind: 'image',
                        asset: logoBlack,
                        themeAssets: { white: logoBlack, black: logoWhite },
                        width: logoW,
                        height: logoH,
                        scale: 'contain',
                        isBrandLogo: true
                      },
                      {
                        kind: 'text',
                        token: { kind: 'template', value: '@model=@{versionNumber}' },
                        font: 'misans',
                        size: modelSize,
                        weight: 500,
                        colorIndex: 0,
                        tracking: 0.01,
                        lineHeight: modelSize * 1.2,
                        align: 'left',
                        gravity: 'leftVerticalCenter',
                        margins: { left: 8 }
                      },
                      {
                        kind: 'text',
                        token: { kind: 'template', value: '@model=@{versionName}' },
                        font: 'misans',
                        size: versionSize,
                        weight: 450,
                        colorIndex: 0,
                        tracking: 0.04,
                        lineHeight: versionSize * 1.2,
                        align: 'left',
                        gravity: 'leftVerticalCenter',
                        margins: { left: 6 },
                        optional: true
                      }
                    ]
                  },
                  {
                    kind: 'text',
                    token: { kind: 'template', value: '@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}' },
                    font: 'misans',
                    size: lineSize,
                    weight: 400,
                    colorIndex: 1,
                    tracking: 0.04,
                    lineHeight: lineSize * 1.25,
                    width: 520,
                    align: 'center',
                    gravity: 'horCenter',
                    margins: { top: 10 }
                  },
                  {
                    kind: 'text',
                    token: { kind: 'location' },
                    font: 'misans',
                    size: lineSize,
                    weight: 400,
                    colorIndex: 1,
                    tracking: 0.04,
                    lineHeight: lineSize * 1.25,
                    width: 520,
                    align: 'center',
                    gravity: 'horCenter',
                    margins: { top: 10 },
                    optional: true
                  },
                  {
                    kind: 'text',
                    token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' },
                    font: 'misans',
                    size: lineSize,
                    weight: 400,
                    colorIndex: 1,
                    tracking: 0.04,
                    lineHeight: lineSize * 1.25,
                    width: 520,
                    align: 'center',
                    gravity: 'horCenter',
                    margins: { top: 10 }
                  }
                ]
              }
            ]
          }
        ]
      }
    ],
    audit: {
      status: 'approx',
      notes: 'From NeoHyper 17 Ultra classic/22 config: inner-border right, 48dp padding, 560dp sidebar. No vendor-rendered reference frame; geometry is config-derived.'
    }
  }
}

const XIAOMI_CLASSIC_22: StockStyle = xiaomiInnerSidebar('xiaomi-classic:22', '经典 · 22', 'classic_22', 40, 36, 24, 20)
const XIAOMI_CLASSIC_99: StockStyle = xiaomiInnerSidebar('xiaomi-classic:99', '经典 · 99', 'classic_99', 26, 22.6, 20, 14.3)

export const STOCK_STYLES: Record<string, StockStyle> = {
  ...Object.fromEntries(OPPO_GALLERY_STYLES.map((s) => [s.id, s])),
  ...Object.fromEntries(OPPO_CAMERAPRO_STYLES.map((s) => [s.id, s])),
  ...Object.fromEntries(XIAOMI_STYLES.map((s) => [s.id, s])),
  ...Object.fromEntries(VIVO_STYLES.map((s) => [s.id, s])),
  [HASSEL.id]: HASSEL,
  [LUMO.id]: LUMO,
  [GR5.id]: GR5,
  [OPPO_FILM_2.id]: OPPO_FILM_2,
  [OPPO_MASTERSIGN_3.id]: OPPO_MASTERSIGN_3,
  [OPPO_RETRO_5.id]: OPPO_RETRO_5,
  [XIAOMI_FILM_15.id]: XIAOMI_FILM_15,
  [XIAOMI_CLASSIC_22.id]: XIAOMI_CLASSIC_22,
  [XIAOMI_CLASSIC_99.id]: XIAOMI_CLASSIC_99
}

export function getStockStyle(id: string | undefined): StockStyle | null {
  return id ? (STOCK_STYLES[id] ?? null) : null
}

export const STOCK_STYLE_LIST: StockStyle[] = Object.values(STOCK_STYLES)
