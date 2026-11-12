package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The user's crop, and the two things it must never do: disagree with the engine
 * when it is untouched, and leave the photo.
 *
 * The engine's own rectangle ([UltraHdrExport.sourceRect]) is the reference: at the
 * defaults `CropGeometry.region` must return **exactly** those doubles, because the
 * whole Ultra HDR alignment contract is defined against it.
 */
class PhotoCropTest {

    @Test
    fun the_default_crop_is_exactly_the_engine_centred_rect() {
        val cases = listOf(
            // (source w, h, box w, h)
            Pair(Pair(6000, 4000), Pair(6000.0, 4000.0)),
            Pair(Pair(6000, 4000), Pair(600.0, 400.0)),
            Pair(Pair(4000, 3000), Pair(1000.0, 1000.0)),
            Pair(Pair(4000, 3000), Pair(2000.0, 1000.0)),
            Pair(Pair(4001, 3001), Pair(1001.0, 999.0)),
            Pair(Pair(640, 480), Pair(1.0, 1.0)),
            Pair(Pair(1, 1), Pair(1.0, 1.0)),
            Pair(Pair(3, 3), Pair(4.0, 2.0)),
        )
        for ((source, box) in cases) {
            val (sourceWidth, sourceHeight) = source
            val (boxWidth, boxHeight) = box
            val label = "$sourceWidth x $sourceHeight into $boxWidth x $boxHeight"
            val engine = assertNotNull(
                UltraHdrExport.sourceRect(sourceWidth, sourceHeight, boxWidth, boxHeight),
                label,
            )
            assertContentEquals(
                engine,
                CropGeometry.region(sourceWidth, sourceHeight, boxWidth, boxHeight, PhotoCrop.Default),
                "the default crop must be the engine's own rect: $label",
            )
            assertContentEquals(
                engine,
                CropGeometry.region(sourceWidth, sourceHeight, boxWidth, boxHeight, PhotoCrop(0.5, 0.5, 1.0)),
                "an explicit centre/zoom of (0.5, 0.5, 1) is the default: $label",
            )
        }
    }

    @Test
    fun panning_to_a_corner_clamps_inside_the_photo_instead_of_leaving_it() {
        // 4000x3000 (4:3) into a 1:1 box keeps a centred 3000x3000 square.
        val left = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(-5.0, 0.5, 1.0)),
        )
        assertContentEquals(doubleArrayOf(0.0, 0.0, 3000.0, 3000.0), left)

        val right = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(7.0, 0.5, 1.0)),
        )
        assertEquals(1000.0, right[0], "the last whole region inside 4000 px starts at 1000")
        assertEquals(4000.0, right[0] + right[2])

        val top = assertNotNull(
            CropGeometry.region(4000, 3000, 4000.0, 2000.0, PhotoCrop(0.5, -9.0, 1.0)),
        )
        assertContentEquals(doubleArrayOf(0.0, 0.0, 4000.0, 2000.0), top)

        val bottom = assertNotNull(
            CropGeometry.region(4000, 3000, 4000.0, 2000.0, PhotoCrop(0.5, 9.0, 1.0)),
        )
        assertEquals(1000.0, bottom[1])
        assertEquals(3000.0, bottom[1] + bottom[3])

        // Whatever the pan and zoom, the rectangle stays inside and is never empty.
        for (x in listOf(-90.0, 0.0, 0.25, 0.5, 0.75, 1.0, 90.0, Double.NaN)) {
            for (zoom in listOf(1.0, 1.7, 3.0, 8.0, 1e9)) {
                val rect = assertNotNull(
                    CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(x, x, zoom)),
                    "x=$x zoom=$zoom",
                )
                assertTrue(rect[2] >= 1.0 && rect[3] >= 1.0, "never empty: ${rect.toList()}")
                assertTrue(rect[0] >= 0.0 && rect[1] >= 0.0, "never starts outside: ${rect.toList()}")
                assertTrue(
                    rect[0] + rect[2] <= 4000.0 && rect[1] + rect[3] <= 3000.0,
                    "never leaves the photo: ${rect.toList()}",
                )
            }
        }
    }

    @Test
    fun zoom_above_one_shrinks_the_region_around_the_centre_it_was_given() {
        val base = assertNotNull(CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop.Default))
        assertContentEquals(doubleArrayOf(500.0, 0.0, 3000.0, 3000.0), base)

        val zoomed = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.5, 0.5, 4.0)),
        )
        assertContentEquals(doubleArrayOf(1625.0, 1125.0, 750.0, 750.0), zoomed)
        assertTrue(zoomed[2] < base[2] && zoomed[3] < base[3], "a deeper zoom keeps less")
        // The box aspect survives (`cover` semantics), so the engine has nothing to re-crop.
        assertEquals(1.0, zoomed[2] / zoomed[3], 1e-9)

        // Moving the centre moves the region by exactly that fraction of the free range
        // and keeps the zoomed extent.
        val quarter = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.25, 0.5, 2.0)),
        )
        val threeQuarters = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.75, 0.5, 2.0)),
        )
        assertEquals(1500.0, quarter[2])
        assertEquals(quarter[2], threeQuarters[2])
        // The region's origin is its centre minus half its extent, clamped into the
        // photo: quarter of 4000 px is 1000, minus 750 of half-extent.
        assertEquals(250.0, quarter[0])
        assertEquals(2250.0, threeQuarters[0])
    }

    @Test
    fun degenerate_input_is_refused_or_clamped_but_never_yields_an_empty_rect() {
        // A one-pixel photo cannot be cropped at all.
        assertContentEquals(
            doubleArrayOf(0.0, 0.0, 1.0, 1.0),
            assertNotNull(CropGeometry.region(1, 1, 1.0, 1.0, PhotoCrop(0.8, 0.2, 4.0))),
        )
        // zoom below 1 is clamped up to 1 — the engine never letterboxes.
        assertContentEquals(
            UltraHdrExport.sourceRect(4000, 3000, 1000.0, 1000.0),
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.5, 0.5, 0.25)),
        )
        // Non-finite values fall back to the default instead of producing NaN pixels.
        assertContentEquals(
            UltraHdrExport.sourceRect(4000, 3000, 1000.0, 1000.0),
            CropGeometry.region(
                4000, 3000, 1000.0, 1000.0,
                PhotoCrop(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN),
            ),
        )
        // An absurd zoom is clamped to MAX_ZOOM and stays at least one pixel.
        val huge = assertNotNull(
            CropGeometry.region(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.5, 0.5, 1e12)),
        )
        assertContentEquals(doubleArrayOf(1813.0, 1313.0, 375.0, 375.0), huge)
        // Geometry that cannot describe a rectangle is refused, not guessed.
        assertNull(CropGeometry.region(0, 3000, 1000.0, 1000.0, PhotoCrop.Default))
        assertNull(CropGeometry.region(4000, 0, 1000.0, 1000.0, PhotoCrop.Default))
        assertNull(CropGeometry.region(4000, 3000, Double.NaN, 1000.0, PhotoCrop.Default))
        assertNull(CropGeometry.region(4000, 3000, 1000.0, -1.0, PhotoCrop.Default))
        assertNull(CropGeometry.region(4000, 3000, 1000.0, Double.POSITIVE_INFINITY, PhotoCrop.Default))
    }

    @Test
    fun the_crop_is_only_offered_when_the_box_really_cuts_the_photo() {
        assertFalse(CropGeometry.cropsSource(6000, 4000, 6000.0, 4000.0), "equal aspect keeps everything")
        assertFalse(CropGeometry.cropsSource(6000, 4000, 600.0, 400.0), "only the ratio matters")
        assertFalse(
            CropGeometry.cropsSource(6000, 4000, 6001.0, 4000.0),
            "a sub-pixel difference is one rounding step, not a crop",
        )
        assertTrue(CropGeometry.cropsSource(6000, 4000, 4000.0, 4000.0))
        assertTrue(CropGeometry.cropsSource(6000, 4000, 4000.0, 6000.0))
        assertFalse(CropGeometry.cropsSource(0, 4000, 4000.0, 4000.0))
        assertFalse(CropGeometry.cropsSource(6000, 4000, 0.0, 4000.0))
    }

    @Test
    fun the_centre_is_limited_to_the_room_the_region_leaves() {
        // A 1:1 region inside a 4:3 photo fills the height, so only x can move.
        val limits = assertNotNull(CropGeometry.centreLimits(4000, 3000, 1000.0, 1000.0, 1.0))
        assertEquals(0.375, limits[0], 1e-9)
        assertEquals(0.625, limits[1], 1e-9)
        assertEquals(0.5, limits[2], 1e-9)
        assertEquals(0.5, limits[3], 1e-9)

        val clamped = CropGeometry.clamped(4000, 3000, 1000.0, 1000.0, PhotoCrop(0.0, 0.9, 1.0))
        assertEquals(0.375, clamped.centerX, 1e-9)
        assertEquals(0.5, clamped.centerY, 1e-9)

        // Zooming in frees both axes.
        val zoomed = assertNotNull(CropGeometry.centreLimits(4000, 3000, 1000.0, 1000.0, 2.0))
        assertEquals(0.1875, zoomed[0], 1e-9)
        assertEquals(0.25, zoomed[2], 1e-9)
    }

    @Test
    fun pinch_zoom_anchors_the_point_under_the_fingers_and_respects_the_limits() {
        val crop = PhotoCrop(0.5, 0.5, 1.0)
        // Pinching in the middle only deepens the zoom.
        assertEquals(PhotoCrop(0.5, 0.5, 2.0), crop.zoomedAround(2.0, 0.5, 0.5))

        // Pinching left of centre pulls the centre towards the fingers by
        // (1 - zoom/nextZoom) of the distance: 0.5 - 0.25 * 0.5 = 0.375.
        val left = crop.zoomedAround(2.0, 0.25, 0.5)
        assertEquals(2.0, left.zoom)
        assertEquals(0.375, left.centerX, 1e-12)
        assertEquals(0.5, left.centerY, 1e-12)

        // Unsupported zoom factors are clamped, not applied.
        assertEquals(PhotoCrop.MAX_ZOOM, crop.zoomedAround(1e9, 0.5, 0.5).zoom)
        assertEquals(PhotoCrop.MIN_ZOOM, crop.zoomedAround(1e-9, 0.5, 0.5).zoom)
        assertEquals(crop, crop.zoomedAround(0.0, 0.5, 0.5))
        assertEquals(crop, crop.zoomedAround(Double.NaN, 0.5, 0.5))

        // A drag moves the window in the opposite direction of the finger.
        assertEquals(0.4, crop.panned(0.1, 0.0).centerX, 1e-12)
        assertEquals(0.6, crop.panned(-0.1, 0.0).centerX, 1e-12)
    }

    @Test
    fun a_centre_released_near_the_middle_snaps_onto_it() {
        val almost = PhotoCrop(0.52, 0.48, 2.0)
        // 居中自动吸附: within the tolerance the centre lands exactly on 0.5.
        assertEquals(PhotoCrop(0.5, 0.5, 2.0), almost.snapped(0.03, 0.03))
        // A centre outside the tolerance keeps its value.
        assertEquals(almost, almost.snapped(0.005, 0.005))
        // The snap never changes the zoom and never moves a far centre.
        assertEquals(2.0, almost.snapped(1.0, 1.0).zoom)
        assertEquals(PhotoCrop(0.9, 0.1, 2.0), PhotoCrop(0.9, 0.1, 2.0).snapped(0.05, 0.05))
    }

    @Test
    fun the_crop_round_trips_through_the_design_params_and_omits_defaults() {
        val base = PhotoDesign.base("simple-4").withParam("inset", 0.12)
        val stored = PhotoCropParams.write(base, PhotoCrop(0.2, 0.8, 2.5))

        assertEquals(0.2, stored.params[PhotoCropParams.X])
        assertEquals(0.8, stored.params[PhotoCropParams.Y])
        assertEquals(2.5, stored.params[PhotoCropParams.ZOOM])
        assertEquals(PhotoCrop(0.2, 0.8, 2.5), PhotoCropParams.read(stored.params))
        assertEquals(PhotoCrop(0.2, 0.8, 2.5), PhotoCropParams.read(stored))
        assertTrue(PhotoCropParams.hasCrop(stored.params))
        // The existing params keep both their value and their position.
        assertEquals(0.12, stored.params["inset"])
        assertEquals(
            listOf("inset", PhotoCropParams.X, PhotoCropParams.Y, PhotoCropParams.ZOOM),
            stored.params.keys.toList(),
        )

        // 重置 removes the keys again instead of writing 0.5/1.0.
        val reset = PhotoCropParams.write(stored, PhotoCrop.Default)
        assertEquals(setOf("inset"), reset.params.keys)
        assertEquals(PhotoCrop.Default, PhotoCropParams.read(reset.params))
        assertFalse(PhotoCropParams.hasCrop(reset.params))

        // A design that never cropped is returned untouched: no key, no edit, no undo step.
        assertSame(base, PhotoCropParams.write(base, PhotoCrop.Default))
        assertSame(base, PhotoCropParams.write(base, PhotoCrop(0.5, 0.5, 1.0)))
        assertFalse(PhotoCropParams.hasCrop(base.params))
    }

    @Test
    fun reading_a_hand_edited_param_map_is_tolerant_and_clamped() {
        // Numbers arrive as Double, Float, Int or String depending on who wrote the file.
        assertEquals(
            PhotoCrop(0.25, 0.75, 3.0),
            PhotoCropParams.read(
                mapOf(
                    PhotoCropParams.X to 0.25f,
                    PhotoCropParams.Y to "0.75",
                    PhotoCropParams.ZOOM to 3,
                ),
            ),
        )
        // Broken values fall back to the engine's own crop, never to NaN pixels.
        assertEquals(
            PhotoCrop.Default,
            PhotoCropParams.read(
                mapOf(
                    PhotoCropParams.X to "nope",
                    PhotoCropParams.Y to null,
                    PhotoCropParams.ZOOM to Double.NaN,
                ),
            ),
        )
        // Out-of-range values are clamped, not honoured.
        assertEquals(
            PhotoCrop(1.0, 0.0, PhotoCrop.MAX_ZOOM),
            PhotoCropParams.read(
                mapOf(
                    PhotoCropParams.X to 4.0,
                    PhotoCropParams.Y to -2.0,
                    PhotoCropParams.ZOOM to 1e9,
                ),
            ),
        )
    }
}
