import type { Design } from '../types'

/**
 * S8 — the ONE encode policy.
 *
 * Before this file the desktop (`src/main/index.ts` → sharp) and Android
 * (`exportAndroid` → canvas.toBlob → Chromium) ends each carried their own
 * encoder settings, so "the same export settings" were only nominally the same.
 * The measured facts behind every number here are in
 * `_research/s8/encoder-facts.json`; the cross-end GAPs are in
 * `_research/handoff/S8.md`.
 *
 * The main process cannot import this module (separate bundle, no shared
 * source tree allowed by this slice), so `src/main/index.ts` carries a mirror
 * block with the same values inside `S8-POLICY-BEGIN/END` markers.
 * `scripts/_s8-policy-check.cjs` parses both blocks and fails the build gate if
 * they drift, so the numbers are still traceable to one described policy.
 *
 * Rule: never trade quality for parity. Where the platform forces a lower
 * quality (Chromium JPEG subsampling below q100), it is declared as a GAP in
 * `subsamplingAndroid` — not silently normalised away.
 */

export type ExportFormat = Design['export']['format']

export type WebpKind = 'lossless' | 'lossy' | 'unknown'

/* S8-POLICY-BEGIN */
export const ENCODE_PROFILE = {
  formats: {
    jpeg: {
      mime: 'image/jpeg',
      extension: '.jpg',
      /** 'design' = the user's 60..100 slider; null = codec default (quality not applicable). */
      quality: 'design',
      /** canvas.toBlob's quality argument: 'design' => design.export.quality / 100, else a fixed 0..1. */
      androidQuality: 'design',
      /** Desktop sharp pins this at every quality. */
      subsampling: '4:4:4',
      /** Measured on Chromium 142 (Electron 45): 4:4:4 at q100 only, 4:2:0 at q99..q60. Not controllable. */
      subsamplingAndroid: 'chromium-uncontrolled',
      lossless: false,
      /** Colour profile the export must carry. */
      icc: 'sRGB',
      /** What the Android encoder actually does (measured, not assumed). */
      iccAndroid: 'embedded-by-encoder',
      android: 'supported',
      androidMessage: null,
      /** EXIF payload is only written into JPEG. */
      exif: true
    },
    png: {
      mime: 'image/png',
      extension: '.png',
      quality: null,
      androidQuality: null,
      subsampling: 'none',
      subsamplingAndroid: 'none',
      lossless: true,
      icc: 'sRGB',
      iccAndroid: 'absent',
      android: 'supported',
      androidMessage: null,
      exif: false
    },
    webp: {
      mime: 'image/webp',
      extension: '.webp',
      quality: null,
      /** Quality 1 makes Chromium emit a VP8L (lossless) bitstream — measured. */
      androidQuality: 1,
      subsampling: 'none',
      subsamplingAndroid: 'none',
      lossless: true,
      icc: 'sRGB',
      iccAndroid: 'embedded-by-encoder',
      android: 'supported',
      androidMessage: null,
      exif: false
    },
    tiff: {
      mime: 'image/tiff',
      extension: '.tiff',
      quality: null,
      androidQuality: null,
      subsampling: 'none',
      subsamplingAndroid: 'none',
      lossless: true,
      icc: 'sRGB',
      iccAndroid: 'n/a',
      android: 'unsupported',
      /**
       * A3: also thrown by the plain-web branch (core/exporter.ts) when a TIFF
       * export reaches it, so the wording names no single platform.
       */
      androidMessage: '当前平台不支持 TIFF 导出（TIFF 仅桌面版提供，LZW 无损）。请改用 PNG 无损导出。',
      exif: false
    }
  },
  /**
   * longEdge is applied ONCE by exportOne (canvas downscale, high smoothing)
   * before the platform branch, so both ends share dimensions. Desktop sharp
   * additionally resizes the PNG inside export:save; the kernels differ, so the
   * pixels differ at longEdge > 0 — a measured GAP, not a bug to "fix" here.
   */
  longEdge: {
    appliedBeforeEncode: true,
    androidResampler: 'canvas-drawImage-high',
    desktopResampler: 'sharp-lanczos3',
    sameDimensions: true
  },
  /** EXIF is injected with piexif on both ends; only JPEG carries it. */
  exif: {
    formats: ['jpeg'],
    android: 'piexif-insert-jpeg-only',
    desktop: 'piexif-insert-with-fallback'
  },
  /** GPS is stripped by default on both ends; only JPEG/EXIF can carry it. */
  gps: {
    removeByDefault: true,
    formats: ['jpeg']
  }
} as const
/* S8-POLICY-END */

/**
 * Walk the RIFF chunk list of a WebP blob and report the bitstream kind.
 * `canvas.toBlob('image/webp', 1)` was measured to be VP8L, but the encoder is
 * whatever WebView ships on the device — the guard turns a silent lossy fallback
 * into a hard export error, per the "never trade quality" rule.
 *
 * Only the first 64 KiB are read; ICCP precedes VP8L in Chromium's output. If the
 * bitstream is beyond the sample (or the header is not RIFF/WEBP) the answer is
 * 'unknown' and the caller must not fail on it.
 */
export async function webpBitstreamKind(blob: Blob): Promise<WebpKind> {
  const head = new Uint8Array(await blob.slice(0, 65536).arrayBuffer())
  if (head.length < 16) return 'unknown'
  const fourcc = (o: number): string => String.fromCharCode(head[o], head[o + 1], head[o + 2], head[o + 3])
  if (fourcc(0) !== 'RIFF' || fourcc(8) !== 'WEBP') return 'unknown'
  let i = 12
  while (i + 8 <= head.length) {
    const id = fourcc(i)
    const len = head[i + 4] | (head[i + 5] << 8) | (head[i + 6] << 16) | (head[i + 7] << 24)
    if (id === 'VP8L') return 'lossless'
    if (id === 'VP8 ') return 'lossy'
    if (len < 0) return 'unknown'
    i += 8 + len + (len & 1)
  }
  return 'unknown'
}

/** Error text when the platform's WebP encoder hands back a lossy bitstream. */
export const ANDROID_WEBP_LOSSY = '安卓 WebP 编码器返回了有损码流，已中止导出（不降画质）。请改用 PNG 无损导出。'
