/**
 * S0 Gate A fixture: a 3000x2000 JPEG with NO EXIF at all.
 * Hard requirement of the gate: `exifr`/`sharp` must both see zero metadata, so
 * the paramStack template can only fall back to its own placeholders.
 */
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')

const root = path.resolve(__dirname, '..')
const outDir = path.join(root, '..', '_research', 's0')
const outFile = path.join(outDir, 'nonexif.jpg')
const W = 3000
const H = 2000

async function main() {
  fs.mkdirSync(outDir, { recursive: true })
  const px = Buffer.alloc(W * H * 3)
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const u = x / (W - 1)
      const v = y / (H - 1)
      // Photo-like gradient: deep teal -> warm sand. Capped well below white so
      // the pure-white param text is trivially separable in the parity check.
      let r = 24 + 150 * u + 18 * Math.sin(v * 6.2)
      let g = 52 + 70 * u + 34 * v
      let b = 78 + 20 * (1 - u) + 46 * Math.sin(u * 4.1)
      // two soft off-white-free "clouds" so plain colour alone cannot hide text
      const dx = u - 0.62
      const dy = v - 0.36
      const d = Math.sqrt(dx * dx + dy * dy)
      if (d < 0.22) {
        const k = (1 - d / 0.22) * 26
        r += k
        g += k
        b += k * 0.6
      }
      // a diagonal band, keeps the grader from relying on a single flat tone
      if (Math.abs(u - v * 0.8 - 0.12) < 0.012) {
        r += 14
        g += 14
        b += 10
      }
      const i = (y * W + x) * 3
      px[i] = Math.max(0, Math.min(215, Math.round(r)))
      px[i + 1] = Math.max(0, Math.min(215, Math.round(g)))
      px[i + 2] = Math.max(0, Math.min(215, Math.round(b)))
    }
  }
  await sharp(px, { raw: { width: W, height: H, channels: 3 } })
    .jpeg({ quality: 94, chromaSubsampling: '4:4:4' })
    .toFile(outFile)

  const meta = await sharp(outFile).metadata()
  const report = {
    file: outFile,
    format: meta.format,
    width: meta.width,
    height: meta.height,
    exif: meta.exif ? meta.exif.length : 0,
    icc: Boolean(meta.icc),
    hasProfile: Boolean(meta.hasProfile)
  }
  fs.writeFileSync(path.join(outDir, 'nonexif.metadata.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
  if (meta.exif) {
    console.error('FAIL fixture carries EXIF')
    process.exit(1)
  }
}

main().catch((e) => {
  console.error('FAIL', e)
  process.exit(1)
})
