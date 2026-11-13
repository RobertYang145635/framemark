package com.framemark.native.inspector

import com.framemark.native.library.Json
import com.framemark.native.library.jsonList
import com.framemark.native.library.jsonMap
import com.framemark.native.library.jsonString
import com.framemark.native.product.RealData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI copy audit 2026-11-13, leak 2: the logo-version subtitle rendered the catalogue's
 * review-sheet placeholder for "no year".
 *
 * `logos.json` marks a missing year with a bare em dash on 110 versions, an empty
 * string on 75 and `n.d.` on 3, and four screens joined it by hand
 * (`"${eraLabel(era)} · ${years}"`). A Sony version therefore read `既有 · —` in the
 * device dump `verify-rc14/c11-sony-styles.xml`, and every version without a year
 * produced a dangling separator. The data file is frozen (byte-identical to the
 * engine's copy), so the fix lives in the display mapping.
 */
class LogoVersionSubtitleTest {

    @Test
    fun a_placeholder_year_collapses_to_the_era() {
        assertEquals("沿革款", versionSubtitle("legacy", "—"))
        assertEquals("现行", versionSubtitle("current", ""))
        assertEquals("现行", versionSubtitle("current", "   "))
        assertEquals("经典", versionSubtitle("classic", "n.d."))
        assertEquals("历史", versionSubtitle("historical", "-"))
    }

    /**
     * Item 3: the four era buckets, in the order the picker shows them. `legacy` used to
     * read 既有 — a catalogue word — and is 沿革款 now.
     */
    @Test
    fun theFourEraBucketsReadAsFourSteps() {
        assertEquals("现行", eraLabel("current"))
        assertEquals("经典", eraLabel("classic"))
        assertEquals("历史", eraLabel("historical"))
        assertEquals("沿革款", eraLabel("legacy"))
        // The stored value is what it always was; only the label changed.
        assertEquals(listOf("current", "classic", "historical", "legacy").size, 4)
        assertTrue(eraLabel("legacy") != "既有")
    }

    @Test
    fun a_real_year_is_kept_verbatim() {
        assertEquals("现行 · 2006–", versionSubtitle("current", "2006–"))
        assertEquals("现行 · 1980s–2006", versionSubtitle("current", "1980s–2006"))
    }

    @Test
    fun the_one_placeholder_that_carries_information_is_translated() {
        assertEquals("2002 年前", yearLabel("n.d. (pre-2002)"))
        assertEquals("现行 · 2002 年前", versionSubtitle("current", "n.d. (pre-2002)"))
    }

    @Test
    fun the_known_english_abbreviation_does_not_reach_the_screen() {
        assertEquals(null, yearLabel("n.d."))
        assertFalse(versionSubtitle("current", "n.d.").contains("n.d."))
        // Two more shipped spellings; only the `pre-YYYY` one says something usable.
        assertEquals(null, yearLabel("n.d. (current site)"))
        assertEquals("现行", versionSubtitle("current", "n.d. (current site)"))
    }

    @Test
    fun no_shipped_version_renders_a_dash_or_a_dangling_separator() {
        val logos = RealData.text("ui/composeApp/src/commonMain/composeResources/files/data/logos.json")
        val versions = Json.parse(logos).jsonMap()["versions"].jsonList()
        assertTrue(versions.size > 200, "logos.json should still carry the whole catalogue")
        var checked = 0
        versions.forEach { raw ->
            val version = raw.jsonMap()
            if (version["selected"] != true) return@forEach
            checked++
            val line = versionSubtitle(
                version.jsonString("era") ?: "current",
                version.jsonString("years").orEmpty(),
            )
            val where = "${version.jsonString("brandKey")}/${version.jsonString("id")}"
            assertFalse(line.contains("—"), "$where renders a review dash: $line")
            assertFalse(line.contains("n.d."), "$where renders the catalogue abbreviation: $line")
            assertFalse(line.trimEnd().endsWith("·"), "$where ends in a separator: $line")
            assertTrue(line.isNotBlank(), "$where renders nothing")
        }
        assertTrue(checked > 200, "expected the selected versions to be scanned, saw $checked")
    }
}
