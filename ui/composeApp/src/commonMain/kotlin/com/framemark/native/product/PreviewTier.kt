package com.framemark.native.product

import kotlin.math.roundToInt

/**
 * The two preview tiers the stage renders, and the pure request parameters that
 * describe each of them.
 *
 * Dragging is rendered at a bounded edge so the picture can keep up with the
 * pointer; the frame after release is rendered at the final edge and is the one
 * the user judges. Both tiers describe the **same design** (see
 * [PreviewRequestKey.sameStageInputs]); only `edge` — and, for the bounded tier,
 * the source-photo decode size — differs.
 */
internal enum class PreviewQuality(val wire: String) {
    Interactive("interactive"),
    Final("final"),
}

/** One tier's request parameters; a pure value so it can be tested off-device. */
internal data class PreviewTierRequest(
    val quality: PreviewQuality,
    /** Canvas long edge requested from the engine. */
    val edge: Int,
    /**
     * Long edge the engine may decode the source photo at. `0` means full
     * resolution — the final frame must stay byte-identical to the pre-tier
     * render, so it never asks for a reduced decode.
     */
    val sourceEdge: Int,
)

internal object PreviewTier {
    /** Final preview canvas cap (the old PREVIEW_MAX_EDGE). */
    const val MAX_EDGE = 1280

    /** Final preview canvas floor (the old PREVIEW_MIN_EDGE). */
    const val MIN_EDGE = 640

    /** Bounded canvas long edge used while the pointer is down. */
    const val INTERACTIVE_MAX_EDGE = 480

    /**
     * How much larger than the interactive canvas the decoded source may be.
     * 2x keeps the downscaled draw sharp at the device scale while staying
     * inside libjpeg-turbo's cheap 1/8 decode for a 50 MP photo.
     */
    const val SOURCE_SUPERSAMPLE = 2

    /** Lower/upper bound for the interactive source decode request. */
    const val SOURCE_MIN_EDGE = 640
    const val SOURCE_MAX_EDGE = 4096

    /** Canvas edge of the final frame for a stage of [displayEdge] pixels. */
    fun finalEdge(displayEdge: Int, supersample: Float): Int =
        (displayEdge.coerceAtLeast(1) * supersample)
            .roundToInt()
            .coerceIn(MIN_EDGE, MAX_EDGE)

    /** Canvas edge of the interactive frame: bounded, never larger than final. */
    fun interactiveEdge(finalEdge: Int): Int =
        finalEdge.coerceAtLeast(1).coerceAtMost(INTERACTIVE_MAX_EDGE)

    /**
     * Source-photo decode edge for an interactive canvas. Independent of the
     * photo size: the engine picks its own supported downscale at or below it.
     */
    fun sourceEdge(interactiveEdge: Int): Int =
        (interactiveEdge.coerceAtLeast(1) * SOURCE_SUPERSAMPLE)
            .coerceIn(SOURCE_MIN_EDGE, SOURCE_MAX_EDGE)

    /**
     * Request parameters for one frame. [interactive] is true while the stage is
     * still tracking new input (drags, slider commits) and false for the frame
     * submitted after the field has been idle for the final debounce.
     */
    fun request(finalEdge: Int, interactive: Boolean): PreviewTierRequest =
        if (interactive) {
            val edge = interactiveEdge(finalEdge)
            PreviewTierRequest(PreviewQuality.Interactive, edge, sourceEdge(edge))
        } else {
            PreviewTierRequest(PreviewQuality.Final, finalEdge, 0)
        }
}
