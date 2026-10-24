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
              width: 160,
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

const XIAOMI_LEICA_2: StockStyle = {
  id: 'xiaomi-leica:2',
  vendor: 'xiaomi',
  family: '小米相机',
  name: '徕卡 · 2',
  nameEn: 'leica_2_xuanyuan',
  placement: 'out',
  referenceWidth: 1080,
  margins: { left: 80, top: 80, right: 80, bottom: 258 },
  background: '#FFFFFF',
  bands: [
    {
      direction: 'top',
      thickness: 80,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 1080,
          height: 80,
          gravity: 'rightBottom',
          margins: { left: 80 },
          children: [
            { kind: 'space', weight: 1 },
            {
              kind: 'image',
              asset: 'xiaomi-leica-2-xiaomi',
              width: 101,
              height: 24,
              scale: 'contain',
              gravity: 'leftBottom',
              margins: { right: 10 }
            },
            {
              kind: 'text',
              token: { kind: 'template', value: '@model=@{versionNumber} @{versionName}' },
              font: 'misans',
              size: 21.6,
              weight: 500,
              color: '#000000',
              align: 'left',
              gravity: 'leftBottom'
            }
          ]
        }
      ]
    },
    {
      direction: 'bottom',
      thickness: 258,
      children: [
        {
          kind: 'group',
          orientation: 'column',
          gravity: 'center',
          children: [
            {
              kind: 'image',
              asset: 'xiaomi-leica-2-leica',
              width: 72,
              height: 72,
              scale: 'contain',
              gravity: 'center'
            },
            {
              kind: 'text',
              token: { kind: 'template', value: '@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}' },
              font: 'oxanium',
              size: 20,
              weight: 500,
              color: '#E20612',
              align: 'center',
              gravity: 'center',
              margins: { top: 20 }
            },
            {
              kind: 'text',
              token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' },
              font: 'oxanium',
              size: 20,
              weight: 500,
              color: '#000000',
              alpha: 0.7,
              align: 'center',
              gravity: 'center',
              margins: { top: 12 }
            }
          ]
        }
      ]
    }
  ],
  assets: {
    'xiaomi-leica-2-xiaomi': { file: 'xiaomi/leica/2/xiaomi_black.webp', width: 673, height: 160 },
    'xiaomi-leica-2-leica': { file: 'xiaomi/leica/2/leica.webp', width: 240, height: 240 }
  },
  audit: { status: 'approx', notes: 'Hand-authored from Xiaomi leica/2 (xuanyuan) config; pending visual audit.' }
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

const XIAOMI_LEICA_91: StockStyle = {
  id: 'xiaomi-leica:91',
  vendor: 'xiaomi',
  family: '小米相机',
  name: '徕卡 · 91',
  nameEn: 'leica_91',
  placement: 'in',
  referenceWidth: 1080,
  margins: { left: 0, top: 0, right: 0, bottom: 0 },
  background: '#00000000',
  transparentBackground: true,
  bands: [
    {
      direction: 'overlay',
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 920,
          gravity: 'topHorCenter',
          margins: { top: 72 },
          children: [
            {
              kind: 'group',
              orientation: 'column',
              width: 520,
              gravity: 'leftTop',
              children: [
                {
                  kind: 'group',
                  orientation: 'row',
                  gravity: 'leftVerticalCenter',
                  children: [
                    {
                      kind: 'image',
                      asset: 'leica-91-logo-xiaomi_white',
                      width: 101,
                      height: 24,
                      scale: 'contain',
                      margins: { right: 8 }
                    },
                    {
                      kind: 'text',
                      token: { kind: 'template', value: '@model=@{versionNumber}' },
                      font: 'misans',
                      size: 21.6,
                      weight: 500,
                      color: '#FFFFFF',
                      tracking: 0,
                      lineHeight: 26,
                      align: 'left'
                    },
                    {
                      kind: 'text',
                      token: { kind: 'template', value: '@model=@{versionName}' },
                      font: 'misans',
                      size: 13.2,
                      weight: 400,
                      color: '#FFFFFF',
                      tracking: 0.028,
                      lineHeight: 20,
                      align: 'center',
                      optional: true,
                      margins: { left: 4 },
                      outline: { paddingX: 5, height: 20, radius: 4, strokeWidth: 1, strokeColor: '#BFFFFFFF' }
                    }
                  ]
                },
                {
                  kind: 'group',
                  orientation: 'row',
                  gravity: 'leftVerticalCenter',
                  margins: { top: 22 },
                  children: [
                    {
                      kind: 'text',
                      token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd' },
                      font: 'lg1056',
                      size: 20,
                      weight: 400,
                      color: '#FFFFFF',
                      tracking: 0.15,
                      lineHeight: 24,
                      align: 'left',
                      shadow: { color: 'rgba(0,0,0,0.5)', blur: 4, x: 0, y: 1 }
                    },
                    {
                      kind: 'text',
                      token: { kind: 'template', value: '@time=@wm_time_HH_mm' },
                      font: 'lg1056',
                      size: 20,
                      weight: 400,
                      color: '#FFFFFF',
                      tracking: 0.15,
                      lineHeight: 24,
                      align: 'left',
                      margins: { left: 24 },
                      shadow: { color: 'rgba(0,0,0,0.5)', blur: 4, x: 0, y: 1 }
                    }
                  ]
                }
              ]
            },
            { kind: 'space', weight: 1 },
            {
              kind: 'group',
              orientation: 'column',
              gravity: 'rightTop',
              children: [
                { kind: 'image', asset: 'leica-91-leica', width: 60, height: 60, scale: 'contain', gravity: 'rightTop' },
                {
                  kind: 'text',
                  token: { kind: 'location' },
                  font: 'misans',
                  size: 18,
                  weight: 400,
                  color: '#FFFFFF',
                  tracking: 0.04,
                  lineHeight: 22,
                  align: 'right',
                  optional: true,
                  gravity: 'rightTop',
                  margins: { top: 14 },
                  shadow: { color: 'rgba(0,0,0,0.5)', blur: 4, x: 0, y: 1 }
                },
                {
                  kind: 'text',
                  token: { kind: 'template', value: '@exif=@{focal}mm  f/@{aperture}  @{speed}s  ISO@{iso}' },
                  font: 'misans',
                  size: 18,
                  weight: 500,
                  color: '#FFFFFF',
                  tracking: 0.04,
                  lineHeight: 22,
                  align: 'right',
                  gravity: 'rightTop',
                  margins: { top: 4 },
                  shadow: { color: 'rgba(0,0,0,0.5)', blur: 4, x: 0, y: 1 }
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  assets: {
    'leica-91-logo-xiaomi_white': { file: 'xiaomi/leica/91/xiaomi_white.webp', width: 673, height: 160 },
    'leica-91-leica': { file: 'xiaomi/leica/91/leica.webp', width: 240, height: 240 }
  },
  audit: {
    status: 'approx',
    notes: 'In-style Zeiss/Leica top overlay; absolute host layout approximated from center_top config. Pending visual audit.'
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

const OPPO_BRAND_3: StockStyle = oppoSideStyle(
  'oppo-gallery:personalize_brand_3', '品牌 · 3', 'brand_imagine_dark', 'brand_imagine_dark.webp', 100, 52,
  { width: 528, height: 272 }
)
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
  margins: { left: 80, top: 80, right: 80, bottom: 100 },
  background: '#141414',
  bands: [
    {
      direction: 'top',
      thickness: 80,
      children: [
        {
          kind: 'group',
          orientation: 'column',
          width: 920,
          height: 80,
          gravity: 'leftTop',
          children: [
            {
              kind: 'image',
              asset: 'film-15-icon_triangle',
              width: 80,
              height: 17.9,
              scale: 'contain',
              gravity: 'topHorCenter'
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
              width: 920,
              align: 'center',
              optional: true,
              margins: { top: 4 }
            }
          ]
        }
      ]
    },
    {
      direction: 'bottom',
      thickness: 100,
      children: [
        {
          kind: 'group',
          orientation: 'column',
          width: 920,
          height: 100,
          gravity: 'leftTop',
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
              width: 920,
              align: 'left'
            },
            {
              kind: 'group',
              orientation: 'row',
              gravity: 'leftVerticalCenter',
              margins: { top: 6 },
              children: [
                {
                  kind: 'image',
                  asset: 'film-15-logo-xiaomi_CE9238',
                  width: 101,
                  height: 24,
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
              width: 920,
              align: 'left',
              margins: { top: 16 }
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
  audit: { status: 'approx', notes: 'Footer reflowed with explicit 6/10dp gaps; source 80dp band was too tight for date+brand+EXIF at 4:3.' }
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
  [XIAOMI_LEICA_2.id]: XIAOMI_LEICA_2,
  [XIAOMI_LEICA_91.id]: XIAOMI_LEICA_91,
  [GR5.id]: GR5,
  [OPPO_BRAND_3.id]: OPPO_BRAND_3,
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
