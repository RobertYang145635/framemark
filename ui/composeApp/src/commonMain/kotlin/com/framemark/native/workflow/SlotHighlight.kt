package com.framemark.native.workflow

import kotlin.math.min

/**
 * A rectangle in preview-box pixels. It is draw-only: nothing here is written
 * into the request key, the project, a preset, the render cache or an export.
 */
data class FitRectPx(val x: Float, val y: Float, val w: Float, val h: Float)

private val EMPTY_RECT = FitRectPx(0f, 0f, 0f, 0f)

/**
 * The destination rectangle of a `ContentScale.Fit` image inside a box of
 * `boxW × boxH` pixels: the bitmap is scaled by `min(boxW / bitmapW,
 * boxH / bitmapH)` and centred, so the two bands on the shorter axis stay empty.
 *
 * Degenerate input (either bitmap edge `<= 0`, either box edge non-finite or
 * `<= 0`) returns an all-zero rectangle instead of throwing: callers treat that
 * as "no frame to draw on".
 */
fun fitImageRect(bitmapW: Int, bitmapH: Int, boxW: Float, boxH: Float): FitRectPx {
    if (bitmapW <= 0 || bitmapH <= 0) return EMPTY_RECT
    if (!boxW.isFinite() || !boxH.isFinite() || boxW <= 0f || boxH <= 0f) return EMPTY_RECT
    val scale = min(boxW / bitmapW.toFloat(), boxH / bitmapH.toFloat())
    if (!scale.isFinite() || scale <= 0f) return EMPTY_RECT
    val w = bitmapW * scale
    val h = bitmapH * scale
    if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return EMPTY_RECT
    return FitRectPx(x = (boxW - w) / 2f, y = (boxH - h) / 2f, w = w, h = h)
}

/**
 * Maps a renderer text-slot rectangle (bitmap pixels) onto the stage preview box
 * of `boxW × boxH` pixels, following the same `ContentScale.Fit` geometry as
 * [fitImageRect].
 *
 * Returns `null` — never a guessed box — when the slot has no trustworthy
 * positive rectangle ([TextSlot.located], finite, `w > 0`, `h > 0`) or when the
 * bitmap/box size cannot be mapped. The caller shows its "待定位" hint instead of
 * drawing a frame the text may not be in.
 */
fun slotHighlightRect(
    slot: TextSlot,
    bitmapW: Int,
    bitmapH: Int,
    boxW: Float,
    boxH: Float,
): FitRectPx? {
    if (!slot.located) return null
    if (!slot.x.isFinite() || !slot.y.isFinite() || !slot.w.isFinite() || !slot.h.isFinite()) return null
    // 空内容的行（这一帧没画东西）量出来是 0 宽：按字高给一个最小框，用户才点得到、
    // 也才看得见高亮（2026-11-21 用户反馈「点这个框为什么没有高亮框」）。
    if (slot.w <= 0.0 || slot.h <= 0.0) {
        val line = (slot.fontSize ?: 0.0).takeIf { it > 0.0 } ?: 24.0
        val fit0 = fitImageRect(bitmapW, bitmapH, boxW, boxH)
        if (fit0.w <= 0f || fit0.h <= 0f) return null
        val scale0 = fit0.w / bitmapW.toFloat()
        if (!scale0.isFinite() || scale0 <= 0f) return null
        val minW = (line * 6.0).toFloat() * scale0
        val minH = (line * 1.4).toFloat() * scale0
        return FitRectPx(
            x = fit0.x + slot.x.toFloat() * scale0,
            y = fit0.y + slot.y.toFloat() * scale0,
            w = minW,
            h = minH,
        )
    }
    val fit = fitImageRect(bitmapW, bitmapH, boxW, boxH)
    if (fit.w <= 0f || fit.h <= 0f) return null
    val scale = fit.w / bitmapW.toFloat()
    if (!scale.isFinite() || scale <= 0f) return null
    val x = slot.x.toFloat()
    val y = slot.y.toFloat()
    val w = slot.w.toFloat()
    val h = slot.h.toFloat()
    if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return null
    val mapped = FitRectPx(
        x = fit.x + x * scale,
        y = fit.y + y * scale,
        w = w * scale,
        h = h * scale,
    )
    if (!mapped.x.isFinite() || !mapped.y.isFinite() || !mapped.w.isFinite() || !mapped.h.isFinite()) return null
    if (mapped.w <= 0f || mapped.h <= 0f) return null
    return mapped
}
