const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const RAW = 'D:/border&watermark/_research/logos-real/raw'
const OUT = 'D:/border&watermark/framemark/src/renderer/src/assets/logos'
const REPORT = 'D:/border&watermark/_research/logos-real/manifest-scans.json'

const SOURCES = {
  voigtlander: {
    file: 'voigtlander-classic.png',
    sourceUrl: 'https://www.voigtlaender.de/wp-content/uploads/2017/12/VOIG-alt-lang-schwarz-transparent.png',
    note: 'Voigtländer 官方经典手写体（黑色透明底）',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  bronica: {
    file: 'bronica-scan.svg',
    sourceUrl: 'https://www.butkus.org/chinon/bronica/bronica_s2/bronica_s2.htm',
    note: 'Bronica S2 原厂说明书封面扫描，抠出 BRONICA 字标并描摹',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  praktica: {
    file: 'praktica-official.png',
    sourceUrl: 'https://cdn.shopify.com/s/files/1/2205/5827/files/Praktica_White_Logo.png',
    note: 'PRAKTICA 官方字标（白色透明底）',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  exakta: {
    file: 'exakta-classic.svg',
    sourceUrl: 'http://www.exaktapages.com/exakta_k.jpg',
    note: 'Exakta 经典手写体（exaktapages 站点标），二值化后描摹',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  minox: {
    file: 'minox-official.svg',
    sourceUrl: 'https://www.minox.com/',
    note: 'MINOX 官网内嵌 SVG 字标',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  sinar: {
    file: 'sinar-official.png',
    sourceUrl: 'https://sinar.ch/wp-content/uploads/sinar-logo-2026.png',
    note: 'Sinar 官方 2026 标，仅保留 Sinar 字样（去星标/副标）',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  linhof: {
    file: 'linhof-official.png',
    sourceUrl: 'https://linhof.com/wp-content/uploads/2019/01/LINHOF_logo.png',
    note: 'Linhof 官方红色手写体，放大到 900px 后转单色',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  lomo: {
    file: 'lomo-plc.png',
    sourceUrl: 'http://www.lomoplc.com/lomoplc.com_files/lomologo.gif',
    note: 'LOMO PLC 官网 LOGO 字标（裁掉副标与 ®），颜色分离后放大',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  seagull: {
    file: 'seagull-official.svg',
    sourceUrl: 'http://www.seagull-digital.com/',
    note: '上海海鸥数码照相机官网海鸥徽标（alpha 提取后描摹）',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  horseman: {
    file: 'horseman-scan.png',
    sourceUrl: 'https://www.cameramanuals.org/prof_pdf/horseman_985.pdf',
    note: 'Horseman 985 原厂说明书封面扫描字标',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  graflex: {
    file: 'graflex-catalog.svg',
    sourceUrl: 'https://www.cameramanuals.org/pdf_files/graflex-1.pdf',
    note: 'Graflex 原厂产品目录封面 GRAFLEX 字标，扫描后描摹',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  red: {
    file: 'red-official.svg',
    sourceUrl: 'https://images.reddigitalcinema.com/logo-lockups/logo-red-bts-white-2026.svg',
    note: 'RED 官方 2026 字标，裁去 “BEHIND THE SCENES” 副标',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  nothing: {
    file: 'nothing-official.svg',
    sourceUrl: 'https://nothing.tech/',
    note: 'Nothing 官网内嵌点阵字标 SVG',
    tones: { black: '#111111', white: '#FFFFFF' }
  },
  nubia: {
    file: 'nubia-official.svg',
    sourceUrl: 'https://www.nubia.com/content/dam/nubia/global/logo/nubia.svg',
    note: 'nubia 官网 SVG 字标',
    tones: { black: '#111111', white: '#FFFFFF' }
  }
}

function fillRgba(raw, color) {
  const rgb = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)]
  const out = Buffer.from(raw)
  for (let i = 0; i < out.length; i += 4) {
    out[i] = rgb[0]
    out[i + 1] = rgb[1]
    out[i + 2] = rgb[2]
  }
  return out
}

async function build(key, spec) {
  const src = path.join(RAW, spec.file)
  if (!fs.existsSync(src)) throw new Error(`missing source: ${src}`)
  const isSvg = /\.svg$/i.test(src)
  const pipeline = isSvg
    ? sharp(src, { density: 300 }).resize({ width: 1600, height: 800, fit: 'inside' })
    : sharp(src).resize({ width: 1600, height: 800, fit: 'inside', withoutEnlargement: true })
  const trimmed = await pipeline
    .ensureAlpha()
    .trim({ background: { r: 0, g: 0, b: 0, alpha: 0 }, threshold: 1 })
    .png()
    .toBuffer()
  const meta = await sharp(trimmed).metadata()
  if (!meta.width || !meta.height) throw new Error('empty render')
  const { data, info } = await sharp(trimmed).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  const written = []
  for (const [tone, color] of Object.entries(spec.tones)) {
    const rgba = fillRgba(data, color)
    const buf = await sharp(rgba, { raw: { width: info.width, height: info.height, channels: 4 } })
      .extend({ top: 10, bottom: 10, left: 10, right: 10, background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .webp({ quality: 100, lossless: true, alphaQuality: 100, effort: 6 })
      .toBuffer()
    const file = path.join(OUT, `${key}_${tone}.webp`)
    fs.writeFileSync(file, buf)
    written.push({ tone, file, bytes: buf.length })
  }
  return { key, source: spec.file, sourceUrl: spec.sourceUrl, note: spec.note, width: info.width, height: info.height, tones: written }
}

async function main() {
  const results = []
  for (const [key, spec] of Object.entries(SOURCES)) {
    results.push(await build(key, spec))
    console.log('OK', key, spec.file)
  }
  fs.writeFileSync(REPORT, JSON.stringify({ generatedAt: new Date().toISOString(), logos: results }, null, 2))
  console.log('done', results.length)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
