package com.framemark.native.product

import com.framemark.native.workflow.WatermarkSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rotation AABB must contain the rotated draw rect — that is the whole
 * contract the live overlay's clip box relies on. The owner's bug
 * (「为什么水印旋转之后，上下会被裁切」) was this box being too short because
 * the normalised height was converted with `h·aspect` instead of `h/aspect`:
 * at 45° on a 4:3 photo the clip box was 440 px tall while the rotated mark was
 * 564 px, cutting ~62 px off the top and the bottom.
 *
 * These are pure geometry tests: corners are computed from the *pixels* the
 * layer really draws (`w·photoW × h·photoH`) and mapped back to the normalised
 * photo space, independent of the implementation formula.
 */
class WatermarkRotationAabbTest {

    private val angles = listOf(0f, 15f, 30f, 45f, 60f, 90f, -45f, 135f, 180f)
    private val aspects = listOf(1.0f, 4f / 3f, 1.5f, 3f / 2f, 0.75f, 2.4f)

    private fun spec(angle: Float, photoAspect: Float, anchor: Int = 4): ProjectWatermarkSpec =
        ProjectWatermarkSpec(
            asset = WatermarkAsset.create("sig.png", "image/png", 512, 512, ByteArray(16) { it.toByte() }),
            anchor = anchor,
            width = 0.30f,
            opacity = 1f,
            rotation = angle,
            photoAspect = photoAspect,
        )

    /** The four corners of the drawn rect, rotated clockwise around its centre. */
    private fun rotatedCorners(
        rect: OverlayRect,
        angle: Float,
        photoAspect: Float,
    ): List<Pair<Float, Float>> {
        // Pick a photo size; only the aspect matters for the normalised result.
        val photoW = 1000f
        val photoH = photoW / photoAspect
        val widthPx = rect.w * photoW
        val heightPx = rect.h * photoH
        val cx = rect.x + rect.w / 2f
        val cy = rect.y + rect.h / 2f
        val rad = angle * PI.toFloat() / 180f
        return listOf(
            -widthPx / 2f to -heightPx / 2f,
            widthPx / 2f to -heightPx / 2f,
            widthPx / 2f to heightPx / 2f,
            -widthPx / 2f to heightPx / 2f,
        ).map { (dx, dy) ->
            val rx = dx * cos(rad) - dy * sin(rad)
            val ry = dx * sin(rad) + dy * cos(rad)
            (cx + rx / photoW) to (cy + ry / photoH)
        }
    }

    @Test
    fun the_aabb_contains_every_rotated_corner_for_0_15_30_45_60_90() {
        for (angle in angles) {
            for (aspect in aspects) {
                val s = spec(angle, aspect)
                val r = s.rect(aspect)
                val box = s.aabb(aspect)
                val corners = rotatedCorners(r, angle, aspect)
                val label = "angle=$angle aspect=$aspect rect=$r aabb=$box"
                for ((x, y) in corners) {
                    assertTrue(
                        x >= box.x - 1e-4f && x <= box.x + box.w + 1e-4f,
                        "$label corner x=$x outside",
                    )
                    assertTrue(
                        y >= box.y - 1e-4f && y <= box.y + box.h + 1e-4f,
                        "$label corner y=$y outside",
                    )
                }
            }
        }
    }

    @Test
    fun the_aabb_is_the_tight_corner_hull_not_just_a_big_box() {
        for (angle in angles) {
            for (aspect in aspects) {
                val s = spec(angle, aspect)
                val r = s.rect(aspect)
                val box = s.aabb(aspect)
                val corners = rotatedCorners(r, angle, aspect)
                val minX = corners.minOf { it.first }
                val maxX = corners.maxOf { it.first }
                val minY = corners.minOf { it.second }
                val maxY = corners.maxOf { it.second }
                val label = "angle=$angle aspect=$aspect"
                assertNear("$label left", minX, box.x)
                assertNear("$label top", minY, box.y)
                assertNear("$label right", maxX, box.x + box.w)
                assertNear("$label bottom", maxY, box.y + box.h)
            }
        }
    }

    @Test
    fun the_45_degree_box_matches_the_known_4_by_3_numbers() {
        // 0.30 × 0.40 normalised on a 4:3 photo is a 300 × 300 px square: its
        // 45° AABB is 300·√2 ≈ 424 px both ways, i.e. 0.4243 × 0.5657
        // normalised. The old formula returned 0.5893 × 0.4419 (589 × 331 px):
        // 93 px of the mark were clipped off the top and bottom.
        val s = spec(45f, 4f / 3f)
        val r = s.rect(4f / 3f)
        assertEquals(0.35f, r.x, 1e-5f)
        assertEquals(0.30f, r.y, 1e-5f)
        assertEquals(0.30f, r.w, 1e-5f)
        assertEquals(0.40f, r.h, 1e-5f)

        val box = s.aabb(4f / 3f)
        val photoW = 1000f
        val photoH = photoW / (4f / 3f)
        val expectedW = (0.30f * photoW * cos45() + 0.40f * photoH * sin45()) / photoW
        val expectedH = (0.30f * photoW * sin45() + 0.40f * photoH * cos45()) / photoH
        assertNear("width", expectedW, box.w)
        assertNear("height", expectedH, box.h)
        assertTrue(box.h > 0.55f, "rotated height must be ~0.56, was ${box.h}")
        assertNear("centred x", 0.5f, box.x + box.w / 2f)
        assertNear("centred y", 0.5f, box.y + box.h / 2f)
    }

    @Test
    fun preview_and_export_agree_at_the_six_capture_angles() {
        for (angle in listOf(0f, 15f, 30f, 45f, 60f, 90f)) {
            for (aspect in listOf(4f / 3f, 1.5f)) {
                val p = spec(angle, aspect, anchor = 2)
                val u = WatermarkSpec(
                    path = "/tmp/sig.png",
                    aspect = 1f,
                    anchor = 2,
                    width = 0.30f,
                    opacity = 1f,
                    rotation = angle,
                    photoAspect = aspect,
                )
                val pr = p.rect(aspect)
                val pa = p.aabb(aspect)
                val ur = u.rect(aspect)
                val ua = u.aabb(aspect)
                val label = "angle=$angle aspect=$aspect"
                assertEquals(pr.x, ur[0], 1e-5f, "$label rect.x")
                assertEquals(pr.y, ur[1], 1e-5f, "$label rect.y")
                assertEquals(pr.w, ur[2], 1e-5f, "$label rect.w")
                assertEquals(pr.h, ur[3], 1e-5f, "$label rect.h")
                assertEquals(pa.x, ua[0], 1e-5f, "$label aabb.x")
                assertEquals(pa.y, ua[1], 1e-5f, "$label aabb.y")
                assertEquals(pa.w, ua[2], 1e-5f, "$label aabb.w")
                assertEquals(pa.h, ua[3], 1e-5f, "$label aabb.h")
            }
        }
    }

    @Test
    fun the_box_never_leaves_the_photo_for_every_anchor() {
        for (anchor in 0..8) {
            for (angle in angles) {
                for (aspect in aspects) {
                    val box = spec(angle, aspect, anchor).aabb(aspect)
                    val label = "anchor=$anchor angle=$angle aspect=$aspect box=$box"
                    assertTrue(box.x >= -1e-4f, "$label left")
                    assertTrue(box.y >= -1e-4f, "$label top")
                    assertTrue(box.x + box.w <= 1.0001f, "$label right")
                    assertTrue(box.y + box.h <= 1.0001f, "$label bottom")
                }
            }
        }
    }

    private fun cos45() = cos(PI.toFloat() / 4f)

    private fun sin45() = sin(PI.toFloat() / 4f)

    private fun assertNear(label: String, expected: Float, actual: Float, tolerance: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= tolerance, "$label expected=$expected actual=$actual")
    }
}
