package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectDesignTest {
    @Test
    fun every_photo_keeps_its_own_design_and_watermark() {
        var project = Project(id = "proj")
            .addPhoto(photoRef("a"))
            .applyCard("simple-4")
            .addPhoto(photoRef("b"))
            .selectPhoto("a")
            .updateActiveDesign { it.withParam("inset", 0.12).withText("model", "X-T5").withOverlay(watermarkSpec(1)) }
            .selectPhoto("b")
            .updateActiveDesign { it.withParam("inset", 0.4).withOverlay(watermarkSpec(2, size = 32)) }
            .selectPhoto("a")

        val a = project.designOf("a")!!
        assertEquals("simple-4", a.cardId)
        assertEquals(0.12, a.params["inset"])
        assertEquals("X-T5", a.text["model"])
        assertEquals("sig-1.png", a.overlay?.asset?.name)
        assertFalse(a.baseDesign)

        val b = project.designOf("b")!!
        assertEquals(0.4, b.params["inset"])
        assertNull(b.text["model"])
        assertEquals("sig-2.png", b.overlay?.asset?.name)

        assertEquals("a", project.activePhotoId)
        assertEquals(a, project.activeDesign)
    }

    @Test
    fun editing_one_photo_cannot_touch_another() {
        val base = sampleProject()
        val before = base.designOf("a")

        val edited = base
            .selectPhoto("b")
            .updateActiveDesign { it.withParam("inset", 0.9).withOverlay(watermarkSpec(2)) }

        assertEquals(before, edited.designOf("a"))
        assertEquals(0.9, edited.designOf("b")!!.params["inset"])
        assertEquals("sig-2.png", edited.designOf("b")!!.overlay?.asset?.name)
        // Source project is immutable.
        assertNull(base.designOf("b")!!.params["inset"])
        assertNull(base.designOf("b")!!.overlay)
    }

    @Test
    fun changing_the_border_keeps_the_watermark_and_other_photos() {
        val before = sampleProject()
        val switched = before.applyCard("film-15")

        val a = switched.designOf("a")!!
        assertEquals("film-15", a.cardId)
        assertEquals("simple-4", before.designOf("a")!!.cardId)
        assertTrue(a.params.isEmpty())
        assertTrue(a.text.isEmpty())
        assertTrue(a.baseDesign)
        assertEquals(before.designOf("a")!!.overlay, a.overlay)
        assertEquals(before.designOf("b"), switched.designOf("b"))
    }

    @Test
    fun a_new_photo_inherits_the_current_design_but_not_the_watermark() {
        val project = sampleProject() // active a: simple-4, inset 0.12, model X-T5, sig-1
        val next = project.addPhoto(photoRef("c"))

        val inherited = next.designOf("c")!!
        assertEquals("simple-4", inherited.cardId)
        assertEquals(0.12, inherited.params["inset"])
        assertEquals("X-T5", inherited.text["model"])
        assertNull(inherited.overlay, "新照片不得自动继承水印")
        assertFalse(inherited.baseDesign)
        assertEquals(project.designForNewPhoto(), inherited)

        // The copy is independent on both sides.
        val edited = next.selectPhoto("c")
            .updateActiveDesign { it.withParam("inset", 0.8).withOverlay(watermarkSpec(7)) }
        assertEquals(0.12, edited.designOf("a")!!.params["inset"])
        assertEquals("sig-1.png", edited.designOf("a")!!.overlay?.asset?.name)
        assertEquals(0.8, edited.designOf("c")!!.params["inset"])
        assertEquals("sig-7.png", edited.designOf("c")!!.overlay?.asset?.name)
        assertEquals("a", next.activePhotoId)
        assertEquals(3, next.photos.size)
    }

    @Test
    fun an_explicit_shared_design_is_what_new_photos_inherit() {
        val project = sampleProject().setSharedDesign(
            PhotoDesign.base("film-15").withParam("inset", 0.33).withOverlay(watermarkSpec(9)),
        )

        val shared = project.sharedDesign!!
        assertEquals("film-15", shared.cardId)
        assertEquals(0.33, shared.params["inset"])
        assertNull(shared.overlay, "默认设计存不下水印")
        assertEquals("film-15", project.selectedCardId)

        val next = project.addPhoto(photoRef("c"))
        assertEquals(shared, next.designOf("c"))
        assertNull(next.designOf("c")!!.overlay)
        assertEquals(project.designOf("a"), next.designOf("a"), "已有照片不因默认设计变化")
    }

    @Test
    fun watermarks_spread_only_through_an_explicit_apply_to_all() {
        val project = sampleProject().addPhoto(photoRef("c"))
        assertNull(project.designOf("c")!!.overlay)

        val spec = project.designOf("a")!!.overlay!!
        val spread = project.applyWatermarkToAll()
        assertEquals(spec, spread.designOf("a")!!.overlay)
        assertEquals(spec, spread.designOf("b")!!.overlay)
        assertEquals(spec, spread.designOf("c")!!.overlay)
        assertEquals(0.12, spread.designOf("c")!!.params["inset"], "“应用到全部”只复制水印")

        val edited = spread.selectPhoto("a")
            .updateActiveDesign { it.withOverlay(watermarkSpec(8, size = 16)) }
        assertEquals(spec, edited.designOf("b")!!.overlay)
        assertEquals(spec, edited.designOf("c")!!.overlay)
        assertEquals("sig-8.png", edited.designOf("a")!!.overlay?.asset?.name)

        val cleared = spread.applyWatermarkToAll(null)
        assertNull(cleared.designOf("a")!!.overlay)
        assertNull(cleared.designOf("b")!!.overlay)
        assertNull(cleared.designOf("c")!!.overlay)
    }

    @Test
    fun apply_to_all_gives_photos_without_a_design_one_when_a_card_is_known() {
        val project = Project(id = "p").addPhoto(photoRef("a")).addPhoto(photoRef("b"))
        assertNull(project.designOf("b"))

        val withCard = project.applyCard("film-15") // applies to the active photo only
        assertNull(withCard.designOf("b"))

        val spread = withCard.applyWatermarkToAll(watermarkSpec(4))
        assertEquals("sig-4.png", spread.designOf("b")!!.overlay?.asset?.name)
        assertEquals("film-15", spread.designOf("b")!!.cardId)
        assertEquals("film-15", spread.designOf("a")!!.cardId)
    }

    @Test
    fun adding_a_photo_without_a_selected_card_adds_no_design() {
        val project = Project(id = "p").addPhoto(photoRef("a"))
        assertNull(project.activeDesign)
        assertEquals("a", project.activePhotoId)
    }

    @Test
    fun editing_without_an_active_design_is_a_no_op() {
        val empty = Project(id = "p")
        assertSame(empty, empty.updateActiveDesign { it.withParam("inset", 1.0) })

        val noDesign = Project(id = "p", photos = listOf(photoRef("a")), activePhotoId = "a")
        assertSame(noDesign, noDesign.updateActiveDesign { it.withParam("inset", 1.0) })
    }

    @Test
    fun removing_a_photo_drops_only_its_design() {
        val project = sampleProject().removePhoto("a")
        assertNull(project.designOf("a"))
        assertEquals("b", project.activePhotoId)
        assertEquals(1, project.photos.size)
        assertEquals("glass-white-1", project.designOf("b")!!.cardId)
    }

    @Test
    fun base_design_is_a_no_op_for_the_engine() {
        val base = PhotoDesign.base("simple-4")
        val snapshot = base.toDesignSnapshot()
        assertEquals("simple-4", snapshot.cardId)
        assertNull(snapshot.styleId)
        assertTrue(snapshot.params.isEmpty())
        assertTrue(snapshot.text.isEmpty())
        assertTrue(base.baseDesign)
        assertTrue(base.isUntouched)

        val edited = base.withParam("inset", 0.2).withText("model", "X-T5")
        assertFalse(edited.baseDesign)
        assertEquals(base, edited.resetToBase())
        assertTrue(edited.resetToBase().baseDesign)

        // Resetting the border does not remove the signature.
        val signed = edited.withOverlay(watermarkSpec(3))
        assertEquals(watermarkSpec(3), signed.resetToBase().overlay)
    }

    @Test
    fun clearing_a_value_removes_the_override() {
        val design = PhotoDesign.base("simple-4").withParam("inset", 0.2).withParam("inset", null)
        assertTrue(design.params.isEmpty())
    }

    @Test
    fun blank_logo_normalizes_to_auto_and_is_recoverable() {
        val design = PhotoDesign.base("simple-4").withLogo("  ")
        assertEquals(null, design.logo, "blank logo must persist as unset")
        val decoded = decodeDesign(
            mapOf(
                "cardId" to "simple-4",
                "logo" to "",
                "params" to emptyMap<String, Any?>(),
                "text" to emptyMap<String, Any?>(),
                "baseDesign" to false,
            ),
            "test",
        )
        assertEquals(null, decoded.logo, "empty logo from an older build must stay readable")
    }
}

class WatermarkSpecTest {
    @Test
    fun rect_stays_inside_the_photo_and_keeps_the_asset_aspect() {
        val spec = watermarkSpec(1) // asset 800x200 → aspect 4
        val rect = spec.rect(photoAspect = 1.5f)

        assertTrue(rect.x >= 0f && rect.y >= 0f, "rect 原点越界：${rect.x},${rect.y}")
        assertTrue(rect.x + rect.w <= 1.0001f, "rect 右边界越界：${rect.x + rect.w}")
        assertTrue(rect.y + rect.h <= 1.0001f, "rect 下边界越界：${rect.y + rect.h}")
        assertEquals(rect.w * 1.5f / 4f, rect.h, 1e-4f, "素材宽高比未保持")
    }

    @Test
    fun anchors_place_the_mark_in_the_expected_corner() {
        val spec = watermarkSpec(1)
        val topLeft = spec.copy(anchor = 0).rect(photoAspect = 1.5f)
        assertEquals(WatermarkSpec.MARGIN, topLeft.x, 1e-5f)
        assertEquals(WatermarkSpec.MARGIN, topLeft.y, 1e-5f)

        val bottomRight = spec.copy(anchor = 8).rect(photoAspect = 1.5f)
        assertEquals(1f - bottomRight.w - WatermarkSpec.MARGIN, bottomRight.x, 1e-5f)
        assertEquals(1f - bottomRight.h - WatermarkSpec.MARGIN, bottomRight.y, 1e-5f)

        val center = spec.copy(anchor = 4).rect(photoAspect = 1.5f)
        assertEquals((1f - center.w) / 2f, center.x, 1e-5f)
        assertEquals((1f - center.h) / 2f, center.y, 1e-5f)
    }

    @Test
    fun nudges_clamp_to_the_photo_and_keep_the_opacity() {
        val spec = watermarkSpec(1)
        val nudged = spec.nudged(-0.9f, -0.9f, photoAspect = 1.5f)
        val rect = nudged.rect(photoAspect = 1.5f)
        assertTrue(rect.x >= -1e-4f && rect.y >= -1e-4f, "位移后原点越界：${rect.x},${rect.y}")
        assertEquals(spec.opacity, nudged.opacity)
    }

    @Test
    fun overlay_conversion_matches_the_rect_maths() {
        val spec = watermarkSpec(1)
        val overlay = spec.toOverlaySpec("/tmp/fm/signature.png", photoAspect = 1.5f)
        val rect = spec.rect(photoAspect = 1.5f)
        assertEquals("/tmp/fm/signature.png", overlay.path)
        assertEquals(rect.x, overlay.x)
        assertEquals(rect.y, overlay.y)
        assertEquals(rect.w, overlay.w)
        assertEquals(rect.h, overlay.h)
        assertEquals(spec.opacity, overlay.opacity)
        assertEquals(0f, overlay.angle)
    }

    @Test
    fun rotation_keeps_draw_rect_and_aabb_inside_the_photo() {
        val base = watermarkSpec(1)
        for (angle in listOf(45f, 90f, -90f, 180f)) {
            val spec = base.copy(rotation = angle)
            val rect = spec.rect(photoAspect = 1.5f)
            val box = spec.aabb(photoAspect = 1.5f)
            assertTrue(rect.x >= -1e-4f && rect.y >= -1e-4f, "$angle rect 原点越界")
            assertTrue(rect.x + rect.w <= 1.0001f && rect.y + rect.h <= 1.0001f, "$angle rect 越界")
            assertTrue(box.x >= -1e-4f && box.y >= -1e-4f, "$angle aabb 原点越界")
            assertTrue(box.x + box.w <= 1.0001f && box.y + box.h <= 1.0001f, "$angle aabb 越界")
            assertEquals(
                rect.x + rect.w / 2f,
                box.x + box.w / 2f,
                1e-4f,
                "$angle 旋转中心漂移",
            )
            assertEquals(
                rect.y + rect.h / 2f,
                box.y + box.h / 2f,
                1e-4f,
                "$angle 旋转中心漂移",
            )
        }
    }

    @Test
    fun rotation_angle_survives_overlay_conversion() {
        val overlay = watermarkSpec(1).copy(rotation = 37f)
            .toOverlaySpec("/tmp/fm/signature.png", photoAspect = 1.5f)
        assertEquals(37f, overlay.angle)
    }

    @Test
    fun asset_keeps_name_size_and_hash() {
        val bytes = ByteArray(1024 * 1024) { (it * 13).toByte() }
        val asset = WatermarkAsset.create("sign.png", "image/png", 1200, 300, bytes)
        assertEquals(bytes.size, asset.byteSize)
        assertEquals(1200, asset.widthPx)
        assertEquals(4f, asset.aspect)
        assertEquals(Sha256.hex(bytes), asset.sha256)
        assertTrue(asset.isIntact())
        assertEquals(asset, WatermarkAsset("sign.png", "image/png", 1200, 300, asset.sha256, bytes.copyOf()))
    }
}
