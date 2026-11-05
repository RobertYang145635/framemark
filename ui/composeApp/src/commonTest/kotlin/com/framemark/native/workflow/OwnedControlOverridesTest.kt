package com.framemark.native.workflow

import com.framemark.native.inspector.ControlType
import com.framemark.native.inspector.ControlValue
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorSection
import com.framemark.native.inspector.InspectorSectionModel
import com.framemark.native.product.PhotoDesign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The collapse/reset path must replace only the values its own model owns:
 * an unknown flat parameter belongs to a future/other control and survives a
 * single-control reset, while the reset itself still has to clear the value.
 */
class OwnedControlOverridesTest {

    private fun control(key: String, param: String) = InspectorControl(
        key = key,
        controlId = key,
        param = param,
        label = key,
        kind = "test",
        section = InspectorSection.Layout,
        type = ControlType.Slider,
        initial = ControlValue.Num(0f),
        min = 0f,
        max = 100f,
        step = 1f,
        options = emptyList(),
        multiline = false,
        declaredDefault = null,
        currentText = null,
        autoMatch = null,
        unit = null,
        hidden = false,
    )

    private fun model(vararg controls: InspectorControl) = InspectorCardModel(
        cardId = "card-1",
        cardName = "card-1",
        audited = true,
        effectiveCount = controls.size,
        gatedCount = 0,
        declaredCount = controls.size,
        effectiveIds = controls.map { it.controlId },
        gatedIds = emptyList(),
        visibleIds = controls.map { it.controlId },
        hiddenIds = emptyList(),
        controls = controls.toList(),
        sections = listOf(InspectorSectionModel(InspectorSection.Layout, controls.toList())),
    )

    private fun design(vararg params: Pair<String, Any?>) =
        PhotoDesign(cardId = "card-1").withParams(mapOf(*params))

    @Test
    fun anOrdinaryEditKeepsFlatParametersItsModelDoesNotOwn() {
        val current = design("footerPct" to 10.0, "future.footerMode" to "wide", "slotText:hand:card-1:card.footer" to "A")
        val next = current.applyInspectorOverrides(
            model = model(control("card-1::footer", "params.footerPct")),
            overrides = mapOf("card-1::footer" to 42f),
        )
        assertEquals(42.0, (next.params["footerPct"] as Number).toDouble())
        assertEquals("wide", next.params["future.footerMode"], "an unknown future key is not swept away")
        assertEquals("A", next.params["slotText:hand:card-1:card.footer"])
    }

    @Test
    fun aResetRemovesOnlyTheOwnedKeyAndRestoresTheDefault() {
        val current = design("footerPct" to 42.0, "future.footerMode" to "wide")
        // The driver deletes the override when the user resets, then applies the map.
        val next = current.applyInspectorOverrides(
            model = model(control("card-1::footer", "params.footerPct")),
            overrides = emptyMap(),
        )
        assertNull(next.params["footerPct"], "reset drops the owned key instead of writing a sentinel")
        assertEquals("wide", next.params["future.footerMode"])
    }

    @Test
    fun textSlotOverridesAreOwnedAndRoundTrip() {
        val key = "hand:card-1:card.footer"
        val textControl = control("card-1::native-text-slot:$key:text", "params.slotText:$key")
        val switchControl = control("card-1::native-text-slot:$key:visible", "params.slotOff:$key")
        val current = design("future.key" to 7)
        val written = current.applyInspectorOverrides(
            model = model(textControl, switchControl),
            overrides = mapOf(
                textControl.key to "自定义页脚",
                switchControl.key to false,
            ),
        )
        assertEquals("自定义页脚", written.params["slotText:$key"])
        assertEquals(false, written.params["slotOff:$key"])
        assertEquals(7.0, written.params["future.key"])

        val cleared = written.applyInspectorOverrides(
            model = model(textControl, switchControl),
            overrides = emptyMap(),
        )
        assertNull(cleared.params["slotText:$key"])
        assertNull(cleared.params["slotOff:$key"])
        assertEquals(7.0, cleared.params["future.key"])
    }

    @Test
    fun aBoundConsumerIsWrittenDirectlyInsteadOfUnderAnUnmappedAlias() {
        val current = design()
        val next = current.applyInspectorOverrides(
            model = model(control("card-1::mystery", "params.mystery")),
            overrides = mapOf("card-1::mystery" to 5f),
        )
        // The consumer maps onto a real design field, so the value lands there and
        // no placeholder key is invented.
        assertEquals(5.0, (next.params["mystery"] as Number).toDouble())
        assertFalse(next.params.keys.any { it.toString().startsWith("unmapped_") })
    }

    @Test
    fun themeLogoAndFontScaleAreReplacedNotAccumulated() {
        val current = PhotoDesign(cardId = "card-1")
            .withTheme("ink")
            .withLogo("leica")
            .withFontScale(1.5)
        val next = current.applyInspectorOverrides(
            model = model(
                control("card-1::theme", "theme"),
                control("card-1::logo", "design.logo"),
                control("card-1::scale", "fontScale"),
            ),
            overrides = mapOf(
                "card-1::theme" to "paper",
                "card-1::logo" to "auto",
                "card-1::scale" to 1.1f,
            ),
        )
        assertEquals("paper", next.theme)
        assertEquals("auto", next.logo)
        assertEquals(1.1, next.fontScale!!, 1e-6)
    }
}
