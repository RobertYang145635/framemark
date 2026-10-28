import { create } from 'zustand'
import { Capacitor } from '@capacitor/core'
import type {
  BrandVersionRef,
  Design,
  EditorTab,
  ExportJob,
  ExportScope,
  InspectorTab,
  InspectorTool,
  ParamOverrides,
  ParamValue,
  Params,
  PhotoAsset,
  Preset,
  Ratio,
  TextSettings,
  TextSlot,
  WatermarkSettings
} from './types'
import { getTemplate } from './core/templates'
import { getVariant, VARIANT_MAP } from './core/variants'
import { autoLogo, logoAsset, logoCutForFile, matchBrand } from './core/brands'
import { LOGO_VERSION_BY_ID, versionForAssetKey } from './core/logo-catalog'
import {
  autoLogoVersionForBrand,
  cutFitsSurface,
  cutForVersionSurface,
  fallbackVersionForSurface,
  refForVersion,
  refFromAssetKey,
  surfaceForDesign
} from './core/logo-compat'
import { photoFromFile, photoFromPath, photoFromUrl, revokePhoto, imageAssetFromFile } from './core/photos'
import {
  DEFAULT_TEXT,
  declaredParams,
  defaultDesign,
  hydrateDesign,
  isValidDesign,
  makeDesignFromVariant,
  materializeParams,
  paramBucketKey,
  portablePresetDesign,
  pruneOverrides
} from './core/presets'
import { getAllPresets, putPreset, removePreset, loadLastDesign, saveLastDesign, downloadJson, readJsonFile, getJob, listJobs, putJob } from './core/storage'
import { exportBatch } from './core/exporter'
import { startExportForeground, stopExportForeground, updateExportForeground } from './core/foreground'
import {
  exportDesignKey,
  isResumable,
  makeTask,
  newestTaskFor,
  reconcile,
  resolveStatus,
  seedFromHistory,
  taskSummary,
  type ExportTask
} from './core/jobs'
import { saveTextDocument } from './core/gallery'
import * as history from './core/history'
import type { FolderScanResult } from './global'

interface Toast {
  id: number
  message: string
  type: 'info' | 'success' | 'error'
}

interface ImportSummary {
  added: number
  failed: number
}

/**
 * Turns the main process' folder-scan counters into one honest sentence (PRODUCT-DEFECTS
 * #58 / defect 1). Returns null when nothing was dropped: the old scanner just returned
 * fewer files and said nothing, which is exactly what this guards against.
 */
function folderScanNotice(scan: FolderScanResult | undefined, imported: number): string | null {
  if (!scan) return null
  const dropped = Number(scan.droppedFiles) || 0
  const skippedDirs = Number(scan.skippedDirs) || 0
  if (!dropped && !skippedDirs && !scan.guardHit) return null
  const parts: string[] = []
  if (dropped) parts.push(`${dropped.toLocaleString('zh-CN')} 张未导入（单次上限 ${scan.maxFiles.toLocaleString('zh-CN')} 张）`)
  if (skippedDirs) parts.push(`${skippedDirs} 个子目录未扫描（层级上限 ${scan.maxDepth} 层）`)
  if (scan.guardHit) parts.push('目录过大，扫描已提前停止')
  return `文件夹只导入了 ${imported.toLocaleString('zh-CN')} 张：${parts.join('；')}`
}

export interface ImportProgress {
  done: number
  total: number
  current: string
  phase: string
  /** Fraction of the current file (0–1), so one large photo still moves the bar. */
  filePct: number
}

interface AppState {
  photos: PhotoAsset[]
  selectedId: string | null
  design: Design
  presets: Preset[]
  activePresetId: string | null
  initialized: boolean
  busy: string | null
  /** Visible import progress for picker/drop/folder imports (null = idle). */
  importProgress: ImportProgress | null
  job: ExportJob | null
  /**
   * S3: an unfinished persisted export for the CURRENT photos (crash/cancel),
   * shown as 「上次导到 X/Y，可继续」 by the ExportOverlay.
   */
  resumable: { id: string; done: number; total: number } | null
  /** Text lines captured from the last hand-template render (v4 文字 pane). */
  textSlots: TextSlot[]
  /** Output pixel size of the current preview (shown in the Inspector dock). */
  previewMeta: { W: number; H: number; outW: number; outH: number } | null
  tab: InspectorTab
  /** Phase 3 top-level Inspector tool (边框 / 水印). */
  tool: InspectorTool
  /** Active editor tab inside the border tool. */
  editorTab: EditorTab
  brandPickerOpen: boolean
  presetDialogOpen: boolean
  /** Phase 4 export dialog; null = closed, otherwise the scope it opened with. */
  exportDialog: ExportScope | null
  /** 1/N batch viewer: index into `photos`, null = closed. */
  batchPreviewIndex: number | null
  galleryQuery: string
  toast: Toast | null
  init: () => Promise<void>
  addFiles: (files: File[]) => Promise<void>
  addPaths: (paths: string[]) => Promise<ImportSummary>
  addSample: (url: string, name: string) => Promise<void>
  pickPhotos: () => Promise<void>
  addFolder: () => Promise<void>
  removePhoto: (id: string) => void
  selectPhoto: (id: string) => void
  setVariant: (id: string) => void
  resetVariantParams: () => void
  /** 双击滑杆复位：把单个参数写回当前模板/样式的声明值（覆盖被剪掉）。 */
  resetParam: (key: string) => void
  setParam: (key: string, value: Params[string]) => void
  setText: (patch: Partial<TextSettings>) => void
  setWatermark: (patch: Partial<WatermarkSettings>) => void
  setWatermarkFile: (file: File) => Promise<void>
  setExport: (patch: Partial<Design['export']>) => void
  setTextSlots: (slots: TextSlot[]) => void
  setPreviewMeta: (meta: { W: number; H: number; outW: number; outH: number } | null) => void
  setRatio: (ratio: Ratio) => void
  setLogoFromBrand: (file: string) => Promise<void>
  setLogoVersion: (versionId: string, cutId?: string) => Promise<void>
  ensureLogoCompatibility: () => Promise<void>
  autoMatchLogo: () => Promise<void>
  clearLogo: () => void
  setAvatarFile: (file: File) => Promise<void>
  clearAvatar: () => void
  setTab: (tab: InspectorTab) => void
  setTool: (tool: InspectorTool) => void
  setEditorTab: (tab: EditorTab) => void
  setBrandPickerOpen: (open: boolean) => void
  setPresetDialogOpen: (open: boolean) => void
  openExportDialog: (scope: ExportScope) => void
  closeExportDialog: () => void
  openBatchPreview: (index: number) => void
  closeBatchPreview: () => void
  stepBatchPreview: (delta: number) => void
  setGalleryQuery: (q: string) => void
  savePreset: (name: string) => Promise<void>
  applyPreset: (id: string) => void
  /** Undo/redo over design fields only (S1). Template/variant switches clear it. */
  undoDesign: () => void
  redoDesign: () => void
  deletePreset: (id: string) => Promise<void>
  importPresetFile: (file: File) => Promise<void>
  exportPresetFile: () => void
  exportCurrent: () => Promise<void>
  exportAll: () => Promise<void>
  cancelExport: () => void
  closeJob: () => void
  /** S3: newest unfinished (running/paused) task that shares a photo with `photos`. */
  findResumable: (photos: PhotoAsset[]) => Promise<ExportTask | null>
  /** S3: continue a persisted task; finished photos are skipped, not re-written. */
  resumeExport: (id: string) => Promise<void>
  /** S3: re-export only the keys that recorded an error. */
  retryFailed: (id: string) => Promise<void>
  /** S3: 新建任务 — retire the current record so the same photos can export again. */
  dismissJob: () => Promise<void>
  showToast: (message: string, type?: Toast['type']) => void
}

const isDesktop = typeof window !== 'undefined' && Boolean(window.framemark)
let toastTimer: ReturnType<typeof setTimeout> | null = null
/** Dedupes the "no version for this surface" warning to once per version+surface. */
let lastMissingLogoWarnKey = ''

/**
 * What one recorded design write is called (S1 undo/redo).
 *   - `key`    coalescing identity: two writes with the same key inside 600 ms
 *              (or anywhere inside an open gesture) become ONE undo step.
 *   - `absorb` an async follow-up write that belongs to the step that opened the
 *              transaction; see core/history.ts openTxn/isOpenTxn.
 */
interface CommitOptions {
  label: string
  key: string
  absorb?: number
}

/** `{a,b}` -> `a+b`: patch identity so different fields never merge by accident. */
function patchKey(patch: object): string {
  return Object.keys(patch).sort().join('+')
}

/**
 * Write one param for the style the design currently points at.
 *
 * Both carriers move together:
 *   - `params` — the flat object every renderer already reads (templates.ts and
 *     core/system read `env.params.<key>` at ~276 sites, the 全部参数 panel and
 *     PreviewStage read `design.params`, and two e2e scripts read
 *     `localStorage['framemark.lastDesign.v1'].params.<key>`), and
 *   - `paramOverrides[bucket]` — the sparse per-style bucket that makes the value
 *     survive a style switch.
 * A key only ever lands in the bucket because the user set it, so an untouched
 * style has no bucket entry and renders its declared defaults.
 */
function writeParam (design: Design, key: string, value: ParamValue): Design {
  const bucket = paramBucketKey(design)
  const overrides: ParamOverrides = {
    ...(design.paramOverrides ?? {}),
    [bucket]: { ...(design.paramOverrides?.[bucket] ?? {}), [key]: value }
  }
  const params = { ...design.params, [key]: value } as Params
  // A value pushed back onto the declared one is not an override any more: drop it
  // so the bucket stays sparse (invariant I1) and later default changes still land.
  const pruned = pruneOverrides(overrides, declaredParams(design), bucket)
  return { ...design, params, ...(pruned ? { paramOverrides: pruned } : { paramOverrides: undefined }) }
}

/** Remove one param from BOTH carriers (what `clearLogo` must do to brandLogoFile). */
function dropParam (design: Design, key: string): Design {
  const bucket = paramBucketKey(design)
  const overrides: ParamOverrides = { ...(design.paramOverrides ?? {}) }
  if (overrides[bucket]) {
    const { [key]: _dropped, ...rest } = overrides[bucket]
    if (Object.keys(rest).length) overrides[bucket] = rest
    else delete overrides[bucket]
  }
  const { [key]: _dropped, ...params } = design.params
  return { ...design, params, paramOverrides: Object.keys(overrides).length ? overrides : undefined }
}

/**
 * Re-point a design at another style: rebuild `params` from THAT style's declared
 * values plus its own bucket only. This is the anti-cross-contamination step —
 * a key the new style never had an override for falls back to its own default,
 * it cannot inherit the value the previous style was showing.
 */
function rescopeParams (design: Design, variantId: string): Design {
  const variant = getVariant(variantId)
  const base = getTemplate(variant.base)
  const declared = { ...base.defaults, ...(variant.params ?? {}) } as Params
  return { ...design, templateId: base.id, variantId: variant.id, params: materializeParams(declared, design.paramOverrides, variant.id) }
}

/**
 * Hydration-time upgrade of the additive `brandVersion` pointer (types.ts).
 *
 * Presets and last-design restores carry `params.brandLogoFile` / `logo.name`
 * (and possibly an older/partial `brandVersion`). When the pointer is missing or
 * names a version the catalog no longer has, derive it from the picked asset via
 * `refFromAssetKey`. An existing valid pointer is left verbatim: loading a preset
 * must not silently repaint it (surface enforcement only runs on a theme change).
 */
function normalizeBrandVersion (design: Design): Design {
  if (design.brandVersion && LOGO_VERSION_BY_ID[design.brandVersion.versionId]) return design
  const surface = surfaceForDesign(design)
  const pick = typeof design.params.brandLogoFile === 'string'
    ? design.params.brandLogoFile
    : design.logo?.name
  const ref = pick ? refFromAssetKey(pick, surface) : null
  if (ref) return { ...design, brandVersion: ref }
  if (design.brandVersion === undefined) return design
  const { brandVersion: _dropped, ...rest } = design
  return rest
}

/**
 * THE write path for `design` (S1 undo/redo). Every user-facing design edit goes
 * through here so one user action produces exactly one history step:
 *   - the snapshot recorded is the design BEFORE the write; undo() takes the
 *     live design from the store, so a follow-up async write inside the same
 *     step is still covered by the step's redo entry;
 *   - `absorb` writes (async logo reconciliation) always apply, but never push:
 *     while their transaction is open they belong to the step that opened it,
 *     and once it is closed they are a stale follow-up that must not open one.
 */
function commitDesign (producer: (design: Design) => Design, opts: CommitOptions): void {
  const before = useAppStore.getState().design
  const next = producer(before)
  if (next === before) return
  useAppStore.setState({ design: next, activePresetId: null })
  if (opts.absorb !== undefined) {
    // Both branches push nothing, kept explicit because they mean different things:
    if (!history.isOpenTxn(opts.absorb)) return // stale follow-up: apply only
    return // still inside the open step: already part of it
  }
  history.push({ label: opts.label, key: opts.key, before })
}

/**
 * Background logo reconciliation. Lives outside the actions so it can be handed
 * a transaction id: it must never call the public `setParam` (that would push a
 * second undo step for one theme click).
 */
async function reconcileLogo (txn?: number): Promise<void> {
  const { design } = useAppStore.getState()
  const current = design.brandVersion
  if (!current) return
  const surface = surfaceForDesign(design)
  const version = LOGO_VERSION_BY_ID[current.versionId]
  if (!version) return

  const cut = cutForVersionSurface(version, surface)
  if (cut) {
    if (cut.id === current.cutId && cut.assetKey === current.assetKey) return
    let asset
    try {
      asset = await logoAsset(cut.assetKey)
    } catch {
      return
    }
    const next = refForVersion(version, surface)
    if (!next) return
    lastMissingLogoWarnKey = ''
    commitDesign(
      (d) =>
        d.brandVersion?.versionId === version.id
          ? {
              ...writeParam(d, 'brandLogoFile', cut.assetKey),
              brandVersion: next,
              logo: asset,
              text: { ...d.text, showLogo: true }
            }
          : d,
      { label: 'Logo 适配', key: 'logo', absorb: txn }
    )
    useAppStore.getState().showToast(`已换成${surface === 'dark' ? '白' : '黑'}版 Logo`, 'info')
    return
  }

  const fallback = fallbackVersionForSurface(version, surface)
  const nextRef = fallback ? refForVersion(fallback, surface) : null
  if (fallback && nextRef) {
    let asset
    try {
      asset = await logoAsset(nextRef.assetKey)
    } catch {
      return
    }
    lastMissingLogoWarnKey = ''
    commitDesign(
      (d) =>
        d.brandVersion?.versionId === version.id
          ? {
              ...writeParam(d, 'brandLogoFile', nextRef.assetKey),
              brandVersion: nextRef,
              logo: asset,
              text: { ...d.text, showLogo: true }
            }
          : d,
      { label: 'Logo 适配', key: 'logo', absorb: txn }
    )
    useAppStore.getState().showToast(`已换成同款的${surface === 'dark' ? '深' : '浅'}底版本`, 'info')
    return
  }

  // No version can live on this surface. Keep `brandVersion`/`logo` intact in
  // this slice (renderer enforcement comes next); warn once per theme change.
  const warnKey = `${version.id}:${surface}`
  if (lastMissingLogoWarnKey !== warnKey) {
    lastMissingLogoWarnKey = warnKey
    useAppStore
      .getState()
      .showToast(`这个 Logo 没有${surface === 'dark' ? '深色' : '浅色'}底版本，导出时不会显示`, 'error')
  }
}

/**
 * Keep the `design.logo` display mirror in step with an explicit
 * `params.brandLogoFile` pick (S9).
 *
 * The renderers resolve the PICK (`scene.ts` hand cards, `assets.ts` stock
 * slots), not the mirror; the mirror exists for the Inspector thumbnail and for
 * saved presets. It must follow the pick anyway, because the hand renderer uses
 * it as the pick when no explicit key is present — a bitmap left over from the
 * previous brand would then outlive its own pick. Writes are absorbed into the
 * caller's undo step (or applied only, once that step is closed).
 */
async function mirrorDesignLogo (txn?: number): Promise<void> {
  const { design } = useAppStore.getState()
  const pick = typeof design.params.brandLogoFile === 'string' ? design.params.brandLogoFile.trim() : ''
  if (!pick) return
  let asset
  try {
    asset = await logoAsset(pick)
  } catch {
    return
  }
  // A newer pick landed while the bitmap decoded: it owns the mirror now.
  if (useAppStore.getState().design.params.brandLogoFile !== design.params.brandLogoFile) return
  commitDesign((d) => ({ ...d, logo: asset }), { label: 'Logo 预览', key: 'logo', absorb: txn })
}

/** The version pointer that matches a raw `brandLogoFile` pick (or null). */
function brandRefForPick (file: string): BrandVersionRef | null {
  const pick = file.trim()
  if (!pick) return null
  return refFromAssetKey(pick, surfaceForDesign(useAppStore.getState().design))
}

async function autoApplyLogo(photo: PhotoAsset | undefined): Promise<void> {
  if (!photo) return
  const state = useAppStore.getState()
  if (state.design.logo) return
  // S9: `design.logo` is an INPUT to the hand renderer's brand resolution now
  // (scene.ts routes it into `params.brandLogoFile`), so an EXIF match must
  // never seed a card whose variant declares its own wordmark: that is exactly
  // how a 哈苏 card used to end up showing the photo's Canon mark before
  // FIX #64. Logo-less cards (no declared brand slot) keep the old behaviour —
  // the mirror is only ever read on cards that own a brand slot.
  const variant = state.design.variantId ? VARIANT_MAP[state.design.variantId] : undefined
  if (variant && (variant.vendorLogo || variant.autoLogo)) return
  const dark = ['ink', 'black'].includes(String(state.design.params.theme))
  const brand = autoLogo(photo.exif.make, photo.exif.model, dark)
  if (!brand) return
  try {
    const asset = await logoAsset(brand)
    if (useAppStore.getState().design.logo) return
    useAppStore.setState((s) => ({ design: { ...s.design, logo: asset } }))
  } catch {
    // logo matching is optional
  }
}

export const useAppStore = create<AppState>((set, get) => ({
  photos: [],
  selectedId: null,
  design: defaultDesign(),
  presets: [],
  activePresetId: null,
  initialized: false,
  busy: null,
  importProgress: null,
  job: null,
  resumable: null,
  textSlots: [],
  previewMeta: null,
  tab: 'template',
  tool: 'border',
  editorTab: 'logo',
  brandPickerOpen: false,
  presetDialogOpen: false,
  exportDialog: null,
  batchPreviewIndex: null,
  galleryQuery: '',
  toast: null,

  init: async () => {
    if (get().initialized) return
    const [presets, last] = await Promise.all([getAllPresets(), Promise.resolve(loadLastDesign())])
    const design = hydrateDesign(last)
    if (isDesktop && !design.export.outputDir) {
      try {
        design.export.outputDir = await window.framemark!.defaultExportDir()
      } catch {
        // fall through to placeholder
      }
    }
    if (!design.export.outputDir) design.export.outputDir = '相册 / Pictures/FrameMark'
    set({ presets, design: normalizeBrandVersion(design), initialized: true })
    window.setTimeout(() => set({ initialized: true }), 400)
  },

  addFiles: async (files) => {
    const images = files.filter((f) => f.type.startsWith('image/') || /\.(jpe?g|png|webp|tiff?|heic|heif)$/i.test(f.name))
    if (!images.length) return
    set({
      busy: `正在读取 ${images.length} 张照片…`,
      importProgress: { done: 0, total: images.length, current: images[0].name, phase: '读取照片信息', filePct: 0 }
    })
    const added: PhotoAsset[] = []
    let failed = 0
    for (let i = 0; i < images.length; i++) {
      const file = images[i]
      try {
        added.push(
          await photoFromFile(file, (phase, filePct) =>
            set({ importProgress: { done: i, total: images.length, current: file.name, phase, filePct } })
          )
        )
      } catch {
        failed++
      }
      set({ importProgress: { done: i + 1, total: images.length, current: file.name, phase: '整理照片', filePct: 0 } })
    }
    set((s) => ({
      photos: [...s.photos, ...added],
      selectedId: s.selectedId ?? added[0]?.id ?? null,
      busy: null,
      importProgress: null
    }))
    await autoApplyLogo(added[0])
    if (added.length) get().showToast(`已添加 ${added.length} 张照片`, 'success')
    if (failed) get().showToast(`${failed} 张照片读取失败`, 'error')
    void refreshResumable()
  },

  addPaths: async (paths) => {
    if (!paths.length) return { added: 0, failed: 0 }
    if (!window.framemark) return { added: 0, failed: 0 }
    const nameOf = (p: string) => p.split(/[\\/]/).pop() || p
    set({
      busy: `正在读取 ${paths.length} 张照片…`,
      importProgress: { done: 0, total: paths.length, current: nameOf(paths[0]), phase: '读取照片', filePct: 0.15 }
    })
    const added: PhotoAsset[] = []
    let failed = 0
    for (let i = 0; i < paths.length; i++) {
      const p = paths[i]
      set({
        importProgress: { done: i, total: paths.length, current: nameOf(p), phase: '生成预览', filePct: 0.45 }
      })
      try {
        const preview = await window.framemark.previewImage(p)
        added.push(photoFromPath(preview, p))
      } catch {
        failed++
      }
      set({
        importProgress: { done: i + 1, total: paths.length, current: nameOf(p), phase: '整理照片', filePct: 0 }
      })
    }
    set((s) => ({
      photos: [...s.photos, ...added],
      selectedId: s.selectedId ?? added[0]?.id ?? null,
      busy: null,
      importProgress: null
    }))
    await autoApplyLogo(added[0])
    if (added.length) get().showToast(`已添加 ${added.length} 张照片`, 'success')
    if (failed) get().showToast(`${failed} 张照片读取失败`, 'error')
    void refreshResumable()
    return { added: added.length, failed }
  },

  addSample: async (url, name) => {
    set({ busy: '正在载入示例…' })
    try {
      const asset = await photoFromUrl(url, name)
      set((s) => ({ photos: [...s.photos, asset], selectedId: s.selectedId ?? asset.id, busy: null }))
      get().showToast('示例已载入', 'success')
      void refreshResumable()
    } catch (e) {
      set({ busy: null })
      get().showToast(e instanceof Error ? e.message : '载入示例失败', 'error')
    }
  },

  pickPhotos: async () => {
    if (window.framemark) {
      const paths = await window.framemark.pickImages()
      if (paths.length) await get().addPaths(paths)
      return
    }
    document.getElementById('framemark-file-input')?.click()
  },

  addFolder: async () => {
    if (!window.framemark) return
    const folder = await window.framemark.pickFolder()
    if (!folder) return
    set({
      busy: '正在扫描文件夹…',
      importProgress: { done: 0, total: 0, current: folder, phase: '扫描文件夹', filePct: 0 }
    })
    let scan: FolderScanResult
    try {
      scan = await window.framemark.listImages(folder)
    } finally {
      set({ busy: null, importProgress: null })
    }
    const files = scan?.files ?? []
    if (!files.length) {
      const notice = folderScanNotice(scan, 0)
      get().showToast(notice ?? '这个文件夹里没有找到照片', 'error')
      return
    }
    const summary = await get().addPaths(files)
    // A ceiling that bit must stay on screen: show it after addPaths so it is the toast
    // the user actually reads (PRODUCT-DEFECTS #58 / defect 1 — no silent truncation).
    const notice = folderScanNotice(scan, summary.added)
    if (notice) {
      const tail = summary.failed ? `；另有 ${summary.failed} 张读取失败` : ''
      get().showToast(`${notice}${tail}`, 'error')
    }
  },

  removePhoto: (id) => {
    const photo = get().photos.find((p) => p.id === id)
    if (photo) revokePhoto(photo)
    set((s) => {
      const photos = s.photos.filter((p) => p.id !== id)
      const selectedId = s.selectedId === id ? photos[0]?.id ?? null : s.selectedId
      return { photos, selectedId }
    })
    void refreshResumable()
  },

  selectPhoto: (id) => set({ selectedId: id }),

  setVariant: (id) => {
    const variant = getVariant(id)
    // Switching template/variant is NOT an undoable edit (S1 scope): the whole
    // history belongs to the previous card, so it is dropped here, not recorded.
    history.clear()
    set((s) => {
      const previous = getVariant(s.design.variantId)
      const previousIsDefault = !s.design.text.title || s.design.text.title === previous.text?.title
      const isSameVariant = s.design.variantId === variant.id
      // A variant's own `text` block is authoritative for every visibility flag.
      // Rebuild from DEFAULT_TEXT (the same base makeDesignFromVariant uses at boot)
      // so nothing — e.g. showDate:false — leaks from the previously selected variant.
      // Re-applying the SAME variant leaves live user edits untouched; switching to a
      // different variant resets the flags to that variant's declared design.
      let text: TextSettings
      if (isSameVariant) {
        text = s.design.text
      } else {
        text = { ...DEFAULT_TEXT, ...(variant.text ?? {}) } as TextSettings
        // Preserve a user-typed custom title/subtitle across text-less variants
        // (legacy previousIsDefault behaviour); flags stay authoritative above.
        if (!variant.text && !previousIsDefault) {
          text.title = s.design.text.title
          text.subtitle = s.design.text.subtitle
        }
      }
      return {
        design: {
          // params is rescoped per style (declared values of THIS variant ⊕ its own
          // bucket); templateId/variantId are set inside rescopeParams. The `text`
          // rebuild below is b242f7c's authoritative-flags logic and stays untouched.
          ...rescopeParams(s.design, variant.id),
          text,
          // FIX #64 (other half, not in leica-wordmark-64.patch): `design.logo` is a
          // GLOBAL field that `rescopeParams()` deliberately does not re-scope, so it
          // survived a variant switch and handed the previous card's wordmark to
          // `env.images.logo` — the cross-card state leak behind
          // `stock-xiaomi-leica-1`. Same rule as `text` above: switching cards
          // resets, never inherits. Re-applying the SAME variant keeps the user's
          // live pick (store.ts:444-448), matching the existing isSameVariant branch.
          logo: isSameVariant ? s.design.logo : null,
          // The version pointer follows the same reset rule as the bitmap it
          // describes; a stale ref must never outlive the logo it was picked for.
          brandVersion: isSameVariant ? s.design.brandVersion : null
        },
        activePresetId: null,
        tab: 'template',
        // Picking a border returns to the border tool, at its first editor tab.
        tool: 'border',
        editorTab: 'logo'
      }
    })
    if (variant.vendorLogo || variant.autoLogo) {
      void (async () => {
        let file = variant.vendorLogo
        if (!file && variant.autoLogo) {
          const state = get()
          const photo = state.photos.find((p) => p.id === state.selectedId) ?? state.photos[0]
          if (!photo) return
          const dark = ['ink', 'black'].includes(String(state.design.params.theme))
          file = autoLogo(photo.exif.make, photo.exif.model, dark) ?? undefined
        }
        if (!file) return
        const asset = await logoAsset(file)
        set((s) =>
          s.design.variantId === variant.id
            ? { design: { ...s.design, logo: asset, text: { ...s.design.text, showLogo: true } } }
            : {}
        )
      })().catch(() => undefined)
    }
  },

  setParam: (key, value) => {
    // S9: `params.brandLogoFile` is the ONE carrier both renderers resolve
    // (scene.ts `styleBrandFile`, assets.ts `resolveBrandSlot`). The per-style
    // brand select writes here directly, so the catalog pointer has to follow
    // it: a version pinned in the Logo Library would otherwise keep winning and
    // the select would look dead. Same shape as `theme` below — one undo step,
    // the bitmap mirror decoded inside it.
    if (key === 'brandLogoFile') {
      const file = typeof value === 'string' ? value : ''
      const ref = brandRefForPick(file)
      const txn = history.openTxn()
      commitDesign((design) => ({ ...writeParam(design, key, value), brandVersion: ref }), {
        label: '品牌 Logo',
        key: 'logo'
      })
      void mirrorDesignLogo(txn)
      history.closeTxn(txn)
      return
    }
    commitDesign((design) => writeParam(design, key, value), {
      label: `参数 ${key}`,
      key: `param:${key}`
    })
    // A theme flip can make the picked logo cut unreadable (black mark on a dark
    // card). Reconcile in the background so typing/sliders never block on a decode,
    // but never let that second write become a second undo step: the open txn
    // keeps it inside the step just recorded above.
    if (key === 'theme') {
      const txn = history.openTxn()
      void reconcileLogo(txn).finally(() => history.closeTxn(txn))
    }
  },

  resetVariantParams: () => {
    const variant = getVariant(get().design.variantId)
    const base = getTemplate(variant.base)
    const declared = { ...base.defaults, ...(variant.params ?? {}) } as Params
    // S2: the Inspector's 「重置」 is a user edit and must be undoable, so it goes
    // through commitDesign like every other design write (label 重置参数).
    commitDesign(
      (design) => {
        const bucket = paramBucketKey(design)
        // "恢复此模板的默认参数" means THIS style's overrides go away; other styles keep theirs.
        const others: ParamOverrides = { ...(design.paramOverrides ?? {}) }
        delete others[bucket]
        const unchanged =
          !design.paramOverrides?.[bucket] &&
          JSON.stringify(design.params) === JSON.stringify(declared)
        // A reset on an untouched style is a no-op: record no empty undo step.
        if (unchanged) return design
        return {
          ...design,
          params: { ...declared },
          paramOverrides: Object.keys(others).length ? others : undefined
        }
      },
      { label: '重置参数', key: 'reset:variant' }
    )
  },

  resetParam: (key) => {
    const value = declaredParams(get().design)[key]
    commitDesign((design) => (value === undefined ? dropParam(design, key) : writeParam(design, key, value)), {
      label: `重置 ${key}`,
      key: `param:${key}`
    })
    if (key === 'theme') {
      const txn = history.openTxn()
      void reconcileLogo(txn).finally(() => history.closeTxn(txn))
    }
  },

  setText: (patch) => {
    commitDesign((design) => ({ ...design, text: { ...design.text, ...patch } }), {
      label: '文字',
      key: `text:${patchKey(patch)}`
    })
  },

  setWatermark: (patch) => {
    commitDesign((design) => ({ ...design, watermark: { ...design.watermark, ...patch } }), {
      label: '水印',
      key: `watermark:${patchKey(patch)}`
    })
  },

  setWatermarkFile: async (file) => {
    try {
      const asset = await imageAssetFromFile(file)
      commitDesign(
        (design) => ({
          ...design,
          watermark: { ...design.watermark, asset, enabled: true, target: 'photo' }
        }),
        { label: '水印图片', key: 'watermark:file' }
      )
      get().showToast('水印已载入', 'success')
    } catch {
      get().showToast('水印图读取失败', 'error')
    }
  },

  setExport: (patch) => {
    // Export settings never detached the active preset before this slice; keep
    // that behaviour (commitDesign clears it like every other edit).
    const presetId = get().activePresetId
    commitDesign((design) => ({ ...design, export: { ...design.export, ...patch } }), {
      label: '导出设置',
      key: `export:${patchKey(patch)}`
    })
    if (presetId !== null) set({ activePresetId: presetId })
  },

  setTextSlots: (slots) => set({ textSlots: slots }),

  setPreviewMeta: (previewMeta) => set({ previewMeta }),

  setRatio: (ratio) => {
    commitDesign((design) => ({ ...design, ratio }), { label: '画幅', key: 'ratio' })
  },

  setLogoFromBrand: async (file) => {
    // `params.brandLogoFile` mirrors the Inspector pick into params so the
    // stock templates' prepare() (which has no `design`) can preload that
    // brand's wordmark for the isBrandLogo slots. It is recorded even when the
    // bitmap load fails: the pick then means "brand chosen, no asset" and the
    // stock slots render empty instead of the vendor's baked mark.
    //
    // It is an EXPLICIT user choice, so it is bucketed per style: an absent key
    // means "auto-resolve the wordmark from this photo's EXIF brand" (549a653),
    // and that derivation must never be inherited by another style.
    //
    // Phase 1b-1: resolve the catalog pointer for the CURRENT surface as a side
    // effect only. `brandLogoFile`/`logo` semantics and legacy picker behavior
    // are unchanged; an un-catalogued (hand-dropped) file clears the pointer.
    const ref = refFromAssetKey(file, surfaceForDesign(get().design))
    // The awaited write below IS the step; the txn exists so any write that a
    // follow-up of the same pick might do lands inside it instead of opening a
    // second step (none today — kept so the contract is uniform with `theme`).
    const txn = history.openTxn()
    try {
      const asset = await logoAsset(file)
      commitDesign(
        (design) => ({
          ...writeParam(design, 'brandLogoFile', file),
          logo: asset,
          text: { ...design.text, showLogo: true },
          brandVersion: ref
        }),
        { label: '品牌 Logo', key: 'logo' }
      )
      get().showToast('Logo 已应用', 'success')
    } catch {
      commitDesign((design) => ({ ...writeParam(design, 'brandLogoFile', file), brandVersion: ref }), {
        label: '品牌 Logo',
        key: 'logo'
      })
      get().showToast('Logo 载入失败', 'error')
    } finally {
      history.closeTxn(txn)
    }
  },

  setLogoVersion: async (versionId, cutId) => {
    const version = LOGO_VERSION_BY_ID[versionId]
    if (!version) return
    const surface = surfaceForDesign(get().design)
    const requested = cutId ? version.cuts.find((c) => c.id === cutId) : null
    const cut =
      requested && cutFitsSurface(requested, surface)
        ? requested
        : cutForVersionSurface(version, surface)
    if (!cut) {
      get().showToast(`这个版本只适用于${surface === 'dark' ? '深色' : '浅色'}底`, 'error')
      return
    }
    // Same contract as setLogoFromBrand: the awaited write is the step, the txn
    // absorbs anything the same pick triggers afterwards.
    const txn = history.openTxn()
    try {
      const ref: BrandVersionRef = {
        brandKey: version.brandKey,
        versionId: version.id,
        cutId: cut.id,
        assetKey: cut.assetKey,
        file: cut.assetKey,
        ink: cut.ink,
        surface: cut.surface,
        name: version.name
      }
      let asset
      try {
        asset = await logoAsset(cut.assetKey)
      } catch {
        // S9: the PICK is the user's choice and the renderer resolves it
        // (`scene.ts` -> `styleBrandFile`); record it even without a bitmap, so
        // the slot renders empty instead of silently keeping the old brand.
        // Same contract `setLogoFromBrand` already documents for a failed load.
        commitDesign(
          (design) => ({
            ...writeParam(design, 'brandLogoFile', cut.assetKey),
            brandVersion: ref,
            logo: null
          }),
          { label: 'Logo 版本', key: 'logo' }
        )
        get().showToast('Logo 载入失败', 'error')
        return
      }
      lastMissingLogoWarnKey = ''
      commitDesign(
        (design) => ({
          ...writeParam(design, 'brandLogoFile', cut.assetKey),
          brandVersion: ref,
          logo: asset,
          text: { ...design.text, showLogo: true }
        }),
        { label: 'Logo 版本', key: 'logo' }
      )
    } finally {
      history.closeTxn(txn)
    }
  },

  // Kept as a public action for callers outside the theme flow; the theme flow
  // calls `reconcileLogo(txn)` directly so its write is absorbed into that step.
  ensureLogoCompatibility: () => reconcileLogo(),

  autoMatchLogo: async () => {
    const s = get()
    const photo = s.photos.find((p) => p.id === s.selectedId) ?? s.photos[0]
    if (!photo) {
      get().showToast('先添加一张照片，才能按机型匹配 Logo', 'error')
      return
    }
    const dark = ['ink', 'black'].includes(String(s.design.params.theme))
    const file = autoLogo(photo.exif.make, photo.exif.model, dark)
    if (!file) {
      get().showToast('没有匹配到品牌 Logo，可手动选择', 'error')
      return
    }
    // Legacy `autoLogo` can return a cut that cannot read on this card (e.g. a
    // black-only brand on a dark surface). Upgrade to a compatible catalog
    // version instead of pinning an unreadable cut; compatible picks keep the
    // old path byte-for-byte.
    const surface = surfaceForDesign(s.design)
    const cut = logoCutForFile(file)
    if (cut && !cutFitsSurface(cut, surface)) {
      const brandKey =
        matchBrand(photo.exif.make, photo.exif.model) ??
        versionForAssetKey(file)?.brandKey ??
        versionForAssetKey(cut.assetKey)?.brandKey
      const version = brandKey ? autoLogoVersionForBrand(brandKey, surface) : null
      if (!version) {
        get().showToast(
          `这个品牌没有${surface === 'dark' ? '深色' : '浅色'}底 Logo，可手动选择`,
          'error'
        )
        return
      }
      await get().setLogoVersion(version.id)
      return
    }
    await get().setLogoFromBrand(file)
    get().showToast('已按 EXIF 匹配品牌 Logo', 'success')
  },

  clearLogo: () => {
    // dropParam clears the key from the flat object AND from this style's bucket,
    // so "no explicit pick" (⇒ auto-match from EXIF) is restored consistently.
    commitDesign((design) => ({ ...dropParam(design, 'brandLogoFile'), logo: null, brandVersion: null }), {
      label: '清除 Logo',
      key: 'logo'
    })
  },

  setAvatarFile: async (file) => {
    try {
      const avatar = await imageAssetFromFile(file)
      commitDesign((design) => ({ ...design, avatar }), { label: '头像', key: 'avatar:file' })
      get().showToast('头像已载入', 'success')
    } catch {
      get().showToast('头像读取失败', 'error')
    }
  },

  clearAvatar: () => {
    commitDesign((design) => ({ ...design, avatar: null }), { label: '清除头像', key: 'avatar' })
  },

  setTab: (tab) => set({ tab }),
  setTool: (tool) => set({ tool }),
  setEditorTab: (editorTab) => set({ editorTab }),
  setBrandPickerOpen: (open) => set({ brandPickerOpen: open }),
  setPresetDialogOpen: (open) => set({ presetDialogOpen: open }),
  openExportDialog: (scope) => set({ exportDialog: scope }),
  closeExportDialog: () => set({ exportDialog: null }),
  openBatchPreview: (index) => set({ batchPreviewIndex: index }),
  closeBatchPreview: () => set({ batchPreviewIndex: null }),
  stepBatchPreview: (delta) =>
    set((s) => {
      if (s.batchPreviewIndex === null || !s.photos.length) return {}
      const n = s.photos.length
      return { batchPreviewIndex: (((s.batchPreviewIndex + delta) % n) + n) % n }
    }),
  setGalleryQuery: (q) => set({ galleryQuery: q }),

  savePreset: async (name) => {
    const preset: Preset = {
      id: `preset-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      name: name.trim() || '未命名预设',
      design: portablePresetDesign(get().design),
      createdAt: Date.now(),
      updatedAt: Date.now()
    }
    await putPreset(preset)
    const presets = await getAllPresets()
    set({ presets, activePresetId: preset.id })
    get().showToast(`预设「${preset.name}」已保存`, 'success')
  },

  applyPreset: (id) => {
    const preset = get().presets.find((p) => p.id === id)
    if (!preset) return
    const liveOutputDir = get().design.export.outputDir
    const design = hydrateDesign(preset.design)
    // Presets are portable: keep the user's current export folder and ignore any
    // machine-specific outputDir saved by legacy presets.
    design.export = { ...design.export, outputDir: liveOutputDir }
    // Upgrades a preset saved before the catalog (or with a partial pointer) to a
    // version ref derived from its own pick; purely additive metadata.
    const next = normalizeBrandVersion(design)
    commitDesign(() => next, { label: `预设 ${preset.name}`, key: `preset:${preset.id}` })
    // Applying a preset IS the preset: activePresetId points at it again.
    set({ activePresetId: preset.id, tab: 'template' })
    get().showToast(`已应用「${preset.name}」`, 'success')
  },

  undoDesign: () => {
    const restored = history.undo(get().design)
    if (!restored) return
    set({ design: restored, activePresetId: null })
  },

  redoDesign: () => {
    const restored = history.redo(get().design)
    if (!restored) return
    set({ design: restored, activePresetId: null })
  },

  deletePreset: async (id) => {
    await removePreset(id)
    const presets = await getAllPresets()
    set((s) => ({ presets, activePresetId: s.activePresetId === id ? null : s.activePresetId }))
    get().showToast('预设已删除', 'info')
  },

  importPresetFile: async (file) => {
    try {
      const data = await readJsonFile<any>(file)
      const list: any[] = Array.isArray(data) ? data : Array.isArray(data?.presets) ? data.presets : []
      let count = 0
      for (const item of list) {
        if (!item || typeof item.name !== 'string' || !isValidDesign(item.design)) continue
        const preset: Preset = {
          id: `import-${Date.now()}-${count}-${Math.random().toString(36).slice(2, 6)}`,
          name: String(item.name).slice(0, 60),
          design: portablePresetDesign(hydrateDesign(item.design)),
          createdAt: Number(item.createdAt) || Date.now(),
          updatedAt: Date.now()
        }
        await putPreset(preset)
        count++
      }
      const presets = await getAllPresets()
      set({ presets })
      get().showToast(count ? `已导入 ${count} 个预设` : '没有可导入的预设', count ? 'success' : 'error')
    } catch {
      get().showToast('预设文件解析失败', 'error')
    }
  },

  exportPresetFile: () => {
    const payload = {
      app: 'FrameMark',
      version: 1,
      exportedAt: new Date().toISOString(),
      presets: get().presets.map((preset) => ({ ...preset, design: portablePresetDesign(preset.design) })),
      currentDesign: portablePresetDesign(get().design)
    }
    const d = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const name = `framemark-presets-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}.json`
    if (window.framemark) {
      get().showToast('正在生成预设文件…', 'info')
      void window.framemark
        .savePresetFile(JSON.stringify(payload, null, 2), name)
        .then((file) => get().showToast(`预设已保存到 ${file}`, 'success'))
        .catch(() => get().showToast('预设文件导出失败', 'error'))
      return
    }
    if (Capacitor.isNativePlatform()) {
      get().showToast('正在生成预设文件…', 'info')
      void saveTextDocument(JSON.stringify(payload, null, 2), name)
        .then(() => get().showToast('预设已保存到 下载/FrameMark', 'success'))
        .catch(() => get().showToast('预设文件导出失败', 'error'))
      return
    }
    downloadJson(payload, name)
    get().showToast('预设文件已导出', 'success')
  },

  exportCurrent: async () => {
    const { photos, selectedId, design } = get()
    const photo = photos.find((p) => p.id === selectedId) ?? photos[0]
    if (!photo) {
      get().showToast('先添加一张照片', 'error')
      return
    }
    await runExport([photo], design, get())
  },

  exportAll: async () => {
    const { photos, design } = get()
    if (!photos.length) {
      get().showToast('先添加一组照片', 'error')
      return
    }
    await runExport(photos, design, get())
  },

  cancelExport: () => {
    const job = get().job
    if (job) set({ job: { ...job, cancelled: true } })
  },

  closeJob: () => set({ job: null }),

  findResumable: async (photos) => {
    if (!photos.length) {
      set({ resumable: null })
      return null
    }
    const tasks = await listJobs()
    // S3b: a task is resumable only for the SAME photo set AND the same design.
    // A changed template / margin / export setting is a new job, never a resume.
    const currentKey = exportDesignKey(get().design)
    const task = newestTaskFor(tasks, photos, (t) => isResumable(t) && t.designKey === currentKey)
    set({ resumable: task ? taskSummary(task) : null })
    return task
  },

  resumeExport: async (id) => {
    const task = await getJob(id)
    if (!task) {
      get().showToast('找不到这次导出的记录', 'error')
      return
    }
    await resumeTask(task)
  },

  retryFailed: async (id) => {
    const task = await getJob(id)
    if (!task) {
      get().showToast('找不到这次导出的记录', 'error')
      return
    }
    await retryTask(task)
  },

  dismissJob: async () => {
    const job = get().job
    if (job?.running) return
    const id = job?.taskId ?? get().resumable?.id ?? null
    set({ job: null, resumable: null })
    if (!id) return
    try {
      const task = await getJob(id)
      if (!task) return
      // 新建任务: the record is retired (not deleted) so these photos export again.
      // `cancelled` is terminal: it is neither resumable nor used as history.
      task.status = 'cancelled'
      task.updatedAt = Date.now()
      await putJob(task)
    } catch {
      // history is best-effort; the overlay still closes
    }
  },

  showToast: (message, type = 'info') => {
    if (toastTimer) clearTimeout(toastTimer)
    const id = Date.now()
    set({ toast: { id, message, type } })
    toastTimer = setTimeout(() => {
      if (get().toast?.id === id) set({ toast: null })
    }, 3400)
  }
}))

/** Recompute the 「上次导到 X/Y」 notice for whatever is imported right now. */
async function refreshResumable(): Promise<void> {
  const { photos, findResumable } = useAppStore.getState()
  await findResumable(photos).catch(() => undefined)
}

/**
 * One live run of a persisted task. 导出 / 继续 / 重试失败项 all funnel through
 * here so the IndexedDB row is written before the first item and refreshed after
 * every item. Write errors are swallowed (the export never fails because of the
 * history), but the write is awaited per item — see `persist` below.
 */
async function executeTask(
  task: ExportTask,
  plan: { photos: PhotoAsset[]; keys: string[]; skip: Set<string> }
): Promise<void> {
  const total = plan.photos.length
  task.status = 'running'
  task.updatedAt = Date.now()
  // Awaited: from the first item onwards a kill/restart can find this row.
  await putJob(task).catch(() => undefined)
  useAppStore.setState({
    job: {
      running: true,
      taskId: task.id,
      total,
      done: 0,
      skipped: 0,
      current: plan.photos[0]?.name ?? '',
      cancelled: false,
      errors: []
    },
    resumable: null
  })
  // S4: on Android this raises a dataSync foreground service + wake lock so the
  // WebView keeps running with the screen off. No-op on desktop; never throws.
  startExportForeground(total, plan.photos[0]?.name ?? '')
  // Fire-and-forget by contract (errors are swallowed), but returned to the
  // exporter so the row is committed before the next photo starts: a kill must
  // never leave an exported file without its history entry.
  const persist = (): Promise<void> => {
    task.updatedAt = Date.now()
    return putJob(task).then(
      () => undefined,
      () => undefined
    )
  }
  let result: Awaited<ReturnType<typeof exportBatch>>
  try {
    result = await exportBatch({
      photos: plan.photos,
      design: task.design,
      presetName: task.presetName || undefined,
      keys: plan.keys,
      skip: plan.skip,
      onProgress: (done, t, current) => {
        const job = useAppStore.getState().job
        if (job) useAppStore.setState({ job: { ...job, done, total: t, current } })
        // S4: mirrors the same progress into the ongoing notification.
        updateExportForeground(done, t, current)
      },
      onItemDone: (key, r) => {
        task.results[key] = { path: r.path, bytes: r.bytes, w: r.width, h: r.height }
        delete task.errors[key]
        return persist()
      },
      onItemError: (key, message) => {
        task.errors[key] = message
        return persist()
      },
      isCancelled: () => useAppStore.getState().job?.cancelled === true
    })
  } finally {
    // S4: completion, cancellation, or a thrown batch all end the foreground
    // service — leave no ongoing notification and no wake lock behind.
    stopExportForeground()
  }
  task.status = resolveStatus(task)
  task.updatedAt = Date.now()
  await putJob(task).catch(() => undefined)

  const success = result.results.length
  const last = result.results[result.results.length - 1]
  useAppStore.setState({
    job: {
      running: false,
      taskId: task.id,
      total,
      done: success + result.errors.length + result.skipped,
      skipped: result.skipped,
      current: '',
      cancelled: result.cancelled,
      errors: result.errors,
      lastPath: last?.path
    }
  })
  const toast = useAppStore.getState().showToast
  if (result.errors.length) {
    toast(`导出完成：成功 ${success}，失败 ${result.errors.length}`, 'error')
  } else if (result.cancelled) {
    toast('已取消导出', 'info')
  } else if (result.skipped === total && total > 0) {
    toast(`这 ${total} 张之前已经导出过，本次没有重复写入`, 'info')
  } else if (success === 1 && !result.skipped) {
    toast(`导出完成：${last?.path ?? ''}`, 'success')
  } else {
    toast(`已导出 ${success} 张${result.skipped ? `，跳过 ${result.skipped} 张` : ''}`, 'success')
  }
  await refreshResumable()
}

/** Continue a task: finished keys are skipped, every other key is attempted. */
async function resumeTask(task: ExportTask): Promise<void> {
  const photos = useAppStore.getState().photos
  const { todo, skipped } = reconcile(task, photos)
  const byKey = new Map([...todo, ...skipped].map((m) => [m.key, m.photo]))
  const keys = task.order.filter((k) => byKey.has(k))
  if (!keys.length) {
    useAppStore.getState().showToast('这批照片都已经导出过了', 'info')
    await refreshResumable()
    return
  }
  await executeTask(task, {
    photos: keys.map((k) => byKey.get(k)!),
    keys,
    skip: new Set(skipped.map((m) => m.key))
  })
}

/** Re-export only the keys that recorded an error; every other key is skipped. */
async function retryTask(task: ExportTask): Promise<void> {
  const photos = useAppStore.getState().photos
  const { todo, skipped } = reconcile(task, photos)
  const byKey = new Map([...todo, ...skipped].map((m) => [m.key, m.photo]))
  const keys = task.order.filter((k) => byKey.has(k))
  const failed = new Set(keys.filter((k) => !task.results[k] && task.errors[k]))
  if (!failed.size) {
    useAppStore.getState().showToast('没有失败的项', 'info')
    return
  }
  await executeTask(task, {
    photos: keys.map((k) => byKey.get(k)!),
    keys,
    skip: new Set(keys.filter((k) => !failed.has(k)))
  })
}

async function runExport(targets: PhotoAsset[], design: Design, state: AppState): Promise<void> {
  let currentDesign = design
  if (isDesktop && !currentDesign.export.outputDir) {
    try {
      currentDesign = { ...currentDesign, export: { ...currentDesign.export, outputDir: await window.framemark!.defaultExportDir() } }
      useAppStore.setState({ design: currentDesign })
    } catch {
      // ignore
    }
  }
  const presetName =
    state.presets.find((p) => p.id === state.activePresetId)?.name ??
    (currentDesign.variantId ? getVariant(currentDesign.variantId).name : getTemplate(currentDesign.templateId).name)

  const tasks = await listJobs()
  // S3b identity: photo set + design. The key folds long base64 payloads and is
  // stable across a restart (`exportDesignKey` drops the re-derived brandVersion).
  const currentKey = exportDesignKey(currentDesign)

  // 1. An unfinished task for these photos AND this design is continued, not
  //    duplicated: this is what makes 开始导出 after a crash behave like 继续.
  //    An unfinished task with a DIFFERENT design is ignored (new job below).
  const live = newestTaskFor(tasks, targets, (t) => isResumable(t) && t.designKey === currentKey)
  if (live) {
    await resumeTask(live)
    return
  }

  // 2. Finished history: skip only photos this exact design already exported.
  //    A new design on the same photos must produce new files (the trap this
  //    slice fixes), so a design mismatch creates a task without seeding.
  //    Only the NEWEST record for THIS design counts: 新建任务 marks it
  //    `cancelled`, and that must switch the history off instead of falling
  //    back to an older row.
  const latest = newestTaskFor(tasks, targets, (t) => t.designKey === currentKey)
  const history = latest && latest.status === 'done' ? latest : null
  const task = makeTask({
    design: currentDesign,
    photos: targets,
    presetName,
    outputDir: currentDesign.export.outputDir
  })
  if (history) seedFromHistory(task, history, targets)
  const { skipped } = reconcile(task, targets)
  await executeTask(task, {
    photos: targets,
    keys: targets.map((_, i) => `k${i + 1}`),
    skip: new Set(skipped.map((m) => m.key))
  })
}

let persistTimer: ReturnType<typeof setTimeout> | null = null
useAppStore.subscribe((state, prev) => {
  if (state.design !== prev.design) {
    if (persistTimer) clearTimeout(persistTimer)
    persistTimer = setTimeout(() => saveLastDesign(state.design), 500)
  }
})

export { makeDesignFromVariant }
