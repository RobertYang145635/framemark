package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.LibraryDataSource
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.RealData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end inspector regression over the exact data files shipped by the app. */
class InspectorDataRealDataTest {
    @Test
    fun reported_phone_cards_receive_named_canonical_brand_choices_and_theme_labels() {
        val uiData = "framemark/ui/composeApp/src/commonMain/composeResources/files/data/"
        val taxonomy = RealData.text("${uiData}taxonomy.json")
        val previews = RealData.text("${uiData}previews.json")
        val capabilities = RealData.text("${uiData}capabilities.json")
        val controls = RealData.text("${uiData}controls.json")
        val inspectorMeta = RealData.text("${uiData}inspector-meta.json")
        val logos = RealData.text("${uiData}logos.json")
        assertEquals(
            RealData.text("framemark/engine/data/logos.json"),
            logos,
            "The shipped Compose logo catalog must remain byte-identical to the engine source.",
        )

        val sourceBrands = Json.parse(logos).jsonMap()["brands"].jsonList()
            .mapNotNull { it.jsonMap().jsonString("key")?.takeIf(String::isNotBlank) }
        val canonicalKeys = sourceBrands.toSet()
        assertTrue(canonicalKeys.isNotEmpty())
        assertEquals(sourceBrands.size, canonicalKeys.size, "Canonical brand IDs must be unique.")

        val library = LibraryDataSource.parse(taxonomy, previews)
        val inspector = InspectorDataSource.parse(capabilities, controls, inspectorMeta, logos)
        val cardsById = library.cards.associateBy { it.id }

        val reportedPhoneCards = listOf(
            "stock-xiaomi-classic-17",
            "stock-xiaomi-disney-31",
            "personal-simple",
        )
        val models = reportedPhoneCards.associateWith { cardId ->
            val card = assertNotNull(cardsById[cardId], "Missing shipped card $cardId")
            InspectorModelFactory.build(card, inspector)
        }

        for ((cardId, model) in models) {
            val brandControls = model.controls.filter { it.kind == "brandPicker" && !it.hidden }
            assertTrue(brandControls.isNotEmpty(), "$cardId must expose its reported manual brand control")
            for (control in brandControls) {
                assertEquals(ControlType.Brand, control.type, "$cardId::${control.controlId}")
                assertTrue(control.label.isNotBlank(), "$cardId::${control.controlId} needs a visible label")
                assertTrue(control.options.size > 1, "$cardId::${control.controlId} must contain manual choices")
                assertEquals("auto", control.options.first().value, "automatic choice must stay available")
                assertEquals(canonicalKeys, control.options.drop(1).map { it.value }.toSet())
                assertTrue(control.options.any { it.value == "xiaomi" && it.display == "小米" })
                assertTrue(control.options.all { it.display.isNotBlank() }, "$cardId contains an unnamed choice")
                assertTrue(
                    control.options.all { option ->
                        !option.value.contains('/') && !option.value.contains('\\') &&
                            !option.value.endsWith(".svg", ignoreCase = true) &&
                            !option.display.contains('/') && !option.display.contains('\\') &&
                            !option.display.endsWith(".svg", ignoreCase = true)
                    },
                    "$cardId options must expose brand IDs/names, never asset paths",
                )
            }
        }

        val classicTheme = assertNotNull(models["stock-xiaomi-classic-17"])
            .controls.first { it.kind == "themeSwatches" }
        assertEquals("浅色模糊", classicTheme.options.first { it.value == "light_blur" }.display)
        assertEquals("深色模糊", classicTheme.options.first { it.value == "dark_blur" }.display)
        assertFalse(classicTheme.options.any { it.display == "未设置" })

        val disneyTheme = assertNotNull(models["stock-xiaomi-disney-31"])
            .controls.first { it.kind == "themeSwatches" }
        assertEquals("透明白", disneyTheme.options.first { it.value == "transparent_white" }.display)
        assertFalse(disneyTheme.options.any { it.display == "未设置" })

        assertEquals("未选择", HumanLabels.forThemeOption("  "))
        assertEquals("未知主题（future_theme）", HumanLabels.forThemeOption("future_theme"))
    }
}
