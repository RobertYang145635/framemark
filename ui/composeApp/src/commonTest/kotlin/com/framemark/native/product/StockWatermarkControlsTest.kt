package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「这种是需要放开的，有的边框需要定死，有的不需要」: one predicate decides whether a
 * vendor-spec (IR) card declares a movable watermark, and how its declared controls
 * split into the movable roles vs the read-only remainder.
 *
 * The read-only gate on both platforms is `!plan.adjustable` (plus each platform's
 * unchanged hand-card rule), so these cases are the whole decision.
 */
class StockWatermarkControlsTest {

    @Test
    fun keys_accept_the_consumer_and_the_bare_spelling() {
        assertEquals("stockAnchor", StockWatermarkControls.keyOf("stockAnchor"))
        assertEquals("stockAnchor", StockWatermarkControls.keyOf("params.stockAnchor"))
        assertEquals("stockOffsetXPct", StockWatermarkControls.keyOf("params.stockOffsetXPct"))
        assertEquals("stockOffsetYPct", StockWatermarkControls.keyOf("  params.stockOffsetYPct  "))
        assertNull(StockWatermarkControls.keyOf(null))
        assertNull(StockWatermarkControls.keyOf(""))
        assertNull(StockWatermarkControls.keyOf("params.footerPct"))
        assertNull(StockWatermarkControls.keyOf("params.slotText:b0/g0/g0"))
        assertNull(StockWatermarkControls.keyOf("stockOffset"))
    }

    @Test
    fun the_three_roles_are_distinguished() {
        assertTrue(StockWatermarkControls.isAnchorParam("params.stockAnchor"))
        assertFalse(StockWatermarkControls.isAnchorParam("params.stockOffsetXPct"))
        assertTrue(StockWatermarkControls.isOffsetParam("params.stockOffsetXPct"))
        assertTrue(StockWatermarkControls.isOffsetParam("params.stockOffsetYPct"))
        assertFalse(StockWatermarkControls.isOffsetParam("params.stockAnchor"))
        assertTrue(StockWatermarkControls.isMovableParam("params.stockOffsetYPct"))
        assertFalse(StockWatermarkControls.isMovableParam("theme"))
        assertFalse(StockWatermarkControls.isMovableParam(null))
    }

    @Test
    fun card_level_predicate_unlocks_only_a_declaring_card() {
        assertTrue(
            declaresMovableStockWatermark(
                listOf("params.slotText:b0/g0", "params.stockAnchor", "params.stockOffsetXPct"),
            ),
        )
        assertTrue(declaresMovableStockWatermark(listOf("stockOffsetYPct")))
        assertFalse(declaresMovableStockWatermark(listOf("theme", "params.footerPct", "text.showModel")))
        assertFalse(declaresMovableStockWatermark(emptyList()))
    }

    @Test
    fun plan_splits_anchor_offsets_and_declared_rows() {
        val controls = listOf(
            "params.slotText:b0/g0",
            "params.stockOffsetXPct",
            "params.stockAnchor",
            "params.stockOffsetYPct",
            "params.slotOff:b0/g0",
        )
        val plan = stockWatermarkPlan(controls) { it }
        assertEquals("params.stockAnchor", plan.anchor)
        assertEquals("params.stockOffsetXPct", plan.offsetX)
        assertEquals("params.stockOffsetYPct", plan.offsetY)
        assertEquals(listOf("params.slotText:b0/g0", "params.slotOff:b0/g0"), plan.others)
        assertEquals(listOf("params.stockOffsetXPct", "params.stockOffsetYPct"), plan.offsets)
        assertEquals(
            listOf("params.stockAnchor", "params.stockOffsetXPct", "params.stockOffsetYPct"),
            plan.owned,
        )
        assertTrue(plan.adjustable)
        assertEquals(0, plan.duplicateAnchors)
        assertEquals(0, plan.duplicateOffsets)
    }

    @Test
    fun plan_reports_repeated_rows_instead_of_rendering_two_grids() {
        // The audit emits one row per style group, so a card can repeat `anchorGrid`;
        // `params.stockAnchor` is still one design field.
        val plan = stockWatermarkPlan(
            listOf(
                "params.stockAnchor",
                "params.stockAnchor",
                "params.stockOffsetXPct",
                "params.stockOffsetXPct",
            ),
        ) { it }
        assertEquals(1, plan.owned.count { StockWatermarkControls.isAnchorParam(it) })
        assertEquals(1, plan.duplicateAnchors)
        assertEquals(1, plan.duplicateOffsets)
        assertTrue(plan.others.isEmpty())
    }

    @Test
    fun a_card_that_declares_no_stock_param_is_not_adjustable() {
        val plan = stockWatermarkPlan(listOf("theme", "params.footerPct", "params.footerMode")) { it }
        assertFalse(plan.adjustable)
        assertNull(plan.anchor)
        assertTrue(plan.offsets.isEmpty())
        assertEquals(3, plan.others.size)
        assertFalse(declaresMovableStockWatermark(plan.others))
    }

    @Test
    fun the_same_predicate_reads_the_registry_model() {
        // `ControlSpec.consumer` is the `params.` spelling; `sourceParam` is the bare
        // one. Both must resolve, whichever a caller hands over.
        fun spec(consumer: String, sourceParam: String?) = ControlSpec(
            controlId = "anchorGrid.bandb0.b0",
            type = ControlType.Select,
            sourceKind = "anchorGrid",
            label = "水印位置",
            consumer = consumer,
            consumerSource = ConsumerSource.Derived,
            scope = "template-param",
            group = "layout",
            defaultValue = "vendor",
            currentValue = null,
            min = null,
            max = null,
            step = null,
            options = emptyList(),
            unit = null,
            visible = true,
            gated = false,
            audited = false,
            routable = true,
            consumed = false,
            hiddenReason = null,
            sourceParam = sourceParam,
        )
        val byConsumer = stockWatermarkPlan(listOf(spec("params.stockAnchor", "stockAnchor"))) { it.consumer }
        assertEquals(1, byConsumer.owned.size)
        assertTrue(byConsumer.adjustable)
        val bySourceParam = stockWatermarkPlan(listOf(spec("params.stockAnchor", "stockAnchor"))) { it.sourceParam }
        assertTrue(bySourceParam.adjustable)
        assertEquals("stockAnchor", StockWatermarkControls.keyOf(bySourceParam.anchor?.sourceParam))
    }
}
