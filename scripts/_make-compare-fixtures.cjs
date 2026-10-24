// Build clean 4:3 fixtures with the EXIF values shown in the original previews.
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')
const piexif = require('piexifjs')

const SRC = 'D:/border&watermark/_research/test-assets/ratio-study/4x3.jpg'
const OUT = 'D:/border&watermark/_research/compare/fixtures'

const sets = {
  plain: null,
  exif35: { make: 'FUJIFILM', model: 'X-T5', focal: 35, aperture: 1.8, iso: 300, shutter: [1, 125] },
  exif22: { make: 'SONY', model: 'ILCE-7M3', focal: 22, aperture: 16, iso: 400, shutter: [1, 125] },
  exif366: { make: 'Panasonic', model: 'DC-S1', focal: 366, aperture: 6.3, iso: 125, shutter: [1, 400] }
}

function exifObj(s) {
  const zeroth = {}
  const exif = {}
  zeroth[piexif.ImageIFD.Make] = s.make
  zeroth[piexif.ImageIFD.Model] = s.model
  zeroth[piexif.ImageIFD.Orientation] = 1
  exif[piexif.ExifIFD.FNumber] = [Math.round(s.aperture * 100), 100]
  exif[piexif.ExifIFD.FocalLength] = [Math.round(s.focal * 100), 100]
  exif[piexif.ExifIFD.ISOSpeedRatings] = s.iso
  exif[piexif.ExifIFD.ExposureTime] = s.shutter
  exif[piexif.ExifIFD.DateTimeOriginal] = '2026:10:24 10:00:00'
  exif[piexif.ExifIFD.LensModel] = `${s.make} Test Lens`
  return { '0th': zeroth, Exif: exif, GPS: {}, Interop: {}, '1st': {}, thumbnail: null }
}

;(async () => {
  fs.mkdirSync(OUT, { recursive: true })
  for (const [name, s] of Object.entries(sets)) {
    const base = await sharp(SRC).resize(2667, 2000).jpeg({ quality: 96, chromaSubsampling: '4:4:4' }).toBuffer()
    let out = base
    if (s) {
      out = Buffer.from(piexif.insert(piexif.dump(exifObj(s)), base.toString('binary')), 'binary')
    }
    const file = path.join(OUT, `${name}.jpg`)
    fs.writeFileSync(file, out)
    const ex = await require('exifr').parse(out, { pick: ['Make', 'Model', 'FocalLength', 'FNumber', 'ExposureTime', 'ISO'] })
    console.log(name, ex)
  }
})().catch((e) => { console.error(e); process.exit(1) })
