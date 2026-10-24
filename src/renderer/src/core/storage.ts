import type { Design, Preset } from '../types'

const DB_NAME = 'framemark'
const DB_VERSION = 1
const STORE = 'presets'
const LAST_KEY = 'framemark.lastDesign.v1'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('IndexedDB 打开失败'))
  })
}

export async function getAllPresets(): Promise<Preset[]> {
  try {
    const db = await openDb()
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readonly')
      const req = tx.objectStore(STORE).getAll()
      req.onsuccess = () => resolve(((req.result as Preset[]) ?? []).sort((a, b) => b.updatedAt - a.updatedAt))
      req.onerror = () => reject(req.error)
    })
  } catch {
    return []
  }
}

export async function putPreset(preset: Preset): Promise<void> {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).put(preset)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

export async function removePreset(id: string): Promise<void> {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).delete(id)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
}

export function loadLastDesign(): Design | null {
  try {
    const s = localStorage.getItem(LAST_KEY)
    if (!s) return null
    return JSON.parse(s) as Design
  } catch {
    return null
  }
}

export function saveLastDesign(design: Design): void {
  try {
    localStorage.setItem(LAST_KEY, JSON.stringify(design))
  } catch {
    // large logos can exceed storage; last design is a convenience only
  }
}

export function downloadJson(data: unknown, filename: string): void {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

export function readJsonFile<T>(file: File): Promise<T> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => {
      try {
        resolve(JSON.parse(String(r.result)) as T)
      } catch (e) {
        reject(e)
      }
    }
    r.onerror = () => reject(new Error('读取 JSON 失败'))
    r.readAsText(file)
  })
}
