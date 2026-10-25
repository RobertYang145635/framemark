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

/* ------------------------------------------------------------------ */
/* VIVO-BATCH-1: five non-Zeiss vivo X500 Pro Max styles.              */
/* Band geometry + card artwork measured from settled (p=0.98) PAG     */
/* frames rendered with libpag from roms/vivo-x500promax; the card     */
/* art PNGs are vendor artwork rasterized by the vendor's own PAG file */
/* (photo layer punched out), never redrawn. Text lines and their      */
/* order come from each edit.apk's assets/temp.json `mappings`.        */
/* Reference canvas is the PAG canvas: 1497 x 1356 px at 4:3.          */
/* ------------------------------------------------------------------ */

const cardArt = (
  file: string,
  asset: string,
  w: number,
  h: number,
  offX: number,
  offY: number
): StockStyle['bands'][number] => ({
  direction: 'overlay',
  width: w,
  thickness: h,
  offset: { x: offX, y: offY },
  drawFirst: true,
  children: [
    { kind: 'image', asset, width: w, height: h, scale: 'fill', gravity: 'leftTop' }
  ]
})

const TXT = (
  token: StockTextNode['token'],
  size: number,
  color: string,
  weight = 400,
  align: 'left' | 'right' | 'center' = 'left'
): StockTextNode => ({
  kind: 'text',
  token,
  font: 'sys',
  size,
  weight,
  color,
  tracking: 0.06,
  lineHeight: size * 1.35,
  align
})

const bandRow = (width: number, children: StockNode[]) => ({
  kind: 'group' as const,
  orientation: 'row' as const,
  width,
  matchHeight: true,
  gravity: 'center' as const,
  children
})

/** 拍立得：black film card + wide white signature band (polaroid/4_3_540_q PAG). */
const POLAROID: StockStyle = {
  id: 'vivo-polaroid:frame',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '拍立得 · 签名框',
  nameEn: 'POLAROID',
  placement: 'out',
  referenceWidth: 1497,
  margins: { left: 39, top: 39, right: 39, bottom: 271 },
  background: '#000000',
  bands: [
    cardArt('vivo/batch1/polaroid-card.png', 'vivo-polaroid-card', 1497, 1356, -39, -39),
    {
      direction: 'bottom',
      thickness: 231,
      children: [
        bandRow(1419, [
          { ...TXT({ kind: 'model' }, 30, '#111111', 500) },
          { kind: 'space', weight: 1 },
          { ...TXT({ kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' }, 26, '#555555', 400, 'right') },
          { ...TXT({ kind: 'location' }, 26, '#555555', 400, 'right'), margins: { left: 26 }, optional: true }
        ])
      ]
    }
  ],
  assets: { 'vivo-polaroid-card': { file: 'vivo/batch1/polaroid-card.png', width: 1497, height: 1356 } },
  audit: {
    status: 'approx',
    notes:
      'Polaroid film card; 39/39/39/271 bands and white 231 band measured from settled polaroid/4_3_540_q PAG frame (canvas 1497x1356). Card art is the vendor PAG raster with the photo layer punched out. Text order (model left, time+location right) from edit.apk temp.json mappings 1/4/12.',
    unsupported: ['bottom-centre mini-photo seen mid-animation is not present in the settled PAG frame; omitted', 'signature (user handwriting) omitted']
  }
}

/** 邮票框：grey card, perforated white stamp frame around the photo. */
const STAMP_PHOTO: StockStyle = {
  id: 'vivo-stamp:frame',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '邮票 · 齿孔框',
  nameEn: 'STAMP',
  placement: 'out',
  referenceWidth: 1497,
  margins: { left: 119, top: 175, right: 119, bottom: 237 },
  background: '#D9D9D9',
  bands: [
    cardArt('vivo/batch1/stamp-card.png', 'vivo-stamp-card', 1497, 1356, -119, -175),
    {
      direction: 'top',
      thickness: 175,
      children: [
        bandRow(1497, [
          { ...TXT({ kind: 'time' }, 30, '#333333', 500), margins: { left: 40 } },
          { kind: 'space', weight: 1 },
          { ...TXT({ kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' }, 26, '#555555', 400, 'right'), margins: { right: 40 } }
        ])
      ]
    },
    {
      direction: 'bottom',
      thickness: 237,
      children: [
        bandRow(1497, [
          { ...TXT({ kind: 'model' }, 30, '#333333', 500), margins: { left: 40 } },
          { kind: 'space', weight: 1 },
          { ...TXT({ kind: 'location' }, 26, '#555555', 400, 'right'), margins: { right: 40 }, optional: true }
        ])
      ]
    }
  ],
  assets: { 'vivo-stamp-card': { file: 'vivo/batch1/stamp-card.png', width: 1497, height: 1356 } },
  audit: {
    status: 'approx',
    notes:
      'Stamp frame; photo hole 119/175/119/237 measured from settled stamp_photo/4_3_540_q PAG frame. Perforated stamp border and mini-stamp are the vendor PAG raster (no redraw). Text slots (time top-left, model bottom-left, time text top-right, location bottom-right) from temp.json mappings 1-4; exact host text metrics unknown.',
    unsupported: ['DAY/WEEK special time format approximated by the plain date token']
  }
}

/** 色调框：full-bleed photo with a white info rail on the right. */
const TONE: StockStyle = {
  id: 'vivo-tone:frame',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '色调 · 右侧栏',
  nameEn: 'TONE',
  placement: 'out',
  referenceWidth: 1497,
  margins: { left: 0, top: 0, right: 226, bottom: 0 },
  background: '#FFFFFF',
  bands: [
    cardArt('vivo/batch1/tone-card.png', 'vivo-tone-card', 1497, 968, 0, 0),
    {
      direction: 'right',
      thickness: 226,
      children: [
        {
          kind: 'group',
          orientation: 'column',
          gravity: 'center',
          children: [
            TXT({ kind: 'literal', value: 'vivo' }, 40, '#111111', 600, 'center'),
            { ...TXT({ kind: 'model' }, 24, '#555555', 400, 'center'), optional: true }
          ]
        }
      ]
    }
  ],
  assets: { 'vivo-tone-card': { file: 'vivo/batch1/tone-card.png', width: 1497, height: 968 } },
  audit: {
    status: 'approx',
    notes:
      'Tone frame; 226-wide white right rail measured from settled tone/4_3_540_q PAG frame (photo x0..1270, rail x1272..1496). Rail text "vivo" per temp.json mapping 1 (右侧中部：vivo). QR code subCard is opt-in in the host and omitted.',
    unsupported: ['optional QR code omitted (host-rendered, default off)']
  }
}

/** 地理：text-only overlay on the photo (geographic PAG carries no decoration). */
const GEOGRAPHIC: StockStyle = {
  id: 'vivo-geographic:overlay',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '地理 · 信息叠加',
  nameEn: 'GEOGRAPHIC',
  placement: 'in',
  referenceWidth: 1497,
  margins: { left: 0, top: 0, right: 0, bottom: 0 },
  background: '#00000000',
  transparentBackground: true,
  bands: [
    {
      direction: 'overlay',
      gravity: 'leftBottom',
      offset: { x: 72, y: -72 },
      children: [
        {
          kind: 'group',
          orientation: 'column',
          gravity: 'leftBottom',
          children: [
            { ...TXT({ kind: 'model' }, 40, '#FFFFFF', 600) },
            { ...TXT({ kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' }, 28, '#F2F2F2') },
            { ...TXT({ kind: 'custom' }, 28, '#F2F2F2'), optional: true },
            { ...TXT({ kind: 'location' }, 28, '#F2F2F2'), optional: true }
          ]
        }
      ]
    }
  ],
  assets: {},
  audit: {
    status: 'approx',
    notes:
      'Geographic overlay; settled geographic_photo/4_3_540_q PAG frame is fully transparent (numImages=1, the photo) so there is no vendor decoration to copy and none was invented. Line order model / time / custom text / latitude-longitude from temp.json mappings 1-4; anchored bottom-left over the photo, host metrics unknown.',
    unsupported: ['latitude/longitude formatting approximated by the location token', 'text opacity slider (10-100%) not modelled']
  }
}

/** 明信片：white card, photo inset, postcard text row on top of the card. */
const POST_CARD: StockStyle = {
  id: 'vivo-postcard:frame',
  vendor: 'vivo',
  family: 'vivo 相机',
  name: '明信片 · 邮戳框',
  nameEn: 'POST CARD',
  placement: 'out',
  referenceWidth: 1497,
  margins: { left: 92, top: 92, right: 92, bottom: 281 },
  background: '#FFFFFF',
  bands: [
    cardArt('vivo/batch1/postcard-card.png', 'vivo-postcard-card', 1405, 1356, -46, -92),
    {
      direction: 'top',
      thickness: 92,
      children: [
        bandRow(1311, [
          { ...TXT({ kind: 'model' }, 28, '#333333', 500) },
          { kind: 'space', weight: 1 },
          { ...TXT({ kind: 'literal', value: 'POST CARD' }, 26, '#777777', 600, 'right') }
        ])
      ]
    },
    {
      direction: 'bottom',
      thickness: 281,
      children: [
        bandRow(1311, [
          {
            kind: 'group',
            orientation: 'column',
            gravity: 'leftVerticalCenter',
            children: [
              { ...TXT({ kind: 'template', value: '@time=@wm_time_yyyy_MM_dd_HH_mm' }, 26, '#555555') },
              { ...TXT({ kind: 'location' }, 26, '#555555'), optional: true }
            ]
          },
          { kind: 'space', weight: 1 },
          { ...TXT({ kind: 'custom' }, 28, '#333333', 500, 'right'), optional: true }
        ])
      ]
    }
  ],
  assets: { 'vivo-postcard-card': { file: 'vivo/batch1/postcard-card.png', width: 1405, height: 1356 } },
  audit: {
    status: 'approx',
    notes:
      'Postcard frame; card x46..1450, photo hole x92..1404 y92..1075 measured from settled post_card/4_3_540_q PAG frame. "POST CARD" literal from mapping 2 (右侧上方：POST CARD TEXT); model top-left per mapping 1. The rotating postmark graphics in the host are user-selected personal_graphic vectors (res/*.xml in edit.apk) and are omitted here.',
    unsupported: ['postmark personal-graphic artwork (Android VectorDrawable in edit.apk) not converted; element omitted', 'dynamic background (live) option omitted']
  }
}

export const VIVO_STYLES: StockStyle[] = [
  ZEISS_TOP,
  ZEISS_UP_DOWN,
  ZEISS_INSIDE,
  POLAROID,
  STAMP_PHOTO,
  TONE,
  GEOGRAPHIC,
  POST_CARD
]
