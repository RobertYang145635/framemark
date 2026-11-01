package com.framemark.native.workflow

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
) {
    /**
     * Normalised rect (x, y, w, h) inside the photo draw box. The height follows
     * the asset aspect and the photo aspect so the engine's "contain" step has
     * nothing to letterbox.
     */
    fun rect(photoAspect: Float): FloatArray {
        var w = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        var h = (w * photoAspectSafe(photoAspect) / aspect.coerceAtLeast(0.01f)).coerceIn(0.02f, 1f)
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
        // Never let the box leave the photo draw box: shrink w/h together so the
        // asset keeps its aspect, then clamp the origin.
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
        return OverlaySpec(path = path, x = r[0], y = r[1], w = r[2], h = r[3], opacity = opacity)
    }

    fun withRectNudge(dxNorm: Float, dyNorm: Float, photoAspect: Float): WatermarkSpec {
        val r = rect(photoAspect)
        val baseX = r[0] - dx
        val baseY = r[1] - dy
        val ndx = (dx + dxNorm).coerceIn(-baseX, (1f - r[2]) - baseX)
        val ndy = (dy + dyNorm).coerceIn(-baseY, (1f - r[3]) - baseY)
        return copy(dx = ndx, dy = ndy)
    }

    companion object {
        const val MARGIN = 0.05f
        const val MIN_WIDTH = 0.05f
        const val MAX_WIDTH = 0.60f
        const val MIN_OPACITY = 0.10f
        const val MAX_OPACITY = 1f

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
        println("FM_WATERMARK_PICK path=$pngPath aspect=$assetAspect")
    }

    fun clear() {
        versionState.intValue++
        println("FM_WATERMARK_PICK cleared")
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
