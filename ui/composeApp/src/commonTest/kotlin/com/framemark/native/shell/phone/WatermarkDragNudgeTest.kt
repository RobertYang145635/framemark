package com.framemark.native.shell.phone

import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live watermark's drag mapping (2026-11-18).
 *
 * The gesture node used to sit **inside** the `graphicsLayer { rotationZ }`, so
 * `detectDragGestures` handed over deltas in the mark's local (rotated) axes and
 * the pane divided those by the photo size. At 45° a horizontal finger therefore
 * wrote an equal `dy` and the mark slid diagonally — the owner's 「拖动是斜着走
 * 的」. With the node on the unrotated AABB box the delta is already screen-axis,
 * and the mapping is exactly `(dx/photoW, dy/photoH)` at every angle.
 *
 * [layerLocalDelta] is the pre-fix mapping kept here as the counter-example: the
 * last test asserts that feeding it to `withRectNudge` still moves the mark off
 * the finger's axis, so the regression cannot come back unnoticed.
 */
class WatermarkDragNudgeTest {

    private val photoWidth = 1328f
    private val photoHeight = 996f
    private val photoAspect = photoWidth / photoHeight

    /** Square asset: a rotation changes the rect's shape the most. */
    private fun spec(rotation: Float): WatermarkSpec = WatermarkSpec(
        path = "wm.png",
        aspect = 1f,
        anchor = 4,
        width = 0.30f,
        opacity = 1f,
        rotation = rotation,
        photoAspect = photoAspect,
    )

    /**
     * What the old code fed to `withRectNudge`: Compose maps a root-space pointer
     * into a rotated layer with the inverse rotation, so a screen delta of
     * `(dx, dy)` arrived as `R(-θ)·(dx, dy)`.
     */
    private fun layerLocalDelta(dx: Float, dy: Float, degrees: Float): Pair<Float, Float> {
        val rad = degrees * PI.toFloat() / 180f
        return (dx * cos(rad) + dy * sin(rad)) to (-dx * sin(rad) + dy * cos(rad))
    }

    @Test
    fun a_drag_is_divided_by_the_photo_box() {
        val nudge = watermarkDragNudge(100f, -40f, photoWidth, photoHeight)
        assertEquals(100f / photoWidth, nudge.dx, 1e-6f, "dx")
        assertEquals(-40f / photoHeight, nudge.dy, 1e-6f, "dy")
        // The distance mapping: one pixel of finger travel is 1/photo, exactly.
        val one = watermarkDragNudge(1f, 1f, photoWidth, photoHeight)
        assertEquals(1f / photoWidth, one.dx, 1e-9f, "1 px x")
        assertEquals(1f / photoHeight, one.dy, 1e-9f, "1 px y")
    }

    @Test
    fun the_resulting_rect_delta_is_the_same_at_every_rotation() {
        // One finger drag, four rotations: the *rect* moves by the same
        // normalised amount every time, and that amount is exactly
        // (dx/photoW, dy/photoH). This is the assertion the rotated layer failed:
        // for 45/90/135 it produced a different, axis-mixed delta.
        val nudge = watermarkDragNudge(100f, 50f, photoWidth, photoHeight)
        val deltas = listOf(0f, 45f, 90f, 135f).map { angle ->
            val s = spec(angle)
            val before = s.aabb(photoAspect)
            val after = s.withRectNudge(nudge.dx, nudge.dy, photoAspect).aabb(photoAspect)
            angle to ((after[0] - before[0]) to (after[1] - before[1]))
        }
        for ((angle, delta) in deltas) {
            assertEquals(100f / photoWidth, delta.first, 1e-5f, "angle=$angle rect dx")
            assertEquals(50f / photoHeight, delta.second, 1e-5f, "angle=$angle rect dy")
        }
    }

    @Test
    fun a_horizontal_drag_moves_only_x_even_when_the_mark_is_rotated() {
        for (angle in listOf(0f, 45f, 90f, 135f)) {
            val s = spec(angle)
            val before = s.aabb(photoAspect)
            val nudge = watermarkDragNudge(100f, 0f, photoWidth, photoHeight)
            val after = s.withRectNudge(nudge.dx, nudge.dy, photoAspect).aabb(photoAspect)
            assertEquals(0f, after[1] - before[1], 1e-6f, "angle=$angle: a horizontal drag must not move y")
            assertEquals(100f / photoWidth, after[0] - before[0], 1e-5f, "angle=$angle: x must follow the finger")
            if (angle == 0f) continue
            // Counter-example: the delta the rotated node used to report.
            val (localX, localY) = layerLocalDelta(100f, 0f, angle)
            val old = s.withRectNudge(localX / photoWidth, localY / photoHeight, photoAspect).aabb(photoAspect)
            assertTrue(
                abs(old[1] - before[1]) > 10f / photoHeight,
                "angle=$angle: the pre-fix mapping must be visibly diagonal, got dy=${old[1] - before[1]}",
            )
        }
    }

    @Test
    fun a_vertical_drag_moves_only_y_even_when_the_mark_is_rotated() {
        for (angle in listOf(0f, 45f, 90f, 135f)) {
            val s = spec(angle)
            val before = s.aabb(photoAspect)
            val nudge = watermarkDragNudge(0f, 100f, photoWidth, photoHeight)
            val after = s.withRectNudge(nudge.dx, nudge.dy, photoAspect).aabb(photoAspect)
            assertEquals(0f, after[0] - before[0], 1e-6f, "angle=$angle: a vertical drag must not move x")
            assertEquals(100f / photoHeight, after[1] - before[1], 1e-5f, "angle=$angle: y must follow the finger")
        }
    }

    @Test
    fun a_zero_sized_photo_never_emits_infinities() {
        assertEquals(0f, watermarkDragNudge(10f, 10f, 0f, 0f).dx)
        assertEquals(0f, watermarkDragNudge(10f, 10f, Float.NaN, 100f).dy)
    }
}
