/**
 * A2 part 1 — the artifact the owner judges: _research/a2/crops.png.
 *
 * Per fixture one horizontal strip of four 2x crops:
 *   [ PNG source | sharp q95 | Chromium q95 | Chromium q85 ]
 * taken from the 200x135 window with the largest Chromium-q85 chroma error
 * (chosen in _a2-metrics.cjs). Tiles are upscaled 2x with nearest-neighbour so
 * a 4:2:0 chroma block is 4 screen pixels, not a resampling artefact. Every
 * tile is labelled in the image; the magenta box marks the worst chroma pixel
 * of Chromium q85 when it falls inside the window.
 *
 * The labels are drawn by Chromium (canvas fillText) because sharp's bundled
 * librsvg has no fonts on this box — measured, not assumed.
 *
 * ONE Electron instance, throw-away profile, same launch pattern as e2e.mjs.
 */
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const requireHere = createRequire(__filename)
const { _electron: electron } = requireHere('playwright-core')

const A2 = path.resolve(__dirname, '..', '..', '_research', 'a2')
const PROFILE = path.join(A2, 'tmp', 'crops-profile')
const MATRIX = JSON.parse(fs.readFileSync(path.join(A2, 'quality-matrix.json'), 'utf8'))

const ORDER = ['bars', 'colortext', 'skin', 'detail']

function cellOf(fixture, encoder, q) {
  const c = MATRIX.cells.find((x) => x.fixture === fixture && x.encoder === encoder && x.q === q)
  if (!c) throw new Error(`missing cell ${fixture} ${encoder} q${q}`)
  return c
}

function buildRows() {
  return ORDER.map((fx) => {
    const crop = MATRIX.crops[fx]
    const s95 = cellOf(fx, 'sharp', 95)
    const c95 = cellOf(fx, 'chromium', 95)
    const c85 = cellOf(fx, 'chromium', 85)
    const tiles = ['source', 'sharp-q95', 'chromium-q95', 'chromium-q85'].map((label) => {
      const t = crop.tiles.find((x) => x.label === label)
      const stats =
        label === 'source'
          ? { sub: 'reference', rmse: null }
          : label === 'sharp-q95'
            ? { sub: s95.subsampling, rmse: s95.chromaRmse }
            : label === 'chromium-q95'
              ? { sub: c95.subsampling, rmse: c95.chromaRmse }
              : { sub: c85.subsampling, rmse: c85.chromaRmse }
      return {
        label,
        b64: fs.readFileSync(t.file).toString('base64'),
        sub: stats.sub,
        rmse: stats.rmse,
        worst: label === 'source' || label === 'sharp-q95' ? null : crop.worstPixel[label === 'chromium-q95' ? 'chromiumQ95' : 'chromiumQ85']
      }
    })
    return {
      fixture: fx,
      window: crop.window,
      tiles,
      bytes: {
        sourcePng: MATRIX.fixtures[fx].bytes,
        sharpQ95: s95.bytes,
        chromiumQ95: c95.bytes,
        chromiumQ85: c85.bytes
      }
    }
  })
}

const composeWork = async function (payload) {
  /* eslint-disable no-undef */
  const { rows, env } = payload
  const M = 26
  const GAP = 14
  const TILE_W = 400
  const TILE_H = 270
  const HEAD_H = 44
  const LABEL_H = 34
  const ROW_GAP = 30
  const CAPTION_H = 64
  const W = M * 2 + 4 * TILE_W + 3 * GAP
  const H = M + rows.length * (HEAD_H + LABEL_H + TILE_H + ROW_GAP) + CAPTION_H + M
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const ctx = c.getContext('2d', { alpha: false })
  ctx.fillStyle = '#0f1115'
  ctx.fillRect(0, 0, W, H)

  const load = async (b64) => {
    const bin = Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0))
    return createImageBitmap(new Blob([bin], { type: 'image/png' }))
  }

  let y = M
  for (const row of rows) {
    // fixture heading
    ctx.fillStyle = '#f2f4f8'
    ctx.font = '700 21px "Segoe UI", Arial'
    ctx.textBaseline = 'middle'
    const heading = `${row.fixture}`
    ctx.fillText(heading, M, y + HEAD_H / 2)
    const hw = ctx.measureText(heading).width
    ctx.fillStyle = '#8b93a3'
    ctx.font = '13px Consolas, "Segoe UI", monospace'
    ctx.fillText(
      `${row.window.w}x${row.window.h} px window @ (${row.window.x}, ${row.window.y}) · worst Chromium-q85 chroma window · 2x nearest-neighbour`,
      M + hw + 16,
      y + HEAD_H / 2
    )
    y += HEAD_H

    // tiles
    for (let t = 0; t < row.tiles.length; t++) {
      const tile = row.tiles[t]
      const x = M + t * (TILE_W + GAP)
      const isChrome = tile.label.startsWith('chromium')
      const accent = tile.label === 'source' ? '#9aa4b2' : tile.label.startsWith('sharp') ? '#7ee0a0' : '#ffb26b'
      ctx.fillStyle = accent
      ctx.font = '700 15px "Segoe UI", Arial'
      ctx.textBaseline = 'middle'
      const title =
        tile.label === 'source'
          ? 'PNG source'
          : tile.label === 'sharp-q95'
            ? 'sharp q95'
            : tile.label === 'chromium-q95'
              ? 'Chromium q95'
              : 'Chromium q85'
      ctx.fillText(title, x, y + LABEL_H / 2)
      const tw = ctx.measureText(title).width
      ctx.fillStyle = '#8b93a3'
      ctx.font = '13px Consolas, "Segoe UI", monospace'
      ctx.fillText(
        tile.label === 'source' ? 'reference · lossless' : `${tile.sub} · chromaRMSE ${tile.rmse}`,
        x + tw + 12,
        y + LABEL_H / 2
      )
      y += LABEL_H

      const img = await load(tile.b64)
      ctx.imageSmoothingEnabled = false
      ctx.drawImage(img, x, y, TILE_W, TILE_H)
      ctx.imageSmoothingEnabled = true
      ctx.strokeStyle = isChrome ? '#ff8a3d' : '#2a2f3a'
      ctx.lineWidth = isChrome ? 2 : 1
      ctx.strokeRect(x + 0.5, y + 0.5, TILE_W - 1, TILE_H - 1)

      if (tile.worst) {
        const wx = (tile.worst.x - row.window.x) * 2
        const wy = (tile.worst.y - row.window.y) * 2
        if (wx >= 0 && wy >= 0 && wx < TILE_W && wy < TILE_H) {
          ctx.strokeStyle = '#ff00ff'
          ctx.lineWidth = 2
          ctx.strokeRect(x + wx - 10, y + wy - 10, 20, 20)
        }
      }
      img.close()
      y -= LABEL_H
    }
    y += LABEL_H + TILE_H + ROW_GAP
  }

  ctx.fillStyle = '#6f7684'
  ctx.font = '13px Consolas, "Segoe UI", monospace'
  ctx.textBaseline = 'top'
  ctx.fillText(
    `A2 · JPEG quality vs chroma fidelity · ${env.electron} / Chromium ${env.chrome} · sharp ${env.sharp}`,
    M,
    y + 4
  )
  ctx.fillText(
    'Sources are 1200x800 PNGs. Crops show decoded pixels (both encoders decoded with libjpeg via sharp). Magenta box = worst chroma pixel of Chromium q85.',
    M,
    y + 24
  )
  ctx.fillText(
    'Read the strips, not the numbers: bars/colortext/detail change at q95 already on Chromium; skin barely moves because 4:2:0 only blurs chroma.',
    M,
    y + 44
  )

  const blob = await new Promise((res) => c.toBlob(res, 'image/png'))
  const buf = new Uint8Array(await blob.arrayBuffer())
  let s = ''
  const CH = 0x8000
  for (let i = 0; i < buf.length; i += CH) s += String.fromCharCode.apply(null, Array.from(buf.subarray(i, i + CH)))
  return btoa(s)
  /* eslint-enable no-undef */
}

async function main() {
  fs.mkdirSync(PROFILE, { recursive: true })
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  const sharpMod = requireHere('sharp')
  const sharpVersion = sharpMod.versions.sharp
  const rows = buildRows()

  const app = await electron.launch({
    executablePath: requireHere('electron'),
    args: ['.', `--user-data-dir=${PROFILE}`],
    cwd: path.resolve(__dirname, '..'),
    env,
    timeout: 60000
  })
  try {
    const page = await app.firstWindow()
    await page.waitForSelector('.app-shell.ready', { timeout: 30000 })
    const versions = await app.evaluate(({ app: a }) => ({
      electron: process.versions.electron,
      chrome: process.versions.chrome,
      appVersion: a.getVersion()
    }))
    const b64 = await page.evaluate(composeWork, { rows, env: { ...versions, sharp: sharpVersion } })
    const out = path.join(A2, 'crops.png')
    fs.writeFileSync(out, Buffer.from(b64, 'base64'))
    const meta = await sharpMod(out).metadata()
    console.log('wrote', out, fs.statSync(out).size, 'bytes', meta.width + 'x' + meta.height)
  } finally {
    await app.close().catch(() => undefined)
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
