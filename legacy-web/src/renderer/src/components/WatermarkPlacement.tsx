import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Move } from 'lucide-react'
import type { Anchor, Rect } from '../types'
import { useAppStore } from '../store'
import { watermarkArea, watermarkRect } from '../core/watermark'

const ANCHORS: { value: Anchor; label: string; x: number; y: number }[] = [
  { value: 'top-left', label: '左上', x: 8, y: 8 },
  { value: 'top-center', label: '上中', x: 50, y: 8 },
  { value: 'top-right', label: '右上', x: 92, y: 8 },
  { value: 'middle-left', label: '左中', x: 8, y: 50 },
  { value: 'center', label: '居中', x: 50, y: 50 },
  { value: 'middle-right', label: '右中', x: 92, y: 50 },
  { value: 'bottom-left', label: '左下', x: 8, y: 92 },
  { value: 'bottom-center', label: '下中', x: 50, y: 92 },
  { value: 'bottom-right', label: '右下', x: 92, y: 92 }
]

const ANCHOR_LABEL: Record<Anchor, string> = Object.fromEntries(
  ANCHORS.map((a) => [a.value, a.label])
) as Record<Anchor, string>

function clampOffset(value: number): number {
  return Math.max(-40, Math.min(40, Math.round(value * 2) / 2))
}

/**
 * Direct manipulation layer for the PNG watermark: shows the actual draw
 * rectangle on top of the preview canvas, lets the user drag it (writes the
 * same X/Y percentages the sliders write) and click one of the nine anchor
 * dots. Uses the exact `watermarkRect()` the canvas painter uses, so what is
 * dragged is what gets exported.
 */
export function WatermarkPlacement({
  canvas,
  photoRect,
  W,
  H
}: {
  canvas: HTMLCanvasElement | null
  photoRect: Rect | null
  W: number
  H: number
}) {
  const tool = useAppStore((s) => s.tool)
  const wm = useAppStore((s) => s.design.watermark)
  const setWatermark = useAppStore((s) => s.setWatermark)
  const [box, setBox] = useState<{ left: number; top: number; width: number; height: number } | null>(null)
  const [dragging, setDragging] = useState(false)
  const dragRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    offsetX: number
    offsetY: number
    areaW: number
    areaH: number
    dirX: number
    dirY: number
  } | null>(null)

  const measure = useCallback(() => {
    if (!canvas) return
    const frame = canvas.parentElement
    if (!frame) return
    const c = canvas.getBoundingClientRect()
    const f = frame.getBoundingClientRect()
    setBox({ left: c.left - f.left, top: c.top - f.top, width: c.width, height: c.height })
  }, [canvas])

  useEffect(() => {
    if (!canvas) return
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(canvas)
    if (canvas.parentElement) observer.observe(canvas.parentElement)
    window.addEventListener('resize', measure)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [canvas, measure])

  useEffect(() => {
    measure()
  }, [W, H, photoRect, measure])

  if (tool !== 'watermark' || !wm.enabled || !wm.asset || !photoRect || !box || W <= 1 || H <= 1) {
    return null
  }

  const sx = box.width / W
  const sy = box.height / H
  const area = watermarkArea(wm, photoRect, W, H)
  const rect = watermarkRect(wm, photoRect, W, H, wm.asset.width, wm.asset.height)
  const areaStyle: React.CSSProperties = {
    left: area.x * sx,
    top: area.y * sy,
    width: area.w * sx,
    height: area.h * sy
  }

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (wm.tile) return
    e.preventDefault()
    e.stopPropagation()
    e.currentTarget.setPointerCapture(e.pointerId)
    dragRef.current = {
      pointerId: e.pointerId,
      startX: e.clientX,
      startY: e.clientY,
      offsetX: wm.offsetX,
      offsetY: wm.offsetY,
      areaW: Math.max(1, area.w * sx),
      areaH: Math.max(1, area.h * sy),
      // Right/bottom anchors grow their offset away from that edge.
      dirX: wm.anchor.includes('right') ? -1 : 1,
      dirY: wm.anchor.startsWith('bottom') ? -1 : 1
    }
    setDragging(true)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    const dx = ((e.clientX - drag.startX) / drag.areaW) * 100 * drag.dirX
    const dy = ((e.clientY - drag.startY) / drag.areaH) * 100 * drag.dirY
    setWatermark({
      offsetX: clampOffset(drag.offsetX + dx),
      offsetY: clampOffset(drag.offsetY + dy)
    })
  }

  const endDrag = (e: React.PointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId !== e.pointerId) return
    dragRef.current = null
    setDragging(false)
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? 2 : 0.5
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step]
    }
    const move = moves[e.key]
    if (!move) return
    e.preventDefault()
    setWatermark({
      offsetX: clampOffset(wm.offsetX + move[0]),
      offsetY: clampOffset(wm.offsetY + move[1])
    })
  }

  return (
    <div className="wm-placement" style={box} aria-label="水印拖动定位">
      <div className={`wm-placement-area ${wm.target === 'canvas' ? 'is-canvas' : ''}`} style={areaStyle} />
      {!wm.tile ? (
        <div className="wm-anchor-map" style={areaStyle}>
          {ANCHORS.map((a) => (
            <button
              key={a.value}
              type="button"
              className={`wm-anchor-dot ${wm.anchor === a.value ? 'active' : ''}`}
              style={{ left: `${a.x}%`, top: `${a.y}%` }}
              title={`${a.label}对齐`}
              aria-label={`水印${a.label}对齐`}
              onClick={() => setWatermark({ anchor: a.value })}
            />
          ))}
        </div>
      ) : null}
      {!wm.tile && rect ? (
        <div
          className={`wm-drag-box ${dragging ? 'dragging' : ''}`}
          style={{
            left: rect.x * sx,
            top: rect.y * sy,
            width: rect.w * sx,
            height: rect.h * sy,
            transform: `rotate(${wm.rotation}deg)`
          }}
          role="slider"
          tabIndex={0}
          aria-label="拖动水印位置"
          aria-valuetext={`${ANCHOR_LABEL[wm.anchor]}，水平 ${wm.offsetX}%，垂直 ${wm.offsetY}%`}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          onKeyDown={onKeyDown}
        >
          <span className="wm-drag-grip">
            <Move size={13} />
            拖动
          </span>
        </div>
      ) : null}
      <span className={`wm-readout ${dragging ? 'dragging' : ''}`}>
        {wm.tile ? '平铺模式：位置由平铺规则决定' : `${ANCHOR_LABEL[wm.anchor]} · X ${wm.offsetX}% · Y ${wm.offsetY}%`}
      </span>
    </div>
  )
}
