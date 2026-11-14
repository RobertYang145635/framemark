package com.framemark.native.workflow

import com.framemark.native.product.FmLog
import com.framemark.native.product.OverlayRect
import com.framemark.native.product.WatermarkGeometry

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
     *
     * The maths live in [WatermarkGeometry] (product), shared with the export
     * spec, so the live overlay and the engine can never disagree.
     */
    fun rect(photoAspect: Float): FloatArray = WatermarkGeometry
        .rect(anchor, width, dx, dy, rotation, aspect, photoAspect)
        .toFloatArray()

    /** Bounding box of the rotated image; equals [rect] at 0°. */
    fun aabb(photoAspect: Float): FloatArray {
        val r = rect(photoAspect)
        return WatermarkGeometry.aabb(r[0], r[1], r[2], r[3], rotation, photoAspect).toFloatArray()
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
        const val MARGIN = WatermarkGeometry.MARGIN
        const val MIN_WIDTH = WatermarkGeometry.MIN_WIDTH
        const val MAX_WIDTH = WatermarkGeometry.MAX_WIDTH
        const val MIN_OPACITY = 0.10f
        const val MAX_OPACITY = 1f
        const val MAX_ROTATION = WatermarkGeometry.MAX_ROTATION
    }
}

private fun OverlayRect.toFloatArray(): FloatArray = floatArrayOf(x, y, w, h)

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
