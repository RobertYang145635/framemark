package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.ControlLabels
import com.framemark.native.product.RealData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, item 7: the Brand control showed 未设置 while a logo *was*
 * set.
 *
 * The legacy `ControlLabels.forBrand` knows ten brand keys (`laika`, `hasu_long_black`,
 * …). The value that reaches the control is also an **exact cut key** from
 * `logos.json` (`fushi_white`, `hassel_h_black`, 474 of them), and those fell through to
 * the literal 未设置. `InspectorDataSource` now carries a value → brand-name map built
 * from the same catalogue (`InspectorSnapshot.brandNames`), and the Brand control's
 * initial value resolves through it.
 */
class BrandNameResolutionTest {

    private val logos = RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/logos.json")
    private val meta = RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json")

    @Test
    fun everyCutKeyResolvesToItsBrand() {
        val names = InspectorDataSource.parseBrandNames(logos)
        assertTrue(names.size > 400, "expected the brand keys and cut keys, saw ${names.size}")
        // Brand keys and the legacy stems the frozen metadata still uses.
        assertEquals("徕卡", names["laika"])
        assertEquals("徕卡", names["laika_long"])
        // `hassel_h_black` is a real cut key; `hasu_long_black` is only a legacy stem
        // (see the metadata test for how that one resolves).
        assertEquals("哈苏", names["hassel_h_black"])
        assertNull(names["hasu_long_black"])
        // The modern persisted value is a namespaced cut key.
        val cut = names.keys.first { it.startsWith("ver:fushi:") }
        assertEquals("富士", names[cut])
        // A `ver:` key outside the catalogue still resolves by its brand segment.
        assertEquals("徕卡", InspectorDataSource.brandNameFor("ver:laika:not-in-the-catalog", names))
        assertNull(InspectorDataSource.brandNameFor("ver:nosuchbrand:x", names))
    }

    @Test
    fun theLegacyTableIsTheTrapThisTestDocuments() {
        // `ControlLabels.forBrand` knows ten legacy keys; the value the picker writes is
        // `ver:<brand>:<version>`, which used to fall through to 未设置.
        assertEquals("未设置", ControlLabels.forBrand("ver:laika:leica-1932"))
        assertEquals("徕卡", ControlLabels.forBrand("laika"))
        assertEquals("哈苏 H · 黑", ControlLabels.forBrand("hassel_h_black"))
    }

    @Test
    fun everyBrandControlValueInTheShippedMetadataResolves() {
        val names = InspectorDataSource.parseBrandNames(logos)
        val values = LinkedHashSet<String>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    if (node["kind"] == "brandPicker") {
                        (node["currentValue"] as? String)?.let(values::add)
                        (node["default"] as? String)?.let(values::add)
                    }
                    node.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
            }
        }
        walk(Json.parse(meta))
        assertTrue(values.isNotEmpty(), "expected brandPicker rows in inspector-meta.json")
        values.filter { it != "auto" }.forEach { value ->
            val label = InspectorDataSource.brandNameFor(value, names)
                ?: ControlLabels.knownBrandLabel(value)
            assertNotNull(label, "brand control value '$value' did not resolve to a brand")
            assertNotEquals("未设置", label)
        }
        // `auto` is the "match from the photo" choice and is not a brand name.
        assertTrue("auto" in values)
    }

    @Test
    fun aParsedSnapshotCarriesTheMap() {
        val snapshot = InspectorDataSource.parse(
            RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/capabilities.json"),
            RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/controls.json"),
            meta,
            logos,
        )
        assertTrue(snapshot.brandNames.keys.any { it.startsWith("ver:") } || snapshot.brandNames.containsKey("laika"))
        assertTrue(snapshot.brandNames.isNotEmpty())
    }
}
