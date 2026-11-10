package com.framemark.native.inspector

import com.framemark.native.library.LibraryDataSource
import com.framemark.native.product.ControlBackend
import com.framemark.native.product.RealData
import com.framemark.native.product.StockWatermarkControls
import com.framemark.native.product.stockWatermarkPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-only gate over the **shipped data**, on both model paths:
 *
 *  - the phone path (`InspectorModelFactory` over the frozen packaged JSON) and
 *  - the desktop path (`ControlRegistry` + `IrSlotBinding` → `RegistryInspectorFactory`).
 *
 * Rule under test: a vendor-spec card is read-only **exactly when** it declares no
 * `stockAnchor`/offset row, and a card that declares one shows that row exactly once
 * (as the 3×3 grid). 37 stock styles declare the movable trio; every other stock
 * style must keep today's spec block.
 */
class StockWatermarkRealDataTest {

    private val uiData = "framemark/ui/composeApp/src/commonMain/composeResources/files/data/"

    private fun phoneModels(): List<InspectorCardModel> {
        val library = LibraryDataSource.parse(
            RealData.text("${uiData}taxonomy.json"),
            RealData.text("${uiData}previews.json"),
        )
        val inspector = InspectorDataSource.parse(
            RealData.text("${uiData}capabilities.json"),
            RealData.text("${uiData}controls.json"),
            RealData.text("${uiData}inspector-meta.json"),
            RealData.text("${uiData}logos.json"),
        )
        return library.cards.map { InspectorModelFactory.build(it, inspector) }
    }

    private fun desktopModels(): List<InspectorCardModel> {
        val registry = RealData.registry()
        val binding = IrSlotBinding.load(RealData.text("framemark/bridge/desktop/ir-slot-binding.json"))
        return registry.cards
            .filter { it.backend == ControlBackend.Ir }
            .map { spec ->
                RegistryInspectorFactory.build(
                    spec.cardId,
                    spec.name,
                    spec.category,
                    spec.categoryName,
                    spec,
                    binding,
                ).model
            }
    }

    private fun checkCards(models: List<InspectorCardModel>, path: String) {
        var adjustable = 0
        var specOnly = 0
        for (model in models) {
            val visible = model.controls.filterNot { it.hidden }
            val plan = stockWatermarkPlan(visible) { it.param }
            val anchors = visible.count { StockWatermarkControls.isAnchorParam(it.param) }
            if (!plan.adjustable) {
                assertEquals(0, anchors, "$path ${model.cardId} renders a grid without a movable plan")
                if (model.isIr) {
                    // Declares nothing movable → exactly today's read-only block.
                    assertEquals(
                        StylePageBody.SpecOnly,
                        phoneStylePageBody(isIr = true, controls = visible),
                        "$path ${model.cardId} must stay read-only",
                    )
                    specOnly++
                }
                continue
            }
            adjustable++
            // One 水印位置 control, never two grids for the same design key.
            assertEquals(1, anchors, "$path ${model.cardId} must show exactly one 水印位置")
            assertEquals(1, plan.owned.count { StockWatermarkControls.isAnchorParam(it.param) })
            assertEquals(0, plan.duplicateAnchors, "$path ${model.cardId} repeats the anchor row")
            assertTrue(plan.offsets.isNotEmpty(), "$path ${model.cardId} anchors without 微调")
            assertEquals(
                StylePageBody.Controls,
                phoneStylePageBody(isIr = true, controls = visible),
                "$path ${model.cardId} declares a movable watermark but stayed read-only",
            )
            // The value the grid writes and the value the engine reads agree.
            plan.anchor?.param?.let { assertEquals("params.stockAnchor", it, "$path ${model.cardId}") }
        }
        assertTrue(adjustable >= 30, "$path only found $adjustable adjustable vendor cards")
        assertTrue(specOnly >= 50, "$path only found $specOnly read-only vendor cards")
        println("STOCK_WATERMARK_REAL_DATA path=$path adjustable=$adjustable specOnly=$specOnly")
    }

    @Test
    fun the_shipped_phone_models_gate_on_the_declared_rows() {
        checkCards(phoneModels(), "phone")
    }

    @Test
    fun the_shipped_desktop_models_gate_on_the_declared_rows() {
        checkCards(desktopModels(), "desktop")
    }
}
