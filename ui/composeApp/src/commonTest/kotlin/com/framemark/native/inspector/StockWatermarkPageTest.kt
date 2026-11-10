package com.framemark.native.inspector

import com.framemark.native.product.PhotoDesign
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.applyInspectorOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「这种是需要放开的，有的边框需要定死，有的不需要」— the 样式 page split.
 *
 * A vendor-spec (IR) card that declares `stockAnchor` / the two offsets must render
 * its declared controls (水印位置 as a 3×3 grid, then the 微调 sliders) instead of the
 * read-only block; a card that declares none keeps exactly the old block; hand
 * (non-IR) cards keep WP-C's rule untouched.
 */
class StockWatermarkPageTest {

    private fun control(
        controlId: String,
        param: String,
        label: String,
        kind: String,
        type: ControlType = ControlType.Slider,
        initial: ControlValue = ControlValue.Num(0f),
        min: Float? = -25f,
        max: Float? = 25f,
        step: Float? = 0.5f,
        section: InspectorSection = InspectorSection.Layout,
        options: List<ControlOption> = emptyList(),
    ) = InspectorControl(
        key = "card::$controlId",
        controlId = controlId,
        param = param,
        label = label,
        kind = kind,
        section = section,
        type = type,
        initial = initial,
        min = min,
        max = max,
        step = step,
        options = options,
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    /** The declared 水印位置 row: `params.stockAnchor`, nine cells + `vendor`. */
    private fun anchor(controlId: String = "anchorGrid.bandb0.b0") = control(
        controlId = controlId,
        param = "params.stockAnchor",
        label = "水印位置",
        kind = "anchorGrid",
        type = ControlType.Select,
        initial = ControlValue.Str("vendor"),
        min = null,
        max = null,
        step = null,
        options = AnchorDomain.map { ControlOption(it, it) },
    )

    private fun offsetX() = control("xyOffset.offsetX.b0", "params.stockOffsetXPct", "水平微调", "xyOffset")

    private fun offsetY() = control("xyOffset.offsetY.b0", "params.stockOffsetYPct", "垂直微调", "xyOffset")

    private fun textRow() = control(
        controlId = "text.modelLine.g2",
        param = "params.slotText:b0/g0/g2",
        label = "编辑机型文字",
        kind = "text",
        type = ControlType.Text,
        initial = ControlValue.Str(""),
        min = null,
        max = null,
        step = null,
        section = InspectorSection.Text,
    )

    private fun themeRow() = control(
        controlId = "theme.theme",
        param = "theme",
        label = "底色",
        kind = "themeSwatches",
        type = ControlType.Select,
        initial = ControlValue.Str("white"),
        min = null,
        max = null,
        step = null,
        section = InspectorSection.Theme,
    )

    private fun footerMode() = control(
        controlId = "gravity.footerMode",
        param = "params.footerMode",
        label = "版式",
        kind = "gravitySegment",
        type = ControlType.Select,
        initial = ControlValue.Str("classic"),
        min = null,
        max = null,
        step = null,
        options = listOf(ControlOption("classic", "经典")),
    )

    private fun footerPct() = control(
        controlId = "slider.params:footerPct",
        param = "params.footerPct",
        label = "底部留白",
        kind = "slider",
        min = 0f,
        max = 40f,
        step = 0.5f,
    )

    private fun model(
        isIr: Boolean,
        controls: List<InspectorControl>,
        cardId: String = "card",
    ) = InspectorCardModel(
        cardId = cardId,
        cardName = "卡",
        audited = !isIr,
        isIr = isIr,
        effectiveCount = controls.size,
        gatedCount = null,
        declaredCount = controls.size,
        effectiveIds = controls.map { it.controlId },
        gatedIds = emptyList(),
        visibleIds = controls.filterNot { it.hidden }.map { it.controlId },
        hiddenIds = controls.filter { it.hidden }.map { it.controlId },
        controls = controls,
        sections = emptyList(),
    )

    /** The declared `stockAnchor` domain order, straight from controls.json. */
    private val AnchorDomain = listOf(
        "vendor", "leftTop", "leftCenter", "leftBottom",
        "centerTop", "center", "centerBottom",
        "rightTop", "rightCenter", "rightBottom",
    )

    // ---- the read-only gate ---------------------------------------------------

    @Test
    fun a_vendor_card_that_declares_anchor_and_offsets_is_not_spec_only() {
        val controls = listOf(anchor(), offsetX(), offsetY())
        val page = model(isIr = true, controls = controls)
        assertEquals(StylePageBody.Controls, phoneStylePageBody(isIr = true, controls = controls))
        assertTrue(movableStockWatermark(controls))
        // Its 水印位置 control is present exactly once, so the grid renders once.
        assertEquals(
            1,
            withoutDuplicateStockAnchors(page.controls).count { it.param == "params.stockAnchor" },
        )
    }

    @Test
    fun a_vendor_card_that_declares_none_stays_spec_only() {
        val controls = listOf(textRow())
        assertEquals(StylePageBody.SpecOnly, phoneStylePageBody(isIr = true, controls = controls))
        assertFalse(movableStockWatermark(controls))
    }

    @Test
    fun offsets_alone_are_enough_to_unlock_the_page() {
        // A card may declare only the 微调 sliders; then there is no grid but the
        // sliders are still manipulable, so the page must not be read-only.
        val controls = listOf(offsetX(), offsetY())
        assertEquals(StylePageBody.Controls, phoneStylePageBody(isIr = true, controls = controls))
        assertTrue(movableStockWatermark(controls))
    }

    @Test
    fun repeated_anchor_rows_split_to_one_control() {
        val controls = listOf(anchor("anchorGrid.bandb0.b0"), anchor("anchorGrid.bandb1.b0"), offsetX())
        val deduped = withoutDuplicateStockAnchors(controls)
        assertEquals(2, deduped.size)
        assertEquals("anchorGrid.bandb0.b0", deduped.first { it.param == "params.stockAnchor" }.controlId)
    }

    // ---- hand (non-IR) cards are untouched ------------------------------------

    @Test
    fun a_hand_card_with_theme_and_sliders_keeps_its_controls() {
        val controls = listOf(themeRow(), footerMode(), footerPct())
        assertEquals(StylePageBody.Controls, phoneStylePageBody(isIr = false, controls = controls))
        assertFalse(movableStockWatermark(controls))
    }

    @Test
    fun a_hand_card_with_only_a_slider_keeps_its_controls() {
        // WP-C's fallback rule verbatim: any slider means the page is not read-only.
        val controls = listOf(footerPct())
        assertEquals(StylePageBody.Controls, phoneStylePageBody(isIr = false, controls = controls))
    }

    @Test
    fun a_hand_card_with_nothing_tunable_keeps_the_spec_block() {
        val controls = listOf(textRow())
        assertEquals(StylePageBody.SpecOnly, phoneStylePageBody(isIr = false, controls = controls))
    }

    @Test
    fun the_desktop_curated_panel_still_needs_theme_mode_and_footer() {
        assertTrue(desktopStylePageIsCurated(listOf(themeRow(), footerMode(), footerPct())))
        assertFalse(desktopStylePageIsCurated(listOf(themeRow(), footerMode())))
        // A movable vendor card is not a "curated" hand card: it keeps 边框规格 +
        // 可调参数, where the grid renders.
        assertFalse(desktopStylePageIsCurated(listOf(anchor(), offsetX(), offsetY())))
    }

    // ---- grid mapping ---------------------------------------------------------

    @Test
    fun stock_anchor_values_map_to_the_3x3_grid_not_the_declared_order() {
        // The declared domain is column-major; the grid is row-major.
        assertEquals("leftTop", AnchorDomain[1])
        assertEquals(0, stockAnchorIndex("leftTop"))
        assertEquals(1, stockAnchorIndex("centerTop"))
        assertEquals(2, stockAnchorIndex("rightTop"))
        assertEquals(3, stockAnchorIndex("leftCenter"))
        assertEquals(4, stockAnchorIndex("center"))
        assertEquals(5, stockAnchorIndex("rightCenter"))
        assertEquals(6, stockAnchorIndex("leftBottom"))
        assertEquals(7, stockAnchorIndex("centerBottom"))
        assertEquals(8, stockAnchorIndex("rightBottom"))
        // 「按规格」and junk pick no cell.
        assertNull(stockAnchorIndex("vendor"))
        assertNull(stockAnchorIndex(""))
        assertNull(stockAnchorIndex(null))
        assertNull(stockAnchorIndex("bogus"))
        assertEquals("leftTop", stockAnchorValueAt(0))
        assertEquals("rightBottom", stockAnchorValueAt(8))
        assertEquals(9, StockAnchorCells.toSet().size)
        assertEquals(9, StockAnchorCellLabels.toSet().size)
    }

    @Test
    fun the_spec_note_only_claims_a_fixed_text_position_when_it_is_fixed() {
        val readOnly = vendorSpecNote(watermarkMovable = false)
        assertTrue(readOnly.contains("文字位置"))
        assertTrue(readOnly.contains("按规格固定"))
        val adjustable = vendorSpecNote(watermarkMovable = true)
        assertFalse(adjustable.contains("文字位置"))
        assertTrue(adjustable.contains("尺寸、留白按厂商规格固定"))
        assertTrue(adjustable.contains("水印位置"))
    }

    // ---- the value rides the normal control plumbing --------------------------

    @Test
    fun anchor_and_offsets_write_to_design_params_and_reset_with_the_page() {
        val controls = listOf(anchor(), offsetX(), offsetY())
        val card = model(isIr = true, controls = controls)
        val session = DesignSession()
        session.seed(controls)
        assertEquals("vendor", session.value(controls[0]))
        assertEquals(0f, session.value(controls[1]))

        session.set(controls[0], stockAnchorValueAt(0))
        session.set(controls[1], 4.5f)
        val written = PhotoDesign.base("card").applyInspectorOverrides(card, session.overrides("card"))
        assertEquals("leftTop", written.params["stockAnchor"])
        // The design stores numbers as the codec's Double, ready for the render request.
        assertEquals(4.5, (written.params["stockOffsetXPct"] as Number).toDouble())

        // 恢复默认 = resetInspectorPage on 样式, the same path as every other control.
        val tabs = inspectorTabsFor(card, session)
        val changed = resetInspectorPage(tabs, InspectorPage.Style, session)
        assertEquals(2, changed)
        assertEquals("vendor", session.value(controls[0]))
        assertEquals(0f, session.value(controls[1]))
        // Back on the declared default, the key is simply not written ("按规格" =
        // the engine's own `vendor` position, which is also the absent value).
        assertNull(
            PhotoDesign.base("card").applyInspectorOverrides(card, session.overrides("card"))
                .params["stockAnchor"],
        )
    }

    @Test
    fun picking_the_vendor_cell_writes_the_vendor_token() {
        val controls = listOf(anchor())
        val card = model(isIr = true, controls = controls)
        val session = DesignSession()
        session.seed(controls)
        session.set(controls[0], stockAnchorValueAt(8))
        assertEquals("rightBottom", session.value(controls[0]))
        assertEquals(
            "rightBottom",
            PhotoDesign.base("card").applyInspectorOverrides(card, session.overrides("card"))
                .params["stockAnchor"],
        )
        session.set(controls[0], com.framemark.native.product.StockWatermarkControls.VENDOR_ANCHOR)
        assertEquals("vendor", session.value(controls[0]))
        assertFalse(session.isModified(controls[0]))
        assertNull(
            PhotoDesign.base("card").applyInspectorOverrides(card, session.overrides("card"))
                .params["stockAnchor"],
        )
    }
}
