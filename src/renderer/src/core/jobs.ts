import type { Design, PhotoAsset } from '../types'
import { designKey } from './batch-preview'

/**
 * S3/S3b — resumable export history.
 *
 * We deliberately do not copy original photos into app storage. Instead one
 * `ExportTask` row is persisted in IndexedDB keyed by a PHOTO FINGERPRINT plus
 * a DESIGN KEY: after a crash the user re-imports the same files and every item
 * that already has a `results[key]` entry is skipped, so the resumed run can
 * never collide with the `-1` suffix `uniqueOutputPath` (main/index.ts) appends.
 *
 * A run is resumable / already-exported only while BOTH match: the same photo
 * set AND the same design. Re-exporting the same photos with a different
 * template or export setting (quality / long edge / format / folder) therefore
 * starts a NEW task and writes new files; the old record stays as history.
 * `designKey` comes from `batch-preview.ts` (the same key the styled-thumb cache
 * uses) and folds long base64 payloads to `length:hash`.
 */

export type ExportTaskStatus = 'running' | 'paused' | 'done' | 'cancelled'

export interface ExportTaskPhoto {
  key: string
  fingerprint: string
  name: string
}

export interface ExportTaskResult {
  path: string
  bytes: number
  w: number
  h: number
}

export interface ExportTask {
  id: string
  createdAt: number
  updatedAt: number
  status: ExportTaskStatus
  design: Design
  /**
   * Stable identity of `design` (same helper the styled-preview cache uses).
   * A task can only be resumed / used as export history when this equals the
   * current design's key. Rows written before S3b do not carry it and are
   * treated as a different design (never resumed, never seeded).
   */
  designKey: string
  presetName: string
  outputDir: string
  photos: ExportTaskPhoto[]
  /** Keys in export order; `photos` is the (unordered) fingerprint registry. */
  order: string[]
  results: Record<string, ExportTaskResult>
  errors: Record<string, string>
}

/** Stable identity of one photo across imports/restarts (no pixel hashing). */
export function fingerprint(photo: PhotoAsset): string {
  return `${photo.name}|${photo.bytes ?? ''}|${photo.width}x${photo.height}`
}

/**
 * Design identity used by export tasks. `designKey` (batch-preview.ts) is the
 * ONE key helper; this wrapper only canonicalises its input: `brandVersion` is
 * derived metadata that `init()` re-creates from `logo` on every launch, not a
 * rendered field. It is also inserted at a different object position by
 * hydration, and `designKey` is order-sensitive — keying it made the same
 * auto-logo design disagree with itself after a restart (crash resume went
 * dark). The rendered bitmap lives in `logo`, which stays part of the key.
 */
export function exportDesignKey(design: Design): string {
  if (!design.brandVersion) return designKey(design)
  const { brandVersion: _derived, ...rest } = design
  return designKey(rest)
}

export function newTaskId(): string {
  return `job-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export function makeTask(args: {
  design: Design
  photos: PhotoAsset[]
  presetName?: string
  outputDir: string
}): ExportTask {
  const createdAt = Date.now()
  const photos: ExportTaskPhoto[] = args.photos.map((photo, i) => ({
    key: `k${i + 1}`,
    fingerprint: fingerprint(photo),
    name: photo.name
  }))
  return {
    id: newTaskId(),
    createdAt,
    updatedAt: createdAt,
    status: 'running',
    design: args.design,
    designKey: exportDesignKey(args.design),
    presetName: args.presetName ?? '',
    outputDir: args.outputDir,
    photos,
    order: photos.map((p) => p.key),
    results: {},
    errors: {}
  }
}

export interface TaskPhotoMatch {
  key: string
  photo: PhotoAsset
  fingerprint: string
}

/**
 * Map the CURRENT imports onto a persisted task by fingerprint.
 *   - `skipped` — the task already has an output for this photo.
 *   - `todo`    — the task still owes an output.
 * Photos the task does not know are not returned (they belong to a new task),
 * and task photos that are not imported right now simply stay unresolved.
 */
export function reconcile(task: ExportTask, photos: PhotoAsset[]): { todo: TaskPhotoMatch[]; skipped: TaskPhotoMatch[] } {
  const byFingerprint = new Map<string, PhotoAsset>()
  for (const photo of photos) {
    const fp = fingerprint(photo)
    // First import wins: a duplicate file in the batch is not exported twice.
    if (!byFingerprint.has(fp)) byFingerprint.set(fp, photo)
  }
  const entryByKey = new Map(task.photos.map((p) => [p.key, p]))
  const todo: TaskPhotoMatch[] = []
  const skipped: TaskPhotoMatch[] = []
  for (const key of task.order) {
    const entry = entryByKey.get(key)
    if (!entry) continue
    const photo = byFingerprint.get(entry.fingerprint)
    if (!photo) continue
    const match = { key, photo, fingerprint: entry.fingerprint }
    if (task.results[key]) skipped.push(match)
    else todo.push(match)
  }
  return { todo, skipped }
}

/** The newest task (listJobs is newest-first) accepted by `accept` that shares ≥1 photo. */
export function newestTaskFor(
  tasks: ExportTask[],
  photos: PhotoAsset[],
  accept: (task: ExportTask) => boolean
): ExportTask | null {
  if (!photos.length) return null
  const fingerprints = new Set(photos.map(fingerprint))
  for (const task of tasks) {
    if (!accept(task)) continue
    if (task.photos.some((p) => fingerprints.has(p.fingerprint))) return task
  }
  return null
}

export function isResumable(task: ExportTask): boolean {
  return task.status === 'running' || task.status === 'paused'
}

/** X/Y for 「上次导到 X/Y」: finished items over the task's item count. */
export function taskSummary(task: ExportTask): { id: string; done: number; total: number } {
  return { id: task.id, done: Object.keys(task.results).length, total: task.order.length }
}

/** A task is finished once every ordered key can point at an output. */
export function resolveStatus(task: ExportTask): ExportTaskStatus {
  return task.order.every((key) => Boolean(task.results[key])) ? 'done' : 'paused'
}

/**
 * Carry "already exported" results from a finished task into a freshly created
 * one (same photo, any design). Returns how many items were seeded. The seeded
 * entries are what makes a second 导出 of the same photos write 0 new files.
 */
export function seedFromHistory(next: ExportTask, history: ExportTask, photos: PhotoAsset[]): number {
  const live = new Set(photos.map(fingerprint))
  const outByFingerprint = new Map<string, ExportTaskResult>()
  for (const entry of history.photos) {
    const result = history.results[entry.key]
    if (result && live.has(entry.fingerprint)) outByFingerprint.set(entry.fingerprint, result)
    // `entry.key` may not be in history.order (defensive), result lookup is enough.
  }
  let seeded = 0
  for (const entry of next.photos) {
    const result = outByFingerprint.get(entry.fingerprint)
    if (!result) continue
    next.results[entry.key] = { ...result }
    seeded++
  }
  return seeded
}
