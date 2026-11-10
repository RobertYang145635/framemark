/**
 * Undo/redo engine for DESIGN FIELDS ONLY (slice S1).
 *
 * Pure bookkeeping: no React, no zustand, no store import. The store owns the
 * `Design` object and hands this module snapshots; this module never inspects a
 * design, so `textSlots` / `previewMeta` (derived, recomputed every render) can
 * never leak into a step.
 *
 * Scope (product owner, 2026-10-28): params / text / watermark / ratio / logo /
 * brandVersion / export settings. Switching template or variant is NOT undoable —
 * the store calls `clear()` there, and adding/removing photos never records.
 *
 * NOT persisted: the design already survives restarts via the 500 ms debounce in
 * store.ts (`framemark.lastDesign.v1`); history is per-session on purpose.
 *
 * Coalescing rule (one entry per "user gesture", not per keystroke):
 *   push() merges into the top entry when the key is identical AND
 *     - a gesture is open (beginGesture/endGesture): always merge, or
 *     - the previous push is younger than COALESCE_MS and no undo/redo happened
 *       since (undo/redo re-open the stack, so the next edit is always a new step).
 * beginGesture()/endGesture() both SEAL: the first write after either boundary
 * starts a new step, and writes inside the gesture then merge regardless of time.
 * That is how a slider drag that lasts 3 s stays one step while two separate
 * drags of the same field stay two.
 */

import type { Design } from '../types'

export interface HistoryEntry {
  /** Human label, shown by the debug hook / future UI. */
  label: string
  /** Coalescing identity: `param:marginPct`, `text:slotValues`, `ratio`, ... */
  key: string
  /** Design as it was BEFORE the write. The store passes `after` back on undo. */
  before: Design
}

export interface PushArgs {
  label: string
  key: string
  before: Design
}

/** Hard cap on steps kept (both stacks). */
export const HISTORY_LIMIT = 50
/** Same-key edits closer than this merge into one step. */
export const COALESCE_MS = 600

const undoStack: HistoryEntry[] = []
const redoStack: HistoryEntry[] = []
let lastPushAt = 0
/** False after clear()/undo()/redo(): the restored top entry must not swallow a new edit. */
let coalescible = false
let gestureDepth = 0
let txnSeq = 0
const openTxns = new Set<number>()

function trim(stack: HistoryEntry[]): void {
  if (stack.length > HISTORY_LIMIT) stack.splice(0, stack.length - HISTORY_LIMIT)
}

/**
 * Record that `before` is about to be replaced. `after` is intentionally not
 * stored: undo() receives the live design from the caller, which keeps the stack
 * free of references that a later edit never touches.
 */
export function push({ label, key, before }: PushArgs): void {
  const now = Date.now()
  const top = undoStack[undoStack.length - 1]
  const sameKey = Boolean(top) && top.key === key
  const merge = sameKey && coalescible && (gestureDepth > 0 || now - lastPushAt < COALESCE_MS)
  // Any new step invalidates redo, even a merged one (the branch it would redo
  // no longer matches the design the user is holding).
  redoStack.length = 0
  if (merge) {
    // Keep the OLDEST `before`: undo must jump to the state before the gesture
    // (a 10-write slider drag is one step back to where the drag started).
    top.label = label
    lastPushAt = now
    coalescible = true
    return
  }
  undoStack.push({ label, key, before })
  trim(undoStack)
  lastPushAt = now
  coalescible = true
}

/**
 * Pop the newest step and return the design to restore (its `before`).
 * The state being left is parked on the redo stack so redo() can come back to
 * exactly the design the user was looking at — including writes that were
 * absorbed into that step (async logo reconciliation).
 */
export function undo(current: Design): Design | null {
  const entry = undoStack.pop()
  if (!entry) return null
  redoStack.push({ label: entry.label, key: entry.key, before: current })
  trim(redoStack)
  lastPushAt = 0
  coalescible = false
  return entry.before
}

/** Re-mount the last undone step; the design being left goes back on the undo stack. */
export function redo(current: Design): Design | null {
  const entry = redoStack.pop()
  if (!entry) return null
  undoStack.push({ label: entry.label, key: entry.key, before: current })
  trim(undoStack)
  lastPushAt = 0
  coalescible = false
  return entry.before
}

/** Start a UI gesture (slider drag / text typing). Nestable; pairs with endGesture(). */
export function beginGesture(): void {
  gestureDepth += 1
  if (gestureDepth === 1) {
    // Seal what happened before the gesture: the FIRST write of a drag must be its
    // own step, otherwise it would merge into an unrelated earlier edit of the
    // same field (two separate drags minutes apart are two steps, not one).
    lastPushAt = 0
    coalescible = false
  }
}

export function endGesture(): void {
  gestureDepth = Math.max(0, gestureDepth - 1)
  if (gestureDepth > 0) return
  // The next write starts a fresh step even if it lands inside COALESCE_MS.
  lastPushAt = 0
  coalescible = false
}

/**
 * Open a transaction that absorbs async follow-up writes into the step that
 * opened it. Returns an id the store passes to commitDesign({ absorb: id }).
 */
export function openTxn(): number {
  txnSeq += 1
  openTxns.add(txnSeq)
  return txnSeq
}

/** Close a txn in the caller's `finally`. Unknown/duplicate ids are a no-op. */
export function closeTxn(id: number): void {
  openTxns.delete(id)
}

/** True while the txn is open. A closed id marks a STALE follow-up write. */
export function isOpenTxn(id: number): boolean {
  return openTxns.has(id)
}

/** Template/variant switch, preset reset, session reset. Drops both stacks. */
export function clear(): void {
  undoStack.length = 0
  redoStack.length = 0
  lastPushAt = 0
  coalescible = false
  gestureDepth = 0
  openTxns.clear()
}

export function size(): number {
  return undoStack.length
}

export function redoSize(): number {
  return redoStack.length
}

/** Oldest → newest, for the debug hook / future tooltips. */
export function labels(): string[] {
  return undoStack.map((entry) => entry.label)
}
