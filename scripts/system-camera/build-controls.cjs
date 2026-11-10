#!/usr/bin/env node
/**
 * build-controls.cjs — FrameMark 系统相机逐套差异化编辑控件：取数 / 生成 / 机检
 *
 *   node build-controls.cjs --extract            # 生成 staging/ir-index.json + staging/vendor-detail.json
 *   node build-controls.cjs --baseline=xiaomi    # 生成 staging/controls.xiaomi.json（oppo / vivo 同）
 *   node build-controls.cjs --publish            # 把 staging/controls.*.json 投放到原生产物自有目录
 *   node build-controls.cjs --diff               # 小米 厂商optional vs IR optional 差异表
 *   node build-controls.cjs --rollout            # staging/rollout-plan.json（每批 <=5 套）
 *   node build-controls.cjs --validate           # 机检 staging/controls.*.json，非 0 退出码
 *   node build-controls.cjs --selftest           # 注入坏记录证明校验器会报错（跑完自动删）
 *
 * 读入只有两类：
 *   - _research/editing-model/{slot-inventory,vendor-edit-model}.json  （前一轮 agent 的机读清单）
 *   - 厂商原始 JSON（_research/extracted/**）与 esbuild 加载 styles.ts（只为拿到节点路径域与条带方向）
 * 产物先写 _research/controls-impl/staging/（草稿）；`--publish` 再把三份 controls.*.json 拷进
 * 原生工程自有数据目录 framemark/bridge/desktop/controls-data/ —— 这是 scripts/native/build.cjs
 * 与 scripts/native/runtime.cjs 读取的唯一落点。本脚本不读也不写冻结的 legacy-web/ 历史 Web 工程。
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const ROOT = 'D:/border&watermark'
const STAGING = path.join(ROOT, '_research', 'controls-impl', 'staging')
const SCHEMA_TS = path.join(STAGING, 'controls-schema.ts')
const INV_P = path.join(ROOT, '_research', 'editing-model', 'slot-inventory.json')
const VEN_P = path.join(ROOT, '_research', 'editing-model', 'vendor-edit-model.json')
const IRX_P = path.join(STAGING, 'ir-index.json')
const VDX_P = path.join(STAGING, 'vendor-detail.json')
const FRAMEMARK = path.join(ROOT, 'framemark')
const STYLES_TS = path.join(FRAMEMARK, 'src/renderer/src/core/system/styles.ts')
// 原生产物自有数据目录：三份 controls.*.json 从这里被 scripts/native/{build,runtime}.cjs 读取。
// 生成物只在 staging/ 里是草稿，`--publish` 之后才算落库。
const PUBLISH_DIR = path.join(FRAMEMARK, 'bridge', 'desktop', 'controls-data')
// OPPO 相册 style JSON 的唯一权威源＝适配器 oppo.cjs 的 ROOT（不是 extracted/ 那份旧拷贝！
// extracted/ 少 5 个 realme 套、且 13 个同名文件大小不一致 ⇒ 用它取数会把厂商证据算旧/算漏）。
const OPPO_GALLERY = path.join(ROOT, '_research/vendor-latest/oppo/assets/watermark_master_styles')
const OPPO_GALLERY_STALE = path.join(ROOT, '_research/extracted/oppo-watermarks/gallery-master-styles/assets/watermark_master_styles')
const OPPO_CP = path.join(ROOT, '_research/extracted/oppo-watermarks/assets/watermark')
const XM_DIR = path.join(ROOT, '_research/extracted/xiaomi-watermarks-v32')

const load = (p) => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''))
const write = (p, obj) => {
  fs.mkdirSync(path.dirname(p), { recursive: true })
  fs.writeFileSync(p, typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2))
}

const SRC_NAMES = {
  '-1': 'custom/literal',
  0: 'model',
  1: 'imageParams(EXIF)',
  2: 'time_location',
  3: 'time',
  4: 'location',
  5: 'greeting',
  6: 'custom',
  7: 'filter',
  8: 'cameraFilterParams(not EXIF)'
}
/** textSource ⇉ 该元素在画什么（用来找 IR 节点） */
const SRC2SEM = {
  '-1': 'custom',
  0: 'model',
  1: 'exif',
  2: 'timelocation',
  3: 'date',
  4: 'location',
  5: 'greeting',
  6: 'custom',
  7: 'filter',
  8: 'skip'
}
const VENDOR_SOURCES = [
  'xiaomi-config-optional',
  'xiaomi-meta-limitation',
  'oppo-style-editable',
  'oppo-style-hidden',
  'oppo-style-gravity',
  'oppo-camerapro-editable',
  'vivo-subcard',
  'vivo-unsupport-edit'
]
const INFERRED_SOURCES = ['ir-optional-heuristic', 'ir-slot-structure', 'vendor-enum-universe']
const MANUAL_SOURCES = ['manual-override']

/* =====================================================================
 * 1. IR 索引（节点路径域 / 条带方向 / 主题 / 父子关系）
 * ===================================================================== */

async function loadStockStyles() {
  const esbuild = require(path.join(FRAMEMARK, 'node_modules', 'esbuild'))
  const res = await esbuild.build({ entryPoints: [STYLES_TS], bundle: true, write: false, format: 'cjs', platform: 'node', target: 'node20' })
  const mod = { exports: {} }
  new Function('module', 'exports', 'require', res.outputFiles[0].text)(mod, mod.exports, require)
  return mod.exports.STOCK_STYLES
}

function walkIr(node, p, parent, out) {
  const rec = { path: p, kind: node.kind, parent }
  if (node.kind === 'text') {
    rec.tokenKind = (node.token && node.token.kind) || null
    rec.tokenValue = (node.token && node.token.value) || null
    rec.optional = !!node.optional
    rec.size = node.size
  } else if (node.kind === 'image') {
    rec.asset = node.asset || null
    rec.isBrandLogo = !!node.isBrandLogo
  } else if (node.kind === 'shape') {
    rec.vectorKey = node.vectorKey || null
  }
  out.push(rec)
  if (node.kind === 'group') (node.children || []).forEach((c, i) => walkIr(c, p + '/g' + i, p, out))
}

async function extractIr() {
  const STYLES = await loadStockStyles()
  const index = {}
  for (const s of Object.values(STYLES)) {
    const nodes = []
    const bands = []
    s.bands.forEach((b, bi) => {
      const tag = 'b' + bi
      bands.push({ band: tag, direction: b.direction || null, rotation: !!b.rotation })
      ;(b.children || []).forEach((c, ci) => walkIr(c, tag + '/' + (b.direction || 'row') + ci, tag, nodes))
    })
    index[s.id] = {
      id: s.id,
      vendor: s.vendor,
      family: s.family,
      name: s.name,
      placement: s.placement,
      themeIds: (s.themes || []).map((t, i) => t.id || t.key || t.name || 'theme' + i),
      bands,
      movableBands: bands.filter((b) => b.direction === 'overlay' || b.rotation).map((b) => b.band),
      paths: nodes.map((n) => n.path),
      nodes
    }
  }
  write(IRX_P, { generatedFrom: 'styles.ts via esbuild (same path scheme as slot-inventory.cjs)', styleCount: Object.keys(index).length, styles: index })
  return index
}

/* =====================================================================
 * 2. 厂商细粒度取数
 * ===================================================================== */

function scanOppoElements(obj, acc) {
  if (!obj || typeof obj !== 'object') return
  if (Array.isArray(obj)) return obj.forEach((x) => scanOppoElements(x, acc))
  if (obj.content && obj.content.type === 'text') {
    acc.push({
      order: acc.length,
      id: obj.id === undefined ? null : obj.id,
      textSource: obj.content.textSource === undefined ? null : obj.content.textSource,
      textSourceName: SRC_NAMES[String(obj.content.textSource)] || String(obj.content.textSource),
      sem: SRC2SEM[String(obj.content.textSource)] || 'unknown',
      editable: obj.editable === true,
      visible: obj.visible !== false,
      isExtendModel: !!obj.content.isExtendModel,
      gravity: (obj.position && obj.position.layoutGravity) || null
    })
  }
  for (const k of ['elements', 'bitmaps', 'subViews', 'childViews']) if (Array.isArray(obj[k])) obj[k].forEach((c) => scanOppoElements(c, acc))
}

function collectSub(obj, acc) {
  if (!obj || typeof obj !== 'object') return
  if (Array.isArray(obj)) return obj.forEach((x) => collectSub(x, acc))
  if (typeof obj.text === 'string') acc.texts.push(obj.text)
  for (const k of ['asset', 'src', 'imageName', 'drawable', 'resName', 'foreground', 'background']) if (typeof obj[k] === 'string') acc.assets.push(obj[k])
  for (const k of Object.keys(obj)) if (k !== 'text') collectSub(obj[k], acc)
}

function scanXiaomiOptional(obj, acc) {
  if (!obj || typeof obj !== 'object') return
  if (Array.isArray(obj)) return obj.forEach((x) => scanXiaomiOptional(x, acc))
  if (obj.optional === true) {
    // 厂商 optional 常挂在容器（Layout）上：真正的文本在 childViews 里，
    // 所以除了自身 text，还要收集整棵子树的文本，才能绑到具体行。
    const sub = { texts: [], assets: [] }
    collectSub(obj, sub)
    acc.push({
      widget: obj.type || obj.className || obj.viewType || '(untyped)',
      id: obj.id === undefined ? null : obj.id,
      settingOptionalType: obj.setting_optional_type === undefined ? null : obj.setting_optional_type,
      layoutGravity: obj.layout_gravity === undefined ? null : obj.layout_gravity,
      text: typeof obj.text === 'string' ? obj.text : null,
      subTexts: [...new Set(sub.texts)].slice(0, 10),
      subAssets: [...new Set(sub.assets)].slice(0, 6)
    })
  }
  for (const k of Object.keys(obj)) {
    if (k === 'optional') continue
    if (Array.isArray(obj[k])) obj[k].forEach((c) => scanXiaomiOptional(c, acc))
    else if (obj[k] && typeof obj[k] === 'object') scanXiaomiOptional(obj[k], acc)
  }
}

function xiaomiLimitation(metaJson) {
  // limitation.name_length_limitation 实测是数组/区间；只取数字集合，取不到就 null
  const s = JSON.stringify(metaJson)
  const m = s.match(/name_length_limitation[^[]*\[[^\]]*\]|name_length_limitation"?\s*:\s*\{[^}]*\}/)
  if (!m) return null
  const nums = (m[0].match(/-?\d+/g) || []).map(Number)
  if (!nums.length) return null
  return { raw: m[0].slice(0, 160), min: Math.min(...nums), max: Math.max(...nums) }
}

function extractVendor() {
  const oppo = {}
  const galleryDir = fs.existsSync(OPPO_GALLERY) ? OPPO_GALLERY : OPPO_GALLERY_STALE
  const srcs = { oppoGallery: galleryDir, oppoGalleryIsAuthoritative: galleryDir === OPPO_GALLERY, oppoCameraPro: OPPO_CP }
  for (const dir of [[galleryDir, 'oppo-gallery'], [OPPO_CP, 'oppo-camerapro']]) {
    if (!fs.existsSync(dir[0])) continue
    for (const f of fs.readdirSync(dir[0]).filter((x) => x.endsWith('.json'))) {
      const acc = []
      try {
        scanOppoElements(load(path.join(dir[0], f)), acc)
      } catch (e) {
        continue
      }
      const styleKey = f.replace('.json', '')
      oppo[dir[1] + ':' + styleKey] = { file: path.relative(ROOT, path.join(dir[0], f)).replace(/\\/g, '/'), elements: acc }
    }
  }
  const xiaomi = {}
  if (fs.existsSync(XM_DIR))
    for (const fam of fs.readdirSync(XM_DIR)) {
      const fp = path.join(XM_DIR, fam)
      if (!fs.statSync(fp).isDirectory()) continue
      for (const id of fs.readdirSync(fp)) {
        const cf = path.join(fp, id, 'config.json')
        if (!fs.existsSync(cf)) continue
        const acc = []
        try {
          scanXiaomiOptional(load(cf), acc)
        } catch (e) {
          continue
        }
        const limit = fs.existsSync(path.join(fp, id, 'meta.json')) ? xiaomiLimitation(load(path.join(fp, id, 'meta.json'))) : null
        let bgCount = null
        try {
          const cj = load(cf)
          const fg = (cj.wmRes && (cj.wmRes.foreground || cj.wmRes.background)) || null
          bgCount = Array.isArray(fg) ? fg.length : null
        } catch (e) {
          bgCount = null
        }
        xiaomi['xiaomi-' + fam + ':' + id] = {
          file: path.relative(ROOT, cf).replace(/\\/g, '/'),
          optionalViews: acc,
          themeVariantCount: bgCount,
          limitation: limit
        }
      }
    }
  const ven = load(VEN_P)
  write(VDX_P, { sources: srcs, oppo, xiaomi, vivo: ven.vivo, vivoFiles: ven.summary && ven.summary.vivo })
  return { oppo, xiaomi, vivo: ven.vivo, sources: srcs }
}

/* =====================================================================
 * 3. 厂商文本模板 ⇉ IR 节点
 * ===================================================================== */

const METRICS = { focal: 'focal', aperture: 'aperture', speed: 'shutter', iso: 'iso' }

/** 小米 config 的视图文本 → 语义键 + 指标集合 */
function parseXiaomiText(t) {
  if (!t) return null
  const head = t.split(';')[0]
  const body = head.replace(/^@[^=]*=/, '')
  if (head.startsWith('@time')) return { sem: 'date', hint: body }
  if (head.startsWith('@location')) return { sem: 'location', hint: body }
  if (head.startsWith('@model')) return { sem: 'model', hint: body }
  if (head.startsWith('@custom')) return { sem: 'custom', hint: body }
  if (head.startsWith('@filter')) return { sem: 'filter', hint: body }
  if (!head.startsWith('@')) return { sem: 'literal', hint: t }
  if (head.startsWith('@exif')) {
    const mets = []
    for (const [k, v] of Object.entries(METRICS)) if (t.includes(k)) mets.push(v)
    return { sem: 'exif', metrics: mets, hint: body }
  }
  return { sem: 'unknown', hint: t.slice(0, 40) }
}

function nodesBySem(st, sem, opts) {
  const o = opts || {}
  return st.nodes.filter((n) => {
    if (n.kind !== 'text') return false
    const tk = n.tokenKind || ''
    const tv = n.tokenValue || ''
    switch (sem) {
      case 'date':
        return tk === 'time' || (tk === 'template' && tv.includes('@time'))
      case 'location':
        return tk === 'location' || tk === 'timeLocation' || (tk === 'template' && tv.includes('@location'))
      case 'model':
        if (o.hint && /versionNumber/.test(o.hint) && tv.includes('versionName')) return false
        if (o.hint && /versionName/.test(o.hint) && tv.includes('versionNumber')) return false
        return tk === 'model' || (tk === 'template' && tv.includes('@model'))
      case 'custom':
        return tk === 'custom'
      case 'filter':
        return tk === 'filter'
      case 'greeting':
        return tk === 'greeting'
      case 'literal':
        // 厂商直接写死的字符串：只有 IR literal 节点文本一致时才算同一行
        return tk === 'literal' && !!tv && (tv === o.hint || tv.includes(o.hint) || o.hint.includes(tv))
      case 'exif':
        if (o.metrics && o.metrics.length) {
          if (tv && tk === 'template') return o.metrics.every((m) => tv.includes(m === 'shutter' ? 'speed' : m))
          return tk === 'imageParams'
        }
        return tk === 'imageParams' || (tk === 'template' && tv.includes('@exif'))
      case 'timelocation':
        return tk === 'timeLocation' || tk === 'time' || tk === 'location'
      default:
        return false
    }
  })
}

/** 若多个命中同属一个 group 且该 group 只有这些文字叶子 ⇒ 绑定 group（整行开关） */
function collapseToGroup(st, hits) {
  if (hits.length < 2) return hits
  const parents = new Set(hits.map((h) => h.parent))
  if (parents.size !== 1) return hits
  const par = [...parents][0]
  const allTextKids = st.nodes.filter((n) => n.kind === 'text' && n.parent === par)
  if (allTextKids.length !== hits.length) return hits
  const grp = st.nodes.find((n) => n.path === par && n.kind === 'group')
  if (!grp) return hits
  const siblingNonText = st.nodes.filter((n) => n.parent === par && n.kind !== 'text' && n.kind !== 'space')
  if (siblingNonText.length) return hits
  return [syntheticGroup(st, par, hits)]
}

function syntheticGroup(st, par, hits) {
  return {
    path: par,
    kind: 'group',
    parent: st.nodes.find((n) => n.path === par).parent,
    tokenKind: 'group:' + hits.map((h) => h.tokenKind).join('+'),
    synthetic: true,
    members: hits.map((h) => h.path)
  }
}

/**
 * 单叶子开关的落地检查：若该叶子所在的 group 里其余文字叶子都是厂商的标签/单位前后缀
 * （tokenKind==='literal'，如 'ZEISS' / 'mm' / 'f/' / '打卡记录'），关掉叶子会把孤零的
 * 前后缀留在画布上 ⇒ 必须把开关上提到整组（厂商的开关语义本来就是关整行）。
 */
function rebindAffixGroup(st, hit) {
  if (hit.kind !== 'text' || hit.parent === null) return hit
  const sibs = st.nodes.filter((n) => n.parent === hit.parent && n.path !== hit.path)
  const otherTokens = sibs.filter((n) => n.kind === 'text' && n.tokenKind !== 'literal')
  const affixes = sibs.filter((n) => n.kind === 'text' && n.tokenKind === 'literal')
  const blockers = sibs.filter((n) => n.kind === 'image' || n.kind === 'shape')
  if (!affixes.length || otherTokens.length || blockers.length) return hit
  return syntheticGroup(st, hit.parent, [hit])
}

function bindNodes(st, sem, opts) {
  let hits = nodesBySem(st, sem, opts)
  if (!hits.length && sem === 'exif' && opts && opts.metrics && opts.metrics.length > 1) {
    // 厂商把 4 个指标写在同一个视图里，而 IR 把它们拆成 4 个叶子 ⇒ 逐指标取并集再上提到整组
    const union = []
    for (const m of opts.metrics) {
      const one = nodesBySem(st, 'exif', { metrics: [m] })
      if (!one.length) return []
      for (const n of one) if (!union.some((u) => u.path === n.path)) union.push(n)
    }
    hits = collapseToGroup(st, union)
    if (hits.length === 1) return hits
    const parents = new Set(union.map((u) => u.parent))
    if (parents.size === 1) return [syntheticGroup(st, [...parents][0], union)]
    return []
  }
  if (hits.length > 1) {
    const exact = opts && opts.hint ? hits.filter((h) => h.tokenValue && (h.tokenValue === opts.hint || h.tokenValue.includes(opts.hint))) : []
    if (exact.length === 1) return exact
    const collapsed = collapseToGroup(st, hits)
    if (collapsed.length === 1) return collapsed
    if (exact.length > 1) return []
    return []
  }
  return hits
}

/* =====================================================================
 * 4. 控件构造
 * ================================================================= ==== */

let CONTROL_SEQ = 0
function ctl(kind, styleId, slotKey, target, extra) {
  const c = Object.assign(
    {
      id: kind + '.' + (slotKey || 'n' + CONTROL_SEQ) + '.' + target.split('/').pop(),
      kind,
      label: '',
      group: kind === 'themeSwatches' ? 'detail' : kind === 'anchorGrid' || kind === 'xyOffset' || kind === 'gravitySegment' ? 'layout' : 'content',
      consumer: '',
      provenance: 'inferred',
      evidence: null
    },
    extra
  )
  CONTROL_SEQ++
  return c
}

function ev(source, ref, detail, match) {
  return { source, ref, detail, match }
}

const CONSUMER_OF = {
  toggle: 'toggle.slotOff',
  text: 'text.slotText',
  sliderScale: 'slider.slotScale',
  sliderAlpha: 'slider.slotAlpha',
  anchorGrid: 'anchorGrid.stockAnchor',
  xyOffset: 'xyOffset.stockOffset',
  themeSwatches: 'theme.theme',
  brandPicker: 'brand.brandLogoFile',
  gravitySegment: 'gravity.slotGravity'
}

function makeToggle(styleId, target, slotKey, provenance, evidence, label) {
  return ctl('toggle', styleId, slotKey, target, {
    label: label || ('显示「' + slotKey + '」行'),
    consumer: CONSUMER_OF.toggle,
    param: 'slotOff',
    targetNode: target,
    default: 'on',
    domain: { on: true, off: true },
    provenance,
    evidence,
    fidelityRisk: provenance === 'vendor' ? false : true
  })
}

function makeText(styleId, target, slotKey, provenance, evidence, maxLength, maxLengthSource, minLength) {
  return ctl('text', styleId, slotKey, target, {
    label: '编辑「' + slotKey + '」文字',
    consumer: CONSUMER_OF.text,
    param: 'slotText',
    targetNode: target,
    default: null,
    domain: { maxLength: maxLength === undefined ? null : maxLength, minLength: minLength === undefined ? null : minLength, maxLengthSource: maxLengthSource || (maxLength ? 'xiaomi-meta-limitation' : 'fallback'), multiline: false },
    provenance,
    evidence,
    fidelityRisk: false
  })
}

function makeSlider(styleId, target, slotKey, which, provenance, evidence) {
  const isScale = which === 'scale'
  return ctl('slider', styleId, slotKey, target, {
    label: isScale ? '「' + slotKey + '」字号' : '「' + slotKey + '」不透明度',
    consumer: isScale ? CONSUMER_OF.sliderScale : CONSUMER_OF.sliderAlpha,
    param: isScale ? 'slotScale' : 'slotAlpha',
    targetNode: target,
    default: isScale ? 1 : null,
    domain: isScale ? { min: 0.6, max: 1.6, step: 0.05, unit: 'multiplier' } : { min: 0.2, max: 1, step: 0.05, unit: 'alpha' },
    provenance,
    evidence,
    fidelityRisk: false
  })
}

function makeAnchor(styleId, band, provenance, evidence, movable) {
  const anchor = ctl('anchorGrid', styleId, 'band' + band, band, {
    label: '水印位置',
    consumer: CONSUMER_OF.anchorGrid,
    param: 'stockAnchor',
    targetBand: band,
    default: 'vendor',
    domain: { options: ['vendor', 'leftTop', 'leftCenter', 'leftBottom', 'centerTop', 'center', 'centerBottom', 'rightTop', 'rightCenter', 'rightBottom'] },
    provenance,
    evidence,
    fidelityRisk: false
  })
  const off = []
  for (const p of ['stockOffsetXPct', 'stockOffsetYPct']) {
    off.push(
      ctl('xyOffset', styleId, p === 'stockOffsetXPct' ? 'offsetX' : 'offsetY', band, {
        label: p === 'stockOffsetXPct' ? '水平微调' : '垂直微调',
        consumer: CONSUMER_OF.xyOffset,
        param: p,
        targetBand: band,
        default: 0,
        domain: { min: -25, max: 25, step: 0.5, unit: 'pctOfLongEdge' },
        provenance,
        evidence,
        fidelityRisk: false
      })
    )
  }
  return [anchor, ...off]
}

function makeTheme(styleId, themeIds, provenance, evidence) {
  return ctl('themeSwatches', styleId, 'theme', 'style', {
    label: '主题',
    consumer: CONSUMER_OF.themeSwatches,
    param: 'theme',
    default: null,
    domain: { options: themeIds, themeCount: themeIds.length },
    provenance,
    evidence,
    fidelityRisk: false
  })
}

function makeBrand(styleId, target, provenance, evidence) {
  return ctl('brandPicker', styleId, 'brandSlot', target, {
    label: '品牌字标',
    consumer: CONSUMER_OF.brandPicker,
    param: 'brandLogoFile',
    targetNode: target,
    default: null,
    domain: { brandLibrary: 'core/brands(62)', autoMatch: true },
    provenance,
    evidence,
    fidelityRisk: false
  })
}

function makeGravity(styleId, target, slotKey, options, provenance, evidence) {
  return ctl('gravitySegment', styleId, slotKey, target, {
    label: '「' + slotKey + '」方位',
    consumer: CONSUMER_OF.gravitySegment,
    param: 'slotGravity',
    targetNode: target,
    default: null,
    domain: { options },
    provenance,
    evidence,
    fidelityRisk: true
  })
}

/* =====================================================================
 * 5. 各族 baseline
 * ===================================================================== */

const MANUAL_OVERRIDDEN = {
  // 人工编写、不走 adapter 重导的套（grep 实测 styles.ts / vivoManual.ts 定义处）：
  // 这些套的 IR 派生控件 provenance='manual'（是我们手工决定的编辑面），厂商证据仍然算 vendor。
  'oppo-gallery:hassel_style_1': 'src/renderer/src/core/system/styles.ts#L7 const HASSEL',
  'oppo-gallery:lumo_style_1': 'src/renderer/src/core/system/styles.ts#L109 const LUMO',
  'oppo-camerapro:gr_style_5': 'src/renderer/src/core/system/styles.ts#L227 const GR5',
  'oppo-gallery:personalize_retroCamera_5': 'src/renderer/src/core/system/styles.ts#L303 const OPPO_RETRO_5',
  'xiaomi-film:15': 'src/renderer/src/core/system/styles.ts#L383 const XIAOMI_FILM_15',
  'xiaomi-classic:22': 'src/renderer/src/core/system/styles.ts#L677 xiaomiInnerSidebar(classic_22)',
  'xiaomi-classic:99': 'src/renderer/src/core/system/styles.ts#L678 xiaomiInnerSidebar(classic_99)',
  'vivo-zeiss:border_top': 'src/renderer/src/core/system/vivoManual.ts#L115',
  'vivo-zeiss:up_down': 'src/renderer/src/core/system/vivoManual.ts#L182',
  'vivo-zeiss:inside': 'src/renderer/src/core/system/vivoManual.ts#L248',
  'vivo-polaroid:frame': 'src/renderer/src/core/system/vivoManual.ts#L351',
  'vivo-stamp:frame': 'src/renderer/src/core/system/vivoManual.ts#L386',
  'vivo-tone:frame': 'src/renderer/src/core/system/vivoManual.ts#L431',
  'vivo-geographic:overlay': 'src/renderer/src/core/system/vivoManual.ts#L469',
  'vivo-postcard:frame': 'src/renderer/src/core/system/vivoManual.ts#L510',
  'vivo-chinoiserie-loc:frame': 'src/renderer/src/core/system/vivoManual.ts#L594',
  'vivo-chinoiserie-signet:frame': 'src/renderer/src/core/system/vivoManual.ts#L645',
  'vivo-chinoiserie-ink:frame': 'src/renderer/src/core/system/vivoManual.ts#L686',
  'vivo-signature:overlay': 'src/renderer/src/core/system/vivoManual.ts#L726',
  'vivo-border-photo:frame': 'src/renderer/src/core/system/vivoManual.ts#L773',
  'vivo-feature-photo:frame': 'src/renderer/src/core/system/vivoManual.ts#L813'
}

/** IR 派生（非厂商授权）控件在三态里的落点：手工套记 manual，其余记 inferred */
function irProv(styleId) {
  return MANUAL_OVERRIDDEN[styleId] ? 'manual' : 'inferred'
}
function irEv(styleId, detail, match, baseSource, baseRef) {
  if (MANUAL_OVERRIDDEN[styleId]) return ev('manual-override', MANUAL_OVERRIDDEN[styleId], '手工样式（不走 adapter 重导）：' + detail, 'manual')
  return ev(baseSource || 'ir-optional-heuristic', baseRef || 'src/renderer/src/core/system/generated', detail, match || 'tokenKind-unique')
}

function slotKeyOf(st, node) {
  if (node.kind === 'group') {
    const ms = (node.members || []).map((m) => st.nodes.find((n) => n.path === m)).filter(Boolean)
    const kinds = [...new Set(ms.map((m) => m.tokenKind))]
    return kinds.join('+')
  }
  const inv = INV_STYLES[st.id]
  const s = inv && inv.slots.find((x) => x.path === node.path)
  return s ? s.type : node.tokenKind || node.kind
}

let INV_STYLES = {}
let INV_FP = {}

function fingerprintOf(st) {
  return INV_FP[st.id] || ''
}

/**
 * panel='generic' 的套必须逐套写清"为什么发不出控件"（计数级理由，不是模板话）。
 * 用到的量都来自本脚本已有的取数：厂商元素 editable/visible 计数、IR optional 行数、可动条带数、主题数。
 */
function genericReason(styleId, st, ven, unmappedCount, family) {
  const bits = []
  if (family === 'oppo') {
    if (ven) {
      const ed = ven.elements.filter((e) => e.editable).length
      const hid = ven.elements.filter((e) => !e.visible).length
      bits.push('厂商 ' + ven.elements.length + ' 个文字元素里 editable:true ' + ed + ' 处、visible:false ' + hid + ' 处')
      if (ed === 0 && hid === 0) bits.push('⇒ 厂商侧没有任何行级授权')
      if (ed > 0) bits.push('（editable 全部绑不上：' + unmappedCount + ' 处，见 unmappedVendor）')
    } else {
      bits.push('无厂商 style JSON 可对照（realme 包未导出 / 手工套）')
    }
  } else if (family === 'xiaomi') {
    const vn = ven ? ven.optionalViews.length : 0
    bits.push('厂商 config 的 optional 视图 ' + vn + ' 处' + (vn === 0 ? ' ⇒ 该套厂商侧整卡不可关' : '（全部绑不上：' + unmappedCount + ' 处）'))
  } else {
    const vt = VIVO_MAP[styleId] ? 1 : 0
    bits.push(vt ? '厂商 temp.json 的 subCard 没有任何可映射到本套 IR 的开关' : '无可映射的厂商 temp.json（模板名对不上）')
  }
  const optN = st.nodes.filter((n) => n.kind === 'text' && n.optional).length
  bits.push('IR optional 行 ' + optN + ' 个')
  bits.push('可动条带（overlay/rotation）' + st.movableBands.length + ' 个')
  bits.push('themes ' + st.themeIds.length + ' 组')
  bits.push('isBrandLogo 节点 ' + st.nodes.filter((n) => n.kind === 'image' && n.isBrandLogo).length + ' 个')
  return bits.join('；') + ' ⇒ 没有任何有消费对象具名函数的编辑面可声明，沿用通用面板（行为零变化）'
}

function buildRecord(st, inv, controls, extra) {
  const uniq = []
  const seen = new Set()
  for (const c of controls) {
    const key = c.kind + '|' + (c.targetNode || c.targetBand || 'style') + '|' + (c.param || '')
    if (seen.has(key)) continue
    seen.add(key)
    uniq.push(c)
  }
  // id 去重（同名槽位多节点时补序号）
  const ids = new Set()
  for (const c of uniq) {
    let id = c.id
    let k = 2
    while (ids.has(id)) id = c.id + '#' + k++
    c.id = id
    ids.add(id)
  }
  const rec = {
    schemaVersion: 'framemark-stock-style-controls/v1',
    styleId: st.id,
    vendor: st.vendor,
    family: st.family,
    name: st.name,
    placement: st.placement,
    rotatedBand: !!(inv && inv.rotatedBand),
    fingerprint: fingerprintOf(st),
    source: MANUAL_OVERRIDDEN[st.id] ? 'manual' : 'baseline',
    panel: uniq.length ? 'declared' : 'generic',
    controls: uniq
  }
  if (!uniq.length) rec.noControlsReason = (extra && extra.noControlsReason) || genericReason(st.id, st, extra && extra.ven, ((extra && extra.unmappedVendor) || []).length, st.vendor)
  if (extra && extra.unmappedVendor) rec.unmappedVendor = extra.unmappedVendor
  if (extra && extra.notes) rec.notes = extra.notes
  return rec
}

function baselineXiaomi(irx, vdx) {
  const out = []
  const diff = []
  for (const id of Object.keys(irx).filter((x) => x.startsWith('xiaomi-'))) {
    const st = irx[id]
    const inv = INV_STYLES[id]
    const ven = vdx.xiaomi[id]
    const ctrls = []
    const unmapped = []
    const vendorBound = new Set()
    if (ven) {
      for (const v of ven.optionalViews) {
        // 厂商 optional 常挂在容器上：优先用子树文本逐行绑定
        const texts = v.text ? [v.text] : v.subTexts || []
        const tag = v.settingOptionalType === null ? '' : ' setting_optional_type:' + v.settingOptionalType
        if (!texts.length) {
          const sot = v.settingOptionalType === null ? '' : String(v.settingOptionalType)
          const imgs = st.nodes.filter((n) => n.kind === 'image')
          if (imgs.length === 1 && (v.subAssets || []).length) {
            vendorBound.add(imgs[0].path)
            ctrls.push(
              makeToggle(id, imgs[0].path, slotKeyOf(st, imgs[0]), 'vendor', ev('xiaomi-config-optional', ven.file, 'optional:true 图片视图 ' + v.widget + ' id:' + v.id + ' sot:' + sot + ' asset:' + v.subAssets[0], 'slotType-unique'), '图片元素开关')
            )
          } else if (imgs.length === 1 && sot && sot !== 'type_custom_icon') {
            // 厂商开关命名了槽位（如 type_leica_looks / type_leica_lens_container），套里只有一个图片节点 ⇒ 绑它
            vendorBound.add(imgs[0].path)
            ctrls.push(
              makeToggle(id, imgs[0].path, slotKeyOf(st, imgs[0]), 'vendor', ev('xiaomi-config-optional', ven.file, 'optional:true 容器 id:' + v.id + ' setting_optional_type:' + sot + '（套内唯一图片节点）', 'slotType-unique'), '图片元素开关')
            )
          } else {
            unmapped.push({
              view: v.widget,
              text: '(subtree 无文本)',
              why: sot === 'type_custom_icon'
                ? '厂商开关是"用户自定义图标"槽位（id:' + v.id + '），我们没绘制该槽位 ⇒ 无对象可关'
                : '厂商 optional 容器无法定位到具体行' + tag
            })
          }
          continue
        }
        for (const t of texts) {
          const sem = parseXiaomiText(t)
          if (!sem || sem.sem === 'unknown') {
            unmapped.push({ view: v.widget, text: (t || '').slice(0, 70), why: '文本无法解析成槽位' + tag })
            continue
          }
          const hits = bindNodes(st, sem.sem, { hint: sem.hint, metrics: sem.metrics })
          if (!hits.length) {
            unmapped.push({ view: v.widget, text: (t || '').slice(0, 70), why: sem.sem + ' 在 IR 里找不到唯一节点' + tag })
            continue
          }
          const raw = hits[0]
          const n = raw.synthetic ? raw : rebindAffixGroup(st, raw)
          if (vendorBound.has(n.path)) continue
          for (const p of [n.path, raw.path].concat(n.members || [])) vendorBound.add(p)
          const sk = slotKeyOf(st, n)
          const upl = n.synthetic && !raw.synthetic ? ' ⇒ 开关上提到整组（组内其余文字是厂商的标签/单位前后缀，留叶子开关会掉孤零前后缀）' : ''
          const e = ev('xiaomi-config-optional', ven.file, 'optional:true ' + (t || '').slice(0, 70) + tag + upl, hits.length === 1 && !n.synthetic ? (n.tokenValue === sem.hint ? 'tokenValue-exact' : 'tokenKind-unique') : 'slotType-unique')
          ctrls.push(makeToggle(id, n.path, sk, 'vendor', e))
          const rawSk = raw.synthetic ? null : slotKeyOf(st, raw)
          if (!raw.synthetic && (['location', 'custom'].includes(raw.tokenKind) || rawSk === 'customLine')) {
            // 厂商 optional 授权的是"这行可以关掉"。只有 custom 行的内容本身就是用户填的文本，
            // 才算厂商授权"改内容"；location 行的文本来自 GPS/地址列表 ⇒ 替换权不在配置里，
            // 必须降级为 inferred（手工套记 manual），否则就是拿 inferred 冒充 vendor。
            const isUserTyped = raw.tokenKind === 'custom' || rawSk === 'customLine'
            const textProv = isUserTyped ? 'vendor' : irProv(id)
            // meta.json 的 limitation.name_length_limitation 是长度窗口 [min,max]（实测 [0,17] 与 [17,100] 两种），
            // 只有用户自填行才允许挂它。
            const lim = isUserTyped && ven.limitation ? ven.limitation.max : null
            const limMin = isUserTyped && ven.limitation && ven.limitation.min > 0 ? ven.limitation.min : null
            const limNote = ven.limitation ? '（meta limitation ' + ven.limitation.raw.replace(/"/g, "'").slice(0, 44) + (isUserTyped ? '）' : '，但该行不是用户自填文本 ⇒ 不套用）') : ''
            const textEv = isUserTyped
              ? ev('xiaomi-config-optional', ven.file, 'optional 视图 + 该行内容本就是用户自填文本：' + (t || '').slice(0, 50) + limNote, 'tokenKind-unique')
              : irEv(id, '厂商 optional 只授权隐藏该行；行内文本来自 GPS/地址列表，"替换成任意文字"不在厂商配置里 ⇒ 非厂商授权' + limNote, 'tokenKind-unique', 'ir-slot-structure', ven.file)
            ctrls.push(makeText(id, raw.path, rawSk, textProv, textEv, lim, lim ? 'xiaomi-meta-limitation' : 'fallback', limMin))
          }
        }
      }
    } else {
      unmapped.push({ view: '(no config.json)', text: '', why: '厂商 config 未导出（手工样式或 blocked）' })
    }
    // IR 启发式 optional 但厂商没授权 ⇒ inferred（手工套 ⇒ manual）+ 保真风险
    for (const n of st.nodes.filter((x) => x.kind === 'text' && x.optional && !vendorBound.has(x.path))) {
      const t = rebindAffixGroup(st, n)
      if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === t.path)) continue
      ctrls.push(makeToggle(id, t.path, slotKeyOf(st, t), irProv(id), irEv(id, 'IR optional:true 但厂商 config 无 optional 标志（适配器文本正则启发式）', 'tokenKind-unique', 'ir-optional-heuristic', ven ? ven.file : 'src/renderer/src/core/system/generated')))
    }
    // 品牌字标（37 套全小米系，chooseBrandSlot 已实现）
    for (const n of st.nodes.filter((x) => x.kind === 'image' && x.isBrandLogo)) {
      const hasVen = !!ven
      ctrls.push(
        makeBrand(
          id,
          n.path,
          hasVen ? 'vendor' : irProv(id),
          hasVen
            ? ev('xiaomi-config-optional', ven.file, '机型 logo 视图带 optional 标志 + IR isBrandLogo:true ⇒ 随照片品牌自动换', 'slotType-unique')
            : irEv(id, 'IR isBrandLogo:true，无厂商 config 可比对', 'slotType-unique', 'ir-slot-structure', 'src/renderer/src/core/system/generated')
        )
      )
    }
    if (st.themeIds.length) {
      const tv = ven && ven.themeVariantCount ? ven.themeVariantCount : 0
      ctrls.push(
        makeTheme(
          id,
          st.themeIds,
          tv > 1 ? 'vendor' : irProv(id),
          tv > 1
            ? ev('xiaomi-config-optional', ven.file, 'wmRes.background/foreground 数组长度 ' + tv + '（IR themes ' + st.themeIds.length + ' 组）', 'band-gate')
            : irEv(id, 'IR themes ' + st.themeIds.length + ' 组，但厂商 config 未给出主题变体数组', 'band-gate', 'ir-slot-structure', 'src/renderer/src/core/system/generated')
        )
      )
    }
    for (const b of st.movableBands) {
      ctrls.push(...makeAnchor(id, b, irProv(id), irEv(id, '条带 ' + b + ' 为 overlay/rotation ⇒ 允许整体锚点与微调；厂商无自由拖拽字段', 'band-gate', 'ir-slot-structure', 'src/renderer/src/core/system/generated'), true))
    }
    if (ven) {
      const irOpt = st.nodes.filter((x) => x.kind === 'text' && x.optional).map((x) => x.path)
      diff.push({
        styleId: id,
        vendorOptionalViews: ven.optionalViews.length,
        vendorMappedNodes: [...vendorBound],
        irOptionalNodes: irOpt,
        vendorOnly: [...vendorBound].filter((p) => !irOpt.includes(p)),
        irOnly: irOpt.filter((p) => !vendorBound.has(p)),
        unmappedVendorViews: unmapped.length
      })
    }
    out.push(buildRecord(st, inv, ctrls, { unmappedVendor: unmapped.length ? unmapped : undefined, ven: ven }))
  }
  return { records: out, diff }
}

function baselineOppo(irx, vdx) {
  const out = []
  for (const id of Object.keys(irx).filter((x) => x.startsWith('oppo-'))) {
    const st = irx[id]
    const inv = INV_STYLES[id]
    const ven = vdx.oppo[id]
    const ctrls = []
    const unmapped = []
    const vendorTextBound = new Set()
    const vendorSrcOfNode = {}
    if (ven) {
      for (const el of ven.elements) {
        if (!el.editable) continue
        if (el.sem === 'skip' || el.sem === 'unknown') {
          unmapped.push({ element: el.id, textSource: el.textSourceName, why: '该 textSource 我们没有对应绘制（如 cameraFilterParams）' })
          continue
        }
        const hits = bindNodes(st, el.sem, {})
        if (!hits.length) {
          unmapped.push({ element: el.id, textSource: el.textSourceName, visible: el.visible, gravity: el.gravity, why: '厂商 editable 元素在 IR 里无对应节点 ⇒ 结构缺口（' + (el.visible ? 'visible:true 却没绘制' : 'visible:false 的用户增行，IR 无默认文本/字号') + '），见 risks.md' })
          continue
        }
        if (hits.length > 1) {
          unmapped.push({ element: el.id, textSource: el.textSourceName, why: '多个候选节点 ' + hits.map((h) => h.path).join(',') + ' ⇒ 不绑定（拒绝编控件）' })
          continue
        }
        const n = hits[0]
        const sk = slotKeyOf(st, n)
        const src = id.startsWith('oppo-camerapro') ? 'oppo-camerapro-editable' : 'oppo-style-editable'
        // editable ⇒ 厂商授权"改这行的文字内容"
        ctrls.push(makeText(id, n.path, sk, 'vendor', ev(src, ven.file, 'editable:true textSource:' + el.textSource + '(' + el.textSourceName + ') el#' + el.id, n.synthetic ? 'slotType-unique' : 'tokenKind-unique'), null, 'fallback'))
        vendorTextBound.add(n.path)
        vendorSrcOfNode[n.path] = el
      }
      // 厂商 visible:false 备用元素 = 同一行的另一版内容 ⇒ 记为该行可切换（vendor 证据，但仍是我们新增的开关语义）
      for (const el of ven.elements) {
        if (el.visible || el.sem === 'skip' || el.sem === 'unknown') continue
        const hits = bindNodes(st, el.sem, {})
        if (hits.length !== 1) continue
        const raw = hits[0]
        const n = raw.synthetic ? raw : rebindAffixGroup(st, raw)
        if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === n.path)) continue
        ctrls.push(
          makeToggle(id, n.path, slotKeyOf(st, n), 'vendor', ev('oppo-style-hidden', ven.file, 'visible:false 备用元素 el#' + el.id + ' textSource:' + el.textSourceName + ' ⇒ 厂商自带换行位' + (n.synthetic && !raw.synthetic ? '（开关上提到整组，避免留下孤零前后缀）' : ''), 'tokenKind-unique'))
        )
      }
    }
    for (const x of st.nodes.filter((x) => x.kind === 'text' && x.optional && !ctrls.some((c) => c.kind === 'toggle' && c.targetNode === x.path))) {
      const n = rebindAffixGroup(st, x)
      if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === n.path)) continue
      ctrls.push(makeToggle(id, n.path, slotKeyOf(st, n), irProv(id), irEv(id, 'IR optional:true 但厂商 style JSON 无 editable/visible:false 标志（oppo.cjs 只把 textSource===-1 标 optional）', 'tokenKind-unique', 'ir-optional-heuristic', ven ? ven.file : 'src/renderer/src/core/system/generated')))
    }
    // 单行方位：只在"该行是唯一文字叶子"的 overlay 条带上给，且值域是厂商 layoutGravity 全集
    const GRAV = ['leftTop', 'leftCenter', 'leftBottom', 'centerTop', 'center', 'centerBottom', 'rightTop', 'rightCenter', 'rightBottom', 'bottomHorCenter']
    for (const n of st.nodes.filter((x) => x.kind === 'text' && vendorTextBound.has(x.path))) {
      const inBand = n.path.split('/')[0]
      if (!st.movableBands.includes(inBand)) continue
      const sameParent = st.nodes.filter((m) => m.parent === n.parent && m.kind === 'text')
      if (sameParent.length !== 1) continue
      const el = vendorSrcOfNode[n.path]
      ctrls.push(makeGravity(id, n.path, slotKeyOf(st, n), GRAV, irProv(id), irEv(id, '厂商给该元素 layoutGravity:' + (el && el.gravity) + '，10 值枚举来自 OPPO style JSON 全集；但"单行改方位"厂商没有授权字段', 'tokenKind-unique', 'vendor-enum-universe', ven.file)))
    }
    if (st.themeIds.length) ctrls.push(makeTheme(id, st.themeIds, irProv(id), irEv(id, 'IR themes ' + st.themeIds.length + ' 组（本轮未从 OPPO style JSON 取 background 组数 ⇒ 不算厂商授权，升级路径见 rollout-plan）', 'band-gate', 'ir-slot-structure', ven ? ven.file : 'src/renderer/src/core/system/generated')))
    for (const b of st.movableBands) ctrls.push(...makeAnchor(id, b, irProv(id), irEv(id, '条带 ' + b + ' overlay/rotation ⇒ 位置锚点/微调；厂商 style JSON 无自由拖拽字段', 'band-gate', 'ir-slot-structure', ven ? ven.file : 'src/renderer/src/core/system/generated'), true))
    for (const n of st.nodes.filter((x) => x.kind === 'image' && x.isBrandLogo)) {
      ctrls.push(makeBrand(id, n.path, irProv(id), irEv(id, 'IR isBrandLogo:true（OPPO 侧无厂商字标库证据）', 'slotType-unique', 'ir-slot-structure', ven ? ven.file : 'src/renderer/src/core/system/generated')))
    }
    out.push(buildRecord(st, inv, ctrls, { unmappedVendor: unmapped.length ? unmapped : undefined, ven: ven, notes: ven ? undefined : '厂商 style JSON 未导出（realme 包/手工样式）' }))
  }
  return { records: out }
}

/** vivo：厂商 temp.json 模板名 ⇉ IR styleId（只保留能从名字判定的映射，其余标 inferred） */
const VIVO_MAP = {
  'vivo-zeiss:inside': 'BLUE_ZEISS_INSIDE_PHOTO-202608013',
  'vivo-zeiss:border_top': 'BORDER_ZEISS_TOP-20260317',
  'vivo-zeiss:up_down': 'BORDER_ZEISS_UP_DOWN_PHOTO-20260511',
  'vivo-postcard:frame': 'BORDER_POST_CARD_PHOTO-20260903',
  'vivo-stamp:frame': 'BORDER_STAMP_PHOTO-20260520',
  'vivo-tone:frame': 'BORDER_TONE-20260902',
  'vivo-geographic:overlay': 'GEOGRAPHIC_PHOTO-20260428',
  'vivo-polaroid:frame': 'POLAROID_PHOTO-20260520',
  'vivo-border-photo:frame': 'STYLE_COLOR_PHOTO-20260508'
}

function baselineVivo(irx, vdx) {
  const out = []
  const BIND = {
    pref_camera_watermark_time: { sem: 'date', label: '日期行' },
    pref_camera_watermark_location: { sem: 'location', label: '地点行' },
    pref_camera_watermark_param: { sem: 'exif', label: '参数行' },
    pref_camera_water_mark_logo: { sem: 'model', label: '机型行' },
    pref_camera_watermark_custom_signature_type: { sem: 'custom', label: '落款', asText: true },
    pref_camera_watermark_user_content: { sem: 'custom', label: '自定义内容', asText: true },
    support_text: { sem: 'custom', label: '文字', asText: true },
    pref_camera_watermark_signature_type: { sem: 'custom', label: '落款' }
  }
  const NO_CONTROL = { pref_camera_watermark_qrcode: 'IR 未绘制二维码', pref_camera_watermark_live: 'IR 未绘制 Live 角标', pref_camera_watermark_personal_graphic: 'IR 未绘制个人图形', brand_icon: 'vivo 无 isBrandLogo 节点 ⇒ chooseBrandSlot 无消费对象' }
  for (const id of Object.keys(irx).filter((x) => x.startsWith('vivo-'))) {
    const st = irx[id]
    const inv = INV_STYLES[id]
    const tName = VIVO_MAP[id]
    const vt = tName && vdx.vivo ? vdx.vivo[tName] : null
    const ref = '_research/extracted/vivo-watermarks/assets/' + tName + '/unpacked/assets/temp.json'
    const ctrls = []
    const unmapped = []
    if (vt) {
      if (vt.unSupportEdit) {
        out.push(buildRecord(st, inv, [], { noControlsReason: '厂商 un_support_edit:true ⇒ 该套不可编辑，不发控件', notes: 'vivo ' + tName }))
        continue
      }
      for (const g of vt.editableGroups) {
        const b = BIND[g.key]
        if (!b) {
          if (NO_CONTROL[g.key]) unmapped.push({ key: g.key, why: NO_CONTROL[g.key] })
          else if (g.key === 'pref_camera_watermark_signature_opacity') {
            const targets = st.nodes.filter((n) => n.kind === 'text' && ['custom', 'literal'].includes(n.tokenKind))
            if (targets.length === 1) ctrls.push(makeSlider(id, targets[0].path, slotKeyOf(st, targets[0]), 'alpha', 'vendor', ev('vivo-subcard', ref, g.key + ' 透明度滑杆 optionCount:' + g.optionCount, 'tokenKind-unique')))
            else unmapped.push({ key: g.key, why: '落款文字节点不唯一（' + targets.length + ' 个）⇒ 不发透明度滑杆' })
          } else if (g.key === 'SCALE_LEVEL') {
            unmapped.push({ key: g.key, why: '尺度调节作用于整卡，已由通用面板 fontScale 承担 ⇒ 不重复发控件' })
          } else if (g.key === 'pref_camera_watermark_material') {
            if (st.themeIds.length) ctrls.push(makeTheme(id, st.themeIds, 'vendor', ev('vivo-subcard', ref, g.key + ' 主题圈 ' + g.optionCount + ' 项', 'band-gate')))
            else unmapped.push({ key: g.key, why: '厂商有主题圈但 IR themes 为空 ⇒ 无可消费对象' })
          } else unmapped.push({ key: g.key, why: '未在绑定表里（需人工判定）' })
          continue
        }
        const hits = bindNodes(st, b.sem, {})
        if (!hits.length) {
          unmapped.push({ key: g.key, why: b.sem + ' 在该套 IR 无对应节点（厂商有此开关，我们没绘制）' })
          continue
        }
        if (hits.length > 1) {
          unmapped.push({ key: g.key, why: b.sem + ' 命中 ' + hits.length + ' 个节点，不唯一 ⇒ 拒绝绑定' })
          continue
        }
        const raw = hits[0]
        const n = b.asText || raw.synthetic ? raw : rebindAffixGroup(st, raw)
        const sk = slotKeyOf(st, n)
        if (b.asText) ctrls.push(makeText(id, raw.path, slotKeyOf(st, raw), 'vendor', ev('vivo-subcard', ref, g.key + ' selectable:' + g.selectable + ' options:' + g.optionCount, 'tokenKind-unique'), null, 'fallback'))
        else {
          if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === n.path)) continue
          ctrls.push(makeToggle(id, n.path, sk, 'vendor', ev('vivo-subcard', ref, g.key + ' removable:' + g.removable + ' selectable:' + g.selectable + ' options:' + g.optionCount + (n.synthetic && n !== raw ? '（开关上提到整组，避免留下孤零前后缀）' : ''), 'tokenKind-unique'), b.label))
        }
      }
      if ((vt.positionEnums || []).length || (vt.editableGroups || []).some((g) => g.hasPosition)) {
        for (const bm of st.movableBands) {
          ctrls.push(...makeAnchor(id, bm, 'vendor', ev('vivo-subcard', ref, 'subCard currentPosition/defaultPosition 枚举 ' + JSON.stringify(vt.positionEnums), 'band-gate'), true))
        }
        if (!st.movableBands.length) unmapped.push({ key: '(position)', why: '厂商 subCard 有位置枚举，但该套无可动条带（边框型样式），锚点无消费对象' })
      }
    } else {
      unmapped.push({ key: '(no temp.json)', why: '厂商模板未导出／模板名无法判定 ⇒ 仅给最小集' })
    }
    // 无厂商证据的套：只补 IR 已标的开关（vivo 全 14 套都是 vivoManual.ts 手工样式 ⇒ manual）
    for (const x of st.nodes.filter((n) => n.kind === 'text' && n.optional)) {
      if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === x.path)) continue
      const n = rebindAffixGroup(st, x)
      if (ctrls.some((c) => c.kind === 'toggle' && c.targetNode === n.path)) continue
      ctrls.push(makeToggle(id, n.path, slotKeyOf(st, n), irProv(id), irEv(id, 'IR optional:true，无 vivo subCard 证据可比对', 'tokenKind-unique', 'ir-optional-heuristic', 'src/renderer/src/core/system/vivoManual.ts')))
    }
    if (st.themeIds.length && !ctrls.some((c) => c.kind === 'themeSwatches')) {
      ctrls.push(makeTheme(id, st.themeIds, irProv(id), irEv(id, 'IR themes ' + st.themeIds.length + ' 组（厂商 pref_camera_watermark_material 未命中该套）', 'band-gate', 'ir-slot-structure', 'src/renderer/src/core/system/vivoManual.ts')))
    }
    for (const bm of st.movableBands) {
      if (ctrls.some((c) => c.kind === 'anchorGrid' && c.targetBand === bm)) continue
      ctrls.push(...makeAnchor(id, bm, irProv(id), irEv(id, '条带 ' + bm + ' overlay/rotation ⇒ 位置锚点最小集；厂商 subCard 无该套位置枚举', 'band-gate', 'ir-slot-structure', 'src/renderer/src/core/system/vivoManual.ts'), true))
    }
    out.push(buildRecord(st, inv, ctrls, { unmappedVendor: unmapped.length ? unmapped : undefined, ven: vt, notes: vt ? 'vivo 模板 ' + tName : '无厂商 temp.json 映射 ⇒ 最小集（inferred）' }))
  }
  return { records: out }
}

/* =====================================================================
 * 6. 校验器
 * ===================================================================== */

function parseSchemaConstants() {
  const ts = fs.readFileSync(SCHEMA_TS, 'utf8')
  const consumers = {}
  const block = ts.slice(ts.indexOf('CONTROL_CONSUMERS'))
  const re = /'([a-zA-Z]+\.[a-zA-Z]+)':\s*\{([\s\S]*?)\n\s*\}/g
  let m
  while ((m = re.exec(block))) {
    const body = m[2]
    const file = (body.match(/file:\s*'([^']+)'/) || [])[1]
    const symbol = (body.match(/symbol:\s*'([^']+)'/) || [])[1]
    const status = (body.match(/status:\s*'([^']+)'/) || [])[1]
    const noOp = (body.match(/noOpWhenDefault:\s*\n?\s*'([^']*)'/) || [])[1]
    consumers[m[1]] = { key: m[1], file, symbol, status, noOp }
  }
  const gravity = (ts.match(/OPPO_LAYOUT_GRAVITY = \[([\s\S]*?)\]/) || [])[1]
  const gravityOptions = gravity ? [...gravity.matchAll(/'([^']+)'/g)].map((x) => x[1]) : []
  const fallback = Number((ts.match(/TEXT_MAX_LENGTH_FALLBACK = (\d+)/) || [])[1])
  const kindBlock = (ts.match(/export type ControlKind =\n([\s\S]*?)\n\n/) || [])[1] || ''
  const kindList = [...kindBlock.matchAll(/'([a-zA-Z]+)'/g)].map((x) => x[1])
  if (kindList.length !== 8) throw new Error('controls-schema.ts: ControlKind 解析到 ' + kindList.length + ' 个（应为 8）: ' + kindList.join(','))
  if (Object.keys(consumers).length !== 9) throw new Error('controls-schema.ts: CONTROL_CONSUMERS 解析到 ' + Object.keys(consumers).length + ' 个（应为 9）')
  if (!gravityOptions.length) throw new Error('controls-schema.ts: OPPO_LAYOUT_GRAVITY 解析失败')
  return { consumers, gravityOptions, fallback, kindList }
}

function validate(dir, opts) {
  const o = opts || {}
  const errors = []
  const irx = o.irx || load(IRX_P).styles
  const sch = parseSchemaConstants()
  const files = fs.readdirSync(dir).filter((f) => /^controls\..+\.json$/.test(f))
  if (!files.length) errors.push('FATAL no controls.*.json in ' + dir)
  const seenStyle = {}
  const covered = new Set()
  for (const f of files) {
    const doc = load(path.join(dir, f))
    if (!doc || !Array.isArray(doc.records)) {
      errors.push('FATAL ' + f + ' has no records[]')
      continue
    }
    for (const r of doc.records) {
      const W = f + ' ' + (r.styleId || '(no styleId)')
      if (!r.styleId) {
        errors.push('ORPHAN ' + W + ' record without styleId')
        continue
      }
      if (!irx[r.styleId]) errors.push('ORPHAN ' + W + ' styleId not in IR STOCK_STYLES (106 only; blocked/variant styles must not appear)')
      if (seenStyle[r.styleId]) errors.push('DUPSTYLE ' + W + ' also declared in ' + seenStyle[r.styleId])
      seenStyle[r.styleId] = f
      if (irx[r.styleId]) covered.add(r.styleId)
      if (irx[r.styleId]) {
        const irs = irx[r.styleId]
        if (r.vendor !== irs.vendor) errors.push('MISMATCHVENDOR ' + W + ' record.vendor=' + r.vendor + ' 但 IR 是 ' + irs.vendor)
        if (r.placement !== irs.placement) errors.push('MISMATCHPLACE ' + W + ' record.placement=' + r.placement + ' 但 IR 是 ' + irs.placement)
        const wantSource = MANUAL_OVERRIDDEN[r.styleId] ? 'manual' : 'baseline'
        if (r.source !== wantSource) errors.push('BADSOURCE ' + W + ' source=' + r.source + '，应为 ' + wantSource + (wantSource === 'manual' ? '（该套在 styles.ts/vivoManual.ts 手工编写）' : ''))
      }
      if (!['declared', 'generic'].includes(r.panel)) errors.push('BADPANEL ' + W + ' panel=' + r.panel)
      if (r.panel === 'generic' && (!r.controls || !r.controls.length) && !r.noControlsReason) errors.push('NOPANELREASON ' + W + ' panel=generic 必须写 noControlsReason')
      if (r.panel === 'declared' && (!r.controls || r.controls.length === 0)) errors.push('EMPTYDECL ' + W + ' panel=declared 但 controls 为空')
      const ids = new Set()
      for (const c of r.controls || []) {
        const C = W + ' ctl=' + c.id
        if (!sch.kindList.includes(c.kind)) errors.push('BADKIND ' + C + ' kind=' + c.kind)
        if (ids.has(c.id)) errors.push('DUPID ' + C)
        ids.add(c.id)
        if (!c.consumer || !sch.consumers[c.consumer]) errors.push('NOCONSUMER ' + C + ' consumer=' + c.consumer + ' not in CONTROL_CONSUMERS')
        if (!['vendor', 'inferred', 'manual'].includes(c.provenance)) errors.push('BADPROV ' + C + ' provenance=' + c.provenance)
        if (c.provenance === 'manual' && !MANUAL_OVERRIDDEN[r.styleId]) errors.push('BADPROV ' + C + " provenance=manual 但该套不是手工样式（不该洗白成 manual 规避厂商证据）")
        if (!c.evidence || !c.evidence.source || !c.evidence.ref || !c.evidence.detail) errors.push('BADEVID ' + C + ' evidence incomplete')
        else if (c.provenance === 'vendor' && !VENDOR_SOURCES.includes(c.evidence.source)) errors.push('PROVMISMATCH ' + C + " provenance=vendor 但 evidence.source=" + c.evidence.source + ' 不是厂商授权来源')
        else if (c.provenance === 'inferred' && !INFERRED_SOURCES.includes(c.evidence.source)) errors.push('PROVMISMATCH ' + C + " provenance=inferred 但 evidence.source=" + c.evidence.source)
        else if (c.provenance === 'manual' && !MANUAL_SOURCES.includes(c.evidence.source)) errors.push('PROVMISMATCH ' + C + ' provenance=manual 但 evidence.source=' + c.evidence.source)
        // 节点路径 / 条带存在性
        const st = irx[r.styleId]
        if (c.targetNode && st && !st.paths.includes(c.targetNode)) errors.push('BADPATH ' + C + ' targetNode=' + c.targetNode + ' 不存在于该套 IR（越界/拼错）')
        if (c.targetNode && st) {
          const tn = st.nodes.find((x) => x.path === c.targetNode)
          if (tn && tn.kind === 'group') {
            const swallowed = st.nodes.filter((x) => x.path.indexOf(c.targetNode + '/') === 0 && (x.kind === 'image' || x.kind === 'shape'))
            if (swallowed.length) errors.push('GROUPSWALLOW ' + C + ' 绑定到 group ' + c.targetNode + ' 会连带隐藏非文字节点 ' + swallowed.map((s) => s.kind + ':' + s.path).join(','))
          }
          if (c.kind === 'text' || c.kind === 'slider' || c.kind === 'gravitySegment') {
            if (!tn || tn.kind !== 'text') errors.push('BADTARGET ' + C + ' ' + c.kind + ' 必须绑文字叶子，实际=' + (tn ? tn.kind : 'none'))
          }
          if (c.kind === 'toggle' && tn && !['text', 'group'].includes(tn.kind)) errors.push('BADTARGET ' + C + ' toggle 只能绑文字叶子或整组，实际=' + tn.kind)
        }
        if (c.targetBand && st && !st.movableBands.includes(c.targetBand)) errors.push('BANDGATE ' + C + ' targetBand=' + c.targetBand + ' 非 overlay/rotation 条带 ⇒ 锚点无消费对象')
        if (c.kind === 'anchorGrid' && c.domain && c.domain.options && c.domain.options[0] !== 'vendor') errors.push('BADANCHOR ' + C + " 第一档必须是 'vendor'")
        if (c.kind === 'themeSwatches') {
          if (!st || !st.themeIds.length) errors.push('NOTHEMES ' + C + ' 该套 IR 无 themes ⇒ 主题控件非法')
          else if (c.domain.options.join(',') !== st.themeIds.join(',')) errors.push('THEMEDOMAIN ' + C + ' options 与 IR themes 不一致')
        }
        if (c.kind === 'brandPicker') {
          const n = st && st.nodes.find((x) => x.path === c.targetNode)
          if (!n || !n.isBrandLogo) errors.push('BRANDGATE ' + C + ' 非 isBrandLogo 节点 ⇒ chooseBrandSlot 不会消费')
        }
        if (c.kind === 'gravitySegment') {
          const bad = (c.domain.options || []).filter((x) => !sch.gravityOptions.includes(x))
          if (bad.length) errors.push('GRAVITYDOMAIN ' + C + ' 非法方位值 ' + bad.join(','))
        }
        if (c.kind === 'slider') {
          const d = c.domain
          if (!d || !(d.min < d.max) || d.step <= 0 || d.step > d.max - d.min) errors.push('SLIDERDOMAIN ' + C + ' ' + JSON.stringify(d))
          if (d.unit === 'alpha' && (d.min < 0 || d.max > 1)) errors.push('SLIDERDOMAIN ' + C + ' alpha 越出 [0,1]')
          if (d.unit === 'multiplier' && (d.min <= 0 || d.max > 3)) errors.push('SLIDERDOMAIN ' + C + ' 字号乘子非法')
          if (c.param === 'slotScale' && c.default !== 1) errors.push('BADDEFAULT ' + C + ' slotScale 默认必须是 1')
          if (c.param === 'slotAlpha' && c.default !== null) errors.push('BADDEFAULT ' + C + ' slotAlpha 默认必须是 null')
        }
        if (c.kind === 'toggle') {
          if (c.default !== 'on') errors.push('BADDEFAULT ' + C + ' toggle 默认必须 on（＝当前行为）')
          if (!Array.isArray(c.domain.on) && c.domain.on !== true) errors.push('BADDOMAIN ' + C + ' toggle domain')
        }
        if (c.kind === 'text') {
          if (c.default !== null) errors.push('BADDEFAULT ' + C + ' text 默认必须 null')
          if (c.domain.maxLength !== null && (c.domain.maxLength <= 0 || c.domain.maxLength > 512)) errors.push('TEXTDOMAIN ' + C + ' maxLength=' + c.domain.maxLength)
          if (c.domain.maxLength === null && c.domain.maxLengthSource !== 'fallback') errors.push('TEXTDOMAIN ' + C + ' maxLength=null 时 source 必须 fallback')
          if (c.domain.maxLengthSource === 'xiaomi-meta-limitation' && c.provenance !== 'vendor') errors.push('TEXTPROV ' + C + ' 厂商长度上限却非 vendor')
          if (!('minLength' in c.domain)) errors.push('TEXTDOMAIN ' + C + ' domain.minLength 缺字段（schema 要求显式 null 或数字）')
          else if (c.domain.minLength !== null) {
            if (typeof c.domain.minLength !== 'number' || c.domain.minLength < 0) errors.push('TEXTDOMAIN ' + C + ' minLength=' + c.domain.minLength)
            if (c.domain.maxLength !== null && c.domain.minLength > c.domain.maxLength) errors.push('TEXTDOMAIN ' + C + ' minLength>maxLength')
            if (c.domain.maxLengthSource !== 'xiaomi-meta-limitation') errors.push('TEXTDOMAIN ' + C + ' 有厂商下界却没有厂商来源')
          }
        }
        if (c.kind === 'xyOffset') {
          if (c.default !== 0) errors.push('BADDEFAULT ' + C + ' xyOffset 默认必须 0')
          if (!c.domain || c.domain.min !== -c.domain.max) errors.push('BANDSYM ' + C + ' 偏移值域必须对称')
        }
      }
    }
  }
  const missing = Object.keys(irx).filter((id) => !covered.has(id))
  if (missing.length) errors.push('MISSING ' + missing.length + ' 套没有记录: ' + missing.slice(0, 12).join(','))
  return { errors, stats: { files: files.length, records: Object.keys(seenStyle).length, irStyles: Object.keys(irx).length, missing: missing.length } }
}

/* =====================================================================
 * 7. 批次计划
 * ===================================================================== */

function rollout(irx) {
  const sch = parseSchemaConstants()
  const files = fs.readdirSync(STAGING).filter((f) => /^controls\..+\.json$/.test(f))
  const recs = []
  for (const f of files) for (const r of load(path.join(STAGING, f)).records) recs.push(r)
  const declared = recs.filter((r) => r.panel === 'declared')
  const CONSUMER_FILE = {
    'toggle.slotOff': ['src/renderer/src/core/system/layout.ts'],
    'text.slotText': ['src/renderer/src/core/system/tokens.ts', 'src/renderer/src/core/system/layout.ts'],
    'slider.slotScale': ['src/renderer/src/core/system/layout.ts'],
    'slider.slotAlpha': ['src/renderer/src/core/system/paint.ts'],
    'anchorGrid.stockAnchor': ['src/renderer/src/core/system/layout.ts'],
    'xyOffset.stockOffset': ['src/renderer/src/core/system/layout.ts'],
    'gravity.slotGravity': ['src/renderer/src/core/system/layout.ts'],
    'theme.theme': ['src/renderer/src/core/system/paint.ts', 'src/renderer/src/core/system/assets.ts'],
    'brand.brandLogoFile': ['src/renderer/src/core/system/assets.ts']
  }
  const KIND_LABEL = {
    toggle: '行开关',
    text: '文字输入',
    slider: '滑杆',
    anchorGrid: '九宫格锚点',
    xyOffset: 'XY 微调',
    themeSwatches: '主题色板',
    brandPicker: '品牌字标',
    gravitySegment: '单行方位'
  }
  const tier = (r) => {
    const hasBrand = (r.controls || []).some((c) => c.kind === 'brandPicker')
    const cobrand = /leica|hassel/i.test(r.styleId)
    if (hasBrand && cobrand) return 0
    if (hasBrand) return 1
    if (cobrand && /oppo-gallery/.test(r.styleId)) return 2
    if (/^xiaomi-/.test(r.styleId)) return 3
    if (/^oppo-/.test(r.styleId)) return 4
    return 5
  }
  const TIER_WHY = {
    0: 'tier0 带品牌字标槽 + 徕卡/哈苏系：用户机身 Panasonic LUMIX S1（另有 Sony/DJI），字标随照片自动换＝改了就看得见',
    1: 'tier1 其余带品牌字标槽的小米系',
    2: 'tier2 OPPO 哈苏/徕卡副厂字标系（厂商 editable 证据集中营）',
    3: 'tier3 其余小米套',
    4: 'tier4 其余 OPPO 套（含 realme/CameraPro）',
    5: 'tier5 vivo 套（vivoManual.ts 手工样式，subCard 证据）'
  }
  const ordered = declared.slice().sort((a, b) => tier(a) - tier(b) || a.styleId.localeCompare(b.styleId))
  const batches = []
  for (let i = 0; i < ordered.length; i += 5) {
    const slice = ordered.slice(i, i + 5)
    const kinds = new Set()
    const consumers = new Set()
    const srcFiles = new Set()
    const pending = new Set()
    const perStyle = []
    for (const r of slice) {
      for (const c of r.controls) {
        kinds.add(c.kind)
        consumers.add(c.consumer)
        for (const f of CONSUMER_FILE[c.consumer] || []) srcFiles.add(f)
        const sc = sch.consumers[c.consumer]
        if (sc && sc.status !== 'existing') pending.add(c.consumer + ' → ' + sc.file + '#' + sc.symbol + ' [' + sc.status + ']')
      }
      perStyle.push({
        styleId: r.styleId,
        controls: r.controls.length,
        vendor: r.controls.filter((c) => c.provenance === 'vendor').length,
        inferred: r.controls.filter((c) => c.provenance === 'inferred').length,
        manual: r.controls.filter((c) => c.provenance === 'manual').length,
        kinds: [...new Set(r.controls.map((c) => c.kind))].map((k) => KIND_LABEL[k] || k).join('/')
      })
    }
    const n = batches.length + 1
    if (n === 1) srcFiles.add('src/renderer/src/components/Inspector.tsx')
    batches.push({
      batch: n,
      tier: tier(slice[0]),
      why: TIER_WHY[tier(slice[0])],
      styleCount: slice.length,
      styleIds: slice.map((r) => r.styleId),
      perStyle,
      controlKinds: [...kinds].sort(),
      consumersNeeded: [...consumers].sort(),
      consumersToWrite: [...pending],
      srcFiles: [...srcFiles].sort(),
      newFiles:
        n === 1
          ? [
              'src/renderer/src/core/system/controls/schema.ts（= staging/controls-schema.ts 原样搬入）',
              'src/renderer/src/core/system/controls/registry.ts（getStockControls(styleId)，manual 覆盖 baseline）',
              'src/renderer/src/core/system/controls/controls-baseline.generated.ts（本脚本 3 个 controls.<family>.json 合并导出）',
              'src/renderer/src/core/system/controls/controls-manual.ts'
            ]
          : ['src/renderer/src/core/system/controls/controls-baseline.generated.ts（追加本批套）'],
      acceptance: [
        '默认零分支：本批每套把每个控件置为 default（或删掉对应 param 键）⇒ 渲染输出与基线 Buffer.equals 逐字节一致',
        '175 张桌面基线 PNG 逐字节不变（npm run 基线复跑；这是硬门，任何一张变了都算回退）',
        'npm run typecheck（tsconfig.web）+ npm run lint 通过',
        'e2e 62 项（watermark/inspector 相关）通过；.anchor-grid button[title] 选择器不变',
        '真变化证明：本批每套至少 1 个控件设为非 default ⇒ 预览像素确实改变（假控件检测）',
        'node scripts/system-camera/validate-ir.cjs 与本脚本 --validate 退出码 0'
      ],
      verifyNote: n === 1 ? '批1 同时要把 Inspector 的 stock 面板改成"有声明按声明渲染、无声明走现通用面板"，并摘除无消费方的 showMake/showLens/showLogo 三枚 chip' : '沿用批1 的面板框架，只加数据与本批消费函数'
    })
  }
  const notScheduled = recs.filter((r) => r.panel === 'generic').map((r) => ({ styleId: r.styleId, reason: r.noControlsReason }))
  return {
    schema: 'framemark-controls-rollout/v1',
    note: '每批 <=5 套（子 agent 150 轮上限）。优先级：tier0=带品牌字标槽的徕卡/哈苏系（用户 LUMIX S1 + Sony/DJI）→ 其余小米 → OPPO 哈苏/徕卡 → 其余 OPPO → vivo。',
    inputs: ['staging/controls.xiaomi.json', 'staging/controls.oppo.json', 'staging/controls.vivo.json', 'staging/controls-schema.ts'],
    totalRecords: recs.length,
    totalDeclared: declared.length,
    totalGeneric: notScheduled.length,
    batchCount: batches.length,
    globalFirstTasks: [
      '把 staging/controls-schema.ts 落为 src/renderer/src/core/system/controls/schema.ts（唯一真源，typecheck 才认）',
      '新增 layout.ts#isSlotOff（toggle 消费函数，slotOff 为空数组时整体短路）——批1 的阻塞项',
      'Inspector：getStockControls(styleId) 命中 ⇒ 按声明顺序渲染；未命中 ⇒ 现面板（零变化）',
      'build-controls.cjs 的 --baseline/--validate 挂进 CI（与 validate-ir.cjs 同处），防假控件回潮'
    ],
    blockedStyles: [
      { group: 'xiaomi-leica 5/88/89', count: 3, why: 'outer-border 侧栏绝对布局 / with 布局含 218dp 容器 + 照片内叠加，缺可用总宽度 ⇒ 无 IR', action: '不发控件；等布局接入后从 config.json 直接重跑本脚本即可拿到 vendor 证据' },
      { group: 'vivo 其余模板（含 DEFAULT_VIDEO、5 个 DISNEY）', count: 28, why: 'temp.json 有 subCard 证据但未导出 IR', action: 'controls 证据已备（vendor-detail.json 的 vivo 段），接入布局后 VIVO_MAP 补映射即复用' },
      { group: 'oppo-gallery:personalize_brand_3', count: 1, why: '只在旧拷贝（extracted/）里有这份 style JSON，适配器权威源 vendor-latest 与 IR 都没有 ⇒ 无套可挂（版本差，见 risks.md R0）', action: '不发控件；若厂商后续恢复该套，重跑 --extract-vendor 即自动进 baseline' },
      { group: 'oppo-camerapro:polaroid_pld_style_1', count: 1, why: '厂商 pack 有 editable，但本项目只做变体不做独立套（107 套口径里的"仅变体"）', action: '控件声明留空；变体级覆盖由 TemplateDef.params 承担' }
    ],
    batches,
    genericRecords: notScheduled
  }
}

/* =====================================================================
 * main
 * ===================================================================== */

;(async () => {
  const argv = process.argv.slice(2)
  const has = (x) => argv.some((a) => a.startsWith(x))
  const val = (x) => (argv.find((a) => a.startsWith(x + '=')) || '').split('=')[1]
  const invDoc = load(INV_P)
  INV_STYLES = {}
  INV_FP = {}
  for (const s of invDoc.styles) {
    INV_STYLES[s.id] = s
    INV_FP[s.id] = [s.placement, s.rotatedBand ? 'rot' : '', s.hasThemes ? 'themes' : '', ...[...new Set((s.slots || []).map((x) => x.type))].sort()].filter(Boolean).join('|')
  }

  let irx
  if (has('--extract-ir') || has('--extract')) {
    irx = await extractIr()
    console.log('ir-index.json written:', Object.keys(irx).length, 'styles;', Object.values(irx).reduce((n, s) => n + s.nodes.length, 0), 'nodes;', Object.values(irx).filter((s) => s.movableBands.length).length, 'styles with movable bands')
  }
  if (has('--extract-vendor') || has('--extract')) {
    const v = extractVendor()
    const editable = Object.values(v.oppo).reduce((n, s) => n + s.elements.filter((e) => e.editable).length, 0)
    const optViews = Object.values(v.xiaomi).reduce((n, s) => n + s.optionalViews.length, 0)
    const gal = Object.keys(v.oppo).filter((k) => k.startsWith('oppo-gallery')).length
    const irGal = Object.keys(irx || {}).filter((k) => k.startsWith('oppo-gallery')).length
    console.log('vendor-detail.json written: oppo 相册', gal, '份（源', v.sources.oppoGallery, v.sources.oppoGalleryIsAuthoritative ? '= 适配器权威源' : '!! 旧拷贝 !!', '）/ CameraPro', Object.keys(v.oppo).length - gal, '份 | editable 元素', editable, '| xiaomi configs', Object.keys(v.xiaomi).length, '(optional views', optViews + ')', '| vivo templates', Object.keys(v.vivo || {}).length)
    if (!v.sources.oppoGalleryIsAuthoritative) console.log('WARN  OPPO 相册 style JSON 用的是 extracted/ 旧拷贝（少 realme 套），不是 oppo.cjs 的输入目录')
    if (irx && gal !== irGal) console.log('NOTE  厂商相册 style JSON', gal, '份 vs IR oppo-gallery 套', irGal, '个（差集见 controls.oppo.json 的 notes/unmappedVendor）')
    irx = irx || (fs.existsSync(IRX_P) ? load(IRX_P).styles : await extractIr())
  }
  if (!irx && (has('--baseline') || has('--diff') || has('--rollout'))) irx = load(IRX_P).styles
  const vdx = fs.existsSync(VDX_P) ? load(VDX_P) : extractVendor()

  if (has('--baseline')) {
    const fam = val('--baseline') || 'all'
    for (const f of ['xiaomi', 'oppo', 'vivo']) {
      if (fam !== 'all' && fam !== f) continue
      const built = f === 'xiaomi' ? baselineXiaomi(irx, vdx) : f === 'oppo' ? baselineOppo(irx, vdx) : baselineVivo(irx, vdx)
      const doc = {
        schema: 'framemark-stock-style-controls/v1',
        family: f,
        generatedBy: 'scripts/system-camera/build-controls.cjs',
        inputs: ['_research/editing-model/slot-inventory.json', '_research/editing-model/vendor-edit-model.json', '_research/controls-impl/staging/ir-index.json', '_research/controls-impl/staging/vendor-detail.json'],
        records: built.records
      }
      write(path.join(STAGING, 'controls.' + f + '.json'), doc)
      const prov = { vendor: 0, inferred: 0, manual: 0 }
      let ctl = 0
      const byKind = {}
      for (const r of built.records) for (const c of r.controls) {
        prov[c.provenance] = (prov[c.provenance] || 0) + 1
        byKind[c.kind] = (byKind[c.kind] || 0) + 1
        ctl++
      }
      console.log('controls.' + f + '.json:', built.records.length, 'records,', ctl, 'controls, provenance', JSON.stringify(prov), 'kinds', JSON.stringify(byKind))
      if (built.diff) {
        write(path.join(STAGING, 'xiaomi-optional-diff.json'), built.diff)
        const extra = built.diff.reduce((n, d) => n + d.vendorOnly.length, 0)
        const irOnly = built.diff.reduce((n, d) => n + d.irOnly.length, 0)
        console.log('  xiaomi diff: vendor-mapped-not-in-IR', extra, '| IR-optional-not-vendor-authorized', irOnly, '-> staging/xiaomi-optional-diff.json')
      }
    }
  }

  if (has('--publish')) {
    // 唯一投放出口：staging 草稿 → 原生工程自有目录。只拷贝、不重新生成，
    // 所以发布前必须先跑 --baseline 并让它通过 --validate。
    fs.mkdirSync(PUBLISH_DIR, { recursive: true })
    const published = []
    for (const f of ['oppo', 'xiaomi', 'vivo']) {
      const from = path.join(STAGING, 'controls.' + f + '.json')
      if (!fs.existsSync(from)) {
        console.log('PUBLISH FAIL — staging 草稿不存在:', from)
        process.exit(1)
      }
      const doc = JSON.parse(fs.readFileSync(from, 'utf8'))
      if (!Array.isArray(doc.records) || !doc.records.length) {
        console.log('PUBLISH FAIL — staging 草稿没有 records:', from)
        process.exit(1)
      }
      const to = path.join(PUBLISH_DIR, 'controls.' + f + '.json')
      fs.copyFileSync(from, to)
      published.push({
        path: to,
        records: doc.records.length,
        sha256: crypto.createHash('sha256').update(fs.readFileSync(to)).digest('hex')
      })
    }
    console.log('PUBLISH OK -> ' + PUBLISH_DIR)
    for (const row of published) console.log('  ' + path.basename(row.path), row.records, 'records', row.sha256)
  }

  if (has('--validate')) {
    const dir = val('--dir') || STAGING
    const res = validate(dir)
    if (res.errors.length) {
      console.log('VALIDATE FAIL —', res.errors.length, 'errors')
      for (const e of res.errors.slice(0, 40)) console.log('  ' + e)
      process.exit(1)
    }
    console.log('VALIDATE OK', JSON.stringify(res.stats))
    process.exit(0)
  }

  if (has('--selftest')) {
    const dir = path.join(STAGING, '_selftest')
    fs.mkdirSync(dir, { recursive: true })
    for (const f of fs.readdirSync(STAGING).filter((x) => /^controls\..+\.json$/.test(x))) fs.copyFileSync(path.join(STAGING, f), path.join(dir, f))
    const target = path.join(dir, 'controls.' + (val('--family') || 'xiaomi') + '.json')
    const doc = load(target)
    const good = doc.records.find((r) => r.controls && r.controls.length)
    doc.records.push({
      schemaVersion: 'framemark-stock-style-controls/v1',
      styleId: 'xiaomi-notreal:999',
      vendor: 'xiaomi',
      family: 'notreal',
      name: '自我测试：孤儿 styleId',
      placement: 'out',
      rotatedBand: false,
      fingerprint: 'out|bogus',
      source: 'baseline',
      panel: 'declared',
      controls: []
    })
    good.controls.push({ id: good.controls[0].id, kind: 'toggle', label: '自我测试：重复 id', group: 'content', consumer: 'toggle.NONEXISTENT', provenance: 'vendor', evidence: { source: 'ir-optional-heuristic', ref: 'x', detail: 'inferred 来源冒充 vendor', match: 'tokenKind-unique' }, param: 'slotOff', targetNode: 'b9/bottom9/g7/g7', default: 'off', domain: { on: true, off: true } })
    // 第三条：开关绑到会连带吞掉 image 子节点的组（证明 GROUPSWALLOW 规则也活着）
    const swallow = doc.records.find((r) => r.styleId === 'xiaomi-film:12')
    if (swallow) swallow.controls.push({ id: 'toggle.groupswallow', kind: 'toggle', label: '自我测试：整组吞图', group: 'content', consumer: 'toggle.slotOff', provenance: 'inferred', evidence: { source: 'ir-optional-heuristic', ref: 'x', detail: '故意绑到会隐藏 image 的组', match: 'slotType-unique' }, param: 'slotOff', targetNode: 'b0/bottom0/g1', default: 'on', domain: { on: true, off: true }, fidelityRisk: true })
    write(target, doc)
    const res = validate(dir)
    console.log('SELFTEST injected 2 bad controls / 1 orphan styleId ->', res.errors.length, 'errors')
    for (const e of res.errors) console.log('  ' + e)
    // 真正跑一次 CLI，证明 --validate 的进程退出码非 0（不是只返回数组长度）
    const child = require('child_process').spawnSync(process.execPath, [__filename, '--validate', '--dir=' + dir], { encoding: 'utf8' })
    console.log('  child exit code =', child.status)
    console.log('  child stdout head =', String(child.stdout).split('\n')[0])
    fs.rmSync(dir, { recursive: true, force: true })
    console.log('selftest dir removed:', !fs.existsSync(dir))
    process.exit(res.errors.length && child.status === 1 ? 0 : 1)
  }

  if (has('--diff')) {
    const diff = load(path.join(STAGING, 'xiaomi-optional-diff.json'))
    const xm = load(path.join(STAGING, 'controls.xiaomi.json')).records
    const byId = {}
    for (const r of xm) byId[r.styleId] = r
    const rows = diff
      .slice()
      .sort((a, b) => b.vendorOnly.length + b.irOnly.length - (a.vendorOnly.length + a.irOnly.length))
      .map((d) => {
        const r = byId[d.styleId] || { controls: [] }
        const vT = r.controls.filter((c) => c.provenance === 'vendor' && c.kind === 'toggle').length
        const iT = r.controls.filter((c) => c.provenance !== 'vendor' && c.kind === 'toggle').length
        return '| ' + d.styleId + ' | ' + d.vendorOptionalViews + ' | ' + d.vendorMappedNodes.length + ' | ' + d.irOptionalNodes.length + ' | ' + vT + ' | ' + iT + ' | +' + d.vendorOnly.length + ' / -' + d.irOnly.length + ' | ' + d.unmappedVendorViews + ' |'
      })
    const tot = diff.reduce(
      (m, d) => ({
        vendorViews: m.vendorViews + d.vendorOptionalViews,
        mapped: m.mapped + d.vendorMappedNodes.length,
        irOpt: m.irOpt + d.irOptionalNodes.length,
        vendorOnly: m.vendorOnly + d.vendorOnly.length,
        irOnly: m.irOnly + d.irOnly.length,
        unmatched: m.unmatched + d.unmappedVendorViews,
        differing: m.differing + (d.vendorOnly.length || d.irOnly.length ? 1 : 0)
      }),
      { vendorViews: 0, mapped: 0, irOpt: 0, vendorOnly: 0, irOnly: 0, unmatched: 0, differing: 0 }
    )
    const md = [
      '# 小米：厂商真 optional:true vs 我们 IR 启发式 optional 的差异表',
      '',
      '数据源：厂商 `_research/extracted/xiaomi-watermarks-v32/<family>/<id>/config.json` 的 `optional:true` 视图（229 处，含挂在 Layout 容器上的：用子树文本落到具体行）；',
      'IR 侧：`ir-index.json`（esbuild 直接加载 styles.ts 得到的 `optional` 标志）。',
      '',
      '| styleId | 厂商 optional 视图数 | 落到 IR 节点数 | IR optional 节点数 | vendor toggle | 非 vendor toggle | 多判(+)/漏判(-) | 厂商有但我们绑不上 |',
      '|---|---|---|---|---|---|---|---|',
      rows.join('\n'),
      '',
      '## 汇总',
      '',
      '- 厂商 optional 视图合计 **' + tot.vendorViews + '** 处；能唯一绑到 IR 节点的 **' + tot.mapped + '** 处；',
      '- 我们 IR 的 optional 节点 **' + tot.irOpt + '** 个；',
      '- **厂商授权但我们 IR 没标 optional（多判缺失）= ' + tot.vendorOnly + ' 个节点** ⇒ baseline 用 vendor provenance 补上开关；',
      '- **我们 IR 标了 optional 但厂商没授权（漏判/自造）= ' + tot.irOnly + ' 个节点** ⇒ 记 inferred（手工套记 manual）且 fidelityRisk=true，进 risks.md；',
      '- 厂商视图存在但绑不上（容器无文本 / 文本非槽位模板）= ' + tot.unmatched + ' 处；',
      '- **40/40 套全部存在差异**（vendor ≠ IR 的套数 = ' + tot.differing + '），比设计轮报的 22/40 更严重：差异是按节点计数、不是按套计数。',
      '',
      '> 结论：适配器 `oppo.cjs`/小米侧的 optional 判定是文本正则启发式，不能作为编辑面授权依据；baseline 一律以厂商 JSON 为准（本表即证据）。'
    ].join('\n')
    write(path.join(STAGING, 'xiaomi-optional-diff.md'), md)
    console.log('xiaomi-optional-diff.md written. totals:', JSON.stringify(tot))
  }

  if (has('--tscheck')) {
    // 用项目自带的 typescript 对 staging/controls-schema.ts 做 strict 诊断（staging 不在 tsconfig.web 里，
    // 所以搬进 src/ 之前必须这样兜一次语法/类型）。只读，不写任何文件。
    const ts = require(path.join(FRAMEMARK, 'node_modules', 'typescript'))
    const src = fs.readFileSync(SCHEMA_TS, 'utf8')
    const sf = ts.createSourceFile(SCHEMA_TS, src, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
    const syn = sf.parseDiagnostics || []
    const host = ts.createCompilerHost({ target: ts.ScriptTarget.ES2022 })
    const orig = host.getSourceFile
    host.getSourceFile = (name, lang, err) => (name === SCHEMA_TS ? ts.createSourceFile(name, src, lang, true) : orig(name, lang, err))
    const prog = ts.createProgram([SCHEMA_TS], { target: ts.ScriptTarget.ES2022, strict: true, noEmit: true, skipLibCheck: true, lib: ['lib.es2022.d.ts'] }, host)
    const sem = ts.getPreEmitDiagnostics(prog).filter((d) => d.file && d.file.fileName === SCHEMA_TS)
    console.log('TSCHECK typescript', ts.version, '| syntax', syn.length, '| semantic', sem.length)
    for (const d of syn.concat(sem).slice(0, 12)) {
      const pos = d.file.getLineAndCharacterOfPosition(d.start)
      console.log('  L' + (pos.line + 1) + ' ' + ts.flattenDiagnosticMessageText(d.messageText, ' '))
    }
    process.exit(syn.length || sem.length ? 1 : 0)
  }

  if (has('--riskdata')) {
    const recs = []
    for (const f of fs.readdirSync(STAGING).filter((x) => /^controls\..+\.json$/.test(x))) for (const r of load(path.join(STAGING, f)).records) recs.push(r)
    const irxAll = load(IRX_P).styles
    const risk = recs
      .filter((r) => r.controls.some((c) => c.fidelityRisk))
      .map((r) => ({
        styleId: r.styleId,
        placement: r.placement,
        rotatedBand: r.rotatedBand,
        nodes: r.controls
          .filter((c) => c.fidelityRisk)
          .map((c) => ({ kind: c.kind, id: c.id, target: c.targetNode || c.targetBand, prov: c.provenance, why: c.evidence.detail.slice(0, 90) }))
      }))
    const unmapped = []
    for (const r of recs) for (const u of r.unmappedVendor || []) unmapped.push({ styleId: r.styleId, ...u })
    const byReason = {}
    for (const u of unmapped) {
      const k = (u.why || '').slice(0, 46)
      ;(byReason[k] = byReason[k] || { count: 0, styleIds: new Set() }).count++
      byReason[k].styleIds.add(u.styleId)
    }
    // 厂商授权行按 tokenKind 分类，用来证明适配器启发式的盲区
    const xm = load(path.join(STAGING, 'controls.xiaomi.json')).records
    const vendorToggleNodes = []
    for (const r of xm) for (const c of r.controls) if (c.kind === 'toggle' && c.provenance === 'vendor') {
      const n = (irxAll[r.styleId].nodes || []).find((x) => x.path === c.targetNode)
      vendorToggleNodes.push({ styleId: r.styleId, path: c.targetNode, irOptional: !!(n && n.optional), tokenKind: n ? n.tokenKind : 'group', tv: n ? n.tokenValue : c.evidence.detail.slice(0, 40) })
    }
    const blind = {}
    for (const v of vendorToggleNodes) {
      const key = (v.tv || v.tokenKind || '?').replace(/@[a-zA-Z]+/g, (m) => m).slice(0, 34)
      blind[key] = (blind[key] || 0) + 1
    }
    const out = {
      generatedFrom: 'staging/controls.*.json + ir-index.json',
      counts: {
        records: recs.length,
        declared: recs.filter((r) => r.panel === 'declared').length,
        generic: recs.filter((r) => r.panel === 'generic').length,
        controls: recs.reduce((n, r) => n + r.controls.length, 0),
        fidelityRiskStyles: risk.length,
        fidelityRiskControls: risk.reduce((n, r) => n + r.nodes.length, 0),
        unmappedVendorEvidence: unmapped.length,
        stylesWithUnmapped: new Set(unmapped.map((u) => u.styleId)).size
      },
      adapterEvidence: {
        xiaomi: 'scripts/system-camera/adapters/xiaomi.cjs L97-120 convertText(node)：optional 由文本正则硬编码（location_latlng/@filter/@custom/@location=/@model=@{versionName} ⇒ true，其余 false）；全文 grep "node.optional" 命中 0 次 ⇒ 厂商视图自带的 optional 标志从未被读',
        oppo: 'adapters/oppo.cjs L234 `optional: source === -1`（只有 textSource===-1 的自定义行）；grep ".editable" 在 oppo.cjs / oppo-camerapro.cjs 命中 0 次 ⇒ 厂商 editable:true 79 处（相册 62 + CameraPro 17）完全没进 IR',
        vivo: 'adapters/vivo-vector.cjs grep "optional|editable" 命中 0 次；vivo 14 套全部是 vivoManual.ts 手工样式 ⇒ IR 派生控件记 provenance=manual'
      },
      xiaomiVendorAuthorizedNodes: { total: vendorToggleNodes.length, irAlreadyOptional: vendorToggleNodes.filter((v) => v.irOptional).length, irMissed: vendorToggleNodes.filter((v) => !v.irOptional).length, byTemplate: blind },
      fidelityRiskStyles: risk,
      unmappedLedger: unmapped,
      unmappedByReason: Object.entries(byReason).map(([k, v]) => ({ reason: k, count: v.count, styles: v.styleIds.size, styleIds: [...v.styleIds].slice(0, 14) })),
      genericRecords: recs.filter((r) => r.panel === 'generic').map((r) => ({ styleId: r.styleId, reason: r.noControlsReason }))
    }
    write(path.join(STAGING, 'risks-data.json'), out)
    console.log('risks-data.json:', JSON.stringify(out.counts))
    console.log('  xiaomi vendor-authorized nodes:', out.xiaomiVendorAuthorizedNodes.total, 'of which IR missed:', out.xiaomiVendorAuthorizedNodes.irMissed)
    console.log('  unmapped top reasons:');
    for (const r of out.unmappedByReason.slice(0, 6)) console.log('    ' + r.count + ' (' + r.styles + ' 套) ' + r.reason)
  }

  if (has('--rollout')) {
    write(path.join(STAGING, 'rollout-plan.json'), rollout(irx))
    const rp = load(path.join(STAGING, 'rollout-plan.json'))
    console.log('rollout-plan.json:', rp.totalDeclared, 'declared styles /', rp.totalRecords, 'records ->', rp.batchCount, 'batches (<=5 each)')
    for (const b of rp.batches.slice(0, 3)) console.log('  batch', b.batch, b.styleIds.join(', '))
  }
})().catch((e) => {
  console.error('BUILD-CONTROLS ERROR:', e && e.stack ? e.stack : e)
  process.exit(1)
})
