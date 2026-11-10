package com.framemark.native.product

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A watermark asset carried by the document itself (PRD §8 decision 4: presets
 * embed the PNG, a bigger file is the accepted cost). There is deliberately no
 * path here: an overlay that depends on an external file silently loses the
 * signature after a reinstall or a move.
 */
class WatermarkAsset(
    val name: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val sha256: String,
    val bytes: ByteArray,
) {
    val aspect: Float
        get() = if (heightPx > 0) widthPx.toFloat() / heightPx else 1f

    /** Recomputes the digest; false means the embedded payload was altered. */
    fun isIntact(): Boolean = byteSize > 0 && Sha256.hex(bytes).equals(sha256, ignoreCase = true)

    val byteSize: Int get() = bytes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatermarkAsset) return false
        return name == other.name &&
            mimeType == other.mimeType &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            sha256.equals(other.sha256, ignoreCase = true) &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + sha256.lowercase().hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object {
        /** Hard cap so a hostile preset cannot exhaust memory before it is rejected. */
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_BASE64_CHARS = (MAX_BYTES / 3 + 1) * 4

        fun create(
            name: String,
            mimeType: String,
            widthPx: Int,
            heightPx: Int,
            bytes: ByteArray,
        ): WatermarkAsset {
            require(name.isNotBlank()) { "水印素材缺少文件名" }
            require(mimeType.startsWith("image/")) { "水印素材必须是图片（实际 $mimeType）" }
            require(widthPx > 0 && heightPx > 0) { "水印素材尺寸无效：${widthPx}x$heightPx" }
            require(bytes.isNotEmpty()) { "水印素材为空" }
            require(bytes.size <= MAX_BYTES) { "水印素材超过 ${MAX_BYTES / 1024 / 1024}MB" }
            return WatermarkAsset(name, mimeType, widthPx, heightPx, Sha256.hex(bytes), bytes)
        }
    }
}

/** Normalised rect inside the photo draw box (same space as render-v1 `overlay.rect`). */
data class OverlayRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Position/size/opacity of the embedded watermark. The rect maths are the same
 * 3×3 anchor + nudge model the current editor uses, so preview and export keep
 * matching once this state feeds both.
 */
data class ProjectWatermarkSpec(
    val asset: WatermarkAsset,
    val anchor: Int = 8,
    val width: Float = 0.25f,
    val opacity: Float = 0.8f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** Degrees, clockwise, around the final dst centre; default 0. */
    val rotation: Float = 0f,
    /** Photo draw-box aspect (w/h) used to derive the height; persisted for export. */
    val photoAspect: Float = 1.5f,
) {
    fun rect(photoAspect: Float): OverlayRect {
        val aspect = photoAspectSafe(photoAspect)
        var w = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        var h = (w * aspect / asset.aspect.coerceAtLeast(0.01f)).coerceIn(0.02f, 1f)
        val col = (anchor.coerceIn(0, 8)) % 3
        val row = (anchor.coerceIn(0, 8)) / 3
        val baseX = when (col) {
            0 -> MARGIN
            2 -> 1f - w - MARGIN
            else -> (1f - w) / 2f
        }
        val baseY = when (row) {
            0 -> MARGIN
            2 -> 1f - h - MARGIN
            else -> (1f - h) / 2f
        }
        var x = baseX + dx
        var y = baseY + dy
        val angle = rotation.coerceIn(-MAX_ROTATION, MAX_ROTATION)
        if (abs(angle) < 0.0001f) return clampUnrotated(x, y, w, h)
        // Work in square pixel units (width = 1, height = aspect) so the AABB
        // is a real rotation, then map the height back to normalised y.
        val rad = angle * PI.toFloat() / 180f
        val cx0 = x + w / 2f
        val cy0 = y + h / 2f
        var bw = abs(w * cos(rad)) + abs(h * aspect * sin(rad))
        var bh = (abs(w * sin(rad)) + abs(h * aspect * cos(rad))) / aspect
        val k = min(1f, min(1f / bw.coerceAtLeast(0.0001f), 1f / bh.coerceAtLeast(0.0001f)))
        if (k < 1f) {
            w *= k
            h *= k
            bw *= k
            bh *= k
        }
        val cx = cx0.coerceIn(bw / 2f, (1f - bw / 2f).coerceAtLeast(bw / 2f))
        val cy = cy0.coerceIn(bh / 2f, (1f - bh / 2f).coerceAtLeast(bh / 2f))
        return OverlayRect(cx - w / 2f, cy - h / 2f, w, h)
    }

    /**
     * Bounding box of the rotated image in the same normalised photo space.
     * At 0° it equals [rect]; the UI clips the live overlay to this box so the
     * rotated corners are never cut by the unrotated draw rect.
     */
    fun aabb(photoAspect: Float): OverlayRect {
        val r = rect(photoAspect)
        val angle = rotation.coerceIn(-MAX_ROTATION, MAX_ROTATION)
        if (abs(angle) < 0.0001f) return r
        val aspect = photoAspectSafe(photoAspect)
        val rad = angle * PI.toFloat() / 180f
        val bw = abs(r.w * cos(rad)) + abs(r.h * aspect * sin(rad))
        val bh = (abs(r.w * sin(rad)) + abs(r.h * aspect * cos(rad))) / aspect
        val cx = r.x + r.w / 2f
        val cy = r.y + r.h / 2f
        return OverlayRect(cx - bw / 2f, cy - bh / 2f, bw, bh)
    }

    private fun clampUnrotated(xIn: Float, yIn: Float, wIn: Float, hIn: Float): OverlayRect {
        var x = xIn
        var y = yIn
        var w = wIn
        var h = hIn
        // Never let the box leave the photo draw box: shrink w/h together so the
        // asset keeps its aspect, then clamp the origin.
        if (x + w > 1f || y + h > 1f || x < 0f || y < 0f) {
            val k = minOf(
                (1f - x.coerceIn(0f, 1f)) / w,
                (1f - y.coerceIn(0f, 1f)) / h,
                1f,
            ).coerceIn(0.01f, 1f)
            if (x + w > 1f || y + h > 1f) {
                w *= k
                h *= k
            }
            x = x.coerceIn(0f, (1f - w).coerceAtLeast(0f))
            y = y.coerceIn(0f, (1f - h).coerceAtLeast(0f))
        }
        return OverlayRect(x, y, w, h)
    }

    fun nudged(dxNorm: Float, dyNorm: Float, photoAspect: Float): ProjectWatermarkSpec {
        val box = aabb(photoAspect)
        val baseX = box.x - dx
        val baseY = box.y - dy
        val ndx = (dx + dxNorm).coerceIn(-baseX, (1f - box.w) - baseX)
        val ndy = (dy + dyNorm).coerceIn(-baseY, (1f - box.h) - baseY)
        return copy(dx = ndx, dy = ndy)
    }

    companion object {
        const val MARGIN = 0.05f
        const val MIN_WIDTH = 0.05f
        const val MAX_WIDTH = 0.60f
        const val MAX_ROTATION = 180f

        private fun photoAspectSafe(aspect: Float): Float =
            if (aspect.isFinite() && aspect > 0.01f) aspect else 1.5f
    }
}
