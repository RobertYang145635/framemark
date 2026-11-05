package com.framemark.native.product

import com.framemark.native.workflow.WatermarkSpec as UiWatermarkSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live Compose overlay and the engine export have separate data classes, but
 * they must compute the identical photo-relative draw rect and AABB. A drift here
 * is exactly the "preview rotated, export upright" class of bug.
 */
class WatermarkGeometryParityTest {
    private fun product(angle: Float, photoAspect: Float): WatermarkSpec {
        val asset = WatermarkAsset.create("sig.png", "image/png", 800, 200, ByteArray(16) { it.toByte() })
        return WatermarkSpec(
            asset = asset,
            anchor = 8,
            width = 0.3f,
            opacity = 0.7f,
            rotation = angle,
            photoAspect = photoAspect,
        )
    }

    private fun ui(angle: Float, photoAspect: Float): UiWatermarkSpec = UiWatermarkSpec(
        path = "/tmp/sig.png",
        aspect = 4f,
        anchor = 8,
        width = 0.3f,
        opacity = 0.7f,
        photoAspect = photoAspect,
        rotation = angle,
    )

    @Test
    fun preview_and_export_geometry_match_for_0_45_90() {
        for (angle in listOf(0f, 45f, 90f, -45f, 180f)) {
            for (photoAspect in listOf(1.5f, 0.75f, 1.583f)) {
                val p = product(angle, photoAspect)
                val u = ui(angle, photoAspect)
                val pr = p.rect(photoAspect)
                val pa = p.aabb(photoAspect)
                val ur = u.rect(photoAspect)
                val ua = u.aabb(photoAspect)
                val label = "angle=$angle aspect=$photoAspect"
                assertNear(label + " rect", pr.x, ur[0])
                assertNear(label + " rect", pr.y, ur[1])
                assertNear(label + " rect", pr.w, ur[2])
                assertNear(label + " rect", pr.h, ur[3])
                assertNear(label + " aabb", pa.x, ua[0])
                assertNear(label + " aabb", pa.y, ua[1])
                assertNear(label + " aabb", pa.w, ua[2])
                assertNear(label + " aabb", pa.h, ua[3])
            }
        }
    }

    @Test
    fun the_rotated_aabb_stays_inside_the_photo_for_every_angle() {
        for (angle in listOf(-180f, -90f, -45f, 0f, 45f, 90f, 180f)) {
            val p = product(angle, 1.5f)
            val box = p.aabb(1.5f)
            assertTrue(box.x >= -1e-4f && box.y >= -1e-4f, "$angle origin = ${box.x},${box.y}")
            assertTrue(box.x + box.w <= 1.0001f, "$angle right = ${box.x + box.w}")
            assertTrue(box.y + box.h <= 1.0001f, "$angle bottom = ${box.y + box.h}")
        }
    }

    private fun assertNear(label: String, expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= 1e-4f, "$label expected=$expected actual=$actual")
    }
}
