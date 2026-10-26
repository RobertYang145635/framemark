import { create } from 'zustand'
import { Capacitor } from '@capacitor/core'
import type {
  Design,
  ExportJob,
  InspectorTab,
  ParamOverrides,
  ParamValue,
  Params,
  PhotoAsset,
  Preset,
  Ratio,
  TextSettings,
  WatermarkSettings
} from './types'
import { getTemplate } from './core/templates'
import { getVariant } from './core/variants'
import { autoLogo, logoAsset } from './core/brands'
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
import { getAllPresets, putPreset, removePreset, loadLastDesign, saveLastDesign, downloadJson, readJsonFile } from './core/storage'
import { exportBatch } from './core/exporter'
import { saveTextDocument } from './core/gallery'
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

interface AppState {
  photos: PhotoAsset[]
  selectedId: string | null
  design: Design
  presets: Preset[]
  activePresetId: string | null
  initialized: boolean
  busy: string | null
  job: ExportJob | null
  tab: InspectorTab
  brandPickerOpen: boolean
  presetDialogOpen: boolean
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
  setParam: (key: string, value: Params[string]) => void
  setText: (patch: Partial<TextSettings>) => void
  setWatermark: (patch: Partial<WatermarkSettings>) => void
  setWatermarkFile: (file: File) => Promise<void>
  setExport: (patch: Partial<Design['export']>) => void
  setRatio: (ratio: Ratio) => void
  setLogoFromBrand: (file: string) => Promise<void>
  autoMatchLogo: () => Promise<void>
  clearLogo: () => void
  setAvatarFile: (file: File) => Promise<void>
  clearAvatar: () => void
  setTab: (tab: InspectorTab) => void
  setBrandPickerOpen: (open: boolean) => void
  setPresetDialogOpen: (open: boolean) => void
  setGalleryQuery: (q: string) => void
  savePreset: (name: string) => Promise<void>
  applyPreset: (id: string) => void
  deletePreset: (id: string) => Promise<void>
  importPresetFile: (file: File) => Promise<void>
  exportPresetFile: () => void
  exportCurrent: () => Promise<void>
  exportAll: () => Promise<void>
  cancelExport: () => void
  closeJob: () => void
  showToast: (message: string, type?: Toast['type']) => void
}

const isDesktop = typeof window !== 'undefined' && Boolean(window.framemark)
let toastTimer: ReturnType<typeof setTimeout> | null = null

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

async function autoApplyLogo(photo: PhotoAsset | undefined): Promise<void> {
  if (!photo) return
  const state = useAppStore.getState()
  if (state.design.logo) return
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
  job: null,
  tab: 'template',
  brandPickerOpen: false,
  presetDialogOpen: false,
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
    set({ presets, design, initialized: true })
    window.setTimeout(() => set({ initialized: true }), 400)
  },

  addFiles: async (files) => {
    const images = files.filter((f) => f.type.startsWith('image/') || /\.(jpe?g|png|webp|tiff?|heic|heif)$/i.test(f.name))
    if (!images.length) return
    set({ busy: `正在读取 ${images.length} 张照片…` })
    const added: PhotoAsset[] = []
    let failed = 0
    for (const file of images) {
      try {
        added.push(await photoFromFile(file))
      } catch {
        failed++
      }
    }
    set((s) => ({
      photos: [...s.photos, ...added],
      selectedId: s.selectedId ?? added[0]?.id ?? null,
      busy: null
    }))
    await autoApplyLogo(added[0])
    if (added.length) get().showToast(`已添加 ${added.length} 张照片`, 'success')
    if (failed) get().showToast(`${failed} 张照片读取失败`, 'error')
  },

  addPaths: async (paths) => {
    if (!paths.length) return { added: 0, failed: 0 }
    if (!window.framemark) return { added: 0, failed: 0 }
    set({ busy: `正在读取 ${paths.length} 张照片…` })
    const added: PhotoAsset[] = []
    let failed = 0
    for (const p of paths) {
      try {
        const preview = await window.framemark.previewImage(p)
        added.push(photoFromPath(preview, p))
      } catch {
        failed++
      }
    }
    set((s) => ({
      photos: [...s.photos, ...added],
      selectedId: s.selectedId ?? added[0]?.id ?? null,
      busy: null
    }))
    await autoApplyLogo(added[0])
    if (added.length) get().showToast(`已添加 ${added.length} 张照片`, 'success')
    if (failed) get().showToast(`${failed} 张照片读取失败`, 'error')
    return { added: added.length, failed }
  },

  addSample: async (url, name) => {
    set({ busy: '正在载入示例…' })
    try {
      const asset = await photoFromUrl(url, name)
      set((s) => ({ photos: [...s.photos, asset], selectedId: s.selectedId ?? asset.id, busy: null }))
      get().showToast('示例已载入', 'success')
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
    set({ busy: '正在扫描文件夹…' })
    let scan: FolderScanResult
    try {
      scan = await window.framemark.listImages(folder)
    } finally {
      set({ busy: null })
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
  },

  selectPhoto: (id) => set({ selectedId: id }),

  setVariant: (id) => {
    const variant = getVariant(id)
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
          logo: isSameVariant ? s.design.logo : null
        },
        activePresetId: null,
        tab: 'template'
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

  setParam: (key, value) => set((s) => ({ design: writeParam(s.design, key, value), activePresetId: null })),

  resetVariantParams: () => {
    const s = get()
    const variant = getVariant(s.design.variantId)
    const base = getTemplate(variant.base)
    const declared = { ...base.defaults, ...(variant.params ?? {}) } as Params
    const bucket = paramBucketKey(s.design)
    // "恢复此模板的默认参数" means THIS style's overrides go away; other styles keep theirs.
    const others: ParamOverrides = { ...(s.design.paramOverrides ?? {}) }
    delete others[bucket]
    set({
      design: {
        ...s.design,
        params: declared,
        paramOverrides: Object.keys(others).length ? others : undefined
      },
      activePresetId: null
    })
  },

  setText: (patch) =>
    set((s) => ({ design: { ...s.design, text: { ...s.design.text, ...patch } }, activePresetId: null })),

  setWatermark: (patch) =>
    set((s) => ({
      design: { ...s.design, watermark: { ...s.design.watermark, ...patch } },
      activePresetId: null
    })),

  setWatermarkFile: async (file) => {
    try {
      const asset = await imageAssetFromFile(file)
      set((s) => ({
        design: {
          ...s.design,
          watermark: { ...s.design.watermark, asset, enabled: true, target: 'photo' }
        },
        activePresetId: null
      }))
      get().showToast('水印已载入', 'success')
    } catch {
      get().showToast('水印图读取失败', 'error')
    }
  },

  setExport: (patch) =>
    set((s) => ({ design: { ...s.design, export: { ...s.design.export, ...patch } } })),

  setRatio: (ratio) => set((s) => ({ design: { ...s.design, ratio }, activePresetId: null })),

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
    try {
      const asset = await logoAsset(file)
      set((s) => ({
        design: {
          ...writeParam(s.design, 'brandLogoFile', file),
          logo: asset,
          text: { ...s.design.text, showLogo: true }
        },
        activePresetId: null
      }))
      get().showToast('Logo 已应用', 'success')
    } catch {
      set((s) => ({ design: writeParam(s.design, 'brandLogoFile', file) }))
      get().showToast('Logo 载入失败', 'error')
    }
  },

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
    await get().setLogoFromBrand(file)
    get().showToast('已按 EXIF 匹配品牌 Logo', 'success')
  },

  clearLogo: () =>
    set((s) => ({
      // dropParam clears the key from the flat object AND from this style's bucket,
      // so "no explicit pick" (⇒ auto-match from EXIF) is restored consistently.
      design: { ...dropParam(s.design, 'brandLogoFile'), logo: null },
      activePresetId: null
    })),

  setAvatarFile: async (file) => {
    try {
      const avatar = await imageAssetFromFile(file)
      set((s) => ({ design: { ...s.design, avatar }, activePresetId: null }))
      get().showToast('头像已载入', 'success')
    } catch {
      get().showToast('头像读取失败', 'error')
    }
  },

  clearAvatar: () => set((s) => ({ design: { ...s.design, avatar: null }, activePresetId: null })),

  setTab: (tab) => set({ tab }),
  setBrandPickerOpen: (open) => set({ brandPickerOpen: open }),
  setPresetDialogOpen: (open) => set({ presetDialogOpen: open }),
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
    set({ design, activePresetId: preset.id, tab: 'template' })
    get().showToast(`已应用「${preset.name}」`, 'success')
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

  showToast: (message, type = 'info') => {
    if (toastTimer) clearTimeout(toastTimer)
    const id = Date.now()
    set({ toast: { id, message, type } })
    toastTimer = setTimeout(() => {
      if (get().toast?.id === id) set({ toast: null })
    }, 3400)
  }
}))

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
  useAppStore.setState({
    job: { running: true, total: targets.length, done: 0, current: targets[0]?.name ?? '', cancelled: false, errors: [] }
  })
  const result = await exportBatch({
    photos: targets,
    design: currentDesign,
    presetName,
    onProgress: (done, total, current) => {
      const job = useAppStore.getState().job
      if (job) useAppStore.setState({ job: { ...job, done, total, current } })
    },
    isCancelled: () => useAppStore.getState().job?.cancelled === true
  })
  const last = result.results[result.results.length - 1]
  const job = useAppStore.getState().job
  useAppStore.setState({
    job: {
      running: false,
      total: targets.length,
      done: result.results.length + result.errors.length,
      current: '',
      cancelled: result.cancelled,
      errors: result.errors,
      lastPath: last?.path
    }
  })
  if (result.errors.length) {
    useAppStore.getState().showToast(`导出完成：成功 ${result.results.length}，失败 ${result.errors.length}`, 'error')
  } else if (result.cancelled) {
    useAppStore.getState().showToast('已取消导出', 'info')
  } else {
    useAppStore.getState().showToast(
      result.results.length === 1 ? `导出完成：${last?.path ?? ''}` : `已导出 ${result.results.length} 张`,
      'success'
    )
  }
  if (!job) useAppStore.setState({ job: null })
}

let persistTimer: ReturnType<typeof setTimeout> | null = null
useAppStore.subscribe((state, prev) => {
  if (state.design !== prev.design) {
    if (persistTimer) clearTimeout(persistTimer)
    persistTimer = setTimeout(() => saveLastDesign(state.design), 500)
  }
})

export { makeDesignFromVariant }
