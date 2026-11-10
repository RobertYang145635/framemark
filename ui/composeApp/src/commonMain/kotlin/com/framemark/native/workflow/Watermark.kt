package com.framemark.native.workflow

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import com.framemark.native.product.FmLog

/**
 * A user watermark attached to one tray photo. [anchor] is a 3x3 slot
 * (0 = top-left … 8 = bottom-right); [dx]/[dy] are normalised nudges from the
 * drag handle. [width] is the overlay width as a fraction of the photo draw box.
 */
data class WatermarkSpec(
    val path: String,
    val aspect: Float,
    val anchor: Int = 8,
    val width: Float = 0.25f,
    val opacity: Float = 0.8f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** Photo draw-box aspect learned from the engine (w/h); 1.5 until known. */
    val photoAspect: Float = 1.5f,
    /** Degrees, clockwise, around the final dst centre; default 0. */
    val rotation: Float = 0f,
) {
    /**
     * Normalised draw rect (x, y, w, h) inside the photo draw box. The height
     * follows the asset aspect and the photo aspect so the engine's "contain"
     * step has nothing to letterbox. With rotation the rect is shrunk around
     * its centre until its rotated AABB stays inside the photo.
     */
    fun rect(photoAspect: Float): FloatArray {
        val aspect = photoAspectSafe(photoAspect)
        var w = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        var h = (w * aspect / this.aspect.coerceAtLeast(0.01f)).coerceIn(0.02f, 1f)
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
        return floatArrayOf(cx - w / 2f, cy - h / 2f, w, h)
    }

    /** Bounding box of the rotated image; equals [rect] at 0°. */
    fun aabb(photoAspect: Float): FloatArray {
        val r = rect(photoAspect)
        val angle = rotation.coerceIn(-MAX_ROTATION, MAX_ROTATION)
        if (abs(angle) < 0.0001f) return r
        val aspect = photoAspectSafe(photoAspect)
        val rad = angle * PI.toFloat() / 180f
        val bw = abs(r[2] * cos(rad)) + abs(r[3] * aspect * sin(rad))
        val bh = (abs(r[2] * sin(rad)) + abs(r[3] * aspect * cos(rad))) / aspect
        val cx = r[0] + r[2] / 2f
        val cy = r[1] + r[3] / 2f
        return floatArrayOf(cx - bw / 2f, cy - bh / 2f, bw, bh)
    }

    private fun clampUnrotated(xIn: Float, yIn: Float, wIn: Float, hIn: Float): FloatArray {
        var x = xIn
        var y = yIn
        var w = wIn
        var h = hIn
        if (x + w > 1f || y + h > 1f || x < 0f || y < 0f) {
            val k = minOf(
                ((1f - x.coerceIn(0f, 1f)) / w),
                ((1f - y.coerceIn(0f, 1f)) / h),
                1f,
            ).coerceIn(0.01f, 1f)
            if (x + w > 1f || y + h > 1f) { w *= k; h *= k }
            x = x.coerceIn(0f, (1f - w).coerceAtLeast(0f))
            y = y.coerceIn(0f, (1f - h).coerceAtLeast(0f))
        }
        return floatArrayOf(x, y, w, h)
    }

    fun toOverlay(photoAspect: Float): OverlaySpec {
        val r = rect(photoAspect)
        return OverlaySpec(
            path = path,
            x = r[0],
            y = r[1],
            w = r[2],
            h = r[3],
            opacity = opacity,
            angle = rotation.coerceIn(-MAX_ROTATION, MAX_ROTATION),
        )
    }

    fun withRectNudge(dxNorm: Float, dyNorm: Float, photoAspect: Float): WatermarkSpec {
        val box = aabb(photoAspect)
        val baseX = box[0] - dx
        val baseY = box[1] - dy
        val ndx = (dx + dxNorm).coerceIn(-baseX, (1f - box[2]) - baseX)
        val ndy = (dy + dyNorm).coerceIn(-baseY, (1f - box[3]) - baseY)
        return copy(dx = ndx, dy = ndy)
    }

    companion object {
        const val MARGIN = 0.05f
        const val MIN_WIDTH = 0.05f
        const val MAX_WIDTH = 0.60f
        const val MIN_OPACITY = 0.10f
        const val MAX_OPACITY = 1f
        const val MAX_ROTATION = 180f

        private fun photoAspectSafe(a: Float): Float = if (a.isFinite() && a > 0.01f) a else 1.5f
    }
}

/** Result of the system document picker after the platform normalised it to PNG. */
object WatermarkSelection {
    private val versionState = androidx.compose.runtime.mutableIntStateOf(0)
    val version: Int get() = versionState.intValue
    var path: String? = null
        private set
    var aspect: Float = 1f
        private set
    var anchor: Int = 8
        private set
    var width: Float = 0.25f
        private set
    var opacity: Float = 0.8f
        private set

    fun set(
        pngPath: String,
        assetAspect: Float,
        slot: Int = 8,
        widthFraction: Float = 0.25f,
        alpha: Float = 0.8f,
    ) {
        path = pngPath
        aspect = assetAspect
        anchor = slot
        width = widthFraction
        opacity = alpha
        versionState.intValue++
        FmLog.info("FM_WATERMARK_PICK") { "path=$pngPath aspect=$assetAspect" }
    }

    fun clear() {
        versionState.intValue++
        FmLog.info("FM_WATERMARK_PICK") { "cleared" }
    }
}

/**
 * Photo draw-box aspect (w/h) reported by the engine for a card, from the last
 * render (preview or export). Export builds its overlay from this so the rect
 * height matches what the user saw on the stage.
 */
object PreviewGeometry {
    private val aspects = HashMap<String, Float>()

    fun record(cardId: String, aspect: Float) {
        if (cardId.isBlank() || !aspect.isFinite() || aspect <= 0.01f) return
        aspects[cardId] = aspect
    }

    fun aspectOf(cardId: String?): Float? = cardId?.let { aspects[it] }
}
