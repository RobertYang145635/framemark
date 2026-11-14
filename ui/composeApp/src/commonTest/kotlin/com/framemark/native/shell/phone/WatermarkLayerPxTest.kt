package com.framemark.native.shell.phone

import com.framemark.native.product.ProjectWatermarkSpec
import com.framemark.native.product.WatermarkAsset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live overlay sizes its clip box to the rotation AABB and draws one
 * rotated image box inside it. This pins the pixel-level contract the owner's
 * bug broke:
 *
 *  - the clip box must cover the **rotated** image at every angle (never crop
 *    it), and
 *  - the image box must keep its intended size instead of being squashed to the
 *    clip box (a plain `size()` is coerced by the parent's constraints, which
 *    shrank the mark to the 224 px tall 90° clip box).
 */
class WatermarkLayerPxTest {
    private val photoLeft = 56f
    private val photoTop = 519f
    private val photoWidth = 1328f
    private val photoHeight = 996f
    private val photoAspect = photoWidth / photoHeight

    private fun spec(angle: Float): ProjectWatermarkSpec = ProjectWatermarkSpec(
        asset = WatermarkAsset.create("sig.png", "image/png", 512, 512, ByteArray(16) { it.toByte() }),
        anchor = 4,
        width = 0.30f,
        opacity = 1f,
        rotation = angle,
        photoAspect = photoAspect,
    )

    /** Corner points of the drawn image box after its layer rotation, in stage px. */
    private fun rotatedImageCorners(layer: WatermarkLayerPx, angle: Float): List<Pair<Float, Float>> {
        val rad = angle * PI.toFloat() / 180f
        val hw = layer.imageWidth / 2f
        val hh = layer.imageHeight / 2f
        val cx = layer.imageLeft + hw
        val cy = layer.imageTop + hh
        return listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (dx, dy) ->
            (cx + dx * cos(rad) - dy * sin(rad)) to (cy + dx * sin(rad) + dy * cos(rad))
        }
    }

    @Test
    fun the_clip_box_never_crops_the_rotated_image() {
        for (angle in listOf(0f, 15f, 30f, 45f, 60f, 90f, -45f, 135f, 180f)) {
            val s = spec(angle)
            val rect = s.rect(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
            val aabb = s.aabb(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
            val layer = watermarkLayerPx(rect, aabb, photoLeft, photoTop, photoWidth, photoHeight, angle)
            val right = layer.clipLeft + layer.clipWidth
            val bottom = layer.clipTop + layer.clipHeight
            for ((x, y) in rotatedImageCorners(layer, angle)) {
                assertTrue(
                    x >= layer.clipLeft - 1e-3f && x <= right + 1e-3f,
                    "angle=$angle corner x=$x outside [${layer.clipLeft}, $right]",
                )
                assertTrue(
                    y >= layer.clipTop - 1e-3f && y <= bottom + 1e-3f,
                    "angle=$angle corner y=$y outside [${layer.clipTop}, $bottom]",
                )
            }
        }
    }

    @Test
    fun the_clip_box_is_the_aabb_and_the_image_keeps_its_size() {
        for (angle in listOf(0f, 15f, 30f, 45f, 60f, 90f)) {
            val s = spec(angle)
            val rect = s.rect(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
            val aabb = s.aabb(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
            val layer = watermarkLayerPx(rect, aabb, photoLeft, photoTop, photoWidth, photoHeight, angle)
            // The image box is the draw rect, not the clip box: a rotation must
            // never resize the PNG.
            assertTrue(
                abs(layer.imageWidth - rect[2] * photoWidth) <= 1f,
                "angle=$angle imageWidth=${layer.imageWidth} rect=${rect[2] * photoWidth}",
            )
            assertTrue(
                abs(layer.imageHeight - rect[3] * photoHeight) <= 1f,
                "angle=$angle imageHeight=${layer.imageHeight} rect=${rect[3] * photoHeight}",
            )
            // The clip box is the AABB (rounded outward by < 1 px on each side).
            val aabbLeft = photoLeft + aabb[0] * photoWidth
            val aabbTop = photoTop + aabb[1] * photoHeight
            val aabbRight = photoLeft + (aabb[0] + aabb[2]) * photoWidth
            val aabbBottom = photoTop + (aabb[1] + aabb[3]) * photoHeight
            val right = layer.clipLeft + layer.clipWidth
            val bottom = layer.clipTop + layer.clipHeight
            assertTrue(layer.clipLeft <= aabbLeft + 0.001f, "angle=$angle clip left")
            assertTrue(layer.clipTop <= aabbTop + 0.001f, "angle=$angle clip top")
            assertTrue(right >= aabbRight - 0.001f, "angle=$angle clip right")
            assertTrue(bottom >= aabbBottom - 0.001f, "angle=$angle clip bottom")
        }
    }

    @Test
    fun at_90_degrees_the_box_is_the_rotated_mark_not_the_unrotated_rect() {
        // The regression at 90° was a 900 px wide, 224 px tall clip box: the
        // rotated square is 596 px tall and got squashed to 224 px, then cut.
        val s = spec(90f)
        val rect = s.rect(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
        val aabb = s.aabb(photoAspect).let { floatArrayOf(it.x, it.y, it.w, it.h) }
        val layer = watermarkLayerPx(rect, aabb, photoLeft, photoTop, photoWidth, photoHeight, 90f)
        val rotatedHeight = layer.imageWidth.toFloat()
        assertTrue(
            layer.clipHeight >= rotatedHeight - 1f,
            "clipHeight=${layer.clipHeight} must cover the rotated ${rotatedHeight}px mark",
        )
        assertTrue(layer.clipHeight < layer.imageHeight * 2, "clip box must stay the mark's own size")
    }
}
