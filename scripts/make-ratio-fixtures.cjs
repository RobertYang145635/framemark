const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')
// #57 PATH-LITERALS: source photos are resolved from a single entry point, not hardcoded root paths
const { resolveAsset } = require('../../_research/test-assets/resolve-test-asset.cjs')

const OUT = 'D:/border&watermark/_research/test-assets/ratio-study'
fs.mkdirSync(OUT, { recursive: true })

const jobs = [
  { name: '3x2.jpg', src: resolveAsset('P1181817.jpg'), w: 3000, h: 2000 },
  { name: '2x3.jpg', src: resolveAsset('PYHY5599-已增强-降噪.jpg'), w: 2000, h: 3000 },
  { name: '1x1.jpg', src: resolveAsset('P1181817.jpg'), w: 2800, h: 2800 },
  { name: '4x5.jpg', src: resolveAsset('P1181817.jpg'), w: 2400, h: 3000 },
  { name: '16x9.jpg', src: resolveAsset('P1181817.jpg'), w: 3200, h: 1800 }
]

;(async () => {
  const thumbs = []
  for (const job of jobs) {
    const out = path.join(OUT, job.name)
    await sharp(job.src)
      .rotate()
      .resize(job.w, job.h, { fit: 'cover', position: 'centre' })
      .withMetadata()
      .jpeg({ quality: 95, chromaSubsampling: '4:4:4' })
      .toFile(out)
    const meta = await sharp(out).metadata()
    const ex = await require('exifr').parse(out, { pick: ['Make', 'Model', 'LensModel', 'FocalLength', 'FNumber', 'ExposureTime', 'ISO', 'Orientation'] })
    console.log(job.name, meta.width, meta.height, ex)
    thumbs.push(out)
  }
  const tileW = 320
  const tileH = 260
  const comps = []
  for (let i = 0; i < thumbs.length; i++) {
    const buf = await sharp(thumbs[i]).resize({ width: tileW - 16, height: tileH - 34, fit: 'inside', background: '#111' }).jpeg({ quality: 85 }).toBuffer()
    const m = await sharp(buf).metadata()
    comps.push({ input: buf, left: i * tileW + Math.round((tileW - m.width) / 2), top: 8 })
    const svg = Buffer.from(`<svg width="${tileW}" height="26"><text x="10" y="19" font-family="sans-serif" font-size="13" fill="#eee">${path.basename(thumbs[i])}</text></svg>`)
    comps.push({ input: svg, left: i * tileW, top: tileH - 24 })
  }
  await sharp({ create: { width: tileW * jobs.length, height: tileH, channels: 3, background: '#111' } }).composite(comps).jpeg({ quality: 88 }).toFile(path.join(OUT, 'contact.jpg'))
  console.log('done')
})().catch((e) => { console.error(e); process.exit(1) })
