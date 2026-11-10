package com.framemark.native.shell.phone

/**
 * The failed-preview placeholder (owner report: the static sample read as
 * "someone else's photo", not as a fallback).
 *
 * When the live render fails the stage falls back to the bundled static sample — a
 * mountain card with its own white frame. That image is legitimate, but on its own
 * it looks like the photo the user is editing. The placeholder is draw-only: a 12 %
 * stage wash over the sample plus one centred chip. Nothing here decides *when* the
 * fallback runs, and nothing is written into the render pipeline, the project, a
 * preset or an export.
 *
 * The predicate is pure so the "which stage states show the chip" rule is tested
 * without a composition.
 */
object PhoneStageFallback {
    /** Roughly 12 % of `FmPalette.stage` over the sample: dimmer, but still legible. */
    const val DimAlpha: Float = 0.12f

    /** The chip's fill, the same weight as the other stage pills (`~88 %`). */
    const val ChipAlpha: Float = 0.88f

    /** One centred line that says what the image is, not whose photo it is. */
    const val ChipText: String = "示例图 · 当前照片预览暂不可用"

    /**
     * Whether the stage is showing the fallback and the placeholder should be drawn.
     *
     * This is deliberately the **same trigger** as the existing bottom-left hint
     * (`预览失败 · 显示静态图`): a non-blank [renderFailure]. `NativeStagePreview` fires
     * that callback from its failure path, which drops the rendered frame back to the
     * bundled `LibraryPreview` sample, so the chip describes what is on screen.
     *
     * [compare] (按住看原图) suppresses it: that shows the raw photo, which must never
     * be dimmed or called a sample.
     */
    fun showsPlaceholder(renderFailure: String?, compare: Boolean): Boolean =
        !compare && !renderFailure.isNullOrBlank()
}
