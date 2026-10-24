import type { StockImageNode, StockNode, StockStyle, StockTextNode } from './ir'

const GRID = { file: 'vivo/zeiss/grid.png', width: 168, height: 168 }
const STAMP_BLACK = { file: 'vivo/zeiss/stamp-black.png', width: 221, height: 192 }
const STAMP_WHITE = { file: 'vivo/zeiss/stamp-white.png', width: 221, height: 192 }

const BRAND = (size: number): StockTextNode => ({
  kind: 'text',
  token: { kind: 'literal', value: 'ZEISS' },
  font: 'zeiss',
  size,
  weight: 700,
  colorIndex: 0,
  tracking: 0.04,
  lineHeight: size * 1.16,
  align: 'left'
})

const MODEL = (size = 30): StockTextNode => ({
  kind: 'text',
  token: { kind: 'model' },
  font: 'sys',
  size,
  weight: 500,
  colorIndex: 0,
  tracking: 0.2,
  lineHeight: size * 1.2,
  align: 'left',
  optional: true
})

const EXIF = (size = 26): StockTextNode => ({
  kind: 'text',
  token: { kind: 'imageParams' },
  font: 'sys',
  size,
  weight: 400,
  colorIndex: 1,
  tracking: 0.08,
  lineHeight: size * 1.2,
  align: 'left',
  optional: true
})

const DATE = (size = 24): StockTextNode => ({
  kind: 'text',
  token: { kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' },
  font: 'sys',
  size,
  weight: 400,
  colorIndex: 1,
  tracking: 0.08,
  lineHeight: size * 1.2,
  align: 'left'
})

const GRID_ICON = (size: number): StockImageNode => ({
  kind: 'image',
  asset: 'vivo-zeiss-grid',
  width: size,
  height: size,
  scale: 'contain'
})

const STAMP = (w: number, h: number): StockImageNode => ({
  kind: 'image',
  asset: 'vivo-zeiss-stamp-black',
  themeAssets: {
    white: 'vivo-zeiss-stamp-black',
    black: 'vivo-zeiss-stamp-white',
    glass: 'vivo-zeiss-stamp-black'
  },
  width: w,
  height: h,
  scale: 'contain',
  gravity: 'rightVerticalCenter'
})

const THEMES = [
  { id: 'white', label: '白底', swatch: '#FFFFFF', background: '#FFFFFF', foreground: ['#111111', '#5A5A5A', '#0057B8'] },
  { id: 'black', label: '黑底', swatch: '#111111', background: '#111111', foreground: ['#FFFFFF', '#BDBDBD', '#FFD166'] },
  { id: 'glass', label: '玻璃', swatch: '#D9FFFFFF', background: '#D9FFFFFF', foreground: ['#111111', '#4A4A4A', '#0057B8'] }
]

const topBrand = (icon: number, brand: number, model: number): StockNode => ({
  kind: 'group',
  orientation: 'row',
  gravity: 'leftVerticalCenter',
  children: [
    GRID_ICON(icon),
    {
      kind: 'group',
      orientation: 'column',
      gravity: 'leftVerticalCenter',
      margins: { left: 24 },
      children: [BRAND(brand), MODEL(model)]
    }
  ]
})

const ZEISS_TOP: StockStyle = {
  id: 'vivo-zeiss:border_top',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '蔡司 · 上框',
  nameEn: 'ZEISS TOP',
  placement: 'out',
  referenceWidth: 1440,
  margins: { left: 21, top: 305, right: 21, bottom: 220 },
  background: '#FFFFFF',
  themes: THEMES,
  defaultTheme: 'white',
  bands: [
    {
      direction: 'top',
      thickness: 305,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 1398,
          matchHeight: true,
          gravity: 'center',
          children: [
            topBrand(84, 56, 30),
            { kind: 'space', weight: 1 },
            { ...STAMP(128, 112), gravity: 'rightVerticalCenter' }
          ]
        }
      ]
    },
    {
      direction: 'bottom',
      thickness: 220,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 1398,
          matchHeight: true,
          gravity: 'center',
          children: [
            {
              kind: 'group',
              orientation: 'column',
              gravity: 'leftVerticalCenter',
              children: [EXIF(26), DATE(24)]
            },
            { kind: 'space', weight: 1 },
            { ...BRAND(34), colorIndex: 2 }
          ]
        }
      ]
    }
  ],
  assets: {
    'vivo-zeiss-grid': GRID,
    'vivo-zeiss-stamp-black': STAMP_BLACK,
    'vivo-zeiss-stamp-white': STAMP_WHITE
  },
  audit: {
    status: 'approx',
    notes: 'Zeiss top frame; 305/220 bands measured from 4:3 PAG preview. Host-rendered QR omitted; brand wordmark uses Zeiss Frutiger text.',
    unsupported: ['QR code omitted (host-rendered)', 'glass texture approximated by translucent solid']
  }
}

const ZEISS_UP_DOWN: StockStyle = {
  id: 'vivo-zeiss:up_down',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '蔡司 · 上下框',
  nameEn: 'ZEISS UP / DOWN',
  placement: 'out',
  referenceWidth: 1440,
  margins: { left: 14, top: 220, right: 14, bottom: 220 },
  background: '#FFFFFF',
  themes: THEMES,
  defaultTheme: 'glass',
  bands: [
    {
      direction: 'top',
      thickness: 220,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 1412,
          matchHeight: true,
          gravity: 'center',
          children: [
            topBrand(72, 46, 26),
            { kind: 'space', weight: 1 },
            { ...STAMP(96, 84), gravity: 'rightVerticalCenter' }
          ]
        }
      ]
    },
    {
      direction: 'bottom',
      thickness: 220,
      children: [
        {
          kind: 'group',
          orientation: 'row',
          width: 1412,
          matchHeight: true,
          gravity: 'center',
          children: [
            {
              kind: 'group',
              orientation: 'column',
              gravity: 'leftVerticalCenter',
              children: [EXIF(24), DATE(22)]
            },
            { kind: 'space', weight: 1 },
            { ...BRAND(32), colorIndex: 2 }
          ]
        }
      ]
    }
  ],
  assets: {
    'vivo-zeiss-grid': GRID,
    'vivo-zeiss-stamp-black': STAMP_BLACK,
    'vivo-zeiss-stamp-white': STAMP_WHITE
  },
  audit: {
    status: 'approx',
    notes: 'Zeiss top/bottom frame; 220/220 bands and 14dp side margins measured from 4:3 PAG preview. Glass theme uses translucent solid instead of live blur.'
  }
}

const ZEISS_INSIDE: StockStyle = {
  id: 'vivo-zeiss:inside',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '蔡司 · 内嵌',
  nameEn: 'ZEISS INSIDE',
  placement: 'in',
  referenceWidth: 1440,
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
          width: 860,
          background: '#CC002D62',
          gravity: 'leftBottom',
          margins: { left: 72, bottom: 72 },
          children: [
            { ...GRID_ICON(96), margins: { left: 30, top: 26, bottom: 26 } },
            {
              kind: 'group',
              orientation: 'column',
              gravity: 'leftVerticalCenter',
              margins: { left: 24, right: 30, top: 26, bottom: 26 },
              children: [
                { ...BRAND(44), color: '#FFFFFF', colorIndex: undefined },
                { ...MODEL(28), color: '#FFFFFF', colorIndex: undefined },
                { ...EXIF(22), color: '#DCE9FF', colorIndex: undefined }
              ]
            }
          ]
        }
      ]
    }
  ],
  assets: { 'vivo-zeiss-grid': GRID },
  audit: {
    status: 'approx',
    notes: 'In-photo Zeiss brand block; host layout not exposed by template APK. Uses official grid icon and measured photo-layer scale; marked approximate.'
  }
}

export const VIVO_STYLES: StockStyle[] = [ZEISS_TOP, ZEISS_UP_DOWN, ZEISS_INSIDE]
