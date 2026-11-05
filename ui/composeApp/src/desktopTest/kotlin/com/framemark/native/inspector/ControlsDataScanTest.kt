package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonInt
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.ControlLabels
import com.framemark.native.product.RealData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-C §4.1 (P0) acceptance over the **shipped** files.
 *
 * - No control's display text may carry `pct` / `multiplier` / `unitless` /
 *   `alpha` / `count`;
 * - one field has exactly one name;
 * - no internal field reaches the UI.
 *
 * `controls.json` and `inspector-meta.json` are the two frozen sources the
 * inspector is built from; the same rules must hold for both, because desktop
 * and phone read the same model.
 */
class ControlsDataScanTest {

    private data class Source(
        val where: String,
        val controlId: String,
        val param: String,
        val label: String,
        val unit: String?,
        val max: Float?,
    )

    private val dataRoot =
        "framemark/ui/composeApp/src/commonMain/composeResources/files/data/"

    private val forbidden = listOf("pct", "multiplier", "unitless", "alpha", "count")

    private fun metaSources(): List<Source> {
        val root = Json.parse(RealData.text("${dataRoot}inspector-meta.json")).jsonMap()
        val out = ArrayList<Source>()
        for (record in root["records"].jsonList()) {
            val map = record.jsonMap()
            val styleId = map.jsonString("styleId") ?: "?"
            for (raw in map["controls"].jsonList()) {
                val c = raw.jsonMap()
                val domain = c["domain"].jsonMap()
                out += Source(
                    where = "inspector-meta/$styleId",
                    controlId = c.jsonString("id") ?: "",
                    param = c.jsonString("param") ?: "",
                    label = c.jsonString("label") ?: "",
                    unit = domain.jsonString("unit") ?: c.jsonString("unit"),
                    max = (domain["max"] as? Number)?.toFloat(),
                )
            }
        }
        return out
    }

    private fun rowSources(): List<Source> {
        val root = Json.parse(RealData.text("${dataRoot}controls.json")).jsonMap()
        val out = ArrayList<Source>()
        for (raw in root["rows"].jsonList()) {
            val r = raw.jsonMap()
            val domain = r["domain"].jsonMap()
            out += Source(
                where = "controls/${r.jsonString("styleId") ?: "?"}",
                controlId = r.jsonString("controlId") ?: "",
                param = r.jsonString("param") ?: "",
                label = r.jsonString("label") ?: "",
                unit = domain.jsonString("unit") ?: r.jsonString("unit"),
                max = (domain["max"] as? Number)?.toFloat(),
            )
        }
        return out
    }

    @Test
    fun everyShippedControlHasAHumanNameOrIsDropped() {
        val sources = metaSources() + rowSources()
        assertTrue(sources.size > 500, "expected the full shipped control set, got ${sources.size}")
        var visible = 0
        var dropped = 0
        for (source in sources) {
            val label = ControlLabels.forControl(source.label, source.controlId, source.param)
            if (label == null) {
                dropped++
                // A dropped control must be internal bookkeeping, never a real field.
                assertTrue(
                    ControlLabels.isInternal(source.param) || !source.label.any { it in '\u4e00'..'\u9fff' },
                    "${source.where} ${source.controlId}: a Chinese label must not be dropped",
                )
                continue
            }
            visible++
            assertTrue(label.isNotBlank(), "${source.where} ${source.controlId}: blank label")
            // Raw identifiers never reach the UI.
            assertFalse(
                Regex("^[a-z][A-Za-z0-9_]*$").matches(label),
                "${source.where} ${source.controlId}: identifier leaked as label '$label'",
            )
            assertTrue(
                label.any { it in '\u4e00'..'\u9fff' } || label == "Logo" || label == "H2",
                "${source.where} ${source.controlId}: label '$label' is not human copy",
            )
            assertFalse(label.contains('{') || label.contains('}'), "${source.where}: template leaked")
        }
        assertTrue(visible > 400, "most controls must stay visible (visible=$visible dropped=$dropped)")
    }

    @Test
    fun noDisplayTextEverContainsARawUnitToken() {
        val sources = metaSources() + rowSources()
        for (source in sources) {
            val label = ControlLabels.forControl(source.label, source.controlId, source.param) ?: continue
            val suffix = FmUnits.suffixFor(source.unit, source.max, source.param)
            val sampleValue = 0.594f
            val text = label + " " + FmUnits.displayText(sampleValue, suffix, FmUnits.scaleFor(source.unit, source.max, source.param)) +
                " " + FmUnits.formatNumber(12.5f) + (suffix ?: "")
            for (token in forbidden) {
                assertFalse(
                    text.contains(token, ignoreCase = true),
                    "${source.where} ${source.controlId}: display text '$text' contains raw token '$token'",
                )
            }
            // The suffix itself must never be an engine token.
            if (suffix != null) {
                assertFalse(
                    forbidden.any { it.equals(suffix, ignoreCase = true) },
                    "${source.where}: suffix '$suffix' is a raw token",
                )
            }
        }
    }

    @Test
    fun oneFieldHasExactlyOneName() {
        val sources = metaSources() + rowSources()
        val byField = LinkedHashMap<String, MutableSet<String>>()
        val byFieldWhere = HashMap<String, String>()
        for (source in sources) {
            val field = ControlLabels.fieldOf(source.param)
            // Renderer row labels are per-node ("编辑「modelLine」文字") and the
            // inspector replaces them with 替换文字 / 显示文字 / 文字大小.
            if (field.startsWith("slot")) continue
            val label = ControlLabels.forControl(source.label, source.controlId, source.param) ?: continue
            byField.getOrPut(field) { LinkedHashSet() }.add(label)
            if (source.param.isNotBlank()) byFieldWhere.putIfAbsent(field, source.where)
        }
        val conflicts = byField.filterValues { it.size > 1 }
        assertTrue(
            conflicts.isEmpty(),
            "fields with more than one name: " +
                conflicts.entries.joinToString { "${it.key} → ${it.value}" },
        )
        // Guard against an empty scan passing this test by accident.
        assertTrue(byField.size > 30, "expected many distinct fields, got ${byField.size}")
        // Spot-check the named P0 merges.
        assertEquals(setOf("照片留白"), byField["marginPct"]?.toSet() ?: emptySet())
        assertEquals(setOf("底色"), byField["theme"]?.toSet() ?: emptySet())
        assertEquals(setOf("版式"), byField["footerMode"]?.toSet() ?: emptySet())
        assertEquals(setOf("Logo 大小"), byField["logoScale"]?.toSet() ?: emptySet())
        assertEquals(setOf("水平位置"), byField["logoCenterX"]?.toSet() ?: emptySet())
        assertEquals(setOf("显示 Logo"), byField["showLogo"]?.toSet() ?: emptySet())
        assertEquals(setOf("模糊强度"), byField["blur"]?.toSet() ?: emptySet())
    }

    @Test
    fun alphaIsScaledOnlyWhenItsDeclaredMaximumIsAnOpacity() {
        val sources = metaSources() + rowSources()
        for (source in sources.filter { it.unit == "alpha" }) {
            val scale = FmUnits.scaleFor(source.unit, source.max, source.param)
            if (source.max == null || source.max <= 1.5f) {
                assertEquals(100f, scale, "${source.where} ${source.controlId}: alpha max ${source.max}")
                assertEquals("%", FmUnits.suffixFor(source.unit, source.max, source.param))
            } else {
                assertEquals(1f, scale)
            }
        }
    }

    @Test
    fun countRendersAsGe() {
        val sources = metaSources() + rowSources()
        val counts = sources.filter { it.unit == "count" }
        assertTrue(counts.isNotEmpty(), "the shipped data has a count control")
        for (source in counts) {
            assertEquals("个", FmUnits.suffixFor(source.unit, source.max, source.param))
        }
    }

    @Test
    fun internalFooterSlotSelectionNeverShows() {
        val sources = metaSources() + rowSources()
        val internal = sources.filter { ControlLabels.fieldOf(it.param) == "slots" }
        assertTrue(internal.isNotEmpty(), "the shipped data still declares params.slots")
        for (source in internal) {
            assertTrue(ControlLabels.isInternal(source.param))
            assertEquals(
                null,
                ControlLabels.forControl(source.label, source.controlId, source.param),
                "${source.where}: params.slots must stay hidden",
            )
        }
    }

    @Test
    fun everyRowThatRendersHasANumberNotAJsonBlob() {
        // The unit scan above covers text; this guards the row default rendering.
        val root = Json.parse(RealData.text("${dataRoot}controls.json")).jsonMap()
        var checked = 0
        for (raw in root["rows"].jsonList()) {
            val r = raw.jsonMap()
            val domain = r["domain"].jsonMap()
            val max = domain["max"] as? Number
            if (max == null) continue
            val unit = domain.jsonString("unit")
            val suffix = FmUnits.suffixFor(unit, max.toFloat(), r.jsonString("param") ?: "")
            val text = FmUnits.displayText(max.toFloat(), suffix, FmUnits.scaleFor(unit, max.toFloat(), r.jsonString("param") ?: ""))
            assertFalse(text.contains('{') || text.contains('['), "row ${r.jsonString("rowId")}: $text")
            checked++
        }
        assertTrue(checked > 50, "expected numeric rows, got $checked")
    }
}
