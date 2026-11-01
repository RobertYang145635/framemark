package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlRegistryRulesTest {
    private fun spec(
        id: String = "c1",
        type: ControlType? = ControlType.Toggle,
        label: String = "显示机型",
        consumer: String = "text.showModel",
        visible: Boolean = true,
        gated: Boolean = false,
        defaultValue: Any? = true,
        min: Double? = null,
        max: Double? = null,
        step: Double? = null,
        options: List<ControlOption> = emptyList(),
        hiddenReason: String? = null,
    ) = ControlSpec(
        controlId = id,
        type = type,
        sourceKind = "toggle",
        label = label,
        consumer = consumer,
        consumerSource = ConsumerSource.Declared,
        scope = "design-text",
        group = "content",
        defaultValue = defaultValue,
        currentValue = null,
        min = min,
        max = max,
        step = step,
        options = options,
        unit = null,
        visible = visible,
        gated = gated,
        audited = true,
        routable = isRoutableConsumer(consumer),
        consumed = true,
        hiddenReason = hiddenReason,
        sourceParam = "text.showModel",
    )

    private fun card(
        id: String = "card-1",
        backend: ControlBackend = ControlBackend.Hand,
        controls: List<ControlSpec>,
        emptyReason: String? = null,
    ) = CardControlModel(
        cardId = id,
        name = id,
        category = "text",
        categoryName = "文字边框",
        brand = "",
        backend = backend,
        styleId = if (backend == ControlBackend.Hand) id else "style:$id",
        audited = backend == ControlBackend.Hand,
        controls = controls,
        effectiveCount = controls.size,
        gatedCount = controls.count { it.gated },
        emptyReason = emptyReason,
    )

    private fun stats(
        cards: List<CardControlModel>,
        capabilityRows: Int = 0,
        effective: Int = 0,
        gated: Int = 0,
        declared: CapabilityCounts? = null,
    ) = RegistryStats(
        cards = cards.size,
        auditedCards = cards.count { it.audited },
        unauditedCards = cards.count { !it.audited },
        cardsWithVisible = cards.count { it.visibleCount > 0 },
        emptyCards = cards.count { it.visibleCount == 0 },
        capabilityRows = capabilityRows,
        effectiveRows = effective,
        gatedRows = gated,
        declaredCounts = declared,
        platformRows = 0,
        platformStyles = 0,
        metaRowsJoined = 0,
        vendoredJoined = 0,
        handPanelRows = 0,
        handPanelCardPairs = 0,
        handPanelRangeMismatches = emptyList(),
        visibleControls = cards.sumOf { it.visibleCount },
        hiddenControls = cards.sumOf { card -> card.controls.count { !it.visible } },
        gatedControls = cards.sumOf { card -> card.controls.count { it.gated } },
        routableControls = cards.sumOf { card -> card.controls.count { it.routable } },
        consumedControls = cards.sumOf { card -> card.controls.count { it.consumed } },
        unmappedCards = emptyList(),
    )

    private fun rules(registry: ControlRegistry): List<String> = registry.validate().map { it.rule }

    @Test
    fun a_clean_registry_has_no_issues() {
        val registry = ControlRegistry(
            listOf(card(controls = listOf(spec()))),
            stats(listOf(card(controls = listOf(spec()))), capabilityRows = 1, effective = 1),
        )
        assertTrue(registry.validate().isEmpty(), registry.validate().joinToString("\n"))
    }

    @Test
    fun a_gated_control_marked_visible_is_reported() {
        val bad = card(controls = listOf(spec(gated = true, visible = true, hiddenReason = null)))
        val registry = ControlRegistry(listOf(bad), stats(listOf(bad)))
        assertTrue("GATED_VISIBLE" in rules(registry))
    }

    @Test
    fun a_visible_control_without_a_routable_consumer_is_reported() {
        val noConsumer = card(
            id = "c1",
            controls = listOf(spec(id = "a", consumer = "", hiddenReason = null)),
        )
        val unroutable = card(
            id = "c2",
            controls = listOf(spec(id = "b", consumer = "slotText", label = "槽位文字", hiddenReason = null)),
        )
        val registry = ControlRegistry(listOf(noConsumer, unroutable), stats(listOf(noConsumer, unroutable)))
        val found = rules(registry)
        assertTrue("VISIBLE_NO_CONSUMER" in found, found.toString())
        assertTrue("VISIBLE_UNROUTABLE" in found, found.toString())
        assertTrue("CONSUMER_FORMAT" in found, found.toString())
    }

    @Test
    fun technical_labels_and_incomplete_domains_are_reported() {
        val technical = spec(id = "t", label = "text.showModel")
        val slider = spec(
            id = "s",
            type = ControlType.Slider,
            label = "底部留白",
            consumer = "params.footerPct",
            defaultValue = null,
            min = null,
            max = null,
            step = null,
        )
        val select = spec(
            id = "o",
            type = ControlType.Select,
            label = "方位",
            consumer = "params.pos",
            defaultValue = "center",
            options = emptyList(),
        )
        val bad = card(controls = listOf(technical, slider, select))
        val registry = ControlRegistry(listOf(bad), stats(listOf(bad)))
        val found = rules(registry)
        assertTrue("LABEL_TECHNICAL" in found, found.toString())
        assertTrue(found.count { it == "RANGE_INCOMPLETE" } >= 2, found.toString())
        assertTrue("OPTIONS_MISSING" in found, found.toString())
    }

    @Test
    fun an_empty_card_must_carry_a_reason() {
        val bad = card(controls = listOf(spec(visible = false, gated = true, hiddenReason = "gated")), emptyReason = null)
        val registry = ControlRegistry(listOf(bad), stats(listOf(bad)))
        assertTrue("CARD_EMPTY_REASON" in rules(registry))
    }

    @Test
    fun capability_conservation_is_checked() {
        val good = card(controls = listOf(spec()))
        val registry = ControlRegistry(
            listOf(good),
            stats(
                listOf(good),
                capabilityRows = 10,
                effective = 7,
                gated = 2,
                declared = CapabilityCounts(rows = 11, effective = 6, gated = 2, auditedCards = 1, unauditedCards = 0),
            ),
        )
        val found = rules(registry)
        assertTrue("CAP_CONSERVATION" in found, found.toString())
        assertTrue("CAP_COUNT_ROWS" in found, found.toString())
        assertTrue("CAP_COUNT_EFFECTIVE" in found, found.toString())
    }

    @Test
    fun brand_options_may_repeat_their_key_as_label() {
        val brand = spec(
            id = "brand",
            type = ControlType.Select,
            label = "品牌字标",
            consumer = "logo",
            defaultValue = "auto",
            options = listOf(ControlOption("auto", "自动匹配"), ControlOption("vivo", "vivo")),
        )
        val good = card(controls = listOf(brand))
        assertTrue(ControlRegistry(listOf(good), stats(listOf(good))).validate().isEmpty())
    }

    @Test
    fun labels_turn_identifiers_into_human_copy_or_null() {
        assertEquals("底部留白", ControlLabels.forControl("footerPct", "slider.params:footerPct"))
        assertEquals("编辑参数文字", ControlLabels.forControl("编辑「exifCompositeLine」文字", "text.exifCompositeLine.g1"))
        assertEquals("白色", ControlLabels.forOption("white"))
        assertEquals("玻璃", ControlLabels.forOption("glass"))
        assertNull(ControlLabels.forControl("glassAlign", "gravity.params:glassAlign"))
        assertNull(ControlLabels.forOption("vivo"))
        assertTrue(isRoutableConsumer("params.footerPct"))
        assertTrue(isRoutableConsumer("params.slotText:b0/bottom0/g2"))
        assertTrue(isRoutableConsumer("text.showMake"))
        assertTrue(isRoutableConsumer("theme"))
        assertFalse(isRoutableConsumer("slotText"))
        assertFalse(isRoutableConsumer("params."))
    }
}
