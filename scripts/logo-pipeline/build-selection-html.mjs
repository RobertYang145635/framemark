// Build a self-contained, local checkbox gallery for the current logo catalog.
// Usage: node scripts/logo-pipeline/build-selection-html.mjs
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const APP = 'D:/border&watermark/framemark'
const CATALOG = path.join(APP, 'src/renderer/src/core/logo-catalog.ts')
const OUT = 'D:/border&watermark/_research/logo-versions/select.html'
const ROOT = 'D:/border&watermark/_research/logo-versions'
const ERA = { current: '现行', classic: '经典', historical: '历史', legacy: '既有' }
const ERA_ORDER = { current: 0, classic: 1, historical: 2, legacy: 3 }
const STATUS = { READY: '官方', LEGACY: '既有', CANDIDATE: '候选', GAP: 'GAP', UNUSABLE: '不可用' }

const result = await build({
  entryPoints: [CATALOG],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
  logLevel: 'silent'
})
const tmp = path.join(os.tmpdir(), `framemark-logo-select-${process.pid}.mjs`)
fs.writeFileSync(tmp, result.outputFiles[0].text)
const mod = await import(pathToFileURL(tmp).href)
fs.rmSync(tmp, { force: true })

const versionUrl = (brandKey, cut) => {
  if (cut.assetKey.startsWith('ver:')) {
    const p = path.join(APP, 'src/renderer/src/assets/logo-versions', brandKey, `${cut.id}.webp`)
    if (fs.existsSync(p)) return '../../framemark/src/renderer/src/assets/logo-versions/' + brandKey + '/' + cut.id + '.webp'
    if (cut.file?.startsWith('previews/')) return cut.file
    return ''
  }
  const p = path.join(APP, 'src/renderer/src/assets/logos', `${cut.assetKey}.webp`)
  if (fs.existsSync(p)) return '../../framemark/src/renderer/src/assets/logos/' + cut.assetKey + '.webp'
  return ''
}

const brands = mod.LOGO_BRANDS
const versions = mod.LOGO_VERSIONS
const SELECTION_FILE = 'D:/border&watermark/framemark-logo-selection-20261027.json'
const MAP = { lumix: 'panasionic', lumo: 'oppo' }
const HIDDEN = ['lumix', 'lumo']
let defaultSelected = []
try {
  const sel = JSON.parse(fs.readFileSync(SELECTION_FILE, 'utf8'))
  defaultSelected = [...new Set((sel.selected || []).map((x) => x.versionId).filter(Boolean))]
} catch {
  defaultSelected = []
}

const brandByKey = new Map(brands.map((b) => [b.key, b]))
const groups = new Map()
for (const v of versions) {
  const displayKey = MAP[v.brandKey] ?? v.brandKey
  if (HIDDEN.includes(v.brandKey) && !MAP[v.brandKey]) continue
  const meta = brandByKey.get(displayKey) ?? brandByKey.get(v.brandKey)
  if (!meta) continue
  if (!groups.has(displayKey)) {
    groups.set(displayKey, {
      key: displayKey,
      label: meta.label,
      cn: meta.cn,
      group: meta.group,
      lifecycle: meta.lifecycle,
      canonicalVersion: meta.canonicalVersion,
      merged: [],
      versions: []
    })
  }
  const g = groups.get(displayKey)
  g.versions.push(v)
  if (v.brandKey !== displayKey) {
    const source = brandByKey.get(v.brandKey)?.label ?? v.brandKey
    if (!g.merged.includes(source)) g.merged.push(source)
  }
}
const displayBrands = [...groups.values()]
  .sort((a, b) => brands.findIndex((x) => x.key === a.key) - brands.findIndex((x) => x.key === b.key))
  .map((g) => ({
    ...g,
    versions: g.versions
      .slice()
      .sort((a, b) => (ERA_ORDER[a.era] - ERA_ORDER[b.era]) || a.id.localeCompare(b.id))
      .map((v) => ({
        id: v.id,
        sourceBrandKey: v.brandKey,
        name: v.name,
        era: v.era,
        eraLabel: ERA[v.era] ?? v.era,
        years: v.years,
        status: v.status,
        confidence: v.confidence,
        surface: v.surface,
        aliases: v.aliases ?? [],
        canonical: g.canonicalVersion === v.id,
        cuts: v.cuts.map((c) => ({
          id: c.id,
          assetKey: c.assetKey,
          ink: c.ink,
          surface: c.surface,
          previewSurface: c.previewSurface,
          status: c.status,
          aliases: c.aliases ?? [],
          url: versionUrl(v.brandKey, c)
        }))
      }))
  }))

const payload = {
  generatedAt: new Date().toISOString(),
  counts: {
    brands: displayBrands.length,
    versions: versions.length,
    cuts: versions.reduce((n, v) => n + v.cuts.length, 0),
    selected: defaultSelected.length
  },
  brandMap: MAP,
  defaultSelected,
  brands: displayBrands
}

const json = JSON.stringify(payload).replace(/</g, '\\u003c')
const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FrameMark · Logo 收集清单</title>
<style>
  :root { --ink:#191917; --muted:#6f6c66; --line:#d9d6cf; --paper:#f4f2ed; --card:#fff; --accent:#c73b2f; --ok:#2f7a58; --warn:#9a650c; }
  * { box-sizing:border-box; }
  html { scroll-behavior:smooth; }
  body { margin:0; background:var(--paper); color:var(--ink); font:14px/1.5 "Segoe UI","Microsoft YaHei",system-ui,sans-serif; }
  button, select, input { font:inherit; }
  .top { position:sticky; top:0; z-index:20; background:rgba(244,242,237,.96); border-bottom:1px solid var(--line); backdrop-filter:blur(14px); }
  .top-inner { max-width:1480px; margin:0 auto; padding:14px 22px 12px; }
  .headline { display:flex; align-items:baseline; justify-content:space-between; gap:12px; flex-wrap:wrap; }
  h1 { margin:0; font-size:21px; letter-spacing:-.01em; }
  .stats { color:var(--muted); font-size:12px; }
  .stats b { color:var(--ink); font-weight:650; }
  .note { margin:8px 0 10px; color:var(--muted); font-size:12px; }
  .note b { color:var(--ink); }
  .controls { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .search { flex:1 1 260px; min-width:220px; height:38px; border:1px solid var(--line); border-radius:9px; background:#fff; display:flex; align-items:center; gap:7px; padding:0 10px; }
  .search input { border:0; outline:0; background:transparent; width:100%; color:var(--ink); }
  select { height:38px; border:1px solid var(--line); border-radius:9px; background:#fff; color:var(--ink); padding:0 9px; max-width:260px; }
  button { height:38px; border:1px solid var(--line); border-radius:9px; background:#fff; color:var(--ink); padding:0 12px; cursor:pointer; }
  button:hover { border-color:#bdb7ac; background:#faf9f6; }
  button.primary { background:var(--ink); border-color:var(--ink); color:#fff; }
  button.primary:hover { background:#33332f; }
  .selection-count { margin-left:auto; font-size:12px; color:var(--muted); white-space:nowrap; }
  .selection-count b { color:var(--accent); font-size:15px; }
  .brand { max-width:1480px; margin:0 auto; padding:18px 22px 2px; }
  .brand-head { display:flex; align-items:baseline; gap:10px; padding:8px 0 9px; border-bottom:1px solid var(--line); margin-bottom:11px; }
  .brand-head h2 { margin:0; font-size:17px; letter-spacing:-.01em; }
  .brand-head .cn { color:var(--muted); font-size:12px; }
  .brand-head .count { color:var(--muted); font-size:12px; }
  .brand-head button { margin-left:auto; height:28px; font-size:11.5px; padding:0 9px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(188px,1fr)); gap:11px; margin-bottom:10px; }
  .vcard { position:relative; display:flex; flex-direction:column; border:1px solid var(--line); border-radius:11px; background:var(--card); overflow:hidden; cursor:pointer; transition:border-color .15s, box-shadow .15s, transform .15s; }
  .vcard:hover { border-color:#b8b1a5; box-shadow:0 3px 12px rgba(30,26,20,.08); }
  .vcard.selected { border-color:var(--accent); box-shadow:0 0 0 2px rgba(199,59,47,.16); }
  .vcard input { position:absolute; opacity:0; pointer-events:none; }
  .check { position:absolute; top:8px; right:8px; z-index:2; width:21px; height:21px; border-radius:6px; border:1.5px solid #c7c2b8; background:rgba(255,255,255,.92); display:grid; place-items:center; }
  .vcard.selected .check { background:var(--accent); border-color:var(--accent); color:#fff; }
  .check svg { width:13px; height:13px; opacity:0; }
  .vcard.selected .check svg { opacity:1; }
  .cuts { display:grid; gap:1px; background:#e6e2d9; }
  .cuts.n1 { grid-template-columns:1fr; }
  .cuts.n2 { grid-template-columns:1fr 1fr; }
  .cut { position:relative; height:86px; overflow:hidden; }
  .cut.light { background-color:#f4f3ef; background-image:linear-gradient(45deg,#e9e5dc 25%,transparent 25%),linear-gradient(-45deg,#e9e5dc 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#e9e5dc 75%),linear-gradient(-45deg,transparent 75%,#e9e5dc 75%); background-size:12px 12px; background-position:0 0,0 6px,6px -6px,-6px 0; }
  .cut.dark { background-color:#151618; background-image:linear-gradient(45deg,#202226 25%,transparent 25%),linear-gradient(-45deg,#202226 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#202226 75%),linear-gradient(-45deg,transparent 75%,#202226 75%); background-size:12px 12px; background-position:0 0,0 6px,6px -6px,-6px 0; }
  .cut img { position:absolute; left:8px; top:8px; width:calc(100% - 16px); height:calc(100% - 16px); object-fit:contain; display:block; }
  .cut .miss { position:absolute; inset:0; display:grid; place-items:center; color:#9c968b; font-size:10px; }
  .meta { padding:9px 10px 10px; display:flex; flex-direction:column; gap:3px; min-height:92px; }
  .title { font-size:12.5px; font-weight:650; line-height:1.35; display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; padding-right:20px; }
  .sub { color:var(--muted); font-size:11px; line-height:1.35; }
  .badges { display:flex; gap:4px; flex-wrap:wrap; margin-top:2px; }
  .badge { display:inline-flex; align-items:center; height:18px; padding:0 6px; border-radius:999px; background:#f0ede6; color:#5c574e; font-size:10px; white-space:nowrap; }
  .badge.candidate { background:#fbf1d9; color:#8a5d07; }
  .badge.ready { background:#eaf3ed; color:#276b4d; }
  .badge.legacy { background:#eceaf0; color:#544d63; }
  .idline { font:10px/1.35 ui-monospace,Consolas,monospace; color:#a29b90; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .empty { max-width:1480px; margin:20px auto; padding:30px 22px; color:var(--muted); }
  .foot { max-width:1480px; margin:24px auto 40px; padding:0 22px; color:var(--muted); font-size:11.5px; }
  .dialog { position:fixed; inset:0; z-index:40; background:rgba(20,18,15,.45); display:none; place-items:center; padding:20px; }
  .dialog.open { display:grid; }
  .dialog-box { width:min(860px,100%); max-height:86vh; display:flex; flex-direction:column; background:#fff; border-radius:13px; overflow:hidden; box-shadow:0 24px 70px rgba(0,0,0,.24); }
  .dialog-head { display:flex; align-items:center; justify-content:space-between; padding:12px 15px; border-bottom:1px solid var(--line); }
  .dialog-head strong { font-size:14px; }
  .dialog textarea { flex:1; min-height:300px; border:0; outline:0; resize:none; padding:14px; font:11.5px/1.45 ui-monospace,Consolas,monospace; background:#faf9f6; color:#2b2925; }
  .dialog-foot { display:flex; gap:8px; justify-content:flex-end; padding:10px 15px; border-top:1px solid var(--line); }
  @media (max-width:720px) {
    .top-inner, .brand, .foot { padding-left:14px; padding-right:14px; }
    h1 { font-size:18px; }
    .selection-count { width:100%; margin-left:0; }
    .grid { grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
    .cut { height:72px; }
    .meta { min-height:104px; }
    .title { font-size:12px; }
  }
</style>
</head>
<body>
<header class="top">
  <div class="top-inner">
    <div class="headline">
      <h1>FrameMark · Logo 收集清单</h1>
      <div class="stats">当前目录：<b>${payload.counts.brands}</b> 品牌 · <b>${payload.counts.versions}</b> 版本 · <b>${payload.counts.cuts}</b> 切版 · 已勾选 <b>${payload.counts.selected}</b></div>
    </div>
    <div class="note"><b>勾选 = 保留并进入 App；不勾选 = 不采用。</b> 已按 2026-10-27 的勾选恢复 ${payload.counts.selected} 个版本。品牌归属：LUMIX → Panasonic，LUMO → OPPO。黑白双切勾一次代表整套。</div>
    <div class="controls">
      <label class="search"><span aria-hidden="true">⌕</span><input id="q" type="search" placeholder="搜索品牌 / 版本 / 年份，如 Fujifilm、1980、Hasselblad"></label>
      <select id="brandFilter"><option value="">全部品牌（${payload.counts.brands}）</option></select>
      <select id="statusFilter">
        <option value="">全部来源</option>
        <option value="curated">官方 + 既有</option>
        <option value="candidate">仅候选</option>
        <option value="selected">仅已勾选</option>
        <option value="unselected">仅未勾选</option>
      </select>
      <button id="selectVisible" type="button">全选当前筛选</button>
      <button id="clearAll" type="button">清空全部</button>
      <button id="export" class="primary" type="button">导出勾选 JSON</button>
      <span id="count" class="selection-count">已勾选 <b>${payload.counts.selected}</b> / ${payload.counts.versions}</span>
    </div>
  </div>
</header>
<main id="main"></main>
<div id="empty" class="empty" hidden>没有匹配的版本。</div>
<div class="foot">勾选状态自动保存在本机浏览器（localStorage）。导出的 JSON 可直接交回项目；未勾选版本不会被删除，只是不会进入下一轮 App 清单。</div>
<div id="dialog" class="dialog" role="dialog" aria-modal="true" aria-labelledby="dialogTitle">
  <div class="dialog-box">
    <div class="dialog-head"><strong id="dialogTitle">勾选结果 JSON</strong><button id="copyJson" type="button">复制</button></div>
    <textarea id="jsonText" readonly></textarea>
    <div class="dialog-foot"><button id="downloadJson" class="primary" type="button">下载 JSON</button><button id="closeDialog" type="button">关闭</button></div>
  </div>
</div>
<script>
const DATA = ${json};
const ERA_ORDER = { current: 0, classic: 1, historical: 2, legacy: 3 };
const STORE_KEY = 'framemark.logo-selection.v2';
const stored = localStorage.getItem(STORE_KEY);
const initial = stored ? JSON.parse(stored) : (DATA.defaultSelected || []);
const selected = new Set(initial.filter((x) => typeof x === 'string'));
let visibleCount = 0;

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const keyOf = (_brandKey, id) => id;
const labelStatus = (s) => s === 'READY' ? '官方' : s === 'LEGACY' ? '既有' : s === 'CANDIDATE' ? '候选' : s;
const surfaceLabel = (s) => s === 'both' ? '自含底' : s === 'dark' ? '深底' : s === 'light' ? '浅底' : '未知';

function save() {
  localStorage.setItem(STORE_KEY, JSON.stringify([...selected].sort()));
}
function updateCount() {
  document.getElementById('count').innerHTML = '已勾选 <b>' + selected.size + '</b> / ' + DATA.counts.versions;
}
function selectedForExport() {
  const out = [];
  for (const brand of DATA.brands) {
    for (const v of brand.versions) {
      const id = keyOf(brand.key, v.id);
      if (!selected.has(id)) continue;
      out.push({
        brandKey: brand.key,
        brand: brand.label,
        versionId: v.id,
        name: v.name,
        era: v.era,
        years: v.years,
        cuts: v.cuts.map((c) => ({ id: c.id, assetKey: c.assetKey, ink: c.ink, surface: c.surface }))
      });
    }
  }
  return out;
}
function payload() {
  return {
    version: 1,
    exportedAt: new Date().toISOString(),
    counts: { selected: selected.size, total: DATA.counts.versions },
    selected: selectedForExport(),
    unselected: [...selected].length ? [] : []
  };
}
function exportJson() {
  const text = JSON.stringify(payload(), null, 2);
  document.getElementById('jsonText').value = text;
  document.getElementById('dialog').classList.add('open');
}
function downloadJson() {
  const blob = new Blob([document.getElementById('jsonText').value || JSON.stringify(payload(), null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'framemark-logo-selection-' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + '.json';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
function card(brand, v) {
  const id = keyOf(brand.key, v.id);
  const checked = selected.has(id);
  const cuts = v.cuts || [];
  const cutHtml = cuts.map((c) => '<div class="cut ' + (c.previewSurface === 'dark' ? 'dark' : 'light') + '">' +
    (c.url ? '<img src="' + esc(c.url) + '" alt="' + esc(v.name) + '" loading="lazy" decoding="async">' : '<span class="miss">无预览</span>') +
    '</div>').join('');
  const statusClass = v.status === 'READY' ? 'ready' : v.status === 'CANDIDATE' ? 'candidate' : 'legacy';
  return '<label class="vcard ' + (checked ? 'selected' : '') + '" data-id="' + esc(id) + '" data-brand="' + esc(brand.key) + '" data-status="' + esc(v.status) + '" data-search="' + esc((brand.label + ' ' + brand.cn + ' ' + brand.merged.join(' ') + ' ' + v.name + ' ' + v.years + ' ' + v.id + ' ' + v.aliases.join(' ')).toLowerCase()) + '" title="' + esc(v.id) + '">' +
    '<input type="checkbox" ' + (checked ? 'checked' : '') + ' data-check="' + esc(id) + '">' +
    '<span class="check" aria-hidden="true"><svg viewBox="0 0 16 16" fill="none"><path d="M3 8.3 6.4 11.7 13 4.8" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>' +
    '<div class="cuts n' + Math.min(2, Math.max(1, cuts.length)) + '">' + cutHtml + '</div>' +
    '<div class="meta"><div class="title">' + esc(v.name) + '</div><div class="sub">' + esc(v.eraLabel) + ' · ' + esc(v.years) + ' · ' + cuts.length + ' 切版</div>' +
    '<div class="badges"><span class="badge ' + statusClass + '">' + esc(labelStatus(v.status)) + '</span>' +
    (v.surface === 'both' ? '<span class="badge">黑/白双版</span>' : '<span class="badge">' + esc(surfaceLabel(v.surface)) + '</span>') +
    (v.canonical ? '<span class="badge">当前默认</span>' : '') + '</div>' +
    '<div class="idline">' + esc(v.id) + '</div></div></label>';
}
function render() {
  const main = document.getElementById('main');
  main.innerHTML = DATA.brands.map((brand) => {
    const cards = brand.versions.map((v) => card(brand, v)).join('');
    return '<section class="brand" data-brand-section="' + esc(brand.key) + '"><div class="brand-head"><h2>' + esc(brand.label) + '</h2><span class="cn">' + esc(brand.cn) + '</span><span class="count">' + brand.versions.length + ' 个版本' + (brand.merged.length ? ' · 含 ' + esc(brand.merged.join(' / ')) : '') + '</span><button type="button" data-brand-all="' + esc(brand.key) + '">全选本品牌</button></div><div class="grid">' + cards + '</div></section>';
  }).join('');
  const sel = document.getElementById('brandFilter');
  for (const brand of DATA.brands) {
    const o = document.createElement('option');
    o.value = brand.key;
    o.textContent = brand.label + ' · ' + brand.cn + '（' + brand.versions.length + '）';
    sel.appendChild(o);
  }
  main.addEventListener('change', onChange);
  main.addEventListener('click', (e) => {
    const all = e.target.closest('[data-brand-all]');
    if (!all) return;
    e.preventDefault();
    e.stopPropagation();
    const key = all.getAttribute('data-brand-all');
    const section = main.querySelector('[data-brand-section="' + CSS.escape(key) + '"]');
    for (const input of section.querySelectorAll('input[data-check]')) {
      selected.add(input.getAttribute('data-check'));
      const card = input.closest('.vcard');
      card.classList.add('selected');
      input.checked = true;
    }
    save(); updateCount(); applyFilters();
  });
}
function onChange(e) {
  const input = e.target.closest('input[data-check]');
  if (!input) return;
  const id = input.getAttribute('data-check');
  if (input.checked) selected.add(id); else selected.delete(id);
  input.closest('.vcard').classList.toggle('selected', input.checked);
  save(); updateCount(); applyFilters();
}
function applyFilters() {
  const q = document.getElementById('q').value.trim().toLowerCase();
  const brand = document.getElementById('brandFilter').value;
  const status = document.getElementById('statusFilter').value;
  visibleCount = 0;
  for (const section of document.querySelectorAll('[data-brand-section]')) {
    let shown = 0;
    for (const card of section.querySelectorAll('.vcard')) {
      const id = card.getAttribute('data-id');
      const okQ = !q || card.getAttribute('data-search').includes(q);
      const okBrand = !brand || card.getAttribute('data-brand') === brand;
      const vStatus = card.getAttribute('data-status');
      const okStatus = !status || (status === 'curated' ? (vStatus === 'READY' || vStatus === 'LEGACY') : status === 'candidate' ? vStatus === 'CANDIDATE' : status === 'selected' ? selected.has(id) : !selected.has(id));
      const ok = okQ && okBrand && okStatus;
      card.style.display = ok ? '' : 'none';
      if (ok) shown++;
    }
    section.style.display = shown ? '' : 'none';
    visibleCount += shown;
  }
  document.getElementById('empty').hidden = visibleCount !== 0;
  document.getElementById('selectVisible').textContent = '全选当前 ' + visibleCount;
}
document.getElementById('q').addEventListener('input', applyFilters);
document.getElementById('brandFilter').addEventListener('change', applyFilters);
document.getElementById('statusFilter').addEventListener('change', applyFilters);
document.getElementById('selectVisible').addEventListener('click', () => {
  for (const card of document.querySelectorAll('.vcard')) {
    if (card.style.display === 'none') continue;
    const input = card.querySelector('input[data-check]');
    if (!input) continue;
    selected.add(input.getAttribute('data-check'));
    input.checked = true;
    card.classList.add('selected');
  }
  save(); updateCount(); applyFilters();
});
document.getElementById('clearAll').addEventListener('click', () => {
  if (!confirm('清空全部勾选？')) return;
  selected.clear(); save(); updateCount();
  for (const card of document.querySelectorAll('.vcard')) { card.classList.remove('selected'); const i = card.querySelector('input'); if (i) i.checked = false; }
  applyFilters();
});
document.getElementById('export').addEventListener('click', exportJson);
document.getElementById('downloadJson').addEventListener('click', downloadJson);
document.getElementById('closeDialog').addEventListener('click', () => document.getElementById('dialog').classList.remove('open'));
document.getElementById('copyJson').addEventListener('click', async () => {
  const text = document.getElementById('jsonText').value;
  try { await navigator.clipboard.writeText(text); } catch { const ta = document.getElementById('jsonText'); ta.select(); document.execCommand('copy'); }
  document.getElementById('copyJson').textContent = '已复制';
  setTimeout(() => { document.getElementById('copyJson').textContent = '复制'; }, 1200);
});
render();
updateCount();
applyFilters();
</script>
</body>
</html>`

fs.writeFileSync(OUT, html)
console.log(JSON.stringify({ out: OUT, bytes: Buffer.byteLength(html), brands: payload.counts.brands, versions: payload.counts.versions, cuts: payload.counts.cuts }, null, 2))
