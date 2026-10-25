// Convert Xiaomi camera watermark JSONs into the shared stock IR (out-style layouts).
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const sharp = require('sharp')

// Source of truth: Xiaomi 17 Ultra NeoHyper (com.android.camera 6.7.000280.0) asset v36.
const ROOT = 'D:/border&watermark/_research/roms/neohyper/apk-assets/assets/watermarks'
const OUT_DIR = 'D:/border&watermark/framemark/src/renderer/src/core/system/generated'
const APP_ASSET_DIR = 'D:/border&watermark/framemark/src/renderer/src/assets/system/xiaomi'

const FAMILY_LABEL = { classic: '经典', film: '胶片', leica: '徕卡', standard: '小米标准', tag: '标签', disney: '迪士尼' }

function dp(v, fallback) {
  if (typeof v === 'number') return v
  if (typeof v === 'string') {
    const m = /^(-?[\d.]+)dp$/.exec(v)
    if (m) return Number(m[1])
  }
  return fallback
}

function gravity(v) {
  const s = String(v ?? '')
  const h = /right/.test(s) ? 'right' : /center_horizontal|horCenter/.test(s) ? 'center' : 'left'
  const vv = /bottom/.test(s) ? 'bottom' : /center_vertical|verticalCenter/.test(s) ? 'center' : 'top'
  if (h === 'right' && vv === 'bottom') return 'rightBottom'
  if (h === 'right' && vv === 'center') return 'rightVerticalCenter'
  if (h === 'right') return 'rightTop'
  if (h === 'center' && vv === 'bottom') return 'centerBottom'
  if (h === 'center' && vv === 'center') return 'center'
  if (h === 'center') return 'topHorCenter'
  if (vv === 'bottom') return 'leftBottom'
  if (vv === 'center') return 'leftVerticalCenter'
  return 'leftTop'
}

// Android spells a whole-axis gravity as the bare token "center" (both axes). The legacy
// gravity() above has no branch for it — it falls through the horizontal/vertical token tests
// (none of "center_horizontal" / "horCenter" / "center_vertical" / "verticalCenter" match the
// lone word "center") and silently becomes "leftTop", de-centring content. frameGravity() fixes
// that root cause (#3) and is applied ONLY to the FrameLayout (orientation:"none") stack path
// below. We keep gravity() itself byte-for-byte legacy so the 17 sibling styles that currently
// render with a "leftTop" container are provably untouched — flipping them to centred in the
// same pass re-centred leica/2, leica/4, film/12 and film/103 by up to 233 reference units.
function frameGravity(v) {
  const s = String(v ?? '').trim()
  if (s === 'center') return 'center'
  return gravity(s)
}

function num(v, fallback = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

function safeName(s) {
  return String(s).replace(/[^A-Za-z0-9_-]+/g, '_')
}

function fontKey(typeface, file) {
  const s = String(typeface ?? '') + ' ' + String(file ?? '')
  if (/oxanium/i.test(s)) return 'oxanium'
  if (/lg1056/i.test(s)) return 'lg1056'
  return 'misans'
}

function weightOf(font) {
  const m = /'wght'\s+(\d+)/.exec(String(font?.variation ?? ''))
  return m ? Math.round(Number(m[1]) / 100) * 100 : 400
}

function colorOf(font) {
  const c = String(font?.color ?? '')
  const m = /^@foreground=(\d+)/.exec(c)
  if (m) return { colorIndex: Number(m[1]) }
  return { color: c.startsWith('#') ? c : '#000000' }
}

function outlineOf(node) {
  const rect = node.rect_params
  if (!rect) return undefined
  const stroke = String(rect.rect_line_color ?? '')
  const strokeIndex = /^@foreground=(\d+)/.exec(stroke)
  const radius = Array.isArray(rect.rect_radius) ? num(rect.rect_radius[0], 0) : num(rect.rect_radius, 0)
  return {
    ...(num(rect.rect_text_width_gap, 0) ? { paddingX: num(rect.rect_text_width_gap, 0) } : {}),
    ...(num(rect.rect_text_height_gap, 0) ? { paddingY: num(rect.rect_text_height_gap, 0) } : {}),
    ...(dp(rect.rect_width) ? { width: dp(rect.rect_width) } : {}),
    ...(dp(rect.rect_height) ? { height: dp(rect.rect_height) } : {}),
    ...(radius ? { radius } : {}),
    strokeWidth: num(rect.rect_line_width, 0),
    ...(strokeIndex ? { strokeColorIndex: Number(strokeIndex[1]) } : stroke.startsWith('#') ? { strokeColor: stroke } : {})
  }
}

function convertText(node, fonts) {
  const text = String(node.text ?? '')
  const outline = outlineOf(node)
  if (!outline && (/^@model=@{versionName}$/.test(text.trim()) || /^@model=@{versionNumber}$/.test(text.trim()))) return null
  let token
  let optional = false
  if (/location_latlng/i.test(text)) {
    token = { kind: 'location' }
    optional = true
  } else if (/@filter=/i.test(text)) {
    token = { kind: 'filter', value: '' }
    optional = true
  } else if (/@custom=/i.test(text)) {
    token = { kind: 'custom' }
    optional = true
  } else if (/@exif=/.test(text) || /@model=/.test(text) || /@time=/.test(text) || /@location=/.test(text)) {
    token = { kind: 'template', value: text }
    if (/@location=/.test(text)) optional = true
  } else {
    token = { kind: 'literal', value: text }
  }
  if (/^@model=@{versionName}$/.test(text.trim())) optional = true
  const f = fonts[node.font_ref] ?? {}
  const col = colorOf(f)
  return {
    kind: 'text',
    token,
    font: fontKey(f.typeface, f.file),
    size: num(f.size, 20),
    weight: weightOf(f),
    ...col,
    alpha: 1,
    tracking: num(f.letterSpacing, 0),
    align: outline ? 'center' : 'left',
    ...(outline ? { outline } : {}),
    margins: {
      ...(dp(node.margin_left) ? { left: dp(node.margin_left) } : {}),
      ...(dp(node.margin_top) ? { top: dp(node.margin_top) } : {}),
      ...(dp(node.margin_right) ? { right: dp(node.margin_right) } : {}),
      ...(dp(node.margin_bottom) ? { bottom: dp(node.margin_bottom) } : {})
    },
    optional
  }
}

const LOGO_BRANDS = ['xiaomi', 'redmi', 'poco']
const LOGO_EXTS = ['.webp', '.png']

function findLogoFile(ctx, entry) {
  if (!entry) return null
  const suffix = String(entry).trim().replace(/^#/, '')
  for (const brand of LOGO_BRANDS) {
    for (const ext of LOGO_EXTS) {
      const name = `${brand}_${suffix}${ext}`
      if (fs.existsSync(path.join(ctx.styleDir, name))) return name
    }
  }
  return null
}

function fallbackLogoFile(ctx, dark) {
  const tones = dark ? ['white', 'black'] : ['black', 'white']
  for (const tone of tones) {
    for (const brand of LOGO_BRANDS) {
      for (const ext of LOGO_EXTS) {
        const name = `${brand}_${tone}${ext}`
        if (fs.existsSync(path.join(ctx.styleDir, name))) return name
      }
    }
  }
  return null
}

function themeIsDark(theme) {
  const bg = String(theme?.background ?? '')
  const m = /^#(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})(?:[;,]|$)/.exec(bg)
  if (m) {
    const v = m[1]
    return (parseInt(v.slice(0, 2), 16) * 0.299 + parseInt(v.slice(2, 4), 16) * 0.587 + parseInt(v.slice(4, 6), 16) * 0.114) / 255 < 0.5
  }
  return /dark|black/.test(String(theme?.id ?? ''))
}

async function convertImage(node, ctx) {
  const pathStr = String(node.path ?? '')
  const isBrandLogo = /pathType=fill;path=\$\{logo\}/.test(pathStr)
  const m = /pathType=direct;path=([^;]+)/.exec(pathStr)
  const fgIndex = /foreground=(\d+)/.exec(pathStr)
  let file
  let asset
  let themeAssets
  let fileAsset
  if (m) {
    file = m[1]
    asset = `${ctx.family}-${ctx.id}-${safeName(path.basename(file, path.extname(file)))}`
  } else if (isBrandLogo) {
    const index = fgIndex ? Number(fgIndex[1]) : null
    const mapping = {}
    fileAsset = new Map()
    for (const theme of ctx.themes) {
      const entry = index != null ? theme.foreground?.[index] : undefined
      const chosen = findLogoFile(ctx, entry) ?? fallbackLogoFile(ctx, themeIsDark(theme))
      if (!chosen) continue
      if (!fileAsset.has(chosen)) {
        const key = `${ctx.family}-${ctx.id}-logo-${safeName(path.basename(chosen, path.extname(chosen)))}`
        fileAsset.set(chosen, key)
      }
      mapping[theme.id] = fileAsset.get(chosen)
    }
    const defaultEntry = index != null
      ? ctx.themes.find((t) => t.id === ctx.defaultTheme)?.foreground?.[index]
      : undefined
    file = findLogoFile(ctx, defaultEntry)
      ?? fileAsset.keys().next().value
      ?? fallbackLogoFile(ctx, ctx.darkBg)
    if (!file) return null
    asset = fileAsset.get(file) ?? `${ctx.family}-${ctx.id}-logo-${safeName(path.basename(file, path.extname(file)))}`
    themeAssets = Object.keys(mapping).length ? mapping : undefined
  } else {
    return null
  }
  const dir = path.join(APP_ASSET_DIR, ctx.family, ctx.id)
  fs.mkdirSync(dir, { recursive: true })
  const register = async (name, key) => {
    const src = path.join(ctx.styleDir, name)
    if (!fs.existsSync(src)) return null
    fs.copyFileSync(src, path.join(dir, name))
    const meta = await sharp(src).metadata().catch(() => null)
    const rel = `xiaomi/${ctx.family}/${ctx.id}/${name}`
    ctx.assets[key] = { file: rel, width: meta?.width ?? 0, height: meta?.height ?? 0 }
    return meta
  }
  const primaryMeta = await register(file, asset)
  if (!primaryMeta && !fs.existsSync(path.join(ctx.styleDir, file))) return null
  if (themeAssets) {
    for (const [name, key] of fileAsset) {
      if (key === asset) continue
      await register(name, key)
    }
  }
  const src = path.join(ctx.styleDir, file)
  const meta = await sharp(src).metadata().catch(() => null)
  const h = dp(node.display_height, 0)
  const w = dp(node.display_width, 0)
  const ratio = meta?.width && meta?.height ? meta.width / meta.height : 1
  const height = h > 0 ? h : w > 0 ? w / ratio : (meta?.height ?? 20)
  const width = w > 0 ? w : height * ratio
  return {
    kind: 'image',
    asset,
    ...(themeAssets ? { themeAssets } : {}),
    ...(fgIndex ? { colorIndex: Number(fgIndex[1]) } : {}),
    width,
    height,
    scale: 'contain',
    isBrandLogo,
    gravity: node.gravity ? gravity(node.gravity) : undefined,
    margins: {
      ...(dp(node.margin_left) ? { left: dp(node.margin_left) } : {}),
      ...(dp(node.margin_top) ? { top: dp(node.margin_top) } : {}),
      ...(dp(node.margin_right) ? { right: dp(node.margin_right) } : {}),
      ...(dp(node.margin_bottom) ? { bottom: dp(node.margin_bottom) } : {}),
      ...(isBrandLogo && !dp(node.margin_right) ? { right: 8 } : {})
    }
  }
}

function applyBrandLogo(children) {
  const hasLogo = children.some((c) => c.kind === 'image' && c.isBrandLogo)
  if (!hasLogo) return
  for (const c of children) {
    if (c.kind !== 'text' || c.token?.kind !== 'template') continue
    let v = String(c.token.value ?? '')
    v = v.replace(/@model=\s*@\{series\}@\{versionNumber\}@\{versionName\}/g, '@model=@{versionNumber}')
    v = v.replace(/@model=\s*@\{series\}@\{versionNumber\}/g, '@model=@{versionNumber}')
    v = v.replace(/@model=\s*@\{series\}/g, '@model=')
    c.token = { ...c.token, value: v }
  }
}

// Mirror of layout.ts intrinsic().h for the subset of IR nodes the Xiaomi adapter emits, so the
// adapter can translate a vendor FrameLayout's absolute offsets into flow gaps that the real solver
// will land back on the same y. Uses single-line text height (size*1.2, or an outline's fixed
// height), image/shape node.height, and the row=max / column=sum rule with child margins — the
// same accumulation layout.ts:174-196 performs.
function intrinsicHeight(n) {
  if (!n) return 0
  if (n.kind === 'image' || n.kind === 'shape') return n.height || 20
  if (n.kind === 'space') return n.height || 0
  if (n.kind === 'text') return n.outline?.height ?? n.size * 1.2
  if (n.kind === 'group') {
    if (n.height) return n.height
    const ch = n.children || []
    const my = (c) => dp(c.margins?.top, 0) + dp(c.margins?.bottom, 0)
    if (n.orientation === 'row') return Math.max(0, ...ch.map((c) => intrinsicHeight(c) + my(c)))
    return ch.reduce((a, c) => a + intrinsicHeight(c) + my(c), 0) + Math.max(0, ch.length - 1) * (n.gap || 0)
  }
  return 0
}

// Absolute-offset presence: "0dp" is a valid offset, so we key off the attribute existing, not truthy.
function hasAbsTop(child) {
  return child && child.margin_top !== undefined && child.margin_top !== null && dp(child.margin_top, NaN) === dp(child.margin_top, 0)
}

async function convertNode(node, ctx) {
  if (!node) return null
  if (node.type === 'TextView') return convertText(node, ctx.fonts)
  if (node.type === 'ImageView') return convertImage(node, ctx)
  if (node.type !== 'Layout') return null
  const raw = node.childViews ?? []
  const pairs = []
  const children = []
  for (const c of raw) {
    const n = await convertNode(c, ctx)
    if (n) {
      children.push(n)
      pairs.push({ raw: c, node: n })
    }
  }
  if (!children.length) {
    // Vendor spacer: an empty wrap_content Layout that only carries a horizontal margin is a
    // fixed gap, not dead weight (leica/92 `space_layout`, margin_left 40dp, sits between the
    // Leica roundel and the EXIF column; dropping it made the two touch at dx = 0).
    const gap = dp(node.margin_left, 0) + dp(node.margin_right, 0)
    if (node.orientation === 'horizontal' && String(node.layout_width) === 'wrap_content' && gap > 0) {
      return { kind: 'space', width: gap }
    }
    return null
  }
  applyBrandLogo(children)

  // --- FrameLayout vertical-rhythm stack (orientation:"none") -------------------------------
  // Android's FrameLayout puts every child at the PARENT origin and lets its own margin + gravity
  // position it; a child with margin_top:157.5 sits 157.5 dp from the top, NOT below its sibling.
  // Mis-parsing this as a column makes layout.ts:354-357 ACCUMULATE those absolute offsets
  // (50 -> 281.5 -> 508.5), which overflows the 302 dp band and trips fitBandItems() into rescaling
  // the whole thing to ~0.58 (leica/111). When — and only when — the children form a genuinely
  // stacked, non-overlapping, top-to-bottom rhythm we keep the column but convert each child's
  // absolute margin_top into the inter-child gap the column flow needs:
  //     gap_i = offset_i - (offset_{i-1} + height_{i-1})    (gap_0 = offset_0)
  // so the solver lands each child back on its true absolute y. We also honour the child's own
  // layout_gravity (dropped for the whole family before) so the roundel/exif centre as intended.
  // Overlapping frames (leica/92's left/right split, whose children share the same y) and every
  // non-none container fall through to the untouched legacy path below, protecting the 17 siblings.
  if (node.orientation === 'none' && pairs.length >= 2 && pairs.every((p) => hasAbsTop(p.raw))) {
    const offs = pairs.map((p) => dp(p.raw.margin_top, 0))
    const stack = offs.every((o, i) => i === 0 || (o > offs[i - 1] && o >= offs[i - 1] + intrinsicHeight(pairs[i - 1].node)))
    if (stack) {
      const placed = pairs.map((p, i) => {
        const top = i === 0 ? offs[i] : offs[i] - offs[i - 1] - intrinsicHeight(pairs[i - 1].node)
        const childMargins = { ...(p.node.margins || {}) }
        if (top > 0) childMargins.top = top
        else delete childMargins.top
        const childGravity = p.raw.layout_gravity ? frameGravity(p.raw.layout_gravity) : p.node.gravity
        return { ...p.node, margins: childMargins, ...(childGravity ? { gravity: childGravity } : {}) }
      })
      const w = dp(node.layout_width, undefined)
      const h = dp(node.layout_height, undefined)
      ctx.unsupported.add(`${ctx.family}/${ctx.id}: FrameLayout (orientation:none) vertical-rhythm stack translated to inter-child gaps`)
      return {
        kind: 'group',
        orientation: 'column',
        width: w,
        height: h,
        matchWidth: node.layout_width === 'match_parent',
        matchHeight: node.layout_height === 'match_parent',
        gravity: frameGravity(node.gravity),
        margins: {
          ...(dp(node.margin_left) ? { left: dp(node.margin_left) } : {}),
          ...(dp(node.margin_top) ? { top: dp(node.margin_top) } : {}),
          ...(dp(node.margin_right) ? { right: dp(node.margin_right) } : {}),
          ...(dp(node.margin_bottom) ? { bottom: dp(node.margin_bottom) } : {})
        },
        children: placed
      }
    }
  }

  // --- legacy path (unchanged for all non-stack containers, incl. overlapping none frames) ---
  const orientation = node.orientation === 'horizontal' ? 'row' : 'column'
  const w = dp(node.layout_width, undefined)
  const h = dp(node.layout_height, undefined)
  return {
    kind: 'group',
    orientation,
    width: w,
    height: h,
    matchWidth: node.layout_width === 'match_parent',
    matchHeight: node.layout_height === 'match_parent',
    gravity: gravity(node.gravity),
    margins: {
      ...(dp(node.margin_left) ? { left: dp(node.margin_left) } : {}),
      ...(dp(node.margin_top) ? { top: dp(node.margin_top) } : {}),
      ...(dp(node.margin_right) ? { right: dp(node.margin_right) } : {}),
      ...(dp(node.margin_bottom) ? { bottom: dp(node.margin_bottom) } : {})
    },
    children
  }
}

function containsPhoto(node) {
  if (!node) return false
  if (node.type === 'ImageView' && node.id === 'src_image_view') return true
  return (node.childViews ?? []).some(containsPhoto)
}

function nodeHeight(node) {
  if (!node) return 0
  const h = dp(node.layout_height, 0)
  if (h > 0) return h
  if (node.type === 'TextView' || node.type === 'ImageView') return 24
  if (node.type === 'Layout') {
    if (node.orientation === 'vertical') return (node.childViews ?? []).reduce((n, c) => n + nodeHeight(c), 0)
    return Math.max(0, ...(node.childViews ?? []).map(nodeHeight))
  }
  return 0
}

const BASE_W = 1080

function widthFromLayout(value) {
  const s = String(value ?? '')
  const pct = /^([\d.]+)%w$/.exec(s)
  if (pct) return (BASE_W * Number(pct[1])) / 100
  if (s === 'match_parent') return BASE_W
  const n = dp(value, 0)
  return n > 0 ? n : undefined
}

function overlayMargins(node) {
  const g = String(node?.gravity ?? '')
  const out = {}
  for (const key of ['left', 'top', 'right', 'bottom']) {
    const n = dp(node?.[`margin_${key}`], 0)
    if (!n) continue
    if (n > 0) out[key] = n
    else if (key === 'top' && /bottom/.test(g)) out.bottom = -n
    else if (key === 'bottom' && /top/.test(g)) out.top = -n
    else if (key === 'left' && /right/.test(g)) out.right = -n
    else if (key === 'right' && /left/.test(g)) out.left = -n
  }
  return out
}

function findNode(node, match) {
  if (!node) return null
  if (match(node)) return node
  for (const child of node.childViews ?? []) {
    const found = findNode(child, match)
    if (found) return found
  }
  return null
}

async function convertOverlayContainer(container, ctx) {
  if (!container) return null
  const children = []
  for (const child of container.childViews ?? []) {
    const converted = await convertNode(child, ctx)
    if (converted) children.push(converted)
  }
  if (!children.length) return null
  // Android hands a `match_parent` child of a horizontal LinearLayout the *remaining* width.
  // Our row solver instead anchors left-gravity children to the band's left edge and
  // right-gravity children to its right edge, so a child that claims the whole band width
  // collapses that packing: it is placed at x = 0 and every right-anchored sibling after it
  // is pushed off-canvas, then fitBandItems() rescales the band (leica/91 dropped the EXIF
  // block onto the date run's baseline). Inside a multi-child overlay row these groups are
  // therefore emitted as wrap_content; their own `right` gravity still anchors the content.
  const isRow = container.orientation !== 'vertical'
  if (isRow && children.length > 1) {
    for (const c of children) {
      if (c.kind === 'group' && c.matchWidth) {
        c.matchWidth = false
        delete c.width
      }
    }
  }
  const width = widthFromLayout(container.layout_width)
  const height = dp(container.layout_height, 0)
  return {
    kind: 'group',
    orientation: container.orientation === 'vertical' ? 'column' : 'row',
    ...(width ? { width } : {}),
    ...(height > 0 ? { height } : {}),
    gravity: gravity(container.gravity),
    margins: overlayMargins(container),
    children
  }
}

async function convertInStyle(cfg, ctx) {
  const groups = cfg.layout_group ?? []
  const order = ['layout', 'bottom', 'bottom_left', 'center_top', 'top']
  let root = null
  let key = ''
  for (const group of groups) {
    for (const k of order) {
      if (group[k]?.[0]) {
        root = group[k][0]
        key = k
        break
      }
    }
    if (root) break
  }
  if (!root) return { blocked: 'no supported in-style root' }
  const container = findNode(root, (n) => n.id === 'watermark_container')
  if (!container) return { blocked: 'no watermark container' }
  const overlay = await convertOverlayContainer(container, ctx)
  if (!overlay) return { blocked: 'empty watermark container' }
  ctx.unsupported.add(`in-style overlay anchored using ${key} layout`)
  if (groups.length > 1) ctx.unsupported.add(`${groups.length} anchor presets in config; default ${key} used`)
  return { overlay, anchor: key }
}

function bandChildren(children) {
  const out = children.filter(Boolean)
  if (out.length >= 2 && !out.some((c) => c.kind === 'space')) {
    return [out[0], { kind: 'space', weight: 1 }, ...out.slice(1)]
  }
  return out
}

async function convertRoot(root, ctx) {
  const children = root.childViews ?? []
  const photoIndex = children.findIndex((c) => c.type === 'ImageView' && c.id === 'src_image_view')
  let topNodes = []
  let bottomNodes = []
  let leftPad = 0
  let rightPad = 0
  if (root.orientation !== 'horizontal') {
    const hostIndex = children.findIndex(containsPhoto)
    if (hostIndex < 0) return null
    topNodes = children.slice(0, hostIndex)
    bottomNodes = children.slice(hostIndex + 1)
    const host = children[hostIndex]
    if (host.type === 'Layout' && host.orientation === 'horizontal') {
      const inner = host.childViews ?? []
      const idx = inner.findIndex((c) => c.type === 'ImageView' && c.id === 'src_image_view')
      if (idx >= 0) {
        leftPad = inner.slice(0, idx).reduce((n, c) => n + dp(c.layout_width, 0), 0)
        rightPad = inner.slice(idx + 1).reduce((n, c) => n + dp(c.layout_width, 0), 0)
      }
    }
  } else {
    const photoChildIndex = children.findIndex((c) => c.childViews?.some((x) => x.id === 'src_image_view'))
    if (photoChildIndex < 0) return null
    leftPad = children.slice(0, photoChildIndex).reduce((n, c) => n + dp(c.layout_width, 0), 0)
    rightPad = children.slice(photoChildIndex + 1).reduce((n, c) => n + dp(c.layout_width, 0), 0)
    const inner = children[photoChildIndex]
    const innerChildren = inner.childViews ?? []
    const idx = innerChildren.findIndex((c) => c.id === 'src_image_view')
    if (idx < 0) return null
    topNodes = innerChildren.slice(0, idx)
    bottomNodes = innerChildren.slice(idx + 1)
  }
  const topThick = topNodes.reduce((n, c) => n + nodeHeight(c), 0)
  const bottomThick = bottomNodes.reduce((n, c) => n + nodeHeight(c), 0)
  const bands = []
  const topContent = []
  for (const n of topNodes) {
    const c = await convertNode(n, ctx)
    if (c) topContent.push(c)
  }
  if (topContent.length) bands.push({ direction: 'top', thickness: Math.max(1, topThick), children: bandChildren(topContent) })
  const bottomContent = []
  for (const n of bottomNodes) {
    const c = await convertNode(n, ctx)
    if (c) bottomContent.push(c)
  }
  if (bottomContent.length) bands.push({ direction: 'bottom', thickness: Math.max(1, bottomThick), children: bandChildren(bottomContent) })
  if (!bands.length) return null
  return { bands, margins: { left: leftPad, top: topThick, right: rightPad, bottom: bottomThick }, photoIndex }
}

function themeColor(bg) {
  const v = String(bg?.value ?? '')
  if (v.startsWith('@color=')) return v.slice(7)
  if (v.startsWith('@algo=')) return '#00000000'
  return '#FFFFFF'
}

async function convertStyle(family, id) {
  const styleDir = path.join(ROOT, family, id)
  const cfgPath = path.join(styleDir, 'config.json')
  const metaPath = path.join(styleDir, 'meta.json')
  if (!fs.existsSync(cfgPath)) return null
  const raw = fs.readFileSync(cfgPath)
  const cfg = JSON.parse(raw.toString('utf8').replace(/^\uFEFF/, ''))
  const meta = fs.existsSync(metaPath) ? JSON.parse(fs.readFileSync(metaPath, 'utf8').replace(/^\uFEFF/, '')) : {}
  const position = meta.metadata?.position
  const backgrounds = cfg.wmRes?.background ?? []
  const themes = backgrounds.map((b) => ({
    id: String(b.name),
    label: String(b.name),
    swatch: themeColor(b),
    background: themeColor(b),
    foreground: Array.isArray(b.foreground) ? b.foreground : []
  }))
  const defaultTheme = themes[0]?.id ?? 'default'
  const defaultBg = themes.find((t) => t.id === defaultTheme)?.background ?? '#FFFFFF'
  const rgb = /^#([0-9a-fA-F]{6})/.exec(defaultBg.replace(/^#FF/i, '#'))
  const lum = rgb ? (parseInt(rgb[1].slice(0, 2), 16) * 0.299 + parseInt(rgb[1].slice(2, 4), 16) * 0.587 + parseInt(rgb[1].slice(4, 6), 16) * 0.114) / 255 : 1
  const ctx = { family, id, styleDir, fonts: cfg.wmRes?.fonts ?? {}, assets: {}, unsupported: new Set(), darkBg: lum > 0.5, themes, defaultTheme }
  const label = FAMILY_LABEL[family] ?? family
  const styleBase = {
    id: `xiaomi-${family}:${id}`,
    vendor: 'xiaomi',
    family: '小米相机',
    name: `${label} · ${id}`,
    nameEn: `${family}_${id}`,
    referenceWidth: 1080,
    themes,
    defaultTheme,
    assets: ctx.assets
  }

  if (position === 'in') {
    const converted = await convertInStyle(cfg, ctx)
    if (!converted.overlay) return { blocked: converted.blocked ?? 'in-style overlay conversion failed' }
    return {
      style: {
        ...styleBase,
        placement: 'in',
        margins: { left: 0, top: 0, right: 0, bottom: 0 },
        background: '#00000000',
        transparentBackground: true,
        bands: [{ direction: 'overlay', children: [converted.overlay] }],
        audit: {
          status: 'approx',
          notes: `In-style overlay (${converted.anchor}) converted from Xiaomi config; pending visual audit.`,
          ...(ctx.unsupported.size ? { unsupported: [...ctx.unsupported] } : {})
        }
      }
    }
  }

  const root = cfg.layout_group?.[0]?.layout?.[0] ?? cfg.layout_group?.[0]?.top?.[0]
  if (!root) return { blocked: 'no supported root layout' }
  const converted = await convertRoot(root, ctx)
  if (!converted) return { blocked: 'unsupported root/photo layout' }
  return {
    style: {
      ...styleBase,
      placement: converted.margins.left || converted.margins.right ? 'with' : 'out',
      margins: converted.margins,
      background: themes.find((t) => t.id === defaultTheme)?.background ?? '#FFFFFF',
      bands: converted.bands,
      audit: { status: 'approx', notes: 'Auto-converted from Xiaomi camera config; needs visual audit.' }
    }
  }
}

async function main() {
  const styles = []
  const blocked = []
  for (const family of fs.readdirSync(ROOT).filter((f) => fs.statSync(path.join(ROOT, f)).isDirectory())) {
    for (const id of fs.readdirSync(path.join(ROOT, family)).filter((f) => /^\d+$/.test(f)).sort((a, b) => Number(a) - Number(b))) {
      const r = await convertStyle(family, id)
      if (r?.style) styles.push(r.style)
      else if (r?.blocked) blocked.push(`${family}/${id}: ${r.blocked}`)
    }
  }
  const tintText = (style, predicate) => {
    const walk = (n) => {
      if (!n || typeof n !== 'object') return
      if (n.kind === 'text' && predicate(n)) n.color = '#FFFFFF'
      for (const c of n.children ?? []) walk(c)
    }
    for (const band of style.bands ?? []) for (const child of band.children ?? []) walk(child)
  }
  for (const style of styles) {
    if (style.id === 'xiaomi-film:12' || style.id === 'xiaomi-film:103') {
      style.defaultTheme = 'transparent'
      style.background = '#FF121212'
    }
    if (style.id === 'xiaomi-classic:71' || style.id === 'xiaomi-classic:97') {
      tintText(style, (n) => n.token?.kind === 'template' && /@model=/.test(n.token.value ?? ''))
      for (const theme of style.themes ?? []) {
        theme.foreground = theme.foreground.map(() => '#FFFFFFFF')
      }
    }
    if (style.id === 'xiaomi-tag:10' || style.id === 'xiaomi-tag:11') {
      tintText(style, (n) => n.token?.kind === 'template' && /@wm_time_(HH_mm|MM|dd)/.test(n.token.value ?? ''))
    }
    if (style.id === 'xiaomi-film:15') {
      const walk = (n) => {
        if (!n || typeof n !== 'object') return
        if (n.kind === 'text' && /@exif=/.test(n.token?.value ?? '')) n.margins = { ...(n.margins ?? {}), top: 7 }
        for (const c of n.children ?? []) walk(c)
      }
      for (const band of style.bands ?? []) for (const child of band.children ?? []) walk(child)
    }
    if (style.id === 'xiaomi-leica:92') {
      // leica/92's EXIF block is a vertical stack of two fixed-height (16dp) FrameLayout rows:
      // `exif_mm_layout` (the `@{focal}mm` line) over `exif_layout` (the F/S/ISO line). Android
      // centres each line in its frame via layout_gravity=center_vertical, so the 20dp `366mm`
      // (24dp box) overflows its 16dp frame symmetrically (top -4) and clears the row below.
      // convertText drops layout_gravity, so our column pinned `366mm` to the frame top and its
      // `mm` descender collided with the `F 6.3 S 1/400 ISO 125` row (drawn-rect overlap 4dp).
      // Re-attach the vertical centre on that one focal line so it lands where the vendor puts
      // it; the sibling F/S/ISO row already carries its own leftVerticalCenter gravity. Scoped to
      // this style so the 17 fixed-height-frame siblings keep their byte-identical solved rects.
      const walk = (n) => {
        if (!n || typeof n !== 'object') return
        if (n.kind === 'text' && n.token?.kind === 'template' && /@\{focal\}mm/.test(n.token.value ?? '')) {
          n.gravity = 'leftVerticalCenter'
        }
        for (const c of n.children ?? []) walk(c)
      }
      for (const band of style.bands ?? []) for (const child of band.children ?? []) walk(child)
    }
  }
  fs.mkdirSync(OUT_DIR, { recursive: true })
  fs.writeFileSync(
    path.join(OUT_DIR, 'xiaomiStyles.ts'),
    `import type { StockStyle } from '../ir'\n\nexport const XIAOMI_STYLES = ${JSON.stringify(styles, null, 2)} as StockStyle[]\n`
  )
  const variants = styles.map((s) => ({
    id: `stock-${s.id.replace(/[:]/g, '-')}`,
    name: s.name,
    en: s.nameEn,
    category: '系统相机',
    base: 'systemCamera',
    params: { systemStyle: s.id },
    text: { showMake: true, showModel: true, showExif: true, showDate: true, showLogo: true },
    badge: '近似',
    vendor: 'xiaomi',
    family: s.family,
    fidelity: s.audit.status
  }))
  fs.writeFileSync(
    path.join(OUT_DIR, 'xiaomiVariants.ts'),
    `import type { TemplateVariant } from '../../../types'\n\nexport const XIAOMI_VARIANTS = ${JSON.stringify(variants, null, 2)} as TemplateVariant[]\n`
  )
  console.log('styles', styles.length, 'blocked', blocked.length)
  console.log('BLOCKED\n' + blocked.join('\n'))
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
